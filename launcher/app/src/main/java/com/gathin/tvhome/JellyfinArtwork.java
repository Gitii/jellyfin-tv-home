package com.gathin.tvhome;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Derives optional backdrops through Jellyfin's own image provider, without copying credentials. */
final class JellyfinArtwork {
    static final String AUTHORITY = "org.jellyfin.androidtv.integration.provider.ImageProvider";

    private JellyfinArtwork() { }

    /** Published fallbacks, in preference order; invalid/duplicate URIs never suppress a valid poster. */
    static List<String> published(String thumbnail, String poster) {
        List<String> out = new ArrayList<>();
        if (isProviderImage(thumbnail)) out.add(thumbnail);
        if (isProviderImage(poster) && !out.contains(poster)) out.add(poster);
        return out;
    }

    /** Jellyfin's error tile is a square logo with a transparent surround, not usable backdrop art. */
    static boolean isPlaceholder(int width, int height, int[] borderPixels) {
        if (width <= 0 || width != height || borderPixels.length != 8) return false;
        for (int pixel : borderPixels) {
            if ((pixel >>> 24) > 16) return false;
        }
        return true;
    }

    static boolean isProviderImage(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            URI uri = URI.create(value);
            return "content".equals(uri.getScheme()) && AUTHORITY.equals(uri.getAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A speculative backdrop request: missing backdrops fall back to the published artwork. */
    static String backdrop(String value) {
        return itemImage(value, "Backdrop/0", 1920);
    }

    static String thumbnail(String value) {
        return itemImage(value, "Thumb", 800);
    }

    static List<Integer> neighbors(int position, int count) {
        List<Integer> out = new ArrayList<>();
        if (position + 1 < count) out.add(position + 1);
        if (position > 0) out.add(position - 1);
        return out;
    }

    /** Do not halve a 1080p original simply because it is wider than the old 1280px request cap. */
    static int sampleSize(int width, int height, int target) {
        if (target <= 0) return 1;
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private static String itemImage(String value, String type, int width) {
        if (!isProviderImage(value)) return null;
        try {
            URI wrapper = URI.create(value);
            if (wrapper.getRawQuery() == null) return null;
            String source = null;
            for (String pair : wrapper.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "src".equals(pair.substring(0, eq))) {
                    source = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                }
            }
            if (source == null) return null;
            URI image = URI.create(source);
            if (!("http".equals(image.getScheme()) || "https".equals(image.getScheme()))
                    || image.getHost() == null || image.getUserInfo() != null) return null;
            String path = image.getPath();
            // Only transform the documented Jellyfin item-image endpoint, retaining any base path.
            if (path == null || !path.matches(".*/Items/[a-fA-F0-9-]{32,36}/Images/(Primary|Thumb)(/0)?")) return null;
            path = path.replaceFirst("/(Primary|Thumb)(/0)?$", "/" + type);
            StringBuilder query = new StringBuilder();
            if (image.getRawQuery() != null) {
                for (String pair : image.getRawQuery().split("&")) {
                    String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8.name());
                    if (key.equalsIgnoreCase("tag") || key.equalsIgnoreCase("maxWidth")
                            || key.equalsIgnoreCase("maxHeight") || key.equalsIgnoreCase("width")
                            || key.equalsIgnoreCase("height") || key.equalsIgnoreCase("quality")) continue;
                    if (query.length() > 0) query.append('&');
                    query.append(pair);
                }
            }
            if (query.length() > 0) query.append('&');
            query.append("maxWidth=").append(width).append("&quality=90");
            String url = new URI(image.getScheme(), image.getAuthority(), path, null, null).toASCIIString()
                    + "?" + query;
            return "content://" + AUTHORITY + "?src="
                    + URLEncoder.encode(url, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }
}
