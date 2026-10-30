package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Auto power-off's backup alarm: if the :home process was killed, this brings it back to check the time. */
public class AutoOffReceiver extends BroadcastReceiver {
    static final String ACTION = "io.freemytv.AUTO_OFF_CHECK";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        AutoOff off = AutoOff.get(context);
        off.start();
        off.settingsChanged(false);   // re-checks now and re-arms the timer (shows the warning if it's due)
    }
}
