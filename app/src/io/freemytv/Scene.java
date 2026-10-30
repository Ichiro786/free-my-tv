package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 * Without AGSL (Android 11/12, the TCL) and with Aurora motion on, a light "fake aurora" stands in: a few soft color
 * blobs from the palette drifting slowly over the dark gradient, painted on the CPU into a reused bitmap at 1/8 of the
 * screen (only when advance moves it, so at most ~10 times per second) and scaled up with bilinear filtering, which
 * blurs it into an aurora-like glow. Aurora motion off: the still gradient everywhere (Android 13+ included).
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private final Paint dimPaint = new Paint();
    // The fake aurora (Android < 13): blobs drawn into a small bitmap, all objects made once and reused.
    private static final int BLOBS = 4;
    private final RadialGradient[] blobShaders = new RadialGradient[BLOBS];
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint blobBase = new Paint(Paint.DITHER_FLAG);
    private final Paint blobUp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix blobMatrix = new Matrix();
    private final RectF blobDst = new RectF();
    private Bitmap blobBitmap;
    private Canvas blobCanvas;
    private LinearGradient blobGradient;
    private int bw = 1, bh = 1;
    private boolean motion = Prefs.auroraMotion(), blobsDirty = true;
    private final RenderNode auroraNode = new RenderNode("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB;
    private BitmapShader glassShaderA;

    Scene() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("AGSL needs Android 13");
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 12: no AGSL, flat look instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        int nbw = Math.max(1, Math.round(w / 8f)), nbh = Math.max(1, Math.round(h / 8f));
        if (nbw != bw || nbh != bh) {
            bw = nbw;
            bh = nbh;
            freeBlobs();
            blobGradient = null;
        }
        blobDst.set(0, 0, w, h);
        auroraNode.setPosition(0, 0, lw, lh);
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Aurora motion (Settings → Themes): off draws the still gradient, also where AGSL is available. */
    void setAuroraMotion(boolean on) {
        if (on == motion) return;
        motion = on;
        blobsDirty = true;
        auroraDirty = true;
        if (!on) freeBlobs();
    }

    /** True while the background moves: the owner should keep calling advance (at ~10 per second) and redraw. */
    boolean animating() {
        return motion;
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        fallback.setShader(new LinearGradient(0, 0, 0, h, palette.color(1), palette.color(0), Shader.TileMode.CLAMP));
        // Fake aurora colors: the middle tone and the highlight, alternating, fading to transparent (made once here,
        // moved every step with a local matrix: nothing is allocated per frame).
        for (int i = 0; i < BLOBS; i++) {
            int rgb = palette.color(i % 2 == 0 ? 2 : 1) & 0xFFFFFF;
            int a = i % 2 == 0 ? 0xB3 : 0x8C;
            blobShaders[i] = new RadialGradient(0, 0, 1, new int[] {(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        }
        blobGradient = null;
        blobsDirty = true;
    }

    /** Frees the fake aurora's bitmap (low memory, or the screen went away); it is made again when needed. */
    void trimMemory() {
        freeBlobs();
    }

    private void freeBlobs() {
        if (blobBitmap != null) blobBitmap.recycle();
        blobBitmap = null;
        blobCanvas = null;
        blobsDirty = true;
    }

    /** Paints the fake aurora at `time` into the small bitmap (Android < 13). */
    private void renderBlobs() {
        if (blobBitmap == null) {
            try {
                blobBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                return;
            }
            blobCanvas = new Canvas(blobBitmap);
        }
        if (blobGradient == null) {
            blobGradient = new LinearGradient(0, 0, 0, bh, palette.color(1), palette.color(0), Shader.TileMode.CLAMP);
            blobBase.setShader(blobGradient);
        }
        Canvas c = blobCanvas;
        c.drawRect(0, 0, bw, bh, blobBase);
        // Slow drift: each blob loops on its own Lissajous path (periods of a few minutes at speed 1). The bands sit in
        // the middle of the sky, like the AGSL aurora's: the top (clock) and the bottom (dock) stay darker.
        float tt = time * 0.035f * speed;
        for (int i = 0; i < BLOBS; i++) {
            if (blobShaders[i] == null) continue;
            float ph = i * 1.9f;
            float cx = bw * (0.5f + 0.42f * (float) Math.sin(tt * (0.9f + 0.23f * i) + ph));
            float cy = bh * (0.42f + 0.14f * (float) Math.sin(tt * (0.7f + 0.17f * i) + ph * 1.7f));
            float rx = bw * (0.36f + 0.08f * (float) Math.sin(tt * 0.5f + ph * 2.3f));
            float ry = rx * (0.45f + 0.1f * i / BLOBS);
            blobMatrix.setScale(rx, ry);
            blobMatrix.postTranslate(cx, cy);
            blobShaders[i].setLocalMatrix(blobMatrix);
            blobPaint.setShader(blobShaders[i]);
            c.drawRect(Math.max(0, cx - rx), Math.max(0, cy - ry), Math.min(bw, cx + rx), Math.min(bh, cy + ry), blobPaint);
        }
        blobPaint.setShader(null);
        blobsDirty = false;
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        dimPaint.setColor(Math.round(255 * dim) << 24);
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        if (seconds != time) blobsDirty = true;
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (!imageFrost || !imageGlass) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        if (liquid == null) return;
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        if (liquid != null) liquid.setFloatUniform("mixB", Math.max(0, Math.min(1, t)));
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        imageFrost = true;
        if (glass == null) return;
        BitmapShader s = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        glass.setInputShader("frost", s);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null || !motion) {
            if (fallback.getShader() == null) applyPalette();
            if (motion) {   // Android < 13: the fake aurora
                if (blobsDirty || blobBitmap == null) renderBlobs();
                if (blobBitmap != null) {
                    c.drawBitmap(blobBitmap, null, blobDst, blobUp);
                    if (dim > 0.004f) c.drawRect(0, 0, w, h, dimPaint);
                    return;
                }
            }
            c.drawRect(0, 0, w, h, fallback);
            if (dim > 0.004f) c.drawRect(0, 0, w, h, dimPaint);
            return;
        }
        if (auroraDirty) {
            RecordingCanvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        c.drawRenderNode(auroraNode);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            if (body > 0) {
                flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.max(0.35f, tintAlpha)));
                c.drawRoundRect(r, radius, radius, flatFill);
            }
            c.drawRoundRect(r, radius, radius, flatRim);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }
}
