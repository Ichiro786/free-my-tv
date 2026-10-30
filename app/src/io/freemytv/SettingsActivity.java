package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            view.trimMemory();
            if (preview != null) preview.trimMemory();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, THEMES_SCREEN = 6;
        // Main list: Home Screen, Screensaver, Home Settings, Themes, Shelf, Remote Buttons, TV Settings.
        private static final int MAIN_ROWS = 7, M_HOME_SETTINGS = 2, M_THEMES = 3, M_SHELF = 4, M_REMOTE = 5;
        private static final float MAIN_ROW_H = 66, MAIN_ROW_GAP = 6;
        private static final long SKY_MS = 100;   // the aurora moves at most 10 times per second
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final float SET_ROW_H = 54, SET_ROW_GAP = 4;   // Home Settings has more rows: a tighter list
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[24];
        // Auto power-off: the hours row unfolds under the switch (reveal), and the knob glides between hours.
        private final Spring reveal = new Spring(0.46f, 0.84f, 0), hoursKnob = new Spring(0.3f, 0.82f, 0);
        private boolean okPress;
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        // Frames run only while something moves (springs, the busy spinner, the aurora); idle, nothing is scheduled.
        private boolean frameQueued;
        private int calmFrames;
        private String polled = "";
        // The status texts under the main list, made when what they show changes (poll), not on every aurora frame.
        private String textsFor, textsLang, recsLine, noLine;
        private final String[] noticeLines = new String[2];
        private final String[][] metricTexts = new String[3][3];
        private int noticeCount;
        private String versionText;
        private final TextPaint fitPaint = new TextPaint();
        private Paint heading, hint, track, knob;
        // Row labels and values, rebuilt only when a setting (Prefs.version) or the screen changes, not every frame.
        private String[] cachedLabels, cachedValues;
        private int cachedVersion = -1, cachedScreen = -1;
        private List<Integer> rowsCache;
        private int rowsVersion = -1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !state.optString("busy").isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                // Redraw only when what the screen shows changed (state, picks, key, or the minute for "5 min ago").
                String now = state.toString() + '|' + recs.toString() + '|' + hasKey + '|' + System.currentTimeMillis() / 60_000;
                if (nowBusy || !now.equals(polled)) {
                    polled = now;
                    invalidate();
                }
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            scene.setMotion(0.5f, 0.2f);
            applyTheme();
            setFocusable(true);
            setContentDescription("Free My TV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = state.optString("busy");
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
                        return Ui.tr("Início, ", "Home Screen, ") + value;
                    }
                    String[] names = {Ui.tr("Tela de Descanso, ver agora", "Screensaver, preview"), Ui.tr("Ajustes do Início", "Home Settings"),
                            Ui.tr("Temas", "Themes"), Ui.tr("Prateleira", "Shelf"), Ui.tr("Botões do Controle", "Remote Buttons"), Ui.tr("Ajustes da TV", "TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case THEMES_SCREEN:
                case REMOTE_KEYS: {
                    String[] labels = listLabels(), values = listValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private float rowH() { return screen == HOME_SETTINGS ? SET_ROW_H : LIST_ROW_H; }

        /** Home Settings scrolls: this many rows fit, and setScroll is the first one shown. */
        private static final int SET_VISIBLE = 13;
        private int setScroll;

        private void keepSettingVisible() {
            if (screen != HOME_SETTINGS) return;
            int n = listLabels().length;
            if (focus < setScroll + 1) setScroll = focus - 1;
            if (focus > setScroll + SET_VISIBLE - 2) setScroll = focus - SET_VISIBLE + 2;
            setScroll = Math.max(0, Math.min(Math.max(0, n - SET_VISIBLE), setScroll));
        }
        private float rowGap() { return screen == HOME_SETTINGS ? SET_ROW_GAP : LIST_ROW_GAP; }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = LIST_TOP + row * (MAIN_ROW_H + MAIN_ROW_GAP);
                height = MAIN_ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + (row - (screen == HOME_SETTINGS ? setScroll : 0)) * (rowH() + rowGap());
                height = rowH();
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            polled = "";
            reveal.snap(Prefs.autoOff() ? 1 : 0);
            hoursKnob.snap(hoursT(Prefs.autoOffHours()));
            applyTheme();   // the screensaver preview or another screen may have been open
            handler.post(poll);
            wake();
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            frameQueued = false;
            scene.trimMemory();   // the fake aurora's bitmap: made again on the next draw
        }

        void trimMemory() {
            scene.trimMemory();
        }

        /** The Themes setting on this screen's background: palette and aurora motion, right away. */
        private void applyTheme() {
            HomeData.Weather wx = HomeData.get(getContext()).weather;
            Shaders.background(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setAuroraMotion(Prefs.auroraMotion());
            scene.applyPalette();
        }

        /** Any redraw request also restarts the frame loop (it stops by itself once everything is still). */
        @Override public void invalidate() {
            super.invalidate();
            wake();
        }

        private void wake() {
            calmFrames = 0;
            if (!running || frameQueued) return;
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        /** The focus highlight follows the focused row (also set here so a key press starts the springs right away). */
        private void retargetPop() {
            int rows = rowCount();
            for (int i = 0; i < pop.length; i++) pop[i].target = i == focus && i < rows ? 1 : 0;
        }

        @Override public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            if (!running) return;
            retargetPop();
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            reveal.target = Prefs.autoOff() ? 1 : 0;
            moving |= !reveal.settled() || !hoursKnob.settled();
            long up = SystemClock.uptimeMillis();
            if (moving) {
                calmFrames = 0;
                super.invalidate();
            } else if (scene.animating() && up - lastSky >= SKY_MS) {
                super.invalidate();
            }
            Choreographer choreographer = Choreographer.getInstance();
            if (moving || ++calmFrames < 3) {
                frameQueued = true;
                choreographer.postFrameCallback(this);
            } else if (scene.animating()) {
                // Only the aurora moves: wake once per step instead of on every display frame.
                frameQueued = true;
                choreographer.postFrameCallbackDelayed(this, Math.max(1, SKY_MS - (up - lastSky)));
            }
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            textsFor = null;
            heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            track = new Paint(Paint.ANTI_ALIAS_FLAG);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setStrokeWidth(8 * k);
            knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        private boolean busy() {
            // A switch cut off midway (the app updated or killed) leaves "busy" behind: after 3 minutes, it's stale.
            boolean running = !state.optString("busy").isEmpty()
                    && System.currentTimeMillis() - state.optLong("busyAt", 0) < 180_000;
            return running || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return MAIN_ROWS;
                case THEMES_SCREEN: return 2;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return listLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            retargetPop();
            for (Spring s : pop) s.step(dt);
            reveal.target = Prefs.autoOff() ? 1 : 0;
            reveal.step(dt);
            hoursKnob.step(dt);
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            if (versionText == null) versionText = versionName(getContext());
            c.drawText(versionText, cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
            for (int i = 0; i < MAIN_ROWS; i++) {
                float y = (LIST_TOP + i * (MAIN_ROW_H + MAIN_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + MAIN_ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(mainLabel(i), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i == M_THEMES) {
                    // The current background, then the chevron.
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(backgroundName(), right - cw - 16 * k, base, rowValue);
                } else if (i >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    fitPaint.set(rowValue);
                    String shown = TextUtils.ellipsize(text, fitPaint, 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(i == 0 ? (working ? "" : mode) : i == 1 ? Ui.tr("Ver agora", "Preview") : "", right, base, rowValue);
                }
            }

            float y = (LIST_TOP + MAIN_ROWS * (MAIN_ROW_H + MAIN_ROW_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        /** Rebuilds the status texts (recommendations, comparison, notices) when the polled state or language changed. */
        private void statusTexts() {
            String lang = Ui.es ? "es" : Ui.en ? "en" : "pt";
            if (polled.equals(textsFor) && lang.equals(textsLang) && recsLine != null) return;
            textsFor = polled;
            textsLang = lang;
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: adicione em Ajustes do Início → Chave do TMDB.", "No TMDB key: add it in Home Settings → TMDB key.");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            recsLine = TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString();
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < 3; i++) {
                metricTexts[i][0] = rows[i][0];
                metricTexts[i][1] = metric(a, rows[i][1]);
                metricTexts[i][2] = metric(b, rows[i][1]);
            }
            JSONArray notices = state.optJSONArray("notices");
            noticeCount = 0;
            for (int i = 0; notices != null && i < notices.length() && noticeCount < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String text = ago(o.optLong("at")) + " · " + o.optString("text");
                noticeLines[noticeCount++] = TextUtils.ellipsize(text, notice, 790 * k, TextUtils.TruncateAt.END).toString();
            }
            noLine = Ui.tr("Nenhum ainda.", "None yet.");
        }

        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            statusTexts();
            c.drawText(recsLine, x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            statusTexts();
            for (int i = 0; i < metricTexts.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(metricTexts[i][0], x, base, cellLabel);
                c.drawText(metricTexts[i][1], colA, base, cellValue);
                c.drawText(metricTexts[i][2], colB, base, cellValue);
            }
            return y + (46 + 40 * metricTexts.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            statusTexts();
            int shown = 0;
            for (int i = 0; i < noticeCount; i++) {
                if (shown > 0 && y + (42 + 38 * shown) * k > h - 8 * k) break;   // the seventh row (Themes) took the room
                c.drawText(noticeLines[i], x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(noLine, x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    keepSettingVisible();
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    keepSettingVisible();
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == HOME_SETTINGS) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == THEMES_SCREEN) {
                        adjustTheme();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                        else if (e.getRepeatCount() == 1 && okDown && screen == SHELF_EDIT) {
                            okDown = false;   // a long press: the app's icon, not on/off the shelf
                            openIconEditor();
                        }
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (screen != SHELF_EDIT) return false;
                    if (down && e.getRepeatCount() == 0) openIconEditor();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = screen == HOME_SETTINGS ? M_HOME_SETTINGS : screen == THEMES_SCREEN ? M_THEMES
                                        : screen == SHELF_EDIT ? M_SHELF : screen == REMOTE_KEYS ? M_REMOTE : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        // Home Settings rows by id (the list is built each time: "Grow amount" only shows with the Grow focus).
        private static final int R_LANGUAGE = 0, R_TEMP = 1, R_THEME = 2, R_WEATHER = 3, R_QUOTES = 4, R_TMDB = 5,
                R_DOCK = 6, R_ICON = 7, R_RADIUS = 8, R_FOCUS = 9, R_GROW = 10, R_OPENING = 11, R_TRAILERS = 12, R_LIVE = 13, R_FORYOU = 14, R_HOMEHOLD = 15, R_WSIZE = 16, R_AUTOOFF = 17, R_AUTOOFF_H = 18, R_COIN = 100;

        private List<Integer> settingRows() {
            if (rowsCache != null && rowsVersion == Prefs.version) return rowsCache;
            rowsVersion = Prefs.version;
            List<Integer> r = rowsCache = new ArrayList<>();
            for (int id : new int[] {R_LANGUAGE, R_TEMP, R_THEME, R_WEATHER, R_QUOTES, R_WSIZE, R_TMDB, R_DOCK, R_ICON, R_RADIUS, R_FOCUS}) r.add(id);
            if (!"outline".equals(Prefs.focusStyle())) r.add(R_GROW);
            r.add(R_OPENING);
            r.add(R_TRAILERS);
            r.add(R_FORYOU);
            r.add(R_LIVE);
            r.add(R_HOMEHOLD);
            r.add(R_AUTOOFF);
            if (Prefs.autoOff()) r.add(R_AUTOOFF_H);   // the hours slider shows only while it's on (animated in)
            return r;   // the crypto coin rows are gone with the quotes
        }

        private int rowId(int index) {
            List<Integer> r = settingRows();
            return index >= 0 && index < r.size() ? r.get(index) : -1;
        }

        private String[] settingLabels() {
            List<Integer> rows = settingRows();
            String[] l = new String[rows.size()];
            for (int i = 0; i < l.length; i++) {
                int id = rows.get(i);
                switch (id) {
                    case R_LANGUAGE: l[i] = Ui.tr("Idioma", "Language"); break;
                    case R_TEMP: l[i] = Ui.tr("Temperatura", "Temperature"); break;
                    case R_THEME: l[i] = Ui.tr("Tema", "Theme"); break;
                    case R_WEATHER: l[i] = Ui.tr("Widget do clima", "Weather widget"); break;
                    case R_QUOTES: l[i] = Ui.tr("Widget de data e hora", "Date & time widget"); break;
                    case R_WSIZE: l[i] = Ui.tr("Tamanho dos widgets", "Widget size"); break;
                    case R_TMDB: l[i] = Ui.tr("Chave do TMDB", "TMDB key"); break;
                    case R_DOCK: l[i] = Ui.tr("Tamanho da barra", "Dock size"); break;
                    case R_ICON: l[i] = Ui.tr("Tamanho dos ícones", "App icon size"); break;
                    case R_RADIUS: l[i] = Ui.tr("Cantos dos ícones", "Icon corner radius"); break;
                    case R_FOCUS: l[i] = Ui.tr("Efeito de foco", "Focus effect"); break;
                    case R_GROW: l[i] = Ui.tr("   Quanto aumentar", "   Grow amount"); break;
                    case R_OPENING: l[i] = Ui.tr("Abrir apps", "App opening"); break;
                    case R_TRAILERS: l[i] = Ui.tr("Trailers no Para você", "Trailers on For You"); break;
                    case R_FORYOU: l[i] = Ui.tr("Abrir títulos do Para você em", "Open For You titles in"); break;
                    case R_LIVE: l[i] = Ui.tr("TV ao vivo", "Live TV"); break;
                    case R_HOMEHOLD: l[i] = Ui.tr("Segurar Início: ajustes", "Hold Home for settings"); break;
                    case R_AUTOOFF: l[i] = Ui.tr("Desligar sozinha", "Auto power-off", "Apagado automático"); break;
                    case R_AUTOOFF_H: l[i] = autoOffAfterLabel(); break;
                    default: {
                        int c = id - R_COIN;
                        l[i] = c == 0 ? "Bitcoin" : c == 1 ? "Ethereum" : c == 2 ? "Solana" : Ui.tr("Dólar", "Dollar");
                    }
                }
            }
            return l;
        }

        private String[] settingValues() {
            List<Integer> rows = settingRows();
            String[] v = new String[rows.size()];
            for (int i = 0; i < v.length; i++) {
                int id = rows.get(i);
                switch (id) {
                    case R_LANGUAGE: {
                        String lang = Prefs.language();
                        v[i] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español" : Ui.tr("Automático", "Automatic");
                        break;
                    }
                    case R_TEMP: {
                        String unit = Prefs.temperature();
                        v[i] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
                        break;
                    }
                    case R_THEME: {
                        String theme = Prefs.theme();
                        v[i] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
                        break;
                    }
                    case R_WEATHER: v[i] = onOff(Prefs.weather()); break;
                    case R_QUOTES: v[i] = onOff(Prefs.clockWidget()); break;
                    case R_WSIZE: v[i] = Prefs.widgetSize() + "%"; break;
                    case R_TMDB: {
                        String key = Prefs.tmdbKey();
                        v[i] = key.isEmpty() ? Ui.tr("Nenhuma", "None") : "••••" + key.substring(Math.max(0, key.length() - 4));
                        break;
                    }
                    case R_DOCK: v[i] = Prefs.dockSize() == 0 ? Ui.tr("Automático", "Auto") : Prefs.dockSize() + "%"; break;
                    case R_ICON: v[i] = Prefs.tileSize() + "%"; break;
                    case R_RADIUS: v[i] = Prefs.tileRadius() == 0 ? Ui.tr("Retos", "Square") : Prefs.tileRadius() + " px"; break;
                    case R_FOCUS: {
                        String fs = Prefs.focusStyle();
                        v[i] = "grow".equals(fs) ? Ui.tr("Aumentar", "Grow") : "growshimmer".equals(fs)
                                ? Ui.tr("Aumentar + brilho líquido", "Grow + liquid shimmer") : Ui.tr("Contorno brilhante", "Shimmer outline");
                        break;
                    }
                    case R_GROW: v[i] = Prefs.growAmount() == 0 ? Ui.tr("Automático", "Auto") : "+" + Prefs.growAmount() + "%"; break;
                    case R_OPENING: {
                        String la = Prefs.launchAnim();
                        v[i] = la.equals("zoom") ? Ui.tr("Zoom do sistema", "System zoom") : la.equals("off") ? Ui.tr("Instantâneo", "Instant") : Ui.tr("Rápido", "Quick fade");
                        break;
                    }
                    case R_FORYOU: v[i] = "nuvio".equals(Prefs.forYouApp()) ? "Nuvio" : Ui.tr("App de streaming", "Streaming app"); break;
                    case R_LIVE: {
                        String m = Prefs.liveTv();
                        boolean tuner = HomeApps.liveTv(getContext()) != null;
                        v[i] = m.equals("iptv") ? Ui.tr("IPTV embutido", "Built-in IPTV") : m.equals("tuner") ? Ui.tr("Sintonizador da TV", "TV tuner")
                                : Ui.tr("Automático", "Auto") + " (" + (tuner ? Ui.tr("sintonizador", "tuner") : "IPTV") + ")";
                        break;
                    }
                    case R_HOMEHOLD: v[i] = onOff(Prefs.homeHold()); break;
                    case R_AUTOOFF: v[i] = onOff(Prefs.autoOff()); break;
                    case R_AUTOOFF_H: v[i] = hoursText(Prefs.autoOffHours()); break;
                    case R_TRAILERS: {
                        String t = Prefs.trailers();
                        v[i] = t.equals("off") ? Ui.tr("Desligado", "Off") : t.equals("muted") ? Ui.tr("Sem som", "Muted") : Ui.tr("Com som", "With sound");
                        break;
                    }
                    default:
                        // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
                        v[i] = onOff(Prefs.quotes() && Prefs.coin(id - R_COIN));
                }
            }
            return v;
        }

        // MARK: - Auto power-off

        private static String autoOffAfterLabel() {
            return Ui.tr("   Desligar depois de", "   Turn off after", "   Apagar después de");
        }

        private static String hoursText(int h) {
            return h + " h";
        }

        /** Where the knob sits for these hours (0 to 1 along the track). */
        private static float hoursT(int h) {
            return (h - Prefs.AUTO_OFF_MIN) / (float) (Prefs.AUTO_OFF_MAX - Prefs.AUTO_OFF_MIN);
        }

        /** The hours row just appeared under the switch: scroll so it is on screen. */
        private void showRevealedRow() {
            rowsCache = null;
            cachedLabels = null;
            int at = settingRows().indexOf(R_AUTOOFF_H);
            if (at >= 0 && at > setScroll + SET_VISIBLE - 1) setScroll = at - SET_VISIBLE + 1;
        }

        /**
         * The "Turn off after" slider row, unfolding with `reveal` (0 to 1): it fades in and slides down from under the
         * switch while the track draws itself from the left and the knob grows in. Also drawn while it folds away
         * (after the switch goes off), when it is no longer a row that can be focused.
         */
        private void drawHoursRow(Canvas c, float top, float f) {
            float r = Ui.clamp(reveal.value);
            if (r <= 0.01f) return;
            float e = r * r * (3 - 2 * r);
            if (f <= 0.01f) rowRect.set(LIST_X * k, top, LIST_R * k, top + rowH() * k);   // else: the focused (lifted) row
            rowRect.offset(0, -16 * k * (1 - e));
            float base = rowRect.centerY() + Ui.halfCap(rowLabel);
            rowLabel.setColor(Ui.alpha(blend(0xF2FFFFFF, INK, f), e));
            c.drawText(autoOffAfterLabel(), rowRect.left + 30 * k, base, rowLabel);
            String value = cachedHours == Prefs.autoOffHours() ? cachedHoursText : (cachedHoursText = hoursText(cachedHours = Prefs.autoOffHours()));
            rowValue.setColor(Ui.alpha(blend(0xB3FFFFFF, 0xB31C1C1E, f), e));
            c.drawText(value, rowRect.right - 30 * k, base, rowValue);
            float tr = rowRect.right - 30 * k - rowValue.measureText("24 h") - 28 * k, tl = tr - 300 * k, cy = rowRect.centerY();
            float drawn = tl + (tr - tl) * e;   // the track draws itself in
            track.setColor(Ui.alpha(blend(0x40FFFFFF, 0x331C1C1E, f), e));
            c.drawLine(tl, cy, drawn, cy, track);
            float kx = tl + (tr - tl) * Ui.clamp(hoursKnob.value) * e;
            track.setColor(Ui.alpha(blend(0xE6FFFFFF, 0xE61C1C1E, f), e));
            c.drawLine(tl, cy, kx, cy, track);
            float kr = 15 * k * (0.4f + 0.6f * e);
            knob.setColor(Ui.alpha(0x33000000, e));
            c.drawCircle(kx, cy + 2 * k, kr + k, knob);
            knob.setColor(Ui.alpha(0xFFFFFFFF, e));
            c.drawCircle(kx, cy, kr, knob);
        }

        private int cachedHours = -1;
        private String cachedHoursText = "";

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        // MARK: - Themes

        private static String backgroundName() {
            return "sky".equals(Prefs.background()) ? Ui.tr("Céu", "Sky") : Ui.tr("Escuro", "Dark");
        }

        private String[] themeLabels() {
            return new String[] {Ui.tr("Fundo", "Background"), Ui.tr("Movimento da aurora", "Aurora motion")};
        }

        private String[] themeValues() {
            return new String[] {backgroundName(), onOff(Prefs.auroraMotion())};
        }

        /** OK or ←/→ on a Themes row: both have two values, so any press flips it. Takes effect right away. */
        private void adjustTheme() {
            if (focus == 0) Prefs.setBackground("sky".equals(Prefs.background()) ? "dark" : "sky");
            else Prefs.setAuroraMotion(!Prefs.auroraMotion());
            applyTheme();
            lastSky = 0;
        }

        private String mainLabel(int i) {
            switch (i) {
                case 0: return Ui.tr("Início", "Home Screen");
                case 1: return Ui.tr("Tela de Descanso", "Screensaver");
                case M_HOME_SETTINGS: return Ui.tr("Ajustes do Início", "Home Settings");
                case M_THEMES: return Ui.tr("Temas", "Themes");
                case M_SHELF: return Ui.tr("Prateleira", "Shelf");
                case M_REMOTE: return Ui.tr("Botões do Controle", "Remote Buttons");
                default: return Ui.tr("Ajustes da TV", "TV Settings");
            }
        }

        /** The rows of the current list screen (Home Settings, Themes or Remote Buttons), rebuilt only on a change. */
        private String[] listLabels() {
            refreshListCache();
            return cachedLabels;
        }

        private String[] listValues() {
            refreshListCache();
            return cachedValues;
        }

        private void refreshListCache() {
            if (cachedLabels != null && cachedVersion == Prefs.version && cachedScreen == screen) return;
            cachedVersion = Prefs.version;
            cachedScreen = screen;
            boolean keys = screen == REMOTE_KEYS, themes = screen == THEMES_SCREEN;
            cachedLabels = keys ? keyLabels() : themes ? themeLabels() : settingLabels();
            cachedValues = keys ? keyValues() : themes ? themeValues() : settingValues();
        }

        private String[] keyLabels() {
            return new String[] {keyLabel(0), keyLabel(1), keyLabel(2), keyLabel(3)};
        }

        private static String keyLabel(int i) {
            switch (i) {
                case 0: return Ui.tr("Botão Netflix", "Netflix button");
                case 1: return Ui.tr("Botão YouTube", "YouTube button");
                case 2: return Ui.tr("Botão Prime Video", "Prime Video button");
                default: return Ui.tr("Botão TCL Channel", "TCL Channel button");
            }
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            cachedLabels = null;
            appPkgs.add("");
            appLabels.add(Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            cachedLabels = null;   // some settings are saved without bumping Prefs.version
            rowsCache = null;
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            int id = rowId(focus);
            switch (id) {
                case R_LANGUAGE: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case R_TEMP: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case R_THEME: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case R_WEATHER: Prefs.setWeather(!Prefs.weather()); break;
                case R_QUOTES: Prefs.setClockWidget(!Prefs.clockWidget()); break;
                case R_WSIZE: { int at = java.util.Arrays.binarySearch(Prefs.WIDGET_SIZES, Prefs.widgetSize()); if (at < 0) at = 3;
                    Prefs.setWidgetSize(Prefs.WIDGET_SIZES[Math.max(0, Math.min(Prefs.WIDGET_SIZES.length - 1, at + dir))]); break; }
                case R_TMDB: askTmdbKey(); return;
                case R_DOCK: Prefs.setDockSize(step(Prefs.DOCK_SIZES, Prefs.dockSize(), dir)); break;
                case R_ICON: Prefs.setTileSize(step(Prefs.TILE_SIZES, Prefs.tileSize(), dir)); break;
                case R_RADIUS: Prefs.setTileRadius(step(Prefs.TILE_RADII, Prefs.tileRadius(), dir)); break;
                case R_FOCUS: {
                    String[] modes = {"outline", "grow", "growshimmer"};
                    int at = Math.max(0, java.util.Arrays.asList(modes).indexOf(Prefs.focusStyle()));
                    Prefs.setFocusStyle(modes[(at + dir + modes.length) % modes.length]);
                    break;
                }
                case R_FORYOU: Prefs.setForYouApp("nuvio".equals(Prefs.forYouApp()) ? "service" : "nuvio"); break;
                case R_LIVE: {
                    String[] modes = {"auto", "iptv", "tuner"};
                    int at = Math.max(0, java.util.Arrays.asList(modes).indexOf(Prefs.liveTv()));
                    Prefs.setLiveTv(modes[(at + dir + modes.length) % modes.length]);
                    break;
                }
                case R_HOMEHOLD: Prefs.setHomeHold(!Prefs.homeHold()); break;
                case R_AUTOOFF: {
                    boolean on = !Prefs.autoOff();
                    Prefs.setAutoOff(on);
                    rowsCache = null;     // the hours row comes or goes
                    cachedLabels = null;
                    if (on) {
                        hoursKnob.snap(hoursT(Prefs.autoOffHours()));
                        HomeMode.requestAutoOffSetup(getContext());   // the overlay's service and permission
                        showRevealedRow();
                    }
                    AutoOff.get(getContext()).settingsChanged(on);
                    return;
                }
                case R_AUTOOFF_H: {
                    int hrs = Prefs.autoOffHours() + dir;
                    // The arrows stop at the ends; OK goes round (24 h, then 1 h).
                    if (okPress) hrs = hrs > Prefs.AUTO_OFF_MAX ? Prefs.AUTO_OFF_MIN : hrs;
                    Prefs.setAutoOffHours(hrs);
                    hoursKnob.target = hoursT(Prefs.autoOffHours());
                    AutoOff.get(getContext()).settingsChanged(false);
                    return;
                }
                case R_GROW: Prefs.setGrowAmount(step(Prefs.GROW_AMOUNTS, Prefs.growAmount(), dir)); break;
                case R_OPENING: {
                    String[] modes = {"quick", "off", "zoom"};
                    int at = Math.max(0, java.util.Arrays.asList(modes).indexOf(Prefs.launchAnim()));
                    Prefs.setLaunchAnim(modes[(at + dir + modes.length) % modes.length]);
                    break;
                }
                case R_TRAILERS: {
                    String[] modes = {"sound", "muted", "off"};
                    int at = Math.max(0, java.util.Arrays.asList(modes).indexOf(Prefs.trailers()));
                    Prefs.setTrailers(modes[(at + dir + modes.length) % modes.length]);
                    break;
                }
                default:
                    if (id < R_COIN || !Prefs.quotes()) return;
                    Prefs.setCoin(id - R_COIN, !Prefs.coin(id - R_COIN));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /** The next value in a list (wraps around); a value not in the list starts from the nearest one. */
        private static int step(int[] values, int current, int dir) {
            int at = 0;
            for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[at] - current)) at = i;
            return values[(at + dir + values.length) % values.length];
        }

        /** A text box for the TMDB key (the phone's remote app keyboard can paste into it). Stays on the TV. */
        private void askTmdbKey() {
            final android.widget.EditText box = new android.widget.EditText(getContext());
            box.setSingleLine(true);
            box.setHint(Ui.tr("Chave da API (v3)", "API key (v3)"));
            box.setText(Prefs.tmdbKey());
            box.setSelection(box.getText().length());
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle(Ui.tr("Chave do TMDB", "TMDB key"))
                    .setView(box)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> {
                        Prefs.setTmdbKey(box.getText().toString());
                        hasKey = Recs.hasKey(getContext());
                        HomeData.get(getContext()).settingsChanged();
                        invalidate();
                    })
                    .setNeutralButton(Ui.tr("Apagar", "Clear"), (d, w) -> {
                        Prefs.setTmdbKey("");
                        hasKey = Recs.hasKey(getContext());
                        HomeData.get(getContext()).settingsChanged();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .show();
            box.requestFocus();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** Hold OK (or Menu) on an app: its own picture for the card (IconActivity). */
        private void openIconEditor() {
            if (shelfRows.isEmpty()) return;
            String[] app = shelfRows.get(pickScroll + focus);
            activity.startActivity(new Intent(activity, IconActivity.class)
                    .putExtra(IconActivity.EXTRA_PKG, app[0]).putExtra(IconActivity.EXTRA_LABEL, app[1]));
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                fitPaint.set(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], fitPaint, 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
            c.drawText(Ui.tr("Segure OK para trocar o ícone do app.", "Hold OK to change the app's icon.",
                    "Mantén OK para cambiar el ícono de la app."), LIST_X * k + 30 * k, hy + 36 * k, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            pickLabels.addAll(appLabels);
            pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            String title = keyLabel(pickFor - 1);
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS, themes = screen == THEMES_SCREEN, home = screen == HOME_SETTINGS;
            String[] labels = listLabels();
            String[] values = listValues();
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : themes ? Ui.tr("Temas", "Themes") : Ui.tr("Ajustes do Início", "Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int first = home ? setScroll : 0, last = home ? Math.min(labels.length, setScroll + SET_VISIBLE) : labels.length;
            for (int i = first; i < last; i++) {
                float y = (LIST_TOP + 20 + (i - first) * (rowH() + rowGap())) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + rowH() * k);
                int id = home ? rowId(i) : -1;
                if (id == R_AUTOOFF_H) {
                    float rv = Ui.clamp(reveal.value);
                    float f = rv > 0.9f ? drawRowBackground(c, i) : 0;   // focusable once it has unfolded
                    drawHoursRow(c, y, f);
                    continue;
                }
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = id >= R_COIN && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
                if (id == R_WSIZE) {
                    // A slider: ← and → move the knob, all the widgets grow and shrink together.
                    int[] sizes = Prefs.WIDGET_SIZES;
                    float t = (Prefs.widgetSize() - sizes[0]) / (float) (sizes[sizes.length - 1] - sizes[0]);
                    float tr = rowRect.right - 30 * k - rowValue.measureText("150%") - 28 * k, tl = tr - 300 * k, cy = rowRect.centerY();
                    track.setColor(blend(0x40FFFFFF, 0x331C1C1E, f));
                    c.drawLine(tl, cy, tr, cy, track);
                    track.setColor(blend(0xE6FFFFFF, 0xE61C1C1E, f));
                    float kx = tl + (tr - tl) * t;
                    c.drawLine(tl, cy, kx, cy, track);
                    knob.setColor(0x33000000);
                    c.drawCircle(kx, cy + 2 * k, 16 * k, knob);
                    knob.setColor(0xFFFFFFFF);
                    c.drawCircle(kx, cy, 15 * k, knob);
                }
            }
            if (home && !Prefs.autoOff() && reveal.value > 0.01f && labels.length < first + SET_VISIBLE) {
                // The hours row folding away after the switch went off (it is no longer in the list).
                drawHoursRow(c, (LIST_TOP + 20 + (labels.length - first) * (rowH() + rowGap())) * k, 0);
            }
            float hy = (LIST_TOP + 20 + (last - first) * (rowH() + rowGap()) + 36) * k;
            if (home && last < labels.length) {
                Symbols.draw(getContext(), c, "chevron_down", (LIST_X + LIST_R) / 2f * k, hy - 30 * k, 26 * k, 0x8CFFFFFF);
            }
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            int focusedId = home ? rowId(focus) : -1;
            if ((focusedId == R_AUTOOFF || focusedId == R_AUTOOFF_H) && hy + 38 * k < h - 6 * k) {
                c.drawText(Ui.tr("Conta desde que a TV ligou; antes vem um aviso de 20 segundos.",
                        "Counts from when the TV last came on; a 20-second warning comes first.",
                        "Cuenta desde que la TV se encendió; antes, un aviso de 20 segundos."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
            if (themes) {
                c.drawText(Ui.tr("Escuro: o céu da noite o dia todo, mais confortável. Céu: segue o sol.",
                        "Dark: the night sky all day, easier on the eyes. Sky: follows the sun."), LIST_X * k + 30 * k, hy + 38 * k, hint);
                c.drawText(Ui.tr("Sem movimento, o fundo fica parado e a TV trabalha menos.",
                        "With motion off, the background stays still and the TV does less work."), LIST_X * k + 30 * k, hy + 76 * k, hint);
            }
            if (keys) {
                c.drawText(RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if ((wantFree || wantFree != freeActive()) && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS) {
                okPress = true;
                adjust(1);
                okPress = false;
                invalidate();
                return;
            } else if (screen == THEMES_SCREEN) {
                adjustTheme();
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == M_HOME_SETTINGS) {
                screen = HOME_SETTINGS;
                focus = 0;
                setScroll = 0;
            } else if (focus == M_THEMES) {
                screen = THEMES_SCREEN;
                focus = 0;
            } else if (focus == M_SHELF) {
                openShelfEditor();
            } else if (focus == M_REMOTE) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
