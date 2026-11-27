package org.eu.klimastech.denon;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Denon Pilot - samodzielna aplikacja KlimasTech (bez serwera posredniego).
 *
 * - Pliki strony sa WBUDOWANE w APK (assets/www) i przy pierwszym uruchomieniu
 *   oraz po kazdej aktualizacji kopiowane do pamieci wewnetrznej telefonu
 *   (filesDir/www); stamtad czyta je WebView.
 * - Zadania, ktore robil serwer (telnet do amplitunera, konfiguracja, odczyty
 *   modelu/zrodel), wykonuje AvrBackend w telefonie. Strona rozmawia z nim przez
 *   /api/* i "WebSocket" /ws, podmienione w app-shim.js na most Java.
 * - Adres IP amplitunera wpisuje sie w Ustawieniach strony (jak dotad).
 */
public class MainActivity extends Activity {
    /** Wirtualny adres, pod ktorym WebView widzi lokalne pliki (nic nie wychodzi do sieci). */
    private static final String APP_HOST = "denon-pilot.local";
    private static final String APP_URL = "https://" + APP_HOST + "/";
    private static final int TLO = Color.parseColor("#0F1115");

    private FrameLayout root;
    private WebView web;
    private File wwwDir;
    private AvrBackend backend;
    private final ExecutorService pula = Executors.newCachedThreadPool();

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 17+: dostep do sieci lokalnej (amplituner) wymaga zgody
        if (Build.VERSION.SDK_INT >= 37) {
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 1);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(TLO);
        // targetSdk 35+ wymusza edge-to-edge - odsuwamy zawartosc od pasków systemowych
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        wwwDir = new File(getFilesDir(), "www");
        przygotujPliki();

        web = new WebView(this);
        web.setBackgroundColor(TLO);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Most(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (APP_HOST.equalsIgnoreCase(u.getHost())) return false;
                // linki zewnetrzne (np. "Postaw kawe", panel producenta) - w przegladarce
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return plikLokalny(request);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        backend = new AvrBackend(this, json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__avrPush&&window.__avrPush(" + JSONObject.quote(json) + ")", null);
        }));
        backend.start();
        web.loadUrl(APP_URL);
    }

    /** Most JS <-> Java: zastepuje fetch('/api/...') i WebSocket('/ws') strony (patrz app-shim.js). */
    private class Most {
        @JavascriptInterface
        public void api(final int id, final String metoda, final String sciezka, final String body) {
            pula.execute(() -> {
                final String wynik = backend.obsluz(metoda, sciezka, body);
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript("window.__apiResult(" + id + "," + JSONObject.quote(wynik) + ")", null);
                });
            });
        }

        /** "WebSocket" otwarty - od razu wyslij biezacy stan. */
        @JavascriptInterface
        public void wsOpen() {
            final String msg = backend.stanWiadomosc();
            runOnUiThread(() -> {
                if (web != null) web.evaluateJavascript("window.__avrPush&&window.__avrPush(" + JSONObject.quote(msg) + ")", null);
            });
        }
    }

    // ───────── Pliki strony: assets/www (w APK) -> filesDir/www (pamiec wewnetrzna) ─────────

    private long wersjaAplikacji() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Kopiuje wbudowane pliki do pamieci wewnetrznej przy pierwszym starcie i po kazdej aktualizacji aplikacji. */
    private void przygotujPliki() {
        File znacznik = new File(wwwDir, ".wersja");
        String aktualna = String.valueOf(wersjaAplikacji());
        try {
            if (znacznik.exists()) {
                try (InputStream in = new FileInputStream(znacznik)) {
                    byte[] b = new byte[64];
                    int n = in.read(b);
                    if (n > 0 && new String(b, 0, n).trim().equals(aktualna)) return;
                }
            }
            usunRekurencyjnie(wwwDir);
            kopiujAsset("www", wwwDir);
            try (OutputStream out = new FileOutputStream(znacznik)) {
                out.write(aktualna.getBytes());
            }
        } catch (IOException ignored) {
            // bez plikow strona sie nie zaladuje - plikLokalny zwroci 404
        }
    }

    private void kopiujAsset(String sciezka, File cel) throws IOException {
        String[] dzieci = getAssets().list(sciezka);
        if (dzieci != null && dzieci.length > 0) {
            if (!cel.exists() && !cel.mkdirs()) throw new IOException("mkdirs " + cel);
            for (String d : dzieci) kopiujAsset(sciezka + "/" + d, new File(cel, d));
        } else {
            try (InputStream in = getAssets().open(sciezka); OutputStream out = new FileOutputStream(cel)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private static void usunRekurencyjnie(File f) {
        File[] dzieci = f.listFiles();
        if (dzieci != null) for (File d : dzieci) usunRekurencyjnie(d);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private WebResourceResponse plikLokalny(WebResourceRequest request) {
        Uri u = request.getUrl();
        if (!APP_HOST.equalsIgnoreCase(u.getHost())) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        String rel;
        if (path.isEmpty() || path.equals("/")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else rel = path.substring(1);
        Map<String, String> naglowki = new HashMap<>();
        naglowki.put("Cache-Control", "no-cache");
        if (rel.equals("service-worker.js")) {
            // strona jest lokalna - service worker niepotrzebny; stub wyrejestrowuje sie sam
            String stub = "self.addEventListener('install',()=>self.skipWaiting());"
                    + "self.addEventListener('activate',e=>e.waitUntil(self.registration.unregister()));";
            return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", naglowki,
                    new ByteArrayInputStream(stub.getBytes()));
        }
        File f = new File(wwwDir, rel);
        if (rel.contains("..") || !f.isFile()) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", naglowki,
                    new ByteArrayInputStream(new byte[0]));
        }
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase() : "";
        String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
        try {
            return new WebResourceResponse(mime, "utf-8", 200, "OK", naglowki, new FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (backend != null) backend.refresh();
    }

    @Override
    protected void onDestroy() {
        if (backend != null) backend.stop();
        pula.shutdownNow();
        super.onDestroy();
    }
}
