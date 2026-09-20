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
| Audio boost, playback information, subtitle background controls | Existing TV controls retained; real-device validation pending |
| Custom profile image backgrounds | Existing TV feature |
| Trakt and SIMKL code sign-in | Existing device-code integrations retained; credentials needed for end-to-end validation |
| Dynamic home background and catalog underline | Added, opt-in per profile; samples existing artwork with bounded caching |
| Profile insights and library calendar | Added, with bounded metadata loading |
| Download network and folder controls | Excluded by the TV roadmap; no TV download service or queue |
| Video quality chooser | Added for supported ExoPlayer video tracks; automatic mode preserved |
| Pointer timeline seeking and keyboard shortcuts | Added; remote navigation retained while controls are visible |
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

## Combined Enhanced + NNTP app

The Enhanced features are now integrated into `feature/native-nntp-testing`.
The combined build retains the installed NNTP test identity
(`com.nuviodebug.com`), permanent test signing configuration, profile/settings
storage and fork update feed. The 1.0.0 update uses version code 1062. It does not adopt the
separate Enhanced preview package or its debug signing key.

NNTP changes in this integration:

- Provider connections start while the NZB loads; validated connections continue
  to use the existing reusable pool. Failed starts release their provider lease.
- Segment replacement reserves only the extra bytes needed. It cannot evict and
  double-release its own entry, and oversized articles cannot flush the seek cache.
- Known setup failures expose fixed error categories and actionable messages, not
  raw provider responses or private links. Existing indexer cooldowns are preserved.
- Settings → Playback → Stream selection includes NNTP / Usenet setup help.
  Provider credentials are configured in the NZB addon; this is not a separate
  in-app provider editor.
- Existing HTTP Range seeking, resume, fallback and cancellation are retained.
  Pointer seeking and quality selection from Enhanced are included.

The signed build runs the existing updater checks plus the combined Kotlin
regression selections and Go pool/loader/archive/session tests before publishing.
It verifies package identity, permanent certificate, version code, embedded
build SHA and all four freshly compiled NNTP native libraries.

### Official 1.0.0 and TV interface update

The combined source includes official release `1.0.0`, commit
`9f17e8bf4abc799dc8c832d2894a8b3b166e4353`, while retaining this fork's
signing, release workflows, updater, diagnostics, Enhanced features and NNTP engine.

- Top navigation is enabled by default for each profile. It remains visible above
  the main browsing screens and disappears on playback and full-screen detail routes.
  Left/right selects a menu item, OK opens it, and Down returns to content. Back
  returns to the menu after the screen's own Back handling; holding Back jumps to
  the menu. Existing exit confirmation is preserved. Settings → Layout → Top
  navigation restores the sidebar when disabled.
- Profile Insights uses a profile/ avatar banner, three summary counters, six
  overview cards and a Taste DNA panel matching the Enhanced mobile design, with
  landscape spacing and focusable cards for a TV remote. It uses the active theme
  accent, genre bars, movie/series balance, badges and recent history.
- Statistics are scoped to the active profile and deduplicate watched records and
  playback progress. Completing one episode does not complete a series. Ended
  series require all known regular episodes; explicit whole-series marks are honored.
- Watch time is an estimate from recorded progress and known runtimes, excluding
  repeat watches. Missing runtimes and incomplete metadata coverage are shown.
  Upcoming counts use announced dates, including future episodes of saved series;
  a bare release year is not converted into an invented release day.
- Metadata uses the existing cache first, then at most 40 title requests per visit,
  two at a time. Closing Insights or switching profile cancels outstanding work.
  Counts appear before metadata requests complete. No full metadata objects are
  retained by this dashboard.

### Delivery status

The previous combined revision `e178e9f` passed signed build
[35177402523](https://github.com/Magichouse227/NuvioTV/actions/runs/35177402523),
including 302 selected Kotlin tests, and the user confirmed that build runs smoothly.
That result does not certify the new 1.0.0/interface update.

The current source passes five APK-verifier fixture tests, XML resource parsing,
shell syntax and diff-whitespace checks locally. Ten new statistics regression
tests and the upstream playback/subtitle regressions are included in the signed
build's required test selection. Kotlin, Gradle and the Android toolchain are not
installed in the editing environment; the new APK and these JVM tests still need
the GitHub build. Physical TV focus/layout and installation verification remain pending.

Run **PR Full Debug Build** manually on **feature/native-nntp-testing**. After it
passes, its existing signed-release step replaces `NuvioTV-NNTP-test.apk` on
the `nntp-testing` prerelease and then updates the `Build-SHA` marker. Do not
start concurrent publishing runs. Verify the workflow head and release marker
match before distributing the APK.

The installed NNTP app checks that fork feed by build SHA, not just the displayed
version name. Enhanced-only Actions artifacts do not appear on it. Until a newer
signed NNTP/combined build is published, no update prompt is expected.

## Historical Enhanced-only preview

The Enhanced TV verification workflow builds and tests without service or signing
secrets and does not publish a release. The generated debug-signed ARM32 preview
uses a separate application ID, com.nuvio.tv.enhanced.preview, so it can coexist
with the installed app. Service integrations requiring build-time API credentials
are not configured in this preview.

The workflow checks the final APK application ID, ARM32-only native libraries and
minimum Android version against the Fire OS 7 target. The artifact includes
`preview-verification.json` and the JVM XML test reports so the package identity,
commit and test counts can be checked independently. Preview signing keys are
generated by CI; installing a later preview may require uninstalling the previous
preview. Keep the main app installed while testing.

Code revision `0782b88` passed the Android build, 44 selected JVM tests (zero
failures, errors or skipped tests), and the final APK compatibility check in
[GitHub Actions run 35078509152](https://github.com/Magichouse227/NuvioTV/actions/runs/35078509152).
The verified APK is `armeabi-v7a`, requires Android API 24 or newer, and uses
`com.nuvio.tv.enhanced.preview`. This revision includes the appearance and
video-quality changes, corrected preview identity, and remote-focusable Profile
Insights rows. No physical Fire TV performance measurement or provider playback
test has been completed.

## Manual Fire TV checks still required

- Install the combined signed test APK over the existing NNTP app; do not
  uninstall the NNTP app or clear its data. Confirm profiles, addons, watch history
  and preferences remain. The old Enhanced-only preview has separate app storage;
  its settings are not automatically imported.
- Use the remote to move across every top navigation item, enter content with
  Down, return with Back/Up, switch profiles and enter/leave playback. Verify that
  the menu does not overlap the hero or appear over video. Toggle the sidebar
  fallback once and verify settings and navigation remain available.
- In Profile Insights, move through both overview rows, the Taste DNA card and
  recent history beyond the first page. Switch profiles and verify the name,
  avatar and counts change together. Check an empty profile and unavailable metadata.
- Add the user's own M3U, Xtream or Stalker source; check channel search,
  group/source filters, favorites, last watched and playback. Switch profiles
  and confirm that the provider configuration and favorites remain separate.
- With a source offering multiple supported video tracks, choose a quality,
  return to Automatic, and close the dialog. Also check a single-quality source.
- Check NNTP startup, repeated forward/backward seeks, stop/reopen resume, missing
  articles, bad provider credentials, indexer cooldown and cancel/retry behavior.
  Use a release and provider that you are authorized to access.
- Check VOD timeline seeking and the physical-keyboard shortcuts, then verify
  that Live TV is not accidentally treated as a seekable VOD timeline.
- Enable and disable the optional home appearance settings, browse artwork-heavy
  catalogs, and resume playback after returning from the background. Record any
  crashes or focus loss; smoothness and memory improvements are not yet measured.

The combined build retains the NNTP branch's configured service integrations.
TMDB, Trakt, SIMKL and account features still need end-to-end device testing.
Do not put provider passwords, tokens or private playlist URLs in bug reports.
