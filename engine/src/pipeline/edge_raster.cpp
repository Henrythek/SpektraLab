// edge_raster.cpp -- see edge_raster.hpp. Not compiled on Apple, where
// overscan.cpp draws through CoreGraphics/CoreText/vImage; build.sh globs
// pipeline/*.cpp there, so the whole translation unit is guarded.
#ifndef __APPLE__
#include "edge_raster.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <thread>

#define STB_TRUETYPE_IMPLEMENTATION
#define STBTT_STATIC
#include "../../third_party/stb/stb_truetype.h"

namespace spk::edge {

namespace {

// ---------------------------------------------------------------------------
// Coverage: signed area accumulation (the font-rs / stb_truetype v2 method).
// Every edge deposits, per scanline it crosses, its exact area contribution
// into the cells it passes and the remainder into the next cell; a running
// sum along the row is then the winding-weighted coverage. |sum| clamped to
// 1 makes overlapping same-direction contours (a glyph's overlapping strokes)
// solid and opposite ones (a counter) clear -- the nonzero rule for every
// shape the edge print draws.
// ---------------------------------------------------------------------------
class Accumulator {
public:
    Accumulator(int w, int h) : w_(w), h_(h), stride_(w + 2), a_(size_t(w + 2) * size_t(h), 0.0f) {}

    void line(double x0, double y0, double x1, double y1) {
        if (std::fabs(y0 - y1) <= 1e-9) return;
        double dir = 1.0;
        if (y0 > y1) { std::swap(x0, x1); std::swap(y0, y1); dir = -1.0; }
        const double dxdy = (x1 - x0) / (y1 - y0);
        const int ys = std::max(0, int(std::floor(y0)));
        const int ye = std::min(h_, int(std::ceil(y1)));
        for (int y = ys; y < ye; ++y) {
            const double top = std::max(double(y), y0), bot = std::min(double(y + 1), y1);
            const double dy = bot - top;
            if (dy <= 0.0) continue;
            // the edge's x where it enters and leaves this scanline, clamped to
            // the box: a part left of it still winds everything to its right
            const double xa = std::clamp(x0 + (top - y0) * dxdy, 0.0, double(w_));
            const double xb = std::clamp(x0 + (bot - y0) * dxdy, 0.0, double(w_));
            cell_span(y, xa, xb, dy * dir);
        }
    }

    // The accumulated coverage of row y, cell x.
    template <class Fn> void rows(Fn fn) const {
        for (int y = 0; y < h_; ++y) {
            const float* r = a_.data() + size_t(y) * size_t(stride_);
            double acc = 0.0;
            for (int x = 0; x < w_; ++x) {
                acc += r[x];
                fn(x, y, std::min(1.0, std::fabs(acc)));
            }
        }
    }

private:
    void cell_span(int y, double xa, double xb, double d) {
        float* r = a_.data() + size_t(y) * size_t(stride_);
        const double lo = std::min(xa, xb), hi = std::max(xa, xb);
        const double lof = std::floor(lo);
        const int li = int(lof);
        const int hi_ceil = int(std::ceil(hi));
        if (hi_ceil <= li + 1) {
            // within one cell: the part right of the edge's mean x is covered
            const double xm = 0.5 * (xa + xb) - lof;
            r[li] += float(d * (1.0 - xm));
            r[li + 1] += float(d * xm);
            return;
        }
        const double s = 1.0 / (hi - lo);
        const double lofrac = lo - lof;
        const double a0 = 0.5 * s * (1.0 - lofrac) * (1.0 - lofrac);
        const double hifrac = hi - double(hi_ceil) + 1.0;
        const double am = 0.5 * s * hifrac * hifrac;
        r[li] += float(d * a0);
        if (hi_ceil == li + 2) {
            r[li + 1] += float(d * (1.0 - a0 - am));
        } else {
            const double a1 = s * (1.5 - lofrac);
            r[li + 1] += float(d * (a1 - a0));
            for (int x = li + 2; x < hi_ceil - 1; ++x) r[x] += float(d * s);
            const double a2 = a1 + double(hi_ceil - li - 3) * s;
            r[hi_ceil - 1] += float(d * (1.0 - a2 - am));
        }
        r[hi_ceil] += float(d * am);
    }

    int w_, h_, stride_;
    std::vector<float> a_;
};

// ---------------------------------------------------------------------------
// Fonts.
// ---------------------------------------------------------------------------
struct Face {
    std::vector<unsigned char> data;
    stbtt_fontinfo info{};
    bool ok = false;
    double units_per_em = 1000.0;
};

std::mutex g_font_mutex;
std::string g_font_dir;
Face g_faces[2];   // 0 regular, 1 bold
bool g_loaded[2] = {false, false};

const Face* face_for(const char* name) {
    const std::string n = name ? name : "";
    const int k = (n.find("Bold") != std::string::npos || n.find("Medium") != std::string::npos) ? 1 : 0;
    std::lock_guard<std::mutex> lock(g_font_mutex);
    Face& f = g_faces[k];
    if (!g_loaded[k]) {
        g_loaded[k] = true;
        const std::string path = g_font_dir + (k ? "/fonts/texgyreheros-bold.otf" : "/fonts/texgyreheros-regular.otf");
        if (FILE* fp = std::fopen(path.c_str(), "rb")) {
            std::fseek(fp, 0, SEEK_END);
            const long len = std::ftell(fp);
            std::fseek(fp, 0, SEEK_SET);
            if (len > 0) {
                f.data.resize(size_t(len));
                if (std::fread(f.data.data(), 1, f.data.size(), fp) == f.data.size() &&
                    stbtt_InitFont(&f.info, f.data.data(), stbtt_GetFontOffsetForIndex(f.data.data(), 0))) {
                    // stbtt_ScaleForMappingEmToPixels(1) is 1 / unitsPerEm
                    f.units_per_em = 1.0 / double(stbtt_ScaleForMappingEmToPixels(&f.info, 1.0f));
                    f.ok = true;
                }
            }
            std::fclose(fp);
        }
    }
    return f.ok ? &f : nullptr;
}

// UTF-8 -> code points (invalid bytes become U+FFFD).
std::vector<int> decode_utf8(const std::string& s) {
    std::vector<int> out;
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = (unsigned char)s[i];
        int cp, n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 4; }
        else { out.push_back(0xFFFD); ++i; continue; }
        if (i + size_t(n) > s.size()) { out.push_back(0xFFFD); break; }
        for (int k = 1; k < n; ++k) cp = (cp << 6) | ((unsigned char)s[i + size_t(k)] & 0x3F);
        out.push_back(cp);
        i += size_t(n);
    }
    return out;
}

// Flatten a Bezier (control points already in pixels) into `c`, finely
// enough that the chord error is well under a tenth of a pixel.
void flatten_quad(Contour& c, double x0, double y0, double cx, double cy, double x1, double y1) {
    const double len = std::hypot(cx - x0, cy - y0) + std::hypot(x1 - cx, y1 - cy);
    const int n = std::clamp(int(std::ceil(std::sqrt(len) * 1.5)), 1, 64);
    for (int k = 1; k <= n; ++k) {
        const double t = double(k) / n, u = 1.0 - t;
        c.push_back(u * u * x0 + 2 * u * t * cx + t * t * x1);
        c.push_back(u * u * y0 + 2 * u * t * cy + t * t * y1);
    }
}

void flatten_cubic(Contour& c, double x0, double y0, double ax, double ay, double bx, double by,
                   double x1, double y1) {
    const double len = std::hypot(ax - x0, ay - y0) + std::hypot(bx - ax, by - ay) + std::hypot(x1 - bx, y1 - by);
    const int n = std::clamp(int(std::ceil(std::sqrt(len) * 1.5)), 1, 64);
    for (int k = 1; k <= n; ++k) {
        const double t = double(k) / n, u = 1.0 - t;
        c.push_back(u * u * u * x0 + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * x1);
        c.push_back(u * u * u * y0 + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * y1);
    }
}

}  // namespace

void Bitmap::fill(const std::vector<Contour>& contours, double gain) {
    double xmin = 1e300, ymin = 1e300, xmax = -1e300, ymax = -1e300;
    for (const Contour& c : contours)
        for (size_t k = 0; k + 1 < c.size(); k += 2) {
            xmin = std::min(xmin, c[k]); xmax = std::max(xmax, c[k]);
            ymin = std::min(ymin, c[k + 1]); ymax = std::max(ymax, c[k + 1]);
        }
    if (!(xmin <= xmax)) return;
    const int bx0 = std::max(0, int(std::floor(xmin)));
    const int by0 = std::max(0, int(std::floor(ymin)));
    const int bx1 = std::min(w_, int(std::ceil(xmax)) + 1);
    const int by1 = std::min(h_, int(std::ceil(ymax)) + 1);
    if (bx1 <= bx0 || by1 <= by0) return;
    Accumulator acc(bx1 - bx0, by1 - by0);
    for (const Contour& c : contours) {
        const size_t n = c.size() / 2;
        if (n < 2) continue;
        for (size_t k = 0; k < n; ++k) {
            const size_t j = (k + 1) % n;
            acc.line(c[2 * k] - bx0, c[2 * k + 1] - by0, c[2 * j] - bx0, c[2 * j + 1] - by0);
        }
    }
    const float g = float(std::clamp(gain, 0.0, 1.0));
    acc.rows([&](int x, int y, double a) {
        if (a <= 0.0) return;
        float& p = px_[size_t(y + by0) * size_t(w_) + size_t(x + bx0)];
        p = p * float(1.0 - a) + g * float(a);
    });
}

void set_font_dir(const std::string& dir) {
    std::lock_guard<std::mutex> lock(g_font_mutex);
    if (dir == g_font_dir) return;
    g_font_dir = dir;
    for (int k = 0; k < 2; ++k) { g_faces[k] = Face{}; g_loaded[k] = false; }
}

bool fonts_available(std::string& error) {
    if (face_for("HelveticaNeue") && face_for("HelveticaNeue-Bold")) return true;
    std::lock_guard<std::mutex> lock(g_font_mutex);
    error = "film edge: the edge-print fonts are missing from " + g_font_dir + "/fonts (texgyreheros-*.otf)";
    return false;
}

bool text_outline(const char* face, double size, const std::string& text, double tracking,
                  double shape_hscale, const Affine& to_px, std::vector<Contour>& out, std::string& error) {
    const Face* f = face_for(face);
    if (!f) {
        error = "film edge: no font at " + g_font_dir + "/fonts (texgyreheros)";
        return false;
    }
    const double scale = size / f->units_per_em;
    double pen = 0.0;
    for (int cp : decode_utf8(text)) {
        const int glyph = stbtt_FindGlyphIndex(&f->info, cp);
        int adv = 0, lsb = 0;
        stbtt_GetGlyphHMetrics(&f->info, glyph, &adv, &lsb);
        // glyph units -> text space: widened about the glyph's own origin
        const Affine g = to_px.after(Affine{scale * shape_hscale, 0, 0, scale, pen, 0});
        stbtt_vertex* v = nullptr;
        const int nv = stbtt_GetGlyphShape(&f->info, glyph, &v);
        double lx = 0, ly = 0;   // the previous on-curve point, in pixels
        for (int i = 0; i < nv; ++i) {
            double x, y;
            g.apply(v[i].x, v[i].y, x, y);
            switch (v[i].type) {
            case STBTT_vmove:
                out.emplace_back();
                out.back().push_back(x); out.back().push_back(y);
                break;
            case STBTT_vline:
                if (out.empty()) out.emplace_back();
                out.back().push_back(x); out.back().push_back(y);
                break;
            case STBTT_vcurve: {
                if (out.empty()) out.emplace_back();
                double cx, cy;
                g.apply(v[i].cx, v[i].cy, cx, cy);
                flatten_quad(out.back(), lx, ly, cx, cy, x, y);
                break;
            }
            case STBTT_vcubic: {
                if (out.empty()) out.emplace_back();
                double ax, ay, bx, by;
                g.apply(v[i].cx, v[i].cy, ax, ay);
                g.apply(v[i].cx1, v[i].cy1, bx, by);
                flatten_cubic(out.back(), lx, ly, ax, ay, bx, by, x, y);
                break;
            }
            default:
                break;
            }
            lx = x; ly = y;
        }
        if (v) stbtt_FreeShape(&f->info, v);
        pen += adv * scale + tracking;
    }
    return true;
}

double text_advance(const char* face, double size, const std::string& text, double tracking) {
    const Face* f = face_for(face);
    if (!f) return 0.0;
    const double scale = size / f->units_per_em;
    double w = 0.0;
    for (int cp : decode_utf8(text)) {
        int adv = 0, lsb = 0;
        stbtt_GetGlyphHMetrics(&f->info, stbtt_FindGlyphIndex(&f->info, cp), &adv, &lsb);
        w += adv * scale + tracking;
    }
    return w;
}

namespace {

template <class Fn> void parallel_rows(int h, Fn fn) {
    const int workers = std::clamp(int(std::thread::hardware_concurrency()), 1, 16);
    if (workers == 1 || h < 64) { for (int y = 0; y < h; ++y) fn(y); return; }
    std::vector<std::thread> pool;
    const int chunk = (h + workers - 1) / workers;
    for (int k = 0; k < workers; ++k) {
        const int a = k * chunk, b = std::min(h, a + chunk);
        if (a >= b) break;
        pool.emplace_back([=] { for (int y = a; y < b; ++y) fn(y); });
    }
    for (std::thread& t : pool) t.join();
}

}  // namespace

void gaussian_blur(std::vector<float>& plane, int w, int h, double sigma) {
    if (w <= 0 || h <= 0 || !(sigma > 0.0)) return;
    const int r = int(std::ceil(3.0 * sigma));
    std::vector<float> k(size_t(2 * r + 1));
    double sum = 0.0;
    for (int i = -r; i <= r; ++i) { k[size_t(i + r)] = float(std::exp(-0.5 * i * i / (sigma * sigma))); sum += k[size_t(i + r)]; }
    for (float& v : k) v = float(v / sum);
    std::vector<float> tmp(plane.size(), 0.0f);
    // along rows, then down columns (row by row, so both passes stream memory)
    parallel_rows(h, [&](int y) {
        const float* in = plane.data() + size_t(y) * size_t(w);
        float* o = tmp.data() + size_t(y) * size_t(w);
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int i = -r; i <= r; ++i) acc += k[size_t(i + r)] * in[std::clamp(x + i, 0, w - 1)];
            o[x] = acc;
        }
    });
    parallel_rows(h, [&](int y) {
        float* o = plane.data() + size_t(y) * size_t(w);
        std::fill(o, o + w, 0.0f);
        for (int i = -r; i <= r; ++i) {
            const float kk = k[size_t(i + r)];
            const float* in = tmp.data() + size_t(std::clamp(y + i, 0, h - 1)) * size_t(w);
            for (int x = 0; x < w; ++x) o[x] += kk * in[x];
        }
    });
}

}  // namespace spk::edge
#endif  // !__APPLE__
