package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
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
 * Home screen — same as APK: banner carousel + poster rows (Movies + Web Series).
 * All data live from MovieBox IN cluster (Hindi dubbed content). No VIP.
 */
public class MainActivity extends Activity {

    private LinearLayout sectionsContainer;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bg = Color.parseColor("#0F1115");
        int card = Color.parseColor("#1A1D24");
        int accent = Color.parseColor("#E50914");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackgroundColor(Color.parseColor("#171A20"));

        TextView logo = new TextView(this);
        logo.setText("🎬 MovieBox HD");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        logo.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(logo, lp);

        TextView searchBtn = new TextView(this);
        searchBtn.setText("🔍");
        searchBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        searchBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        header.addView(searchBtn);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Loading movies…");
        statusView.setTextColor(Color.parseColor("#9AA0A6"));
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        sectionsContainer = new LinearLayout(this);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(sectionsContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        loadHome();
    }

    private void loadHome() {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Loading movies…");
        ApiClient.async(new ApiClient.BgTask<JSONObject[]>() {
            @Override
            public JSONObject[] run() {
                try {
                    JSONObject movies = ApiClient.getTabOperating("2", 1);
                    JSONObject series = null;
                    try {
                        series = ApiClient.getTabOperating("5", 1);
                    } catch (Exception ignored) {
                    }
                    return new JSONObject[]{movies, series};
                } catch (Exception e) {
                    return new JSONObject[]{null, null};
                }
            }
        }, new ApiClient.UiTask<JSONObject[]>() {
            @Override
            public void onDone(JSONObject[] res) {
                statusView.setVisibility(View.GONE);
                sectionsContainer.removeAllViews();
                boolean any = false;
                if (res[0] != null) any |= renderTab(res[0], true);
                if (res[1] != null) any |= renderTab(res[1], false);
                if (!any) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("Network problem — app ko dobara kholo ya internet check karein.");
                }
            }
        });
    }

    /** returns true if anything rendered */
    private boolean renderTab(JSONObject tabRes, boolean withBanner) {
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

    private void renderBanner(JSONObject item) {
        try {
            JSONObject banner = item.optJSONObject("banner");
            if (banner == null) return;
            JSONArray banners = banner.optJSONArray("banners");
            if (banners == null || banners.length() == 0) return;

            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            hs.addView(row);

            int w = getResources().getDisplayMetrics().widthPixels - dp(24);
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
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, (int) (w * 0.42f));
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                iv.setBackgroundColor(Color.parseColor("#1A1D24"));
                ImageLoader.load(url, iv);
                if (!subjectId.isEmpty()) {
                    iv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openDetail(subjectId);
                        }
                    });
                }
                row.addView(iv, lp);
            }
            sectionsContainer.addView(hs);
        } catch (Exception ignored) {
        }
    }

    private void renderSection(String title, JSONArray subjects) {
        if (subjects == null || subjects.length() == 0) return;
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(16), dp(10), dp(16), dp(2));
        sectionsContainer.addView(tv);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
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
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.parseColor("#1A1D24"));
            ImageLoader.load(ApiClient.coverUrl(info), iv);
            fl.addView(iv, new FrameLayout.LayoutParams(pw, ph));

            // corner badge (CAM / quality marker) if present
            JSONObject ops = info.optJSONObject("ops");
            String badge = ops == null ? info.optString("corner", "") : ops.optString("cornerBadge", "");
            if (badge != null && badge.length() > 0) {
                TextView bt = new TextView(this);
                bt.setText(badge);
                bt.setTextColor(Color.WHITE);
                bt.setBackgroundColor(Color.parseColor("#CC000000"));
                bt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
                bt.setPadding(dp(4), dp(1), dp(4), dp(1));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.gravity = Gravity.TOP | Gravity.START;
                blp.setMargins(dp(4), dp(4), 0, 0);
                fl.addView(bt, blp);
            }

            TextView cap = new TextView(this);
            cap.setText(fTitle);
            cap.setTextColor(Color.parseColor("#DDDDDD"));
            cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            cap.setMaxLines(1);
            cap.setPadding(dp(2), dp(2), dp(2), dp(2));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.BOTTOM | Gravity.START;
            cap.setBackgroundColor(Color.parseColor("#B0000000"));
            fl.addView(cap, clp);

            fl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openDetail(fSid);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pw, ph);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(fl, lp);
        }
        sectionsContainer.addView(hs);
    }

    private void openDetail(String subjectId) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra("subjectId", subjectId);
        startActivity(it);
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
