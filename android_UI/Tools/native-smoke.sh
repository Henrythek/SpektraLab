#!/usr/bin/env bash
# native-smoke.sh -- build the Android app's native core for this Linux
# machine and run tests/studio_smoke.cpp on its Vulkan device (lavapipe on a
# machine without a GPU: `apt install mesa-vulkan-drivers glslang-tools`).
#
#   android_UI/Tools/native-smoke.sh [photo...]
#
# The same studio.cpp, decoders, writers and engine the APK carries, built
# with the host compiler instead of the NDK. Prints go to build/android-smoke/out.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
build="$repo/build/android-smoke"
deps="$("$repo/engine/setup-deps.sh" | tail -1)"

cmake -S "$repo/android_UI/app/src/main/cpp" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DSPEKTRALAB_DEPS_DIR="$deps" -DSPEKTRALAB_LIBRAW_SOURCE="$deps/LibRaw-0.22.2" \
  -DSPEKTRALAB_VULKAN_HEADERS="$deps/Vulkan-Headers/include" >/dev/null
ninja -C "$build" studio_smoke

# The engine directory the app unpacks on first launch, assembled as
# app/build.gradle.kts assembles its assets: the baked resources and SPIR-V,
# then the product's black-and-white profiles beside spektrafilm's.
eng="$build/engine-assets"
rm -rf "$eng"
cp -R "$build/engine/resources" "$eng"
cp "$repo/engine/resources_product/profiles/"*.json "$eng/profiles/"
"$build/studio_smoke" "$eng" "$build/out" "$@"
