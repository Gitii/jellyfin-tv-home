# Use Jellyfin's published content for Home

Status: accepted 2026-10-21

## Context

The owner primarily uses Jellyfin and wants show/movie artwork and direct entry into that app,
rather than prominent commercial streaming-app tiles. The target is an Android 9 Sony TV.
The existing launcher has an app dock, but not Android TV preview-channel rendering.

## Decision

Add a separate Jellyfin-first Home activity, keeping the original launcher accessible by holding
Settings. After the owner's interactive mock review, reuse the original header layout, tile layout,
tile focus animations, white glass highlight, configured app ordering and native app banners.
Use one horizontal featured row, one horizontal library row, then four-column app rows. Preserve
all published libraries and featured items through horizontal navigation, rather than truncating
them to the four visible slots. No category tabs or large synopsis panel.
Read only Jellyfin's searchable preview and Watch Next listings with the read-only READ_TV_LISTINGS
runtime permission. Ask explicitly before reading; app shortcuts remain usable if denied.
Do not request privileged EPG access, root, or the user's Jellyfin credentials.
Open items with Jellyfin's published intents, restricted to Jellyfin's package.

Request artwork through Jellyfin's exported image provider. For the known item-image endpoint,
try the same item's backdrop on the same server, discard the poster's image tag and size parameters,
and retain the original origin and other query parameters. Only featured content uses artwork;
libraries and apps use the original coloured glow and invalidate pending backdrop updates.
Request 1920px-wide sources at quality 90 to match the actual Android app surface on the target Sony.
The 4K panel exposes only a 1080p app surface; decoding/re-encoding a 4K source inside Jellyfin's image
provider would add CPU work without increasing the final output detail. Do not stretch portrait
posters into backgrounds.

Cache images in memory only: 26 MiB for backdrops and 6 MiB for thumbnails. Coalesce identical
requests and prioritize the focused backdrop over queued work. Prefetch only immediate previous/next
featured backdrops. Retain the cache when opening another app, release it on memory pressure or
activity destruction. No separate library-backdrop requests for now, per the owner's decision.

## Alternatives and consequences

A direct Jellyfin API integration could provide independently ordered libraries and complete
backdrop metadata, but would introduce account pairing, token storage, and a second client session.
Native integration avoids those requirements, but depends on the app publishing listings and the
TV exposing them. Backdrop availability must be checked on a real device; it is not guaranteed by
the preview-channel contract. An empty or restricted provider must leave Jellyfin and other apps
launchable with a clear explanation.

The separate activity avoids disturbing the original launcher's full settings/reordering UI and
provides a local fallback, at the cost of two explicit Home candidates. Installation must select the new
activity, not rely on Android picking a default.

Jellyfin already includes a library-artwork DreamService. Evaluate that existing screensaver before
building another one. Screensaver changes are deferred until the launcher works on the target TV.
