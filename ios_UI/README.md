# SpektraLab for iPhone and iPad

The Mac app's engine and film library on iOS 18 and later. One app for iPhone
and iPad, in English and Simplified Chinese.

## What it is

| | |
|---|---|
| Engine | `engine/src`, the same C++ the Mac app compiles in, on the same **Metal** path. iOS has Metal, so there is no MoltenVK and no second backend. The C surface (`spk_engine.h`) is unchanged. |
| Kernels | `engine/src/shaders/*.metal`, compiled at build time for the device or the simulator by `SpektraLab/Tools/bundle-engine.sh`, with the Mac's safe-math flags (RFC-014 §5.1 trap 1). |
| Data | `engine/resources/` (spektrafilm's colour stocks, constants, print LUTs) plus `engine/resources_product/profiles/` (the black-and-white films and Multigrade paper), copied into one `profiles/` folder as `engine/build.sh bundle` does for the Mac. |
| Catalogue, covers, licences | Read from the Mac app's `Resources/`, so the two apps cannot list different stocks. |
| Language | A Swift string table (`Localization/Strings.swift`) switched live from Settings, as on the Mac, reusing the Mac's Chinese terms. The one string iOS shows itself (the Photos prompt) is in `*.lproj/InfoPlist.strings`. |

### What the phone has of the Mac's editor

- **Open** from Photos (the original file, so ProRAW arrives as its DNG) or from Files: RAW, ProRAW, HEIC, JPEG, PNG, TIFF.
- **Film**: every colour negative, slide, black-and-white and cine stock in the catalogue, with covers; film format (110, APS, 135, 120, Super 8, 16mm, Super 16, Super 35).
- **Print**: every paper that can print the film (silver for B&W), the declared pairing marked, No Print Profile, and the enlarger (print brightness, yellow and magenta in CC).
- **Camera**: the five metering modes with the meter's reading, film exposure, white balance (RAW: the decoder's own; other files: a correction on top).
- **Effects**: grain, halation, DIR couplers and glare, each on/off with a strength.
- **Finish**: exposure, contrast, saturation, highlights, shadows, temperature, tint, vignette, applied to the finished print.
- **Export**: JPEG, HEIC or 16-bit TIFF, full size or 4096 / 2048 px, saved to Photos or shared. The source's EXIF and GPS are copied across.
- Press and hold the print for the original; pinch and double-tap to zoom.

### Not yet on the phone

Scene Placement, film edge and date back, crop and rotate, curves and colour
wheels, the Digital Intermediate, half-frame pairs, a library of many photos
with sidecars, and the settings clipboard. **Finish** uses Core Image filters
with the Mac's control names and ranges, not a port of the Mac's `layer2`
shader, so the same numbers do not give an identical picture on both.

**Memory.** The frame the engine develops is capped (Settings → Develop size,
12 MP by default) because a phone's memory limit is a fraction of a Mac's.
Export's "Full" is that size.

## Building

The project is generated, as the Mac's is:

```bash
python3 ios_UI/SpektraLab/Tools/gen-project.py          # after adding or removing a file
python3 ios_UI/SpektraLab/Tools/gen-project.py --check  # CI runs this
```

The generator is standard-library Python and runs on Windows and Linux too.
**Building needs a Mac with Xcode 26.6**; nothing else in the repository has
to run first, because the build phase compiles the kernels and copies the data.

```bash
xcodebuild -project ios_UI/SpektraLab/SpektraLab.xcodeproj -scheme SpektraLab \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

CI (`.github/workflows/ios.yml`) builds for the simulator and, unsigned, for a
device on a GitHub macOS runner, and uploads the device build as
`SpektraLab-iOS-unsigned.ipa`.

## Getting it onto an iPhone

Every route needs the app **signed** by an Apple ID; which one decides how long
an install lasts.

| Route | Needs | Lasts |
|---|---|---|
| Xcode on a Mac, free Apple ID | a Mac, a cable, Signing tab → your Personal Team, and a bundle id you own (`SPEKTRALAB_IOS_BUNDLE_ID` in the generator, or edit it in Xcode) | 7 days, then reinstall |
| Sideload the CI `.ipa` from Windows (AltStore, Sideloadly) | the unsigned `.ipa` from the iOS workflow's artifacts, iTunes/iCloud for Windows, a free Apple ID | 7 days, then refresh |
| Apple Developer Program ($99/yr) | the membership, a team id (`SPEKTRALAB_IOS_TEAM_ID`), then Xcode or a signed CI job | a year (development) or TestFlight / App Store |

TestFlight and the App Store need the paid membership, an App Store Connect
record, an archive signed with a distribution certificate, and App Review. A
GitHub Actions job can do the signing and the upload once the certificate,
profile and an App Store Connect API key are added as repository secrets.
