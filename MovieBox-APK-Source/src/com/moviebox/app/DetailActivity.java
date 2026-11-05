package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Detail page — poster, title, meta, description, Hindi/English dub switch,
 * play button (movies) or episode list (series). All playable, no VIP.
 */
public class DetailActivity extends Activity {

    private String subjectId;
    private LinearLayout infoContainer;
    private TextView statusView;
    private View loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        subjectId = getIntent().getStringExtra("subjectId");
        if (subjectId == null) subjectId = "";

        int bg = Color.parseColor("#0F1115");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        infoContainer = new LinearLayout(this);
        infoContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(infoContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9AA0A6"));
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setText("Loading…");
        infoContainer.addView(statusView);

        loadDetail(subjectId);
    }

    private void loadDetail(final String sid) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Loading…");
        ApiClient.async(new ApiClient.BgTask<JSONObject>() {
            @Override
            public JSONObject run() {
                try {
                    return ApiClient.getSubject(sid);
                } catch (Exception e) {
                    return null;
                }
            }
        }, new ApiClient.UiTask<JSONObject>() {
            @Override
            public void onDone(JSONObject res) {
                infoContainer.removeAllViews();
                if (res == null || res.optInt("code", -1) != 0 || res.optJSONObject("data") == null) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("Movie load nahi hui. Internet check karein.");
                    return;
                }
                statusView.setVisibility(View.GONE);
                renderDetail(res.optJSONObject("data"));
            }
        });
    }

    private void renderDetail(JSONObject s) {
        int bg = Color.parseColor("#0F1115");
        String title = s.optString("title", "");
        String desc = s.optString("description", "");
        String year = s.optString("releaseDate", "");
        if (year.length() > 4) year = year.substring(0, 4);
        String genre = s.optString("genre", "");
        String country = s.optString("countryName", "");
        String imdb = s.optString("imdbRatingValue", "");
        int subjectType = s.optInt("subjectType", 1);
        boolean isSeries = subjectType == 2 || s.optInt("seNum", 0) > 0;

        // ---- top: poster + info ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(12), dp(12), dp(12), dp(8));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.parseColor("#1A1D24"));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(140), (int) (dp(140) * 1.42f));
        top.addView(poster, plp);
        ImageLoader.load(ApiClient.coverUrl(s), poster);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12), 0, 0, 0);

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
        tvType.setTextColor(Color.parseColor("#E50914"));
        tvType.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvType.setPadding(0, dp(6), 0, 0);
        tvType.setTypeface(null, android.graphics.Typeface.BOLD);
        meta.addView(tvType);
        top.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        infoContainer.addView(top);

        // ---- play button ----
        if (!isSeries) {
            TextView play = new TextView(this);
            play.setText("▶  Play Movie");
            play.setTextColor(Color.WHITE);
            play.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            play.setGravity(Gravity.CENTER);
            play.setTypeface(null, android.graphics.Typeface.BOLD);
            play.setBackgroundColor(Color.parseColor("#E50914"));
            play.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp2.setMargins(dp(12), dp(8), dp(12), dp(4));
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPlayer(subjectId, 0, 0);
                }
            });
            infoContainer.addView(play, plp2);
        }

        // ---- dubs (Hindi / English switch) ----
        JSONArray dubs = s.optJSONArray("dubs");
        if (dubs != null && dubs.length() > 1) {
            TextView dubTitle = new TextView(this);
            dubTitle.setText("Audio / Language Versions");
            dubTitle.setTextColor(Color.WHITE);
            dubTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            dubTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            dubTitle.setPadding(dp(16), dp(12), dp(16), dp(4));
            infoContainer.addView(dubTitle);

            HorizontalScrollView dhs = new HorizontalScrollView(this);
            LinearLayout drow = new LinearLayout(this);
            drow.setOrientation(LinearLayout.HORIZONTAL);
            drow.setPadding(dp(12), dp(4), dp(12), dp(4));
            for (int i = 0; i < dubs.length(); i++) {
                JSONObject d = dubs.optJSONObject(i);
                if (d == null) continue;
                final String dubSid = d.optString("subjectId", "");
                String lan = d.optString("lanName", d.optString("lanCode", "Audio"));
                if (dubSid.isEmpty() || dubSid.equals(subjectId)) continue;
                TextView btn = new TextView(this);
                btn.setText(lan);
                btn.setTextColor(Color.WHITE);
                btn.setBackgroundColor(Color.parseColor("#2A2E36"));
                btn.setPadding(dp(14), dp(8), dp(14), dp(8));
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // reload with dubbed subjectId
                        loadDetail(dubSid);
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.setMargins(dp(4), 0, dp(4), 0);
                drow.addView(btn, blp);
            }
            dhs.addView(drow);
            infoContainer.addView(dhs);
        }

        // ---- description ----
        if (desc.length() > 0) {
            TextView dt = new TextView(this);
            dt.setText("Story");
            dt.setTextColor(Color.WHITE);
            dt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            dt.setTypeface(null, android.graphics.Typeface.BOLD);
            dt.setPadding(dp(16), dp(12), dp(16), dp(2));
            infoContainer.addView(dt);

            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(Color.parseColor("#BBBBBB"));
            dv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            dv.setPadding(dp(16), dp(4), dp(16), dp(12));
            infoContainer.addView(dv);
        }

        // ---- episodes for series ----
        if (isSeries) {
            renderEpisodes(s);
        }
    }

    private void renderEpisodes(JSONObject s) {
        // collect episode numbers from resourceDetectors resolutionList
        List<Long> eps = new ArrayList<>();
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

        TextView et = new TextView(this);
        et.setText(eps.isEmpty() ? "Episodes" : "Episodes (" + eps.size() + ")");
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        et.setTypeface(null, android.graphics.Typeface.BOLD);
        et.setPadding(dp(16), dp(12), dp(16), dp(4));
        infoContainer.addView(et);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(12));

        if (eps.isEmpty()) {
            // fallback: ask season-info/v2 in background; meanwhile offer 1..12
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
        btn.setBackgroundColor(Color.parseColor("#2A2E36"));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlayer(subjectId, 1, (int) ep);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(40));
        lp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(btn, lp);
    }

    private void openPlayer(String sid, int se, int ep) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("subjectId", sid);
        it.putExtra("se", se);
        it.putExtra("ep", ep);
        it.putExtra("title", "");
        startActivity(it);
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
