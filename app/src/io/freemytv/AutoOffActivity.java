package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Last resort for Auto power-off's warning when neither overlay is available (no accessibility service, no overlay
 * permission): the same view in a translucent activity. Only opened by AutoOff.
 */
public class AutoOffActivity extends Activity {
    private AutoOffView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = AutoOff.get(this).attachActivity(this);
        if (view == null) {
            finishQuietly();
            return;
        }
        setContentView(view);
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && view.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        super.onStop();
        if (view != null && !isFinishing()) {
            AutoOff.get(this).activityLeft(this);
            finishQuietly();
        }
    }

    void finishQuietly() {
        view = null;
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }
}
