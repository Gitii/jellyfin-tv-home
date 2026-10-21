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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Two bounded image workers, coalesced requests, and separate thumbnail/backdrop budgets. No disk cache. */
final class JellyfinImages implements AutoCloseable {
    interface Callback { void ready(Bitmap bitmap); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(JellyfinImageQueue.CAPACITY);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, queue);
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
    private final Set<InputStream> streams = Collections.synchronizedSet(new HashSet<>());
    // Owned by the main thread. A focus request joins or promotes its existing prefetch, never downloads twice.
    private final Map<String, Job> pending = new HashMap<>();
    private final LruCache<String, Bitmap> thumbnails = cache(6 * 1024 * 1024);
    private final LruCache<String, Bitmap> backdrops = cache(26 * 1024 * 1024);
    private volatile boolean closed;

    JellyfinImages(Context context) {
        this.context = context.getApplicationContext();
        workers.prestartAllCoreThreads();
    }

    private static LruCache<String, Bitmap> cache(int budget) {
        return new LruCache<String, Bitmap>(budget) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
    }

    void prefetch(String source, int width) { request(source, width, null, () -> true); }

    void load(String source, int width, BooleanSupplier needed, Callback callback) { request(source, width, callback, needed); }

    Bitmap peek(String source, int width) {
        return source == null ? null : store(width).get(width + ":" + source);
    }

    private LruCache<String, Bitmap> store(int width) { return width > 800 ? backdrops : thumbnails; }

    private void request(String source, int width, Callback callback, BooleanSupplier needed) {
        if (closed || !needed.getAsBoolean()) return;
        prune();
        if (!JellyfinArtwork.isProviderImage(source)) {
            if (callback != null) callback.ready(null);
            return;
        }
        Bitmap cached = peek(source, width);
        if (cached != null) { if (callback != null) callback.ready(cached); return; }
        String key = width + ":" + source;
        Job job = pending.get(key);
        if (job != null) {
            if (callback != null) job.callbacks.add(new Subscriber(needed, callback));
            if (callback != null && width > 800 && queue.remove(job)) job.queued = false;
            drain();
            return;
        }
        job = new Job(key, source, width);
        if (callback != null) job.callbacks.add(new Subscriber(needed, callback));
        job.speculative = callback == null;
        pending.put(key, job);
        drain(); // Visible requests remain deferred on backpressure, not completed as missing artwork.
    }

    private static final class Subscriber {
        final BooleanSupplier needed;
        final Callback callback;
        Subscriber(BooleanSupplier needed, Callback callback) { this.needed = needed; this.callback = callback; }
    }

    private void prune() {
        for (Job job : new ArrayList<>(pending.values())) {
            job.callbacks.removeIf(subscriber -> !subscriber.needed.getAsBoolean());
            if (job.callbacks.isEmpty() && !job.speculative && !job.running
                    && (!job.queued || queue.remove(job))) pending.remove(job.key);
        }
    }

    private void drain() {
        prune();
        List<Job> jobs = new ArrayList<>(pending.values());
        jobs.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        for (Job job : jobs) {
            if (job.queued || job.running || !pending.containsKey(job.key)) continue;
            boolean admitted = JellyfinImageQueue.offer(queue, job, job.priority(),
                    task -> ((Job) task).callbacks.isEmpty(), task -> pending.remove(((Job) task).key));
            job.queued = admitted;
            if (!admitted && job.callbacks.isEmpty()) pending.remove(job.key);
        }
    }

    private final class Job implements Runnable {
        final String key, source;
        final int width;
        final List<Subscriber> callbacks = new ArrayList<>();
        volatile boolean running;
        boolean queued, speculative;
        Job(String key, String source, int width) { this.key = key; this.source = source; this.width = width; }

        int priority() {
            return callbacks.isEmpty() ? JellyfinImageQueue.PREFETCH
                    : width > 800 ? JellyfinImageQueue.FOCUSED : JellyfinImageQueue.VISIBLE;
        }

        @Override public void run() {
            running = true;
            long started = android.os.SystemClock.elapsedRealtime();
            android.util.Log.d("JellyfinImages", "Loading image at " + width + "px");
            Bitmap result = null;
            try { result = decode(source, width); } catch (Exception ignored) {
                // Offline, permission denied or missing artwork: retain the tile and the original glow.
                android.util.Log.d("JellyfinImages", "Image unavailable: " + ignored.getClass().getSimpleName());
            }
            android.util.Log.d("JellyfinImages", "Image " + width + "px finished in "
                    + (android.os.SystemClock.elapsedRealtime() - started) + "ms; decoded=" + (result != null));
            if (result != null && !closed) store(width).put(key, result);
            Bitmap bitmap = result;
            if (!closed) main.post(() -> {
                if (closed) return;
                pending.remove(key);
                for (Subscriber subscriber : callbacks) {
                    if (subscriber.needed.getAsBoolean()) subscriber.callback.ready(bitmap);
                }
                drain();
            });
        }
    }

    private Bitmap decode(String source, int width) throws Exception {
        if (closed) return null;
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(source))) {
            if (in == null) return null;
            streams.add(in);
            ScheduledFuture<?> deadline = timeouts.schedule(() -> {
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
                options.inSampleSize = JellyfinArtwork.sampleSize(options.outWidth, options.outHeight, width);
                options.inDensity = Math.max(options.outWidth, options.outHeight) / options.inSampleSize;
                options.inTargetDensity = Math.min(width, options.inDensity);
                options.inScaled = true;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap != null) bitmap.setDensity(Bitmap.DENSITY_NONE);
                return bitmap;
            } finally {
                deadline.cancel(false);
                streams.remove(in);
            }
        }
    }

    void trim() { thumbnails.evictAll(); backdrops.evictAll(); }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        timeouts.shutdownNow();
        main.removeCallbacksAndMessages(null);
        pending.clear();
        synchronized (streams) {
            for (InputStream stream : streams) {
                try { stream.close(); } catch (Exception ignored) { }
            }
            streams.clear();
        }
        trim();
    }
}
