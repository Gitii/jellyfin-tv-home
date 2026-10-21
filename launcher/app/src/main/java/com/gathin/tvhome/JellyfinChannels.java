package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only Jellyfin's published listings; never opens its private files or changes TV channels. */
final class JellyfinChannels {
    static final String PACKAGE = "org.jellyfin.androidtv";
    static final int MAX_ITEMS = 20;

    static final class Item {
        final String title, description, poster, thumbnail, intent;

        Item(String title, String description, String poster, String thumbnail, String intent) {
            this.title = title;
            this.description = description;
            this.poster = poster;
            this.thumbnail = thumbnail;
            this.intent = intent;
        }
    }

    static final class Row {
        final String title;
        final List<Item> items = new ArrayList<>();

        Row(String title) { this.title = title; }
    }

    static final class Result {
        final List<Row> rows;
        final boolean unavailable;

        Result(List<Row> rows, boolean unavailable) {
            this.rows = rows;
            this.unavailable = unavailable;
        }
    }

    private JellyfinChannels() { }

    static Result load(Context context) {
        List<Row> rows = new ArrayList<>();
        boolean unavailable = false;
        try {
            Row next = new Row(context.getString(R.string.jellyfin_continue));
            readPrograms(context, TvContract.WatchNextPrograms.CONTENT_URI, next);
            if (!next.items.isEmpty()) rows.add(next);
        } catch (RuntimeException ignored) {
            unavailable = true;
        }
        try {
            Map<Long, Row> channels = new LinkedHashMap<>();
            Uri uri = owned(TvContract.Channels.CONTENT_URI);
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{"_id", "package_name", "display_name", "type"}, null, null, null)) {
                int scanned = 0;
                while (c != null && c.moveToNext() && scanned++ < 100) {
                    if (PACKAGE.equals(c.getString(1)) && "TYPE_PREVIEW".equals(c.getString(3))) {
                        channels.put(c.getLong(0), new Row(c.getString(2)));
                    }
                }
            }
            for (Map.Entry<Long, Row> entry : channels.entrySet()) {
                if (rows.size() >= 7) break;
                Uri programs = TvContract.PreviewPrograms.CONTENT_URI.buildUpon()
                        .appendQueryParameter("channel", String.valueOf(entry.getKey())).build();
                readPrograms(context, programs, entry.getValue());
                if (!entry.getValue().items.isEmpty()) rows.add(entry.getValue());
            }
        } catch (RuntimeException ignored) {
            unavailable = true;
        }
        return new Result(rows, unavailable);
    }

    private static Uri owned(Uri uri) {
        return uri.buildUpon().appendQueryParameter("package", PACKAGE).build();
    }

    private static void readPrograms(Context context, Uri uri, Row row) {
        // Selection clauses are forbidden to ordinary callers by Android 9's TV provider.
        try (Cursor c = context.getContentResolver().query(owned(uri),
                new String[]{"package_name", "title", "short_description", "poster_art_uri",
                        "thumbnail_uri", "intent_uri"}, null, null, null)) {
            int scanned = 0;
            while (c != null && c.moveToNext() && scanned++ < 300 && row.items.size() < MAX_ITEMS) {
                if (!PACKAGE.equals(c.getString(0)) || c.isNull(1) || c.isNull(5)) continue;
                row.items.add(new Item(c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5)));
            }
        }
    }
}
