package com.moviebox.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poster image loader — memory cache + disk cache (offline save) + fade-in.
 */
public class ImageLoader {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final int MAX_MEM = (int) (Runtime.getRuntime().maxMemory() / 6);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_MEM) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public static void load(final String url, final ImageView view) {
        if (url == null || url.length() == 0) return;
        view.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        // disk cache (offline) — decode fast
        Bitmap disk = CacheManager.imgGet(url);
        if (disk != null) {
            CACHE.put(url, disk);
            view.setImageBitmap(disk);
            Anim.fadeIn(view, 220);
            return;
        }
        view.post(new Runnable() {
            @Override public void run() { Anim.shimmer(view, 0); }
        });
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = ApiClient.fetchImage(url);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 1;
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    if (bmp == null) return;
                    CacheManager.imgPut(url, data);
                    CACHE.put(url, bmp);
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            Object tag = view.getTag();
                            if (tag != null && tag.equals(url)) {
                                Anim.stopShimmer(view);
                                view.clearAnimation();
                                view.setAlpha(0f);
                                view.setImageBitmap(bmp);
                                view.animate().alpha(1f).setDuration(340)
                                        .setInterpolator(new DecelerateInterpolator()).start();
                            }
                        }
                    });
                } catch (Exception ignored) {
                    view.post(new Runnable() {
                        @Override public void run() { Anim.stopShimmer(view); }
                    });
                }
            }
        });
    }
}
