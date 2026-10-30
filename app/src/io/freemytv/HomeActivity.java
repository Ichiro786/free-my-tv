package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 *
 * The screen draws only between onResume and onPause. A screensaver that begins but never shows (or any other window
 * that pauses us and then goes away without a new onResume) used to leave it frozen on the last frame: it is now
 * brought back whenever the window has focus again, a key arrives, or the system says the dream ended or the screen
 * came on.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean registered;

    /** The dream ended, the screen came on, or the TV was unlocked: if we are the visible window, draw again. */
    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(recover);
            handler.postDelayed(recover, 350);   // focus moves back to us a moment after the broadcast
        }
    };

    private final Runnable recover = () -> recoverIfStale("broadcast");

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Free My TV teve um problema.\nVoltando para o Início original da TV…",
                    "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        IntentFilter f = new IntentFilter(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wake, f);
        registered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (home != null) home.pause();   // already paused normally; harmless twice
        super.onStop();
    }

    /** Focus came back to the home screen without an onResume (a dream that never showed): draw again. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) recoverIfStale("focus");
    }

    /** We are on screen with focus but stopped drawing: start again (the same as onResume does). */
    private void recoverIfStale(String why) {
        if (home == null || home.isRunning() || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        AppLog.w("home: drawing again after a pause with no resume (" + why + ")");
        home.resume();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (home != null) home.trimMemory(level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (home != null) home.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(recover);
        if (registered) {
            try { unregisterReceiver(wake); } catch (IllegalArgumentException ignored) {}
            registered = false;
        }
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // A key reaching us means we have focus: if drawing had stopped, every press would change nothing on screen.
        if (home != null && !home.isRunning()) recoverIfStale("key");
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
