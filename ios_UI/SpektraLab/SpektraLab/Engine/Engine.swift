//  Engine.swift — the spektrafilm render engine, in this process, on iOS.
//
//  The same engine the Mac app links (`engine/src`, RFC-014), reached through
//  the same hand-written C surface (`spk_engine.h`), on the same Metal path:
//  iOS has Metal, so there is no MoltenVK and no second backend. What is
//  different is only what the phone can afford and what it shows:
//
//  - **One frame at a time, and smaller.** The Mac keeps eight frames'
//    textures resident and opens a 45 MP frame at full size. A phone's jetsam
//    limit is a fraction of that, so `PhotoDecoder` caps the frame the engine
//    develops (Settings → Develop size) and this actor holds one session.
//  - **The engine renders straight to Display P3.** The Mac renders into the
//    ProPhoto working space and converts in its own canvas shader (RFC-018
//    §5.1), because it grades in that space. This app has no Layer 2 shader to
//    feed, so it asks the engine for the display space directly
//    (`output_color_space`, which `spk_open` lets a delta override) and the
//    engine's own gamut compression does the conversion. `rgba16` is then a
//    picture Core Graphics can wrap as it stands.
//  - **Pixels, not textures, come back.** `spk_result.rgba16` is copied into
//    a `CGImage` and the result freed at once, so nothing here outlives the
//    call that made it.
//
//  An `actor` for the reason the Mac's `EngineClient` is one: one engine
//  handle's lifecycle, kept simple. Every call blocks on the GPU, so nothing
//  here should be called from the main actor without `await`.

import CoreGraphics
import CoreImage
import Foundation
import Metal

/// A JSON scalar for `params_delta`. The Mac app's `ParamValue`, the same wire.
enum ParamValue: Codable, Equatable, Sendable {
    case double(Double), bool(Bool), string(String)

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let d = try? c.decode(Double.self) { self = .double(d) }
        else { self = .string(try c.decode(String.self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .double(let d): try c.encode(d)
        case .bool(let b): try c.encode(b)
        case .string(let s): try c.encode(s)
        }
    }

    var doubleValue: Double? { if case .double(let d) = self { d } else { nil } }
}

/// One rendered picture, already in Display P3 with its transfer function.
///
/// `@unchecked Sendable` because `CGImage` is immutable once made but is not
/// annotated as Sendable in every SDK this builds against.
struct RenderedPrint: @unchecked Sendable {
    let image: CGImage
    let elapsedMs: Double
}

enum EngineError: Error, LocalizedError {
    case noDevice, noResources(String), engine(String), notOpen, badReply(String), frameTooLarge(Int)

    var errorDescription: String? {
        switch self {
        case .noDevice: "This device has no Metal GPU."
        case .noResources(let path): "The engine's resources are missing at \(path)."
        case .engine(let message): message
        case .notOpen: "No photo is open."
        case .badReply(let text): "The engine sent a reply this app cannot read: \(text)"
        case .frameTooLarge(let bytes): "Could not allocate \(bytes / 1_000_000) MB for the frame."
        }
    }
}

/// What `spk_open` reports that this app reads.
struct OpenReply: Decodable, Sendable {
    let sessionID: String
    let meta: Meta
    struct Meta: Decodable, Sendable { let width: Int; let height: Int }
    enum CodingKeys: String, CodingKey { case sessionID = "session_id", meta }
}

actor Engine {
    /// The C objects. ARC frees neither, so they live in a box whose `deinit`
    /// does, exactly as the Mac's `EngineClient.Handles`: session first, then
    /// the engine that made it.
    private final class Handles: @unchecked Sendable {
        var engine: OpaquePointer?
        var session: OpaquePointer?
        deinit {
            if let session { spk_session_release(session) }
            if let engine { spk_engine_destroy(engine) }
        }
    }

    private let handles = Handles()
    private let device: MTLDevice
    private let context: CIContext
    let resources: URL

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw EngineError.noDevice }
        self.device = device
        self.context = CIContext(mtlDevice: device, options: [
            .workingColorSpace: CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)!,
            .workingFormat: CIFormat.RGBAh,
            .cacheIntermediates: false,
        ])
        self.resources = Engine.resourcesURL
    }

    /// `engine/` in the app bundle, put there by `Tools/bundle-engine.sh`.
    static var resourcesURL: URL { Bundle.main.bundleURL.appending(path: "engine") }

    private func start() throws -> OpaquePointer {
        if let engine = handles.engine { return engine }
        let constants = resources.appending(path: "spektrafilm_constants.bin")
        guard FileManager.default.fileExists(atPath: constants.path) else {
            throw EngineError.noResources(resources.path)
        }
        guard let handle = resources.path.withCString({ path in
            spk_engine_create(path, Unmanaged.passUnretained(device).toOpaque())
        }) else {
            throw EngineError.engine(String(cString: spk_last_error(nil)))
        }
        handles.engine = handle
        return handle
    }

    private func lastError() -> String {
        guard let engine = handles.engine else { return "the render engine is not running" }
        let message = String(cString: spk_last_error(engine))
        return message.isEmpty ? "the render engine failed" : message
    }

    /// Take ownership of a `char*` the engine allocated and decode it.
    private func decode<R: Decodable>(_ buffer: UnsafeMutablePointer<CChar>?, as: R.Type) throws -> R {
        guard let buffer else { throw EngineError.badReply("no reply") }
        defer { spk_string_free(buffer) }
        let data = Data(bytes: buffer, count: strlen(buffer))
        do { return try JSONDecoder().decode(R.self, from: data) }
        catch { throw EngineError.badReply(String(decoding: data.prefix(300), as: UTF8.self)) }
    }

    private static func json(_ delta: [String: ParamValue]) throws -> String {
        String(decoding: try JSONEncoder().encode(delta), as: UTF8.self)
    }

    /// Pay the per-stock-pair setup ahead of the user (RFC-013 §3). Best-effort.
    func warmUp(film: String, print: String) {
        guard let engine = try? start() else { return }
        var out: UnsafeMutablePointer<CChar>?
        _ = spk_warm_up(engine, film, print, &out)
        if let out { spk_string_free(out) }
    }

    /// Open one frame, replacing the last.
    ///
    /// `linear` must already be scene-linear (Apple's RAW tone rendering off);
    /// it is rendered here into float32 RGBA **linear ProPhoto**, top row first,
    /// in a shared buffer on the engine's own device, which `spk_open_device`
    /// borrows for this call only -- the Mac's `ImageDecoder.engineFrame`, the
    /// same three load-bearing details: `toBitmap`, no vertical flip, linear
    /// ProPhoto as the destination.
    func open(_ linear: SendableImage, delta: [String: ParamValue]) throws -> OpenReply {
        let engine = try start()
        if let session = handles.session { spk_session_release(session); handles.session = nil }

        let image = linear.image
        let extent = image.extent.integral
        let width = Int(extent.width), height = Int(extent.height)
        guard width > 0, height > 0 else { throw EngineError.engine("the photo has no pixels") }
        let bytes = width * height * 16
        guard let buffer = device.makeBuffer(length: bytes, options: .storageModeShared) else {
            throw EngineError.frameTooLarge(bytes)
        }
        let placed = image.transformed(by: .init(translationX: -extent.origin.x, y: -extent.origin.y))
        // Not in an `autoreleasepool` closure as on the Mac: Swift 6 refuses to
        // send the actor's buffer into one. The pool this call's autoreleases
        // land in drains when the actor's job returns.
        context.render(placed, toBitmap: buffer.contents(), rowBytes: width * 16,
                       bounds: CGRect(x: 0, y: 0, width: width, height: height),
                       format: .RGBAf, colorSpace: ColourSpaces.linearProPhoto)

        var reply: UnsafeMutablePointer<CChar>?
        var frame = spk_device_image(buffer: Unmanaged.passUnretained(buffer).toOpaque(),
                                     width: UInt32(width), height: UInt32(height), channels: 4)
        let text = try Engine.json(delta)
        let handle: OpaquePointer? = text.withCString { deltaPtr in
            withUnsafePointer(to: &frame) { spk_open_device(engine, $0, deltaPtr, &reply) }
        }
        guard let handle else { throw EngineError.engine(lastError()) }
        handles.session = handle
        return try decode(reply, as: OpenReply.self)
    }

    /// What the meter would choose for the open frame, without changing it
    /// (`solve(target: "exposure")`): the baseline the Exposure slider offsets.
    func meteredEV() -> Double? {
        guard let session = handles.session else { return nil }
        struct Reply: Decodable { let solved_params: [String: Double] }
        var out: UnsafeMutablePointer<CChar>?
        guard spk_solve(session, "exposure", &out) == SPK_OK else {
            if let out { spk_string_free(out) }
            return nil
        }
        return (try? decode(out, as: Reply.self))?.solved_params["exposure_compensation_ev"]
    }

    /// Apply `delta` and print the cached negative at `tier` (`spk_reprint`):
    /// the engine re-develops the film side only when the delta touched it.
    /// "live" is the preview's size, "full" the frame's own.
    func render(delta: [String: ParamValue], tier: String) throws -> RenderedPrint {
        guard let session = handles.session else { throw EngineError.notOpen }
        if !delta.isEmpty {
            var out: UnsafeMutablePointer<CChar>?
            let text = try Engine.json(delta)
            guard text.withCString({ spk_set_params(session, $0, &out) }) == SPK_OK else {
                if let out { spk_string_free(out) }
                throw EngineError.engine(lastError())
            }
            if let out { spk_string_free(out) }
        }
        var result = spk_result()
        guard tier.withCString({ spk_reprint(session, $0, &result) }) == SPK_OK else {
            throw EngineError.engine(lastError())
        }
        // Copied before the free: `rgba16` lives exactly as long as `texture`.
        defer { spk_result_free(&result) }
        guard let pixels = result.rgba16, result.width > 0, result.height > 0 else {
            throw EngineError.engine("the render returned no pixels")
        }
        let width = Int(result.width), height = Int(result.height)
        // The row pitch is the device's, reported rather than assumed (spk_engine.h).
        let rowBytes = Int(result.row_stride_px) * 8
        let data = Data(bytes: pixels, count: rowBytes * height)
        guard let provider = CGDataProvider(data: data as CFData),
              let image = CGImage(width: width, height: height, bitsPerComponent: 16, bitsPerPixel: 64,
                                  bytesPerRow: rowBytes, space: ColourSpaces.displayP3,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue
                                                           | CGBitmapInfo.byteOrder16Little.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true,
                                  intent: .defaultIntent)
        else { throw EngineError.engine("could not wrap the render as an image") }
        return RenderedPrint(image: image, elapsedMs: result.elapsed_ms)
    }

    /// Drop the open frame and everything the engine cached for it.
    func close() {
        if let session = handles.session { spk_session_release(session) }
        handles.session = nil
    }
}

/// A `CIImage` handed across an actor boundary. Core Image images are
/// immutable recipes, so sharing one is safe; the type is not annotated.
struct SendableImage: @unchecked Sendable {
    let image: CIImage
}

enum ColourSpaces {
    /// Linear ProPhoto RGB, constructed: Core Graphics ships ROMM at gamma 1.8
    /// and linear sRGB/P3, but no linear ROMM. The engine's input convention
    /// (`io.input_color_space`), built exactly as the Mac's
    /// `ImageDecoder.linearProPhoto` builds it.
    static let linearProPhoto: CGColorSpace = {
        let d50: [CGFloat] = [0.9642, 1.0000, 0.8249]
        let black: [CGFloat] = [0, 0, 0]
        let gamma: [CGFloat] = [1, 1, 1]
        let primaries: [CGFloat] = [
            0.7976749, 0.2880402, 0.0000000,
            0.1351917, 0.7118741, 0.0000000,
            0.0313534, 0.0000857, 0.8252100,
        ]
        return d50.withUnsafeBufferPointer { wp in
            black.withUnsafeBufferPointer { bp in
                gamma.withUnsafeBufferPointer { gp in
                    primaries.withUnsafeBufferPointer { mp in
                        CGColorSpace(calibratedRGBWhitePoint: wp.baseAddress!, blackPoint: bp.baseAddress,
                                     gamma: gp.baseAddress!, matrix: mp.baseAddress)!
                    }
                }
            }
        }
    }()

    static let displayP3 = CGColorSpace(name: CGColorSpace.displayP3)!
}
