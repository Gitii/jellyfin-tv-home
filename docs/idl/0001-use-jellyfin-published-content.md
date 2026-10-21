# Use Jellyfin's published content for Home

Status: accepted 2026-10-21

## Context

The owner primarily uses Jellyfin and wants show/movie artwork and direct entry into that app,
rather than prominent commercial streaming-app tiles. The target is an Android 9 Sony TV.
The existing launcher has an app dock, but not Android TV preview-channel rendering.

## Decision

Add a separate Jellyfin-first Home activity, keeping the original launcher accessible as All apps.
Read only Jellyfin's searchable preview and Watch Next listings with the read-only READ_TV_LISTINGS
runtime permission. Ask explicitly before reading; app shortcuts remain usable if denied.
Do not request privileged EPG access, root, or the user's Jellyfin credentials.
Open items with Jellyfin's published intents, restricted to Jellyfin's package.

Request artwork through Jellyfin's exported image provider. For the known item-image endpoint,
try the same item's backdrop on the same server, discard the poster's image tag and size parameters,
and retain the original origin and other query parameters. Fall back to published artwork; soften
portrait posters instead of stretching them into full-screen backdrops. Cache images in memory only.

## Alternatives and consequences

A direct Jellyfin API integration could provide independently ordered libraries and complete
backdrop metadata, but would introduce account pairing, token storage, and a second client session.
Native integration avoids those requirements, but depends on the app publishing listings and the
TV exposing them. Backdrop availability must be checked on a real device; it is not guaranteed by
the preview-channel contract. An empty or restricted provider must leave Jellyfin and other apps
launchable with a clear explanation.

The separate activity avoids disturbing the original launcher's app-grid navigation and provides
a local fallback, at the cost of two explicit Home candidates. Installation must select the new
activity, not rely on Android picking a default.

Jellyfin already includes a library-artwork DreamService. Evaluate that existing screensaver before
building another one. Screensaver changes are deferred until the launcher works on the target TV.
