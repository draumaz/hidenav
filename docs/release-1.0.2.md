# HideNav v1.0.2

This release focuses on **reliability** and **gesture smoothness** since v1.0.1—keeping HideNav alive in the background, fixing Circle to Search breakage after duplicate Shizuku daemons, and removing unnecessary screen captures on swipe gestures.

## What's Changed

### Reliability & Background Survival
- **Special-use foreground service (`KeepAliveService`)**: Keeps HideNav's process at perceptible priority even when the overlay is hidden (lock screen, screen off, or apps that hide non-system overlays). No notification permission prompt is needed on Android 13+.
- **Automatic battery / background exemption**: Through Shizuku, HideNav now automatically whitelists itself from Doze / App Standby (`deviceidle whitelist`) and allows `RUN_ANY_IN_BACKGROUND` / `RUN_IN_BACKGROUND` so Adaptive Battery stops demoting and killing it.
- **Hardened app respawn watchdog**: If Android still kills HideNav, the shell service now restarts it via `am start-foreground-service` (falling back to `content call`) and retries with exponential backoff if the app doesn't re-attach within 10 seconds.
- **Shell-side fallback for "Show outside apps"**: If the app process is temporarily gone while "Show outside apps" is enabled, the shell service continues toggling the pill directly on task and keyguard changes so the pill never gets stuck in the wrong state.

### Bug Fixes
- **Fixed Circle to Search stopping after duplicate Shizuku daemons**: Starting a new shell daemon now terminates stale copies of itself (seen with Nightzuku after an OTA), dropping orphaned `DISABLE_HOME` records that prevented Launcher from accepting replayed long presses.
- **Fixed touch zone getting stuck untouchable**: Re-syncs `FLAG_NOT_TOUCHABLE` when re-showing the overlay in case a tap pass-through was interrupted mid-flight.
- **Fixed stale pill-cover flash**: Cancels in-flight async colour samples when returning to the home screen before the cover animation starts.
- **Guarded shell helper commands**: Added a 5-second timeout and proper stream cleanup in `HideNavUserService` so a stalled shell command can't block the service handler thread.
- **Fixed edge-to-edge scrolling in HideNav's own UI**: Disabled the system navigation bar contrast scrim and moved system-bar inset padding inside the scroll container so content scrolls cleanly behind the navigation bar.

### Performance
- **Smoother home & back swipes**: Deferred screen luminance sampling by 20 ms from `ACTION_DOWN` and cancelled it as soon as a touch becomes a swipe, avoiding an extra SurfaceFlinger capture pass at the start of every gesture animation.
- **Lower overhead in the shell service**: Cached reflected framework methods across task/keyguard events and display captures, avoided per-pixel boxing when averaging luminance, and disabled offscreen layer allocation when fading the pill copy.

**Full Changelog**: https://github.com/draumaz/hidenav/compare/v1.0.1...v1.0.2
