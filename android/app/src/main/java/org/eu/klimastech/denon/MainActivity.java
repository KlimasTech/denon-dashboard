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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Denon Pilot - aplikacja KlimasTech: opakowanie (WebView) dla Denon AVR
 * Dashboard. Adres serwera (np. http://192.168.1.10:7777) wpisuje sie przy
 * pierwszym uruchomieniu i mozna go zmienic w Ustawieniach dashboardu
 * (przycisk "Zmien adres serwera") albo z ekranu bledu polaczenia.
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
