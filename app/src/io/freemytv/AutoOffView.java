package io.freemytv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * Auto power-off's warning, drawn over whatever is on screen: a frosted card in the Themes palette with a 20-second
 * ring, "Your TV has been on for N hours", "It will turn off in N seconds" and a Cancel button that always has the
 * focus (white with the shimmer rim, like a focused row in the settings). It comes in with a spring (scale, fade and,
 * on Android 12+, a blur that clears) and leaves the same way. OK or Back cancel; the arrows only nudge the button
 * (there is nothing else to focus); any other key (volume, Home…) also counts as "someone is watching" and cancels.
 * Everything that can be is made once when it opens: the frames allocate nothing.
 */
final class AutoOffView extends View implements Choreographer.FrameCallback {
    interface Listener {
        void onCancel();
        void onTimeUp();
        void onGone();
    }

    static final long COUNTDOWN_MS = 20_000;
    private static final int COUNTING = 0, TURNING_OFF = 1, FAILED = 2, LEAVING = 3;
    private static final float CX = 960, CY = 540, CARD_W = 780, CARD_H = 556, RADIUS = 40;
    private static final float RING_Y = 392, RING_R = 62, RING_W = 7;
    private static final float TITLE_Y = 528, SUB_Y = 576, BUTTON_Y = 668, BUTTON_W = 300, BUTTON_H = 76, HINT_Y = 772;
    private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;
    private static final float[] BLUR_RADII = {2, 4, 7, 10, 14, 18, 23, 28};

    private final Listener listener;
    private final Spring appear = new Spring(0.5f, 0.78f, 0);   // a touch of overshoot on the way in
    private final Spring press = new Spring(0.2f, 0.75f, 0);
    private final Spring nudgeX = new Spring(0.34f, 0.42f, 0), nudgeY = new Spring(0.34f, 0.42f, 0);
    private final Spring glow = new Spring(0.7f, 1f, 0);
    private final Shaders.Palette palette = new Shaders.Palette();
    private final String[] seconds = new String[21], subs = new String[21];
    private final String titleFull, turningText, failedTitleFull, failedSubText, cancelText, okText, hintText;
    private String title = "", failedTitle = "";
    private final RectF card = new RectF(), ring = new RectF(), button = new RectF(), tmp = new RectF();
    private Object[] blurs;   // RenderEffect (Android 12+), one per radius, made once
    private int blurShown = -1;
    private int state = COUNTING;
    private long deadline, startedAt, lastNanos;
    private boolean attached, frameQueued, okDown, timeUpSent, goneSent;
    private int w, h;
    private float k = 1;
    private Paint scrim, cardShadow, cardFill, cardGlow, rim, track, arc, arcGlow, head, number, titlePaint, sub, buttonFill,
            buttonShadow, buttonText, hint, spinner;

    private final Runnable leaveAfterFailure = this::exit;

    AutoOffView(Context c, int hours, Listener listener) {
        super(c);
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setWillNotDraw(false);
        Shaders.background(palette, System.currentTimeMillis(), 0, 0);   // Themes: Dark (the default) or Sky
        String h = String.valueOf(hours);
        titleFull = hours == 1
                ? Ui.tr("Sua TV está ligada há 1 hora", "Your TV has been on for 1 hour", "Tu TV lleva 1 hora encendida")
                : Ui.tr("Sua TV está ligada há " + h + " horas", "Your TV has been on for " + h + " hours", "Tu TV lleva " + h + " horas encendida");
        for (int i = 0; i <= 20; i++) {
            seconds[i] = String.valueOf(i);
            subs[i] = i == 1
                    ? Ui.tr("Ela vai desligar em 1 segundo", "It will turn off in 1 second", "Se apagará en 1 segundo")
                    : Ui.tr("Ela vai desligar em " + i + " segundos", "It will turn off in " + i + " seconds", "Se apagará en " + i + " segundos");
        }
        turningText = Ui.tr("Desligando…", "Turning off…", "Apagando…");
        failedTitleFull = Ui.tr("Não deu para desligar a TV", "Couldn't turn the TV off", "No se pudo apagar la TV");
        failedSubText = Ui.tr("Use o botão de ligar do controle.", "Use the power button on the remote.", "Usa el botón de encendido del control.");
        cancelText = Ui.tr("Cancelar", "Cancel", "Cancelar");
        okText = "OK";
        hintText = Ui.tr("OK ou Voltar mantêm a TV ligada", "OK or Back keeps the TV on", "OK o Atrás mantienen la TV encendida");
        setContentDescription(titleFull);
        if (Build.VERSION.SDK_INT >= 31) {
            blurs = new Object[BLUR_RADII.length];
        }
    }

    // MARK: - Lifecycle

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        startedAt = SystemClock.uptimeMillis();
        deadline = startedAt + COUNTDOWN_MS + 300;   // the full 20 shows while the card comes in
        appear.target = 1;
        glow.target = 1;
        requestFocus();
        announceForAccessibility(titleFull + ". " + subs[20]);
        wake();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
        removeCallbacks(leaveAfterFailure);
        super.onDetachedFromWindow();
    }

    /** Leaves with the reverse of the entrance; onGone once it has faded out. */
    void exit() {
        if (state == LEAVING) return;
        state = LEAVING;
        removeCallbacks(leaveAfterFailure);
        appear.target = 0;
        press.target = 0;
        glow.target = 0;
        wake();
    }

    /** The countdown reached zero and the TV is being turned off. */
    void turningOff() {
        if (state == LEAVING) return;
        state = TURNING_OFF;
        setContentDescription(turningText);
        wake();
    }

    /** No way to turn the TV off worked: say so, then leave by itself. */
    void failed() {
        if (state == LEAVING) return;
        state = FAILED;
        setContentDescription(failedTitleFull);
        announceForAccessibility(failedTitleFull + ". " + failedSubText);
        removeCallbacks(leaveAfterFailure);
        postDelayed(leaveAfterFailure, 6_000);
        wake();
    }

    boolean counting() {
        return state == COUNTING;
    }

    private void wake() {
        if (!attached || frameQueued) return;
        frameQueued = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        frameQueued = false;
        if (!attached) return;
        long up = SystemClock.uptimeMillis();
        if (state == COUNTING && up >= deadline && !timeUpSent) {
            timeUpSent = true;
            state = TURNING_OFF;
            listener.onTimeUp();
        }
        if (state == LEAVING && appear.value < 0.015f && !goneSent) {
            goneSent = true;
            listener.onGone();
            return;
        }
        if (blurs != null) applyBlur();
        invalidate();
        frameQueued = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Android 12+: the card comes in out of focus and sharpens (the reverse on the way out). */
    @android.annotation.TargetApi(31)
    private void applyBlur() {
        float a = Ui.clamp(appear.value);
        int index = a >= 0.97f ? -1 : Math.min(BLUR_RADII.length - 1, (int) ((1 - a) * BLUR_RADII.length));
        if (index == blurShown) return;
        blurShown = index;
        if (index < 0) {
            setRenderEffect(null);
            return;
        }
        if (blurs[index] == null) {
            float r = BLUR_RADII[index] * k;
            blurs[index] = android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
        }
        setRenderEffect((android.graphics.RenderEffect) blurs[index]);
    }

    // MARK: - Keys (from the accessibility filter, or from this window when it has the focus)

    /** True if the key was used up here. */
    boolean handleKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        press.target = 1;
                        wake();
                    }
                } else {
                    press.target = 0;
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (!down) activate();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Nothing else to focus: the button leans toward the arrow and springs back (the tvOS edge bump).
                if (down) {
                    int code = e.getKeyCode();
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) nudgeX.velocity -= 7;
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) nudgeX.velocity += 7;
                    else if (code == KeyEvent.KEYCODE_DPAD_UP) nudgeY.velocity -= 7;
                    else nudgeY.velocity += 7;
                    wake();
                }
                return true;
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_SOFT_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return false;   // the system's own (turning the TV off by hand also closes this)
            default:
                // Volume, Home, the app buttons…: someone is watching. Cancel, and let the key do its job.
                if (down && state == COUNTING) activate();
                return false;
        }
    }

    private void activate() {
        if (state == COUNTING) listener.onCancel();
        else if (state == FAILED) exit();
    }

    // MARK: - Drawing

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        blurShown = -2;
        if (blurs != null) java.util.Arrays.fill(blurs, null);   // radii scale with the screen
        card.set((CX - CARD_W / 2) * k, (CY - CARD_H / 2) * k, (CX + CARD_W / 2) * k, (CY + CARD_H / 2) * k);
        ring.set((CX - RING_R) * k, (RING_Y - RING_R) * k, (CX + RING_R) * k, (RING_Y + RING_R) * k);

        scrim = new Paint();
        scrim.setColor(0xFF000000);
        cardShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardShadow.setColor(0x8C000000);
        cardShadow.setMaskFilter(new BlurMaskFilter(54 * k, BlurMaskFilter.Blur.NORMAL));
        // Frosted card in the Themes palette: lighter at the top, a soft glow of the highlight tone, a thin rim.
        boolean bright = palette.brightest() > 0.45f;
        int top = scale(palette.color(1), bright ? 0.52f : 0.9f), bottom = scale(palette.color(0), bright ? 0.42f : 0.95f);
        cardFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        cardFill.setShader(new LinearGradient(0, card.top, 0, card.bottom, Ui.alpha(top, 0.94f), Ui.alpha(bottom, 0.96f), Shader.TileMode.CLAMP));
        cardGlow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        cardGlow.setShader(new RadialGradient(CX * k, card.top, CARD_W * 0.62f * k,
                new int[] {Ui.alpha(palette.color(2), bright ? 0.18f : 0.26f), Ui.alpha(palette.color(2), 0.06f), 0},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1, 1.5f * k));
        rim.setColor(0x33FFFFFF);
        track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(RING_W * k);
        track.setColor(0x26FFFFFF);
        arc = new Paint(track);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFFFFFFF);
        arcGlow = new Paint(arc);
        arcGlow.setStrokeWidth((RING_W + 12) * k);
        arcGlow.setMaskFilter(new BlurMaskFilter(10 * k, BlurMaskFilter.Blur.NORMAL));
        head = new Paint(Paint.ANTI_ALIAS_FLAG);
        number = Ui.numbers(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
        number.setTextAlign(Paint.Align.CENTER);
        titlePaint = Ui.text(Ui.displaySemibold, 38 * k, 0xFFFFFFFF);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        sub = Ui.numbers(Ui.regular, 26 * k, 0xA6FFFFFF);
        sub.setTextAlign(Paint.Align.CENTER);
        buttonFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonFill.setColor(0xFFF5F5F7);
        buttonShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonShadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        buttonText = Ui.text(Ui.semibold, 28 * k, INK);
        buttonText.setTextAlign(Paint.Align.CENTER);
        hint = Ui.text(Ui.regular, 21 * k, 0x73FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setStrokeWidth(RING_W * k);
        spinner.setColor(0xFFFFFFFF);
        TextPaint fit = new TextPaint(titlePaint);
        title = TextUtils.ellipsize(titleFull, fit, (CARD_W - 90) * k, TextUtils.TruncateAt.END).toString();
        failedTitle = TextUtils.ellipsize(failedTitleFull, fit, (CARD_W - 90) * k, TextUtils.TruncateAt.END).toString();
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || scrim == null) return;
        long nanos = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        appear.step(dt);
        press.step(dt);
        nudgeX.step(dt);
        nudgeY.step(dt);
        glow.step(dt);
        long up = SystemClock.uptimeMillis();
        float a = Ui.clamp(appear.value);

        scrim.setAlpha(Math.round(255 * 0.55f * a));
        c.drawRect(0, 0, w, h, scrim);
        if (a <= 0.004f) return;

        int save = c.save();
        float s = 0.9f + 0.1f * appear.value;
        c.translate(0, 30 * k * (1 - appear.value));
        c.scale(s, s, CX * k, CY * k);
        boolean layer = a < 0.995f;
        if (layer) c.saveLayerAlpha(card.left - 80 * k, card.top - 60 * k, card.right + 80 * k, card.bottom + 110 * k, Math.round(255 * a));

        // Card: shadow, frosted fill, glow, rim.
        tmp.set(card);
        tmp.offset(0, 22 * k);
        c.drawRoundRect(tmp, RADIUS * k, RADIUS * k, cardShadow);
        c.drawRoundRect(card, RADIUS * k, RADIUS * k, cardFill);
        c.drawRoundRect(card, RADIUS * k, RADIUS * k, cardGlow);
        c.drawRoundRect(card, RADIUS * k, RADIUS * k, rim);

        drawRing(c, up);

        // Title and the line under it.
        boolean failedNow = state == FAILED;
        c.drawText(failedNow ? failedTitle : title, CX * k, TITLE_Y * k, titlePaint);
        String line;
        if (state == COUNTING || state == LEAVING && !timeUpSent) line = subs[secondsLeft(up)];
        else if (failedNow) line = failedSubText;
        else line = turningText;
        c.drawText(line, CX * k, SUB_Y * k, sub);

        drawButton(c, up, failedNow ? okText : cancelText);
        c.drawText(hintText, CX * k, HINT_Y * k, hint);

        if (layer) c.restore();
        c.restoreToCount(save);
    }

    private int secondsLeft(long up) {
        long left = Math.max(0, deadline - up);
        return (int) Math.min(20, (left + 999) / 1000);
    }

    /** The ring empties smoothly over the 20 seconds; warm in the last five; each new number pops in. */
    private void drawRing(Canvas c, long up) {
        float cx = ring.centerX(), cy = ring.centerY(), r = ring.width() / 2;
        c.drawCircle(cx, cy, r, track);
        if (state == TURNING_OFF || (state == LEAVING && timeUpSent)) {
            Ui.spinner(c, cx, cy, r, (up - startedAt) / 1000f * 1.2f, spinner);
            Symbols.draw(getContext(), c, "bedtime", cx, cy, 40 * k, 0xEBFFFFFF);
            return;
        }
        if (state == FAILED) {
            Symbols.draw(getContext(), c, "bedtime", cx, cy, 40 * k, 0x99FFFFFF);
            return;
        }
        long left = Math.max(0, deadline - up);
        float progress = Ui.clamp(left / (float) COUNTDOWN_MS);
        float warm = Ui.clamp((5_000 - left) / 1_500f);   // turns amber as the last five seconds begin
        int color = blend(0xFFFFFFFF, AMBER, warm);
        float sweep = 360f * progress;
        if (sweep > 0.5f) {
            arcGlow.setColor(Ui.alpha(color, 0.35f));
            c.drawArc(ring, -90, sweep, false, arcGlow);
            arc.setColor(color);
            c.drawArc(ring, -90, sweep, false, arc);
            double angle = Math.toRadians(-90 + sweep);
            head.setColor(color);
            c.drawCircle(cx + (float) Math.cos(angle) * r, cy + (float) Math.sin(angle) * r, RING_W * 0.95f * k, head);
        }
        // The number: a small pop each time it changes.
        int n = secondsLeft(up);
        float into = 1 - ((left % 1000) / 1000f);              // 0 right as the number changes
        float pop = 1 + 0.1f * (1 - Ui.clamp(into / 0.22f));
        number.setColor(color);
        int save = c.save();
        c.scale(pop, pop, cx, cy);
        c.drawText(seconds[n], cx, cy + Ui.halfCap(number), number);
        c.restoreToCount(save);
    }

    /** The one button, always focused: white, lifted, with the shimmer rim; it presses in on OK. */
    private void drawButton(Canvas c, long up, String label) {
        float f = Ui.clamp(glow.value);
        float scale = 1 + 0.045f * f - 0.06f * Ui.clamp(press.value);
        float bw = BUTTON_W * k * scale, bh = BUTTON_H * k * scale;
        float bx = CX * k + nudgeX.value * 2.6f * k, by = BUTTON_Y * k + nudgeY.value * 2.0f * k;
        button.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2);
        float r = bh / 2;
        tmp.set(button);
        tmp.offset(0, 10 * k * f);
        buttonShadow.setColor(Ui.alpha(0xFF000000, 0.42f * f));
        c.drawRoundRect(tmp, r, r, buttonShadow);
        Shimmer.draw(c, button, r, f, k, (up - startedAt) / 1000f);
        c.drawRoundRect(button, r, r, buttonFill);
        c.drawText(label, bx, by + Ui.halfCap(buttonText), buttonText);
    }

    private static int scale(int rgb, float f) {
        int r = Math.round(((rgb >> 16) & 255) * f), g = Math.round(((rgb >> 8) & 255) * f), b = Math.round((rgb & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
