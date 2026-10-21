package com.gathin.tvhome;

import org.junit.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class JellyfinArtworkTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";

    private String wrap(String value) throws Exception {
        return "content://" + JellyfinArtwork.AUTHORITY + "?src="
                + URLEncoder.encode(value, StandardCharsets.UTF_8.name()) + "&v=0.19.10";
    }

    private URI unwrap(String value) throws Exception {
        return URI.create(URLDecoder.decode(URI.create(value).getRawQuery().substring(4),
                StandardCharsets.UTF_8.name()));
    }

    @Test public void derivesBackdropOnSameServerAndPreservesBasePath() throws Exception {
        URI image = unwrap(JellyfinArtwork.backdrop(wrap(
                "https://media.example/jellyfin/Items/" + ID + "/Images/Primary?tag=old&maxWidth=360")));
        assertEquals("media.example", image.getHost());
        assertEquals("/jellyfin/Items/" + ID + "/Images/Backdrop/0", image.getPath());
        assertEquals("maxWidth=1280&quality=80", image.getQuery());
    }

    @Test public void preservesAuthenticationWithoutCopyingItIntoAnotherOrigin() throws Exception {
        URI image = unwrap(JellyfinArtwork.backdrop(wrap(
                "http://media.example:8096/Items/" + ID
                        + "/Images/Thumb/0?api_key=test-only&quality=40&height=400&MaxHeight=300")));
        assertEquals("media.example:8096", image.getAuthority());
        assertEquals("api_key=test-only&maxWidth=1280&quality=80", image.getQuery());
    }

    @Test public void rejectsOtherProvidersAndNonImages() throws Exception {
        assertFalse(JellyfinArtwork.isProviderImage("content://other.provider/image"));
        assertNull(JellyfinArtwork.backdrop("https://media.example/poster.jpg"));
        assertNull(JellyfinArtwork.backdrop(wrap("file:///Items/" + ID + "/Images/Primary")));
        assertNull(JellyfinArtwork.backdrop(wrap("https://media.example/not-an-image")));
        assertNull(JellyfinArtwork.backdrop(wrap("https://user:password@media.example/Items/" + ID + "/Images/Primary")));
    }

    @Test public void malformedMissingAndOversizedSourcesFallBack() {
        assertNull(JellyfinArtwork.backdrop(null));
        assertNull(JellyfinArtwork.backdrop("content://" + JellyfinArtwork.AUTHORITY));
        assertNull(JellyfinArtwork.backdrop("content://" + JellyfinArtwork.AUTHORITY + "?src=%xx"));
        assertFalse(JellyfinArtwork.isProviderImage("content://" + JellyfinArtwork.AUTHORITY + "?src=" + "a".repeat(8192)));
    }
}
