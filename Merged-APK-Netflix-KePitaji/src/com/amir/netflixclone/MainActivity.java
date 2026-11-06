package com.amir.netflixclone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebView shell for the Netflix Ke Pitaji web app (assets/www).
 * NOTE: original runtime logic preserved 1:1 (asset serving, /p proxy, ad blocking,
 * player proxy+inject, popup traps, fullscreen, intro video, profiles, JS bridge).
 * ADDITIONS ONLY: NFApp.mbHome/mbSearch/mbPlay bridges + /mbp streaming proxy
 * for MovieBox data/playback merged into the page by assets/www/moviebox.js.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "nf_app";
    private static final String PREF_PROFILE = "profile";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String SPOOF_JS = "(function(){try{var chromeLike={app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'},RunningState:{CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'},getDetails:function(){return null;},getIsInstalled:function(){return false;},install:function(){},running:function(){return false;}},csi:function(){return{};},loadTimes:function(){return{};},runtime:{connect:function(){return{};},sendMessage:function(){},getManifest:function(){return{};},getURL:function(u){return u||'';},id:'',PlatformOs:{MAC:'mac',WIN:'win',ANDROID:'android',CROS:'cros',LINUX:'linux',OPENBSD:'openbsd'},OnInstalledReason:{CHROME_UPDATE:'chrome_update',INSTALL:'install',SHARED_MODULE_UPDATE:'shared_module_update',UPDATE:'update'},OnRestartRequiredReason:{APP_UPDATE:'app_update',OS_UPDATE:'os_update',PERIODIC:'periodic'}}};var replaced=false;try{Object.defineProperty(window,'chrome',{get:function(){return chromeLike;},configurable:true});replaced=true;}catch(e){}if(!replaced){try{if(!window.chrome){window.chrome={};}for(var k in chromeLike){try{window.chrome[k]=chromeLike[k];}catch(e2){}}try{delete window.chrome.webview;}catch(e3){}}catch(e5){}}}catch(e){}try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}try{Object.defineProperty(navigator,'vendor',{get:function(){return 'Google Inc.';},configurable:true});}catch(e){}})();";

    private static final String START_URL = "http://localhost/index.html";

    private String currentProfile;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private View fullscreenView;
    private FrameLayout introPanel;
    private VideoView introVideo;
    private WebView mainWebView;
    private LinearLayout profilePanel;
    private FrameLayout rootLayout;

    private static final String[] PROFILE_NAMES = {"Mian Amir", "Atif", "Aqib", "Abdullah", "Kids"};
    private static final int[] PROFILE_COLORS = {-1767148, -16747546, -16729939, -7655466, -674550};

    private static final Set<String> ASSET_EXTENSIONS = new HashSet<>(Arrays.asList(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "mp4", "m4v", "webm", "mkv", "m3u8", "ts",
            "mpd", "json", "map", "vtt", "srt", "xml", "txt", "wasm"));

    private static final String[] AD_HOSTS = {"doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagmanager.com", "google-analytics.com", "adservice.google", "applovin.com", "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net", "criteo.com", "criteo.net", "casalemedia.com", "adform.net", "smartadserver.com", "advertising.com", "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "popads.net", "popcash.net", "adcash.com", "zeropark.com", "bidgear.com", "propellerads.com", "propellerclick.com", "monetag.com", "onclickalgo.com", "onclickmega.com", "onclickperformance.com", "onclickpredictiv.com", "onclickrev.com", "adsterra.network", "hilltopads.net", "hilltopads.com", "galaksion.com", "juicyads.com", "exoclick.com", "exosrv.com", "realsrv.com", "trafficjunky.net", "trafficfactory.biz", "histats.com", "onesignal.com", "pushwoosh.com", "sharethis.com"};

    private static final String[] PLAYER_HOSTS = {"2embed.cc", "2embed.com", "vidsrc", "vidlink.org", "smashystream", "smashyplay", "superembed", "vidora", "autoembed", "multiembed", "flixer", "vidfast", "vidjoy", "spenembed", "vidora.su"};

    private static final String[] PROXY_HOSTS = {"api.themoviedb.org", "image.tmdb.org", "cors-anywhere.pulkitpareekofficial.workers.dev", "v3.sg.media-imdb.com", "media-imdb.com", "media-amazon.com", "m.media-amazon.com", "images.weserv.nl"};

    private final List<WebView> popupTraps = new ArrayList<>();
    private final Handler trapHandler = new Handler();
    private boolean introDone = false;
    private boolean pageLoadedOnce = false;

    /* --------------------------------------------------------------
       JS BRIDGE (NFApp). Original methods unchanged; mb* methods added.
       -------------------------------------------------------------- */
    private class Bridge {

        @JavascriptInterface
        public void search(final String str, final String str2) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String q = str == null ? "" : str;
                    String javaSearch = javaSearch(q);
                    final String quote = javaSearch == null ? "null" : JSONObject.quote(javaSearch);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mainWebView != null) {
                                try {
                                    mainWebView.evaluateJavascript(
                                            "window.__NF_javaSearch&&__NF_javaSearch(" + JSONObject.quote(str2) + "," + quote + ")", null);
                                } catch (Throwable ignored) { }
                            }
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void showProfiles() {
            runOnUiThread(new Runnable() {
                @Override public void run() { showProfilesPanel(); }
            });
        }

        @JavascriptInterface
        public String version() {
            return "1.9";
        }

        /* ---------- MovieBox additions (purely additive) ---------- */

        @JavascriptInterface
        public void mbHome(final String cbId) {
            MovieBoxApi.async(new MovieBoxApi.Bg<String>() {
                @Override public String run() { return mbHomeJson(); }
            }, new MovieBoxApi.Ui<String>() {
                @Override public void onDone(String result) { mbReply(cbId, result); }
            });
        }

        @JavascriptInterface
        public void mbSearch(final String q, final String cbId) {
            MovieBoxApi.async(new MovieBoxApi.Bg<String>() {
                @Override public String run() { return mbSearchJson(q == null ? "" : q); }
            }, new MovieBoxApi.Ui<String>() {
                @Override public void onDone(String result) { mbReply(cbId, result); }
            });
        }

        @JavascriptInterface
        public void mbPlay(final String subjectId, final String se, final String ep, final String cbId) {
            MovieBoxApi.async(new MovieBoxApi.Bg<String>() {
                @Override public String run() {
                    return mbPlayJson(subjectId == null ? "" : subjectId,
                            parseIntSafe(se), parseIntSafe(ep));
                }
            }, new MovieBoxApi.Ui<String>() {
                @Override public void onDone(String result) { mbReply(cbId, result); }
            });
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(String.valueOf(s).trim()); } catch (Exception e) { return 0; }
    }

    private void mbReply(final String cbId, final String payload) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mainWebView == null) return;
                try {
                    String data = payload == null ? "null" : JSONObject.quote(payload);
                    mainWebView.evaluateJavascript(
                            "window.__NF_mbReply&&__NF_mbReply(" + JSONObject.quote(cbId) + "," + data + ")", null);
                } catch (Throwable ignored) { }
            }
        });
    }

    /* ---------- MovieBox JSON builders (background thread) ---------- */

    private static volatile String sMbHomeCache = "";
    private static volatile long sMbHomeCacheAt = 0L;

    private static JSONObject mbItemOf(JSONObject subject) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", subject.optString("subjectId", ""));
            o.put("t", subject.optString("title", ""));
            o.put("p", MovieBoxApi.coverUrl(subject));
            o.put("s", subject.optInt("subjectType", 1) == 2 ? 1 : 0);
            return o;
        } catch (Exception e) { return null; }
    }

    private String mbHomeJson() {
        long now = System.currentTimeMillis();
        String cached = sMbHomeCache;
        if (cached.length() > 0 && now - sMbHomeCacheAt < 15 * 60 * 1000L) return cached;
        JSONArray rows = new JSONArray();
        int[][] wants = {{2, 4}, {5, 2}};   // tabId, max sections taken
        for (int[] w : wants) {
            try {
                JSONObject res = MovieBoxApi.getTabOperating(String.valueOf(w[0]), 1);
                JSONObject data = res.optJSONObject("data");
                if (data == null) continue;
                JSONArray items = data.optJSONArray("items");
                if (items == null) continue;
                int taken = 0;
                for (int i = 0; i < items.length() && taken < w[1]; i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String type = it.optString("type", "");
                    if (!type.startsWith("SUBJECTS")) continue;
                    JSONArray subs = it.optJSONArray("subjects");
                    if (subs == null || subs.length() == 0) continue;
                    String label = it.optString("title", it.optString("name", "MovieBox"));
                    JSONArray arr = new JSONArray();
                    int n = Math.min(subs.length(), 14);
                    for (int j = 0; j < n; j++) {
                        JSONObject info = MovieBoxApi.infoOf(subs.optJSONObject(j));
                        if (info == null) continue;
                        JSONObject item = mbItemOf(info);
                        if (item != null && item.optString("id").length() > 0
                                && item.optString("p").length() > 0) arr.put(item);
                    }
                    if (arr.length() == 0) continue;
                    JSONObject row = new JSONObject();
                    row.put("label", label);
                    row.put("items", arr);
                    rows.put(row);
                    taken++;
                }
            } catch (Exception ignored) { }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", rows.length() > 0 ? 1 : 0);
            out.put("rows", rows);
        } catch (Exception ignored) { }
        String s = out.toString();
        if (rows.length() > 0) {
            sMbHomeCache = s;
            sMbHomeCacheAt = System.currentTimeMillis();
        }
        return s;
    }

    private String mbSearchJson(String q) {
        JSONObject out = new JSONObject();
        try {
            String trim = q.trim();
            if (trim.length() == 0) {
                out.put("ok", 0);
                out.put("items", new JSONArray());
                return out.toString();
            }
            JSONArray res = MovieBoxApi.searchResults(trim, 1);
            JSONArray items = new JSONArray();
            int n = Math.min(res.length(), 20);
            for (int i = 0; i < n; i++) {
                JSONObject item = mbItemOf(res.optJSONObject(i));
                if (item != null) items.put(item);
            }
            out.put("ok", items.length() > 0 ? 1 : 0);
            out.put("items", items);
        } catch (Exception e) {
            try { out.put("ok", 0); out.put("items", new JSONArray()); } catch (Exception ignored) { }
        }
        return out.toString();
    }

    private String mbPlayJson(String subjectId, int seIn, int epIn) {
        JSONObject out = new JSONObject();
        try {
            JSONObject det = MovieBoxApi.getSubject(subjectId);
            JSONObject subject = det.optJSONObject("data");
            if (subject == null) subject = new JSONObject();
            int subjectType = subject.optInt("subjectType", 2);
            boolean isSeries = subjectType == 2 || subject.optInt("seNum", 0) > 0;
            int se = seIn, ep = epIn;
            JSONArray seasons = new JSONArray();
            try {
                JSONObject si = MovieBoxApi.getSeasonInfoV2(subjectId);
                JSONObject sd = si.optJSONObject("data");
                if (sd != null && sd.optJSONArray("seasons") != null) seasons = sd.optJSONArray("seasons");
            } catch (Exception ignored) { }
            if (isSeries && se <= 0) {
                int firstSe = 1;
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject s0 = seasons.optJSONObject(i);
                    if (s0 != null && s0.optInt("se", 0) > 0) { firstSe = s0.optInt("se"); break; }
                }
                se = firstSe;
            }
            if (isSeries && ep <= 0) ep = 1;
            MBStreams.Result r = MBStreams.resolveSubject(subject, subjectId, se, ep);
            out.put("ok", r.candidates.isEmpty() ? 0 : 1);
            out.put("error", r.error);
            out.put("title", subject.optString("title", ""));
            out.put("poster", MovieBoxApi.coverUrl(subject));
            out.put("isSeries", r.isSeries ? 1 : 0);
            out.put("se", se);
            out.put("ep", ep);
            out.put("seNum", subject.optInt("seNum", 0));
            out.put("seasons", seasons);
            JSONArray cands = new JSONArray();
            for (MBStreams.Candidate c : r.candidates) {
                JSONObject c0 = new JSONObject();
                c0.put("u", c.url);
                c0.put("kind", c.kind);
                c0.put("q", c.quality);
                c0.put("c", c.codec);
                c0.put("cookie", c.cookie);
                c0.put("size", c.size);
                cands.put(c0);
            }
            out.put("cands", cands);
        } catch (Exception e) {
            try {
                out.put("ok", 0);
                out.put("error", "MovieBox network error: " + e.getMessage());
                out.put("cands", new JSONArray());
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    /* --------------------------------------------------------------
       Original wrapper logic (unchanged behavior)
       -------------------------------------------------------------- */

    /** first profile init + page hook */
    public void applyProfileToPage() {
        WebView wv = mainWebView;
        if (wv == null) return;
        String str = currentProfile == null ? PROFILE_NAMES[0] : currentProfile;
        try {
            wv.evaluateJavascript("window.__NF_setProfile&&__NF_setProfile(" + JSONObject.quote(str) + ")", null);
        } catch (Throwable ignored) { }
    }

    /** navigation policy: localhost/about/data ok; ads blocked; only player hosts navigable */
    public boolean blockOrAllow(Uri uri) {
        try {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if ((!scheme.startsWith("http") || !"localhost".equals(uri.getHost()))
                    && !scheme.equals("about") && !scheme.equals("data")) {
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                if (isAdHost(host)) return true;
                return !isPlayerHost(host);
            }
            return false;
        } catch (Throwable unused) {
            return true;
        }
    }

    private void buildIntroPanel() {
        FrameLayout frameLayout = new FrameLayout(this);
        introPanel = frameLayout;
        frameLayout.setBackgroundColor(-16777216);
        introVideo = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.gravity = 17;
        introPanel.addView(introVideo, lp);
        TextView skip = new TextView(this);
        skip.setText("Skip Intro");
        skip.setTextColor(-2236963);
        skip.setTextSize(13.0f);
        skip.setPadding(28, 16, 28, 16);
        skip.setBackgroundColor(1711276032);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishIntro(true); }
        });
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(-2, -2);
        lp2.gravity = 8388693;
        lp2.setMargins(0, 0, 36, 60);
        introPanel.addView(skip, lp2);
        introPanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishIntro(true); }
        });
        rootLayout.addView(introPanel, new FrameLayout.LayoutParams(-1, -1));
        playIntro();
    }

    private void buildProfilePanel() {
        LinearLayout panel = new LinearLayout(this);
        profilePanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(-16777216);
        panel.setGravity(17);
        panel.setVisibility(8);
        TextView title = new TextView(this);
        title.setText("Who's Watching?");
        title.setTextColor(-1);
        title.setTextSize(24.0f);
        title.setGravity(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(34);
        panel.addView(title, lp);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout rowL = new LinearLayout(this);
        rowL.setOrientation(LinearLayout.HORIZONTAL);
        rowL.setGravity(17);
        int size = (int) (getResources().getDisplayMetrics().density * 86.0f);
        for (int i = 0; i < PROFILE_NAMES.length && i < PROFILE_COLORS.length; i++) {
            final String name = PROFILE_NAMES[i];
            int color = PROFILE_COLORS[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(17);
            cell.setPadding(dp(10), dp(4), dp(10), dp(4));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(drawAvatar(color));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(size, size);
            ivLp.bottomMargin = dp(10);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onProfileSelected(name); }
            });
            cell.addView(iv, ivLp);
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextColor(name.equals(currentProfile) ? -1 : -6513508);
            tv.setTextSize(12.0f);
            tv.setGravity(17);
            cell.addView(tv, new LinearLayout.LayoutParams(-2, -2));
            rowL.addView(cell);
        }
        scroll.addView(rowL, new FrameLayout.LayoutParams(-2, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        rootLayout.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void configureSettings(WebSettings s) {
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(2);
        s.setCacheMode(-1);
        try {
            String ua = s.getUserAgentString();
            if (ua != null) {
                String cleaned = ua.replace("; wv", "").replace("; wv)", ")")
                        .replaceFirst("Version/[0-9.]+ ", "");
                if (cleaned.length() > 0) s.setUserAgentString(cleaned);
            }
        } catch (Throwable ignored) { }
    }

    /** hidden popup trap WebView (protects against window.open ad spam) */
    private WebView createTrap() {
        Iterator<WebView> it = popupTraps.iterator();
        while (it.hasNext()) {
            try { it.next().loadUrl("about:blank"); } catch (Throwable ignored) { }
        }
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                scheduleTrapBlank(view);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                return scheme != null && !scheme.equals("http") && !scheme.equals("https");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String scheme = Uri.parse(url).getScheme();
                return scheme != null && !scheme.equals("http") && !scheme.equals("https");
            }
        });
        wv.setVisibility(8);
        popupTraps.add(wv);
        if (popupTraps.size() > 3) destroyTrap(popupTraps.remove(0));
        return wv;
    }

    private void destroyTrap(WebView wv) {
        if (wv == null) return;
        try { wv.stopLoading(); } catch (Throwable ignored) { }
        try { wv.removeAllViews(); } catch (Throwable ignored) { }
        try { wv.destroy(); } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Bitmap drawAvatar(int color) {
        Bitmap bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(1);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        float f = 192;
        canvas.drawRoundRect(new RectF(0f, 0f, f, f), 34f, 34f, paint);
        paint.setColor(-1);
        paint.setStyle(Paint.Style.FILL);
        float r = 0.075f * f;
        float y = 0.42f * f;
        canvas.drawCircle(0.34f * f, y, r, paint);
        canvas.drawCircle(0.66f * f, y, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.07f * f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(new RectF(0.28f * f, 0.44f * f, 0.72f * f, f * 0.76f), 20f, 140f, false, paint);
        return bmp;
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    /** HTML5 video fullscreen: sensor landscape + sticky immersive */
    private void enterFullscreenMode() {
        try { getWindow().addFlags(128); } catch (Throwable ignored) { }
        hideSystemBars();
        try { setRequestedOrientation(6); } catch (Throwable ignored) { }
    }

    private void exitFullscreen() {
        try { getWindow().clearFlags(128); } catch (Throwable ignored) { }
        showSystemBars();
        try { setRequestedOrientation(-1); } catch (Throwable ignored) { }
        if (fullscreenView == null) return;
        try { rootLayout.removeView(fullscreenView); } catch (Throwable ignored) { }
        fullscreenView = null;
        if (fullscreenCallback != null) {
            try { fullscreenCallback.onCustomViewHidden(); } catch (Throwable ignored) { }
            fullscreenCallback = null;
        }
        if ((profilePanel == null || profilePanel.getVisibility() != 0) && mainWebView != null) {
            mainWebView.setVisibility(0);
        }
    }

    private void finishIntro(boolean clicked) {
        if (introDone) return;
        introDone = true;
        try { introVideo.stopPlayback(); } catch (Throwable ignored) { }
        try { introPanel.setVisibility(8); } catch (Throwable ignored) { }
        showSystemBars();
        showProfilesPanel();
    }

    private void goBackOrExit() {
        WebView wv = mainWebView;
        if (wv == null || !wv.canGoBack()) {
            finish();
        } else {
            mainWebView.goBack();
        }
    }

    private static String guessMime(String path) {
        String l = path.toLowerCase();
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json") || l.endsWith(".map") || l.endsWith(".webmanifest")) return "application/json";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".avif")) return "image/avif";
        if (l.endsWith(".ico")) return "image/x-icon";
        if (l.endsWith(".woff")) return "font/woff";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".ttf")) return "font/ttf";
        if (l.endsWith(".otf")) return "font/otf";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void hideSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(5894);
        } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(2);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** inject SPOOF_JS right after <head...> (or prepend when tag missing) */
    private static byte[] injectIntoHtml(byte[] html) {
        try {
            String src = new String(html, "UTF-8");
            String lower = src.toLowerCase();
            int tag = lower.indexOf("<head");
            if (tag < 0) tag = lower.indexOf("<html");
            if (tag < 0) tag = lower.indexOf("<!doctype");
            if (tag < 0) {
                return ("<script>" + SPOOF_JS + "</script>" + src).getBytes("UTF-8");
            }
            int close = lower.indexOf('>', tag);
            if (close < 0) {
                return ("<script>" + SPOOF_JS + "</script>" + src).getBytes("UTF-8");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(src, 0, close + 1);
            sb.append("<script>").append(SPOOF_JS).append("</script>");
            sb.append(src.substring(close + 1));
            return sb.toString().getBytes("UTF-8");
        } catch (Throwable unused) {
            return html;
        }
    }

    /** central request interception */
    public WebResourceResponse intercept(WebResourceRequest req) {
        String scheme;
        String host;
        Uri url;
        try {
            url = req.getUrl();
            scheme = url.getScheme() == null ? "" : url.getScheme();
            host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        } catch (Throwable unused) {
            return null;
        }
        if (scheme.startsWith("http") && "localhost".equals(host)) {
            if ("/p".equals(url.getPath())) {
                WebResourceResponse r = proxyPassthrough(url);
                return r != null ? r : emptyResponse();
            }
            if ("/mbp".equals(url.getPath())) {
                WebResourceResponse r = mbProxy(req);
                return r != null ? r : emptyResponse();
            }
            return serveAsset(url);
        }
        if (isAdHost(host)) return emptyResponse();
        if (scheme.startsWith("http") && "GET".equals(req.getMethod())
                && isProxyHost(host)) {
            WebResourceResponse r = proxyPassthrough(url);
            if (r != null) return r;
        }
        if (scheme.startsWith("http") && "GET".equals(req.getMethod())
                && isPlayerHost(host) && isFrameDocumentLoad(req) && isDocumentLike(url)) {
            WebResourceResponse r = proxyAndInject(req);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean isAdHost(String host) {
        if (host != null && host.length() != 0) {
            for (String h : AD_HOSTS) if (host.contains(h)) return true;
        }
        return false;
    }

    private static boolean isDocumentLike(Uri uri) {
        try {
            String path = uri.getPath();
            if (path == null || path.length() == 0) return true;
            String last = path.substring(path.lastIndexOf('/') + 1);
            int dot = last.lastIndexOf('.');
            if (dot <= 0) return true;
            return !ASSET_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase());
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean isFrameDocumentLoad(WebResourceRequest req) {
        Map<String, String> headers;
        try { headers = req.getRequestHeaders(); } catch (Throwable unused) { headers = null; }
        if (headers == null) return true;
        String dest = null;
        String accept = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            String val = e.getValue() == null ? "" : e.getValue().toLowerCase();
            if (key.equalsIgnoreCase("Sec-Fetch-Dest")) dest = val;
            else if (key.equalsIgnoreCase("Accept")) accept = val;
        }
        if (dest != null) return dest.equals("document") || dest.equals("iframe") || dest.equals("frame");
        return accept.contains("text/html") || accept.length() == 0;
    }

    private static boolean isPlayerHost(String host) {
        if (host != null && host.length() != 0) {
            for (String h : PLAYER_HOSTS) if (host.contains(h)) return true;
        }
        return false;
    }

    private static boolean isProxyHost(String host) {
        if (host != null && host.length() != 0) {
            for (String h : PROXY_HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
        }
        return false;
    }

    /** plain GET used by NFApp.search (IMDb/TMDB fallback) */
    private static String javaGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            byte[] data = readAll(in);
            try { in.close(); } catch (Throwable ignored) { }
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** Java-side IMDb search with TMDB fallback (unchanged) */
    private String javaSearch(String q) {
        String trim = q == null ? "" : q.trim();
        if (trim.length() == 0) return null;
        String enc;
        try {
            enc = URLEncoder.encode(trim, "UTF-8");
        } catch (Throwable unused) {
            enc = trim.replaceAll("[^A-Za-z0-9]", "+");
        }
        String[] tries = {
                "https://v3.sg.media-imdb.com/suggestion/x/" + enc + ".json",
                "https://v2.sg.media-imdb.com/suggestion/"
                        + trim.substring(0, 1).toLowerCase() + "/" + enc + ".json",
                "https://cors-anywhere.pulkitpareekofficial.workers.dev/?url="
                        + URLEncoder.encode("https://v3.sg.media-imdb.com/suggestion/x/" + enc + ".json"),
        };
        for (String t : tries) {
            String res = javaGet(t, 7000);
            if (res != null && res.contains("\"d\"")) {
                return "{\"src\":\"imdb\",\"body\":" + JSONObject.quote(res) + "}";
            }
        }
        String res = javaGet("https://api.themoviedb.org/3/search/multi?api_key="
                + "b6b677eb7d4ec17f700e3d4dfc31d005&language=en-US&query=" + enc, 8000);
        if (res == null || !res.contains("\"results\"")) return null;
        return "{\"src\":\"tmdb\",\"body\":" + JSONObject.quote(res) + "}";
    }

    private void keepCookies(HttpURLConnection conn, Uri uri) {
        try {
            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers == null) return;
            CookieManager cm = CookieManager.getInstance();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if ((e.getKey() == null ? "" : e.getKey()).equalsIgnoreCase("Set-Cookie")
                        && e.getValue() != null) {
                    for (String v : e.getValue()) {
                        try { cm.setCookie(uri.toString(), v); } catch (Throwable ignored) { }
                    }
                }
            }
            cm.flush();
        } catch (Throwable ignored) { }
    }

    /* ---------- /mbp streaming proxy (MovieBox media, Range-aware) ---------- */

    private WebResourceResponse mbProxy(WebResourceRequest req) {
        HttpURLConnection conn = null;
        try {
            Uri uri = req.getUrl();
            String u = uri.getQueryParameter("u");
            if (u == null || u.length() == 0) return null;
            u = u.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
            URL target = new URL(u);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Referer", "https://api6.aoneroom.com/");
            String ck = uri.getQueryParameter("ck");
            if (ck != null && ck.length() > 0) {
                try { conn.setRequestProperty("Cookie", URLDecoder.decode(ck, "UTF-8")); }
                catch (Throwable ignored) { }
            }
            // pass Range header straight through (seeking support)
            Map<String, String> rh = req.getRequestHeaders();
            if (rh != null) {
                for (Map.Entry<String, String> e : rh.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Range")
                            && e.getValue() != null) {
                        conn.setRequestProperty("Range", e.getValue());
                    }
                }
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 206 && code != 302) return null;
            String ct = conn.getContentType();
            String mime = ct == null || ct.length() == 0 ? "application/octet-stream" : ct;
            int semi = mime.indexOf(';');
            if (semi >= 0) mime = mime.substring(0, semi).trim();
            String ckRaw = ck == null ? "" : ck;
            String finalUrl = conn.getURL() != null ? conn.getURL().toExternalForm() : u;
            boolean isPlaylist = u.toLowerCase().contains(".m3u8") || mime.toLowerCase().contains("mpegurl");
            if (isPlaylist) {
                /* rewrite relative segment/variant URIs to absolute /mbp proxied URLs
                   so hls.js can fetch every child through the proxy too */
                String text = readAllText(conn.getInputStream());
                try { conn.disconnect(); } catch (Throwable ignored) { }
                String rewritten = rewritePlaylist(text, finalUrl, ckRaw);
                byte[] bytes = rewritten.getBytes("UTF-8");
                WebResourceResponse resp = new WebResourceResponse("application/vnd.apple.mpegurl",
                        "utf-8", new ByteArrayInputStream(bytes));
                Map<String, String> out2 = new HashMap<>();
                out2.put("Access-Control-Allow-Origin", "*");
                out2.put("Cache-Control", "no-cache");
                resp.setResponseHeaders(out2);
                return resp;
            }
            InputStream in = conn.getInputStream();
            WebResourceResponse resp = new WebResourceResponse(mime, null, in);
            Map<String, String> out = new HashMap<>();
            out.put("Access-Control-Allow-Origin", "*");
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            out.put("Accept-Ranges", acceptRanges != null && acceptRanges.length() > 0
                    ? acceptRanges : "bytes");
            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange != null && contentRange.length() > 0) out.put("Content-Range", contentRange);
            String contentLength = conn.getHeaderField("Content-Length");
            if (contentLength != null && contentLength.length() > 0) out.put("Content-Length", contentLength);
            String cacheCtl = conn.getHeaderField("Cache-Control");
            if (cacheCtl != null && cacheCtl.length() > 0) out.put("Cache-Control", cacheCtl);
            resp.setResponseHeaders(out);
            return resp;
        } catch (Throwable t) {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            return null;
        }
    }

    private static String readAllText(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        try { in.close(); } catch (Throwable ignored) { }
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String mbEnc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String proxiedUrl(String abs, String ckRaw) {
        String p = "/mbp?u=" + mbEnc(abs);
        if (ckRaw != null && ckRaw.length() > 0) p += "&ck=" + mbEnc(ckRaw);
        return p;
    }

    /** rewrite every playlist line (and URI="..." attributes) to absolute proxied URLs */
    private static String rewritePlaylist(String text, String baseUrl, String ckRaw) {
        try {
            URL base = new URL(baseUrl);
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.length() == 0) {
                    sb.append('\n');
                    continue;
                }
                if (t.charAt(0) == '#') {
                    sb.append(rewriteUriAttrs(t, base, ckRaw)).append('\n');
                    continue;
                }
                if (t.startsWith("data:")) {
                    sb.append(t).append('\n');
                    continue;
                }
                String abs = t;
                try {
                    abs = new URL(base, t).toExternalForm();
                } catch (Throwable ignored) { }
                if (abs.startsWith("http://") || abs.startsWith("https://")) {
                    sb.append(proxiedUrl(abs, ckRaw)).append('\n');
                } else {
                    sb.append(t).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    private static final java.util.regex.Pattern URI_ATTR =
            java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");

    private static String rewriteUriAttrs(String line, URL base, String ckRaw) {
        try {
            java.util.regex.Matcher m = URI_ATTR.matcher(line);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String val = m.group(1);
                String repl = val;
                if (!val.startsWith("data:")) {
                    try {
                        String abs = new URL(base, val).toExternalForm();
                        if (abs.startsWith("http://") || abs.startsWith("https://")) {
                            repl = "http://localhost" + proxiedUrl(abs, ckRaw);
                        }
                    } catch (Throwable ignored) { }
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("URI=\"" + repl + "\""));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return line;
        }
    }

    private static WebResourceResponse notFoundResponse() {
        try {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                    new ByteArrayInputStream(new byte[0]));
        } catch (Throwable unused) {
            return emptyResponse();
        }
    }

    /** profile selection from native panel */
    private void onProfileSelected(String name) {
        currentProfile = name;
        try {
            getSharedPreferences(PREFS, 0).edit().putString(PREF_PROFILE, name).apply();
        } catch (Throwable ignored) { }
        if (profilePanel != null) profilePanel.setVisibility(8);
        if (mainWebView != null) {
            try { mainWebView.onResume(); } catch (Throwable ignored) { }
            mainWebView.setVisibility(0);
            applyProfileToPage();
        }
    }

    private void playIntro() {
        hideSystemBars();
        try {
            File f = new File(getCacheDir(), "nf-intro.mp4");
            if (!f.exists() || f.length() == 0) {
                InputStream in = getAssets().open("nf-intro.mp4");
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                try { in.close(); } catch (Throwable ignored) { }
                try { out.close(); } catch (Throwable ignored) { }
            }
            introVideo.setVideoPath(f.getAbsolutePath());
            introVideo.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { finishIntro(false); }
            });
            introVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    finishIntro(false);
                    return true;
                }
            });
            introVideo.start();
        } catch (Throwable unused) {
            finishIntro(false);
        }
    }

    /** GET proxy used by the site for whitelisted hosts (TMDB/IMDb/images) */
    private WebResourceResponse proxyPassthrough(Uri uri) {
        HttpURLConnection conn = null;
        try {
            String u = uri.getQueryParameter("u");
            if (u == null || u.length() == 0) return null;
            u = u.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
            URL url = new URL(u);
            if (!isProxyHost(url.getHost() == null ? "" : url.getHost().toLowerCase())) return null;
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String contentType = conn.getContentType();
            String mime = "application/octet-stream";
            String charset = null;
            if (contentType != null && contentType.length() > 0) {
                int semi = contentType.indexOf(';');
                mime = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
                if (semi >= 0) {
                    String tail = contentType.substring(semi + 1).trim();
                    if (tail.toLowerCase().startsWith("charset=")) charset = tail.substring(8).trim();
                }
            }
            if (mime.startsWith("image/")) charset = null;
            InputStream in = conn.getInputStream();
            byte[] body = readAll(in);
            try { in.close(); } catch (Throwable ignored) { }
            WebResourceResponse resp = new WebResourceResponse(mime, charset,
                    new ByteArrayInputStream(body));
            return resp;
        } catch (Throwable unused) {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** player-host iframe documents: proxy + inject SPOOF_JS (unchanged behavior) */
    private WebResourceResponse proxyAndInject(WebResourceRequest req) {
        HttpURLConnection conn = null;
        try {
            Uri uri = req.getUrl();
            conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            keepCookies(conn, uri);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String contentType = conn.getContentType();
            String mime = "text/html";
            String charset = "utf-8";
            if (contentType != null && contentType.length() > 0) {
                int semi = contentType.indexOf(';');
                mime = (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase();
                if (semi >= 0) {
                    String tail = contentType.substring(semi + 1).trim();
                    if (tail.toLowerCase().startsWith("charset=")) charset = tail.substring(8).trim();
                }
            }
            if (!mime.contains("html")) {
                InputStream in = conn.getInputStream();
                byte[] raw = readAll(in);
                try { in.close(); } catch (Throwable ignored) { }
                return new WebResourceResponse(mime, charset, new ByteArrayInputStream(raw));
            }
            InputStream in = conn.getInputStream();
            byte[] body = readAll(in);
            try { in.close(); } catch (Throwable ignored) { }
            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(injectIntoHtml(body)));
        } catch (Throwable unused) {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            total += n;
            if (total > 6291456) break;
        }
        return bo.toByteArray();
    }

    private void scheduleTrapBlank(final WebView wv) {
        trapHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try { wv.loadUrl("about:blank"); } catch (Throwable ignored) { }
            }
        }, 15000L);
    }

    /** serve bundled web app from assets/www */
    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.length() == 0 || path.equals("/")) path = "/index.html";
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Throwable ignored) { }
        if (path.contains("..")) return notFoundResponse();
        try {
            InputStream in = getAssets().open("www" + path);
            String mime = guessMime(path);
            String charset = "utf-8";
            if ("text/html".equals(mime)) {
                byte[] injected = injectIntoHtml(readAll(in));
                try { in.close(); } catch (Throwable ignored) { }
                return new WebResourceResponse(mime, "utf-8", new ByteArrayInputStream(injected));
            }
            if (!mime.startsWith("text/") && !mime.contains("javascript")
                    && !mime.contains("json") && !mime.contains("svg")) {
                charset = null;
            }
            return new WebResourceResponse(mime, charset, in);
        } catch (IOException unused) {
            return notFoundResponse();
        }
    }

    private void showProfilesPanel() {
        if (profilePanel == null) return;
        if (mainWebView != null) {
            try { mainWebView.onPause(); } catch (Throwable ignored) { }
        }
        profilePanel.setVisibility(0);
    }

    private void showSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(256);
        } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT < 30) return;
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen();
            return;
        }
        if (introPanel != null && introPanel.getVisibility() == 0) {
            finishIntro(false);
            return;
        }
        if (profilePanel != null && profilePanel.getVisibility() == 0) {
            finish();
            return;
        }
        try {
            mainWebView.evaluateJavascript("window.__NF_onBack?String(window.__NF_onBack()):'0'",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (v == null || !v.contains("true")) goBackOrExit();
                        }
                    });
        } catch (Throwable unused) {
            goBackOrExit();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentProfile = getSharedPreferences(PREFS, 0).getString(PREF_PROFILE, PROFILE_NAMES[0]);
        try { setVolumeControlStream(3); } catch (Throwable ignored) { }
        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        root.setBackgroundColor(-16777216);
        setContentView(root);
        WebView wv = new WebView(this);
        mainWebView = wv;
        wv.setBackgroundColor(-16777216);
        mainWebView.setVisibility(8);
        root.addView(mainWebView, new FrameLayout.LayoutParams(-1, -1));
        configureSettings(mainWebView.getSettings());
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        try { cookies.setAcceptThirdPartyCookies(mainWebView, true); } catch (Throwable ignored) { }

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageLoadedOnce = true;
                applyProfileToPage();
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(req);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return blockOrAllow(req.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockOrAllow(Uri.parse(url));
            }
        });

        mainWebView.setWebChromeClient(new WebChromeClient() {
            @Override public void onCloseWindow(WebView w) {
                popupTraps.remove(w);
                destroyTrap(w);
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                                                    boolean isUserGesture, Message msg) {
                try {
                    ((WebView.WebViewTransport) msg.obj).setWebView(createTrap());
                    msg.sendToTarget();
                    return true;
                } catch (Throwable unused) {
                    return false;
                }
            }

            @Override public void onHideCustomView() {
                exitFullscreen();
            }

            @Override public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
                if (fullscreenView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = cb;
                mainWebView.setVisibility(8);
                rootLayout.addView(view, new FrameLayout.LayoutParams(-1, -1));
                enterFullscreenMode();
            }
        });

        try {
            mainWebView.addJavascriptInterface(new Bridge(), "NFApp");
        } catch (Throwable ignored) { }

        buildProfilePanel();
        buildIntroPanel();
        mainWebView.loadUrl(START_URL);
    }

    @Override
    protected void onDestroy() {
        if (mainWebView != null) {
            try { mainWebView.stopLoading(); } catch (Throwable ignored) { }
            try { mainWebView.destroy(); } catch (Throwable ignored) { }
            mainWebView = null;
        }
        for (WebView w : new ArrayList<>(popupTraps)) destroyTrap(w);
        popupTraps.clear();
        super.onDestroy();
    }
}
