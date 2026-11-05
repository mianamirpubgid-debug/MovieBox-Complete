package com.moviebox.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MovieBox / AOneRoom signed API client (Java port).
 * Ported from src/lib/moviebox.ts (website) + moviebox-internal-api repo.
 * All data live-fetched from official mobile BFF clusters. No local data.
 */
public class ApiClient {

    // ---------- constants ----------
    public static final String GATEWAY_SECRET = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O";
    public static final String APP_ID = "4U01pxRu278GqCZKY9";
    public static final String UA = "MovieBox/4.0.02 (Android 14; Pixel 6)";
    public static final String IMG_REFERER = "https://api.inmoviebox.com/";

    public static final String[] CLUSTERS = {
            "https://api.inmoviebox.com",
            "https://apig.inmoviebox.com",
            "https://api5.aoneroom.com",
            "https://api6.aoneroom.com",
    };

    public static final String[] TRAP_PATTERNS = {
            "macdn.aoneroom.com", "please_update", "update_app",
            "b164fbfb4347792950bdfbfb563d39d9", "80da52722ee7de7a4303290af6783424",
            "1c7de0bd3393702d9191801f15f88f8d", "9a0461bc39da389663bf3dbb17091d3f",
            "aa348f2541d13ffe", "notice.mp4",
    };

    // ---------- session ----------
    private static final Object LOCK = new Object();
    private static String sToken = "";
    private static long sTokenAt = 0;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** background task: runs on worker thread, returns result */
    public interface BgTask<T> {
        T run();
    }

    /** ui callback: runs on main thread with result */
    public interface UiTask<T> {
        void onDone(T result);
    }

    public static <T> void async(final BgTask<T> bg, final UiTask<T> ui) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T res = null;
                try {
                    res = bg.run();
                } catch (Exception ignored) {
                }
                final T finalRes = res;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        ui.onDone(finalRes);
                    }
                });
            }
        });
    }

    // ---------- crypto helpers ----------
    public static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareTo(b[0]);
                    }
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
            String contentLength = bodyBytes.length > 0 ? String.valueOf(body.getBytes(StandardCharsets.UTF_8).length) : "";
            String canonical = method.toUpperCase() + "\n"
                    + "application/json" + "\n"
                    + "application/json;charset=UTF-8" + "\n"
                    + contentLength + "\n"
                    + ts + "\n"
                    + bodyMd5 + "\n"
                    + resource;
            byte[] key = android.util.Base64.decode(GATEWAY_SECRET, android.util.Base64.DEFAULT);
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key, "HmacMD5"));
            byte[] sig = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return ts + "|2|" + android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return ts + "|2|";
        }
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
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static final String DEVICE_ID = "86820305" + randomDigits(7);

    private static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(10));
        return sb.toString();
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
            byte[] dec = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            String decoded = new String(dec, StandardCharsets.UTF_8);
            if (!decoded.startsWith("http")) return null;
            return decoded.endsWith("/") ? decoded + "index.m3u8" : decoded + "/index.m3u8";
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- guest token ----------
    private static void ensureGuestToken(boolean force) throws Exception {
        synchronized (LOCK) {
            long TTL = 1000L * 60 * 60 * 20;
            if (!force && sToken.length() > 0 && System.currentTimeMillis() - sTokenAt < TTL) return;
            JSONObject res = rawRequest("POST", "/wefeed-mobile-bff/user-api/visitor-login",
                    new HashMap<String, String>(), "{}", true, CLUSTERS[0]);
            if (res != null && res.optInt("code", -1) == 0 && res.optJSONObject("data") != null) {
                String t = res.optJSONObject("data").optString("token", "");
                if (t.length() > 0) {
                    sToken = t;
                    sTokenAt = System.currentTimeMillis();
                }
            }
        }
    }

    public static void ensureSession(final UiTask<Boolean> ui) {
        async(new BgTask<Boolean>() {
            @Override
            public Boolean run() {
                try {
                    ensureGuestToken(false);
                    return sToken.length() > 0;
                } catch (Exception e) {
                    return false;
                }
            }
        }, new UiTask<Boolean>() {
            @Override
            public void onDone(Boolean ok) {
                if (ui != null) ui.onDone(ok);
            }
        });
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
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod(method.toUpperCase());
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
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
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
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
                    synchronized (LOCK) {
                        sToken = t;
                        sTokenAt = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String text = readAll(in);
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("code", -1);
                err.put("msg", "non-json " + status + ": " + text.substring(0, Math.min(120, text.length())));
            } catch (Exception ignored) {
            }
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

    public static byte[] fetchImage(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", IMG_REFERER);
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ---------- request with path healing + cluster rotation + 401 recovery ----------
    public static JSONObject mbRequest(String method, String endpoint,
                                       Map<String, String> params, String body,
                                       boolean rotate) throws Exception {
        ensureGuestToken(false);
        List<String> tryEndpoints = new ArrayList<>();
        tryEndpoints.add(endpoint);
        if (endpoint.startsWith("/wefeed-mobile-bff/")) {
            tryEndpoints.add(endpoint.replace("/wefeed-mobile-bff", ""));
        } else {
            tryEndpoints.add("/wefeed-mobile-bff" + endpoint);
        }
        String[] bases = rotate ? CLUSTERS : new String[]{CLUSTERS[0]};
        JSONObject last = null;
        for (String base : bases) {
            for (String ep : tryEndpoints) {
                JSONObject res;
                try {
                    res = rawRequest(method, ep, params, body, false, base);
                } catch (Exception e) {
                    continue;
                }
                if (res != null) {
                    int code = res.optInt("code", -1);
                    String msg = res.optString("msg", "");
                    if (code == 401 || msg.contains("JWT token has expired") || msg.contains("Unauthorized")) {
                        try {
                            ensureGuestToken(true);
                        } catch (Exception ignored) {
                        }
                        try {
                            res = rawRequest(method, ep, params, body, false, base);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (res != null && res.optInt("code", -1) == 0 && res.has("data")) return res;
                last = res;
            }
        }
        return last != null ? last : new JSONObject("{\"code\":-1,\"msg\":\"no response\"}");
    }

    // ---------- typed API calls ----------
    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static JSONObject getTabOperating(String tabId, int page) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/tab-operating",
                p("tabId", tabId, "page", String.valueOf(page), "pageSize", "24"), null, false);
    }

    public static JSONObject getTopRec(int page, int pageSize) throws Exception {
        return mbRequest("POST", "/wefeed-mobile-bff/subject-api/top-rec",
                p(), "{\"page\":" + page + ",\"pageSize\":" + pageSize + "}", false);
    }

    public static JSONObject getDailyRec(int page, int pageSize) throws Exception {
        return mbRequest("POST", "/wefeed-mobile-bff/subject-api/daily-movie-rec",
                p(), "{\"page\":" + page + ",\"pageSize\":" + pageSize + "}", false);
    }

    public static JSONObject getRankingList(String tabId) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/tab/ranking-list",
                p("tabId", tabId, "categoryType", "all", "page", "1", "perPage", "20"), null, false);
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

    public static JSONObject getPlayInfoV2(String subjectId, int se, int ep) throws Exception {
        Map<String, String> m = p("subjectId", subjectId);
        if (se > 0) m.put("se", String.valueOf(se));
        if (ep > 0) m.put("ep", String.valueOf(ep));
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/play-info/v2", m, null, true);
    }

    public static JSONObject getPlayInfo(String subjectId, int se, int ep) throws Exception {
        Map<String, String> m = p("subjectId", subjectId);
        if (se > 0) m.put("se", String.valueOf(se));
        if (ep > 0) m.put("ep", String.valueOf(ep));
        return mbRequest("GET", "/wefeed-mobile-bff/subject-api/play-info", m, null, true);
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

    public static JSONObject getUgcFirstBySubject(String subjectId) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/ugc/video/first-by-subject",
                p("subjectId", subjectId), null, true);
    }

    public static JSONObject getUgcVideoDownload(String ugcVideoId) throws Exception {
        return mbRequest("GET", "/wefeed-mobile-bff/ugc/video/download",
                p("ugcVideoId", ugcVideoId), null, true);
    }

    // ---------- subject helpers ----------
    public static String coverUrl(JSONObject subject) {
        Object cover = subject.opt("cover");
        if (cover instanceof String) return (String) cover;
        if (cover instanceof JSONObject) return ((JSONObject) cover).optString("url", "");
        return "";
    }

    public static String titleOf(JSONObject item) {
        JSONObject info = infoOf(item);
        return info.optString("title", "");
    }

    public static JSONObject infoOf(JSONObject item) {
        if (item.has("subjectId") || item.has("cover")) return item;
        JSONObject info = item.optJSONObject("info");
        if (info != null && (info.has("subjectId") || info.has("cover"))) return info;
        info = item.optJSONObject("subject");
        if (info != null && (info.has("subjectId") || info.has("cover"))) return info;
        return item;
    }
}
