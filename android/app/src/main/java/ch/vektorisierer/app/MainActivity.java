package ch.vektorisierer.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Vektorisierer als App: zeigt die eingebaute Seite (assets/index.html) offline an
 * und ergänzt, was der Browser nicht kann:
 *  - Bilder empfangen über «Teilen» und «Öffnen mit»
 *  - SVG in Downloads/Vektorisierer und PNG in Bilder/Vektorisierer speichern
 *  - Dateiauswahl für «Bild wählen»
 *  - kein automatisches Umfärben durch den Dunkelmodus
 */
public class MainActivity extends Activity {

    private static final int PICK_IMAGE = 1;
    private static final long MAX_SHARE_BYTES = 60L * 1024 * 1024;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean pageReady = false;
    private Uri pendingShare = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Ab Android 15 zeichnet die App unter Status- und Navigationsleiste: Abstand selbst setzen
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 33) {
            // Der Dunkelmodus darf die Vektorfarben nicht aufhellen: die Seite hat ein eigenes Dunkel-Design
            s.setAlgorithmicDarkeningAllowed(false);
        }

        web.addJavascriptInterface(new Bridge(), "VkApp");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, getString(R.string.choose_image)), PICK_IMAGE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                // Links nach draussen (z. B. zu GitHub) im Browser öffnen
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverShare();
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------- Bilder empfangen ----------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri != null) {
            pendingShare = uri;
            deliverShare();
        }
    }

    private void deliverShare() {
        if (!pageReady || pendingShare == null) return;
        final Uri uri = pendingShare;
        pendingShare = null;
        new Thread(() -> {
            String js;
            try {
                ContentResolver cr = getContentResolver();
                String mime = cr.getType(uri);
                if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                byte[] bytes = readAll(cr.openInputStream(uri));
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                js = "window.vkOpenShared&&window.vkOpenShared('data:" + mime + ";base64," + b64 + "'," + jsString(displayName(uri)) + ")";
            } catch (Exception e) {
                js = "window.vkAppToast&&window.vkAppToast(" + jsString("Das geteilte Bild liess sich nicht öffnen.") + ")";
            }
            final String script = js;
            runOnUiThread(() -> web.evaluateJavascript(script, null));
        }).start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replaceFirst("\\.[^.]+$", "");
            }
        } catch (Exception ignored) { }
        return "geteilt";
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) throw new IllegalStateException("kein Inhalt");
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > MAX_SHARE_BYTES) throw new IllegalStateException("zu gross");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char ch : s.toCharArray()) {
            if (ch == '\'' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('\'').toString();
    }

    // ---------- Brücke für die Seite ----------

    private class Bridge {
        @JavascriptInterface
        public String platform() {
            return "android";
        }

        /** Speichert eine Datei. PNG landet in Bilder/Vektorisierer, alles andere in Downloads/Vektorisierer. */
        @JavascriptInterface
        public void save(String name, String base64, String mime, String callbackId) {
            new Thread(() -> {
                boolean ok = false;
                String where;
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    boolean image = mime != null && mime.startsWith("image/png");
                    Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    String folder = (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/Vektorisierer";
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    ContentResolver cr = getContentResolver();
                    Uri item = cr.insert(collection, v);
                    if (item == null) throw new IllegalStateException("kein Speicherplatz");
                    try (OutputStream out = cr.openOutputStream(item)) {
                        if (out == null) throw new IllegalStateException("nicht beschreibbar");
                        out.write(data);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(item, done, null, null);
                    ok = true;
                    where = (image ? "Bilder" : "Downloads") + "/Vektorisierer";
                } catch (Exception e) {
                    where = String.valueOf(e.getMessage());
                }
                final String js = "window.__vkSaved&&window.__vkSaved(" + jsString(callbackId) + "," + ok + "," + jsString(where) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
