// studio.cpp -- see studio.hpp.

#include "studio.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <memory>
#include <thread>

#include "json.hpp"
#include "platform.hpp"
#include "volk.h"
#include "white_balance.hpp"

using spk::Json;
using namespace spkhost;

namespace spkandroid {
namespace {

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

template <typename F>
void parallel_rows(uint32_t rows, F&& body) {
    const uint32_t threads = std::max(1u, std::min(hardware_threads(), 8u));
    if (threads == 1 || rows < 64) { body(0u, rows); return; }
    std::vector<std::thread> pool;
    const uint32_t step = (rows + threads - 1) / threads;
    for (uint32_t r = 0; r < rows; r += step) pool.emplace_back(body, r, std::min(rows, r + step));
    for (auto& t : pool) t.join();
}

Json parse_object(const std::string& text, const char* what, std::string& error, bool& ok) {
    ok = true;
    if (text.empty()) return Json::object();
    Json out;
    if (!Json::parse(text, out, error) || !(out.is_object() || out.is_null())) {
        error = std::string(what) + " is not a JSON object" + (error.empty() ? "" : ": " + error);
        ok = false;
        return Json::object();
    }
    return out.is_null() ? Json::object() : out;
}

Json take_json(char* text) {
    Json out;
    std::string error;
    if (text) {
        Json::parse(text, out, error);
        spk_string_free(text);
    }
    return out;
}

// The display space this app renders into, forced into every open's wire so
// the pixel conventions below cannot drift from what the engine was asked.
void force_output(Json& delta) {
    delta.set("output_color_space", Json(std::string("sRGB")));
    delta.set("output_cctf_encoding", Json(true));
}

// --- colour ----------------------------------------------------------------

using M3 = std::array<double, 9>;
M3 mul3(const M3& a, const M3& b) {
    M3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
    return r;
}
M3 inv3(const M3& m) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    return {A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
}

// Bradford adaptation from white xy `src` to white xy `dst`, in XYZ.
M3 bradford(double sx, double sy, double dx, double dy) {
    const M3 b = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    auto lms = [&](double x, double y) {
        const double X = x / y, Y = 1.0, Z = (1 - x - y) / y;
        return std::array<double, 3>{b[0] * X + b[1] * Y + b[2] * Z, b[3] * X + b[4] * Y + b[5] * Z,
                                     b[6] * X + b[7] * Y + b[8] * Z};
    };
    const auto s = lms(sx, sy), d = lms(dx, dy);
    const M3 scale = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
    return mul3(inv3(b), mul3(scale, b));
}

// A flat file's white balance: a correction on a rendering, as iOS does it
// with CITemperatureAndTint. The file is taken as rendered for D65 (6500 K,
// tint 0); the user's temperature is the light it should have been balanced
// for, so a higher temperature warms it, as it does for a RAW.
void correct_flat_white_balance(std::vector<float>& rgb, double temperature_k, double tint) {
    double sx, sy, dx, dy;
    temperature_tint_to_xy(temperature_k, tint, sx, sy);
    temperature_tint_to_xy(6500, 0, dx, dy);
    const M3 to_xyz = linear_prophoto_space().to_xyz_d50;
    const M3 m = mul3(inv3(to_xyz), mul3(bradford(sx, sy, dx, dy), to_xyz));
    const size_t n = rgb.size() / 3;
    for (size_t p = 0; p < n; ++p) {
        float* v = &rgb[p * 3];
        const double r = v[0], g = v[1], b = v[2];
        v[0] = float(m[0] * r + m[1] * g + m[2] * b);
        v[1] = float(m[3] * r + m[4] * g + m[5] * b);
        v[2] = float(m[6] * r + m[7] * g + m[8] * b);
    }
}

inline float srgb_encode(float v) {
    v = std::clamp(v, 0.0f, 1.0f);
    return v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}
inline float srgb_decode(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((std::max(v, 0.0f) + 0.055f) / 1.055f, 2.4f);
}
inline float smoothstep(float a, float b, float x) {
    const float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3 - 2 * t);
}

void shrink(FloatImage& frame, uint64_t cap) {
    const uint64_t pixels = uint64_t(frame.width) * frame.height;
    if (!cap || pixels <= cap) return;
    const double s = std::sqrt(double(cap) / double(pixels));
    const uint32_t dw = std::max(1u, uint32_t(frame.width * s));
    const uint32_t dh = std::max(1u, uint32_t(frame.height * s));
    std::vector<float> out;
    resize_area(frame.rgb.data(), frame.width, frame.height, 3, dw, dh, out);
    frame.rgb.swap(out);
    frame.width = dw;
    frame.height = dh;
}

void fit(uint32_t w, uint32_t h, uint32_t edge, uint32_t& dw, uint32_t& dh) {
    dw = w;
    dh = h;
    const uint32_t l = std::max(w, h);
    if (!edge || l <= edge) return;
    const double s = double(edge) / l;
    dw = std::max(1u, uint32_t(std::lround(w * s)));
    dh = std::max(1u, uint32_t(std::lround(h * s)));
}

// sRGB 8-bit RGBA, area-averaged to `edge`, in linear light.
Image8 shrink_rgba8(const uint8_t* rgba, uint32_t w, uint32_t h, uint32_t row_bytes, uint32_t edge) {
    uint32_t dw, dh;
    fit(w, h, edge, dw, dh);
    std::vector<float> lin(size_t(w) * h * 3);
    float table[256];
    for (int i = 0; i < 256; ++i) table[i] = srgb_decode(i / 255.0f);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) lin[(size_t(y) * w + x) * 3 + c] = table[rgba[size_t(y) * row_bytes + x * 4 + c]];
    std::vector<float> small;
    if (dw != w || dh != h) resize_area(lin.data(), w, h, 3, dw, dh, small);
    else small.swap(lin);
    Image8 out;
    out.width = dw;
    out.height = dh;
    out.rgba.resize(size_t(dw) * dh * 4);
    for (size_t p = 0; p < size_t(dw) * dh; ++p) {
        for (int c = 0; c < 3; ++c) out.rgba[p * 4 + c] = uint8_t(std::lround(srgb_encode(small[p * 3 + c]) * 255));
        out.rgba[p * 4 + 3] = 255;
    }
    return out;
}

void encode8(const std::vector<float>& lin, Image8& out) {
    const size_t n = lin.size() / 3;
    out.rgba.resize(n * 4);
    parallel_rows(uint32_t((n + 4095) / 4096), [&](uint32_t r0, uint32_t r1) {
        for (size_t p = size_t(r0) * 4096; p < std::min(n, size_t(r1) * 4096); ++p) {
            for (int c = 0; c < 3; ++c) out.rgba[p * 4 + c] = uint8_t(std::lround(srgb_encode(lin[p * 3 + c]) * 255));
            out.rgba[p * 4 + 3] = 255;
        }
    });
}

std::string meta_string(const Json& j, const char* key) {
    const Json& v = j.at(key);
    return v.is_string() ? v.as_string() : std::string();
}
std::optional<double> meta_number(const Json& j, const char* key) {
    const Json& v = j.at(key);
    if (v.is_number() && v.as_double() > 0) return v.as_double();
    return std::nullopt;
}

Json metadata_json(const Metadata& m) {
    Json j = Json::object();
    if (!m.make.empty()) j.set("make", Json(m.make));
    if (!m.model.empty()) j.set("model", Json(m.model));
    if (!m.lens.empty()) j.set("lens", Json(m.lens));
    if (m.iso) j.set("iso", Json(*m.iso));
    if (m.shutter_s) j.set("shutter_s", Json(*m.shutter_s));
    if (m.aperture) j.set("aperture", Json(*m.aperture));
    if (m.focal_mm) j.set("focal_mm", Json(*m.focal_mm));
    if (!m.datetime_original.empty()) j.set("datetime_original", Json(m.datetime_original));
    if (m.as_shot_temperature_k && m.as_shot_tint) {
        Json a = Json::object();
        a.set("temperature_k", Json(std::round(*m.as_shot_temperature_k)));
        a.set("tint", Json(std::round(*m.as_shot_tint * 10) / 10));
        j.set("as_shot", std::move(a));
    }
    return j;
}

struct WhiteBalanceChoice {
    bool custom = false;
    double temperature_k = 6500, tint = 0;
};

bool white_balance_of(const std::string& text, WhiteBalanceChoice& wb, std::string& error) {
    bool ok;
    const Json j = parse_object(text, "white balance", error, ok);
    if (!ok) return false;
    const Json& mode = j.at("mode");
    if (!mode.is_string() || mode.as_string() == "as_shot") return true;
    if (mode.as_string() != "custom") { error = "white balance mode must be as_shot or custom"; return false; }
    wb.custom = true;
    wb.temperature_k = std::clamp(j.at("temperature_k").as_double(6500), 2000.0, 50000.0);
    wb.tint = std::clamp(j.at("tint").as_double(0), -150.0, 150.0);
    return true;
}

// The Vulkan device the engine will pick (a discrete GPU first, else the
// first with a compute queue -- vulkan_gpu.cpp's rule), asked for its
// storage-buffer range. Through function pointers of its own, so volk's
// process-wide table, which the engine fills from its own instance, is never
// touched.
uint64_t query_storage_range() {
    if (volkInitialize() != VK_SUCCESS || !vkGetInstanceProcAddr) return 0;
    auto create = reinterpret_cast<PFN_vkCreateInstance>(vkGetInstanceProcAddr(nullptr, "vkCreateInstance"));
    if (!create) return 0;
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "SpektraLab limits";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (create(&info, nullptr, &instance) != VK_SUCCESS) return 0;
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(vkGetInstanceProcAddr(instance, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    uint64_t range = 0;
    uint32_t count = 0;
    if (enumerate && properties && enumerate(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        if (enumerate(instance, &count, devices.data()) == VK_SUCCESS) {
            int best = -1;
            for (VkPhysicalDevice d : devices) {
                VkPhysicalDeviceProperties p{};
                properties(d, &p);
                const int score = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1;
                if (score > best) { best = score; range = p.limits.maxStorageBufferRange; }
            }
        }
    }
    if (destroy) destroy(instance, nullptr);
    return range;
}

}  // namespace

// The engine's largest plane is one float per channel of a 4-channel frame
// (the RGBA16 result's float source and the grain layers are no larger), so
// 16 bytes a pixel, with a margin for the row padding of 2-D dispatches.
uint64_t storage_pixel_cap(uint64_t range) {
    if (!range || range >= 0xFFFFFFF0ull) return 0;
    return uint64_t(double(range) / 16.0 * 0.97);
}

void apply_finish(const Finish& f, std::vector<float>& rgb, uint32_t width, uint32_t height) {
    if (f.neutral()) return;
    const float t = float(f.temperature / 100), tn = float(f.tint / 100);
    float gain[3] = {1 + 0.25f * t, 1 - 0.2f * tn, 1 - 0.25f * t};
    const float norm = 0.2126f * gain[0] + 0.7152f * gain[1] + 0.0722f * gain[2];
    const float ev = float(std::exp2(f.exposure));
    for (float& g : gain) g = g / norm * ev;
    const float k = float(1 + f.contrast / 100);
    const float sh = float(f.shadows / 100), hi = float(f.highlights / 100);
    const float sat = float(1 + f.saturation / 100);
    const float vig = float(f.vignette / 100);
    const float cx = width * 0.5f, cy = height * 0.5f;
    const float rmax = std::sqrt(cx * cx + cy * cy);
    parallel_rows(height, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            for (uint32_t x = 0; x < width; ++x) {
                float* v = &rgb[(size_t(y) * width + x) * 3];
                float c[3];
                // white balance and exposure, in linear light
                for (int i = 0; i < 3; ++i) c[i] = std::max(0.0f, v[i] * gain[i]);
                // contrast, highlights and shadows, on the encoded print
                float e[3];
                for (int i = 0; i < 3; ++i) e[i] = srgb_encode(c[i]);
                if (k != 1) for (float& u : e) u = std::clamp(0.5f + (u - 0.5f) * k, 0.0f, 1.0f);
                if (sh != 0 || hi != 0) {
                    const float luma = 0.2126f * e[0] + 0.7152f * e[1] + 0.0722f * e[2];
                    const float ms = 1 - smoothstep(0.0f, 0.55f, luma), mh = smoothstep(0.45f, 1.0f, luma);
                    const float lift = 0.3f * (sh * ms + hi * mh);
                    for (float& u : e) u = std::clamp(u + lift * (lift > 0 ? (1 - u) : u), 0.0f, 1.0f);
                }
                for (int i = 0; i < 3; ++i) c[i] = srgb_decode(e[i]);
                // saturation and vignette, in linear light
                if (sat != 1) {
                    const float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
                    for (float& u : c) u = std::max(0.0f, l + (u - l) * sat);
                }
                if (vig != 0) {
                    const float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                    const float r = std::sqrt(dx * dx + dy * dy) / rmax;
                    const float w = 1 + vig * 0.8f * smoothstep(0.3f, 1.0f, r);
                    for (float& u : c) u *= std::max(0.0f, w);
                }
                v[0] = c[0]; v[1] = c[1]; v[2] = c[2];
            }
        }
    });
}

Studio::~Studio() {
    close();
    if (engine_) spk_engine_destroy(engine_);
}

bool Studio::start(const std::string& resources, std::string& error) {
    if (engine_) return true;
    resources_ = resources;
    storage_range_ = query_storage_range();
    engine_ = spk_engine_create(resources.c_str(), nullptr);
    if (!engine_) {
        error = spk_last_error(nullptr);
        if (error.empty()) error = "the render engine did not start";
        return false;
    }
    Json caps;
    std::string why;
    Json::parse(spk_capabilities(engine_), caps, why);
    gpu_name_ = caps.at("backend").at("gpu").as_string();
    math_mode_ = caps.at("backend").at("math_mode").as_string();
    max_texture_ = uint32_t(caps.at("max_texture_dimension_2d").as_double(0));
    if (!transform_.fetch(engine_, "sRGB", "sRGB", error)) {
        error = "output transform: " + error;
        spk_engine_destroy(engine_);
        engine_ = nullptr;
        return false;
    }
    return true;
}

std::string Studio::engine_info() const {
    Json j = Json::object();
    j.set("build", Json(std::string(spk_build_info())));
    j.set("gpu", Json(gpu_name_));
    j.set("math_mode", Json(math_mode_));
    j.set("max_storage_buffer_bytes", Json(double(storage_range_)));
    j.set("max_texture_dimension_2d", Json(double(max_texture_)));
    j.set("storage_pixel_cap", Json(double(storage_pixel_cap(storage_range_))));
    return j.dump();
}

std::string Studio::last_error() const {
    const std::string e = spk_last_error(engine_);
    return e.empty() ? std::string("the render engine failed") : e;
}

uint64_t Studio::frame_cap(uint64_t requested) const {
    uint64_t cap = requested;
    const uint64_t storage = storage_pixel_cap(storage_range_);
    if (storage && (!cap || storage < cap)) cap = storage;
    return cap;
}

void Studio::close() {
    if (session_) spk_session_release(session_);
    session_ = nullptr;
    live_.clear();
    live_w_ = live_h_ = 0;
}

bool Studio::open_file(const std::string& path, const std::string& wb_json, const std::string& delta_json,
                       uint64_t max_pixels, uint32_t preview_edge, std::string& reply, std::string& error) {
    if (!engine_) { error = "the render engine is not running"; return false; }
    WhiteBalanceChoice wb;
    if (!white_balance_of(wb_json, wb, error)) return false;
    const auto file = utf8_path(path);
    Probe probe;
    if (!probe_file(file, probe, error)) return false;
    DecodeOptions options;
    if (wb.custom && probe.kind == FileKind::Raw) {
        options.temperature_k = wb.temperature_k;
        options.tint = wb.tint;
    }
    FloatImage frame;
    if (!decode_linear_prophoto(file, options, frame, probe, error)) {
        const std::string prefix = kUnsupportedPrefix;
        if (error.rfind(prefix, 0) == 0) error = error.substr(prefix.size());
        return false;
    }
    shrink(frame, frame_cap(max_pixels));
    if (wb.custom && probe.kind != FileKind::Raw) correct_flat_white_balance(frame.rgb, wb.temperature_k, wb.tint);

    // The original: the decoder's own display rendering (a RAW's embedded
    // preview or a half-size decode), never what the engine develops.
    Rgba8 thumb;
    std::string source, why;
    if (thumbnail(file, std::max(preview_edge, 256u), thumb, source, why)) {
        original_.width = thumb.width;
        original_.height = thumb.height;
        original_.rgba = std::move(thumb.rgba);
    } else {
        original_ = Image8{};
    }
    metadata_ = probe.metadata;
    kind_ = probe.kind;
    return open_frame(frame, delta_json, reply, error);
}

bool Studio::open_pixels(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t row_bytes,
                         const std::string& meta_json, const std::string& wb_json, const std::string& delta_json,
                         uint64_t max_pixels, uint32_t preview_edge, std::string& reply, std::string& error) {
    if (!engine_) { error = "the render engine is not running"; return false; }
    if (!rgba || !width || !height || row_bytes < width * 4) { error = "the photo has no pixels"; return false; }
    WhiteBalanceChoice wb;
    if (!white_balance_of(wb_json, wb, error)) return false;
    bool ok;
    const Json meta = parse_object(meta_json, "metadata", error, ok);
    if (!ok) return false;

    FloatImage frame;
    frame.width = width;
    frame.height = height;
    frame.rgb.resize(size_t(width) * height * 3);
    for (uint32_t y = 0; y < height; ++y)
        for (uint32_t x = 0; x < width; ++x)
            for (int c = 0; c < 3; ++c)
                frame.rgb[(size_t(y) * width + x) * 3 + c] = rgba[size_t(y) * row_bytes + x * 4 + c] / 255.0f;
    to_linear_prophoto(srgb_space(), frame.rgb);
    shrink(frame, frame_cap(max_pixels));
    if (wb.custom) correct_flat_white_balance(frame.rgb, wb.temperature_k, wb.tint);

    original_ = shrink_rgba8(rgba, width, height, row_bytes, std::max(preview_edge, 256u));
    metadata_ = Metadata{};
    metadata_.make = meta_string(meta, "make");
    metadata_.model = meta_string(meta, "model");
    metadata_.lens = meta_string(meta, "lens");
    metadata_.datetime_original = meta_string(meta, "datetime_original");
    metadata_.iso = meta_number(meta, "iso");
    metadata_.shutter_s = meta_number(meta, "shutter_s");
    metadata_.aperture = meta_number(meta, "aperture");
    metadata_.focal_mm = meta_number(meta, "focal_mm");
    kind_ = FileKind::Unknown;
    return open_frame(frame, delta_json, reply, error);
}

bool Studio::open_frame(FloatImage& frame, const std::string& delta_json, std::string& reply, std::string& error) {
    bool ok;
    Json delta = parse_object(delta_json, "params", error, ok);
    if (!ok) return false;
    force_output(delta);
    close();
    const auto t0 = Clock::now();
    spk_image image{frame.rgb.data(), frame.width, frame.height, 3};
    char* out = nullptr;
    const std::string text = delta.dump();
    session_ = spk_open(engine_, &image, text.c_str(), &out);
    if (!session_) {
        if (out) spk_string_free(out);
        error = last_error();
        return false;
    }
    Json opened = take_json(out);
    Json r = Json::object();
    r.set("width", Json(double(frame.width)));
    r.set("height", Json(double(frame.height)));
    r.set("kind", Json(std::string(kind_name(kind_))));
    r.set("is_raw", Json(kind_ == FileKind::Raw));
    r.set("metadata", metadata_json(metadata_));
    r.set("open_ms", Json(ms_since(t0)));
    r.set("params", opened.at("params"));
    reply = r.dump();
    return true;
}

bool Studio::set_params(const std::string& delta_json, std::string& error) {
    bool ok;
    Json delta = parse_object(delta_json, "params", error, ok);
    if (!ok) return false;
    if (delta.fields().empty()) return true;
    // Never let a delta move the display space out from under the pixels.
    if (delta.has("output_color_space") || delta.has("output_cctf_encoding")) force_output(delta);
    char* out = nullptr;
    const spk_status st = spk_set_params(session_, delta.dump().c_str(), &out);
    if (out) spk_string_free(out);
    if (st != SPK_OK) { error = last_error(); return false; }
    return true;
}

bool Studio::print(const char* tier, spk_result& out, std::string& error) {
    out = spk_result{};
    spk_status st = spk_reprint(session_, tier, &out);
    if (st == SPK_ERR_CANCELLED) { error = last_error(); return false; }
    if (st != SPK_OK) {
        out = spk_result{};
        st = spk_render(session_, tier, &out);
        if (st != SPK_OK) { error = last_error(); return false; }
    }
    if (!out.rgba16 || !out.width || !out.height) {
        spk_result_free(&out);
        error = "the render returned no pixels";
        return false;
    }
    return true;
}

bool Studio::render(const std::string& delta_json, const Finish& finish, Image8& out, double& engine_ms,
                    std::string& error) {
    if (!session_) { error = "no photo is open"; return false; }
    if (!set_params(delta_json, error)) return false;
    spk_result r{};
    if (!print("live", r, error)) return false;
    live_w_ = r.width;
    live_h_ = r.height;
    transform_.to_linear(r.rgba16, r.width, r.height, r.row_stride_px ? r.row_stride_px : r.width, live_);
    engine_ms = r.elapsed_ms;
    spk_result_free(&r);
    return refinish(finish, out);
}

bool Studio::refinish(const Finish& finish, Image8& out) const {
    if (live_.empty()) return false;
    out.width = live_w_;
    out.height = live_h_;
    if (finish.neutral()) {
        encode8(live_, out);
        return true;
    }
    std::vector<float> lin = live_;
    apply_finish(finish, lin, live_w_, live_h_);
    encode8(lin, out);
    return true;
}

bool Studio::metered_ev(double& ev) {
    if (!session_) return false;
    char* out = nullptr;
    if (spk_solve(session_, "exposure", &out) != SPK_OK) {
        if (out) spk_string_free(out);
        return false;
    }
    const Json reply = take_json(out);
    const Json& v = reply.at("solved_params").at("exposure_compensation_ev");
    if (!v.is_number()) return false;
    ev = v.as_double();
    return true;
}

bool Studio::export_file(const std::string& path, const std::string& delta_json, const Finish& finish,
                         const std::string& format, uint32_t long_edge, int quality, std::string& reply,
                         std::string& error) {
    if (!session_) { error = "no photo is open"; return false; }
    if (format != "jpeg" && format != "png" && format != "tiff16") {
        error = "format must be jpeg, png or tiff16";
        return false;
    }
    if (!set_params(delta_json, error)) return false;
    spk_result r{};
    if (!print("full", r, error)) return false;
    uint32_t w = r.width, h = r.height;
    std::vector<float> lin;
    transform_.to_linear(r.rgba16, w, h, r.row_stride_px ? r.row_stride_px : w, lin);
    spk_result_free(&r);

    // Finish before the resize, so the vignette is placed on the frame.
    apply_finish(finish, lin, w, h);
    uint32_t dw, dh;
    fit(w, h, long_edge, dw, dh);
    if (dw != w || dh != h) {
        std::vector<float> small;
        resize_area(lin.data(), w, h, 3, dw, dh, small);
        lin.swap(small);
        w = dw;
        h = dh;
    }

    std::vector<uint8_t> icc, bytes;
    if (!icc_for("sRGB", utf8_path(resources_), icc, error)) return false;
    Exif exif;
    exif.make = metadata_.make;
    exif.model = metadata_.model;
    exif.lens = metadata_.lens;
    exif.datetime_original = metadata_.datetime_original;
    exif.iso = metadata_.iso.value_or(0);
    exif.shutter_s = metadata_.shutter_s.value_or(0);
    exif.aperture = metadata_.aperture.value_or(0);
    exif.focal_mm = metadata_.focal_mm.value_or(0);
    const size_t n = size_t(w) * h;
    bool ok;
    if (format == "tiff16") {
        std::vector<uint16_t> rgba;
        transform_.encode_rgba16(lin, rgba);
        std::vector<uint16_t> rgb(n * 3);
        for (size_t p = 0; p < n; ++p) for (int c = 0; c < 3; ++c) rgb[p * 3 + c] = rgba[p * 4 + c];
        ok = encode_tiff(rgb.data(), w, h, 16, icc, bytes, error, &exif);
    } else {
        std::vector<uint8_t> rgba;
        transform_.encode_rgba8(lin, rgba);
        std::vector<uint8_t> rgb(n * 3);
        for (size_t p = 0; p < n; ++p) for (int c = 0; c < 3; ++c) rgb[p * 3 + c] = rgba[p * 4 + c];
        ok = format == "png" ? encode_png8(rgb.data(), w, h, icc, bytes, error, &exif)
                             : encode_jpeg(rgb.data(), w, h, std::clamp(quality, 1, 100), icc, bytes, error, &exif);
    }
    if (!ok) return false;
    if (!write_file_atomic(utf8_path(path), bytes, true, error)) return false;
    Json j = Json::object();
    j.set("width", Json(double(w)));
    j.set("height", Json(double(h)));
    j.set("bytes", Json(double(bytes.size())));
    reply = j.dump();
    return true;
}

}  // namespace spkandroid
