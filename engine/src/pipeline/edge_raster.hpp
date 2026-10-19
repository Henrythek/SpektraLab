// edge_raster.hpp -- the film edge's 2D raster off Apple (PORT_PLAN.md §4).
//
// On macOS overscan.cpp draws the edge print and the date with CoreGraphics
// and CoreText and blurs them with vImage. Elsewhere this stands in: one
// antialiasing model for polygons, discs and glyph outlines (exact area
// coverage, nonzero-like winding), glyphs from TeX Gyre Heros through
// stb_truetype (resources/fonts/README.txt), and a separable Gaussian with
// edges extended. The layout -- what is drawn where, at what size and
// tracking -- stays overscan.cpp's own numbers; only glyph shapes differ.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace spk::edge {

// A 2D affine map, x' = a x + c y + e, y' = b x + d y + f (CoreGraphics' order).
struct Affine {
    double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
    void apply(double x, double y, double& ox, double& oy) const {
        ox = a * x + c * y + e;
        oy = b * x + d * y + f;
    }
    // this o m: m first, then this.
    Affine after(const Affine& m) const {
        return {a * m.a + c * m.b, b * m.a + d * m.b, a * m.c + c * m.d, b * m.c + d * m.d,
                a * m.e + c * m.f + e, b * m.e + d * m.f + f};
    }
};

using Contour = std::vector<double>;   // x0, y0, x1, y1, ... in pixels; closed implicitly

// A grey coverage bitmap, row-major, row 0 at the top. Fills composite like
// an opaque CoreGraphics fill: dst = dst (1 - a) + gain a.
class Bitmap {
public:
    Bitmap(int w, int h) : w_(w), h_(h), px_(size_t(w) * size_t(h), 0.0f) {}
    void fill(const std::vector<Contour>& contours, double gain);
    std::vector<float>& pixels() { return px_; }
    int width() const { return w_; }
    int height() const { return h_; }
private:
    int w_, h_;
    std::vector<float> px_;
};

// The resources directory holding fonts/ (spk_engine_create sets it).
void set_font_dir(const std::string& dir);

// Both faces load; otherwise `error` names the directory looked in.
bool fonts_available(std::string& error);

// A Helvetica Neue face name ("HelveticaNeue", "-Bold", "-Medium") mapped to
// its stand-in. `text` is UTF-8. Text space is CoreText's for a font of em
// `size`: x along the baseline from the pen's start, y up. `shape_hscale`
// widens each glyph about its own origin; `tracking` is added after every
// glyph. The outlines come back through `to_px` (text space -> pixels).
bool text_outline(const char* face, double size, const std::string& text, double tracking,
                  double shape_hscale, const Affine& to_px, std::vector<Contour>& out, std::string& error);

// The line's advance in text-space units, as CTLineGetTypographicBounds
// reports it with the same kern attribute: every glyph's advance plus the
// tracking after each one. 0 when the font cannot be read.
double text_advance(const char* face, double size, const std::string& text, double tracking);

// Separable Gaussian of `sigma` px over a w x h plane, edges extended
// (vImage's kvImageEdgeExtend), threaded over rows.
void gaussian_blur(std::vector<float>& plane, int w, int h, double sigma);

}  // namespace spk::edge
