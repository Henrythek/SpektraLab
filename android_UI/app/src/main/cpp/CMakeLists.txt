# libspektralab.so -- the engine, its decoders and writers, and the JNI layer
# the Kotlin app calls (jni.cpp -> studio.cpp -> spk_engine.h).
#
# The engine is built by its own CMakeLists (engine/CMakeLists.txt, the
# Linux/Windows build) with the Vulkan backend: Android is "not Apple, not
# Windows" there, so it takes the Linux path unchanged. LibRaw and the
# Vulkan-Headers come from engine/setup-deps.sh, pinned, in <repo>/build/deps;
# app/build.gradle.kts runs that script before CMake and passes the paths.
#
# Off Android (a Linux machine with a Vulkan driver or lavapipe), the same
# file builds `studio_smoke`, which drives studio.cpp end to end without a
# phone: `android_UI/Tools/native-smoke.sh`.
cmake_minimum_required(VERSION 3.22.1)
project(spektralab_android LANGUAGES C CXX)

get_filename_component(SPEKTRALAB_REPO "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The engine and LibRaw are several times slower unoptimised, and a debug APK
# is what gets sideloaded: its native code is optimised too.
if(ANDROID)
    set(CMAKE_C_FLAGS_DEBUG "-O2")
    set(CMAKE_CXX_FLAGS_DEBUG "-O2")
endif()

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_NATIVE_RAW ON CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_HOST OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_DESKTOP OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_TEST_DLL OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_REQUIRE_VULKAN ON CACHE BOOL "" FORCE)
add_subdirectory("${SPEKTRALAB_REPO}/engine" engine)

set(host "${SPEKTRALAB_REPO}/engine/host")
add_library(spektralab_studio STATIC
    studio.cpp
    "${host}/image_io.cpp"
    "${host}/output.cpp"
    "${host}/platform.cpp"
    "${host}/stb_impl.cpp"
    "${host}/tiff_ifd.cpp"
    "${host}/white_balance.cpp")
target_include_directories(spektralab_studio PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${host}"
    "${SPEKTRALAB_REPO}/engine/include"
    "${SPEKTRALAB_REPO}/engine/src"
    "${SPEKTRALAB_REPO}/engine/third_party/stb")
target_link_libraries(spektralab_studio PUBLIC spk_image_io spk_libraw spektralab_engine spektralab_core spk_volk)

if(ANDROID)
    add_library(spektralab SHARED jni.cpp)
    target_link_libraries(spektralab PRIVATE spektralab_studio jnigraphics log)
    # Android 15 devices may use 16 KB pages; an ELF aligned for 4 KB only
    # does not load there.
    target_link_options(spektralab PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
else()
    add_executable(studio_smoke tests/studio_smoke.cpp)
    target_link_libraries(studio_smoke PRIVATE spektralab_studio)
endif()
