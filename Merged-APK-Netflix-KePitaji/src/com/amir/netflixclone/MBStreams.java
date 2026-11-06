package com.amir.netflixclone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MovieBox stream resolution pipeline (for in-app WebView player).
 * play-info v2/v1 -> resource/v2 -> trap recovery -> detail detectors -> UGC fallback.
 * Priority for WebView: h264 mp4 > h264 hls > h265 (webview may lack HEVC).
 */
public class MBStreams {

    public static class Candidate {
        public String url;
        public String quality;
        public String codec;
        public String cookie;
        public String kind; // mp4 | hls
        public String size;
        public String source;

        public Candidate(String url, String quality, String codec, String cookie, String size, String source) {
            this.url = url;
            this.quality = quality;
            this.codec = codec == null ? "" : codec;
            this.cookie = cookie == null ? "" : cookie;
            this.kind = classify(url);
            this.size = size == null ? "" : size;
            this.source = source;
        }
    }

    public static class Result {
        public List<Candidate> candidates = new ArrayList<>();
        public JSONObject subject = new JSONObject();
        public JSONArray seasons = new JSONArray();
        public boolean isSeries = false;
        public String error = "";
    }

    public static String classify(String url) {
        String l = url == null ? "" : url.toLowerCase();
        if (l.contains(".m3u8")) return "hls";
        if (l.contains(".mpd")) return "dash";
        return "mp4";
    }

    private static String qualityOf(String quality, long resolution) {
        if (quality != null && quality.length() > 0) return quality;
        if (resolution > 0) return resolution + "p";
        return "Auto";
    }

    private static String humanSize(Object bytes) {
        long b = 0;
        try {
            if (bytes instanceof Number) b = ((Number) bytes).longValue();
            else if (bytes instanceof String && ((String) bytes).length() > 0
                    && !((String) bytes).contains(" ")) b = Long.parseLong((String) bytes);
            else return bytes instanceof String ? (String) bytes : "";
        } catch (Exception e) { return ""; }
        if (b <= 0) return "";
        if (b > 1024L * 1024 * 1024) return String.format("%.1f GB", b / (1024.0 * 1024 * 1024));
        if (b > 1024L * 1024) return Math.round(b / (1024.0 * 1024)) + " MB";
        return Math.max(1, Math.round(b / 1024.0)) + " KB";
    }

    /** synchronous resolve; call from background thread */
    public static Result resolve(String subjectId, int season, int episode) {
        JSONObject subject;
        try {
            JSONObject det = MovieBoxApi.getSubject(subjectId);
            subject = det.optJSONObject("data");
            if (subject == null) subject = new JSONObject();
        } catch (Exception e) {
            subject = new JSONObject();
        }
        return resolveSubject(subject, subjectId, season, episode);
    }

    /** resolve with a pre-fetched subject (avoids duplicate subject-api/get) */
    public static Result resolveSubject(JSONObject subject, String subjectId, int season, int episode) {
        Result out = new Result();
        if (subject == null) subject = new JSONObject();
        out.subject = subject;
        int subjectType = subject.optInt("subjectType", 2);
        boolean isSeries = subjectType == 2 || subject.optInt("seNum", 0) > 0;
        out.isSeries = isSeries;
        final int se = isSeries ? season : 0;
        final int ep = isSeries ? episode : 0;

        // seasons metadata (for the episode picker)
        try {
            JSONObject si = MovieBoxApi.getSeasonInfoV2(subjectId);
            JSONArray seasons = si.optJSONObject("data") == null ? null
                    : si.optJSONObject("data").optJSONArray("seasons");
            if (seasons != null) out.seasons = seasons;
        } catch (Exception ignored) { }

        List<JSONObject> collected = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        // ---- 1) play-info v2 then v1 ----
        String[][] pis = {
                {"/wefeed-mobile-bff/subject-api/play-info/v2", "play-info/v2"},
                {"/wefeed-mobile-bff/subject-api/play-info", "play-info"},
        };
        for (String[] pi : pis) {
            try {
                Map<String, String> m = new HashMap<>();
                m.put("subjectId", subjectId);
                if (se > 0) m.put("se", String.valueOf(se));
                if (ep > 0) m.put("ep", String.valueOf(ep));
                JSONObject res = MovieBoxApi.mbRequest("GET", pi[0], m, null, true);
                JSONObject data = res.optJSONObject("data");
                if (data == null) continue;
                JSONArray streams = data.optJSONArray("streams");
                if (streams == null) streams = data.optJSONArray("streamList");
                String globalCookie = data.optString("signCookie", "");
                if (streams != null && streams.length() > 0) {
                    for (int i = 0; i < streams.length(); i++) {
                        JSONObject st = streams.optJSONObject(i);
                        if (st == null || st.optString("url", "").isEmpty()) continue;
                        try {
                            if (st.optString("signCookie", "").isEmpty() && globalCookie.length() > 0) {
                                st.put("signCookie", globalCookie);
                            }
                            st.put("_source", pi[1]);
                        } catch (Exception ignored) { }
                        collected.add(st);
                    }
                    break;
                }
            } catch (Exception ignored) { }
        }

        // ---- 2) resource/v2 ----
        try {
            JSONObject res = MovieBoxApi.getResourceV2(subjectId, se, ep);
            JSONObject data = res.optJSONObject("data");
            JSONArray list = data == null ? null : data.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it == null) continue;
                    String link = it.optString("resourceLink", it.optString("url", it.optString("downloadUrl", "")));
                    if (link.isEmpty()) continue;
                    try {
                        JSONObject c = new JSONObject();
                        c.put("url", link);
                        c.put("quality", qualityOf(it.optString("quality", ""), it.optLong("resolution", 0)));
                        c.put("codec", it.optString("codec", it.optString("codecName", "")));
                        c.put("signCookie", it.optString("signCookie", ""));
                        c.put("id", it.optString("resourceId", it.optString("id", "")));
                        c.put("size", humanSize(it.has("sizeBytes") ? it.opt("sizeBytes") : it.opt("size")));
                        c.put("_source", "resource/v2");
                        collected.add(c);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }

        // ---- 3) trap recovery via resource-position/v2 + urlprefix bypass ----
        List<JSONObject> recovered = new ArrayList<>();
        for (JSONObject st : collected) {
            String url = st.optString("url", "");
            String rid = st.optString("resourceId", st.optString("id", ""));
            if (MovieBoxApi.isTrapUrl(url) && rid.length() > 0) {
                try {
                    JSONObject rr = MovieBoxApi.getResourcePosition(subjectId, rid, url);
                    JSONObject data = rr.optJSONObject("data");
                    JSONArray rl = data == null ? null : data.optJSONArray("list");
                    if (rl != null && rl.length() > 0) {
                        JSONObject first = rl.optJSONObject(0);
                        String real = first == null ? "" : first.optString("resourceLink", first.optString("url", ""));
                        if (!real.isEmpty() && !MovieBoxApi.isTrapUrl(real)) {
                            try {
                                st.put("url", real);
                                if (first.optString("signCookie", "").length() > 0) {
                                    st.put("signCookie", first.optString("signCookie"));
                                }
                                st.put("_source", "resource-position");
                            } catch (Exception ignored) { }
                            recovered.add(st);
                        }
                    }
                } catch (Exception ignored) { }
            } else if (!MovieBoxApi.isTrapUrl(url)) {
                recovered.add(st);
            } else {
                String bypassed = MovieBoxApi.bypassTrap(st.optString("signCookie", ""));
                if (bypassed != null && !MovieBoxApi.isTrapUrl(bypassed)) {
                    try {
                        st.put("url", bypassed);
                        st.put("_source", "urlprefix-bypass");
                    } catch (Exception ignored) { }
                    recovered.add(st);
                }
            }
        }
        collected = recovered;

        // ---- 4) detail resourceDetectors ----
        JSONArray detectors = subject.optJSONArray("resourceDetectors");
        if (detectors != null) {
            for (int i = 0; i < detectors.length(); i++) {
                JSONObject det = detectors.optJSONObject(i);
                if (det == null) continue;
                String detCookie = det.optString("signCookie", "");
                String dlUrl = det.optString("downloadUrl", "");
                if (!dlUrl.isEmpty() && !MovieBoxApi.isTrapUrl(dlUrl) && (se == 0 || det.optInt("type", 0) == 0)) {
                    try {
                        JSONObject c = new JSONObject();
                        c.put("url", dlUrl);
                        c.put("quality", "Auto");
                        c.put("codec", "");
                        c.put("signCookie", detCookie);
                        c.put("id", det.optString("resourceId", ""));
                        c.put("size", humanSize(det.opt("size")));
                        c.put("_source", "detail.downloadUrl");
                        collected.add(c);
                    } catch (Exception ignored) { }
                }
                JSONArray rl = det.optJSONArray("resolutionList");
                if (rl != null) {
                    for (int j = 0; j < rl.length(); j++) {
                        JSONObject r = rl.optJSONObject(j);
                        if (r == null) continue;
                        String link = r.optString("resourceLink", "");
                        if (link.isEmpty() || MovieBoxApi.isTrapUrl(link)) continue;
                        if (se > 0 && r.optLong("episode", -1) > 0 && r.optLong("episode", -1) != ep) continue;
                        try {
                            JSONObject c = new JSONObject();
                            c.put("url", link);
                            c.put("quality", r.optLong("resolution", 0) > 0 ? r.optLong("resolution") + "p" : "Auto");
                            c.put("codec", "");
                            c.put("signCookie", detCookie);
                            c.put("id", det.optString("resourceId", ""));
                            c.put("size", humanSize(r.opt("size")));
                            c.put("_source", "detail.resolutionList");
                            collected.add(c);
                        } catch (Exception ignored) { }
                    }
                }
            }
        }

        // ---- 5) final urlprefix bypass ----
        for (JSONObject st : collected) {
            if (MovieBoxApi.isTrapUrl(st.optString("url", "")) && st.optString("signCookie", "").length() > 0) {
                String bypassed = MovieBoxApi.bypassTrap(st.optString("signCookie"));
                if (bypassed != null) {
                    try { st.put("url", bypassed); } catch (Exception ignored) { }
                }
            }
        }

        // ---- 6) dedupe + codec inference + build candidates ----
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenCand = new HashSet<>();
        for (JSONObject st : collected) {
            String url = st.optString("url", "");
            if (url.isEmpty() || MovieBoxApi.isTrapUrl(url)) continue;
            String kind = classify(url);
            if (kind.equals("dash")) continue;
            String url2 = url.toLowerCase();
            String codec = st.optString("codec", st.optString("codecName", ""));
            if (codec.isEmpty()) {
                if (url2.contains("h265") || url2.contains("x265") || url2.contains("hev1") || url2.contains("/h265/")) codec = "hevc";
                else if (url2.contains("h264") || url2.contains("x264") || url2.contains("avc1")) codec = "h264";
            }
            if (seenCand.contains(url)) continue;
            seenCand.add(url);
            candidates.add(new Candidate(url,
                    qualityOf(st.optString("quality", ""), st.optLong("resolution", 0)),
                    codec, st.optString("signCookie", ""), st.optString("size", ""),
                    st.optString("_source", "api")));
        }

        // ---- 7) validate (HEAD) ----
        List<Candidate> ok = new ArrayList<>();
        for (Candidate c : candidates) {
            if (headValidate(c)) ok.add(c);
        }
        if (!ok.isEmpty()) candidates = ok;

        // ---- 8) webview-friendly sort: h264 first, mp4 before hls, hevc last ----
        candidates.sort(new java.util.Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                return prioritize(a) - prioritize(b);
            }
        });
        out.candidates = candidates;
        if (candidates.isEmpty()) out.error = "No playable stream found right now";
        return out;
    }

    public static boolean headValidate(Candidate c) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(c.url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            if (c.cookie.length() > 0) conn.setRequestProperty("Cookie", c.cookie);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200 || code == 206 || code == 302;
        } catch (Exception e) {
            return false;
        }
    }

    /** WebView playback priority: h264(0) > unknown(1) > hevc(2); mp4(0) > hls(1) */
    public static int prioritize(Candidate c) {
        String u = c.url == null ? "" : c.url.toLowerCase();
        String codec = c.codec == null ? "" : codecLower(c.codec);
        int score;
        if (codec.contains("h264") || codec.contains("avc1")) score = 0;
        else if (codec.isEmpty() || codec.equals("unknown")) score = 1;
        else score = 2; // hevc
        if (c.kind.equals("hls")) score += 1;
        if (u.contains("h265") || u.contains("x265") || u.contains("hevc")) score = Math.max(score, 2);
        return score;
    }

    private static String codecLower(String s) { return s == null ? "" : s.toLowerCase(); }
}
