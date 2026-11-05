package com.moviebox.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.BaseAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Search page — searchV2 API + poster grid. */
public class SearchActivity extends Activity {

    private GridView grid;
    private TextView statusView;
    private List<JSONObject> results = new ArrayList<>();
    private BaseAdapter adapter;
    private String query = "";
    private int page = 1;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F1115"));

        // ---- search bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(Color.parseColor("#171A20"));

        final EditText input = new EditText(this);
        input.setHint("Search movies, series…");
        input.setHintTextColor(Color.parseColor("#777777"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackgroundColor(Color.parseColor("#2A2E36"));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView go = new TextView(this);
        go.setText("🔍");
        go.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        go.setPadding(dp(12), dp(6), dp(8), dp(6));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(input.getText().toString().trim());
            }
        });
        bar.addView(go);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(input.getText().toString().trim());
                    return true;
                }
                return false;
            }
        });

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9AA0A6"));
        statusView.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusView.setText("Movie ya series ka naam likhein…");
        root.addView(statusView);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setBackgroundColor(Color.parseColor("#0F1115"));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(10));
        grid.setAdapter(adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return results.size();
            }

            @Override
            public Object getItem(int position) {
                return results.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                FrameLayout fl;
                if (convertView instanceof FrameLayout) {
                    fl = (FrameLayout) convertView;
                } else {
                    fl = new FrameLayout(SearchActivity.this);
                }
                int pw = (int) ((getResources().getDisplayMetrics().widthPixels - dp(38)) / 3f);
                int ph = (int) (pw * 1.42f);

                ImageView iv;
                TextView cap;
                if (fl.getChildCount() == 2) {
                    iv = (ImageView) fl.getChildAt(0);
                    cap = (TextView) fl.getChildAt(1);
                } else {
                    fl.removeAllViews();
                    iv = new ImageView(SearchActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundColor(Color.parseColor("#1A1D24"));
                    fl.addView(iv, new FrameLayout.LayoutParams(pw, ph));
                    cap = new TextView(SearchActivity.this);
                    cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                    cap.setTextColor(Color.parseColor("#DDDDDD"));
                    cap.setBackgroundColor(Color.parseColor("#B0000000"));
                    cap.setMaxLines(1);
                    cap.setPadding(dp(2), dp(2), dp(2), dp(2));
                    FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                    fl.addView(cap, clp);
                }

                JSONObject s = results.get(position);
                JSONObject info = ApiClient.infoOf(s);
                String url = ApiClient.coverUrl(info);
                iv.setImageDrawable(null);
                ImageLoader.load(url, iv);
                cap.setText(info.optString("title", ""));
                return fl;
            }
        });
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                JSONObject s = results.get(position);
                JSONObject info = ApiClient.infoOf(s);
                String sid = info.optString("subjectId", "");
                if (!sid.isEmpty()) {
                    Intent it = new Intent(SearchActivity.this, DetailActivity.class);
                    it.putExtra("subjectId", sid);
                    startActivity(it);
                }
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (!loading && !query.isEmpty() && totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 3) {
                    page++;
                    searchPage();
                }
            }
        });

        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void doSearch(String q) {
        if (q.isEmpty()) return;
        query = q;
        page = 1;
        results.clear();
        adapter.notifyDataSetChanged();
        statusView.setText("Searching “" + q + "”…");
        searchPage();
    }

    private void searchPage() {
        loading = true;
        final int p = page;
        ApiClient.async(new ApiClient.BgTask<JSONArray>() {
            @Override
            public JSONArray run() {
                try {
                    JSONObject res = ApiClient.searchV2(query, p);
                    JSONObject data = res.optJSONObject("data");
                    if (data == null) return new JSONArray();
                    JSONArray list = data.optJSONArray("list");
                    if (list == null) list = data.optJSONArray("items");
                    if (list == null) list = data.optJSONArray("subjects");
                    return list == null ? new JSONArray() : list;
                } catch (Exception e) {
                    return new JSONArray();
                }
            }
        }, new ApiClient.UiTask<JSONArray>() {
            @Override
            public void onDone(JSONArray list) {
                loading = false;
                int before = results.size();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject s = list.optJSONObject(i);
                    if (s != null) results.add(s);
                }
                statusView.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
                if (results.isEmpty()) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("Kuch nahi mila — spelling check karein ya English mein search karein.");
                }
            }
        });
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
