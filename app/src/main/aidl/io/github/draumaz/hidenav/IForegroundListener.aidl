package io.github.draumaz.hidenav;

// Implemented in the app; the shell service calls it when what's in front changes.
oneway interface IForegroundListener {
    // True while an app is in front: not the home screen, Recents or the lock screen.
    void onAppInFrontChanged(boolean appInFront);
}
