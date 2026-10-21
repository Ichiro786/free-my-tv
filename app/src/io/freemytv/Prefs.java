package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - TMDB key (typed on the TV; the one in config.properties is the default)

    static String tmdbKey() {
        String typed = sp().getString("tmdbKey", "").trim();
        return typed.isEmpty() ? Config.TMDB_KEY.trim() : typed;
    }

    static void setTmdbKey(String v) { put("tmdbKey", v == null ? "" : v.trim()); }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City (from config.properties)

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** Date and time widgets where the crypto quotes used to be (the tab row and the drawer card). */
    static boolean clockWidget() { return sp().getBoolean("clockWidget", true); }
    static void setClockWidget(boolean on) { put("clockWidget", on); }

    /** Size of the home widgets together, in percent. */
    static final int[] WIDGET_SIZES = {70, 80, 90, 100, 110, 120, 130, 140, 150};
    static int widgetSize() { return sp().getInt("widgetSize", 100); }
    static void setWidgetSize(int pct) { sp().edit().putInt("widgetSize", pct).apply(); version++; }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }

    // MARK: - Shelf look (Home Settings): card size, corner radius, focus effect

    static final int[] TILE_SIZES = {70, 75, 80, 85, 90, 95, 100};   // percent of the largest card the shelf fits
    static int tileSize() { return Math.max(70, Math.min(100, sp().getInt("tileSize", 100))); }
    static void setTileSize(int pct) { sp().edit().putInt("tileSize", pct).apply(); }
    static final int[] TILE_RADII = {0, 4, 8, 12, 16, 20, 22, 26, 30, 36, 44, 56};
    static int tileRadius() { return sp().getInt("tileRadius", 22); }
    static void setTileRadius(int px) { sp().edit().putInt("tileRadius", px).apply(); }
    /** "outline": the focused card keeps its size and gets a shimmering outline; "grow": it grows 20%. */
    static String focusStyle() { return sp().getString("focusStyle", "outline"); }
    static void setFocusStyle(String v) { put("focusStyle", v); }
    /** How apps open: "quick" (a 170 ms fade-in), "zoom" (the system zoom out of the card) or "off". */
    static String launchAnim() { return sp().getString("launchAnim", "quick"); }
    static void setLaunchAnim(String v) { put("launchAnim", v); }

    // MARK: - Dock, focus growth, trailers, pinned apps

    /** Dock (glass shelf) size: 0 = automatic (hugs the cards), else percent of the original bar. */
    static final int[] DOCK_SIZES = {0, 80, 85, 90, 95, 100, 105, 110};
    static int dockSize() { return sp().getInt("dockSize", 0); }
    static void setDockSize(int pct) { sp().edit().putInt("dockSize", pct).apply(); }
    /** How much a focused card grows with the Grow effect: 0 = automatic, else percent. */
    static final int[] GROW_AMOUNTS = {0, 6, 8, 10, 12, 14, 16, 18, 20, 25};
    static int growAmount() { return sp().getInt("growAmount", 0); }
    static void setGrowAmount(int pct) { sp().edit().putInt("growAmount", pct).apply(); }
    /** Trailer previews on For You's big card: "sound", "muted" or "off". */
    static String trailers() { return sp().getString("trailers", "sound"); }
    static void setTrailers(String v) { put("trailers", v); }

    /** Apps pinned from All Apps (long press OK): a row in the bottom drawer, in pin order. */
    static List<String> pinned() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("pinned", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Pins the app (at the end) or unpins it; returns true if it is pinned now. */
    static boolean togglePinned(String pkg) {
        List<String> list = pinned();
        boolean pin = !list.remove(pkg);
        if (pin) list.add(pkg);
        StringBuilder sb = new StringBuilder();
        for (String p : list) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("pinned", sb.toString());
        return pin;
    }

    /** Live TV: "auto" (the TV's tuner app if there is one, else the built-in IPTV), "tuner" or "iptv". */
    static String liveTv() { return sp().getString("liveTv", "auto"); }
    static void setLiveTv(String v) { sp().edit().putString("liveTv", v).apply(); }
    /** For You titles open in: "nuvio" (the title's page in Nuvio, when installed) or "service" (Netflix and co). */
    static String forYouApp() { return sp().getString("forYouApp", "nuvio"); }
    static void setForYouApp(String v) { put("forYouApp", v); }
    /** Holding the remote's Home button opens Free My TV's settings. */
    static boolean homeHold() { return sp().getBoolean("homeHold", true); }
    static void setHomeHold(boolean v) { sp().edit().putBoolean("homeHold", v).apply(); }
}
