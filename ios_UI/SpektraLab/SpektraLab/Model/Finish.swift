//  Finish.swift — Layer 2 on the phone: adjustments to the finished print.
//
//  The Mac's Layer 2 (`Adjustments.swift`) is a Metal shader on the canvas,
//  graded in the ProPhoto working space. This is the same idea, a scan of a
//  print adjusted afterwards, built from Core Image filters on the Display P3
//  print the engine returns. **It is not the Mac's maths**: the controls have
//  the same names, ranges and order, and zero is "nothing applied" for every
//  one, but a value moved on one platform will not look identical on the
//  other. Porting `layer2` from `Shaders.metal` as a `CIKernel` is the way to
//  close that, and is left for a later pass.
//
//  Never sent to the engine. Applied identically to the preview and the export.

import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

struct Finish: Codable, Equatable, Sendable {
    var exposure = 0.0      // stops, −3…3
    var contrast = 0.0      // −50…50
    var saturation = 0.0    // −100…100
    var highlights = 0.0    // −100…100
    var shadows = 0.0       // −100…100
    var temperature = 0.0   // −100…100, blue ↔ amber
    var tint = 0.0          // −100…100, green ↔ magenta
    var vignette = 0.0      // −100…100, negative darkens the corners

    static let neutral = Finish()
    var isNeutral: Bool { self == .neutral }

    /// The Mac's order: white balance → exposure/contrast → highlights/shadows
    /// → saturation → vignette.
    func apply(to input: CIImage) -> CIImage {
        guard !isNeutral else { return input }
        var image = input
        if temperature != 0 || tint != 0 {
            let f = CIFilter.temperatureAndTint()
            f.inputImage = image
            f.neutral = CIVector(x: 6500, y: 0)
            // ±100 is about ±1500 K and ±50 tint, a correction rather than a re-balance.
            f.targetNeutral = CIVector(x: 6500 - temperature * 15, y: -tint * 0.5)
            image = f.outputImage ?? image
        }
        if exposure != 0 {
            let f = CIFilter.exposureAdjust()
            f.inputImage = image
            f.ev = Float(exposure)
            image = f.outputImage ?? image
        }
        if contrast != 0 || saturation != 0 {
            let f = CIFilter.colorControls()
            f.inputImage = image
            f.contrast = Float(1 + contrast / 100)
            f.saturation = Float(1 + saturation / 100)
            f.brightness = 0
            image = f.outputImage ?? image
        }
        if highlights != 0 || shadows != 0 {
            let f = CIFilter.highlightShadowAdjust()
            f.inputImage = image
            // Core Image's highlight amount is 1 at rest and lowers highlights below it.
            f.highlightAmount = Float(1 - max(0, -highlights) / 100)
            f.shadowAmount = Float(shadows / 100)
            image = f.outputImage ?? image
            if highlights > 0 {
                let lift = CIFilter.toneCurve()
                lift.inputImage = image
                let h = Float(highlights / 100) * 0.08
                lift.point0 = CGPoint(x: 0, y: 0)
                lift.point1 = CGPoint(x: 0.25, y: 0.25)
                lift.point2 = CGPoint(x: 0.5, y: 0.5)
                lift.point3 = CGPoint(x: 0.75, y: CGFloat(0.75 + h))
                lift.point4 = CGPoint(x: 1, y: 1)
                image = lift.outputImage ?? image
            }
        }
        if vignette != 0 {
            let f = CIFilter.vignetteEffect()
            let e = input.extent
            f.inputImage = image
            f.center = CGPoint(x: e.midX, y: e.midY)
            f.radius = Float((e.width * e.width + e.height * e.height).squareRoot() / 2 * 0.85)
            // Positive intensity darkens outside the radius; the slider's minus does.
            f.intensity = Float(-vignette / 100)
            f.falloff = 0.6
            image = f.outputImage ?? image
        }
        return image.cropped(to: input.extent)
    }
}
