//  FilmSettings.swift — Layer 1, the engine's parameters, as the phone holds them.
//
//  A subset of the Mac's `FilmParams` with the same wire: every name below is
//  a schema name (`engine/src/core/params.cpp`, API-SPEC §3), and every value
//  is derived the way `FilmParams.wire` derives it, so a frame printed on the
//  phone and on the Mac with the same settings asks the engine for the same
//  thing. Where a rule is the Mac's, the comment says so and does not restate
//  the reason; `modern_UI/.../Model/Params.swift` has it.
//
//  What the phone leaves out for now: Scene Placement (RFC-023), the Tone Mask
//  (off on the Mac too), the film edge and date back (RFC-031/032), the
//  Digital Intermediate, crop and half-frame pairs.

import Foundation

/// The four metering intents plus Custom, which is the meter switched off
/// (`auto_exposure = false`), exactly the Mac's `AEMethod`.
enum Metering: String, CaseIterable, Codable, Identifiable, Sendable {
    case custom, balanced, center
    case protectHighlights = "protect_highlights"
    case protectShadows = "protect_shadows"
    var id: String { rawValue }
}

/// A film frame by its short side, the Mac's `FilmFrame` with Side = Short:
/// the wire's `film_format_mm` (the long edge) is derived from the photo's own
/// aspect, so "120" is right for 645, 6×6 and 6×7 alike.
struct FilmFrame: Identifiable, Hashable, Sendable {
    let id: String
    let shortSideMM: Double
    let isCine: Bool

    static let all: [FilmFrame] = [
        .init(id: "110", shortSideMM: 13, isCine: false),
        .init(id: "APS", shortSideMM: 16.7, isCine: false),
        .init(id: "135", shortSideMM: 24, isCine: false),
        .init(id: "120", shortSideMM: 56, isCine: false),
        .init(id: "Super 8", shortSideMM: 4.01, isCine: true),
        .init(id: "16mm", shortSideMM: 7.49, isCine: true),
        .init(id: "Super 16", shortSideMM: 7.41, isCine: true),
        .init(id: "Super 35", shortSideMM: 14.0, isCine: true),
    ]
    static func named(_ id: String) -> FilmFrame { all.first { $0.id == id } ?? all[2] }
}

struct FilmSettings: Codable, Equatable, Sendable {
    var filmStock = "kodak_portra_400"
    var printStock = "kodak_portra_endura"
    /// No print stage: the negative (or slide) is scanned directly.
    var scanFilm = false

    var metering = Metering.balanced
    /// Stops, an offset from the meter (or the whole exposure under Custom).
    var exposureEV = 0.0
    var whiteBalance = WhiteBalance()

    var frame = "135"

    var grainActive = true
    var grain = 1.0            // 0…2, times the film's own (`ownGrain`)
    var halationActive = true
    var halation = 1.0         // 0…4
    var couplersActive = true
    var couplers = 1.0         // 0…1.5
    var glareActive = true
    var glare = 1.0            // 0…30

    /// Print brightness in stops; the wire's `print_exposure` is 2^-stops.
    var printBrightness = 0.0
    var yellowShift = 0.0      // −1…1 of `filterShiftCC`
    var magentaShift = 0.0

    static let `default` = FilmSettings()

    // MARK: rules the film list applies (the Mac's `Session.selectFilmStock`)

    /// Choose a film: follow its declared paper, and keep a paper only if it
    /// can print the film. A slide film is scanned; leaving one for a negative
    /// brings the print stage back.
    mutating func selectFilm(_ id: String, catalog: StockCatalog = .shared) {
        let wasPositive = catalog.stock(filmStock)?.isPositive ?? false
        filmStock = id
        if let target = catalog.stock(id)?.targetPrint, catalog.stock(target) != nil,
           !catalog.isDeclaredPairing(film: id, paper: printStock) {
            printStock = target
        }
        if !catalog.canPrint(film: id, on: printStock) {
            printStock = catalog.stock(id)?.targetPrint ?? FilmSettings.default.printStock
        }
        let isPositive = catalog.stock(id)?.isPositive ?? false
        if isPositive { scanFilm = true } else if wasPositive { scanFilm = false }
    }

    var filmIsMonochrome: Bool { StockCatalog.shared.stock(filmStock)?.isMonochrome ?? false }
    var printIsMonochrome: Bool { StockCatalog.shared.stock(printStock)?.isMonochrome ?? false }

    // MARK: the wire

    /// The Mac's `FilmParams.filterShiftCC`: the slider's ends, in CC.
    static let filterShiftCC = 40.0
    /// Multigrade filter 2, the Mac's `FilmParams.multigradeFilter2`.
    static let multigradeFilter2 = (c: 0.0, m: 0.0, y: 68.0)

    /// The Mac's `FilmParams.ownGrain`: what a strength of 1 is on each B&W film.
    static func ownGrain(of stock: String) -> Double {
        switch stock {
        case "kodak_tri_x_400": 1.70
        case "ilford_hp5_plus_400": 1.49
        case "kodak_tmax_100": 0.74
        case "fujifilm_neopan_acros_100_ii": 0.64
        default: 1
        }
    }

    /// Every field, as `params_delta` names it. `aspect` is the photo's
    /// long/short ratio, which turns the frame's short side into the wire's
    /// long edge (the Mac's `Session.filmFormatMM`).
    func wire(aspect: Double) -> [String: ParamValue] {
        let filmFormat = (FilmFrame.named(frame).shortSideMM * max(aspect, 1)).clamped(to: 4...200)
        var fields: [String: ParamValue] = [
            "film_stock": .string(filmStock),
            "print_stock": .string(printStock),
            "scan_film": .bool(scanFilm),
            "exposure_compensation_ev": .double(exposureEV.clamped(to: -8...8)),
            "auto_exposure": .bool(metering != .custom),
            "auto_exposure_method": .string(metering == .custom ? Metering.balanced.rawValue : metering.rawValue),
            "film_format_mm": .double(filmFormat),
            "grain_active": .bool(grainActive),
            // Never on a B&W film (owner, 2026-10-09); see the Mac's wire.
            "grain_sublayers_active": .bool(grainActive && !filmIsMonochrome),
            "grain_amount": .double((grain * FilmSettings.ownGrain(of: filmStock)).clamped(to: 0...2)),
            "halation_active": .bool(halationActive),
            "halation_amount": .double(halation.clamped(to: 0...4)),
            "dir_couplers_active": .bool(couplersActive),
            "dir_couplers_amount": .double(couplers.clamped(to: 0...1.5)),
            "glare_active": .bool(glareActive),
            "glare_amount": .double(glare.clamped(to: 0...30)),
            "print_exposure": .double(pow(2.0, -printBrightness).clamped(to: 0.05...20)),
            "y_filter_shift": .double(yellowShift.clamped(to: -1...1)),
            "m_filter_shift": .double(magentaShift.clamped(to: -1...1)),
            "filter_shift_scale": .double(FilmSettings.filterShiftCC),
        ]
        // B&W on its paper: the enlarger's pack, because the engine's table has
        // no row for a silver paper. Only then, as on the Mac.
        if filmIsMonochrome && printIsMonochrome {
            let f = FilmSettings.multigradeFilter2
            fields["c_filter_neutral"] = .double(f.c)
            fields["m_filter_neutral"] = .double(f.m)
            fields["y_filter_neutral"] = .double(f.y)
        }
        return fields
    }

    /// The fields that differ from `previous`'s wire.
    func delta(from previous: [String: ParamValue], aspect: Double) -> [String: ParamValue] {
        wire(aspect: aspect).filter { previous[$0.key] != $0.value }
    }
}

/// Session settings that are not part of a frame's look: the space the engine
/// renders into on this device, and the preview's size.
enum SessionWire {
    static func fields(previewLongEdge: Int) -> [String: ParamValue] {
        ["output_color_space": .string("Display P3"),
         "output_cctf_encoding": .bool(true),
         "preview_long_edge": .double(Double(previewLongEdge))]
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self { min(max(self, range.lowerBound), range.upperBound) }
}
