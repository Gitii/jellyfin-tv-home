package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class GlowRasterTest {
    @Test public void retainsOriginalCentreAndFalloffWithTransparentOutside() {
        int[] pixels = new GlowRaster(100, 100).tint(0xFFFFFFFF);
        assertTrue((pixels[42 * 100 + 32] >>> 24) >= 90);
        assertEquals(0, pixels[99 * 100 + 99]);
        for (int pixel : pixels) assertTrue((pixel >>> 24) <= 92);
    }

    @Test public void noColouredNoiseLeaksIntoBlackOrTransparentPixels() {
        int[] black = new GlowRaster(100, 100).tint(0xFF000000);
        for (int pixel : black) assertEquals(0, pixel & 0xFFFFFF);
        int[] red = new GlowRaster(100, 100).tint(0xFF800000);
        for (int pixel : red) assertEquals(0, pixel & 0xFFFF);
    }

    @Test public void stableNoisePatternDoesNotChangeWhenRevisitingAColour() {
        GlowRaster raster = new GlowRaster(100, 100);
        int[] first = raster.tint(0xFF305080).clone();
        raster.tint(0xFF809030);
        assertArrayEquals(first, raster.tint(0xFF305080));
    }

    @Test public void ditheringBreaksUpLongDarkColourContours() {
        int width = 480, height = 270, y = 113, changes = 0, plainChanges = 0;
        int[] pixels = new GlowRaster(width, height).tint(0xFF304060);
        int previous = -1, plainPrevious = -1;
        for (int x = 0; x < width; x++) {
            int pixel = pixels[y * width + x];
            int a = pixel >>> 24;
            int red = (((pixel >>> 16) & 255) * a + 127) / 255;
            float dx = x + .5f - width * .32f, dy = y + .5f - height * .42f;
            float distance = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / (width * .62f));
            float alpha = distance < .45f ? 92f - 54f * distance / .45f : 38f * (1f - distance) / .55f;
            int plain = Math.round(48 * alpha / 255f);
            assertTrue(Math.abs(red - plain) <= 1);
            if (red != previous) changes++;
            if (plain != plainPrevious) plainChanges++;
            previous = red; plainPrevious = plain;
        }
        assertTrue("Spatial dither should break up the quantized flat regions", changes > plainChanges * 2);
    }
}
