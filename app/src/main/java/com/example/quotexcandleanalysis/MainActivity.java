package com.example.quotexcandleanalysis;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE = 401;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new NativeApiBridge(), "AndroidApi");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = params.getAcceptTypes();
                String mime = "*/*";
                if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) mime = types[0];
                intent.setType(mime);
                try { startActivityForResult(intent, PICK_FILE); }
                catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private final class NativeApiBridge {
        @JavascriptInterface
        public void fetchCandles(String requestId, String apiKey, String symbol, String timeframe, String limit) {
            if (requestId == null || requestId.length() > 40 ||
                apiKey == null || apiKey.trim().isEmpty() || apiKey.length() > 512 ||
                symbol == null || !symbol.matches("[A-Za-z0-9_]{1,40}") ||
                !("60".equals(timeframe) || "300".equals(timeframe) || "900".equals(timeframe) || "1800".equals(timeframe) || "3600".equals(timeframe)) ||
                !("30".equals(limit) || "50".equals(limit) || "100".equals(limit) || "200".equals(limit))) {
                deliver(requestId, 0, "{\"error\":\"Invalid API input. Check key, symbol and options.\"}");
                return;
            }
            networkExecutor.execute(() -> {
                HttpURLConnection connection = null;
                int status = 0;
                String body = "";
                try {
                    Uri uri = Uri.parse("https://otcharts.com/v1/candles").buildUpon()
                        .appendQueryParameter("venue", "quotex")
                        .appendQueryParameter("symbol", symbol)
                        .appendQueryParameter("tf", timeframe)
                        .appendQueryParameter("limit", limit).build();
                    connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                    connection.setRequestProperty("Accept", "application/json");
                    status = connection.getResponseCode();
                    InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
                    if (stream != null) {
                        StringBuilder out = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) out.append(line);
                        }
                        body = out.toString();
                    }
                } catch (Exception e) {
                    body = "{\"error\":\"Network request failed: " + safeMessage(e) + "\"}";
                } finally {
                    if (connection != null) connection.disconnect();
                }
                deliver(requestId, status, body);
            });
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.replace("\\", "/").replace("\"", "'").replace("\n", " ");
    }

    private void deliver(String requestId, int status, String body) {
        if (requestId == null) return;
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("status", status);
            envelope.put("body", body == null ? "" : body);
            String quotedId = JSONObject.quote(requestId);
            String quotedEnvelope = JSONObject.quote(envelope.toString());
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(
                    "window.onNativeCandlesResponse && window.onNativeCandlesResponse(" + quotedId + "," + quotedEnvelope + ")", null);
            });
        } catch (Exception ignored) { }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override protected void onDestroy() {
        networkExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApi");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
