# Dual Clips (formerly Thor Clips)

Medal / ShadowPlay-style instant replay for the **AYN Thor**: it keeps the last
30, 60 or 120 seconds of **both screens** (stacked, like a real DS) plus game audio
in memory, and saves a clip when you press **L3 + R3**.

Nothing is written to storage until you save. Clips go to `Movies/DualClips`.

## Install

1. Install **Shizuku** (Play Store) and start it with *Wireless debugging*
   (Developer options). After a reboot, start it again.
2. In **Obtainium**, add this repo's URL. It installs the APK from Releases and
   keeps it updated.
3. Open Thor Clips:
   - **Allow Shizuku access**
   - **Turn on L3 + R3 button** (Accessibility → Thor Clips → on)
   - **Start clip buffer** and accept the screen-capture prompt.

## How it stays light

- Keeps **compressed** HEVC video (H.264 fallback), never raw frames.
- Memory is reserved once and reused, so it never grows during long sessions.
- Uses the hardware video encoder; frames go straight from the screens to it.
- Saving just copies already-encoded video into an MP4, on a low-priority thread.
- Pauses while the screen is off.

## Why Shizuku?

Android only lets normal apps record the main display. Shizuku runs a tiny helper
with shell (adb) rights that can mirror the Thor's bottom screen too.

## Notes

- Some apps block other apps from recording their audio; their clips will be silent.
- The signing key is committed on purpose (personal project) so every build can
  update the previous one in place.
