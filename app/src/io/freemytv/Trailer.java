package io.freemytv;

import android.annotation.SuppressLint;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Trailer preview on For You's big card: the title's YouTube trailer (TMDB "videos"), playing inside the card's rounded
 * shape. The player loads hidden and only fades in once YouTube reports it is actually playing, so the card never shows
 * a spinner, a black frame or YouTube's title bar (the 16:9 video is cropped to the wide card, like CSS object-fit:
 * cover, which also cuts the title bar and the logo off). The sound rises over a second and a half instead of starting
 * at full volume.
 *
 * The page is loaded from an https base URL with a matching "origin" so YouTube's embed gets the referrer it requires
 * (without one, WebView embeds fail with "error 153"). The WebView never takes focus: the remote keeps driving the home
 * screen. It is created on first use and destroyed when the home screen goes to the background (it's the biggest single
 * user of memory on the TV).
 */
final class Trailer {
    private static final String ORIGIN = "https://freemytv.app";
    private static final long FADE_IN_MS = 700, FADE_OUT_MS = 260;

    interface Host { void trailerFailed(String key); }

    private final ViewGroup parent;
    private final int index;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean broken;           // no WebView on this TV (or it crashed): previews stay off for this run
    private String key;               // playing (or loading) this video; null = idle
    private float radius;
    private final RectF rect = new RectF();

    Trailer(ViewGroup parent, int index, Host host) {
        this.parent = parent;
        this.index = index;
        this.host = host;
    }

    boolean active() {
        return key != null;
    }

    String key() {
        return key;
    }

    /** Starts the video (hidden until it plays) in `r` (screen pixels) with the card's corner radius. */
    @SuppressLint("SetJavaScriptEnabled")
    void play(String videoKey, RectF r, float cornerRadius, boolean muted) {
        if (broken || videoKey == null) return;
        if (videoKey.equals(key)) return;
        if (web == null && !create()) return;
        key = videoKey;
        rect.set(r);
        radius = cornerRadius;
        web.animate().cancel();
        web.setAlpha(0);
        web.setVisibility(View.VISIBLE);
        layout();
        web.loadDataWithBaseURL(ORIGIN + "/", page(videoKey, Math.round(r.width()), Math.round(r.height()), muted),
                "text/html", "utf-8", null);
    }

    /** Fades out and stops (the WebView stays, blank, for the next one). */
    void stop() {
        if (web == null || key == null) return;
        key = null;
        final WebView v = web;
        v.evaluateJavascript("window.fadeOut&&fadeOut()", null);
        v.animate().cancel();
        v.animate().alpha(0).setStartDelay(0).setDuration(FADE_OUT_MS).withEndAction(() -> {
            if (key != null || web != v) return;   // a new one started meanwhile
            v.setVisibility(View.INVISIBLE);
            v.loadUrl("about:blank");
        }).start();
    }

    /** The home screen went away: frees the WebView. */
    void release() {
        key = null;
        if (web == null) return;
        WebView v = web;
        web = null;
        v.animate().cancel();
        parent.removeView(v);
        try {
            v.loadUrl("about:blank");
            v.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    /** Lays the player out where the card is (called by the parent's onLayout too). */
    void layout() {
        if (web == null) return;
        int w = Math.round(rect.width()), h = Math.round(rect.height());
        web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        web.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.left) + w, Math.round(rect.top) + h);
        web.invalidateOutline();
    }

    /** Follows the card's layer when it moves (the drawer lifts it). */
    void setTranslationY(float y) {
        if (web != null) web.setTranslationY(y);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private boolean create() {
        try {
            WebView v = new WebView(parent.getContext());
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
            v.setBackgroundColor(0xFF000000);
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            v.setVerticalScrollBarEnabled(false);
            v.setHorizontalScrollBarEnabled(false);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
            v.setWebViewClient(new WebViewClient());
            v.setWebChromeClient(new WebChromeClient());
            v.addJavascriptInterface(new Bridge(), "Android");
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            v.setClipToOutline(true);
            v.setAlpha(0);
            v.setVisibility(View.INVISIBLE);
            parent.addView(v, Math.min(index, parent.getChildCount()));
            web = v;
            return true;
        } catch (Throwable t) {   // no WebView provider, or it failed to load
            AppLog.w("trailers off, no WebView: " + t);
            broken = true;
            return false;
        }
    }

    /** YouTube's IFrame player, sized to cover the card (16:9 cropped to the card's shape) and centered. */
    private static String page(String videoKey, int w, int h, boolean muted) {
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
                + "#p{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);border:0;pointer-events:none}</style>"
                + "</head><body><div id='p'></div><script>"
                + "var K='" + videoKey.replaceAll("[^A-Za-z0-9_-]", "") + "',M=" + (muted ? 1 : 0) + ",P=null,R=null,started=0;"
                + "function cover(){var W=innerWidth||" + w + ",H=innerHeight||" + h + ",vw=W,vh=W*9/16;"
                + "if(vh<H){vh=H;vw=H*16/9;}return[Math.ceil(vw*1.02),Math.ceil(vh*1.02)];}"
                + "function ramp(to,ms,done){if(!P||M)return done&&done();clearInterval(R);var from=P.getVolume?P.getVolume():0,"
                + "t0=Date.now();R=setInterval(function(){var e=Math.min(1,(Date.now()-t0)/ms);e=e*e*(3-2*e);"
                + "try{P.setVolume(Math.round(from+(to-from)*e));}catch(x){}if(e>=1){clearInterval(R);done&&done();}},40);}"
                + "function fadeOut(){ramp(0,220,function(){try{P.pauseVideo();}catch(x){}});}"
                + "function onYouTubeIframeAPIReady(){var s=cover();P=new YT.Player('p',{width:s[0],height:s[1],videoId:K,"
                + "host:'https://www.youtube.com',playerVars:{autoplay:1,mute:M,controls:0,disablekb:1,fs:0,iv_load_policy:3,"
                + "modestbranding:1,playsinline:1,rel:0,loop:1,playlist:K,cc_load_policy:0,origin:'" + ORIGIN + "'},"
                + "events:{onReady:function(e){if(M)e.target.mute();else{e.target.unMute();e.target.setVolume(0);}"
                + "e.target.setPlaybackQuality&&e.target.setPlaybackQuality('hd1080');e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data==1&&!started){started=1;Android.playing(K);ramp(100,1500);}},"
                + "onError:function(e){Android.failed(K,''+e.data);}}});}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    private final class Bridge {
        @JavascriptInterface public void playing(final String k) {
            main.post(() -> {
                if (web == null || !k.equals(key)) return;
                // A beat after "playing": the first frame is on screen, so the fade shows motion, not black.
                web.animate().cancel();
                web.animate().alpha(1).setStartDelay(250).setDuration(FADE_IN_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            });
        }

        @JavascriptInterface public void failed(final String k, final String code) {
            main.post(() -> {
                AppLog.w("trailer " + k + " failed: " + code);
                if (!k.equals(key)) return;
                stop();
                host.trailerFailed(k);
            });
        }
    }
}
