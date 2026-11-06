package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Detail v2 — embedded MINI PLAYER at top (portrait) with fullscreen button,
 * poster/title/meta, dub switch, story, episodes. Professional Netflix layout.
 */
public class DetailActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0C10");
    private static final int SURFACE = Color.parseColor("#14161D");
    private static final int ACCENT = Color.parseColor("#E50914");

    private String subjectId;
    private LinearLayout infoContainer;
    private TextView statusView;

    // ---- mini player ----
    private FrameLayout playerBox;
    private TextureView textureView;
    private ImageView btnPlayPause;
    private ImageView btnFullscreen;
    private FrameLayout buffering;
    private TextView playerTitle;
    private ExoPlayer player;
    private StreamResolver.Result streamResult;
    private int currentCandidate = 0;
    private String currentTitle = "";
    private long resumePos = 0;
    private boolean miniPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CacheManager.init(this);
        subjectId = getIntent().getStringExtra("subjectId");
        if (subjectId == null) subjectId = "";

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(BG);
        infoContainer = new LinearLayout(this);
        infoContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(infoContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // ---------------- mini player box (16:9) ----------------
        playerBox = new FrameLayout(this);
        playerBox.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        playerBox.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // buffering spinner (hidden)
        buffering = new FrameLayout(this);
        buffering.addView(Anim.spinner(this, ACCENT, 46), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        buffering.setVisibility(View.GONE);
        playerBox.addView(buffering, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // fullscreen button (bottom-right)
        btnFullscreen = new ImageView(this);
        btnFullscreen.setImageResource(R.drawable.ic_fullscreen);
        btnFullscreen.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnFullscreen.setBackground(circle(Color.parseColor("#66000000")));
        Anim.press(btnFullscreen);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(40), dp(40));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.setMargins(0, 0, dp(10), dp(10));
        btnFullscreen.setVisibility(View.GONE);
        playerBox.addView(btnFullscreen, flp);
        btnFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFullscreen(); }
        });

        // play/pause (center)
        btnPlayPause = new ImageView(this);
        btnPlayPause.setImageResource(R.drawable.ic_play_circle);
        btnPlayPause.setPadding(dp(6), dp(6), dp(6), dp(6));
        Anim.press(btnPlayPause);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER);
        playerBox.addView(btnPlayPause, plp);
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMiniPlay(); }
        });

        // player title (top-left)
        playerTitle = new TextView(this);
        playerTitle.setTextColor(Color.WHITE);
        playerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        playerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        playerTitle.setBackgroundColor(Color.parseColor("#55000000"));
        playerTitle.setPadding(dp(10), dp(6), dp(10), dp(6));
        playerTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        playerBox.addView(playerTitle, tlp);

        int ph = (int) (getResources().getDisplayMetrics().widthPixels * 9f / 16f);
        infoContainer.addView(playerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ph));

        // loading status
        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9AA0A6"));
        statusView.setText("  Loading…");
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        infoContainer.addView(statusView);

        loadDetail(subjectId);
    }

    private android.graphics.drawable.GradientDrawable circle(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return gd;
    }

    // ================================================== load + render
    private void loadDetail(final String sid) {
        ApiClient.async(new ApiClient.BgTask<JSONObject>() {
            @Override public JSONObject run() {
                try { return ApiClient.getSubject(sid); } catch (Exception e) { return null; }
            }
        }, new ApiClient.UiTask<JSONObject>() {
            @Override public void onDone(JSONObject res) {
                infoContainer.removeView(statusView);
                if (res == null || res.optInt("code", -1) != 0 || res.optJSONObject("data") == null) {
                    TextView err = new TextView(DetailActivity.this);
                    err.setText("Movie load nahi hui. Internet check karein.");
                    err.setTextColor(Color.parseColor("#9AA0A6"));
                    err.setPadding(dp(16), dp(16), dp(16), dp(16));
                    infoContainer.addView(err);
                    return;
                }
                JSONObject s = res.optJSONObject("data");
                // offline save
                try { CacheManager.apiPut("detail_" + sid, s.toString()); } catch (Exception ignored) {}
                renderDetail(s);
            }
        });
    }

    private void renderDetail(JSONObject s) {
        String title = s.optString("title", "");
        currentTitle = title;
        playerTitle.setText(title);
        playerTitle.setVisibility(View.VISIBLE);
        Anim.fadeIn(playerTitle, 250);

        String desc = s.optString("description", "");
        String year = s.optString("releaseDate", "");
        if (year.length() > 4) year = year.substring(0, 4);
        String genre = s.optString("genre", "");
        String country = s.optString("countryName", "");
        String imdb = s.optString("imdbRatingValue", "");
        int subjectType = s.optInt("subjectType", 1);
        boolean isSeries = subjectType == 2 || s.optInt("seNum", 0) > 0;

        // ---- poster + info ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(8));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(SURFACE);
        top.addView(poster, new LinearLayout.LayoutParams(dp(124), (int) (dp(124) * 1.42f)));
        ImageLoader.load(ApiClient.coverUrl(s), poster);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(14), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        meta.addView(tvTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StringBuilder sb = new StringBuilder();
        if (year.length() > 0) sb.append(year);
        if (genre.length() > 0) sb.append(sb.length() > 0 ? " • " : "").append(genre);
        if (country.length() > 0) sb.append(sb.length() > 0 ? " • " : "").append(country);
        if (imdb.length() > 0) sb.append(sb.length() > 0 ? " • " : "").append("⭐ ").append(imdb);
        TextView tvMeta = new TextView(this);
        tvMeta.setText(sb.toString());
        tvMeta.setTextColor(Color.parseColor("#9AA0A6"));
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvMeta.setPadding(0, dp(6), 0, 0);
        meta.addView(tvMeta);

        TextView tvType = new TextView(this);
        tvType.setText(isSeries ? "Web Series / TV" : "Movie");
        tvType.setTextColor(ACCENT);
        tvType.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvType.setPadding(0, dp(6), 0, 0);
        tvType.setTypeface(null, android.graphics.Typeface.BOLD);
        meta.addView(tvType);

        // ---- big play button ----
        TextView playBtn = new TextView(this);
        playBtn.setText(isSeries ? "▶  Play Series" : "▶  Play Movie");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        playBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackgroundColor(ACCENT);
        Anim.press(playBtn);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        pb.setMargins(0, dp(10), 0, 0);
        meta.addView(playBtn, pb);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (player != null && player.isPlaying()) {
                    openFullscreen();
                } else {
                    toggleMiniPlay();
                }
            }
        });

        top.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Anim.fadeIn(top, 300);
        infoContainer.addView(top);

        // ---- dubs ----
        JSONArray dubs = s.optJSONArray("dubs");
        if (dubs != null && dubs.length() > 1) {
            TextView dt = sectionTitle("Audio / Language Versions");
            infoContainer.addView(dt);
            HorizontalScrollView dhs = new HorizontalScrollView(this);
            LinearLayout drow = new LinearLayout(this);
            drow.setOrientation(LinearLayout.HORIZONTAL);
            drow.setPadding(dp(14), dp(4), dp(14), dp(4));
            for (int i = 0; i < dubs.length(); i++) {
                JSONObject d = dubs.optJSONObject(i);
                if (d == null) continue;
                final String dubSid = d.optString("subjectId", "");
                String lan = d.optString("lanName", d.optString("lanCode", "Audio"));
                if (dubSid.isEmpty() || dubSid.equals(subjectId)) continue;
                TextView btn = chip(lan);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { loadDetail(dubSid); }
                });
                drow.addView(btn);
            }
            dhs.addView(drow);
            infoContainer.addView(dhs);
        }

        // ---- description ----
        if (desc.length() > 0) {
            infoContainer.addView(sectionTitle("Story"));
            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(Color.parseColor("#BBBBBB"));
            dv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            dv.setLineSpacing(dp(2), 1f);
            dv.setPadding(dp(16), dp(4), dp(16), dp(12));
            Anim.fadeIn(dv, 300);
            infoContainer.addView(dv);
        }

        if (isSeries) renderEpisodes(s);
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(16), dp(14), dp(16), dp(4));
        return t;
    }

    private TextView chip(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#23272F"));
        gd.setCornerRadius(dp(18));
        btn.setBackground(gd);
        btn.setPadding(dp(16), dp(9), dp(16), dp(9));
        Anim.press(btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void renderEpisodes(JSONObject s) {
        java.util.List<Long> eps = new java.util.ArrayList<Long>();
        JSONArray detectors = s.optJSONArray("resourceDetectors");
        if (detectors != null) {
            for (int i = 0; i < detectors.length(); i++) {
                JSONObject det = detectors.optJSONObject(i);
                JSONArray rl = det == null ? null : det.optJSONArray("resolutionList");
                if (rl == null) continue;
                for (int j = 0; j < rl.length(); j++) {
                    JSONObject r = rl.optJSONObject(j);
                    long e = r == null ? 0 : r.optLong("episode", 0);
                    if (e > 0 && !eps.contains(e)) eps.add(e);
                }
            }
        }

        infoContainer.addView(sectionTitle(eps.isEmpty() ? "Episodes" : "Episodes (" + eps.size() + ")"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(4), dp(14), dp(12));
        if (eps.isEmpty()) {
            for (long i = 1; i <= 12; i++) addEpisodeBtn(row, i);
        } else {
            java.util.Collections.sort(eps);
            for (long e : eps) addEpisodeBtn(row, e);
        }
        hs.addView(row);
        infoContainer.addView(hs);
    }

    private void addEpisodeBtn(LinearLayout row, final long ep) {
        TextView btn = new TextView(this);
        btn.setText(String.valueOf(ep));
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#23272F"));
        gd.setCornerRadius(dp(8));
        btn.setBackground(gd);
        Anim.press(btn);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                resumePos = 0;
                startMiniPlayer((int) ep);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(42));
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(btn, lp);
    }

    // ================================================== mini player
    private void toggleMiniPlay() {
        if (player != null) {
            if (player.isPlaying()) {
                player.pause();
                btnPlayPause.setImageResource(R.drawable.ic_play_circle);
            } else {
                player.play();
                btnPlayPause.setImageResource(R.drawable.ic_pause_circle);
            }
            return;
        }
        startMiniPlayer(0);
    }

    private void startMiniPlayer(final int ep) {
        btnPlayPause.setVisibility(View.INVISIBLE);
        buffering.setVisibility(View.VISIBLE);
        streamResult = null;
        ApiClient.async(new ApiClient.BgTask<StreamResolver.Result>() {
            @Override public StreamResolver.Result run() {
                return StreamResolver.resolve(subjectId, ep > 0 ? 1 : 0, ep);
            }
        }, new ApiClient.UiTask<StreamResolver.Result>() {
            @Override public void onDone(StreamResolver.Result r) {
                buffering.setVisibility(View.GONE);
                if (r == null || r.candidates.isEmpty()) {
                    btnPlayPause.setVisibility(View.VISIBLE);
                    Toast.makeText(DetailActivity.this, "Stream nahi mila — thodi der baad try karein", Toast.LENGTH_LONG).show();
                    return;
                }
                streamResult = r;
                currentCandidate = 0;
                if (r.subject != null && r.subject.optString("title", "").length() > 0) {
                    currentTitle = r.subject.optString("title", "");
                    playerTitle.setText(currentTitle);
                }
                playCandidate(0, resumePos);
            }
        });
    }

    private void playCandidate(int idx, long startPos) {
        if (streamResult == null || idx >= streamResult.candidates.size()) {
            btnPlayPause.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Ye video abhi play nahi ho rahi", Toast.LENGTH_SHORT).show();
            return;
        }
        currentCandidate = idx;
        StreamResolver.Candidate c = streamResult.candidates.get(idx);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", ApiClient.UA);
        if (c.cookie.length() > 0) headers.put("Cookie", c.cookie);

        if (player != null) { player.release(); player = null; }
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(httpFactory);
        player = new ExoPlayer.Builder(this, msf).build();
        player.setVideoTextureView(textureView);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_circle);
                    btnPlayPause.setVisibility(View.VISIBLE);
                    btnFullscreen.setVisibility(View.VISIBLE);
                    Anim.fadeIn(btnFullscreen, 200);
                }
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                miniPlaying = isPlaying;
                btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_circle : R.drawable.ic_play_circle);
            }
            @Override public void onPlayerError(PlaybackException error) {
                playCandidate(currentCandidate + 1, 0);
            }
        });
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        if (startPos > 0) player.seekTo(startPos);
        player.play();
    }

    private void openFullscreen() {
        long pos = player != null ? player.getCurrentPosition() : 0;
        if (player != null) { player.pause(); }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("subjectId", subjectId);
        it.putExtra("title", currentTitle);
        it.putExtra("pos", pos);
        if (streamResult != null) {
            StreamResolver.Candidate c = streamResult.candidates.get(currentCandidate);
            it.putExtra("url", c.url);
            it.putExtra("cookie", c.cookie);
            it.putExtra("quality", c.quality);
            // pre-resolved candidates so fullscreen starts instantly
            StringBuilder urls = new StringBuilder();
            for (int i = 0; i < streamResult.candidates.size(); i++) {
                StreamResolver.Candidate cc = streamResult.candidates.get(i);
                if (i > 0) urls.append("\n");
                urls.append(cc.url).append("|").append(cc.cookie).append("|").append(cc.quality).append("|").append(cc.codec);
            }
            it.putExtra("candidates", urls.toString());
        }
        startActivityForResult(it, 77);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 77 && data != null) {
            resumePos = data.getLongExtra("pos", 0);
            if (player != null && resumePos > 0) player.seekTo(resumePos);
            if (player != null) player.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
