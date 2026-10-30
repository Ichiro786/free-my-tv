package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. Also
 * the host of Auto power-off's warning (an accessibility overlay above any app, with its keys read here) and the
 * safety net for a screensaver that never showed (the key that would be lost wakes the TV instead).
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AutoOff.get(this).attachService(this);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (AutoOff.get(this).onKey(event)) return true;
        if (Screensaver.rescue(event)) return true;
        if (RemoteKeys.onHomeKey(this, event)) return true;
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        AutoOff.get(this).detachService(this);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        AutoOff.get(this).detachService(this);
        super.onDestroy();
    }
}
