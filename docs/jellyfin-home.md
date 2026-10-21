# Jellyfin-first Home

This fork adds `com.gathin.tvhome/.JellyfinHomeActivity`. It puts Jellyfin's content shelf and
full-screen artwork first, with other apps below. The original launcher remains under **All apps**.
The original MIT licence and upstream history are retained.

## Content and artwork

- Reads Jellyfin's Android TV preview channels and Watch Next entries, not your account files.
- Select a content category above the shelf; use the remote to focus a title and press OK to open it
  in Jellyfin. Continue watching and next-up entries share a shelf, as published by Jellyfin.
- Requests full-screen backdrops through Jellyfin's own image provider. This uses the same item's
  `Images/Backdrop/0` endpoint when the published image identifies an item. Missing or inaccessible
  backdrops leave the published image visible; portrait posters become a soft backdrop. Published
  thumbnails are tried before posters, and a failed thumbnail does not suppress the poster. Remote
  image-pipe reads are bounded to eight seconds, with two workers and a 12 MiB memory cache.
- No Jellyfin server address, login, or API key is configured in this launcher. It has no Internet
  permission; Jellyfin handles image fetching using its existing session. Nothing is sent to an
  analytics service. Image URLs and media titles are not written to logs or disk caches.
- Only Jellyfin's rows are read. The launcher does not write to the TV listings provider.

Choose **Allow TV listings** and approve the read-only permission prompt. Use your normal Jellyfin
profile, then return Home and select **Refresh**. Enable home-screen integration in Jellyfin if your
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
