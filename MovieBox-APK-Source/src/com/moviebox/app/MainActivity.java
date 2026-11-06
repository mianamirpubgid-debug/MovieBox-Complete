package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home v2 — Netflix-style: banner carousel (auto-scroll + dots), poster rows,
 * skeleton shimmer loading, bottom navigation, press animations.
 */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0C10");
    private static final int SURFACE = Color.parseColor("#14161D");
    private static final int ACCENT = Color.parseColor("#E50914");
    private static final int TEXT_SUB = Color.parseColor("#9AA0A6");

    /** handed over from Splash prefetch */
    public static ApiClient.HomeData PREFETCH = null;

    private LinearLayout sectionsContainer;
    private FrameLayout contentFrame;
    private TextView statusView;
    static class NavTab { LinearLayout item; ImageView icon; TextView label; }
    private NavTab tabHome, tabMovies, tabSeries, tabSearch;
    private String currentTab = "home";
    private ApiClient.HomeData data;
    private ValueAnimatorRef bannerAnim = new ValueAnimatorRef();
    private Handler ui = new Handler(Looper.getMainLooper());

    static class ValueAnimatorRef { android.animation.ValueAnimator a; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CacheManager.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        header.setBackgroundColor(Color.parseColor("#101218"));

        TextView logo = new TextView(this);
        logo.setText("MovieBox HD");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        logo.setTypeface(null, Typeface.BOLD);
        header.addView(logo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView searchBtn = new ImageView(this);
        searchBtn.setImageResource(R.drawable.ic_search);
        searchBtn.setPadding(dp(8), dp(8), dp(4), dp(8));
        Anim.press(searchBtn);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab("search"); }
        });
        header.addView(searchBtn);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- content frame ----
        contentFrame = new FrameLayout(this);
        contentFrame.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        sectionsContainer = new LinearLayout(this);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(sectionsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(TEXT_SUB);
        statusView.setPadding(dp(16), dp(20), dp(16), dp(8));
        statusView.setVisibility(View.GONE);
        contentFrame.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- bottom navigation ----
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        setContentView(root);

        // data from splash (instant open!)
        data = PREFETCH;
        if (data == null) data = new ApiClient.HomeData();
        switchTab("home");
    }

    // ================================================== bottom nav
    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Color.parseColor("#101218"));
        int pad = dp(6);
        nav.setPadding(0, pad, 0, pad);

        tabHome = navItem(nav, R.drawable.ic_home, "Home");
        tabMovies = navItem(nav, R.drawable.ic_movies, "Movies");
        tabSeries = navItem(nav, R.drawable.ic_series, "Series");
        tabSearch = navItem(nav, R.drawable.ic_search, "Search");
        highlightNav("home");
        return nav;
    }

    private NavTab navItem(LinearLayout parent, int iconRes, String label) {
        NavTab t = new NavTab();
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        Anim.press(item);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setPadding(dp(6), dp(2), dp(6), dp(0));
        t.icon = iv;
        item.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        tv.setPadding(0, dp(2), 0, 0);
        t.label = tv;
        item.addView(tv);

        t.item = item;
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String which = (v == tabHome.item) ? "home" : (v == tabMovies.item) ? "movies"
                        : (v == tabSeries.item) ? "series" : "search";
                switchTab(which);
            }
        });

        parent.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return t;
    }

    private void highlightNav(String tab) {
        if (tabHome == null) return;
        setNav(tabHome.icon, tabHome.label, tab.equals("home"));
        setNav(tabMovies.icon, tabMovies.label, tab.equals("movies"));
        setNav(tabSeries.icon, tabSeries.label, tab.equals("series"));
        setNav(tabSearch.icon, tabSearch.label, tab.equals("search"));
    }

    private void setNav(ImageView icon, TextView label, boolean active) {
        if (icon != null) icon.setColorFilter(active ? ACCENT : Color.parseColor("#6B7280"));
        if (label != null) label.setTextColor(active ? ACCENT : Color.parseColor("#6B7280"));
    }

    private void switchTab(String tab) {
        currentTab = tab;
        highlightNav(tab);
        if (tab.equals("search")) {
            startActivity(new Intent(this, SearchActivity.class));
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out);
            highlightNav("home");
            currentTab = "home";
            return;
        }
        sectionsContainer.removeAllViews();
        Anim.stopShimmerAll(sectionsContainer);
        if (data.movies != null || data.series != null) {
            render(tab);
            Anim.fadeIn(sectionsContainer, 260);
        } else {
            showSkeleton();
            reload(tab);
        }
    }

    private void reload(final String tab) {
        statusView.setVisibility(View.GONE);
        ApiClient.async(new ApiClient.BgTask<ApiClient.HomeData>() {
            @Override public ApiClient.HomeData run() { return ApiClient.loadHome(); }
        }, new ApiClient.UiTask<ApiClient.HomeData>() {
            @Override public void onDone(ApiClient.HomeData d) {
                if (d == null || (d.movies == null && d.series == null)) {
                    showNetworkError();
                    return;
                }
                data = d;
                if (currentTab.equals(tab)) {
                    Anim.stopShimmerAll(sectionsContainer);
                    sectionsContainer.removeAllViews();
                    render(tab);
                    Anim.fadeIn(sectionsContainer, 300);
                }
            }
        });
    }

    // ================================================== render
    private void render(String tab) {
        boolean wantBanner = tab.equals("home");
        boolean renderMovies = tab.equals("home") || tab.equals("movies");
        boolean renderSeries = tab.equals("home") || tab.equals("series");

        boolean any = false;
        if (renderMovies && data.movies != null) any |= renderTab(data.movies, wantBanner, false);
        if (renderSeries && data.series != null) any |= renderTab(data.series, false, tab.equals("series"));

        if (!any) showNetworkError();
        else statusView.setVisibility(View.GONE);
    }

    private boolean renderTab(JSONObject tabRes, boolean withBanner, boolean firstOnly) {
        try {
            JSONArray items = tabRes.getJSONObject("data").getJSONArray("items");
            boolean rendered = false;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type", "");
                if (type.equals("BANNER") && withBanner) {
                    renderBanner(item);
                    rendered = true;
                } else if (type.equals("SUBJECTS_MOVIE")) {
                    renderSection(item.optString("title", "Movies"), item.optJSONArray("subjects"));
                    rendered = true;
                    if (firstOnly && rendered) break;
                } else if (type.equals("RANKING_LIST_MULTI_TAB")) {
                    JSONArray rankings = item.optJSONArray("rankings");
                    if (rankings != null && rankings.length() > 0) {
                        JSONObject r0 = rankings.optJSONObject(0);
                        if (r0 != null) {
                            renderSection(r0.optString("title", "Trending"), r0.optJSONArray("subjects"));
                            rendered = true;
                        }
                    }
                }
            }
            return rendered;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================== banner carousel
    private void renderBanner(JSONObject item) {
        try {
            JSONObject banner = item.optJSONObject("banner");
            if (banner == null) return;
            JSONArray banners = banner.optJSONArray("banners");
            if (banners == null || banners.length() == 0) return;

            final HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFillViewport(true);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            hs.addView(row);
            Anim.fadeIn(hs, 320);

            int w = getResources().getDisplayMetrics().widthPixels - dp(32);
            for (int i = 0; i < Math.min(banners.length(), 8); i++) {
                JSONObject b = banners.optJSONObject(i);
                if (b == null) continue;
                JSONObject img = b.optJSONObject("image");
                String url = img == null ? b.optString("image", "") : img.optString("url", "");
                if (url.isEmpty()) continue;
                String sid = b.optString("subjectId", "");
                if (sid.isEmpty()) {
                    String deep = b.optString("deepLink", "");
                    if (deep.contains("subjectId=")) {
                        sid = deep.substring(deep.indexOf("subjectId=") + 10);
                        int amp = sid.indexOf('&');
                        if (amp > 0) sid = sid.substring(0, amp);
                    }
                }
                final String subjectId = sid;

                FrameLayout card = new FrameLayout(this);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(SURFACE);
                card.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ImageLoader.load(url, iv);

                // title overlay at bottom
                TextView t = new TextView(this);
                String bTitle = b.optString("title", "");
                if (bTitle.isEmpty()) {
                    JSONObject bb = b.optJSONObject("banner");
                    if (bb != null) bTitle = bb.optString("title", "");
                }
                t.setText(bTitle);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                t.setTypeface(null, Typeface.BOLD);
                t.setBackgroundColor(Color.parseColor("#88000000"));
                t.setPadding(dp(12), dp(6), dp(12), dp(6));
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.gravity = Gravity.BOTTOM | Gravity.START;
                card.addView(t, tlp);

                if (!subjectId.isEmpty()) {
                    Anim.press(card);
                    card.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openDetail(subjectId); }
                    });
                }
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, (int) (w * 0.46f));
                clp.setMargins(dp(8), dp(8), dp(8), dp(8));
                row.addView(card, clp);
            }
            sectionsContainer.addView(hs);

            // auto-scroll carousel every 3.8s
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        android.animation.ValueAnimator a = Anim.autoScroll(hs, getResources().getDisplayMetrics().widthPixels - dp(32) + dp(16), 3800);
                        bannerAnim.a = a;
                    } catch (Exception ignored) {}
                }
            }, 1200);
        } catch (Exception ignored) {
        }
    }

    // ================================================== poster row
    private void renderSection(String title, JSONArray subjects) {
        if (subjects == null || subjects.length() == 0) return;
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(18), dp(12), dp(16), dp(2));
        Anim.fadeIn(tv, 260);
        sectionsContainer.addView(tv);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        hs.addView(row);

        int pw = dp(112);
        int ph = (int) (pw * 1.42f);
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject s = subjects.optJSONObject(i);
            if (s == null) continue;
            JSONObject info = ApiClient.infoOf(s);
            String sid = info.optString("subjectId", "");
            if (sid.isEmpty()) continue;
            final String fSid = sid;
            final String fTitle = info.optString("title", "");

            FrameLayout fl = new FrameLayout(this);
            fl.setElevation(dp(2));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(SURFACE);
            fl.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageLoader.load(ApiClient.coverUrl(info), iv);

            // rounded corners
            android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
            clip.setColor(Color.TRANSPARENT);
            clip.setCornerRadius(dp(10));
            try {
                fl.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                    }
                });
                fl.setClipToOutline(true);
            } catch (Exception ignored) {}

            JSONObject ops = info.optJSONObject("ops");
            String badge = ops == null ? info.optString("corner", "") : ops.optString("cornerBadge", "");
            if (badge != null && badge.length() > 0) {
                TextView bt = new TextView(this);
                bt.setText(badge);
                bt.setTextColor(Color.WHITE);
                bt.setBackgroundColor(Color.parseColor("#CC000000"));
                bt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
                bt.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.gravity = Gravity.TOP | Gravity.START;
                blp.setMargins(dp(5), dp(5), 0, 0);
                fl.addView(bt, blp);
            }

            TextView cap = new TextView(this);
            cap.setText(fTitle);
            cap.setTextColor(Color.WHITE);
            cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            cap.setMaxLines(1);
            cap.setPadding(dp(4), dp(3), dp(4), dp(3));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.BOTTOM | Gravity.START;
            cap.setBackgroundColor(Color.parseColor("#A0000000"));
            fl.addView(cap, clp);

            Anim.press(fl);
            fl.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openDetail(fSid); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pw, ph);
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(fl, lp);
        }
        sectionsContainer.addView(hs);
    }

    // ================================================== skeletons + error
    private void showSkeleton() {
        sectionsContainer.removeAllViews();
        // banner skeleton
        sectionsContainer.addView(Anim.skeletonBlock(this, ViewGroup.LayoutParams.MATCH_PARENT,
                (int) ((getResources().getDisplayMetrics().widthPixels - dp(32)) * 0.46f), 12));
        for (int s = 0; s < 2; s++) {
            TextView t = new TextView(this);
            t.setText(" ");
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            t.setPadding(dp(18), dp(14), 0, dp(4));
            sectionsContainer.addView(t);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(14), 0, dp(14), dp(6));
            for (int i = 0; i < 4; i++) {
                View sk = Anim.skeletonBlock(this, dp(112), dp(160), 10);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(160));
                lp.setMargins(dp(5), 0, dp(5), 0);
                sk.setLayoutParams(lp);
                row.addView(sk);
            }
            hs.addView(row);
            sectionsContainer.addView(hs);
        }
        statusView.setVisibility(View.GONE);
    }

    private void showNetworkError() {
        Anim.stopShimmerAll(sectionsContainer);
        sectionsContainer.removeAllViews();
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Network problem — internet check karein.\n(Cached content bhi offline chalta hai)");

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        TextView retry = new TextView(this);
        retry.setText("⟳  RETRY");
        retry.setTextColor(Color.WHITE);
        retry.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        retry.setTypeface(null, Typeface.BOLD);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(28), dp(14), dp(28), dp(14));
        retry.setBackgroundColor(ACCENT);
        Anim.press(retry);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.setMargins(0, dp(18), 0, 0);
        wrap.addView(retry, rlp);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sectionsContainer.removeAllViews();
                showSkeleton();
                reload(currentTab);
            }
        });
        sectionsContainer.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Anim.fadeIn(retry, 300);
    }

    private void openDetail(String subjectId) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra("subjectId", subjectId);
        startActivity(it);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out);
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onResume() {
        super.onResume();
        highlightNav(currentTab);
    }
}
