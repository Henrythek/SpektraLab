//  EditorModel.swift — one photo, its film settings, and the print on screen.
//
//  The phone's `Session`, much smaller: one frame at a time, no library, no
//  sidecars. What it keeps from the Mac's is the flow --
//
//      decode (linear + display)  →  engine open (with the full wire)
//                                 →  meter  →  reprint at the live tier
//
//  -- and the rule that an edit sends only the fields it changed, so the engine
//  re-develops the negative only when a shoot-layer field moved and otherwise
//  reprints the cached one (API-SPEC §3's layers).
//
//  Edits arriving while a render runs are coalesced: the loop renders the
//  latest settings when the current render finishes, never every tick of a
//  slider. White balance is the exception that costs a re-decode, because it
//  happens before the engine (`PhotoDecoder`), so it is applied when its
//  slider is released.

import CoreImage
import Foundation
import Observation
import Photos

@MainActor
@Observable
final class EditorModel {
    enum Phase: Equatable { case empty, opening, ready, failed(String) }

    private(set) var phase = Phase.empty
    /// The print, with the finish applied: what the canvas shows.
    private(set) var displayImage: CGImage?
    /// The photo as Apple renders it: what "hold for original" shows.
    private(set) var originalImage: CGImage?
    private(set) var isRendering = false
    private(set) var meteredEV: Double?
    private(set) var lastRenderMs: Double?
    private(set) var errorMessage: String?
    private(set) var asShotTemperature: Double?
    private(set) var asShotTint: Double?
    private(set) var isRAW = false
    private(set) var sourceName = ""

    var settings = FilmSettings.default {
        didSet {
            guard settings != oldValue else { return }
            if settings.whiteBalance != oldValue.whiteBalance { needsRedecode = true }
            scheduleRender()
        }
    }

    var finish = Finish.neutral {
        didSet { if finish != oldValue { refreshDisplay() } }
    }

    let app: AppSettings

    private var engine: Engine?
    private var decoded: DecodedPhoto?
    private var sourceURL: URL?
    private var printImage: CGImage?
    /// The wire as the engine last received it.
    private var sent: [String: ParamValue] = [:]
    private var renderTask: Task<Void, Never>?
    private var dirty = false
    private var needsRedecode = false
    private var finishGeneration = 0

    init(app: AppSettings) {
        self.app = app
    }

    private var aspect: Double {
        guard let size = decoded?.pixelSize, size.width > 0, size.height > 0 else { return 1.5 }
        return max(size.width, size.height) / min(size.width, size.height)
    }

    private func engineOrStart() throws -> Engine {
        if let engine { return engine }
        let made = try Engine()
        engine = made
        return made
    }

    // MARK: - open

    /// Open `url`, a file this model now owns (a copy in the temporary
    /// directory, deleted when the next photo replaces it).
    func open(_ url: URL) async {
        if let previous = sourceURL, previous != url { try? FileManager.default.removeItem(at: previous) }
        sourceURL = url
        sourceName = url.lastPathComponent
        // Before the settings change, so the change does not start a render of
        // the photo being replaced.
        phase = .opening
        // A new photo keeps the film and print but starts from its own white balance.
        settings.whiteBalance = WhiteBalance()
        needsRedecode = false
        displayImage = nil
        originalImage = nil
        printImage = nil
        await developFromSource()
    }

    private func developFromSource() async {
        guard let url = sourceURL else { return }
        let wb = settings.whiteBalance
        let maxPixels = app.developMegapixels * 1_000_000
        do {
            let photo = try await Task.detached(priority: .userInitiated) {
                try PhotoDecoder.decode(url, whiteBalance: wb, maxPixels: maxPixels)
            }.value
            decoded = photo
            isRAW = photo.isRAW
            asShotTemperature = photo.asShotTemperature
            asShotTint = photo.asShotTint
            let edge = app.previewLongEdge
            let display = SendableImage(image: photo.display)
            originalImage = await Task.detached(priority: .userInitiated) {
                Renderer.preview(display, longEdge: edge)
            }.value?.image
            try await develop(photo)
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    private func develop(_ photo: DecodedPhoto) async throws {
        let engine = try engineOrStart()
        let wire = settings.wire(aspect: aspect)
        let delta = wire.merging(SessionWire.fields(previewLongEdge: app.previewLongEdge)) { $1 }
        isRendering = true
        defer { isRendering = false }
        _ = try await engine.open(SendableImage(image: photo.linear), delta: delta)
        sent = wire
        meteredEV = await engine.meteredEV()
        let print = try await engine.render(delta: [:], tier: "live")
        lastRenderMs = print.elapsedMs
        printImage = print.image
        errorMessage = nil
        phase = .ready
        refreshDisplay()
        // Edits made while the frame was opening were not in the open's wire.
        if dirty { scheduleRender() }
    }

    /// A photo that could not even be copied in.
    func failToOpen(_ error: Error) {
        phase = .failed(error.localizedDescription)
    }

    /// Whether there is a photo to edit: opening, open, or failed on top of a
    /// print that is still on screen.
    var showsEditor: Bool {
        switch phase {
        case .empty: false
        case .failed: printImage != nil
        case .opening, .ready: true
        }
    }

    // MARK: - edits

    private func scheduleRender() {
        dirty = true
        guard renderTask == nil, phase == .ready else { return }
        renderTask = Task { [weak self] in
            await self?.renderLoop()
        }
    }

    private func renderLoop() async {
        defer { renderTask = nil }
        while dirty {
            dirty = false
            if needsRedecode {
                needsRedecode = false
                await developFromSource()
                continue
            }
            guard let engine else { return }
            let delta = settings.delta(from: sent, aspect: aspect)
            guard !delta.isEmpty else { continue }
            isRendering = true
            do {
                let print = try await engine.render(delta: delta, tier: "live")
                sent.merge(delta) { $1 }
                if !Set(delta.keys).isDisjoint(with: ["film_stock", "auto_exposure", "auto_exposure_method"]) {
                    meteredEV = await engine.meteredEV()
                }
                lastRenderMs = print.elapsedMs
                printImage = print.image
                errorMessage = nil
                refreshDisplay()
            } catch {
                errorMessage = error.localizedDescription
                // The engine refused the delta; resend the whole wire next time.
                sent = [:]
            }
            isRendering = false
        }
    }

    /// The preview size changed in Settings: the live tier's long edge is a
    /// session setting, sent on its own, and the print redrawn.
    func applyPreviewSize() {
        guard phase == .ready, let engine else { return }
        let fields = SessionWire.fields(previewLongEdge: app.previewLongEdge)
        Task {
            if let print = try? await engine.render(delta: fields, tier: "live") {
                printImage = print.image
                refreshDisplay()
            }
        }
    }

    /// The develop size changed in Settings: decode and open again.
    func applyDevelopSize() {
        guard phase == .ready else { return }
        needsRedecode = true
        scheduleRender()
    }

    func resetLook() {
        let film = settings.filmStock, print = settings.printStock, scan = settings.scanFilm
        var fresh = FilmSettings.default
        fresh.filmStock = film
        fresh.printStock = print
        fresh.scanFilm = scan
        fresh.whiteBalance = settings.whiteBalance
        settings = fresh
        finish = .neutral
    }

    private func refreshDisplay() {
        guard let printImage else { displayImage = nil; return }
        guard !finish.isNeutral else { displayImage = printImage; return }
        finishGeneration += 1
        let generation = finishGeneration
        let input = RenderedPrint(image: printImage, elapsedMs: 0)
        let finish = self.finish
        Task {
            let out = await Task.detached(priority: .userInitiated) {
                Renderer.finished(input, finish: finish, sixteenBit: false)
            }.value
            guard generation == finishGeneration, let out else { return }
            displayImage = out.image
        }
    }

    // MARK: - export

    /// The print at the frame's full develop size, finished and encoded to a
    /// file in the temporary directory.
    func export(format: ExportFormat, longEdge: Int?) async throws -> URL {
        guard let engine, let decoded, phase == .ready else { throw EngineError.notOpen }
        // Whatever the canvas has not sent yet goes with the export.
        let delta = settings.delta(from: sent, aspect: aspect)
        isRendering = true
        defer { isRendering = false }
        let print = try await engine.render(delta: delta, tier: "full")
        sent.merge(delta) { $1 }
        let finish = self.finish
        let name = (decoded.sourceName as NSString).deletingPathExtension + " SpektraLab"
        let properties = SendableProperties(value: decoded.properties)
        return try await Task.detached(priority: .userInitiated) {
            try Exporter.write(print, finish: finish, format: format, longEdge: longEdge,
                               properties: properties, baseName: name)
        }.value
    }

    /// Add a finished file to the photo library (add-only access).
    func saveToPhotos(_ url: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw ExportError.photosDenied }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, fileURL: url, options: nil)
        }
    }
}
