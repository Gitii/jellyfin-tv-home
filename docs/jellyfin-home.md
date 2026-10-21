# Jellyfin-first Home

This fork adds `com.gathin.tvhome/.JellyfinHomeActivity`. It reuses the original clock/header,
rounded app tiles, white focus glass and focus animation. Featured Jellyfin content comes first,
its libraries second, and the original configured app grid below. Hold **Settings** to reach the
original launcher and its app-layout settings.
The original MIT licence and upstream history are retained.

The original spring highlight, title fade/slide, and glow transitions are preserved. Rows scroll
smoothly; reduced-motion and disabled-animation choices still apply. Featured artwork uses a short
fade-out/fade-in instead of snapping. The small glow texture is dithered to reduce dark-gradient
banding, and the extra full-screen gradient overlay is removed. This cannot correct banding already
present in an image or introduced by the TV's panel processing.

## Content and artwork

- Reads Jellyfin's Android TV preview channels and Watch Next entries, not your account files.
- Use Left/Right to browse featured content or libraries, Down to move between rows, and OK to open
  the selected title or library directly in Jellyfin. Featured content starts with Watch Next entries
  and fills from other published channels, deduplicated by item ID. Libraries are identified by
  Jellyfin's published `ItemIsUserView` metadata, not translated channel names.
- Requests full-screen backdrops through Jellyfin's own image provider. This uses the same item's
  `Images/Backdrop/0` endpoint when the published image identifies an item. Missing or inaccessible
  backdrops fall back to landscape thumbnail artwork, then the original glow. Libraries and apps
  always use the glow; pending featured-image callbacks cannot paint over those rows. Library tiles
  use Jellyfin's actual published image. Library backdrops and the screensaver are not changed.
- Backdrop requests use 1920px-wide sources at quality 90. The Sony Android 9 interface currently
  exposes a 1920×1080 app surface despite its 4K panel: decoding matches that actual surface, avoiding
  both the old 1280px request cap and unnecessary 4K image conversions/bitmap allocations. Source images with less
  detail cannot be made sharper by requesting a larger size.
- Immediate previous/next featured backdrops are prefetched; identical requests are coalesced and
  focused artwork takes priority over queued work. Two workers use a 26 MiB backdrop cache plus a
  6 MiB thumbnail cache. Cache entries survive opening another app, but are released under memory
  pressure or activity destruction. Image-pipe reads are bounded to eight seconds.
- Queue slots are reserved for visible images and focused backgrounds; speculative prefetches can
  be evicted. Obsolete focus/tile work is discarded, while current visible requests wait for capacity
  instead of being reported as missing images. App ordering and theme configuration are re-read on
  refresh/return from the original launcher.
- No Jellyfin server address, login, or API key is configured in this launcher. It has no Internet
  permission; Jellyfin handles image fetching using its existing session. Nothing is sent to an
  analytics service. Image URLs and media titles are not written to logs or disk caches.
- Only Jellyfin's rows are read. The launcher does not write to the TV listings provider.

Choose **Allow TV listings** and approve the read-only permission prompt. Use your normal Jellyfin
profile, then return Home. Hold **Settings** and select **Refresh** to reload explicitly. Enable home-screen integration in Jellyfin if your
version provides that setting; version 0.19.10 publishes automatically. Jellyfin controls which items
are published and when they update. Android TV
may limit what third-party launchers can read; an empty shelf is not proof that the server is empty.
The fallback message leaves the app shortcuts usable.

## Build

Requires JDK 17 and Android SDK platform/build tools 34. From `launcher/`:

```fish
env JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew testDebugUnitTest assembleRelease
```

The default build uses a local Android debug signing identity, matching upstream's build approach.
To upgrade an existing installation without losing its settings, use its existing signing identity:
set `TVHOME_SIGNING_STORE`, `TVHOME_SIGNING_ALIAS`, and `TVHOME_SIGNING_PASSWORD` in the build
environment. Never commit signing material, device configuration, or credentials.

## Select Home and recover

Install the signed APK with `adb install -r`, start the new activity, and verify that it starts before
changing the default. Use the TV's default-home picker, or:

```fish
adb shell am start -W -n com.gathin.tvhome/.JellyfinHomeActivity
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.JellyfinHomeActivity
```

To return to the original clean launcher's UI, select:

```fish
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
adb shell am start -W -n com.gathin.tvhome/.MainActivity
```

No uninstall is required. Use a trusted network and disable debugging after checking the result.
The inherited `build-and-install` scripts still select the original activity; use the explicit
commands above for this fork's Jellyfin-first Home.

## Baseline

The `installed-cleanmytv-2.4` tag marks upstream commit
`fd1ee0a94cada6819660c245c343d167e48365c0`, used by CleanMyTV's bundled launcher.
This feature branch starts from upstream's newer 2.5 source while preserving that exact baseline.

## Screensaver follow-up

Jellyfin Android TV 0.19.10 already declares
`org.jellyfin.androidtv/.integration.dream.LibraryDreamService`, a screensaver that shows series and
movies from its libraries. Prefer evaluating it in the TV's screensaver settings before writing
another integration. This change does not switch the configured screensaver or start a dream.
