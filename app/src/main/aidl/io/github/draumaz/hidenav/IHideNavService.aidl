package io.github.draumaz.hidenav;

import io.github.draumaz.hidenav.IForegroundListener;

// Runs inside Shizuku as the shell UID (2000). See shell/HideNavUserService.kt.
interface IHideNavService {
    // Reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Hides or shows the gesture pill by holding SystemUI's DISABLE_HOME flag.
    void setPillHidden(boolean hidden) = 1;
    boolean isPillHidden() = 2;

    // Replays a long-press on the nav handle so Launcher opens Circle to Search,
    // lifting the flag just long enough for Launcher to accept the touch.
    oneway void longPressHandle(float x, float y) = 3;

    // Forwards a tap the touch zone swallowed to whatever sits underneath it.
    oneway void tap(float x, float y, long delayMs) = 4;

    // Grants this app "display over other apps" so the touch zone can be shown.
    boolean grantOverlayPermission(String packageName) = 5;

    // Average luminance (0..1) of a screen rectangle, or -1 if it can't be captured.
    // Lets the touch zone pick the same light/dark pill colour Launcher would.
    float sampleLuminance(int left, int top, int right, int bottom) = 6;

    // Starts (or with null, stops) reporting whether an app is in front, for the
    // "show outside apps" setting. Reports the current state straight away.
    void setForegroundListener(IForegroundListener listener) = 7;
}
