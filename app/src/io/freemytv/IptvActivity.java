package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The built-in IPTV player (Live TV when Home Settings > Live TV is "Built-in IPTV"). Media3 ExoPlayer plays the
 * streams (HLS, MPEG-TS over HTTP and the rest, with the playlist's own user agent and referrer per channel); the UI is
 * drawn like the rest of the home screen:
 *  - Watching: full screen. ↑/↓ or CH+/CH− change channel (with an info banner), digits jump to a channel number, OK or
 *    ← open the channel list, → opens the options (audio, subtitles, picture), Back twice leaves.
 *  - Channel list over the picture: categories (Search, Favorites, Recent, All, then the playlist's groups) and their
 *    channels with logo, number and what's on now. Hold OK on a channel to make it a favorite.
 *  - Playlists: M3U links or Xtream Codes logins, several at once; the guide (XMLTV) comes from the playlist itself or
 *    the Xtream server.
 */
public class IptvActivity extends Activity implements Player.Listener {
    private static final int WELCOME = 0, LOADING = 1, BROWSE = 2, WATCH = 3, OPTIONS = 4, MANAGE = 5;
    private static final int CAT_SEARCH = 0, CAT_FAV = 1, CAT_RECENT = 2, CAT_ALL = 3, CAT_GROUP = 4, CAT_PLAYLISTS = 5,
            CAT_RESULTS = 6;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surface;
    private Screen ui;
    private ExoPlayer player;
    private Logos logos;

    private Iptv.Playlist playlist;
    private List<Iptv.Channel> channels = new ArrayList<>();
    private final Map<String, Iptv.Channel> byKey = new HashMap<>();
    private Iptv.Guide guide;
    private Set<String> favorites = new HashSet<>();

    private static final class Category {
        final int kind;
        final String label;
        final List<Iptv.Channel> list;

        Category(int kind, String label, List<Iptv.Channel> list) {
            this.kind = kind;
            this.label = label;
            this.list = list;
        }
    }

    private final List<Category> cats = new ArrayList<>();
    private int state = LOADING, catIndex = CAT_ALL, chIndex, column;   // column: 0 = categories, 1 = channels
    private String loadingText = "";
    private String searchQuery;

    // Playback
    private Iptv.Channel playing;
    private List<Iptv.Channel> zapList = new ArrayList<>();
    private boolean triedHls, asHls;
    private int retries;
    private String errorText;
    private boolean buffering;
    private Tracks tracks = Tracks.EMPTY;
    private List<Cue> cues = Collections.emptyList();
    private int videoW, videoH;
    private long bannerUntil, backAt;
    private String digits = "";
    private final Runnable digitJump = this::jumpToNumber;
    private final Runnable retry = this::retryNow;

    // Options panel and playlists panel
    private final List<String[]> options = new ArrayList<>();   // {kind, label, arg}; kind "h" = header
    private int optIndex;
    private int manageIndex;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        ui = new Screen(this);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        logos = new Logos(this, () -> ui.invalidate());
        favorites = Iptv.favorites(this);
        playlist = Iptv.active(this);
        if (playlist == null) state = WELCOME;
        else load(false);
    }

    @Override protected void onStart() {
        super.onStart();
        createPlayer();
        if (playing != null) play(playing, false);
    }

    @Override protected void onStop() {
        releasePlayer();
        main.removeCallbacks(retry);
        super.onStop();
    }

    // MARK: - Loading the playlist

    private void load(final boolean refresh) {
        final Iptv.Playlist p = playlist;
        state = LOADING;
        loadingText = Ui.tr("Abrindo ", "Opening ") + p.name + "…";
        ui.invalidate();
        final Context app = getApplicationContext();
        WORK.execute(() -> {
            List<Iptv.Channel> list = refresh ? null : Iptv.cached(app, p);
            boolean stale = list != null && System.currentTimeMillis() - p.loadedAt > 24 * 3600_000L;
            String error = null;
            if (list == null) {
                try {
                    list = Iptv.download(app, p, n -> main.post(() -> {
                        loadingText = Ui.tr("Carregando canais… ", "Loading channels… ") + n;
                        ui.invalidate();
                    }));
                } catch (Exception | OutOfMemoryError e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                    AppLog.w("iptv playlist failed: " + error);
                    list = Iptv.cached(app, p);   // keep what we had
                }
            }
            final List<Iptv.Channel> result = list;
            final String err = error;
            Iptv.Guide g = Iptv.cachedGuide(app, p);
            final Iptv.Guide cachedGuide = g;
            main.post(() -> {
                if (p != playlist) return;
                if (result == null || result.isEmpty()) {
                    state = MANAGE;
                    manageIndex = 0;
                    toast(Ui.tr("Não deu para carregar a lista: ", "Couldn't load the playlist: ") + err);
                    ui.invalidate();
                    return;
                }
                if (err != null) toast(Ui.tr("Sem atualizar (", "Not updated (") + err + Ui.tr("): usando a lista salva", "): using the saved list"));
                setChannels(result);
                guide = cachedGuide;
                startAfterLoad();
                if (stale) refreshQuietly();
                refreshGuideIfOld();
            });
        });
    }

    /** Re-downloads the playlist in the background (the saved one stays on screen meanwhile). */
    private void refreshQuietly() {
        final Iptv.Playlist p = playlist;
        final Context app = getApplicationContext();
        WORK.execute(() -> {
            try {
                final List<Iptv.Channel> list = Iptv.download(app, p, null);
                main.post(() -> {
                    if (p != playlist) return;
                    String keep = playing == null ? null : playing.key();
                    setChannels(list);
                    if (keep != null && byKey.containsKey(keep)) playing = byKey.get(keep);
                    ui.invalidate();
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("iptv refresh failed: " + e);
            }
        });
    }

    private void refreshGuideIfOld() {
        final Iptv.Playlist p = playlist;
        if (p.epg == null || p.epg.isEmpty()) return;
        if (guide != null && System.currentTimeMillis() - guide.loadedAt < 12 * 3600_000L) return;
        final List<Iptv.Channel> list = channels;
        final Context app = getApplicationContext();
        WORK.execute(() -> {
            try {
                final Iptv.Guide g = Iptv.downloadGuide(app, p, list);
                main.post(() -> {
                    if (p != playlist) return;
                    guide = g;
                    ui.invalidate();
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("iptv guide failed: " + e);
            }
        });
    }

    private void setChannels(List<Iptv.Channel> list) {
        channels = list;
        byKey.clear();
        for (Iptv.Channel ch : list) byKey.put(ch.key(), ch);
        buildCategories();
    }

    private void buildCategories() {
        String focusedLabel = catIndex < cats.size() ? cats.get(catIndex).label : null;
        cats.clear();
        cats.add(new Category(CAT_SEARCH, Ui.tr("Buscar", "Search"), new ArrayList<>()));
        if (searchQuery != null) cats.add(new Category(CAT_RESULTS, "\u201c" + searchQuery + "\u201d", search(searchQuery)));
        List<Iptv.Channel> fav = new ArrayList<>();
        for (Iptv.Channel ch : channels) if (favorites.contains(ch.key())) fav.add(ch);
        cats.add(new Category(CAT_FAV, Ui.tr("Favoritos", "Favorites"), fav));
        List<Iptv.Channel> recent = new ArrayList<>();
        for (String k : Iptv.recents(this)) if (byKey.containsKey(k)) recent.add(byKey.get(k));
        cats.add(new Category(CAT_RECENT, Ui.tr("Recentes", "Recent"), recent));
        cats.add(new Category(CAT_ALL, Ui.tr("Todos os canais", "All channels"), channels));
        for (Map.Entry<String, List<Iptv.Channel>> e : Iptv.groups(channels).entrySet()) {
            cats.add(new Category(CAT_GROUP, e.getKey(), e.getValue()));
        }
        cats.add(new Category(CAT_PLAYLISTS, Ui.tr("Listas de canais", "Playlists"), new ArrayList<>()));
        catIndex = -1;
        for (int i = 0; focusedLabel != null && i < cats.size(); i++) if (cats.get(i).label.equals(focusedLabel)) catIndex = i;
        if (catIndex < 0) catIndex = indexOfKind(CAT_ALL);
        chIndex = Math.min(chIndex, Math.max(0, list().size() - 1));
    }

    private int indexOfKind(int kind) {
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).kind == kind) return i;
        return 0;
    }

    private List<Iptv.Channel> list() {
        return catIndex >= 0 && catIndex < cats.size() ? cats.get(catIndex).list : Collections.<Iptv.Channel>emptyList();
    }

    private List<Iptv.Channel> search(String q) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<Iptv.Channel> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        try {
            int n = Integer.parseInt(s);
            for (Iptv.Channel ch : channels) if (ch.number == n) out.add(ch);
        } catch (NumberFormatException ignored) {
        }
        for (Iptv.Channel ch : channels) if (ch.name.toLowerCase(Locale.ROOT).contains(s) && !out.contains(ch)) out.add(ch);
        return out;
    }

    /** First open: the last channel watched, full screen; otherwise the channel list. */
    private void startAfterLoad() {
        Iptv.Channel last = byKey.get(Iptv.last(this));
        if (playing != null && byKey.containsKey(playing.key())) last = byKey.get(playing.key());
        if (last != null) {
            catIndex = indexOfKind(CAT_ALL);
            zapList = channels;
            chIndex = channels.indexOf(last);
            play(last, true);
            state = WATCH;
            showBanner();
        } else {
            state = BROWSE;
            column = 1;
            chIndex = 0;
        }
        ui.snapScroll();
        ui.invalidate();
    }

    // MARK: - Player

    private void createPlayer() {
        if (player != null) return;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(12_000, 40_000, 1_200, 2_500).build();
        player = new ExoPlayer.Builder(this, rf).setLoadControl(lc).build();
        player.setVideoSurfaceView(surface);
        player.addListener(this);
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        if (player == null) return;
        player.removeListener(this);
        player.release();
        player = null;
    }

    private void play(Iptv.Channel ch, boolean fresh) {
        if (ch == null) return;
        if (fresh) {
            retries = 0;
            triedHls = false;
            asHls = ch.url.toLowerCase(Locale.ROOT).contains(".m3u8") || ch.url.toLowerCase(Locale.ROOT).contains("m3u8");
        }
        playing = ch;
        errorText = null;
        cues = Collections.emptyList();
        main.removeCallbacks(retry);
        Iptv.watched(this, ch.key());
        if (player == null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.ua != null ? ch.ua : Iptv.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000);
        if (ch.referrer != null) http.setDefaultRequestProperties(Collections.singletonMap("Referer", ch.referrer));
        MediaItem.Builder item = new MediaItem.Builder().setUri(ch.url);
        MediaSource src;
        if (asHls) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
            src = new HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(item.build());
        } else {
            DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
            src = new ProgressiveMediaSource.Factory(http, ef).createMediaSource(item.build());
        }
        buffering = true;
        player.setMediaSource(src);
        player.prepare();
        player.setPlayWhenReady(true);
        ui.invalidate();
    }

    private void retryNow() {
        if (playing != null) play(playing, false);
    }

    @Override public void onPlaybackStateChanged(int s) {
        buffering = s == Player.STATE_BUFFERING;
        if (s == Player.STATE_READY) {
            retries = 0;
            errorText = null;
        }
        ui.invalidate();
    }

    @Override public void onPlayerError(PlaybackException e) {
        AppLog.w("iptv play error " + e.getErrorCodeName() + " on " + (playing == null ? "?" : playing.name));
        if (playing == null) return;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        boolean format = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
        if (format && !triedHls) {
            // The URL doesn't say what it is: try the other way (HLS ⇄ plain stream) once.
            triedHls = true;
            asHls = !asHls;
            play(playing, false);
            return;
        }
        if (retries++ < 3) {
            buffering = true;
            main.postDelayed(retry, 1500L * retries);
            ui.invalidate();
            return;
        }
        buffering = false;
        boolean http = e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        errorText = http ? Ui.tr("O servidor recusou este canal agora.", "The server refused this channel right now.")
                : e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ? Ui.tr("Sem conexão com o servidor do canal.", "Can't reach this channel's server.")
                : Ui.tr("Este canal não está funcionando agora.", "This channel isn't working right now.");
        ui.invalidate();
    }

    @Override public void onVideoSizeChanged(VideoSize v) {
        videoW = Math.round(v.width * (v.pixelWidthHeightRatio > 0 ? v.pixelWidthHeightRatio : 1));
        videoH = v.height;
        layoutSurface();
    }

    @Override public void onTracksChanged(Tracks t) {
        tracks = t;
    }

    @Override public void onCues(CueGroup g) {
        cues = g.cues;
        ui.invalidate();
    }

    /** Fit (whole picture), Fill (crop to the screen) or Stretch. */
    private void layoutSurface() {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw == 0 || sh == 0) return;
        String mode = Iptv.aspect(this);
        int w = sw, h = sh;
        if (videoW > 0 && videoH > 0 && !mode.equals("stretch")) {
            float video = (float) videoW / videoH, screen = (float) sw / sh;
            boolean wider = video > screen;
            if (mode.equals("fill") ? !wider : wider) {
                w = sw;
                h = Math.round(sw / video);
            } else {
                h = sh;
                w = Math.round(sh * video);
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    // MARK: - Watching

    private void zap(int dir) {
        if (zapList.isEmpty() || playing == null) return;
        int at = zapList.indexOf(playing);
        int next = ((at < 0 ? 0 : at + dir) % zapList.size() + zapList.size()) % zapList.size();
        play(zapList.get(next), true);
        showBanner();
    }

    private void showBanner() {
        bannerUntil = SystemClock.uptimeMillis() + 5000;
        ui.invalidate();
        ui.postDelayed(ui::invalidate, 5100);
    }

    private void typeDigit(int d) {
        if (digits.length() >= 5) digits = "";
        digits += d;
        main.removeCallbacks(digitJump);
        main.postDelayed(digitJump, 1600);
        ui.invalidate();
    }

    private void jumpToNumber() {
        if (digits.isEmpty()) return;
        int n;
        try {
            n = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            n = -1;
        }
        digits = "";
        for (Iptv.Channel ch : channels) {
            if (ch.number == n) {
                zapList = channels;
                play(ch, true);
                state = WATCH;
                showBanner();
                return;
            }
        }
        toast(Ui.tr("Nenhum canal ", "No channel ") + n);
    }

    private void openBrowse() {
        state = BROWSE;
        column = 1;
        buildCategories();   // Recent and Favorites as they are now
        if (playing != null) {
            int at = list().indexOf(playing);
            if (at < 0) {
                // Show the playing channel in its own group.
                for (int i = 0; i < cats.size(); i++) {
                    if (cats.get(i).kind == CAT_GROUP && cats.get(i).list.contains(playing)) {
                        catIndex = i;
                        break;
                    }
                }
                at = Math.max(0, list().indexOf(playing));
            }
            chIndex = at;
        }
        ui.snapScroll();
        ui.invalidate();
    }

    // MARK: - Options (audio, subtitles, picture)

    private void openOptions() {
        options.clear();
        List<String[]> audio = new ArrayList<>(), text = new ArrayList<>();
        int g = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                String name = trackName(f, group.getType() == C.TRACK_TYPE_AUDIO ? audio.size() + 1 : text.size() + 1);
                String mark = group.isTrackSelected(i) ? "1" : "0";
                if (group.getType() == C.TRACK_TYPE_AUDIO) audio.add(new String[] {"a", name, g + ":" + i, mark});
                else if (group.getType() == C.TRACK_TYPE_TEXT) text.add(new String[] {"t", name, g + ":" + i, mark});
            }
            g++;
        }
        if (!audio.isEmpty()) {
            options.add(new String[] {"h", Ui.tr("Áudio", "Audio"), "", "0"});
            options.addAll(audio);
        }
        boolean textOn = player != null && !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        boolean anyText = false;
        for (String[] t : text) anyText |= t[3].equals("1");
        options.add(new String[] {"h", Ui.tr("Legendas", "Subtitles"), "", "0"});
        options.add(new String[] {"toff", Ui.tr("Desligadas", "Off"), "", textOn && anyText ? "0" : "1"});
        options.addAll(text);
        options.add(new String[] {"h", Ui.tr("Imagem", "Picture"), "", "0"});
        String aspect = Iptv.aspect(this);
        options.add(new String[] {"p", Ui.tr("Ajustar (imagem inteira)", "Fit (whole picture)"), "fit", aspect.equals("fit") ? "1" : "0"});
        options.add(new String[] {"p", Ui.tr("Preencher (cortar bordas)", "Fill (crop edges)"), "fill", aspect.equals("fill") ? "1" : "0"});
        options.add(new String[] {"p", Ui.tr("Esticar", "Stretch"), "stretch", aspect.equals("stretch") ? "1" : "0"});
        options.add(new String[] {"h", Ui.tr("Canal", "Channel"), "", "0"});
        options.add(new String[] {"reload", Ui.tr("Recarregar o canal", "Reload channel"), "", "0"});
        if (playing != null) {
            options.add(new String[] {"fav", favorites.contains(playing.key()) ? Ui.tr("Tirar dos favoritos", "Remove from favorites")
                    : Ui.tr("Adicionar aos favoritos", "Add to favorites"), "", "0"});
        }
        optIndex = 0;
        while (optIndex < options.size() && options.get(optIndex)[0].equals("h")) optIndex++;
        state = OPTIONS;
        ui.invalidate();
    }

    private static String trackName(Format f, int n) {
        String lang = f.language == null || f.language.equals("und") ? null
                : new Locale(f.language).getDisplayLanguage(Ui.locale());
        String label = f.label != null ? f.label : lang != null ? lang : "#" + n;
        if (f.label != null && lang != null && !f.label.toLowerCase(Locale.ROOT).contains(lang.toLowerCase(Locale.ROOT))) label += " (" + lang + ")";
        if (f.channelCount >= 6) label += " 5.1";
        return label;
    }

    private void applyOption() {
        if (optIndex >= options.size() || player == null) return;
        String[] o = options.get(optIndex);
        switch (o[0]) {
            case "a":
            case "t": {
                String[] gi = o[2].split(":");
                Tracks.Group group = tracks.getGroups().get(Integer.parseInt(gi[0]));
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(group.getType(), false)
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), Integer.parseInt(gi[1])))
                        .build());
                break;
            }
            case "toff":
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                cues = Collections.emptyList();
                break;
            case "p":
                Iptv.setAspect(this, o[2]);
                layoutSurface();
                break;
            case "reload":
                if (playing != null) play(playing, true);
                break;
            case "fav":
                toggleFavorite(playing);
                break;
            default:
                return;
        }
        state = WATCH;
        ui.invalidate();
        // The track list changes after a selection: reopening shows the new state.
    }

    private void toggleFavorite(Iptv.Channel ch) {
        if (ch == null) return;
        boolean on = Iptv.toggleFavorite(this, ch.key());
        favorites = Iptv.favorites(this);
        toast(on ? Ui.tr("Nos favoritos: ", "Added to favorites: ") + ch.name : Ui.tr("Fora dos favoritos: ", "Removed from favorites: ") + ch.name);
        int keepCat = catIndex;
        Category c = catIndex < cats.size() ? cats.get(catIndex) : null;
        buildCategories();
        if (c != null && c.kind == CAT_FAV) chIndex = Math.min(chIndex, Math.max(0, list().size() - 1));
        else catIndex = Math.min(keepCat, cats.size() - 1);
        ui.invalidate();
    }

    // MARK: - Playlists: add, use, refresh, rename, delete

    private List<Iptv.Playlist> managed() {
        return Iptv.playlists(this);
    }

    private void manageActivate() {
        List<Iptv.Playlist> list = managed();
        if (manageIndex < list.size()) {
            final Iptv.Playlist p = list.get(manageIndex);
            String[] items = {Ui.tr("Usar esta lista", "Use this playlist"), Ui.tr("Atualizar agora", "Refresh now"),
                    Ui.tr("Renomear", "Rename"), Ui.tr("Apagar", "Delete")};
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(p.name)
                    .setItems(items, (d, which) -> {
                        if (which == 0 || which == 1) {
                            Iptv.setActive(this, p.id);
                            switchTo(Iptv.active(this), which == 1);
                        } else if (which == 2) {
                            ask(Ui.tr("Nome da lista", "Playlist name"), p.name, InputType.TYPE_CLASS_TEXT, v -> {
                                if (!v.trim().isEmpty()) Iptv.rename(this, p.id, v.trim());
                                if (playlist != null && playlist.id.equals(p.id)) playlist.name = v.trim();
                                ui.invalidate();
                            });
                        } else {
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle(Ui.tr("Apagar ", "Delete ") + p.name + "?")
                                    .setPositiveButton(Ui.tr("Apagar", "Delete"), (d2, w2) -> {
                                        Iptv.remove(this, p.id);
                                        manageIndex = 0;
                                        if (playlist != null && playlist.id.equals(p.id)) {
                                            Iptv.Playlist next = Iptv.active(this);
                                            if (next == null) {
                                                playlist = null;
                                                channels = new ArrayList<>();
                                                cats.clear();
                                                playing = null;
                                                if (player != null) player.stop();
                                                state = WELCOME;
                                            } else {
                                                switchTo(next, false);
                                            }
                                        }
                                        ui.invalidate();
                                    })
                                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null).show();
                        }
                    }).show();
        } else if (manageIndex == list.size()) {
            addM3u();
        } else {
            addXtream();
        }
    }

    private void switchTo(Iptv.Playlist p, boolean refresh) {
        playlist = p;
        playing = null;
        guide = null;
        searchQuery = null;
        if (player != null) player.stop();
        load(refresh);
    }

    interface Answer { void got(String value); }

    private void ask(String title, String value, int type, Answer answer) {
        final EditText box = field(value, title, type);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(wrap(box))
                .setPositiveButton("OK", (d, w) -> answer.got(box.getText().toString()))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null).show();
        box.requestFocus();
    }

    private EditText field(String value, String hint, int type) {
        EditText box = new EditText(this);
        box.setSingleLine(true);
        box.setHint(hint);
        box.setInputType(type);
        if (value != null) {
            box.setText(value);
            box.setSelection(box.getText().length());
        }
        return box;
    }

    private View wrap(View... views) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad / 2, pad, 0);
        for (View v : views) l.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    private void addM3u() {
        final EditText url = field("", Ui.tr("Link M3U (http://…)", "M3U link (http://…)"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText name = field("", Ui.tr("Nome (opcional)", "Name (optional)"), InputType.TYPE_CLASS_TEXT);
        final EditText epg = field("", Ui.tr("Guia XMLTV (opcional)", "XMLTV guide link (optional)"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Adicionar lista M3U", "Add M3U playlist"))
                .setView(wrap(url, name, epg))
                .setPositiveButton(Ui.tr("Adicionar", "Add"), (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) return;
                    if (!u.matches("(?i)^[a-z]+://.*")) u = "http://" + u;
                    switchTo(Iptv.add(this, name.getText().toString(), u, epg.getText().toString()), true);
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null).show();
        url.requestFocus();
    }

    private void addXtream() {
        final EditText server = field("", Ui.tr("Servidor (http://exemplo.com:8080)", "Server (http://example.com:8080)"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user = field("", Ui.tr("Usuário", "Username"), InputType.TYPE_CLASS_TEXT);
        final EditText pass = field("", Ui.tr("Senha", "Password"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText name = field("", Ui.tr("Nome (opcional)", "Name (optional)"), InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Adicionar Xtream Codes", "Add Xtream Codes"))
                .setView(wrap(server, user, pass, name))
                .setPositiveButton(Ui.tr("Adicionar", "Add"), (d, w) -> {
                    if (server.getText().toString().trim().isEmpty() || user.getText().toString().trim().isEmpty()) return;
                    switchTo(Iptv.addXtream(this, name.getText().toString(), server.getText().toString(),
                            user.getText().toString(), pass.getText().toString()), true);
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null).show();
        server.requestFocus();
    }

    private String toastText;
    private long toastUntil;

    private void toast(String s) {
        toastText = s;
        toastUntil = SystemClock.uptimeMillis() + 3500;
        ui.invalidate();
        ui.postDelayed(ui::invalidate, 3600);
    }

    // MARK: - Keys

    private boolean okDown;

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(e);
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (down && e.getRepeatCount() == 0) okDown = true;
            else if (down && e.getRepeatCount() == 1 && okDown) {
                okDown = false;
                longOk();
            } else if (!down && okDown) {
                okDown = false;
                key(KeyEvent.KEYCODE_DPAD_CENTER, false);
            }
            return true;
        }
        if (!down) return true;
        if (code == KeyEvent.KEYCODE_BACK && e.getRepeatCount() > 0) return true;
        key(code, e.getRepeatCount() > 0);
        return true;
    }

    private void longOk() {
        if (state == BROWSE && column == 1 && chIndex < list().size()) toggleFavorite(list().get(chIndex));
        else key(KeyEvent.KEYCODE_DPAD_CENTER, false);
    }

    private void key(int code, boolean repeat) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && (state == WATCH || state == BROWSE) && !channels.isEmpty()) {
            typeDigit(code - KeyEvent.KEYCODE_0);
            return;
        }
        switch (state) {
            case WELCOME: keyWelcome(code); break;
            case LOADING: if (code == KeyEvent.KEYCODE_BACK) finish(); break;
            case BROWSE: keyBrowse(code, repeat); break;
            case WATCH: keyWatch(code); break;
            case OPTIONS: keyOptions(code); break;
            default: keyManage(code); break;
        }
        ui.invalidate();
    }

    private int welcomeIndex;

    private void keyWelcome(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) welcomeIndex = 1 - welcomeIndex;
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (welcomeIndex == 0) addM3u();
            else addXtream();
        } else if (code == KeyEvent.KEYCODE_BACK) finish();
    }

    private void keyBrowse(int code, boolean repeat) {
        int page = 7;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN: {
                int dir = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP ? -1 : 1;
                int step = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : page;
                if (column == 0) {
                    int next = Math.max(0, Math.min(cats.size() - 1, catIndex + dir * step));
                    if (next != catIndex) {
                        catIndex = next;
                        chIndex = Math.max(0, list().indexOf(playing));
                        ui.snapChannels();
                    }
                } else {
                    int n = list().size();
                    if (n == 0) return;
                    chIndex = Math.max(0, Math.min(n - 1, chIndex + dir * step));
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (column == 0 && !list().isEmpty()) column = 1;
                else if (column == 1 && playing != null) openOptions();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (column == 1) column = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER: {
                if (column == 0) {
                    Category c = cats.get(catIndex);
                    if (c.kind == CAT_SEARCH) {
                        ask(Ui.tr("Buscar canal (nome ou número)", "Search channels (name or number)"), searchQuery,
                                InputType.TYPE_CLASS_TEXT, v -> {
                                    searchQuery = v.trim().isEmpty() ? null : v.trim();
                                    buildCategories();
                                    if (searchQuery != null) {
                                        catIndex = indexOfKind(CAT_RESULTS);
                                        chIndex = 0;
                                        column = list().isEmpty() ? 0 : 1;
                                        if (list().isEmpty()) toast(Ui.tr("Nada encontrado", "Nothing found"));
                                    }
                                    ui.snapScroll();
                                    ui.invalidate();
                                });
                    } else if (c.kind == CAT_PLAYLISTS) {
                        state = MANAGE;
                        manageIndex = 0;
                    } else if (!c.list.isEmpty()) {
                        column = 1;
                    }
                } else if (chIndex < list().size()) {
                    Iptv.Channel ch = list().get(chIndex);
                    zapList = new ArrayList<>(list());
                    if (ch != playing || errorText != null) play(ch, true);
                    state = WATCH;
                    showBanner();
                }
                break;
            }
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (playing != null) openOptions();
                break;
            case KeyEvent.KEYCODE_BACK:
                if (playing != null) state = WATCH;
                else finish();
                break;
            default:
                break;
        }
    }

    private void keyWatch(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                openBrowse();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                openOptions();
                break;
            case KeyEvent.KEYCODE_INFO:
                showBanner();
                break;
            case KeyEvent.KEYCODE_BACK: {
                long now = SystemClock.uptimeMillis();
                if (now - backAt < 2200) {
                    finish();
                } else {
                    backAt = now;
                    toast(Ui.tr("Aperte Voltar de novo para sair", "Press Back again to exit"));
                }
                break;
            }
            default:
                break;
        }
    }

    private void keyOptions(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1, i = optIndex + dir;
                while (i >= 0 && i < options.size() && options.get(i)[0].equals("h")) i += dir;
                if (i >= 0 && i < options.size()) optIndex = i;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
                applyOption();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                state = WATCH;
                break;
            default:
                break;
        }
    }

    private void keyManage(int code) {
        int n = managed().size() + 2;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: manageIndex = Math.max(0, manageIndex - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: manageIndex = Math.min(n - 1, manageIndex + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: manageActivate(); break;
            case KeyEvent.KEYCODE_BACK:
                if (playlist == null) state = WELCOME;
                else if (channels.isEmpty()) finish();
                else state = BROWSE;
                break;
            default: break;
        }
    }

    // MARK: - Channel logos: downloaded once, kept small on disk and in memory

    private static final class Logos {
        private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
        private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
        private final ExecutorService pool = Executors.newFixedThreadPool(4);
        private final File dir;
        private final Runnable changed;
        private final Handler main = new Handler(Looper.getMainLooper());

        Logos(Context c, Runnable changed) {
            dir = new File(Iptv.dir(c), "logos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            this.changed = changed;
        }

        Bitmap get(final String url) {
            if (url == null) return null;
            Bitmap b = memory.get(url);
            if (b != null || failed.contains(url) || !loading.add(url)) return b;
            pool.execute(() -> {
                Bitmap got = null;
                File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length() + ".png");
                try {
                    if (f.exists()) got = BitmapFactory.decodeFile(f.getPath());
                    if (got == null) {
                        HttpURLConnection c = Iptv.open(url, null, null);
                        byte[] data;
                        try (InputStream in = Iptv.body(c)) {
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
                            data = out.toByteArray();
                        } finally {
                            c.disconnect();
                        }
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        o.inJustDecodeBounds = false;
                        o.inSampleSize = 1;
                        while (o.outWidth / (o.inSampleSize * 2) >= 240 && o.outHeight / (o.inSampleSize * 2) >= 135) o.inSampleSize *= 2;
                        Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        if (raw != null) {
                            float s = Math.min(1f, Math.min(240f / raw.getWidth(), 135f / raw.getHeight()));
                            got = s < 1 ? Bitmap.createScaledBitmap(raw, Math.max(1, Math.round(raw.getWidth() * s)),
                                    Math.max(1, Math.round(raw.getHeight() * s)), true) : raw;
                            try (FileOutputStream out = new FileOutputStream(f)) {
                                got.compress(Bitmap.CompressFormat.PNG, 100, out);
                            }
                        }
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                }
                final Bitmap result = got;
                main.post(() -> {
                    loading.remove(url);
                    if (result == null) failed.add(url);
                    else memory.put(url, result);
                    changed.run();
                });
            });
            return null;
        }
    }

    // MARK: - Drawing

    private final class Screen extends View implements Choreographer.FrameCallback {
        private float k = 1;
        private final Spring catScroll = new Spring(0.3f, 1f, 0), chScroll = new Spring(0.3f, 1f, 0);
        private final Spring catBar = new Spring(0.22f, 0.9f, 0), chBar = new Spring(0.22f, 0.9f, 0);
        private final Spring overlay = new Spring(0.3f, 1f, 0), side = new Spring(0.3f, 1f, 0), banner = new Spring(0.3f, 1f, 0);
        private boolean framing;
        private long lastNanos;
        private Paint title, sub, row, rowDim, rowSmall, number, header, big, body, chip, cueText;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path star = new Path();
        private static final float CAT_X = 64, CAT_W = 380, CH_X = 470, CH_W = 820, TOP = 214, CAT_H = 66, CH_H = 100;

        Screen(Context c) {
            super(c);
            setFocusable(false);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
            sub = Ui.text(Ui.medium, 25 * k, 0x99FFFFFF);
            row = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
            rowDim = Ui.text(Ui.medium, 29 * k, 0xB3FFFFFF);
            rowSmall = Ui.text(Ui.regular, 23 * k, 0x99FFFFFF);
            number = Ui.numbers(Ui.medium, 24 * k, 0x80FFFFFF);
            header = Ui.text(Ui.semibold, 22 * k, 0x80FFFFFF);
            big = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            body = Ui.text(Ui.regular, 25 * k, 0xCCFFFFFF);
            chip = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
            cueText = Ui.text(Ui.semibold, 44 * k, 0xFFFFFFFF);
            cueText.setTextAlign(Paint.Align.CENTER);
            cueText.setShadowLayer(6 * k, 0, 2 * k, 0xE6000000);
            spin.setStyle(Paint.Style.STROKE);
            spin.setStrokeWidth(5 * k);
            spin.setStrokeCap(Paint.Cap.ROUND);
            spin.setColor(0xE6FFFFFF);
            snapScroll();
            main.post(IptvActivity.this::layoutSurface);
        }

        void snapScroll() {
            aimScroll();
            catScroll.snap(catScroll.target);
            chScroll.snap(chScroll.target);
            catBar.snap(catIndex);
            chBar.snap(chIndex);
        }

        void snapChannels() {
            aimScroll();
            chScroll.snap(chScroll.target);
            chBar.snap(chIndex);
        }

        private void aimScroll() {
            int catVisible = (int) ((1040 - TOP) / CAT_H), chVisible = (int) ((1040 - TOP) / CH_H);
            catScroll.target = window(catScroll.target, catIndex, cats.size(), catVisible);
            chScroll.target = window(chScroll.target, chIndex, list().size(), chVisible);
            catBar.target = catIndex;
            chBar.target = chIndex;
        }

        /** Keeps the focused row inside the window, two rows from its edges while scrolling. */
        private float window(float top, int focus, int count, int visible) {
            float margin = Math.min(2, (visible - 1) / 2f);
            if (focus - top < margin) top = focus - margin;
            if (focus - top > visible - 1 - margin) top = focus - (visible - 1 - margin);
            return Math.max(0, Math.min(Math.max(0, count - visible), top));
        }

        @Override public void invalidate() {
            super.invalidate();
            if (!framing) {
                framing = true;
                lastNanos = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override public void doFrame(long now) {
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            aimScroll();
            overlay.target = state == BROWSE || state == MANAGE || state == WELCOME || state == LOADING ? 1 : 0;
            side.target = state == OPTIONS ? 1 : 0;
            banner.target = state == WATCH && (SystemClock.uptimeMillis() < bannerUntil || errorText != null) ? 1 : 0;
            boolean moving = catScroll.step(dt) | chScroll.step(dt) | catBar.step(dt) | chBar.step(dt) | overlay.step(dt)
                    | side.step(dt) | banner.step(dt);
            boolean spinning = state == LOADING || (buffering && playing != null && errorText == null);
            super.invalidate();
            if (moving || spinning) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                framing = false;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            int w = getWidth(), h = getHeight();
            boolean video = playing != null && player != null;
            if (!video) c.drawColor(0xFF0B0B0F);
            float ov = Ui.clamp(overlay.value);
            switch (state) {
                case WELCOME: drawWelcome(c, w, h); break;
                case LOADING: drawLoading(c, w, h); break;
                default:
                    if (ov > 0.01f && (state == BROWSE || state == MANAGE)) drawBrowse(c, w, h, ov);
                    break;
            }
            if (state == MANAGE) drawManage(c, w, h);
            if (Ui.clamp(side.value) > 0.01f) drawOptions(c, w, h, Ui.clamp(side.value));
            if (Ui.clamp(banner.value) > 0.01f && playing != null) drawBanner(c, w, h, Ui.clamp(banner.value));
            if (video && !cues.isEmpty() && state != BROWSE) drawCues(c, w, h);
            if (video && buffering && errorText == null && state != LOADING) {
                float cx = state == BROWSE ? (CH_X + CH_W + 1920) / 2 * k : w / 2f;
                Ui.spinner(c, cx, h / 2f, 34 * k, SystemClock.uptimeMillis() / 900f, spin);
            }
            if (video && errorText != null && state != BROWSE) {
                drawPill(c, errorText, w / 2f, h / 2f, 0xD9000000);
            }
            if (!digits.isEmpty()) drawDigits(c, w);
            if (toastText != null && SystemClock.uptimeMillis() < toastUntil) drawPill(c, toastText, w / 2f, 90 * k, 0xE61C1C1E);
        }

        private void drawWelcome(Canvas c, int w, int h) {
            fill.setShader(new LinearGradient(0, 0, w, h, 0xFF15161C, 0xFF07070A, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            Symbols.draw(getContext(), c, "live_tv", w / 2f, 300 * k, 96 * k, 0xFFFFFFFF);
            centered(c, Ui.tr("TV ao vivo por IPTV", "Live TV over IPTV"), 470 * k, big);
            centered(c, Ui.tr("Adicione o link M3U ou o login Xtream Codes do seu provedor.",
                    "Add your provider's M3U link or Xtream Codes login."), 530 * k, body);
            centered(c, Ui.tr("Fica salvo na TV: da próxima vez, é só abrir.", "It's saved on the TV: next time it just opens."), 572 * k, sub);
            String[] b = {Ui.tr("Link M3U", "M3U link"), "Xtream Codes"};
            float bw = 360 * k, bh = 84 * k, gap = 36 * k, x = (w - 2 * bw - gap) / 2f, y = 660 * k;
            for (int i = 0; i < 2; i++) {
                boolean f = i == welcomeIndex;
                r.set(x, y, x + bw, y + bh);
                if (f) {
                    shadowRect(c, r, bh / 2, 0x66000000);
                    fill.setColor(0xFFFFFFFF);
                } else {
                    fill.setColor(0x26FFFFFF);
                }
                c.drawRoundRect(r, bh / 2, bh / 2, fill);
                row.setColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
                c.drawText(b[i], r.centerX() - row.measureText(b[i]) / 2, r.centerY() + Ui.halfCap(row), row);
                row.setColor(0xFFFFFFFF);
                x += bw + gap;
            }
        }

        private void drawLoading(Canvas c, int w, int h) {
            Ui.spinner(c, w / 2f, h / 2f - 30 * k, 40 * k, SystemClock.uptimeMillis() / 900f, spin);
            centered(c, loadingText, h / 2f + 60 * k, rowDim);
        }

        private void drawBrowse(Canvas c, int w, int h, float a) {
            int alpha = Math.round(255 * a);
            // A dark veil on the left (where the lists are), the picture still visible on the right.
            fill.setShader(new LinearGradient(0, 0, w * 0.82f, 0, new int[] {Ui.alpha(0xFF000000, 0.94f * a),
                    Ui.alpha(0xFF000000, 0.86f * a), Ui.alpha(0xFF000000, 0)}, new float[] {0, 0.62f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            boolean layer = alpha < 250;
            if (layer) c.saveLayerAlpha(0, 0, w, h, alpha);
            else c.save();
            c.drawText(Ui.tr("TV ao vivo", "Live TV"), CAT_X * k, 112 * k, title);
            if (playlist != null) c.drawText(playlist.name + "  ·  " + channels.size() + Ui.tr(" canais", " channels"), CAT_X * k, 156 * k, sub);
            String clock = Ui.clock(System.currentTimeMillis());
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(clock, (CH_X + CH_W) * k, 112 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            drawCategories(c, h);
            drawChannels(c, h);
            if (state == BROWSE && column == 1 && chIndex < list().size()) drawInfo(c, w, h, list().get(chIndex));
            c.restore();
        }

        private void drawCategories(Canvas c, int h) {
            c.save();
            c.clipRect(0, (TOP - 10) * k, (CAT_X + CAT_W + 10) * k, h);
            float top = TOP * k, rh = CAT_H * k, x = CAT_X * k, cw = CAT_W * k;
            float barY = top + (catBar.value - catScroll.value) * rh;
            r.set(x, barY + 4 * k, x + cw, barY + rh - 4 * k);
            fill.setColor(column == 0 ? 0xFFFFFFFF : 0x29FFFFFF);
            c.drawRoundRect(r, 16 * k, 16 * k, fill);
            int first = Math.max(0, (int) catScroll.value - 1);
            for (int i = first; i < cats.size(); i++) {
                float y = top + (i - catScroll.value) * rh;
                if (y > h) break;
                Category cat = cats.get(i);
                boolean on = i == catIndex && column == 0;
                Paint p = i == catIndex ? row : rowDim;
                p.setColor(on ? 0xFF1C1C1E : i == catIndex ? 0xFFFFFFFF : 0xB3FFFFFF);
                String sym = cat.kind == CAT_SEARCH ? "apps" : cat.kind == CAT_PLAYLISTS ? "settings" : null;
                float tx = x + 24 * k;
                if (cat.kind == CAT_FAV) {
                    drawStar(c, tx + 12 * k, y + rh / 2, 13 * k, on ? 0xFF1C1C1E : 0xFFFFD60A);
                    tx += 38 * k;
                } else if (sym != null) {
                    Symbols.draw(getContext(), c, sym, tx + 12 * k, y + rh / 2, 26 * k, p.getColor());
                    tx += 38 * k;
                }
                String count = cat.kind == CAT_SEARCH || cat.kind == CAT_PLAYLISTS ? "" : String.valueOf(cat.list.size());
                float countW = count.isEmpty() ? 0 : number.measureText(count) + 20 * k;
                String label = TextUtils.ellipsize(cat.label, new TextPaint(p), x + cw - tx - countW - 16 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(label, tx, y + rh / 2 + Ui.halfCap(p), p);
                if (!count.isEmpty()) {
                    number.setColor(on ? 0x991C1C1E : 0x66FFFFFF);
                    number.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(count, x + cw - 20 * k, y + rh / 2 + Ui.halfCap(number), number);
                    number.setTextAlign(Paint.Align.LEFT);
                }
                p.setColor(0xFFFFFFFF);
            }
            c.restore();
        }

        private void drawChannels(Canvas c, int h) {
            List<Iptv.Channel> l = list();
            float top = TOP * k, rh = CH_H * k, x = CH_X * k, cw = CH_W * k;
            c.save();
            c.clipRect(x - 10 * k, (TOP - 10) * k, x + cw + 10 * k, h);
            if (l.isEmpty()) {
                Category cat = catIndex < cats.size() ? cats.get(catIndex) : null;
                String msg = cat == null ? "" : cat.kind == CAT_FAV ? Ui.tr("Segure OK num canal para favoritar.", "Hold OK on a channel to add it here.")
                        : cat.kind == CAT_RECENT ? Ui.tr("Os canais que você assistir aparecem aqui.", "Channels you watch show up here.")
                        : cat.kind == CAT_SEARCH ? Ui.tr("Aperte OK para buscar por nome ou número.", "Press OK to search by name or number.")
                        : cat.kind == CAT_PLAYLISTS ? Ui.tr("Aperte OK para adicionar ou trocar de lista.", "Press OK to add or switch playlists.")
                        : Ui.tr("Nenhum canal aqui.", "No channels here.");
                c.drawText(msg, x + 24 * k, top + 56 * k, rowDim);
                c.restore();
                return;
            }
            float barY = top + (chBar.value - chScroll.value) * rh;
            r.set(x, barY + 5 * k, x + cw, barY + rh - 5 * k);
            if (column == 1) {
                shadowRect(c, r, 20 * k, 0x80000000);
                fill.setColor(0xFFFFFFFF);
            } else {
                fill.setColor(0x1FFFFFFF);
            }
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            long now = System.currentTimeMillis();
            int first = Math.max(0, (int) chScroll.value - 1);
            for (int i = first; i < l.size(); i++) {
                float y = top + (i - chScroll.value) * rh;
                if (y > h) break;
                Iptv.Channel ch = l.get(i);
                boolean on = i == chIndex && column == 1;
                int ink = on ? 0xFF1C1C1E : 0xFFFFFFFF;
                number.setColor(on ? 0x991C1C1E : 0x80FFFFFF);
                c.drawText(String.valueOf(ch.number), x + 22 * k, y + rh / 2 + Ui.halfCap(number), number);
                // Logo on a soft plate (logos are made for dark and light backgrounds alike).
                r.set(x + 100 * k, y + 16 * k, x + 100 * k + 120 * k, y + rh - 16 * k);
                fill.setColor(on ? 0x141C1C1E : 0x14FFFFFF);
                c.drawRoundRect(r, 12 * k, 12 * k, fill);
                Bitmap logo = logos.get(ch.logo);
                if (logo != null) fit(c, logo, r, 6 * k);
                else {
                    String ini = ch.name.isEmpty() ? "?" : ch.name.substring(0, Math.min(2, ch.name.length())).toUpperCase(Locale.ROOT);
                    chip.setColor(on ? 0x991C1C1E : 0x99FFFFFF);
                    c.drawText(ini, r.centerX() - chip.measureText(ini) / 2, r.centerY() + Ui.halfCap(chip), chip);
                }
                float tx = x + 246 * k, right = x + cw - 24 * k;
                boolean fav = favorites.contains(ch.key());
                if (fav) {
                    drawStar(c, right - 12 * k, y + 36 * k, 11 * k, on ? 0xFFB8860B : 0xFFFFD60A);
                    right -= 34 * k;
                }
                if (ch == playing) {
                    fill.setColor(0xFFFF453A);
                    c.drawCircle(right - 6 * k, y + 36 * k, 6 * k, fill);
                    right -= 26 * k;
                }
                Iptv.Program[] nn = guide == null ? new Iptv.Program[2] : guide.nowNext(ch, now);
                row.setColor(ink);
                float nameY = nn[0] != null ? y + 44 * k : y + rh / 2 + Ui.halfCap(row);
                c.drawText(TextUtils.ellipsize(ch.name, new TextPaint(row), right - tx, TextUtils.TruncateAt.END).toString(), tx, nameY, row);
                if (nn[0] != null) {
                    rowSmall.setColor(on ? 0xB31C1C1E : 0x99FFFFFF);
                    c.drawText(TextUtils.ellipsize(nn[0].title, new TextPaint(rowSmall), x + cw - 24 * k - tx, TextUtils.TruncateAt.END).toString(),
                            tx, y + 76 * k, rowSmall);
                    float pr = Ui.clamp((now - nn[0].start) / (float) Math.max(1, nn[0].stop - nn[0].start));
                    float bx = tx, bw = Math.min(260 * k, x + cw - 24 * k - tx), by = y + 86 * k;
                    fill.setColor(on ? 0x261C1C1E : 0x26FFFFFF);
                    c.drawRoundRect(bx, by, bx + bw, by + 4 * k, 2 * k, 2 * k, fill);
                    fill.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                    c.drawRoundRect(bx, by, bx + bw * pr, by + 4 * k, 2 * k, 2 * k, fill);
                }
                row.setColor(0xFFFFFFFF);
            }
            c.restore();
        }

        /** Now and next for the focused channel, on the right (over the picture). */
        private void drawInfo(Canvas c, int w, int h, Iptv.Channel ch) {
            if (guide == null) return;
            long now = System.currentTimeMillis();
            Iptv.Program[] nn = guide.nowNext(ch, now);
            if (nn[0] == null && nn[1] == null) return;
            float x = (CH_X + CH_W + 50) * k, right = 1856 * k, y = 640 * k;
            r.set(x, y, right, 1010 * k);
            fill.setColor(0xB3141418);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            float tx = x + 32 * k, tw = right - 32 * k - tx, ty = y + 58 * k;
            if (nn[0] != null) {
                header.setColor(0xFFFF453A);
                c.drawText(Ui.tr("AGORA  ", "NOW  ") + Ui.time(nn[0].start) + " – " + Ui.time(nn[0].stop), tx, ty, header);
                ty += 46 * k;
                c.drawText(TextUtils.ellipsize(nn[0].title, new TextPaint(row), tw, TextUtils.TruncateAt.END).toString(), tx, ty, row);
                ty += 40 * k;
                if (!nn[0].desc.isEmpty()) ty = paragraph(c, nn[0].desc, tx, ty, tw, rowSmall, 3) + 14 * k;
            }
            if (nn[1] != null && ty < 960 * k) {
                header.setColor(0x80FFFFFF);
                c.drawText(Ui.tr("DEPOIS  ", "NEXT  ") + Ui.time(nn[1].start), tx, ty + 10 * k, header);
                c.drawText(TextUtils.ellipsize(nn[1].title, new TextPaint(rowDim), tw, TextUtils.TruncateAt.END).toString(), tx, ty + 50 * k, rowDim);
            }
            header.setColor(0x80FFFFFF);
        }

        private float paragraph(Canvas c, String text, float x, float y, float width, Paint p, int maxLines) {
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            int lines = 0;
            float lh = p.getTextSize() * 1.3f;
            for (int i = 0; i < words.length && lines < maxLines; i++) {
                String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
                if (p.measureText(tryLine) > width && line.length() > 0) {
                    boolean lastLine = lines == maxLines - 1;
                    String out = lastLine ? TextUtils.ellipsize(line + " " + words[i] + "…", new TextPaint(p), width, TextUtils.TruncateAt.END).toString() : line.toString();
                    c.drawText(out, x, y, p);
                    y += lh;
                    lines++;
                    line = new StringBuilder(words[i]);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            if (lines < maxLines && line.length() > 0) {
                c.drawText(line.toString(), x, y, p);
                y += lh;
            }
            return y;
        }

        private void drawBanner(Canvas c, int w, int h, float a) {
            Iptv.Channel ch = playing;
            float bh = 210 * k, x = 64 * k, right = w - 64 * k, y = h - bh - 48 * k + (1 - a) * 40 * k;
            if (a < 0.98f) c.saveLayerAlpha(0, y - 20 * k, w, h, Math.round(255 * a));
            else c.save();
            r.set(x, y, right, y + bh);
            shadowRect(c, r, 32 * k, 0x80000000);
            fill.setColor(0xE6141418);
            c.drawRoundRect(r, 32 * k, 32 * k, fill);
            RectF lr = new RectF(x + 32 * k, y + 36 * k, x + 32 * k + 240 * k, y + bh - 36 * k);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(lr, 18 * k, 18 * k, fill);
            Bitmap logo = logos.get(ch.logo);
            if (logo != null) fit(c, logo, lr, 12 * k);
            float tx = lr.right + 36 * k;
            number.setColor(0x99FFFFFF);
            c.drawText(String.valueOf(ch.number) + "  ·  " + ch.group, tx, y + 62 * k, number);
            c.drawText(TextUtils.ellipsize(ch.name, new TextPaint(big), right - 300 * k - tx, TextUtils.TruncateAt.END).toString(), tx, y + 112 * k, big);
            String clock = Ui.clock(System.currentTimeMillis());
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(clock, right - 32 * k, y + 62 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            if (errorText != null) {
                rowDim.setColor(0xFFFF6961);
                c.drawText(errorText, tx, y + 160 * k, rowDim);
                rowDim.setColor(0xB3FFFFFF);
            } else if (guide != null) {
                long now = System.currentTimeMillis();
                Iptv.Program[] nn = guide.nowNext(ch, now);
                if (nn[0] != null) {
                    String t = Ui.time(nn[0].start) + " – " + Ui.time(nn[0].stop) + "   " + nn[0].title;
                    c.drawText(TextUtils.ellipsize(t, new TextPaint(rowDim), right - 32 * k - tx, TextUtils.TruncateAt.END).toString(), tx, y + 158 * k, rowDim);
                    float pr = Ui.clamp((now - nn[0].start) / (float) Math.max(1, nn[0].stop - nn[0].start));
                    float bw = right - 32 * k - tx;
                    fill.setColor(0x33FFFFFF);
                    c.drawRoundRect(tx, y + 178 * k, tx + bw, y + 183 * k, 3 * k, 3 * k, fill);
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(tx, y + 178 * k, tx + bw * pr, y + 183 * k, 3 * k, 3 * k, fill);
                }
                if (nn[1] != null) {
                    String t = Ui.tr("Depois: ", "Next: ") + Ui.time(nn[1].start) + "  " + nn[1].title;
                    rowSmall.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(TextUtils.ellipsize(t, new TextPaint(rowSmall), 560 * k, TextUtils.TruncateAt.END).toString(), right - 32 * k, y + 112 * k, rowSmall);
                    rowSmall.setTextAlign(Paint.Align.LEFT);
                }
            }
            c.restore();
        }

        private void drawOptions(Canvas c, int w, int h, float a) {
            float pw = 560 * k, x = w - pw * a;
            fill.setColor(Ui.alpha(0xF2121216, 1));
            r.set(x, 0, x + pw, h);
            c.drawRect(r, fill);
            float y = 120 * k;
            c.drawText(Ui.tr("Opções", "Options"), x + 48 * k, y, big);
            y += 40 * k;
            for (int i = 0; i < options.size(); i++) {
                String[] o = options.get(i);
                if (o[0].equals("h")) {
                    y += 46 * k;
                    header.setColor(0x80FFFFFF);
                    c.drawText(o[1].toUpperCase(Ui.locale()), x + 48 * k, y, header);
                    y += 14 * k;
                    continue;
                }
                boolean f = i == optIndex;
                r.set(x + 28 * k, y, x + pw - 28 * k, y + 64 * k);
                if (f) {
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, 16 * k, 16 * k, fill);
                }
                rowDim.setColor(f ? 0xFF1C1C1E : 0xE6FFFFFF);
                c.drawText(TextUtils.ellipsize(o[1], new TextPaint(rowDim), pw - 160 * k, TextUtils.TruncateAt.END).toString(),
                        x + 48 * k, r.centerY() + Ui.halfCap(rowDim), rowDim);
                if (o[3].equals("1")) Symbols.draw(getContext(), c, "check", r.right - 34 * k, r.centerY(), 28 * k, f ? 0xFF1C1C1E : 0xFFFFFFFF);
                rowDim.setColor(0xB3FFFFFF);
                y += 70 * k;
            }
        }

        private void drawManage(Canvas c, int w, int h) {
            fill.setColor(0xCC000000);
            c.drawRect(0, 0, w, h, fill);
            List<Iptv.Playlist> list = managed();
            float cw = 900 * k, x = (w - cw) / 2f, y = 170 * k;
            c.drawText(Ui.tr("Listas de canais", "Playlists"), x, y, title);
            y += 40 * k;
            int n = list.size() + 2;
            String active = playlist == null ? "" : playlist.id;
            for (int i = 0; i < n; i++) {
                boolean f = i == manageIndex;
                r.set(x, y, x + cw, y + 84 * k);
                fill.setColor(f ? 0xFFFFFFFF : 0x1FFFFFFF);
                c.drawRoundRect(r, 20 * k, 20 * k, fill);
                int ink = f ? 0xFF1C1C1E : 0xFFFFFFFF;
                row.setColor(ink);
                String label;
                if (i < list.size()) {
                    Iptv.Playlist p = list.get(i);
                    label = p.name;
                    if (p.id.equals(active)) Symbols.draw(getContext(), c, "check", r.right - 40 * k, r.centerY(), 30 * k, ink);
                    rowSmall.setColor(f ? 0x991C1C1E : 0x80FFFFFF);
                    String host = Iptv.hostOf(p.url);
                    rowSmall.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(host, r.right - 90 * k, r.centerY() + Ui.halfCap(rowSmall), rowSmall);
                    rowSmall.setTextAlign(Paint.Align.LEFT);
                } else {
                    label = i == list.size() ? Ui.tr("+  Adicionar link M3U", "+  Add M3U link") : Ui.tr("+  Adicionar Xtream Codes", "+  Add Xtream Codes");
                }
                c.drawText(TextUtils.ellipsize(label, new TextPaint(row), cw - 380 * k, TextUtils.TruncateAt.END).toString(),
                        x + 32 * k, r.centerY() + Ui.halfCap(row), row);
                row.setColor(0xFFFFFFFF);
                y += 98 * k;
            }
            sub.setColor(0x80FFFFFF);
            c.drawText(Ui.tr("OK numa lista: usar, atualizar, renomear ou apagar.", "OK on a playlist: use, refresh, rename or delete."), x, y + 30 * k, sub);
            sub.setColor(0x99FFFFFF);
        }

        private void drawCues(Canvas c, int w, int h) {
            StringBuilder sb = new StringBuilder();
            for (Cue q : cues) if (q.text != null) sb.append(sb.length() == 0 ? "" : "\n").append(q.text);
            String[] lines = sb.toString().split("\n");
            float y = h - (state == WATCH && banner.value > 0.05f ? 300 : 90) * k - (lines.length - 1) * 54 * k;
            for (String line : lines) {
                c.drawText(line, w / 2f, y, cueText);
                y += 54 * k;
            }
        }

        private void drawDigits(Canvas c, int w) {
            float cw = 220 * k, x = w - cw - 64 * k, y = 56 * k;
            r.set(x, y, x + cw, y + 110 * k);
            fill.setColor(0xE6141418);
            c.drawRoundRect(r, 24 * k, 24 * k, fill);
            big.setTextAlign(Paint.Align.CENTER);
            c.drawText(digits, r.centerX(), r.centerY() + Ui.halfCap(big), big);
            big.setTextAlign(Paint.Align.LEFT);
        }

        private void drawPill(Canvas c, String s, float cx, float cy, int color) {
            float tw = Math.min(rowDim.measureText(s), 1500 * k), ph = 74 * k;
            r.set(cx - tw / 2 - 40 * k, cy - ph / 2, cx + tw / 2 + 40 * k, cy + ph / 2);
            fill.setColor(color);
            c.drawRoundRect(r, ph / 2, ph / 2, fill);
            rowDim.setColor(0xFFFFFFFF);
            String t = TextUtils.ellipsize(s, new TextPaint(rowDim), 1500 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(t, cx - rowDim.measureText(t) / 2, cy + Ui.halfCap(rowDim), rowDim);
            rowDim.setColor(0xB3FFFFFF);
        }

        private void centered(Canvas c, String s, float y, Paint p) {
            c.drawText(s, getWidth() / 2f - p.measureText(s) / 2, y, p);
        }

        private void shadowRect(Canvas c, RectF rr, float rad, int color) {
            fill.setColor(color);
            c.drawRoundRect(rr.left, rr.top + 10 * k, rr.right, rr.bottom + 10 * k, rad, rad, fill);
        }

        private void fit(Canvas c, Bitmap b, RectF box, float pad) {
            float bw = box.width() - 2 * pad, bh = box.height() - 2 * pad;
            float s = Math.min(bw / b.getWidth(), bh / b.getHeight());
            float dw = b.getWidth() * s, dh = b.getHeight() * s;
            RectF d = new RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
            c.drawBitmap(b, null, d, bmp);
        }

        private void drawStar(Canvas c, float cx, float cy, float rad, int color) {
            star.reset();
            for (int i = 0; i < 10; i++) {
                double ang = -Math.PI / 2 + i * Math.PI / 5;
                float rr = i % 2 == 0 ? rad : rad * 0.45f;
                float px = cx + (float) Math.cos(ang) * rr, py = cy + (float) Math.sin(ang) * rr;
                if (i == 0) star.moveTo(px, py);
                else star.lineTo(px, py);
            }
            star.close();
            fill.setColor(color);
            c.drawPath(star, fill);
        }
    }
}
