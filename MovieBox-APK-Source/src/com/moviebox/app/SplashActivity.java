package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Splash — animated logo + background home prefetch (from cache instantly,
 * refresh from network) → crossfade into Home. Professional warm start.
 */
public class SplashActivity extends Activity {

    private static final long MIN_SPLASH_MS = 950;
    private long startedAt = 0;
    private boolean launched = false;
    private ApiClient.HomeData homeData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CacheManager.init(this);
        startedAt = System.currentTimeMillis();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0C10"));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(center, clp);

        // ---- logo: red rounded square + play triangle ----
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER);
        int logoSize = Anim.dp(this, 92);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        center.addView(logo, llp);

        AnimationSet anim = new AnimationSet(true);
        ScaleAnimation scale = new ScaleAnimation(0.5f, 1f, 0.5f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(700);
        scale.setInterpolator(new DecelerateInterpolator(1.6f));
        AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
        alpha.setDuration(550);
        anim.addAnimation(scale);
        anim.addAnimation(alpha);
        logo.startAnimation(anim);

        // ---- app name ----
        TextView name = new TextView(this);
        name.setText("MovieBox HD");
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, Anim.dp(this, 18), 0, 0);
        center.addView(name);
        AlphaAnimation na = new AlphaAnimation(0f, 1f);
        na.setStartOffset(320);
        na.setDuration(520);
        name.startAnimation(na);

        // ---- tagline ----
        TextView tag = new TextView(this);
        tag.setText("Movies • Web Series • Hindi Dubbed");
        tag.setTextColor(Color.parseColor("#8A9099"));
        tag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tag.setPadding(0, Anim.dp(this, 6), 0, 0);
        center.addView(tag);
        AlphaAnimation ta = new AlphaAnimation(0f, 1f);
        ta.setStartOffset(520);
        ta.setDuration(520);
        tag.startAnimation(ta);

        // ---- loading dots ----
        LinearLayout dotsWrap = new LinearLayout(this);
        dotsWrap.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = Anim.dp(this, 64);
        dotsWrap.addView(Anim.loadingDots(this, Color.parseColor("#E50914")));
        root.addView(dotsWrap, dlp);

        setContentView(root);

        // ---- background prefetch: cached-first then network refresh ----
        ApiClient.async(new ApiClient.BgTask<ApiClient.HomeData>() {
            @Override
            public ApiClient.HomeData run() {
                return ApiClient.loadHome();
            }
        }, new ApiClient.UiTask<ApiClient.HomeData>() {
            @Override
            public void onDone(ApiClient.HomeData data) {
                homeData = data;
                goHomeWhenReady();
            }
        });
    }

    private void goHomeWhenReady() {
        long elapsed = System.currentTimeMillis() - startedAt;
        long wait = Math.max(0, MIN_SPLASH_MS - elapsed);
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (launched || isFinishing()) return;
                launched = true;
                Intent it = new Intent(SplashActivity.this, MainActivity.class);
                if (homeData != null && homeData.movies != null) {
                    it.putExtra("hasData", true);
                }
                startActivity(it);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }, wait);
    }
}
