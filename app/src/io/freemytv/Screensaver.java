package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.view.KeyEvent;

import java.lang.ref.WeakReference;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 *
 * Since Android 11 a dream draws in a DreamActivity that the system starts for it. On Android 11 that activity is
 * started in the app's default process, not the dream's: with the dream in another process the window never came, the
 * TV stayed "dreaming" with no dream on screen, and every key but Home was lost. The application's default process is
 * now :home (the manifest), and as a safety net a dream whose window doesn't arrive ends itself (`GHOST_MS`), and the
 * first key pressed during such a dream ends it too (RemoteKeyService → `rescue`).
 */
public class Screensaver extends DreamService {
    /** A dream window normally arrives in well under a second; past this, the dream is a ghost and ends itself. */
    private static final long GHOST_MS = 6_000;
    private static WeakReference<Screensaver> current = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DreamView view;
    private boolean attached, started;
    private long createdAt;

    private final Runnable ghostCheck = () -> {
        if (attached) return;
        AppLog.w("screensaver: no window " + GHOST_MS + " ms after the dream began; ending it so the TV stays usable");
        endGhost();
    };

    @Override public void onCreate() {
        super.onCreate();
        createdAt = SystemClock.uptimeMillis();
        current = new WeakReference<>(this);
        handler.postDelayed(ghostCheck, GHOST_MS);
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        handler.removeCallbacks(ghostCheck);
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        try {
            view = new DreamView(this);
            setContentView(view);
        } catch (RuntimeException e) {
            // Never leave the TV in a dream with nothing on screen.
            AppLog.w("screensaver: couldn't build its view (" + e + "); ending the dream");
            view = null;
            finish();
        }
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        started = true;
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        started = false;
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (view != null && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) view.trimMemory();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(ghostCheck);
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    /** Ends a dream that never got its window (finish() also covers a dream the system hasn't attached yet). */
    private void endGhost() {
        try {
            finish();
        } catch (RuntimeException e) {
            AppLog.w("screensaver: finish failed: " + e);
        }
    }

    /** True while a dream of ours is up with nothing on screen (its window never came). */
    private boolean ghost() {
        return !attached && SystemClock.uptimeMillis() - createdAt > 800;
    }

    /** Is the real screensaver showing right now (used by Auto power-off). */
    static boolean showing() {
        Screensaver s = current.get();
        return s != null && s.attached && s.started;
    }

    /**
     * A key reached the accessibility filter while a dream of ours has no window: the key would be lost (nothing on
     * screen has focus), so the dream ends now and the key is used up as the wake-up press, like a real screensaver.
     */
    static boolean rescue(KeyEvent e) {
        Screensaver s = current.get();
        if (s == null || !s.ghost()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            AppLog.w("screensaver: key " + e.getKeyCode() + " during a dream with no window; waking up");
            s.endGhost();
        }
        return e.getKeyCode() != KeyEvent.KEYCODE_HOME;   // Home still goes home
    }
}
