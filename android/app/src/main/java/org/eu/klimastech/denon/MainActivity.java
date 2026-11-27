package org.eu.klimastech.denon;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Denon Pilot - aplikacja KlimasTech dla Denon AVR Dashboard.
 *
 * Pliki strony (HTML/ikony/manifest) sa WBUDOWANE w APK (assets/www) i przy
 * pierwszym uruchomieniu (oraz po kazdej aktualizacji aplikacji) kopiowane do
 * pamieci wewnetrznej (filesDir/www) - stad czyta je WebView, wiec interfejs
 * otwiera sie od razu, takze gdy serwer jest wylaczony. Strona pozostaje na
 * adresie serwera (ten sam origin), dzieki czemu API i WebSocket (/api/*, /ws)
 * ida normalnie do serwera, bez CORS i bez zmian po stronie backendu.
 *
 * Adres serwera (np. http://192.168.1.10:7777) wpisuje sie przy pierwszym
 * uruchomieniu i mozna go zmienic w Ustawieniach dashboardu (przycisk "Zmien
 * adres serwera") albo z ekranu bledu polaczenia.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "denon_pilot";
    private static final String KEY_URL = "server_url";
    private static final int TLO = Color.parseColor("#0F1115");
    private static final int AKCENT = Color.parseColor("#4DA3FF");
    private static final int TEKST = Color.parseColor("#E8EAED");
    private static final int PRZYGASZONY = Color.parseColor("#9AA0A6");

    private FrameLayout root;
    private WebView web;
    private View nakladka;   // ekran wpisania adresu / bledu polaczenia
    private String serverUrl;
    private File wwwDir;     // pliki strony w pamieci wewnetrznej: filesDir/www

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
        MIME.put("webmanifest", "application/manifest+json");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Most(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                Uri base = Uri.parse(serverUrl == null ? "" : serverUrl);
                boolean swoj = u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost())
                        && u.getPort() == base.getPort();
                if (swoj) return false;
                // linki zewnetrzne (np. "Postaw kawe") - w przegladarce, nie w aplikacji
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            /** GET / i /static/* na adresie serwera -> plik z pamieci wewnetrznej; reszta (API, WS) idzie do serwera. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return plikLokalny(request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pokazNakladke("Nie można połączyć z serwerem:\n" + serverUrl, true);
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        serverUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null);
        if (serverUrl == null) {
            pokazNakladke("Wpisz adres serwera Denon Dashboard.", false);
        } else {
            web.loadUrl(serverUrl);
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
        } catch (IOException e) {
            // brak lokalnych plikow - strona zaladuje sie wtedy z serwera (shouldInterceptRequest zwroci null)
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
        if (serverUrl == null || !"GET".equals(request.getMethod())) return null;
        Uri u = request.getUrl();
        Uri base = Uri.parse(serverUrl);
        if (u.getHost() == null || !u.getHost().equalsIgnoreCase(base.getHost()) || u.getPort() != base.getPort()) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        String rel;
        if (path.isEmpty() || path.equals("/")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else return null;
        if (rel.contains("..")) return null;
        Map<String, String> naglowki = new HashMap<>();
        naglowki.put("Cache-Control", "no-cache");
        if (rel.equals("service-worker.js")) {
            // w aplikacji strona jest lokalna - service worker niepotrzebny; ten stub wyrejestrowuje sie sam
            String stub = "self.addEventListener('install',()=>self.skipWaiting());"
                    + "self.addEventListener('activate',e=>e.waitUntil(self.registration.unregister()));";
            return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", naglowki,
                    new ByteArrayInputStream(stub.getBytes()));
        }
        File f = new File(wwwDir, rel);
        if (!f.isFile()) return null;
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase() : "";
        String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
        try {
            return new WebResourceResponse(mime, "utf-8", 200, "OK", naglowki, new FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    /** Most JS <-> aplikacja: dashboard pokazuje przycisk zmiany adresu tylko w aplikacji. */
    private class Most {
        @JavascriptInterface
        public void openServerSettings() {
            runOnUiThread(() -> pokazNakladke("Zmień adres serwera Denon Dashboard.", false));
        }

        @JavascriptInterface
        public String getServerUrl() {
            return serverUrl == null ? "" : serverUrl;
        }
    }

    private static String normalizuj(String wpis) {
        String t = wpis.trim();
        if (t.isEmpty()) return null;
        if (!t.matches("(?i)^https?://.*")) t = "http://" + t;
        Uri u = Uri.parse(t);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    private TextView tekst(String t, int rozmiar, int kolor) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(rozmiar);
        tv.setTextColor(kolor);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button przycisk(String t, boolean glowny) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(glowny ? Color.parseColor("#0F1115") : TEKST);
        b.setBackgroundColor(glowny ? AKCENT : Color.parseColor("#2A2F38"));
        return b;
    }

    /** Ekran wpisania adresu serwera (blad=false) albo bledu polaczenia (blad=true). */
    private void pokazNakladke(String komunikat, boolean blad) {
        if (nakladka != null) root.removeView(nakladka);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(TLO);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);
        box.setClickable(true);

        box.addView(tekst("🎛 Denon Pilot", 26, TEKST));
        TextView opis = tekst(komunikat, 16, blad ? Color.parseColor("#FF6B6B") : PRZYGASZONY);
        opis.setPadding(0, p / 2, 0, p / 2);
        box.addView(opis);

        final EditText pole = new EditText(this);
        pole.setHint("np. http://192.168.1.10:7777");
        pole.setHintTextColor(PRZYGASZONY);
        pole.setTextColor(TEKST);
        pole.setSingleLine(true);
        pole.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pole.setImeOptions(EditorInfo.IME_ACTION_GO);
        if (serverUrl != null) pole.setText(serverUrl);
        box.addView(pole, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView err = tekst("", 14, Color.parseColor("#FF6B6B"));
        box.addView(err);

        Button polacz = przycisk("Połącz", true);
        Runnable zatwierdz = () -> {
            String u = normalizuj(pole.getText().toString());
            if (u == null) {
                err.setText("Podaj poprawny adres, np. http://192.168.1.10:7777");
                return;
            }
            serverUrl = u;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, u).apply();
            zamknijNakladke();
            web.loadUrl(u);
        };
        polacz.setOnClickListener(v -> zatwierdz.run());
        pole.setOnEditorActionListener((v, id, e) -> { zatwierdz.run(); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = p / 2;
        box.addView(polacz, lp);

        if (blad) {
            Button ponow = przycisk("Spróbuj ponownie", false);
            ponow.setOnClickListener(v -> { zamknijNakladke(); web.loadUrl(serverUrl); });
            box.addView(ponow, lp);
        } else if (serverUrl != null) {
            Button anuluj = przycisk("Anuluj", false);
            anuluj.setOnClickListener(v -> zamknijNakladke());
            box.addView(anuluj, lp);
        }

        nakladka = box;
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void zamknijNakladke() {
        if (nakladka != null) {
            root.removeView(nakladka);
            nakladka = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (nakladka != null && serverUrl != null) {
            zamknijNakladke();
        } else if (nakladka == null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
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
    }
}
