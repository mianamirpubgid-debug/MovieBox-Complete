package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Search v2 — fixed parsing (data.results[].subjects), live debounced search,
 * poster grid results with fade-in, professional empty states.
 */
public class SearchActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0C10");
    private static final int SURFACE = Color.parseColor("#14161D");
    private static final int ACCENT = Color.parseColor("#E50914");

    private EditText input;
    private LinearLayout resultsGrid;
    private FrameLayout loadingBox;
    private TextView emptyText;
    private LinearLayout dotsWrap;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int lastSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CacheManager.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- top search bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(12), dp(12));
        bar.setBackgroundColor(Color.parseColor("#101218"));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(dp(6), dp(6), dp(12), dp(6));
        Anim.press(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        FrameLayout searchWrap = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(SURFACE);
        gd.setCornerRadius(dp(22));
        searchWrap.setBackground(gd);
        searchWrap.setPadding(dp(14), dp(2), dp(10), dp(2));

        input = new EditText(this);
        input.setHint("Movies, series, actors…");
        input.setHintTextColor(Color.parseColor("#6B7280"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(0, dp(10), 0, dp(10));
        searchWrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
                final String q = s.toString().trim();
                if (q.length() < 2) { showIdle(); return; }
                pendingSearch = new Runnable() {
                    @Override public void run() { doSearch(q); }
                };
                ui.postDelayed(pendingSearch, 450);
            }
        });
        input.requestFocus();

        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(0, dp(44), 1);
        bar.addView(searchWrap, swlp);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- loading dots ----
        loadingBox = new FrameLayout(this);
        dotsWrap = new LinearLayout(this);
        dotsWrap.setGravity(Gravity.CENTER);
        dotsWrap.addView(Anim.loadingDots(this, ACCENT));
        loadingBox.addView(dotsWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        loadingBox.setVisibility(View.GONE);
        root.addView(loadingBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        // ---- empty text ----
        emptyText = new TextView(this);
        emptyText.setText("Movie ya series search karein…");
        emptyText.setTextColor(Color.parseColor("#6B7280"));
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(20), dp(40), dp(20), dp(20));
        root.addView(emptyText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- results grid ----
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        resultsGrid = new LinearLayout(this);
        resultsGrid.setOrientation(LinearLayout.VERTICAL);
        resultsGrid.setPadding(dp(8), dp(4), dp(8), dp(16));
        scroll.addView(resultsGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void showIdle() {
        loadingBox.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText("Movie ya series search karein…");
        resultsGrid.removeAllViews();
    }

    private void doSearch(final String q) {
        final int seq = ++lastSeq;
        emptyText.setVisibility(View.GONE);
        loadingBox.setVisibility(View.VISIBLE);
        resultsGrid.removeAllViews();

        ApiClient.async(new ApiClient.BgTask<JSONArray>() {
            @Override public JSONArray run() {
                try { return ApiClient.searchResults(q, 1); } catch (Exception e) { return new JSONArray(); }
            }
        }, new ApiClient.UiTask<JSONArray>() {
            @Override public void onDone(JSONArray items) {
                if (seq != lastSeq) return; // stale response
                loadingBox.setVisibility(View.GONE);
                if (items == null || items.length() == 0) {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText("\"" + q + "\" ke liye kuch nahi mila.\nSpelling check karein ya dusra naam try karein.");
                    Anim.fadeIn(emptyText, 250);
                    return;
                }
                renderResults(items);
            }
        });
    }

    private void renderResults(JSONArray items) {
        resultsGrid.removeAllViews();
        int n = getResources().getDisplayMetrics().widthPixels;
        int cellW = (n - dp(24)) / 3;
        int cellH = (int) (cellW * 1.42f) + dp(22);

        LinearLayout row = null;
        for (int i = 0; i < items.length(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(4), dp(2), dp(4), dp(2));
                resultsGrid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            JSONObject s = items.optJSONObject(i);
            if (s == null) continue;
            String sid = s.optString("subjectId", "");
            if (sid.isEmpty()) continue;
            final String fSid = sid;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClickable(true);
            Anim.press(cell);

            FrameLayout fl = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(SURFACE);
            fl.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageLoader.load(ApiClient.coverUrl(s), iv);
            try {
                fl.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                    }
                });
                fl.setClipToOutline(true);
            } catch (Exception ignored) {}
            cell.addView(fl, new LinearLayout.LayoutParams(cellW - dp(8), cellH - dp(22)));

            TextView cap = new TextView(this);
            cap.setText(s.optString("title", ""));
            cap.setTextColor(Color.parseColor("#DDDDDD"));
            cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            cap.setMaxLines(2);
            cap.setPadding(dp(3), dp(4), dp(3), 0);
            cell.addView(cap, new LinearLayout.LayoutParams(cellW - dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent it = new Intent(SearchActivity.this, DetailActivity.class);
                    it.putExtra("subjectId", fSid);
                    startActivity(it);
                    overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out);
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(dp(4), dp(4), dp(4), dp(6));
            row.addView(cell, clp);
            Anim.fadeIn(cell, 240);
        }
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
