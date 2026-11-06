package com.moviebox.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * Animation toolkit — press effects, fade-ins, shimmer skeletons,
 * pulsing loading dots, spinning progress.
 */
public class Anim {

    public interface Run { void go(); }

    // ---------- press scale (professional touch feedback) ----------
    public static void press(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View vv, android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        vv.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        vv.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        break;
                }
                return false; // let click handler still run
            }
        });
    }

    // ---------- fade in on appearance / image load ----------
    public static void fadeIn(View v, long ms) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void fadeIn(View v) { fadeIn(v, 320); }

    /** crossfade container content swap */
    public static void crossfade(final View from, final View to) {
        to.setAlpha(0f);
        to.setVisibility(View.VISIBLE);
        to.animate().alpha(1f).setDuration(300).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (from != null) from.setVisibility(View.GONE);
                to.animate().setListener(null);
            }
        }).start();
    }

    // ---------- skeleton shimmer (alpha pulse, staggered) ----------
    public static void shimmer(View v, long delayMs) {
        if (v == null) return;
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 0.25f, 0.75f);
        a.setDuration(620);
        a.setStartDelay(delayMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        v.setTag(R.id.tag_shimmer, a);
    }

    public static void stopShimmer(View v) {
        Object a = v == null ? null : v.getTag(R.id.tag_shimmer);
        if (a instanceof ObjectAnimator) ((ObjectAnimator) a).cancel();
    }

    public static void stopShimmerAll(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            stopShimmer(c);
            if (c instanceof ViewGroup) stopShimmerAll((ViewGroup) c);
        }
    }

    public static View skeletonBlock(android.content.Context ctx, int w, int h, int radiusDp) {
        View v = new View(ctx);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E222B"));
        gd.setCornerRadius(dp(ctx, radiusDp));
        v.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        int m = dp(ctx, 4);
        lp.setMargins(m, m, m, m);
        v.setLayoutParams(lp);
        shimmer(v, (long) (Math.random() * 350));
        return v;
    }

    // ---------- pulsing loading dots ----------
    public static View loadingDots(android.content.Context ctx, int color) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View dot = new View(ctx);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(color);
            gd.setShape(GradientDrawable.OVAL);
            dot.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10));
            lp.setMargins(dp(ctx, 5), 0, dp(ctx, 5), 0);
            dot.setLayoutParams(lp);
            shimmer(dot, i * 180L);
            row.addView(dot);
        }
        return row;
    }

    // ---------- circular spinner ----------
    public static ProgressBar spinner(android.content.Context ctx, int color, int sizeDp) {
        ProgressBar pb = new ProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp));
        pb.setLayoutParams(lp);
        pb.setIndeterminate(true);
        try {
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color));
        } catch (Exception ignored) {
        }
        return pb;
    }

    @SuppressWarnings("deprecation")
    private static android.graphics.drawable.Drawable tint(android.graphics.drawable.Drawable d, int color) {
        android.graphics.drawable.Drawable mut = d.mutate();
        mut.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP);
        return mut;
    }

    // ---------- banner auto-scroll helper ----------
    public static ValueAnimator autoScroll(final android.widget.HorizontalScrollView hs, final int pageWidth, long intervalMs) {
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(intervalMs);
        anim.setInterpolator(new LinearInterpolator());
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(Animator a) {
                int max = hs.getChildAt(0).getWidth() - hs.getWidth();
                if (max <= 0) return;
                int cur = hs.getScrollX();
                int next = cur + pageWidth;
                if (next >= max) next = 0;
                hs.smoothScrollTo(next, 0);
            }
        });
        anim.start();
        return anim;
    }

    public static int dp(android.content.Context ctx, int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
