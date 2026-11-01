package com.aziz.thorclips;

import android.view.Surface;

// Runs inside a Shizuku (shell-privileged) process. Its only job is to mirror
// a physical display into a Surface that the app owns.
interface ICaptureService {
    // Reserved transaction code Shizuku uses to tear the service down.
    void destroy() = 16777114;

    // Returns a token >= 1 on success, or -1 on failure (see info()).
    int startMirror(int displayId, in Surface surface, int width, int height) = 1;

    void stopMirror(int token) = 2;

    // Diagnostics: uid, SDK level and the log of what was tried.
    String info() = 3;
}
