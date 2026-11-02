package com.aziz.thorclips;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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

    // Visible app windows per display, topmost first, one per line:
    // "displayId|package|windowType". Used to tell which screen a game is on.
    String windowSnapshot() = 4;

    // ---- Recorder running inside this (shell) process, so it survives low memory ----
    // Returns 0 on success, -1 on failure (see recorderStatus()).
    int startRecording(in Bundle config) = 5;
    void stopRecording() = 6;
    // "state=recording;live=BOTH;layout=1280x1440;codec=...;game=...;err=..."
    String recorderStatus() = 7;
    // Writes the last N seconds (video only) to fd; returns start time in microseconds or -1.
    long saveClip(in ParcelFileDescriptor fd, int seconds) = 8;
    boolean screenshot(in ParcelFileDescriptor fd) = 9;
    // Helper log lines since the last call.
    String takeLog() = 10;
}
