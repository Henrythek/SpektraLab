//  Exporter.swift — the finished print as a file, and the Core Image work the
//  preview shares with it.
//
//  The engine writes no files (ARCHITECTURE §8.8), on the phone as on the
//  Mac: it returns pixels and ImageIO encodes them. The source's metadata is
//  copied across uninterpreted, as the Mac's `Exporter` does, with the one
//  field that would now be wrong reset -- the orientation, because the pixels
//  the engine returns are already upright.

import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ExportFormat: String, CaseIterable, Identifiable, Sendable {
    case jpeg, heic, tiff
    var id: String { rawValue }
    var title: String {
        switch self {
        case .jpeg: "JPEG"
        case .heic: "HEIC"
        case .tiff: "TIFF 16-bit"
        }
    }
    var type: UTType {
        switch self {
        case .jpeg: .jpeg
        case .heic: .heic
        case .tiff: .tiff
        }
    }
    var fileExtension: String {
        switch self {
        case .jpeg: "jpg"
        case .heic: "heic"
        case .tiff: "tif"
        }
    }
}

enum ExportError: Error, LocalizedError {
    case encode, photosDenied
    var errorDescription: String? {
        switch self {
        case .encode: "The print could not be encoded."
        case .photosDenied: "SpektraLab is not allowed to add to your photo library."
        }
    }
}

/// An ImageIO properties dictionary across an actor boundary. Read-only.
struct SendableProperties: @unchecked Sendable {
    let value: [CFString: Any]
}

enum Renderer {
    /// One context for the app: contexts are expensive and cache kernels.
    nonisolated(unsafe) static let context = CIContext(options: [
        .workingColorSpace: CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)!,
        .workingFormat: CIFormat.RGBAh,
        .cacheIntermediates: false,
    ])

    /// Apple's rendering of the photo at the preview's size: the original.
    static func preview(_ image: SendableImage, longEdge: Int) -> RenderedPrint? {
        let input = image.image
        let edge = max(input.extent.width, input.extent.height)
        let scaled = edge > CGFloat(longEdge)
            ? input.transformed(by: CGAffineTransform(scaleX: CGFloat(longEdge) / edge, y: CGFloat(longEdge) / edge))
            : input
        guard let cg = context.createCGImage(scaled, from: scaled.extent.integral, format: .RGBA8,
                                             colorSpace: ColourSpaces.displayP3) else { return nil }
        return RenderedPrint(image: cg, elapsedMs: 0)
    }

    /// The print with the finish applied, optionally scaled to `longEdge`.
    static func finished(_ print: RenderedPrint, finish: Finish, sixteenBit: Bool,
                         longEdge: Int? = nil) -> RenderedPrint? {
        if finish.isNeutral, longEdge == nil { return print }
        var image = finish.apply(to: CIImage(cgImage: print.image))
        if let longEdge {
            let edge = max(image.extent.width, image.extent.height)
            if edge > CGFloat(longEdge) {
                let lanczos = CIFilter(name: "CILanczosScaleTransform")!
                lanczos.setValue(image, forKey: kCIInputImageKey)
                lanczos.setValue(CGFloat(longEdge) / edge, forKey: kCIInputScaleKey)
                lanczos.setValue(1.0, forKey: kCIInputAspectRatioKey)
                image = lanczos.outputImage ?? image
            }
        }
        guard let cg = context.createCGImage(image, from: image.extent.integral,
                                             format: sixteenBit ? .RGBA16 : .RGBA8,
                                             colorSpace: ColourSpaces.displayP3) else { return nil }
        return RenderedPrint(image: cg, elapsedMs: print.elapsedMs)
    }
}

enum Exporter {
    static func write(_ print: RenderedPrint, finish: Finish, format: ExportFormat, longEdge: Int?,
                      properties: SendableProperties, baseName: String) throws -> URL {
        guard let finished = Renderer.finished(print, finish: finish, sixteenBit: format == .tiff,
                                               longEdge: longEdge)
        else { throw ExportError.encode }
        let folder = FileManager.default.temporaryDirectory.appending(path: "Exports", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appending(path: "\(baseName).\(format.fileExtension)")
        try? FileManager.default.removeItem(at: url)
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, format.type.identifier as CFString,
                                                                1, nil)
        else { throw ExportError.encode }
        CGImageDestinationAddImage(destination, finished.image, metadata(properties.value, format: format) as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw ExportError.encode }
        return url
    }

    /// The source's EXIF, TIFF and GPS dictionaries, upright, plus the
    /// encoder's quality. Nothing is interpreted.
    static func metadata(_ source: [CFString: Any], format: ExportFormat) -> [CFString: Any] {
        var out: [CFString: Any] = [:]
        for key in [kCGImagePropertyExifDictionary, kCGImagePropertyGPSDictionary] {
            if let value = source[key] { out[key] = value }
        }
        var tiff = source[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        tiff[kCGImagePropertyTIFFOrientation] = 1
        tiff[kCGImagePropertyTIFFSoftware] = "SpektraLab for iOS"
        out[kCGImagePropertyTIFFDictionary] = tiff
        out[kCGImagePropertyOrientation] = 1
        if format != .tiff { out[kCGImageDestinationLossyCompressionQuality] = 0.92 }
        return out
    }
}
