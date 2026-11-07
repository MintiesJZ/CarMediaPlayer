# Testing with the car's own apps

Some tests run other apps' own code against this app, converted from their
APKs with dex2jar when the tests run.  That code isn't ours, so it's never in
this repo: each test is skipped unless it's given the APK (as in the release
workflow).

- `LauncherWheelTest` — the head unit's launcher: its steering-wheel handling
  (SwcManager, ModeManager, VolumeManager, SAIC's car API) and its now-playing
  tile's model (MediaModel), so the wheel's keys and the tile's buttons are
  handled exactly as in the car.  With SmartTube given as well, its last tests
  have a video open in SmartTube.
- `LauncherStartupTest` — the launcher bringing back the last source as the
  car starts (ScreenStateMachine, LastModeManager, SAIC's CommandParcelable).

## The launcher

It comes from the head unit's firmware update (tested with
1100 SWI131 R27 09.08.24, a full A/B OTA zip):

```
unzip <update>.zip payload.bin
python3 tools/firmware/payload_extract.py payload.bin system system.img
debugfs -R 'rdump <launcher folder> <out-dir>' system.img
```

The launcher is `Launcher_eh32small_l.apk`, in a folder of its own with the
other system apps (`debugfs -R 'ls <folder>' system.img` lists a folder).
Keep the APK outside the repo, and delete the update and the images.

## SmartTube

The YouTube app used in the car: its APK as installed (tested with 32.71,
`org.smarttube.stable`).  Its player (ExoPlayer 2.10, which takes and gives up
the speakers itself) and its media session run as its own code, without its
screens; the "video" is a song from the made-up Navidrome.

## Running

```
cd tools/buildcheck
FIRMWARE_LAUNCHER_APK=/path/to/Launcher_eh32small_l.apk SMARTTUBE_APK=/path/to/smarttube.apk \
    gradle :app:testDebugUnitTest --rerun --tests '*Launcher*'
```

- Gradle converts the APKs into `app/build/launcher/` and `app/build/smarttube/`
  (ignored by git).  `--rerun`: Gradle doesn't notice the environment.
- `LAUNCHER_LOG=1` prints the apps' logs.
- `ApkCode` loads each app's classes as its own copies (as in its own
  process), works out the stack map frames dex2jar leaves out, and stands in
  empty for SAIC's additions to Android.
- What the rest of the car would do is stood in for: Android connecting apps'
  media sessions (`SessionBridge`: commands reach a session on the main
  thread; its state and metadata reach whoever watches it), passing the
  speakers between apps (`AudioFocusStack`), and delivering startService
  intents between apps (as parcels, so SAIC's CommandParcelable and the app's
  copy must agree); SAIC's car audio service (checked against the firmware's
  own interface codes); the launcher's home screen and the car's power
  ("ready" events, at a typical moment).  The other apps' work threads are
  the main thread here.
