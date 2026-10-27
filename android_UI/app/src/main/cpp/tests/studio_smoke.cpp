// studio_smoke.cpp -- studio.cpp end to end on a desktop Vulkan device
// (lavapipe on CI), so the code the phone runs is exercised without a phone.
//
//   studio_smoke <engine-dir> <out-dir> [photo...]
//
// A synthetic frame goes through every path the app takes: open, colour
// negative on paper, a slide scanned, black and white on Multigrade, the
// meter, Finish, a custom white balance, and export to JPEG, PNG and 16-bit
// TIFF -- which is then opened again as a file. Photos given on the command
// line (RAWs, JPEGs) are opened and printed too. Writes PNGs of each print to
// <out-dir> for a person to look at; exits non-zero on the first failure.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

#include "stb_image_write.h"
#include "studio.hpp"

using namespace spkandroid;

static int failures = 0;
#define CHECK(cond, what)                                                         \
    do {                                                                          \
        if (!(cond)) { std::fprintf(stderr, "FAIL %s: %s\n", what, error.c_str()); ++failures; return; } \
        std::fprintf(stderr, "ok   %s\n", what);                                  \
    } while (0)

static std::string out_dir;
static void save(const Image8& im, const std::string& name) {
    const std::string p = out_dir + "/" + name + ".png";
    stbi_write_png(p.c_str(), int(im.width), int(im.height), 4, im.rgba.data(), int(im.width * 4));
}

static double mean(const Image8& im, int c) {
    double s = 0;
    for (size_t p = 0; p < size_t(im.width) * im.height; ++p) s += im.rgba[p * 4 + c];
    return s / (double(im.width) * im.height);
}

static std::string wire(const char* film, const char* print, bool scan, bool bw) {
    std::string s = std::string("{\"film_stock\":\"") + film + "\",\"print_stock\":\"" + print +
                    "\",\"scan_film\":" + (scan ? "true" : "false") +
                    ",\"auto_exposure\":true,\"auto_exposure_method\":\"balanced\",\"film_format_mm\":36"
                    ",\"grain_active\":true,\"grain_sublayers_active\":" + (bw ? "false" : "true") +
                    ",\"preview_long_edge\":1024";
    if (bw) s += ",\"c_filter_neutral\":0,\"m_filter_neutral\":0,\"y_filter_neutral\":68";
    return s + "}";
}

static void run(Studio& studio, const std::vector<std::string>& photos) {
    std::string error, reply;
    // A frame with colour, a grey ramp and a bright patch.
    const uint32_t w = 1800, h = 1200;
    std::vector<uint8_t> px(size_t(w) * h * 4);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            uint8_t* p = &px[(size_t(y) * w + x) * 4];
            const double u = double(x) / w, v = double(y) / h;
            if (v < 0.25) { p[0] = p[1] = p[2] = uint8_t(u * 255); }
            else {
                p[0] = uint8_t(127 + 120 * std::sin(6.28 * u));
                p[1] = uint8_t(127 + 120 * std::sin(6.28 * (u + 0.33)));
                p[2] = uint8_t(127 + 120 * std::sin(6.28 * (v + 0.66)));
            }
            if (x > 1500 && y > 900) p[0] = p[1] = p[2] = 250;
            p[3] = 255;
        }
    bool ok = studio.open_pixels(px.data(), w, h, w * 4, R"({"make":"Test","model":"Synthetic","iso":200})",
                                 "", wire("kodak_portra_400", "kodak_portra_endura", false, false),
                                 4000000, 1024, reply, error);
    CHECK(ok, "open_pixels");
    std::fprintf(stderr, "     %s\n", reply.substr(0, 160).c_str());
    Image8 im;
    double ms = 0;
    ok = studio.render("", Finish{}, im, ms, error);
    CHECK(ok && im.width == 1024, "render portra/endura");
    std::fprintf(stderr, "     %ux%u in %.0f ms, mean %.1f %.1f %.1f\n", im.width, im.height, ms, mean(im, 0), mean(im, 1), mean(im, 2));
    save(im, "portra400-endura");
    save(studio.original(), "original");
    double ev = 0;
    ok = studio.metered_ev(ev);
    CHECK(ok, "metered_ev");
    std::fprintf(stderr, "     metered %+.2f EV\n", ev);

    Finish f;
    f.exposure = 0.5; f.contrast = 20; f.saturation = -30; f.vignette = -60; f.temperature = 40;
    Image8 fin;
    ok = studio.refinish(f, fin) && mean(fin, 0) != mean(im, 0);
    CHECK(ok, "refinish");
    save(fin, "portra400-finished");

    ok = studio.render(R"({"film_stock":"fujifilm_provia_100f","scan_film":true})", Finish{}, im, ms, error);
    CHECK(ok, "render provia scanned");
    save(im, "provia100f-scan");

    ok = studio.render(R"({"film_stock":"kodak_tri_x_400","print_stock":"ilford_multigrade_iv_rc","scan_film":false,"grain_sublayers_active":false,"c_filter_neutral":0,"m_filter_neutral":0,"y_filter_neutral":68})",
                       Finish{}, im, ms, error);
    CHECK(ok, "render tri-x on multigrade");
    {
        double spread = 0;
        for (size_t p = 0; p < size_t(im.width) * im.height; ++p)
            spread = std::max(spread, std::fabs(double(im.rgba[p * 4]) - im.rgba[p * 4 + 2]));
        std::fprintf(stderr, "     max |R-B| %.0f (a neutral print is near 0)\n", spread);
    }
    save(im, "trix400-multigrade");

    ok = studio.render(R"({"film_stock":"kodak_portra_400","print_stock":"kodak_portra_endura","grain_sublayers_active":true,"c_filter_neutral":null})",
                       Finish{}, im, ms, error);
    if (!ok) {   // a null may not be a legal delta; fall back to a plain switch back
        ok = studio.render(R"({"film_stock":"kodak_portra_400","print_stock":"kodak_portra_endura","grain_sublayers_active":true})",
                           Finish{}, im, ms, error);
    }
    CHECK(ok, "render back to colour");

    for (const char* fmt : {"jpeg", "png", "tiff16"}) {
        const std::string path = out_dir + "/export." + (std::string(fmt) == "tiff16" ? "tif" : fmt);
        ok = studio.export_file(path, "", f, fmt, std::string(fmt) == "png" ? 900 : 0, 92, reply, error);
        CHECK(ok && std::filesystem::file_size(path) > 1000, (std::string("export ") + fmt).c_str());
        std::fprintf(stderr, "     %s\n", reply.c_str());
    }

    ok = studio.open_file(out_dir + "/export.jpeg", R"({"mode":"custom","temperature_k":3200,"tint":10})",
                          wire("kodak_gold_200", "kodak_portra_endura", false, false), 4000000, 1024, reply, error);
    CHECK(ok, "open_file jpeg with a custom white balance");
    ok = studio.render("", Finish{}, im, ms, error);
    CHECK(ok, "render gold 200");
    save(im, "gold200-3200K");
    ok = studio.open_file(out_dir + "/export.tif", "", wire("kodak_portra_400", "kodak_portra_endura", false, false),
                          4000000, 1024, reply, error);
    CHECK(ok, "open_file tiff16");

    int i = 0;
    for (const std::string& photo : photos) {
        ok = studio.open_file(photo, "", wire("kodak_portra_400", "kodak_portra_endura", false, false),
                              12000000, 1600, reply, error);
        CHECK(ok, ("open " + photo).c_str());
        std::fprintf(stderr, "     %s\n", reply.substr(0, 240).c_str());
        ok = studio.render("", Finish{}, im, ms, error);
        CHECK(ok, "render photo");
        save(im, "photo" + std::to_string(i));
        save(studio.original(), "photo" + std::to_string(i) + "-original");
        ok = studio.export_file(out_dir + "/photo" + std::to_string(i) + "-full.jpg", "", Finish{}, "jpeg", 0, 90,
                                reply, error);
        CHECK(ok, "export photo at full size");
        std::fprintf(stderr, "     %s\n", reply.c_str());
        ok = studio.open_file(photo, R"({"mode":"custom","temperature_k":4000,"tint":0})",
                              wire("kodak_portra_400", "kodak_portra_endura", false, false), 12000000, 1600, reply, error);
        CHECK(ok, "reopen photo with a custom white balance");
        ++i;
    }
    studio.close();
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: studio_smoke <engine-dir> <out-dir> [photo...]\n"); return 2; }
    out_dir = argv[2];
    std::filesystem::create_directories(out_dir);
    Studio studio;
    std::string error;
    if (!studio.start(argv[1], error)) { std::fprintf(stderr, "FAIL start: %s\n", error.c_str()); return 1; }
    std::fprintf(stderr, "ok   start %s\n", studio.engine_info().c_str());
    std::vector<std::string> photos(argv + 3, argv + argc);
    run(studio, photos);
    std::fprintf(stderr, failures ? "FAILED\n" : "all passed\n");
    return failures ? 1 : 0;
}
