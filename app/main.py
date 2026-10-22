"""Denon AVR dashboard — mały backend (FastAPI) tlumaczacy HTTP/WebSocket
na klasyczny protokol telnet sterowania Denon/Marantz (port 23)."""
import asyncio
import json
import os
import re
import socket
import time
import xml.etree.ElementTree as ET
from contextlib import suppress
from pathlib import Path

import httpx
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

DATA_DIR = Path(os.environ.get("DATA_DIR", "/app/data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
CONFIG_PATH = DATA_DIR / "config.json"

CONFIG_API_PORT = 10443  # panel ustawien producenta (AJAX/XML), zawsze na tym porcie
UPNP_DESC_PORT = 60006   # UPnP/AiosDevice — opis urzadzenia z dokladnym modelem i FW
TELNET_PORT = 23         # klasyczny protokol sterowania — zawsze ten sam port, nie konfigurowalny

DEVICE_INFO: dict = {"model": None, "firmware": None}


async def fetch_device_info(host: str) -> dict | None:
    """Czyta UPnP device description (port 60006) — jedyne miejsce gdzie AVR
    podaje pelna nazwe modelu (np. 'Denon AVR-X2700H') i czytelny numer
    wersji firmware (np. '3.139.173'), w odroznieniu od wewnetrznego
    build-stringa z panelu ustawien (type=12 Information)."""
    url = f"http://{host}:{UPNP_DESC_PORT}/upnp/desc/aios_device/aios_device.xml"
    try:
        async with httpx.AsyncClient(timeout=6) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            text = resp.text
    except Exception:
        return None
    model_m = re.search(r"<modelName>(.*?)</modelName>", text)
    fw_m = re.search(r"<firmware_version>(.*?)</firmware_version>", text)
    if not model_m:
        return None
    return {
        "model": model_m.group(1).strip(),
        "firmware": fw_m.group(1).strip() if fw_m else None,
    }


def load_config() -> dict:
    if CONFIG_PATH.exists():
        with suppress(Exception):
            cfg = json.loads(CONFIG_PATH.read_text())
            cfg.setdefault("hide_disabled_sources", False)
            cfg.setdefault("volume_display", "scale")  # "scale" (0-99) albo "db"
            cfg["avr_port"] = TELNET_PORT  # port zawsze zaszyty, niezaleznie od starych zapisow
            return cfg
    return {
        "avr_host": os.environ.get("AVR_HOST", "10.10.4.7"),
        "avr_port": TELNET_PORT,
        "hide_disabled_sources": False,
        "volume_display": "scale",
    }


def save_config(cfg: dict):
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))


CONFIG = load_config()

app = FastAPI(title="Denon AVR Dashboard")

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


# ───────────────────────── Denon telnet client ─────────────────────────

SOURCES = [
    ("PHONO", "Gramofon"),
    ("CD", "CD"),
    ("TUNER", "Tuner"),
    ("DVD", "DVD"),
    ("BD", "Blu-ray"),
    ("TV", "TV"),
    ("SAT/CBL", "SAT/Kabel"),
    ("MPLAY", "Media Player"),
    ("GAME", "Konsola"),
    ("AUX1", "AUX"),
    ("NET", "Siec/HEOS"),
    ("TAPE", "Tape"),
    ("SHIELD", "Shield"),
    ("BT", "Bluetooth"),
]

# Mapowanie nazw z panelu ustawien producenta (SourceList, type=7) na kody
# telnet uzywane przez SI<kod>. Nazwy znormalizowane: upper(), bez spacji.
SOURCE_NAME_TO_CODE = {
    "PHONO": "PHONO",
    "CD": "CD",
    "TUNER": "TUNER",
    "DVD": "DVD",
    "BD": "BD",
    "BLU-RAY": "BD",
    "TV": "TV",
    "SAT/CBL": "SAT/CBL",
    "SATCBL": "SAT/CBL",
    "MEDIAPLAYER": "MPLAY",
    "MPLAY": "MPLAY",
    "GAME": "GAME",
    "AUX": "AUX1",
    "AUX1": "AUX1",
    "NET": "NET",
    "NET/USB": "NET",
    "HEOSMUSIC": "NET",
    "NETWORK": "NET",
    "TAPE": "TAPE",
    "SHIELD": "SHIELD",
    "BT": "BT",
    "BLUETOOTH": "BT",
}


async def fetch_enabled_source_codes(host: str) -> set[str] | None:
    """Pyta panel ustawien producenta (port 10443) o liste zrodel faktycznie
    skonfigurowanych w AVR (SourceList, Zone 1 / Main Zone). Zwraca None przy
    bledzie (np. urzadzenie offline) — wywolujacy powinien wtedy nic nie filtrowac."""
    url = f"https://{host}:{CONFIG_API_PORT}/ajax/globals/get_config"
    try:
        async with httpx.AsyncClient(verify=False, timeout=6) as client:
            resp = await client.get(url, params={"type": 7})
            resp.raise_for_status()
            root = ET.fromstring(resp.text)
    except Exception:
        return None

    codes = set()
    for zone in root.findall(".//Zone"):
        if zone.get("zone") != "1":
            continue
        for src in zone.findall("Source"):
            name_el = src.find("Name")
            if name_el is None or not name_el.text:
                continue
            key = name_el.text.strip().upper().replace(" ", "")
            code = SOURCE_NAME_TO_CODE.get(key)
            if code:
                codes.add(code)
    return codes or None


class DenonClient:
    """Trwałe polaczenie telnet z AVR; broadcastuje zmiany do wszystkich WS klientow."""

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.reader: asyncio.StreamReader | None = None
        self.writer: asyncio.StreamWriter | None = None
        self.state = {
            "power": None,       # ON / STANDBY
            # Głośność i jej maksimum startują z ostatniej znanej wartości
            # zapisanej w config.json — póki AVR nie odpowie na MV?/MVMAX,
            # UI pokazuje sensowny domyślny poziom zamiast "—"/0.
            "volume": CONFIG.get("last_volume"),
            "volume_max": CONFIG.get("last_volume_max"),
            "mute": None,        # ON / OFF
            "source": None,
            "zone_main": None,   # ON / OFF
        }
        self.listeners: set[WebSocket] = set()
        self._lock = asyncio.Lock()
        self._connected_event = asyncio.Event()
        self._listen_task: asyncio.Task | None = None
        self._stop = False

    async def connect(self):
        self._stop = False
        while not self._stop:
            try:
                self.reader, self.writer = await asyncio.open_connection(self.host, self.port)
                self._connected_event.set()
                await self.refresh_all()
                self._listen_task = asyncio.create_task(self._listen())
                return
            except OSError:
                self._connected_event.clear()
                await asyncio.sleep(3)

    async def switch_host(self, host: str, port: int):
        """Zamyka obecne polaczenie i laczy sie z nowym AVR."""
        self._stop = True
        self._connected_event.clear()
        with suppress(Exception):
            if self._listen_task:
                self._listen_task.cancel()
        with suppress(Exception):
            if self.writer:
                self.writer.close()
        self.host, self.port = host, port
        for key in self.state:
            self.state[key] = None
        await self._broadcast()
        asyncio.create_task(self.connect())

    async def _listen(self):
        try:
            while True:
                line = await self.reader.readuntil(b"\r")
                text = line.decode(errors="ignore").strip()
                if text:
                    self._apply(text)
                    await self._broadcast()
        except (asyncio.IncompleteReadError, ConnectionResetError, OSError):
            self._connected_event.clear()
            await asyncio.sleep(2)
            await self.connect()

    def _apply(self, text: str):
        if text.startswith("PWON"):
            self.state["power"] = "ON"
        elif text.startswith("PWSTANDBY"):
            self.state["power"] = "STANDBY"
        elif text.startswith("MVMAX"):
            try:
                self.state["volume_max"] = int(text.split()[-1])
                CONFIG["last_volume_max"] = self.state["volume_max"]
                save_config(CONFIG)
            except ValueError:
                pass
        elif text.startswith("MV") and len(text) <= 6:
            raw = text[2:]
            # Denon: 2 cyfry = liczba calkowita (np. "MV40" -> 40),
            # 3 cyfry = krok polowkowy, ostatnia cyfra to dziesiatki (np. "MV265" -> 26.5)
            with suppress(ValueError):
                if len(raw) == 2:
                    self.state["volume"] = float(int(raw))
                elif len(raw) == 3:
                    self.state["volume"] = int(raw) / 10
                else:
                    raise ValueError("unexpected MV length")
                CONFIG["last_volume"] = self.state["volume"]
                save_config(CONFIG)
        elif text.startswith("MUON"):
            self.state["mute"] = "ON"
        elif text.startswith("MUOFF"):
            self.state["mute"] = "OFF"
        elif text.startswith("SI"):
            self.state["source"] = text[2:]
        elif text.startswith("ZMON"):
            self.state["zone_main"] = "ON"
        elif text.startswith("ZMOFF"):
            self.state["zone_main"] = "OFF"

    async def _broadcast(self):
        dead = []
        for ws in self.listeners:
            try:
                await ws.send_text(json.dumps({"type": "state", "state": self.state}))
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.listeners.discard(ws)

    async def send(self, command: str):
        await self._connected_event.wait()
        async with self._lock:
            self.writer.write(f"{command}\r".encode())
            await self.writer.drain()

    async def refresh_all(self):
        for q in ("PW?", "MV?", "MU?", "SI?", "ZM?"):
            await self.send(q)
            await asyncio.sleep(0.15)


denon = DenonClient(CONFIG["avr_host"], CONFIG["avr_port"])


async def refresh_device_info(host: str):
    info = await fetch_device_info(host)
    if info:
        DEVICE_INFO.update(info)
        CONFIG["last_device_model"] = info["model"]
        CONFIG["last_device_firmware"] = info["firmware"]
        save_config(CONFIG)
    else:
        # offline/niedostepne — uzyj ostatniej znanej wartosci z config.json
        DEVICE_INFO["model"] = DEVICE_INFO["model"] or CONFIG.get("last_device_model")
        DEVICE_INFO["firmware"] = DEVICE_INFO["firmware"] or CONFIG.get("last_device_firmware")


@app.on_event("startup")
async def startup():
    asyncio.create_task(denon.connect())
    asyncio.create_task(refresh_device_info(denon.host))


@app.get("/")
async def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/device_info")
async def device_info():
    return DEVICE_INFO


@app.get("/api/sources")
async def sources():
    all_sources = [{"code": c, "label": l} for c, l in SOURCES]
    if not CONFIG.get("hide_disabled_sources"):
        return all_sources
    enabled = await fetch_enabled_source_codes(denon.host)
    if enabled is None:
        return all_sources  # nie udalo sie sprawdzic — pokaz wszystko, nie chowaj na sile
    return [s for s in all_sources if s["code"] in enabled]


@app.get("/api/state")
async def state():
    return denon.state


@app.get("/api/config")
async def get_config():
    return {
        "avr_host": denon.host,
        "avr_port": denon.port,
        "hide_disabled_sources": CONFIG.get("hide_disabled_sources", False),
        "volume_display": CONFIG.get("volume_display", "scale"),
        "default_volume": CONFIG.get("last_volume"),
        "default_volume_max": CONFIG.get("last_volume_max", 60),
        "config_panel_port": CONFIG_API_PORT,
        "connected": denon._connected_event.is_set(),
    }


@app.post("/api/config")
async def set_config(payload: dict):
    host = str(payload.get("avr_host", "")).strip()
    if not host:
        return {"ok": False, "error": "Brak adresu IP"}
    CONFIG["avr_host"] = host
    CONFIG["avr_port"] = TELNET_PORT
    if "hide_disabled_sources" in payload:
        CONFIG["hide_disabled_sources"] = bool(payload["hide_disabled_sources"])
    if "volume_display" in payload and payload["volume_display"] in ("scale", "db"):
        CONFIG["volume_display"] = payload["volume_display"]
    save_config(CONFIG)
    await denon.switch_host(host, TELNET_PORT)
    asyncio.create_task(refresh_device_info(host))
    return {"ok": True}


@app.post("/api/power/{value}")
async def power(value: str):
    await denon.send("PWON" if value == "on" else "PWSTANDBY")
    return {"ok": True}


@app.post("/api/mute/{value}")
async def mute(value: str):
    if denon.state.get("power") != "ON":
        return {"ok": False, "error": "Zasilanie wylaczone"}
    await denon.send("MUON" if value == "on" else "MUOFF")
    return {"ok": True}


@app.post("/api/volume/{level}")
async def volume(level: float):
    if denon.state.get("power") != "ON":
        return {"ok": False, "error": "Zasilanie wylaczone"}
    level = max(0, min(level, denon.state.get("volume_max") or CONFIG.get("last_volume_max") or 60))
    code = f"{round(level * 10):03d}"
    await denon.send(f"MV{code}")
    return {"ok": True}


@app.post("/api/volume_step/{direction}")
async def volume_step(direction: str):
    if denon.state.get("power") != "ON":
        return {"ok": False, "error": "Zasilanie wylaczone"}
    await denon.send("MVUP" if direction == "up" else "MVDOWN")
    return {"ok": True}


@app.post("/api/source/{code}")
async def source(code: str):
    if denon.state.get("power") != "ON":
        return {"ok": False, "error": "Zasilanie wylaczone"}
    await denon.send(f"SI{code}")
    return {"ok": True}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    denon.listeners.add(ws)
    await ws.send_text(json.dumps({"type": "state", "state": denon.state}))
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        denon.listeners.discard(ws)
