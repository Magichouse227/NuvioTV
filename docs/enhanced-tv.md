# Enhanced features for NuvioTV

This work adapts the features described by
[NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced/tree/51f51b332f46b8cb64eb9e665238bb16761f204a)
to the native Android TV interface in this fork. Credit goes to NuvioMedia,
luqmanfadlli and their contributors. The repository's GPL-3.0 license applies.

## Device target

Amazon Fire TV Stick HD, first generation (2024), model AFTSS, Fire OS 7:
Android API 28, 1 GB RAM, 32-bit ARM. This is the HD model, rather than the
original 2014 Fire TV Stick. No device identifiers or account details are needed.

The lightweight policy caps artwork memory at 16 MiB, artwork disk cache at
64 MiB, concurrent image requests at six and bitmap decoders at one. Playback
buffers are capped at 48 MiB (32 MiB on the stock path), and parallel chunk
sessions are disabled on this tier. Animated image decoders are omitted there.
Existing trailer preferences are retained; new profiles start with autoplay off.

## Implementation status

| Feature | Current status |
| --- | --- |
| M3U URL and file playlists, Xtream, Stalker | Added, with profile-specific encrypted configuration |
| Live TV navigation, favorites, filters, last watched | Added |
| Movie budget and revenue | Added through TMDB detail enrichment |
| TMDB episode ratings | Added, labeled separately from IMDb and subject to rating visibility settings |
| Random episode playback | Added; watched episodes excluded by default, unavailable/future episodes excluded |
| More Like This: paged grid | Added for TMDB and Trakt |
| Hero trailer controls and delay | Existing TV controls; autoplay now opt-in for new profiles |
| Audio boost, playback information, subtitle background controls | Existing TV features; final compatibility review pending |
| Custom profile image backgrounds | Existing TV feature |
| Trakt and SIMKL code sign-in | Existing TV integrations; final compatibility review pending |
| Dynamic home background and catalog underline | Preferences prepared; rendering integration pending |
| Profile insights and library calendar | Pending |
| Download network and folder controls | Pending |
| iOS Metal PiP, Liquid Glass tabs, iOS background downloads/Live Activities, Skia implementation | Platform-specific; these implementations cannot run on Fire OS |

## Live TV behavior

Open Settings → Integrations → Live TV to add a source. The navigation entry
appears after a source is configured and can be hidden. File import requires a
Storage Access Framework file picker; a playlist URL works on TVs without one.
Provider configuration is encrypted using an Android Keystore key and is scoped
to the active profile. It must be configured again after restoring to a device
that does not have the key; the settings page provides an explicit reset action.

Browsing loads providers sequentially, caps the combined list at 20,000 channels
and bounds response/playlist sizes. Stalker playback requests a fresh link when
opening a channel. Provider credentials are not sent through diagnostic logging
interceptors. Provider compatibility and real-device remote navigation still need
manual verification with the user's own sources.

## Verification and preview builds

The Enhanced TV verification workflow builds and tests without service or signing
secrets and does not publish a release. The generated debug-signed ARM32 preview
uses a separate application ID, com.nuvio.tv.enhanced.preview, so it can coexist
with the installed app. Service integrations requiring build-time API credentials
are not configured in this preview.

The Live TV and memory-policy revision dcbc172 passed the Android build and its
selected JVM tests in GitHub Actions run 34886462401. Later feature revisions
still require their own passing build. No physical Fire TV performance measurement
or provider playback test has been completed.
