# EchoMap 3D (experimental)

Camera-free acoustic echo mapping prototype for Android. Uses phone speaker, microphone and rotation-vector sensor. Produces a directional echo-candidate cloud and CSV export. **It does not reliably reconstruct walls, doors or furniture, and is not a true SLAM system.** Single-phone acoustic range estimates are noisy, depend on hardware and audio latency, and may fail completely on some phones. The phone's position is assumed stationary during each scan; rotate in place.

## Get an APK without Android Studio
1. Create a new GitHub repository.
2. Upload **the contents of this folder** to the repository root (including `.github/workflows/build.yml`).
3. Open GitHub **Actions → Build Android APK → Run workflow** (or push to `main`).
4. When finished, open the run and download the **EchoMap3D-debug-apk** artifact; unzip and install `app-debug.apk` on Android.

## Local build
Open the folder in Android Studio (JDK 17), allow Gradle sync, then Build → Build APK(s).

## Permissions
Only RECORD_AUDIO. No camera, location, network, Bluetooth or storage permission. CSV is written to the app-specific external-files directory.

## Hardware notes
The emitted chirp is 17.5–19.5 kHz, potentially audible to some people and animals. Keep volume low. Not suitable for environments where high-frequency sound is undesirable. Audio hardware may filter the chirp. Avoid Bluetooth headphones. Results are experimental.
