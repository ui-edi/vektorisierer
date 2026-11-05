package ch.vektorisierer.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.webkit.RenderProcessGoneDetail;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final long MAX_SHARE_BYTES = 120L * 1024 * 1024;
    /** Grössere Bilder werden vor der Übergabe an die Seite verkleinert (Speicher, Übergabe als Text). */
    private static final int MAX_SIDE = 4000;
    private static final long PASS_THROUGH_BYTES = 12L * 1024 * 1024;
    private long lastBack = 0;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean pageReady = false;
    private Uri pendingShare = null;
    private Segmenter segmenter;
    private final ExecutorService aiQueue = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        setContentView(root);

        // Ab Android 15 zeichnet die App unter Status- und Navigationsleiste: Abstand selbst setzen
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        segmenter = new Segmenter(this);
        createWebView();
        handleIntent(getIntent());

        // Zurück: erst nach zweimaligem Wischen beenden, damit die Arbeit nicht aus Versehen verloren geht
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backPressed);
        }
    }

    private FrameLayout root;

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        backPressed();
    }

    private void backPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            Toast.makeText(this, "Nochmals zurück zum Beenden", Toast.LENGTH_SHORT).show();
        }
    }

    private void createWebView() {
        pageReady = false;
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Ganze Seite nicht zoomen: gezoomt wird nur im Bild (eigene Gesten der Seite)
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
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

            /** Stürzt die Seite ab (z. B. zu wenig Speicher), startet sie neu statt die ganze App zu beenden. */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != web) return true;
                root.removeView(web);
                web.destroy();
                web = null;
                createWebView();
                Toast.makeText(MainActivity.this, "Das Bild war zu gross für den Speicher. Die App wurde neu geladen.", Toast.LENGTH_LONG).show();
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
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
        aiQueue.shutdownNow();
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
                byte[][] box = {bytes};
                mime = shrinkIfLarge(box, mime);
                bytes = box[0];
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                bytes = null;
                js = "window.vkOpenShared&&window.vkOpenShared('data:" + mime + ";base64," + b64 + "'," + jsString(displayName(uri)) + ")";
            } catch (OutOfMemoryError e) {
                js = "window.vkAppToast&&window.vkAppToast(" + jsString("Das geteilte Bild ist zu gross für den Speicher.") + ")";
            } catch (Exception e) {
                js = "window.vkAppToast&&window.vkAppToast(" + jsString("Das geteilte Bild liess sich nicht öffnen.") + ")";
            }
            final String script = js;
            runOnUiThread(() -> { if (web != null) web.evaluateJavascript(script, null); });
        }).start();
    }

    /** Verkleinert sehr grosse Bilder auf höchstens MAX_SIDE (PNG bei Transparenz, sonst JPEG 95). */
    private static String shrinkIfLarge(byte[][] box, String mime) {
        byte[] bytes = box[0];
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return mime; // unbekanntes Format: die Seite versucht es selbst
        int side = Math.max(w, h);
        if (side <= MAX_SIDE && bytes.length <= PASS_THROUGH_BYTES) return mime;
        int sample = 1;
        while (side / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
        if (b == null) return mime;
        float f = Math.min(1f, MAX_SIDE / (float) Math.max(b.getWidth(), b.getHeight()));
        if (f < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * f)), Math.max(1, Math.round(b.getHeight() * f)), true);
            if (s != b) b.recycle();
            b = s;
        }
        boolean alpha = b.hasAlpha();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, alpha ? 100 : 95, out);
        b.recycle();
        box[0] = out.toByteArray();
        return alpha ? "image/png" : "image/jpeg";
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

        /** Welche KI-Modelle in der App stecken (JSON). */
        @JavascriptInterface
        public String aiModels() {
            return segmenter.availableJson();
        }

        /** KI-Freistellung: Ergebnis kommt über window.__vkSeg(id, ok, maskeOderFehler, millisekunden, modell). */
        @JavascriptInterface
        public void segment(String key, String imageBase64, String callbackId) {
            aiQueue.execute(() -> {
                long t0 = System.currentTimeMillis();
                boolean ok = false;
                String payload;
                String label = "";
                try {
                    label = segmenter.label(key);
                    payload = segmenter.segment(key, imageBase64);
                    ok = true;
                } catch (OutOfMemoryError e) {
                    payload = "Zu wenig Arbeitsspeicher";
                } catch (Throwable e) {
                    payload = String.valueOf(e.getMessage());
                }
                long ms = System.currentTimeMillis() - t0;
                final String js = "window.__vkSeg&&window.__vkSeg(" + jsString(callbackId) + "," + ok + "," + jsString(payload) + "," + ms + "," + jsString(label) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            });
        }

        /** Speichert eine Datei. PNG landet in Bilder/Vektorisierer, alles andere in Downloads/Vektorisierer. */
        @JavascriptInterface
        public void save(String name, String base64, String mime, String callbackId) {
            new Thread(() -> {
                boolean ok = false;
                String where;
                Uri item = null;
                ContentResolver cr = getContentResolver();
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
                    item = cr.insert(collection, v);
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
                } catch (OutOfMemoryError e) {
                    where = "zu wenig Arbeitsspeicher, wähle eine kleinere PNG-Grösse";
                } catch (Exception e) {
                    where = String.valueOf(e.getMessage());
                }
                // Halb geschriebene Datei nicht als Leiche im Speicher lassen
                if (!ok && item != null) {
                    try { cr.delete(item, null, null); } catch (Exception ignored) { }
                }
                final String js = "window.__vkSaved&&window.__vkSaved(" + jsString(callbackId) + "," + ok + "," + jsString(where) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }).start();
        }
    }
}
