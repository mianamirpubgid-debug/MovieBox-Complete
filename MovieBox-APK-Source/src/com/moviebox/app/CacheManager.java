package com.moviebox.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Disk cache — "auto offline save": API responses + poster images are stored
 * locally so the app opens instantly and still works when internet is down.
 */
public class CacheManager {

    private static File apiDir;
    private static File imgDir;

    public static void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        File base = new File(app.getFilesDir(), "mbcache");
        apiDir = new File(base, "api");
        imgDir = new File(base, "img");
        if (!apiDir.exists()) apiDir.mkdirs();
        if (!imgDir.exists()) imgDir.mkdirs();
    }

    private static File apiFile(String key) {
        String safe = ApiClient.md5Hex(key.getBytes(StandardCharsets.UTF_8));
        return new File(apiDir, safe + ".json");
    }

    /** read cached API response (any age — used for offline/instant render) */
    public static String apiGet(String key) {
        try {
            File f = apiFile(key);
            if (!f.exists()) return null;
            byte[] data = readFile(f);
            if (data == null) return null;
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void apiPut(String key, String json) {
        try {
            writeFile(apiFile(key), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** image disk file for a url */
    public static File imgFile(String url) {
        String safe = ApiClient.md5Hex(url.getBytes(StandardCharsets.UTF_8));
        return new File(imgDir, safe);
    }

    public static Bitmap imgGet(String url) {
        try {
            File f = imgFile(url);
            if (!f.exists()) return null;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public static void imgPut(String url, byte[] data) {
        try {
            if (data == null || data.length == 0) return;
            writeFile(imgFile(url), data);
            trimDir(imgDir, 300); // keep max ~300 images
        } catch (Exception ignored) {
        }
    }

    private static void trimDir(File dir, int max) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= max) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            int over = files.length - max;
            for (int i = 0; i < over; i++) files[i].delete();
        } catch (Exception ignored) {
        }
    }

    static byte[] readFile(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.flush();
        fo.close();
    }
}
