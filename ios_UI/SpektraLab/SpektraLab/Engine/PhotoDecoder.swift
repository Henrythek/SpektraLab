//  PhotoDecoder.swift — a photo from the library or Files, decoded twice:
//  once for the engine, once for the eye.
//
//  The Mac's `ImageDecoder`, cut to what a phone needs, and keeping the rule
//  that matters most there: **a RAW is decoded twice and the two must not be
//  confused.**
//
//  - `linear` is what the engine develops: Apple's tone rendering switched
//    off (boost, shadow boost, gamut mapping, local tone map, EDR), so the
//    film model receives sensor response. Not tuning.
//  - `display` is the original the before/after shows: Apple's default
//    rendering. It never reaches the engine.
//
//  White balance follows the user in both, so the comparison is the film,
//  not the neutral point. For a RAW it is `CIRAWFilter`'s own neutral; a flat
//  file (JPEG, HEIC, PNG, TIFF) is already a rendering with its white balance
//  baked in, so its temperature and tint are a correction applied on top
//  (`CITemperatureAndTint`), the honest thing a flat file allows.
//
//  **The frame is capped** (`maxPixels`) before the engine sees it. A 48 MP
//  ProRAW at full size is ~770 MB of float32 input alone, and the engine's
//  working planes several times that: past a phone's memory limit. The RAW
//  path scales inside the demosaic (`scaleFactor`), which is both cheaper and
//  sharper than scaling afterwards.

import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO
import UniformTypeIdentifiers

struct WhiteBalance: Codable, Equatable, Sendable {
    var asShot = true
    /// Kelvin, as `CIRAWFilter.neutralTemperature` reads it.
    var temperature: Double = 5500
    var tint: Double = 0
}

struct DecodedPhoto: @unchecked Sendable {
    let linear: CIImage
    let display: CIImage
    let pixelSize: CGSize
    let isRAW: Bool
    let asShotTemperature: Double?
    let asShotTint: Double?
    /// The source's metadata, copied to the export with the orientation reset
    /// (the pixels are already upright). Never shown to the engine.
    let properties: [CFString: Any]
    let sourceName: String
}

enum PhotoDecoder {
    enum Failure: Error, LocalizedError {
        case unsupported(String)
        var errorDescription: String? {
            switch self { case .unsupported(let name): "Cannot decode \(name)." }
        }
    }

    static func isRAW(_ url: URL) -> Bool {
        if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .rawImage) { return true }
        return ["dng", "nef", "arw", "cr2", "cr3", "raf", "rw2", "orf", "pef", "srw", "3fr", "iiq"]
            .contains(url.pathExtension.lowercased())
    }

    static func decode(_ url: URL, whiteBalance: WhiteBalance, maxPixels: Int) throws -> DecodedPhoto {
        let properties: [CFString: Any] = {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
            else { return [:] }
            return props
        }()
        if isRAW(url), let raw = try? decodeRAW(url, whiteBalance: whiteBalance, maxPixels: maxPixels,
                                                properties: properties) {
            return raw
        }
        return try decodeFlat(url, whiteBalance: whiteBalance, maxPixels: maxPixels, properties: properties)
    }

    private static func rawFilter(_ url: URL, linear: Bool, whiteBalance: WhiteBalance,
                                  maxPixels: Int) throws -> CIRAWFilter {
        guard let filter = CIRAWFilter(imageURL: url) else { throw Failure.unsupported(url.lastPathComponent) }
        // Geometry identical in both looks, or the before/after compares two
        // registrations of the frame rather than two renderings of it.
        filter.isLensCorrectionEnabled = false
        filter.isDraftModeEnabled = false
        let native = filter.nativeSize
        let pixels = native.width * native.height
        if pixels > CGFloat(maxPixels) {
            filter.scaleFactor = Float((CGFloat(maxPixels) / pixels).squareRoot())
        }
        if linear {
            filter.boostAmount = 0
            filter.boostShadowAmount = 0
            filter.isGamutMappingEnabled = false
            filter.localToneMapAmount = 0
            filter.extendedDynamicRangeAmount = 0
        }
        if !whiteBalance.asShot {
            filter.neutralTemperature = Float(whiteBalance.temperature)
            filter.neutralTint = Float(whiteBalance.tint)
        }
        return filter
    }

    private static func decodeRAW(_ url: URL, whiteBalance: WhiteBalance, maxPixels: Int,
                                  properties: [CFString: Any]) throws -> DecodedPhoto {
        let linearFilter = try rawFilter(url, linear: true, whiteBalance: whiteBalance, maxPixels: maxPixels)
        // The camera's own neutral, read from a filter nobody has overridden.
        let probe = CIRAWFilter(imageURL: url)
        let asShotT = Double(probe?.neutralTemperature ?? 5500)
        let asShotTint = Double(probe?.neutralTint ?? 0)
        guard let linear = linearFilter.outputImage else { throw Failure.unsupported(url.lastPathComponent) }
        let displayFilter = try rawFilter(url, linear: false, whiteBalance: whiteBalance, maxPixels: maxPixels)
        guard let display = displayFilter.outputImage else { throw Failure.unsupported(url.lastPathComponent) }
        return DecodedPhoto(linear: linear, display: display, pixelSize: linear.extent.size, isRAW: true,
                            asShotTemperature: asShotT, asShotTint: asShotTint,
                            properties: properties, sourceName: url.lastPathComponent)
    }

    private static func decodeFlat(_ url: URL, whiteBalance: WhiteBalance, maxPixels: Int,
                                   properties: [CFString: Any]) throws -> DecodedPhoto {
        guard var image = CIImage(contentsOf: url, options: [.applyOrientationProperty: true]) else {
            throw Failure.unsupported(url.lastPathComponent)
        }
        // Untagged 16-bit and float files are the engine's linear ProPhoto
        // convention, as on the Mac; untagged 8-bit is sRGB, Core Image's own
        // assumption.
        if image.colorSpace == nil {
            let isFloat = properties[kCGImagePropertyIsFloat] as? Bool ?? false
            let depth = properties[kCGImagePropertyDepth] as? Int ?? 8
            if isFloat || depth >= 16 {
                image = image.matchedToWorkingSpace(from: ColourSpaces.linearProPhoto) ?? image
            }
        }
        let pixels = image.extent.width * image.extent.height
        if pixels > CGFloat(maxPixels) {
            let scale = (CGFloat(maxPixels) / pixels).squareRoot()
            let lanczos = CIFilter.lanczosScaleTransform()
            lanczos.inputImage = image
            lanczos.scale = Float(scale)
            lanczos.aspectRatio = 1
            image = lanczos.outputImage ?? image
        }
        if !whiteBalance.asShot {
            // From the neutral the file was rendered at (taken as D65) to the
            // user's; a correction on a rendering, not a re-balance of a RAW.
            let wb = CIFilter.temperatureAndTint()
            wb.inputImage = image
            wb.neutral = CIVector(x: 6500, y: 0)
            wb.targetNeutral = CIVector(x: whiteBalance.temperature, y: whiteBalance.tint)
            image = wb.outputImage ?? image
        }
        return DecodedPhoto(linear: image, display: image, pixelSize: image.extent.size, isRAW: false,
                            asShotTemperature: nil, asShotTint: nil,
                            properties: properties, sourceName: url.lastPathComponent)
    }
}
