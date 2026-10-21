package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/** Small, bounded, memory-only image cache. Image networking stays inside Jellyfin's provider. */
final class JellyfinImages implements AutoCloseable {
    interface Callback { void ready(Bitmap bitmap); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Set<InputStream> streams = Collections.synchronizedSet(new HashSet<>());
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    JellyfinImages(Context context) { this.context = context.getApplicationContext(); }

    void load(String source, int width, Callback callback) {
        if (closed) return;
        if (!JellyfinArtwork.isProviderImage(source)) {
            callback.ready(null);
            return;
        }
        String key = width + ":" + source;
        Bitmap cached = cache.get(key);
        if (cached != null) { callback.ready(cached); return; }
        worker.execute(() -> {
            Bitmap result = null;
            try {
                result = decode(source, width);
                if (result != null && !closed) cache.put(key, result);
            } catch (Exception ignored) {
                // Missing artwork, provider permission denied, or Jellyfin offline: keep the text card.
            }
            Bitmap bitmap = result;
            if (!closed) main.post(() -> { if (!closed) callback.ready(bitmap); });
        });
    }

    private Bitmap decode(String source, int width) throws Exception {
        if (closed) return null;
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(source))) {
            if (in == null) return null;
            streams.add(in);
            ScheduledFuture<?> deadline = timeouts.schedule(() -> {
                // A provider can return a pipe before its remote request succeeds. Bound that read too.
                try { in.close(); } catch (Exception ignored) { }
            }, 8, TimeUnit.SECONDS);
            try {
                if (closed) return null;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (closed || bytes.size() + n > 6 * 1024 * 1024) return null;
                    bytes.write(buffer, 0, n);
                }
                byte[] data = bytes.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0
                        || options.outWidth > 16000 || options.outHeight > 16000) return null;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > width
                        || options.outHeight / options.inSampleSize > width) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                // Keep alpha so Jellyfin's transparent error tile can fall through to another image.
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(data, 0, data.length, options);
            } finally {
                deadline.cancel(false);
                streams.remove(in);
            }
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        timeouts.shutdownNow();
        main.removeCallbacksAndMessages(null);
        synchronized (streams) {
            for (InputStream stream : streams) {
                try { stream.close(); } catch (Exception ignored) { }
            }
            streams.clear();
        }
        cache.evictAll();
    }
}
