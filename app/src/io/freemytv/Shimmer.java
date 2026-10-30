package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;

/**
 * "Grow + liquid shimmer" focus: a live, glowing white rim around the focused card. On Android 13+ an AGSL shader runs
 * two light currents around the rim that wobble and merge like liquid, with a soft halo (three passes: a wide faint
 * glow, a medium glow and the bright core). Older Android (the TCL's 11) gets the same three passes with a sweep
 * gradient whose two highlights circle at different speeds: still alive and glowing, just not liquid.
 */
final class Shimmer {
    private Shimmer() {}

    private static final String AGSL =
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float t;\n" +
        "uniform float alpha;\n" +
        "half4 main(float2 p) {\n" +
        "  float2 d = (p - center) / halfSize;\n" +
        "  float a = atan(d.y, d.x);\n" +
        "  float w1 = sin(a - t * 1.9 + 0.7 * sin(a * 3.0 + t * 1.3));\n" +
        "  float w2 = sin(2.0 * a + t * 1.25 + 0.55 * sin(a * 5.0 - t * 2.2));\n" +
        "  float w3 = sin(3.0 * a - t * 0.8 + 1.7);\n" +
        "  float h = pow(0.5 + 0.5 * w1, 7.0) + 0.65 * pow(0.5 + 0.5 * w2, 9.0) + 0.25 * pow(0.5 + 0.5 * w3, 12.0);\n" +
        "  float v = alpha * (0.38 + 0.62 * clamp(h, 0.0, 1.0));\n" +
        "  return half4(v, v, v, v);\n" +
        "}\n";

    private static Object[] shaders;   // RuntimeShader ×3 (one per pass: their uniforms differ)
    private static boolean agslFailed;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix M = new Matrix();
    private static final RectF R = new RectF();
    private static final float[] WIDTH = {22, 10, 3.2f}, ALPHA = {0.10f, 0.28f, 1f};

    static void draw(Canvas c, RectF box, float radius, float focus, float k, float seconds) {
        PAINT.setStyle(Paint.Style.STROKE);
        for (int pass = 0; pass < 3; pass++) {
            float stroke = WIDTH[pass] * k, off = 2.5f * k + stroke / 2;
            R.set(box.left - off, box.top - off, box.right + off, box.bottom + off);
            PAINT.setStrokeWidth(stroke);
            Shader s = Build.VERSION.SDK_INT >= 33 && !agslFailed ? liquid(pass, focus, seconds) : null;
            if (s == null) {
                s = sweep(pass, focus, seconds);   // sets the pass's alpha on the paint
            } else {
                PAINT.setAlpha(255);
            }
            PAINT.setShader(s);
            c.drawRoundRect(R, radius + off, radius + off, PAINT);
        }
        PAINT.setShader(null);
        PAINT.setAlpha(255);
    }

    @android.annotation.TargetApi(33)
    private static Shader liquid(int pass, float focus, float seconds) {
        try {
            if (shaders == null) {
                shaders = new Object[3];
                for (int i = 0; i < 3; i++) shaders[i] = new android.graphics.RuntimeShader(AGSL);
            }
            android.graphics.RuntimeShader s = (android.graphics.RuntimeShader) shaders[pass];
            s.setFloatUniform("center", R.centerX(), R.centerY());
            s.setFloatUniform("halfSize", Math.max(1, R.width() / 2), Math.max(1, R.height() / 2));
            s.setFloatUniform("t", seconds % 1000f);
            s.setFloatUniform("alpha", ALPHA[pass] * focus);
            return s;
        } catch (Throwable e) {
            AppLog.w("liquid shimmer off: " + e);
            agslFailed = true;
            return null;
        }
    }

    // Android 12 and older: one sweep built once (white, at the origin) and reused by all three passes every frame.
    // The pass's strength rides the paint's alpha and the rotation and position ride the local matrix, so nothing is
    // allocated on the UI thread while the rim spins (it used to be 3 new gradients per frame, ~90 a second on the TCL).
    private static SweepGradient sweepShader;

    private static Shader sweep(int pass, float focus, float seconds) {
        if (sweepShader == null) {
            sweepShader = new SweepGradient(0, 0,
                    new int[] {Ui.alpha(0xFFFFFFFF, 0.38f), 0xFFFFFFFF, Ui.alpha(0xFFFFFFFF, 0.38f), Ui.alpha(0xFFFFFFFF, 0.6f),
                            Ui.alpha(0xFFFFFFFF, 0.38f), Ui.alpha(0xFFFFFFFF, 0.38f)},
                    new float[] {0f, 0.1f, 0.3f, 0.55f, 0.75f, 1f});
        }
        M.setRotate((seconds * 95f) % 360f);
        M.postTranslate(R.centerX(), R.centerY());
        sweepShader.setLocalMatrix(M);
        PAINT.setAlpha(Math.round(255 * Ui.clamp(ALPHA[pass] * focus)));
        return sweepShader;
    }
}
