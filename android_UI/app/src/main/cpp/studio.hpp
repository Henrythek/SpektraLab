// studio.hpp -- one photo on the engine, as the Android app drives it.
//
// The phone's `Engine` actor (ios_UI/.../Engine/Engine.swift) and the parts of
// spektralab-host (engine/host/main.cpp) a phone needs, in one object the JNI
// layer (jni.cpp) wraps. The engine is reached only through its C ABI
// (`spk_engine.h`), on its Vulkan backend -- Android has Vulkan, so this is
// the same backend the Linux and Windows desktop build renders on, and the
// host's own decoders (LibRaw, TIFF, JPEG, PNG) and writers are reused as
// they stand rather than copied.
//
// What differs from the desktop host is only what a phone can afford:
//
//  - **One frame at a time, and capped.** `open_*` scales the developed frame
//    down to `max_pixels` and to what the GPU's storage-buffer range allows
//    (`storage_pixel_cap`), because a phone's memory and its GPU's limits are a
//    fraction of a desktop's.
//  - **The engine renders straight to sRGB.** As the iOS app asks for Display
//    P3, this asks the engine for the display space directly
//    (`output_color_space`), so the engine's own gamut compression does the
//    conversion and `rgba16` is a picture as it stands.
//  - **Finish is applied here.** The iOS app's Layer 2 is Core Image filters on
//    the print; this is the same controls, in the same order, on the linear
//    print (`apply_finish`). Like iOS, it is not the Mac's `layer2` shader.
//
// Not thread-safe: the Kotlin side calls it from one worker thread.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "image_io.hpp"
#include "output.hpp"
#include "spektrafilm/spk_engine.h"

namespace spkandroid {

struct Image8 {
    uint32_t width = 0, height = 0;
    std::vector<uint8_t> rgba;   // sRGB, top row first, alpha 255
};

// Layer 2, never sent to the engine. The iOS app's `Finish`, same ranges.
struct Finish {
    double exposure = 0;     // stops, -3..3
    double contrast = 0;     // -50..50
    double saturation = 0;   // -100..100
    double highlights = 0;   // -100..100
    double shadows = 0;      // -100..100
    double temperature = 0;  // -100..100, blue <-> amber
    double tint = 0;         // -100..100, green <-> magenta
    double vignette = 0;     // -100..100, negative darkens the corners
    bool neutral() const {
        return exposure == 0 && contrast == 0 && saturation == 0 && highlights == 0 && shadows == 0 &&
               temperature == 0 && tint == 0 && vignette == 0;
    }
};

// Linear sRGB, 3 channels, in place. `width`/`height` place the vignette.
void apply_finish(const Finish& f, std::vector<float>& rgb, uint32_t width, uint32_t height);

// The largest frame, in pixels, whose biggest working plane fits a storage
// buffer of `range` bytes. 0 means no limit.
uint64_t storage_pixel_cap(uint64_t range);

class Studio {
public:
    Studio() = default;
    Studio(const Studio&) = delete;
    Studio& operator=(const Studio&) = delete;
    ~Studio();

    // `resources` is the engine directory (baked resources, vulkan/*.spv,
    // profiles/, io/sRGB.icc).
    bool start(const std::string& resources, std::string& error);
    // JSON: build info, GPU, math mode, limits. For Settings and bug reports.
    std::string engine_info() const;

    // Open a file the host's decoders read (RAW, TIFF, JPEG, PNG), replacing
    // the open frame. `wb_json` is the host's `decode.white_balance`
    // (`{"mode":"as_shot"}` or `{"mode":"custom","temperature_k":..,"tint":..}`);
    // for a file that is not RAW a custom balance is a correction on top, as
    // on iOS. `delta_json` is the full wire. `reply` is JSON: size, kind,
    // metadata (with the RAW as-shot balance), the engine's resolved params.
    bool open_file(const std::string& path, const std::string& wb_json, const std::string& delta_json,
                   uint64_t max_pixels, uint32_t preview_edge, std::string& reply, std::string& error);

    // Open 8-bit sRGB pixels the platform decoded (HEIC, WebP, AVIF...),
    // orientation already applied. `meta_json` may carry make, model, lens,
    // iso, shutter_s, aperture, focal_mm and datetime_original for the export.
    bool open_pixels(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t row_bytes,
                     const std::string& meta_json, const std::string& wb_json, const std::string& delta_json,
                     uint64_t max_pixels, uint32_t preview_edge, std::string& reply, std::string& error);

    // Apply `delta_json` (may be "" or "{}") and print the cached negative at
    // the live tier, then finish it. The unfinished print is kept so a Finish
    // change does not go back to the engine (`refinish`).
    bool render(const std::string& delta_json, const Finish& finish, Image8& out, double& engine_ms,
                std::string& error);
    bool refinish(const Finish& finish, Image8& out) const;

    // The photo before the film: the decoder's display rendering.
    const Image8& original() const { return original_; }

    // What the meter would choose (`solve exposure`), without changing it.
    bool metered_ev(double& ev);

    // Render the full tier, finish, scale to `long_edge` (0 = full), encode
    // as `format` (jpeg, png, tiff16) with EXIF and an sRGB profile, write to
    // `path`. `reply` is JSON: width, height, bytes.
    bool export_file(const std::string& path, const std::string& delta_json, const Finish& finish,
                     const std::string& format, uint32_t long_edge, int quality, std::string& reply,
                     std::string& error);

    void close();
    bool is_open() const { return session_ != nullptr; }

private:
    bool open_frame(spkhost::FloatImage& frame, const std::string& delta_json, std::string& reply,
                    std::string& error);
    bool set_params(const std::string& delta_json, std::string& error);
    bool print(const char* tier, spk_result& out, std::string& error);
    std::string last_error() const;
    uint64_t frame_cap(uint64_t requested) const;

    spk_engine* engine_ = nullptr;
    spk_session* session_ = nullptr;
    std::string resources_;
    spkhost::OutputTransform transform_;   // sRGB -> sRGB: decode and encode only
    uint64_t storage_range_ = 0;
    uint32_t max_texture_ = 0;
    std::string gpu_name_, math_mode_;

    spkhost::Metadata metadata_;
    spkhost::FileKind kind_ = spkhost::FileKind::Unknown;
    Image8 original_;
    std::vector<float> live_;   // the last live print, linear sRGB, unfinished
    uint32_t live_w_ = 0, live_h_ = 0;
};

}  // namespace spkandroid
