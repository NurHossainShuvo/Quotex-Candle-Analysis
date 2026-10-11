package com.example.quotexcandleanalysis;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
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
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
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
    private static final int CAPTURE_SCREEN = 402;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private long lastFrameAt = 0;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
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
        public void startScreenCapture() {
            runOnUiThread(() -> {
                if (mediaProjection != null) { notifyCaptureState(true, "স্ক্রিন ক্যাপচার চালু আছে"); return; }
                try { startActivityForResult(projectionManager.createScreenCaptureIntent(), CAPTURE_SCREEN); }
                catch (Exception e) { notifyCaptureState(false, "স্ক্রিন ক্যাপচার অনুমতি খোলা যায়নি: " + safeMessage(e)); }
            });
        }
        @JavascriptInterface
        public void stopScreenCapture() { runOnUiThread(() -> stopCapture("স্ক্রিন ক্যাপচার বন্ধ করা হয়েছে")); }
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

    private void startCapture(int resultCode, Intent data) {
        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) { notifyCaptureState(false, "স্ক্রিন ক্যাপচার অনুমতি পাওয়া যায়নি"); return; }
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int width = Math.min(metrics.widthPixels, 720);
            int height = Math.max(1, metrics.heightPixels * width / Math.max(1, metrics.widthPixels));
            int density = metrics.densityDpi;
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    long now = System.currentTimeMillis();
                    if (image == null || now - lastFrameAt < 1800) return;
                    lastFrameAt = now;
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int pixelStride = plane.getPixelStride();
                    int rowStride = plane.getRowStride();
                    int rowPadding = rowStride - pixelStride * width;
                    Bitmap full = Bitmap.createBitmap(width + Math.max(0, rowPadding / pixelStride), height, Bitmap.Config.ARGB_8888);
                    buffer.rewind();
                    full.copyPixelsFromBuffer(buffer);
                    Bitmap frame = Bitmap.createBitmap(full, 0, 0, width, height);
                    full.recycle();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    frame.compress(Bitmap.CompressFormat.JPEG, 55, out);
                    frame.recycle();
                    String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("window.onScreenCaptureFrame && window.onScreenCaptureFrame(" + JSONObject.quote(encoded) + ")", null);
                    });
                } catch (Exception ignored) {
                } finally { if (image != null) image.close(); }
            }, null);
            virtualDisplay = mediaProjection.createVirtualDisplay("CandleLabScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { runOnUiThread(() -> stopCapture("Android স্ক্রিন ক্যাপচার বন্ধ করেছে")); }
            }, null);
            notifyCaptureState(true, "স্ক্রিন ক্যাপচার চালু। Quotex-এ ফিরে চার্ট দেখান; প্রতি প্রায় ২ সেকেন্ডে ফ্রেম আসবে।");
        } catch (Exception e) { stopCapture("ক্যাপচার শুরু হয়নি: " + safeMessage(e)); }
    }

    private void notifyCaptureState(boolean active, String message) {
        if (webView != null) webView.evaluateJavascript("window.onScreenCaptureState && window.onScreenCaptureState(" + active + "," + JSONObject.quote(message) + ")", null);
    }

    private void stopCapture(String message) {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        MediaProjection old = mediaProjection;
        mediaProjection = null;
        try { if (old != null) old.stop(); } catch (Exception ignored) {}
        notifyCaptureState(false, message);
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
        if (requestCode == CAPTURE_SCREEN) {
            if (resultCode == RESULT_OK && data != null) startCapture(resultCode, data);
            else notifyCaptureState(false, "স্ক্রিন ক্যাপচার অনুমতি বাতিল করা হয়েছে");
            return;
        }
        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override protected void onDestroy() {
        stopCapture("অ্যাপ বন্ধ হওয়ায় ক্যাপচার বন্ধ হয়েছে");
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
