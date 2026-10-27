# SpektraLab for Android

The engine and film library on Android 10 and later, for arm64 phones and
tablets, in English and Simplified Chinese. The iOS app's feature set
(`ios_UI/`), built the same way: the repository's own engine, not a copy.

## What it is

| | |
|---|---|
| Engine | `engine/src`, the same C++ every other build compiles, on its **Vulkan** backend (`src/gpu/vulkan_gpu.cpp`). Android has Vulkan, so this is the Linux/Windows desktop path unchanged; the C surface (`spk_engine.h`) is the same. |
| Native library | `app/src/main/cpp`: `studio.cpp` drives the engine for one photo (open, print, meter, finish, export), reusing the desktop host's decoders and writers (`engine/host/`: LibRaw for RAW, TIFF/JPEG/PNG with their ICC profiles, the EXIF-carrying writers). `jni.cpp` is the door from Kotlin. Built by the NDK with `engine/CMakeLists.txt`. |
| Kernels | `engine/src/shaders/vulkan/*.comp`, compiled to SPIR-V at build time by `glslangValidator`, the list read from `engine/CMakeLists.txt` so the two cannot drift. |
| Data | `engine/resources/` (spektrafilm's colour stocks, constants, print LUTs) plus `engine/resources_product/profiles/` (the black-and-white films and Multigrade paper) in one `profiles/` folder, as the Mac and iOS bundles have it. Unpacked from the APK to the app's files directory on first launch. |
| Catalogue, covers | The Mac app's `Resources/StockCatalog.json` and `FilmCovers/`, so the three apps cannot list different stocks. |
| UI | Kotlin and Jetpack Compose. The language switches live from Settings (`i18n/Strings.kt`, the iOS table with the Mac's Chinese terms). |

### What the phone has of the Mac's editor

- **Open** from the gallery (the system photo picker) or from Files: RAW and DNG
  (LibRaw), JPEG, PNG, TIFF, HEIC/HEIF, AVIF and WebP. "Open with" and "Share"
  from other apps work too.
- **Film**: every colour negative, slide, black-and-white and cine stock in the
  catalogue, with covers; film format (110, APS, 135, 120, Super 8, 16mm,
  Super 16, Super 35).
- **Print**: every paper that can print the film (silver for B&W), the declared
  pairing marked, No Print Profile, and the enlarger (print brightness, yellow
  and magenta in CC).
- **Camera**: the five metering modes with the meter's reading, film exposure,
  white balance (RAW: LibRaw's own, at decode; other files: a correction on top).
- **Effects**: grain, halation, DIR couplers and glare, each on/off with a strength.
- **Finish**: exposure, contrast, saturation, highlights, shadows, temperature,
  tint, vignette, applied to the finished print.
- **Export**: JPEG, PNG or 16-bit TIFF with an sRGB profile, full size or
  4096 / 2048 px, saved to `Pictures/SpektraLab` or shared. The source's EXIF is
  copied across.
- Press and hold the print for the original; pinch and double-tap to zoom.

### Not yet on the phone

What iOS also lacks (Scene Placement, film edge and date back, crop and rotate,
curves and colour wheels, the Digital Intermediate, half-frame pairs, a library
of many photos, the settings clipboard), plus the four features the Vulkan
backend refuses on every platform (`engine/PORT_PLAN.md`). **Finish** is
`apply_finish` in `studio.cpp` with the Mac's control names and ranges, not a
port of the Mac's `layer2` shader. The canvas is sRGB; there is no Display P3
path yet.

**Memory and GPU limits.** The frame the engine develops is capped (Settings →
Develop size, 12 MP by default), and `studio.cpp` lowers the cap further to what
the phone's GPU can hold in one storage buffer (`maxStorageBufferRange`; on a
GPU that reports 128 MiB that is about 8 MP). Export's "Full" is that size.

**Phone GPUs are untested.** CI renders on Mesa's CPU Vulkan (lavapipe), not on
an Adreno or Mali. At start-up the engine runs its precision probe on the GPU
and refuses a device whose arithmetic it cannot trust; on Android, a GPU with
consistently unfused `fma` is accepted and takes the exact Dekker path the CPU
device uses. If the engine does not start, the error and the GPU's name are on
screen and in Settings.

## Building

CI (`.github/workflows/android.yml`) builds the APK on every push that touches
the app or the engine, and uploads it as the artifact **SpektraLab-android-debug**.
It also runs `Tools/native-smoke.sh`, which builds the same native core for
Linux and prints colour, slide and B&W frames on lavapipe.

By hand you need JDK 17, the Android SDK with NDK 27 and CMake, bash and git (for
`engine/setup-deps.sh`, which the build runs), and `glslangValidator` on the
`PATH` or in `$VULKAN_SDK/bin` (the Vulkan SDK; `apt install glslang-tools` on
Linux). Then:

```bash
cd android_UI
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Android Studio opens `android_UI/` as a project. On Windows, run Gradle from a
shell where `bash` (Git Bash) and `glslangValidator` are on the `PATH`.

```bash
android_UI/Tools/native-smoke.sh [photo...]   # the native core on this Linux machine's Vulkan
```

## Installing on a phone

The CI APK is signed with a debug key, so it installs on any Android phone that
allows apps from outside the Play Store. No developer account is needed.

1. Open the latest **Android** run under the repository's **Actions** tab, and
   download **SpektraLab-android-debug** (a zip holding the `.apk`). Unzip it.
2. Get the `.apk` onto the phone: download it there directly, or copy it over
   USB, or send it to yourself.
3. Tap the `.apk` in the phone's Files app (or the browser's downloads). Android
   asks to allow installing unknown apps from that app; allow it, go back, and
   tap **Install**. Play Protect may warn that the app is unrecognised; choose
   **Install anyway**.

Or from a computer with USB debugging on: `adb install SpektraLab-android-debug.apk`.

Every build is signed with the same debug key (`app/debug.keystore`), so a newer
APK installs over the old one and keeps the app's settings.
