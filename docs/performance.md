# Native test-channel performance

This is the Android/Fire TV app, not the Replit web scaffold. The reported device
identifies as Amazon AFTSS; its Fire OS/API and retail generation have not been
verified. Do not infer decoder capability or claim a measured speedup from that label.

## Delivery identity and shrinking decision

The test delivery stays `fullDebug`, non-debuggable, package `com.nuviodebug.com`,
version code 1059 (previous delivery 1058), using the permanent test signing
certificate. Existing dev/login/service configuration, features and the
`Magichouse227/NuvioTV` test updater are unchanged.

R8/resource shrinking was evaluated but is **not enabled for this delivery**.
Standard `fullRelease` is not a substitute: it changes the package, backend
configuration, fork-test flag and updater channel.

The existing optimized ProGuard rules retain Moshi/DTO/Retrofit models, QuickJS
bindings, CloudStream's dynamically loaded API/dependencies, Media3/ExoPlayer,
allocator/sample-queue JNI, MPV callbacks and TorrServer. The broad keeps limit
potential gains. Kotlinx serialization's generic serializer member rule alone
is not evidence that every generated/polymorphic serializer remains reachable.
An optimized test build needs real runtime checks of authentication, persisted
models, plugins/extensions, JNI allocation, MPV, ExoPlayer/FFmpeg, torrents,
external players and NNTP before enabling it. No Android/Fire TV runtime was
available here, so speculative shrinking is not shipped.

## Startup and browsing changes

Home can display its cached/placeholder shell once authentication and the active
profile's local layout/content are resolved. It does not wait for optional rich
metadata, continue-watching enrichment or a full remote startup sync. A cache
from a different profile cannot satisfy that readiness gate.

Optional sync, rich metadata and TV-channel work wait for an input on a safe
Home shell, with an eight-second foreground fallback for users who do not press
a button or launch directly into content. Normal sync is not disabled. Addon
manifest requests share a maximum of three in flight, including overlapping
refreshes. Devices classified as low-RAM by Android avoid speculative offscreen
row/artwork loading and explicit hero offscreen buffers. No retail model name
is used to select the behavior.

**Settings > Advanced > Performance & navigation > Reduce Home effects** controls
hero artwork crossfades, explicit offscreen buffers and speculative row/artwork
prefetch together. It defaults to the existing device-memory policy. Switching
it on or off saves an explicit override; off restores normal effects/prefetch
even on a low-RAM device. It does not reset the other settings below.

Existing controls remain available:

- **Settings > Advanced > RGB565 image decoding** trades some artwork color
  precision for lower bitmap memory use; it does not change playback resolution.
- **Settings > Layout > Focused Poster > Expand Focused Poster to Backdrop**
  and **Autoplay Trailer** can be turned off to reduce browsing work.
- **Settings > Layout > Modern Sidebar** blur and **Card Depth > Enable depth
  effect** can be turned off if animations/effects feel sluggish.
- **Fast Horizontal Navigation** and **Nuvio Focus Scrolling** remain available
  in Advanced; keep whichever focus behavior works best with the remote.

Preferences are not reset. All sources, playback engines and playback-quality
choices remain available. Useful caches and app data are not cleared.

## Timing evidence

The fixed `NuvioStartupTiming` logcat tag records bounded, process-local monotonic
timing markers. It does not send timing data to a service or include account,
profile, title, URL or device identifiers. First draw is not proof that Home is
usable; compare it with profile-scoped shell readiness and interaction readiness.

On a suitable connected device, collect several cold starts before and after
installing the update, with the same account, profile, addon set, cache state and
network. Use force-stop/relaunch rather than clearing data or caches. Separate
offline, warm-cache and slow-addon cases. Record the real device OS/API. Inspect
`adb logcat -s NuvioStartupTiming:I` alongside `adb shell am start -W` launch
times and manually verify D-pad focus, returning from details and playback.
Do not report device improvements without those measurements.

## Release checks

The established signed workflow builds fullDebug. The native Gradle build
requires rebuilding NNTP for arm64-v8a, armeabi-v7a, x86 and x86_64 in CI and
runs focused Kotlin regressions through `testFullDebugPerformance`, independently
of the workflow's existing updater/diagnostic test selectors.
Before publishing, `scripts/verify-test-apk.py` verifies:

- APK signature and permanent test certificate.
- Exact test package, non-debuggable flag and non-downgrade version.
- Full build SHA in DEX.
- All four native ABIs and correct ELF architectures.
- Packaged NNTP bytes match this run's native packaging output.

The build writes `verification.json` beside the APK and JUnit XML to the test
results directory. The existing workflow uploads only the APK; verification
metadata is also printed in its build log. The GitHub connection can write
source but not workflow files, so the established workflow is unchanged.
Check the live head/run and fresh release asset digest at delivery time:
PR and manual builds in this workflow have different concurrency groups and
can otherwise replace the same release asset in completion order.
The Downloader-compatible asset remains:
https://github.com/Magichouse227/NuvioTV/releases/download/nntp-testing/NuvioTV-NNTP-test.apk

Compilation, unit/native regression tests, signature and package checks are
source/build evidence, **not** hardware navigation/playback or speed measurements.