package com.moviebox.app;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fullscreen player — landscape sensor auto-rotate, immersive mode
 * (status bar + navigation bar hidden => notification panel hidden).
 * Tries candidates in priority order; switches on error.
 */
public class PlayerActivity extends Activity {

    private VideoView videoView;
    private MediaController controller;
    private List<StreamResolver.Candidate> candidates;
    private int currentIdx = -1;
    private String subjectId;
    private int se, ep;
    private TextView statusOverlay;
    private TextView titleOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        subjectId = getIntent().getStringExtra("subjectId");
        se = getIntent().getIntExtra("se", 0);
        ep = getIntent().getIntExtra("ep", 0);
        String title = getIntent().getStringExtra("title");
        if (title == null) title = "MovieBox HD";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top overlay: back button + title ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(10), dp(10));
        top.setBackgroundColor(Color.parseColor("#66000000"));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(close);

        titleOverlay = new TextView(this);
        titleOverlay.setText(title);
        titleOverlay.setTextColor(Color.WHITE);
        titleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleOverlay.setPadding(dp(8), 0, dp(8), 0);
        top.addView(titleOverlay, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(top, tlp);

        // ---- center status ----
        statusOverlay = new TextView(this);
        statusOverlay.setText("Loading stream…");
        statusOverlay.setTextColor(Color.WHITE);
        statusOverlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusOverlay.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER;
        root.addView(statusOverlay, slp);

        setContentView(root);

        // ---- immersive fullscreen (hide status bar / notification panel + nav bar) ----
        applyImmersive();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        resolveAndPlay();
    }

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
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    applyImmersive();
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void resolveAndPlay() {
        statusOverlay.setText("Loading stream…");
        ApiClient.async(new ApiClient.BgTask<StreamResolver.Result>() {
            @Override
            public StreamResolver.Result run() {
                return StreamResolver.resolve(subjectId, se, ep);
            }
        }, new ApiClient.UiTask<StreamResolver.Result>() {
            @Override
            public void onDone(StreamResolver.Result result) {
                if (result == null || result.candidates.isEmpty()) {
                    statusOverlay.setText("Stream nahi mila — thodi der baad try karein.\n" + (result != null ? result.error : ""));
                    Toast.makeText(PlayerActivity.this, "No playable stream found", Toast.LENGTH_LONG).show();
                    return;
                }
                candidates = result.candidates;
                if (titleOverlay.getText() == null || titleOverlay.getText().length() == 0 || titleOverlay.getText().equals("MovieBox HD")) {
                    titleOverlay.setText(result.subject.optString("title", "MovieBox HD"));
                }
                playCandidate(0);
            }
        });
    }

    private void playCandidate(final int idx) {
        if (candidates == null || idx >= candidates.size()) {
            statusOverlay.setText("Ye movie abhi play nahi ho rahi. Dobara try karein.");
            return;
        }
        currentIdx = idx;
        StreamResolver.Candidate c = candidates.get(idx);
        statusOverlay.setText("Playing " + c.quality + "…");

        try {
            Map<String, String> headers = new HashMap<>();
            if (c.cookie.length() > 0) headers.put("Cookie", c.cookie);
            if (videoView.isPlaying()) videoView.stopPlayback();
            videoView.setVideoURI(Uri.parse(c.url), headers.isEmpty() ? null : headers);
        } catch (Exception e) {
            playCandidate(idx + 1);
            return;
        }

        if (controller == null) {
            controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (controller != null) {
                        if (controller.isShowing()) controller.hide();
                        else controller.show();
                    }
                    return false;
                }
            });
        }

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                statusOverlay.setVisibility(View.INVISIBLE);
                mp.start();
            }
        });

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                // try next candidate
                videoView.post(new Runnable() {
                    @Override
                    public void run() {
                        playCandidate(currentIdx + 1);
                    }
                });
                return true;
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                finish();
            }
        });

        videoView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!videoView.isPlaying() && statusOverlay.getVisibility() == View.VISIBLE) {
                    // stuck loading → try next candidate
                    if (candidates != null && currentIdx + 1 < candidates.size()) {
                        playCandidate(currentIdx + 1);
                    }
                }
            }
        }, 15000);

        videoView.requestFocus();
        videoView.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }

    @Override
    protected void onDestroy() {
        if (videoView != null) videoView.stopPlayback();
        super.onDestroy();
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
