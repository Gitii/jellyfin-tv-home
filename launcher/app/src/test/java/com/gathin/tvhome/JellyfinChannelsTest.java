package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class JellyfinChannelsTest {
    private JellyfinChannels.Item item(String key, boolean library) {
        return new JellyfinChannels.Item(key, "Same display name", null, null, null, "intent-" + key, library);
    }

    @Test public void librariesAreSeparatedByIntentMetadataNotTranslatedChannelName() {
        JellyfinChannels.Row row = new JellyfinChannels.Row("Meine Medien");
        row.items.add(item("library-1", true));
        row.items.add(item("movie-1", false));
        JellyfinChannels.Result content = new JellyfinChannels.Result(Collections.singletonList(row), false);
        assertEquals("library-1", content.libraries.get(0).key);
        assertEquals("movie-1", content.featured.get(0).key);
    }

    @Test public void watchNextOrderWinsAndDuplicateIdsDoNotRepeatAcrossChannels() {
        JellyfinChannels.Row watch = new JellyfinChannels.Row("Watch next");
        watch.items.add(item("episode", false));
        JellyfinChannels.Row other = new JellyfinChannels.Row("Latest");
        other.items.add(item("episode", false));
        other.items.add(item("another-episode", false));
        other.items.add(item("library", true));
        JellyfinChannels.Result content = new JellyfinChannels.Result(Arrays.asList(watch, other), false);
        assertEquals(2, content.featured.size());
        assertEquals("episode", content.featured.get(0).key);
        assertEquals("another-episode", content.featured.get(1).key);
        assertEquals(1, content.libraries.size());
    }

    @Test public void featuredLimitDoesNotDiscardLibraries() {
        JellyfinChannels.Row row = new JellyfinChannels.Row("Data");
        for (int i = 0; i < 30; i++) row.items.add(item("movie-" + i, false));
        row.items.add(item("library", true));
        JellyfinChannels.Result content = new JellyfinChannels.Result(Collections.singletonList(row), false);
        assertEquals(20, content.featured.size());
        assertEquals(1, content.libraries.size());
    }

    @Test public void librariesAreNotTruncatedAtTheFeaturedLimit() {
        JellyfinChannels.Row row = new JellyfinChannels.Row("Libraries");
        for (int i = 0; i < 25; i++) row.items.add(item("library-" + i, true));
        JellyfinChannels.Result content = new JellyfinChannels.Result(Collections.singletonList(row), false);
        assertEquals(25, content.libraries.size());
        assertEquals("library-24", content.libraries.get(24).key);
    }
}
