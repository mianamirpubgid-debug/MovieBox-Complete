package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fullscreen player v2 — landscape, immersive (notification panel hidden),
 * custom controls: seek bar with times, play/pause, ±10s, auto-hide.
 */
public class PlayerActivity extends Activity {

    private static final int ACCENT = Color.parseColor("#E50914");

    private TextureViewHost host;
    private ExoPlayer player;
    private String subjectId, title, directUrl, directCookie, directQuality;
    private long startPos;
    private List<StreamResolver.Candidate> candidates = new ArrayList<StreamResolver.Candidate>();
    private int currentIdx = -1;

    // controls
    private FrameLayout controls;
    private SeekBar seek;
    private TextView tTime, tDur, tTitle;
    private ImageView bPlay, bBack10, bFwd10;
    private FrameLayout buffering;
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideRun = new Runnable() {
        @Override public void run() { setControls(false); }
    };
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        subjectId = getIntent().getStringExtra("subjectId");
        title = getIntent().getStringExtra("title");
        if (title == null || title.length() == 0) title = "MovieBox HD";
        directUrl = getIntent().getStringExtra("url");
        directCookie = getIntent().getStringExtra("cookie");
        directQuality = getIntent().getStringExtra("quality");
        startPos = getIntent().getLongExtra("pos", 0);
        String candRaw = getIntent().getStringExtra("candidates");
        if (candRaw != null) {
            for (String line : candRaw.split("\n")) {
                String[] p = line.split("\\|", -1);
                if (p.length >= 1 && p[0].startsWith("http")) {
                    StreamResolver.Candidate c = new StreamResolver.Candidate(
                            p[0], p.length > 2 ? p[2] : "", p.length > 3 ? p[3] : "",
                            p.length > 1 ? p[1] : "", "", "passed");
                    candidates.add(c);
                }
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ---- video surface ----
        host = new TextureViewHost(this, PlayerActivity.this);
        root.addView(host.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- buffering ----
        buffering = new FrameLayout(this);
        buffering.addView(Anim.spinner(this, ACCENT, 56), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        buffering.setVisibility(View.VISIBLE);
        root.addView(buffering, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildControls(root);
        setContentView(root);

        applyImmersive();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean haveDirect = directUrl != null && directUrl.startsWith("http");
        if (haveDirect && candidates.isEmpty()) {
            candidates.add(new StreamResolver.Candidate(
                    directUrl, directQuality == null ? "" : directQuality, "",
                    directCookie == null ? "" : directCookie, "", "direct"));
        }

        if (!candidates.isEmpty()) {
            playCandidate(0, startPos);
        } else if (subjectId != null && subjectId.length() > 0) {
            resolveFresh();
        } else {
            finish();
        }
    }

    // ---- TextureView wrapper (tag trick for exact view) ----
    static class TextureViewHost {
        final android.view.TextureView view;
        TextureViewHost(Activity a, final PlayerActivity outer) {
            view = new android.view.TextureView(a);
            view.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { outer.toggleControls(); }
            });
        }
    }

    private void buildControls(FrameLayout root) {
        controls = new FrameLayout(this);
        controls.setClickable(true);
        controls.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleControls(); }
        });

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.parseColor("#66000000"));
        top.setPadding(dp(8), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        controls.addView(top, tlp);

        ImageView close = iconBtn(R.drawable.ic_close, dp(38));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(close);

        tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(Color.WHITE);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tTitle.setPadding(dp(10), 0, dp(10), 0);
        tTitle.setMaxLines(1);
        top.addView(tTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView rotate = iconBtn(R.drawable.ic_fullscreen, dp(38));
        rotate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(PlayerActivity.this, "Phone rotate karein — sensor auto full screen", Toast.LENGTH_SHORT).show();
            }
        });
        top.addView(rotate);

        // bottom bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.parseColor("#88000000"));
        bottom.setPadding(dp(16), dp(6), dp(16), dp(10));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        controls.addView(bottom, blp);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        tTime = timeLabel("0:00");
        seekRow.addView(tTime);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#44FFFFFF")));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.setMargins(dp(10), 0, dp(10), 0);
        seekRow.addView(seek, slp);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    tTime.setText(fmt((long) (p / 1000.0 * player.getDuration())));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo((long) (s.getProgress() / 1000.0 * player.getDuration()));
                    pokeControls();
                }
            }
        });

        tDur = timeLabel("0:00");
        seekRow.addView(tDur);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dp(4), 0, 0);

        bBack10 = iconBtn(R.drawable.ic_back10, dp(46));
        bBack10.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(-10); }
        });
        btnRow.addView(bBack10);

        bPlay = iconBtn(R.drawable.ic_pause_circle, dp(62));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(62), dp(62));
        plp.setMargins(dp(26), 0, dp(26), 0);
        bPlay.setLayoutParams(plp);
        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        btnRow.addView(bPlay);

        bFwd10 = iconBtn(R.drawable.ic_fwd10, dp(46));
        bFwd10.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(10); }
        });
        btnRow.addView(bFwd10);

        bottom.addView(seekRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Anim.fadeIn(controls, 260);
        scheduleHide();
    }

    private TextView timeLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    private ImageView iconBtn(int res, int size) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        Anim.press(iv);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return iv;
    }

    // ================================================== stream
    private void resolveFresh() {
        ApiClient.async(new ApiClient.BgTask<StreamResolver.Result>() {
            @Override public StreamResolver.Result run() {
                return StreamResolver.resolve(subjectId, 0, 0);
            }
        }, new ApiClient.UiTask<StreamResolver.Result>() {
            @Override public void onDone(StreamResolver.Result r) {
                if (r == null || r.candidates.isEmpty()) {
                    Toast.makeText(PlayerActivity.this, "Stream nahi mila", Toast.LENGTH_LONG).show();
                    buffering.setVisibility(View.GONE);
                    return;
                }
                candidates = r.candidates;
                if (r.subject != null && r.subject.optString("title", "").length() > 0) {
                    tTitle.setText(r.subject.optString("title", ""));
                }
                playCandidate(0, startPos);
            }
        });
    }

    private void playCandidate(int idx, long seekPos) {
        if (idx >= candidates.size()) {
            Toast.makeText(this, "Ye video abhi play nahi ho rahi", Toast.LENGTH_SHORT).show();
            buffering.setVisibility(View.GONE);
            return;
        }
        currentIdx = idx;
        StreamResolver.Candidate c = candidates.get(idx);
        buffering.setVisibility(View.VISIBLE);

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
        player.setVideoTextureView(host.view);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    bPlay.setImageResource(R.drawable.ic_pause_circle);
                    if (player.getDuration() > 0) tDur.setText(fmt(player.getDuration()));
                }
                if (state == Player.STATE_ENDED && !finished) {
                    finished = true;
                    finish();
                }
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (!userSeeking) {
                    bPlay.setImageResource(isPlaying ? R.drawable.ic_pause_circle : R.drawable.ic_play_circle);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                playCandidate(currentIdx + 1, 0);
            }
        });
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        if (seekPos > 0) player.seekTo(seekPos);
        player.play();

        // progress ticker
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (player == null || isFinishing()) return;
                long dur = player.getDuration();
                long pos = player.getCurrentPosition();
                if (dur > 0 && !userSeeking) {
                    seek.setProgress((int) (pos * 1000 / dur));
                    tTime.setText(fmt(pos));
                }
                ui.postDelayed(this, 500);
            }
        }, 500);
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) { player.pause(); bPlay.setImageResource(R.drawable.ic_play_circle); }
        else { player.play(); bPlay.setImageResource(R.drawable.ic_pause_circle); }
        pokeControls();
    }

    private void skip(int sec) {
        if (player == null) return;
        player.seekTo(Math.max(0, player.getCurrentPosition() + sec * 1000L));
        pokeControls();
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }

    // ================================================== controls visibility
    private void toggleControls() { setControls(!controlsVisible); }

    private void setControls(boolean show) {
        controlsVisible = show;
        if (show) {
            controls.setVisibility(View.VISIBLE);
            Anim.fadeIn(controls, 200);
            scheduleHide();
        } else {
            controls.animate().alpha(0f).setDuration(220).start();
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!controlsVisible) controls.setVisibility(View.GONE);
                }
            }, 230);
        }
    }

    private void pokeControls() {
        setControls(true);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideRun);
        ui.postDelayed(hideRun, 4200);
    }

    // ================================================== immersive
    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        decor.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) applyImmersive();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) player.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        long pos = player != null ? player.getCurrentPosition() : 0;
        if (player != null) { player.release(); player = null; }
        Intent out = new Intent();
        out.putExtra("pos", pos);
        setResult(RESULT_OK, out);
        super.onDestroy();
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
