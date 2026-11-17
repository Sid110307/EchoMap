# EchoMap

> Acoustic echo mapping prototype for Android.

EchoMap plays an **audible 4–12 kHz chirp**, listens for a delayed reflection,
and plots approximate range along the top edge of the screen. It needs Android
8 or later, a built-in speaker/microphone supporting 48 kHz audio, and a rotation
vector sensor (game rotation vector is supported as a fallback).

### Scanning

1. Disconnect headphones and Bluetooth audio. Set media volume to a moderate level.
2. Tap START and allow microphone access. Keep both the speaker and mic uncovered.
3. Stay in one spot. Point the **top edge of the screen** at a hard surface
   roughly 0.55–5 m away, then rotate slowly. Keep the phone fairly flat for a
   horizontal sweep; tilting its top edge upward plots higher candidates.
4. Stop before moving to another position. CLEAR starts a new map. The app does
   not track translation, so walking around combines incompatible measurements.

The status distinguishes an undetected chirp, an undetected reflection,
microphone clipping, unsupported audio routing, and initialization errors.
Raise volume gradually if no chirp is detected; lower it if the mic clips.
Soft furnishings, noise, microphone processing and phone speaker response can
prevent usable echoes. A phone's sound beam is broad: points are **echo
candidates**, not verified walls, furniture, or a metric 3D reconstruction.
Distances use 343 m/s and do not compensate for speaker/mic separation. The
game rotation sensor uses a relative heading that can drift; clear the map
after suspending or repositioning the phone.

EXPORT CSV saves x/y/z in metres and relative echo strength to the app's external
files directory. Its full path is shown after export. For a debug installation,
retrieve files with `adb pull /sdcard/Android/data/org.echomap.app/files/`.

### Building and checking

Use JDK 17, Gradle 8.9, Android SDK platform 35 and build tools 35.0.0.
Set `ANDROID_HOME` to your SDK location, then run:

```sh
gradle lintDebug assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs
the same checks and uploads the APK before publishing a build release.

Verify ranging against a measured hard surface on the target device. Compilation
and lint cannot validate a phone's physical audio path.

