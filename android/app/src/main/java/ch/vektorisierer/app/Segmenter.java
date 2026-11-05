package ch.vektorisierer.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * KI-Freisteller, läuft komplett auf dem Gerät (ONNX Runtime, CPU).
 *  - «foto»:      U²-Net (Apache 2.0), Eingabe 320 × 320, ImageNet-Normalisierung
 *  - «zeichnung»: ISNet-Anime (Apache 2.0), Eingabe 1024 × 1024, Werte 0..1
 * Liefert eine Graustufen-Maske (weiss = Motiv) als PNG in Modellgrösse; die Seite skaliert sie hoch.
 */
final class Segmenter {

    static final class Model {
        final String key, file, label;
        final int size;
        final float[] mean, std;
        Model(String key, String file, String label, int size, float[] mean, float[] std) {
            this.key = key; this.file = file; this.label = label; this.size = size; this.mean = mean; this.std = std;
        }
    }

    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};
    private static final Model[] MODELS = {
            new Model("foto", "u2net.onnx", "U²-Net", 320, IMAGENET_MEAN, IMAGENET_STD),
            new Model("zeichnung", "isnet-anime.onnx", "ISNet-Anime", 1024, new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f}),
    };

    private final Context ctx;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private String sessionKey;

    Segmenter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** JSON-Liste der Modelle, die in der App stecken, z. B. [{"key":"foto","label":"U²-Net"}]. */
    String availableJson() {
        StringBuilder b = new StringBuilder("[");
        for (Model m : MODELS) {
            if (!hasAsset("models/" + m.file)) continue;
            if (b.length() > 1) b.append(',');
            b.append("{\"key\":\"").append(m.key).append("\",\"label\":\"").append(m.label).append("\"}");
        }
        return b.append(']').toString();
    }

    /** Rechnet die Maske. Eingabe: Bild als Base64 (PNG/JPEG). Ausgabe: Masken-PNG als Base64. */
    synchronized String segment(String key, String imageBase64) throws Exception {
        Model m = find(key);
        byte[] bytes = Base64.decode(imageBase64, Base64.DEFAULT);
        Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (src == null) throw new IllegalArgumentException("Bild unlesbar");
        int s = m.size;
        Bitmap scaled = Bitmap.createScaledBitmap(src, s, s, true);
        if (scaled != src) src.recycle();
        int[] px = new int[s * s];
        scaled.getPixels(px, 0, s, 0, 0, s, s);
        scaled.recycle();

        int plane = s * s;
        float[] input = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = px[i];
            input[i] = (((p >> 16) & 255) / 255f - m.mean[0]) / m.std[0];
            input[plane + i] = (((p >> 8) & 255) / 255f - m.mean[1]) / m.std[1];
            input[2 * plane + i] = ((p & 255) / 255f - m.mean[2]) / m.std[2];
        }

        OrtSession sess = session(m);
        String inputName = sess.getInputNames().iterator().next();
        float[] out = new float[plane];
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, s, s});
             OrtSession.Result r = sess.run(Collections.singletonMap(inputName, t))) {
            FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
            fb.get(out, 0, plane);
        }

        // auf 0..1 strecken, wie es die Referenz-Umsetzung (rembg) macht
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : out) { if (v < lo) lo = v; if (v > hi) hi = v; }
        float span = Math.max(1e-6f, hi - lo);
        for (int i = 0; i < plane; i++) {
            int g = Math.round((out[i] - lo) / span * 255f);
            if (g < 0) g = 0; else if (g > 255) g = 255;
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        Bitmap mask = Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        mask.compress(Bitmap.CompressFormat.PNG, 100, bo);
        mask.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    String label(String key) {
        return find(key).label;
    }

    private Model find(String key) {
        for (Model m : MODELS) if (m.key.equals(key)) return m;
        throw new IllegalArgumentException("unbekanntes Modell: " + key);
    }

    /** Hält höchstens ein Modell im Speicher; beim Wechsel wird das alte freigegeben. */
    private OrtSession session(Model m) throws Exception {
        if (session != null && m.key.equals(sessionKey)) return session;
        if (session != null) { session.close(); session = null; sessionKey = null; }
        File f = modelFile(m);
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors())));
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(f.getAbsolutePath(), o);
        sessionKey = m.key;
        return session;
    }

    /** ONNX Runtime braucht einen Dateipfad: Modell einmalig aus der App in den App-Speicher kopieren. */
    private File modelFile(Model m) throws Exception {
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("kein Speicherplatz");
        File f = new File(dir, m.file);
        long expected = -1;
        try (AssetFileDescriptor fd = ctx.getAssets().openFd("models/" + m.file)) {
            expected = fd.getLength();
        } catch (Exception ignored) { }
        if (f.isFile() && (expected < 0 || f.length() == expected)) return f;
        File tmp = new File(dir, m.file + ".tmp");
        try (InputStream in = ctx.getAssets().open("models/" + m.file); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("Modell konnte nicht abgelegt werden");
        return f;
    }

    private boolean hasAsset(String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }
}
