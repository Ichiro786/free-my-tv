package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.IOException;

/**
 * Auto power-off (Home Settings): after N hours of continuous on-time (counted from the last time the screen came on,
 * or from boot), a 20-second warning over whatever is on screen, then the TV goes to standby unless someone cancels.
 *
 * Runs in the :home process (home screen, settings, screensaver and the accessibility service all live there, so it is
 * alive whenever the TV is on in Free My TV mode). No foreground service: a dynamic receiver follows the screen, a
 * Handler times the warning, and an inexact AlarmManager alarm brings the process back if it was killed.
 *
 * The warning, in order of preference:
 *  1. an accessibility overlay from RemoteKeyService (no permission, above every app, keys read by the service's key
 *     filter, never takes focus away from the app that is playing);
 *  2. a SYSTEM_ALERT_WINDOW overlay (granted over the TV's own ADB when the feature is turned on);
 *  3. a translucent activity (AutoOffActivity), which Android only lets start from the background in some cases (our
 *     home screen in front, for one), so it is the last resort; never over a running screensaver.
 * Turning off, in order, checking after each that the screen really went off:
 *  1. the accessibility service's GLOBAL_ACTION_LOCK_SCREEN (Android 9+): PowerManager.goToSleep in the system, the
 *     same standby as the remote's power button;
 *  2. the TV's own ADB (already trusted in Free My TV mode; never shows the "Allow debugging?" prompt here):
 *     `input keyevent KEYCODE_SLEEP`, which only ever turns the screen off;
 *  3. `input keyevent KEYCODE_POWER`, only if the screen is still on a moment before (it toggles).
 * The method that worked is tried first next time.
 */
final class AutoOff {
    private static final long HOUR = 3_600_000L, RECHECK_MAX = 30 * 60_000L, RETRY_NO_WINDOW = 10 * 60_000L;
    private static final long ALARM_WINDOW = 10 * 60_000L, SLEPT_SINCE = 60_000L;
    private static final int NONE = 0, A11Y = 1, OVERLAY = 2, ACTIVITY = 3;
    private static AutoOff instance;

    static synchronized AutoOff get(Context c) {
        if (instance == null) instance = new AutoOff(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PowerManager power;
    private final SharedPreferences st;
    private boolean started, dreaming, waitingForDream, turningOff;
    private AccessibilityService service;
    private AutoOffView popup;
    private int host = NONE;
    private WindowManager hostWm;
    private AutoOffActivity activity;
    private boolean waitingForActivity;

    private final Runnable check = this::check;
    private final Runnable activityTimeout = () -> {
        if (!waitingForActivity) return;
        waitingForActivity = false;
        AppLog.w("auto power-off: no way to show the warning (no accessibility service, no overlay permission, "
                + "activity start blocked); trying again in 10 min");
        main.removeCallbacks(check);
        main.postDelayed(check, RETRY_NO_WINDOW);
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                markOn("screen on");
                if (popup != null && !turningOff) dismissNow();
                schedule();
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                markOff();
                dismissNow();   // the TV went to standby (by us or by hand): no warning left behind
                schedule();
            } else if (Intent.ACTION_DREAMING_STARTED.equals(a)) {
                dreaming = true;
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(a)) {
                dreaming = false;
                if (waitingForDream) {
                    waitingForDream = false;
                    main.removeCallbacks(check);
                    main.postDelayed(check, 1_500);
                }
            }
        }
    };

    private AutoOff(Context app) {
        this.app = app;
        power = app.getSystemService(PowerManager.class);
        st = app.getSharedPreferences("autooff", Context.MODE_PRIVATE);
    }

    // MARK: - Start, settings, the accessibility service

    /** Once per process (App.onCreate in :home, the alarm, the accessibility service). */
    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        try {
            app.registerReceiver(screen, f);
        } catch (RuntimeException e) {
            AppLog.w("auto power-off: screen receiver failed: " + e);
        }
        sync();
        schedule();
    }

    /** Settings changed it. Turning it on starts the count now (not hours ago, when the TV came on). */
    void settingsChanged(boolean justTurnedOn) {
        start();
        if (justTurnedOn) st.edit().putLong("armedAt", SystemClock.elapsedRealtime()).apply();
        if (!Prefs.autoOff() && popup != null && !turningOff) popup.exit();
        schedule();
    }

    void attachService(AccessibilityService s) {
        service = s;
        start();
    }

    void detachService(AccessibilityService s) {
        if (service != s) return;
        service = null;
        if (host == A11Y && !turningOff) {
            dismissNow();
            main.removeCallbacks(check);
            main.postDelayed(check, 2_000);   // shows again another way, if it's still due
        }
    }

    // MARK: - On-time

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    /** Time the CPU has spent suspended since boot: it only grows while the screen is off. */
    private static long slept() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    private void markOn(String why) {
        st.edit().putLong("onAt", now()).putLong("sleptAt", slept()).putBoolean("on", true).apply();
        AppLog.w("auto power-off: on-time starts now (" + why + ")");
    }

    private void markOff() {
        st.edit().putBoolean("on", false).putLong("offAt", now()).apply();
    }

    /**
     * Brings the saved state up to date: a reboot (boot count) means on since boot; if the screen is on but we missed
     * it coming on (the process was dead, or the CPU slept since), on-time starts now.
     */
    private void sync() {
        int boot = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot != st.getInt("boot", -2)) {
            st.edit().putInt("boot", boot).putLong("onAt", 0).putLong("sleptAt", 0).putBoolean("on", true)
                    .putLong("armedAt", 0).apply();
        }
        if (!st.contains("onAt")) markOn("first run");
        if (!power.isInteractive()) return;
        if (!st.getBoolean("on", true)) markOn("screen was on, its event was missed");
        else if (slept() - st.getLong("sleptAt", 0) > SLEPT_SINCE) markOn("the TV slept since");
    }

    private long dueAt() {
        long start = Math.max(st.getLong("onAt", 0), st.getLong("armedAt", 0));
        return start + Prefs.autoOffHours() * HOUR;
    }

    private void schedule() {
        main.removeCallbacks(check);
        if (!Prefs.autoOff()) {
            cancelAlarm();
            return;
        }
        long due = dueAt();
        main.postDelayed(check, Math.max(1_000, Math.min(due - now(), RECHECK_MAX)));
        setAlarm(due);
    }

    private void check() {
        if (!Prefs.autoOff()) {
            cancelAlarm();
            return;
        }
        if (popup != null || turningOff || waitingForActivity) return;
        sync();
        if (!power.isInteractive()) {
            schedule();   // off already: the count starts again when the screen comes on
            return;
        }
        if (now() + 1_500 < dueAt()) {
            schedule();
            return;
        }
        show();
    }

    // MARK: - Backup alarm (brings :home back if it was killed)

    private PendingIntent alarmIntent() {
        Intent i = new Intent(app, AutoOffReceiver.class).setAction(AutoOffReceiver.ACTION);
        return PendingIntent.getBroadcast(app, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void setAlarm(long due) {
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            // Not a wake-up alarm: with the TV off nothing needs to happen; inexact, so no special permission.
            am.setWindow(AlarmManager.ELAPSED_REALTIME, due, ALARM_WINDOW, alarmIntent());
        } catch (RuntimeException e) {
            AppLog.w("auto power-off: alarm failed: " + e);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent());
    }

    // MARK: - The warning

    private final AutoOffView.Listener listener = new AutoOffView.Listener() {
        @Override public void onCancel() {
            AppLog.w("auto power-off: cancelled; counting again from now");
            st.edit().putLong("armedAt", now()).apply();
            if (popup != null) popup.exit();
            schedule();
        }

        @Override public void onTimeUp() {
            if (popup != null) popup.turningOff();
            powerOff();
        }

        @Override public void onGone() {
            removeHost();
        }
    };

    private int hoursShown() {
        long on = now() - Math.max(st.getLong("onAt", 0), st.getLong("armedAt", 0));
        return (int) Math.max(Prefs.autoOffHours(), on / HOUR);
    }

    private void show() {
        Ui.init(app);
        int hours = hoursShown();
        AppLog.w("auto power-off: " + hours + " h on; showing the 20-second warning");
        if (service != null && addOverlay(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, false, hours, A11Y)) return;
        if (Settings.canDrawOverlays(app) && addOverlay(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, true, hours, OVERLAY)) return;
        if (dreaming || Screensaver.showing()) {
            // An activity would open under the screensaver, unseen: wait for it to end.
            AppLog.w("auto power-off: screensaver showing and no overlay available; waiting for it to end");
            waitingForDream = true;
            return;
        }
        waitingForActivity = true;
        main.postDelayed(activityTimeout, 4_000);
        try {
            app.startActivity(new Intent(app, AutoOffActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException e) {
            AppLog.w("auto power-off: activity didn't start: " + e);
        }
    }

    private boolean addOverlay(Context c, int type, boolean focusable, int hours, int kind) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        if (wm == null) return false;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, type, flags, PixelFormat.TRANSLUCENT);
        lp.setTitle("FreeMyTV:AutoOff");
        lp.windowAnimations = 0;   // our own entrance
        AutoOffView v = new AutoOffView(c, hours, listener);
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            AppLog.w("auto power-off: overlay " + kind + " refused: " + e);
            return false;
        }
        popup = v;
        host = kind;
        hostWm = wm;
        return true;
    }

    /** AutoOffActivity asks for its view (null: it isn't wanted any more and finishes). */
    AutoOffView attachActivity(AutoOffActivity a) {
        if (!waitingForActivity || popup != null) return null;
        waitingForActivity = false;
        main.removeCallbacks(activityTimeout);
        AutoOffView v = new AutoOffView(a, hoursShown(), listener);
        popup = v;
        host = ACTIVITY;
        activity = a;
        return v;
    }

    /** The activity went away underneath (Home, another app): someone is using the TV, so it counts as Cancel. */
    void activityLeft(AutoOffActivity a) {
        if (activity != a) return;
        if (popup != null && popup.counting()) {
            AppLog.w("auto power-off: warning left (another app came up); counting again from now");
            st.edit().putLong("armedAt", now()).apply();
            schedule();
        }
        if (!turningOff) {
            popup = null;
            host = NONE;
            activity = null;
        }
    }

    private void removeHost() {
        AutoOffView v = popup;
        int kind = host;
        popup = null;
        host = NONE;
        if (v == null) return;
        if (kind == ACTIVITY) {
            AutoOffActivity a = activity;
            activity = null;
            if (a != null) a.finishQuietly();
        } else if (hostWm != null) {
            try {
                hostWm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
        }
        hostWm = null;
    }

    /** Gone at once, no animation (the screen went off, or the service that drew it left). */
    private void dismissNow() {
        waitingForDream = false;
        if (popup != null) removeHost();
    }

    /** Keys from RemoteKeyService's filter. True if used up here. */
    boolean onKey(KeyEvent e) {
        AutoOffView v = popup;
        return v != null && v.handleKey(e);
    }

    // MARK: - Turning the TV off

    private void powerOff() {
        if (turningOff) return;
        turningOff = true;
        final AccessibilityService s = service;
        final String first = st.getString("method", "a11y");
        new Thread(() -> {
            String[] order = "sleep".equals(first) ? new String[] {"sleep", "a11y", "power"} : new String[] {"a11y", "sleep", "power"};
            String used = null;
            for (String m : order) {
                if (!power.isInteractive()) {
                    used = "already off";
                    break;
                }
                try {
                    if (m.equals("a11y")) {
                        if (s == null || !s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) continue;
                    } else {
                        try (Adb adb = Adb.connectTrusted(app)) {
                            // POWER toggles: only if the screen is still on right now.
                            if (!power.isInteractive()) {
                                used = "already off";
                                break;
                            }
                            adb.shell("input keyevent " + (m.equals("sleep") ? "KEYCODE_SLEEP" : "KEYCODE_POWER"));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    AppLog.w("auto power-off: " + m + " didn't work: " + e.getMessage());
                    continue;
                }
                if (waitForOff(3_500)) {
                    used = m;
                    break;
                }
                AppLog.w("auto power-off: still on after " + m);
            }
            final String result = used;
            main.post(() -> finishPowerOff(result));
        }, "free-my-tv-auto-off").start();
    }

    private boolean waitForOff(long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            if (!power.isInteractive()) return true;
            SystemClock.sleep(100);
        }
        return !power.isInteractive();
    }

    private void finishPowerOff(String used) {
        turningOff = false;
        if (used != null) {
            AppLog.w("auto power-off: TV turned off (" + used + ")");
            if (!used.equals("already off")) st.edit().putString("method", used).apply();
            markOff();
            dismissNow();
            schedule();
            return;
        }
        AppLog.w("auto power-off: no method turned the TV off");
        st.edit().putLong("armedAt", now()).apply();
        if (popup != null) popup.failed();
        schedule();
    }
}
