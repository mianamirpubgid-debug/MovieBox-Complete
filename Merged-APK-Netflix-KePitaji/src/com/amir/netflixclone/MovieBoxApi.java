package com.amir.netflixclone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MovieBox / AOneRoom signed API client (Java).
 * Proven pipeline ported from the MovieBox HD app (v1.2) - live verified:
 * visitor-login -> tab-operating / search/v2 / subject get / play-info / resource.
 */
public class MovieBoxApi {

    public static final String GATEWAY_SECRET = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
    public static final String APP_ID = "4U01pxRu278GqCZKY9";
    public static final String UA = "MovieBox/4.0.02 (Android 14; Pixel 6)";

    public static final String[] CLUSTERS = {
            "https://api6.aoneroom.com",
            "https://api.inmoviebox.com",
            "https://apig.inmoviebox.com",
            "https://api5.aoneroom.com",
    };

    private static volatile String sBestCluster = "";

    public static final String[] TRAP_PATTERNS = {
            "macdn.aoneroom.com", "please_update", "update_app",
            "b164fbfb4347792950bdfbfb563d39d9", "80da52722ee7de7a4303290af6783424",
            "1c7de0bd3393702d9191801f15f88f8d", "9a0461bc39da389663bf3dbb17091d3f",
            "aa348f2541d13ffe", "notice.mp4",
    };

    private static final Object LOCK = new Object();
    private static String sToken = "";
    private static long sTokenAt = 0;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    public interface Bg<T> { T run(); }
    public interface Ui<T> { void onDone(T result); }

    public static <T> void async(final Bg<T> bg, final Ui<T> ui) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                T res = null;
                try { res = bg.run(); } catch (Throwable ignored) { }
                final T f = res;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() { ui.onDone(f); }
                });
            }
        });
    }

    // ---------- crypto ----------
    public static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String generateClientToken() {
        String ts = String.valueOf(System.currentTimeMillis());
        String rev = new StringBuilder(ts).reverse().toString();
        return ts + "," + md5Hex(rev.getBytes(StandardCharsets.UTF_8));
    }

    private static String generateTrSignature(String method, String url, String body, long ts) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            String query = u.getQuery();
            List<String[]> pairs = new ArrayList<>();
            if (query != null && query.length() > 0) {
                for (String p : query.split("&")) {
                    int i = p.indexOf('=');
                    if (i > 0) pairs.add(new String[]{p.substring(0, i), p.substring(i + 1)});
                }
                Collections.sort(pairs, new Comparator<String[]>() {
                    @Override public int compare(String[] a, String[] b) { return a[0].compareTo(b[0]); }
                });
            }
            StringBuilder resource = new StringBuilder(path);
            if (!pairs.isEmpty()) {
                resource.append('?');
                for (int i = 0; i < pairs.size(); i++) {
                    if (i > 0) resource.append('&');
                    resource.append(pairs.get(i)[0]).append('=').append(pairs.get(i)[1]);
                }
            }
            byte[] bodyBytes = body != null && body.length() > 0
                    ? body.substring(0, Math.min(body.length(), 102400)).getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            String bodyMd5 = bodyBytes.length > 0 ? md5Hex(bodyBytes) : "";
            String contentLength = bodyBytes.length > 0
                    ? String.valueOf(body.getBytes(StandardCharsets.UTF_8).length) : "";
            String canonical = method.toUpperCase() + "\n"
                    + "application/json" + "\n"
                    + "application/json;charset=UTF-8" + "\n"
                    + contentLength + "\n"
                    + ts + "\n"
                    + bodyMd5 + "\n"
                    + resource;
            byte[] key = Base64.getDecoder().decode(GATEWAY_SECRET);
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key, "HmacMD5"));
            byte[] sig = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return ts + "|2|" + Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            return ts + "|2|";
        }
    }

    private static final String DEVICE_ID = "86820305" + randomDigits(7);

    private static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private static String clientInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("package_name", "com.community.oneroom");
            o.put("version_name", "4.0.02");
            o.put("version_code", 50020126);
            o.put("os", "android");
            o.put("os_version", "14");
            o.put("install_ch", "ps");
            o.put("device_id", DEVICE_ID);
            o.put("install_store", "ps");
            o.put("gaid", "");
            o.put("brand", "Google");
            o.put("model", "Pixel 6");
            o.put("system_language", "en");
            o.put("net", "wifi");
            o.put("region", "IN");
            o.put("timezone", "Asia/Kolkata");
            o.put("sp_code", "404");
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---------- trap helpers ----------
    public static boolean isTrapUrl(String u) {
        if (u == null || u.length() == 0) return true;
        String l = u.toLowerCase();
        for (String p : TRAP_PATTERNS) if (l.contains(p)) return true;
        return false;
    }

    public static String bypassTrap(String signCookie) {
        if (signCookie == null || !signCookie.contains("urlprefix=")) return null;
        try {
            int start = signCookie.indexOf("urlprefix=") + 10;
            int end = signCookie.indexOf(':', start);
            int end2 = signCookie.indexOf(';', start);
            if (end == -1 || (end2 != -1 && end2 < end)) end = end2 == -1 ? signCookie.length() : end2;
            if (end == -1) end = signCookie.length();
            String b64 = signCookie.substring(start, end);
            int pad = (4 - (b64.length() % 4)) % 4;
            for (int i = 0; i < pad; i++) b64 += "=";
            byte[] dec = Base64.getMimeDecoder().decode(b64);
            String decoded = new String(dec, StandardCharsets.UTF_8);
            if (!decoded.startsWith("http")) return null;
            return decoded.endsWith("/") ? decoded + "index.m3u8" : decoded + "/index.m3u8";
        } catch (Exception e) { return null; }
    }

    // ---------- session ----------
    private static void ensureGuestToken(boolean force) throws Exception {
        synchronized (LOCK) {
            long TTL = 1000L * 60 * 60 * 20;
            if (!force && sToken.length() > 0 && System.currentTimeMillis() - sTokenAt < TTL) return;
            for (String base : orderedClusters()) {
                JSONObject res;
                try {
                    res = rawRequest("POST", "/wefeed-mobile-bff/user-api/visitor-login",
                            new HashMap<String, String>(), "{}", true, base);
                } catch (Exception e) { continue; }
                if (res != null && res.optInt("code", -1) == 0 && res.optJSONObject("data") != null) {
                    String t = res.optJSONObject("data").optString("token", "");
                    if (t.length() > 0) {
                        sToken = t;
                        sTokenAt = System.currentTimeMillis();
                        if (sBestCluster.length() == 0) sBestCluster = base;
                        return;
                    }
                }
            }
            throw new Exception("all clusters failed on visitor-login");
        }
    }

    // ---------- raw request ----------
    private static JSONObject rawRequest(String method, String endpoint,
                                         Map<String, String> params, String body,
                                         boolean isAuthCall, String base) throws Exception {
        Map<String, String> p = new HashMap<>();
        if (params != null) p.putAll(params);
        if (!p.containsKey("host")) p.put("host", base.replace("https://", ""));

        long ts = System.currentTimeMillis();
        List<String> keys = new ArrayList<>(p.keySet());
        Collections.sort(keys);
        StringBuilder q = new StringBuilder();
        for (String k : keys) {
            if (q.length() > 0) q.append('&');
            q.append(k).append('=').append(p.get(k));
        }
        String url = base + endpoint + "?" + q;
        String bodyStr = body != null ? body : "";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod(method.toUpperCase());
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setRequestProperty("X-M-Version", "4.0.02");
        conn.setRequestProperty("Accept-Language", "en-IN,hi-IN;q=0.9,en;q=0.8");
        conn.setRequestProperty("X-Sign-Version", "2.0");
        conn.setRequestProperty("appid", APP_ID);
        conn.setRequestProperty("region", "IN");
        conn.setRequestProperty("lang", "en");
        conn.setRequestProperty("os", "android");
        conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
        conn.setRequestProperty("Referer", base + "/");
        conn.setRequestProperty("X-Client-Token", generateClientToken());
        conn.setRequestProperty("x-tr-signature", generateTrSignature(method, url, bodyStr, ts));
        conn.setRequestProperty("X-Play-Mode", "2");
        conn.setRequestProperty("X-Client-Info", clientInfo());
        conn.setRequestProperty("X-Client-Status", "0");
        if (!isAuthCall && sToken.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + sToken);
        }
        if (bodyStr.length() > 0) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(bodyStr.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }

        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String xUser = conn.getHeaderField("x-user");
        if (xUser != null && xUser.length() > 0) {
            try {
                JSONObject ud = new JSONObject(xUser);
                String t = ud.optString("token", "");
                if (t.length() > 0) {
                    synchronized (LOCK) { sToken = t; sTokenAt = System.currentTimeMillis(); }
                }
            } catch (Exception ignored) { }
        }
        String text = readAll(in);
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("code", -1);
                err.put("msg", "non-json " + status + ": " + text.substring(0, Math.min(120, text.length())));
            } catch (Exception ignored) { }
            return err;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    private static List<String> orderedClusters() {
        List<String> out = new ArrayList<>();
        String best = sBestCluster;
        if (best != null && best.length() > 0) out.add(best);
        for (String c : CLUSTERS) if (!out.contains(c)) out.add(c);
        return out;
    }

    // ---------- request with cluster rotation + 401 recovery ----------
    public static JSONObject mbRequest(String method, String endpoint,
                                       Map<String, String> params, String body,
                                       boolean rotate) throws Exception {
        try { ensureGuestToken(false); } catch (Exception ignored) { }
        List<String> tryEndpoints = new ArrayList<>();
        tryEndpoints.add(endpoint);
        if (endpoint.startsWith("/wefeed-mobile-bff/")) {
            tryEndpoints.add(endpoint.replace("/wefeed-mobile-bff", ""));
        } else {
            tryEndpoints.add("/wefeed-mobile-bff" + endpoint);
        }
        List<String> bases = orderedClusters();
        JSONObject last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String base : bases) {
                for (String ep : tryEndpoints) {
                    JSONObject res;
                    try {
                        res = rawRequest(method, ep, params, body, false, base);
                    } catch (Exception e) { continue; }
                    if (res != null) {
                        int code = res.optInt("code", -1);
                        String msg = res.optString("msg", "") + res.optString("message", "");
                        if (code == 401 || code == 441 || msg.contains("JWT token has expired")
                                || msg.contains("Unauthorized") || msg.contains("miss token")) {
                            try { ensureGuestToken(true); } catch (Exception ignored) { }
                            try { res = rawRequest(method, ep, params, body, false, base); }
                            catch (Exception ignored) { }
                        }
                    }
                    if (res != null && res.optInt("code", -1) == 0 && res.has("data")) {
                        sBestCluster = base;
                        return res;
                    }
                    last = res;
                }
            }
        }
        return last != null ? last : new JSONObject("{\"code\":-1,\"msg\":\"no response\"}");
    }

    // ---------- typed calls ----------
    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static JSONObject getTabOperating(String tabId, int page) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/tab-operating",
                p("tabId", tabId, "page", String.valueOf(page), "pageSize", "24"), null, false);
    }

    public static JSONObject searchV2(String keyword, int page) throws Exception {
        String safe = keyword.replace("\"", "\\\"");
        String body = "{\"keyword\":\"" + safe + "\",\"page\":" + page
                + ",\"perPage\":20,\"pageSize\":20,\"subjectType\":0,\"type\":0,\"q\":\"" + safe + "\"}";
        return mbRequest("POST", "/wefeed-mobile-bff/subject-api/search/v2", p(), body, true);
    }

    public static JSONObject getSubject(String subjectId) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/get",
                p("subjectId", subjectId), null, false);
    }

    public static JSONObject getSeasonInfoV2(String subjectId) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/season-info/v2",
                p("subjectId", subjectId), null, false);
    }

    public static JSONObject getResourceV2(String subjectId, int se, int ep) throws Exception {
        Map<String, String> m = p("subjectId", subjectId, "page", "1", "perPage", "20",
                "all", "0", "startPosition", "1", "endPosition", "1", "pagerMode", "2",
                "resolution", "0", "isVip", "true");
        if (se > 0 || ep > 0) {
            m.put("se", String.valueOf(se));
            m.put("epFrom", String.valueOf(ep));
            m.put("epTo", String.valueOf(ep));
        }
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/resource/v2", m, null, true);
    }

    public static JSONObject getResourcePosition(String subjectId, String resourceId, String failUrl) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/resource-position/v2",
                p("subjectId", subjectId, "resourceId", resourceId, "failUrl", failUrl,
                        "failCode", "404", "resourceNum", "0", "isVip", "true"), null, true);
    }

    // ---------- subject helpers ----------
    public static String coverUrl(JSONObject subject) {
        Object cover = subject.opt("cover");
        if (cover instanceof String) return (String) cover;
        if (cover instanceof JSONObject) return ((JSONObject) cover).optString("url", "");
        return "";
    }

    public static JSONObject infoOf(JSONObject item) {
        if (item.has("subjectId") || item.has("cover")) return item;
        JSONObject info = item.optJSONObject("info");
        if (info != null && (info.has("subjectId") || info.has("cover"))) return info;
        info = item.optJSONObject("subject");
        if (info != null && (info.has("subjectId") || info.has("cover"))) return info;
        return item;
    }

    /** Flatten search results (data.results[].subjects) into a plain JSONArray */
    public static JSONArray searchResults(String keyword, int page) throws Exception {
        JSONObject res = searchV2(keyword, page);
        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (res.optInt("code", -1) != 0) return out;
        JSONObject d = res.optJSONObject("data");
        if (d == null) return out;
        Object raw = d.opt("results");
        if (!(raw instanceof JSONArray)) return out;
        JSONArray groups = (JSONArray) raw;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray subs = g.optJSONArray("subjects");
            if (subs == null) continue;
            for (int j = 0; j < subs.length(); j++) {
                JSONObject s = subs.optJSONObject(j);
                if (s == null) continue;
                JSONObject info = infoOf(s);
                String sid = info.optString("subjectId", "");
                if (sid.isEmpty() || !seen.add(sid)) continue;
                if (coverUrl(info).isEmpty()) continue;
                out.put(info);
            }
        }
        return out;
    }
}
