package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** The key codes are TCL's: on other brands they can be other buttons, so nothing here runs there. */
    static boolean supported() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == e.getKeyCode();
        if (!ours) return false;
        String pkg = Prefs.keyApp(e.getKeyCode());
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = HomeApps.launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    // MARK: - Hold Home: Free My TV's settings (Home Settings > Hold Home for settings)

    private static final long HOLD_MS = 650;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean homeDown, homeHeld;
    private static Runnable holdAction;

    /**
     * The service sees Home before the system does. A press shorter than 0.65 s is handed back as the normal Home
     * (performGlobalAction), so Home works exactly as before; held longer, it opens Free My TV's settings instead.
     */
    static boolean onHomeKey(final android.accessibilityservice.AccessibilityService s, KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !Prefs.homeHold()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && !homeDown) {
                homeDown = true;
                homeHeld = false;
                holdAction = () -> {
                    if (!homeDown) return;
                    homeHeld = true;
                    AppLog.w("home held: opening settings");
                    Intent i = new Intent(s, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        s.startActivity(i);
                    } catch (RuntimeException ex) {
                        AppLog.w("settings didn't open: " + ex);
                    }
                };
                MAIN.postDelayed(holdAction, HOLD_MS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (holdAction != null) MAIN.removeCallbacks(holdAction);
            boolean wasDown = homeDown;
            homeDown = false;
            if (wasDown && !homeHeld) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            return true;
        }
        return false;
    }
}
