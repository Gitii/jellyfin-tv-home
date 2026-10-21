package com.gathin.tvhome;

/** Original 480x270 glow geometry, with deterministic dithering in the final premultiplied colour. */
final class GlowRaster {
    private final float[] alpha;
    private final byte[] noise;
    private final int[] pixels;

    GlowRaster(int width, int height) {
        alpha = new float[width * height];
        noise = new byte[alpha.length];
        pixels = new int[alpha.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x;
            float dx = x + .5f - width * .32f, dy = y + .5f - height * .42f;
            float distance = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / (width * .62f));
            alpha[i] = distance < .45f ? 92f + (38f - 92f) * distance / .45f
                    : 38f * (1f - distance) / .55f;
            int hash = x * 0x1f123bb5 ^ y * 0x5f356495;
            hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
            noise[i] = (byte) (hash ^ (hash >>> 16));
        }
    }

    int[] tint(int colour) {
        int r = (colour >>> 16) & 255, g = (colour >>> 8) & 255, b = colour & 255;
        for (int i = 0; i < pixels.length; i++) {
            int a = Math.round(alpha[i]);
            if (a == 0) { pixels[i] = 0; continue; }
            float n = noise[i] / 256f;
            pixels[i] = (a << 24) | (channel(r, alpha[i], a, n) << 16)
                    | (channel(g, alpha[i], a, n) << 8) | channel(b, alpha[i], a, n);
        }
        return pixels;
    }

    private static int channel(int value, float alpha, int a, float noise) {
        int premultiplied = Math.max(0, Math.min(a, Math.round(value * alpha / 255f + noise)));
        return (premultiplied * 255 + a / 2) / a;
    }
}
