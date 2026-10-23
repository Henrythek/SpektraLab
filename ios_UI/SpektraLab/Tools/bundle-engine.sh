#!/bin/sh
# bundle-engine.sh -- a build phase of the iOS app: the engine's Metal kernels,
# compiled for the platform being built, and its data, copied into the bundle.
#
# Why this is a build phase here and a separate step on the Mac
# (`engine/build.sh bundle`): a metallib carries the platform it was compiled
# for, and an iOS build is for the device *or* the simulator. One prebuilt
# library cannot serve both, so the kernels are compiled for `$PLATFORM_NAME`
# every time the app is.
#
# The two math flags are RFC-014 §5.1 trap 1, copied from `engine/build.sh`
# where the reason is written down: Metal's default is fast math, under which
# `exp` and fma contraction drift past the float32 bar, and
# `spk_engine_create` refuses a library built that way (`spk_math_probe`).
# Change them there and here together.
#
# Layout in the bundle (what `Engine.resourcesURL` looks for):
#
#   engine/spektrafilm.metallib
#   engine/spektrafilm_constants.bin, print_luts.json, neutral_print_filters.json, io/
#   engine/profiles/*.json            spektrafilm's colour stocks + the product's B&W ones
#   engine/PRODUCT_PROFILES_NOTICE.md
#   StockCatalog.json, FilmCovers/    from the Mac app, one catalogue for both
#   Licenses/                         from the Mac app, the texts the bundle must carry
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
repo=$(CDPATH= cd -- "$here/../.." && pwd)
engine="$repo/engine"
mac_res="$repo/modern_UI/Spektrafilm/Spektrafilm/Resources"

: "${TARGET_BUILD_DIR:?run from Xcode}"
: "${UNLOCALIZED_RESOURCES_FOLDER_PATH:?run from Xcode}"
: "${PLATFORM_NAME:?run from Xcode}"
dest="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
work="${DERIVED_FILE_DIR:-${TMPDIR:-/tmp}}/spektralab-kernels"
min="${IPHONEOS_DEPLOYMENT_TARGET:-18.0}"

case "$PLATFORM_NAME" in
  iphoneos)        target_flag="-mios-version-min=$min" ;;
  iphonesimulator) target_flag="-mios-simulator-version-min=$min" ;;
  *) echo "error: bundle-engine.sh: unexpected platform $PLATFORM_NAME" >&2; exit 1 ;;
esac

if [ ! -f "$engine/resources/spektrafilm_constants.bin" ]; then
  echo "error: $engine/resources is missing; it is tracked, restore it with git checkout -- engine/resources" >&2
  exit 1
fi

mkdir -p "$work" "$dest/engine/profiles"

# --- kernels -----------------------------------------------------------------
airs=""
for m in "$engine"/src/shaders/*.metal; do
  air="$work/$(basename "$m" .metal).air"
  xcrun -sdk "$PLATFORM_NAME" metal -fmetal-math-mode=safe -fmetal-math-fp32-functions=precise \
    "$target_flag" -Werror -c "$m" -o "$air"
  airs="$airs $air"
done
# shellcheck disable=SC2086  # the .air paths are under DerivedData and have no spaces
xcrun -sdk "$PLATFORM_NAME" metallib $airs -o "$dest/engine/spektrafilm.metallib"

# --- data --------------------------------------------------------------------
for f in spektrafilm_constants.bin print_luts.json neutral_print_filters.json; do
  cp "$engine/resources/$f" "$dest/engine/$f"
done
rm -rf "$dest/engine/io"
cp -R "$engine/resources/io" "$dest/engine/io"
cp "$engine/resources/profiles/"*.json "$dest/engine/profiles/"
# The product's black-and-white films and paper go beside spektrafilm's,
# because the engine opens a stock from one `profiles/` directory -- exactly as
# `engine/build.sh bundle` does for the Mac app. The notice travels with them.
cp "$engine/resources_product/profiles/"*.json "$dest/engine/profiles/"
cp "$engine/resources_product/NOTICE.md" "$dest/engine/PRODUCT_PROFILES_NOTICE.md"

cp "$mac_res/StockCatalog.json" "$dest/StockCatalog.json"
rm -rf "$dest/FilmCovers" "$dest/Licenses"
cp -R "$mac_res/FilmCovers" "$dest/FilmCovers"
cp -R "$mac_res/Licenses" "$dest/Licenses"

echo "bundle-engine: $PLATFORM_NAME kernels and $(ls "$dest/engine/profiles" | wc -l | tr -d ' ') profiles -> $dest"
