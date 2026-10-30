package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process; in :home (the default process), also starts Auto power-off's timer. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        String name = Application.getProcessName();
        if (name != null && name.endsWith(":home")) AutoOff.get(this).start();
    }
}
