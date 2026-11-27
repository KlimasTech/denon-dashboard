package org.eu.klimastech.denon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Wbudowany "backend" aplikacji - odpowiednik app/main.py (FastAPI) z serwera,
 * ale dzialajacy w telefonie: trwale polaczenie telnet (port 23) wprost z
 * amplitunerem Denon + proste odczyty HTTP (UPnP, panel producenta). Strona
 * (index.html) rozmawia z nim przez te same sciezki /api/* i "WebSocket" /ws,
 * podmienione w app-shim.js na most JavaScript <-> Java. Zero serwera posredniego.
 */
public class AvrBackend {
    public interface Listener {
        void onState(String json);
    }

    private static final int TELNET_PORT = 23;
    /** Port polaczenia telnet; domyslnie TELNET_PORT (zmieniany tylko w testach). */
    static volatile int portPolaczenia = TELNET_PORT;
    private static final int CONFIG_API_PORT = 10443;   // panel ustawien producenta (HTTPS, XML)
    private static final int UPNP_DESC_PORT = 60006;    // UPnP - dokladny model i firmware

    private static final String[][] SOURCES = {
            {"PHONO", "Gramofon"}, {"CD", "CD"}, {"TUNER", "Tuner"}, {"DVD", "DVD"},
            {"BD", "Blu-ray"}, {"TV", "TV"}, {"SAT/CBL", "SAT/Kabel"}, {"MPLAY", "Media Player"},
            {"GAME", "Konsola"}, {"AUX1", "AUX"}, {"NET", "Siec/HEOS"}, {"TAPE", "Tape"},
            {"SHIELD", "Shield"}, {"BT", "Bluetooth"},
    };

    // nazwy z panelu ustawien producenta (SourceList) -> kody telnet SI<kod>; znormalizowane: upper(), bez spacji
    private static final Map<String, String> NAME_TO_CODE = new HashMap<>();
    static {
        String[][] m = {
                {"PHONO", "PHONO"}, {"CD", "CD"}, {"TUNER", "TUNER"}, {"DVD", "DVD"}, {"BD", "BD"},
                {"BLU-RAY", "BD"}, {"TV", "TV"}, {"SAT/CBL", "SAT/CBL"}, {"SATCBL", "SAT/CBL"},
                {"MEDIAPLAYER", "MPLAY"}, {"MPLAY", "MPLAY"}, {"GAME", "GAME"}, {"AUX", "AUX1"},
                {"AUX1", "AUX1"}, {"NET", "NET"}, {"NET/USB", "NET"}, {"HEOSMUSIC", "NET"},
                {"NETWORK", "NET"}, {"TAPE", "TAPE"}, {"SHIELD", "SHIELD"}, {"BT", "BT"},
                {"BLUETOOTH", "BT"},
        };
        for (String[] p : m) NAME_TO_CODE.put(p[0], p[1]);
    }

    private final SharedPreferences prefs;
    private final Listener listener;

    // stan amplitunera (jak CONFIG/state w main.py)
    private final Object stateLock = new Object();
    private String power, mute, source, zoneMain;
    private Double volume;
    private Integer volumeMax;
    private String model, firmware;

    private volatile String host;
    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile Socket socket;
    private volatile int generation = 0;
    private Thread loop;

    public AvrBackend(Context ctx, Listener listener) {
        this.prefs = ctx.getSharedPreferences("denon_backend", Context.MODE_PRIVATE);
        this.listener = listener;
        this.host = prefs.getString("avr_host", "");
        if (prefs.contains("last_volume")) volume = (double) prefs.getFloat("last_volume", 0f);
        if (prefs.contains("last_volume_max")) volumeMax = prefs.getInt("last_volume_max", 60);
        model = prefs.getString("last_model", null);
        firmware = prefs.getString("last_fw", null);
    }

    // ───────────────────────── cykl zycia ─────────────────────────

    public void start() {
        stopped = false;
        loop = new Thread(this::petla, "denon-telnet");
        loop.setDaemon(true);
        loop.start();
        odswiezInfo();
    }

    public void stop() {
        stopped = true;
        zamknijSocket();
    }

    /** Ponowne odpytanie o stan (np. po powrocie do aplikacji). */
    public void refresh() {
        new Thread(() -> { try { odswiezStan(); } catch (Exception ignored) { } }).start();
    }

    private void zamknijSocket() {
        Socket s = socket;
        socket = null;
        connected = false;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private void petla() {
        while (!stopped) {
            String h = host;
            if (h == null || h.isEmpty()) {
                spij(1000);
                continue;
            }
            int gen = generation;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(h, portPolaczenia), 5000);
                socket = s;
                connected = true;
                new Thread(() -> { try { spij(100); odswiezStan(); } catch (Exception ignored) { } }).start();
                nasluchuj(s, gen);
            } catch (IOException ignored) {
                // nie udalo sie polaczyc / polaczenie zerwane - ponowna proba ponizej
            }
            connected = false;
            if (!stopped) spij(3000);
        }
    }

    private void nasluchuj(Socket s, int gen) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while (!stopped && gen == generation && (b = in.read()) != -1) {
            if (b == '\r') {
                String linia = buf.toString("UTF-8").trim();
                buf.reset();
                if (!linia.isEmpty()) {
                    zastosuj(linia);
                    rozgloszStan();
                }
            } else {
                buf.write(b);
            }
        }
    }

    private static void spij(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void odswiezStan() {
        for (String q : new String[]{"PW?", "MV?", "MU?", "SI?", "ZM?"}) {
            wyslij(q);
            spij(150);
        }
    }

    private synchronized boolean wyslij(String komenda) {
        Socket s = socket;
        if (s == null || !connected) return false;
        try {
            OutputStream out = s.getOutputStream();
            out.write((komenda + "\r").getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (IOException e) {
            zamknijSocket();
            return false;
        }
    }

    // ───────────────────────── stan (port _apply z main.py) ─────────────────────────

    private void zastosuj(String t) {
        synchronized (stateLock) {
            if (t.startsWith("PWON")) power = "ON";
            else if (t.startsWith("PWSTANDBY")) power = "STANDBY";
            else if (t.startsWith("MVMAX")) {
                try {
                    String[] cz = t.split("\\s+");
                    volumeMax = Integer.parseInt(cz[cz.length - 1]);
                    prefs.edit().putInt("last_volume_max", volumeMax).apply();
                } catch (NumberFormatException ignored) { }
            } else if (t.startsWith("MV") && t.length() <= 6) {
                String raw = t.substring(2);
                try {
                    // 2 cyfry = liczba calkowita (MV40 -> 40), 3 cyfry = krok polowkowy (MV265 -> 26.5)
                    if (raw.length() == 2) volume = (double) Integer.parseInt(raw);
                    else if (raw.length() == 3) volume = Integer.parseInt(raw) / 10.0;
                    else throw new NumberFormatException("dlugosc MV");
                    prefs.edit().putFloat("last_volume", (float) (double) volume).apply();
                } catch (NumberFormatException ignored) { }
            } else if (t.startsWith("MUON")) mute = "ON";
            else if (t.startsWith("MUOFF")) mute = "OFF";
            else if (t.startsWith("SI")) source = t.substring(2);
            else if (t.startsWith("ZMON")) zoneMain = "ON";
            else if (t.startsWith("ZMOFF")) zoneMain = "OFF";
        }
    }

    private JSONObject stanJson() {
        JSONObject st = new JSONObject();
        synchronized (stateLock) {
            try {
                st.put("power", power == null ? JSONObject.NULL : power);
                st.put("volume", volume == null ? JSONObject.NULL : volume);
                st.put("volume_max", volumeMax == null ? JSONObject.NULL : volumeMax);
                st.put("mute", mute == null ? JSONObject.NULL : mute);
                st.put("source", source == null ? JSONObject.NULL : source);
                st.put("zone_main", zoneMain == null ? JSONObject.NULL : zoneMain);
            } catch (JSONException ignored) { }
        }
        return st;
    }

    /** Wiadomosc dla "WebSocketu" strony: {"type":"state","state":{...}}. */
    public String stanWiadomosc() {
        try {
            return new JSONObject().put("type", "state").put("state", stanJson()).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void rozgloszStan() {
        listener.onState(stanWiadomosc());
    }

    // ───────────────────────── odczyty HTTP z amplitunera ─────────────────────────

    private void odswiezInfo() {
        final String h = host;
        if (h == null || h.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + ":" + UPNP_DESC_PORT
                        + "/upnp/desc/aios_device/aios_device.xml").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                String txt = czytaj(c.getInputStream());
                Matcher mm = Pattern.compile("<modelName>(.*?)</modelName>").matcher(txt);
                Matcher fm = Pattern.compile("<firmware_version>(.*?)</firmware_version>").matcher(txt);
                if (mm.find()) {
                    model = mm.group(1).trim();
                    firmware = fm.find() ? fm.group(1).trim() : null;
                    prefs.edit().putString("last_model", model).putString("last_fw", firmware).apply();
                }
            } catch (Exception ignored) {
                // offline - zostaje ostatnia znana wartosc
            }
        }, "denon-info").start();
    }

    private static String czytaj(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    /** Zrodla faktycznie skonfigurowane w AVR (panel producenta, HTTPS z certyfikatem self-signed) albo null. */
    private Set<String> wlaczoneZrodla() {
        try {
            TrustManager[] ufaj = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, ufaj, new SecureRandom());
            HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + ":" + CONFIG_API_PORT
                    + "/ajax/globals/get_config?type=7").openConnection();
            // zaufanie ograniczone do tego jednego polaczenia z amplitunerem w sieci lokalnej
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((HostnameVerifier) (h, s) -> true);
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            byte[] xml = czytaj(c.getInputStream()).getBytes("UTF-8");
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml));
            Set<String> kody = new HashSet<>();
            NodeList strefy = doc.getElementsByTagName("Zone");
            for (int i = 0; i < strefy.getLength(); i++) {
                Element z = (Element) strefy.item(i);
                if (!"1".equals(z.getAttribute("zone"))) continue;
                NodeList zr = z.getElementsByTagName("Source");
                for (int j = 0; j < zr.getLength(); j++) {
                    NodeList nazwa = ((Element) zr.item(j)).getElementsByTagName("Name");
                    if (nazwa.getLength() == 0) continue;
                    String key = nazwa.item(0).getTextContent().trim().toUpperCase(Locale.ROOT).replace(" ", "");
                    String kod = NAME_TO_CODE.get(key);
                    if (kod != null) kody.add(kod);
                }
            }
            return kody.isEmpty() ? null : kody;
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── API (/api/*) ─────────────────────────

    /** Obsluga zapytania strony; wolane z watku roboczego (moze blokowac na sieci). Zwraca JSON. */
    public String obsluz(String metoda, String sciezka, String body) {
        try {
            String path = sciezka;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/api/config") && metoda.equals("GET")) return pobierzKonfig();
            if (path.equals("/api/config") && metoda.equals("POST")) return zapiszKonfig(body);
            if (path.equals("/api/state")) return stanJson().toString();
            if (path.equals("/api/device_info")) return infoJson();
            if (path.equals("/api/sources")) return zrodla();
            if (metoda.equals("POST")) {
                if (path.startsWith("/api/power/")) {
                    boolean ok = wyslij(path.endsWith("/on") ? "PWON" : "PWSTANDBY");
                    return ok ? "{\"ok\":true}" : blad("Brak połączenia z amplitunerem");
                }
                if (path.startsWith("/api/mute/")) {
                    if (!"ON".equals(power)) return blad("Zasilanie wylaczone");
                    wyslij(path.endsWith("/on") ? "MUON" : "MUOFF");
                    return "{\"ok\":true}";
                }
                if (path.startsWith("/api/volume_step/")) {
                    if (!"ON".equals(power)) return blad("Zasilanie wylaczone");
                    wyslij(path.endsWith("/up") ? "MVUP" : "MVDOWN");
                    return "{\"ok\":true}";
                }
                if (path.startsWith("/api/volume/")) {
                    if (!"ON".equals(power)) return blad("Zasilanie wylaczone");
                    double lvl = Double.parseDouble(path.substring("/api/volume/".length()));
                    int max = volumeMax != null ? volumeMax : prefs.getInt("last_volume_max", 60);
                    lvl = Math.max(0, Math.min(lvl, max));
                    wyslij(String.format(Locale.ROOT, "MV%03d", Math.round(lvl * 10)));
                    return "{\"ok\":true}";
                }
                if (path.startsWith("/api/source/")) {
                    if (!"ON".equals(power)) return blad("Zasilanie wylaczone");
                    String kod = java.net.URLDecoder.decode(path.substring("/api/source/".length()), "UTF-8");
                    wyslij("SI" + kod);
                    return "{\"ok\":true}";
                }
            }
            return blad("Nieznane zapytanie");
        } catch (Exception e) {
            return blad(String.valueOf(e.getMessage()));
        }
    }

    private static String blad(String m) {
        try {
            return new JSONObject().put("ok", false).put("error", m).toString();
        } catch (JSONException e) {
            return "{\"ok\":false}";
        }
    }

    private String infoJson() throws JSONException {
        return new JSONObject()
                .put("model", model == null ? JSONObject.NULL : model)
                .put("firmware", firmware == null ? JSONObject.NULL : firmware).toString();
    }

    private String zrodla() throws JSONException {
        Set<String> wlaczone = null;
        if (prefs.getBoolean("hide_disabled_sources", false) && host != null && !host.isEmpty()) {
            wlaczone = wlaczoneZrodla();   // null = nie udalo sie sprawdzic -> pokaz wszystko
        }
        JSONArray arr = new JSONArray();
        for (String[] s : SOURCES) {
            if (wlaczone != null && !wlaczone.contains(s[0])) continue;
            arr.put(new JSONObject().put("code", s[0]).put("label", s[1]));
        }
        return arr.toString();
    }

    private String pobierzKonfig() throws JSONException {
        JSONObject o = new JSONObject()
                .put("avr_host", host == null ? "" : host)
                .put("avr_port", TELNET_PORT)
                .put("device_name", prefs.getString("device_name", ""))
                .put("hide_disabled_sources", prefs.getBoolean("hide_disabled_sources", false))
                .put("volume_display", prefs.getString("volume_display", "scale"))
                .put("config_panel_port", CONFIG_API_PORT)
                .put("connected", connected);
        if (prefs.contains("last_volume")) o.put("default_volume", prefs.getFloat("last_volume", 0f));
        else o.put("default_volume", JSONObject.NULL);
        o.put("default_volume_max", prefs.getInt("last_volume_max", 60));
        return o.toString();
    }

    private String zapiszKonfig(String body) throws JSONException {
        JSONObject p = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        String nowy = p.optString("avr_host", "").trim();
        if (nowy.isEmpty()) return blad("Brak adresu IP");
        SharedPreferences.Editor e = prefs.edit();
        e.putString("avr_host", nowy);
        if (p.has("device_name")) {
            String n = p.optString("device_name", "").trim();
            e.putString("device_name", n.length() > 40 ? n.substring(0, 40) : n);
        }
        if (p.has("hide_disabled_sources")) e.putBoolean("hide_disabled_sources", p.optBoolean("hide_disabled_sources"));
        String vd = p.optString("volume_display", "");
        if (vd.equals("scale") || vd.equals("db")) e.putString("volume_display", vd);
        e.apply();
        if (!nowy.equals(host)) przelaczHost(nowy);
        return "{\"ok\":true}";
    }

    /** Zamyka obecne polaczenie i laczy z nowym AVR (jak switch_host w main.py). */
    private void przelaczHost(String nowy) {
        host = nowy;
        generation++;
        zamknijSocket();
        synchronized (stateLock) {
            power = mute = source = zoneMain = null;
            volume = null;
            volumeMax = null;
        }
        rozgloszStan();
        odswiezInfo();
    }
}
