//  Panels.swift — the five tool panels under (or beside) the print.
//
//  Film, Print, Camera and Effects are Layer 1: every control writes a
//  `FilmSettings` field and so a schema field, and the engine renders it.
//  Finish is Layer 2 (`Finish.swift`): it never reaches the engine.

import SwiftUI

enum Tool: String, CaseIterable, Identifiable {
    case film, print, camera, effects, finish
    var id: String { rawValue }

    @MainActor var title: String {
        switch self {
        case .film: L(.tabFilm)
        case .print: L(.tabPrint)
        case .camera: L(.tabCamera)
        case .effects: L(.tabEffects)
        case .finish: L(.tabFinish)
        }
    }

    var symbol: String {
        switch self {
        case .film: "film"
        case .print: "photo.on.rectangle"
        case .camera: "camera.aperture"
        case .effects: "sparkles"
        case .finish: "slider.horizontal.3"
        }
    }
}

struct ToolPanel: View {
    @Bindable var model: EditorModel
    let tool: Tool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                switch tool {
                case .film: FilmPanel(model: model)
                case .print: PrintPanel(model: model)
                case .camera: CameraPanel(model: model)
                case .effects: EffectsPanel(model: model)
                case .finish: FinishPanel(model: model)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .scrollIndicators(.hidden)
    }
}

// MARK: - Film

struct FilmPanel: View {
    @Bindable var model: EditorModel
    @State private var group = StockCatalog.FilmGroup.negative
    private let catalog = StockCatalog.shared

    var body: some View {
        Picker("", selection: $group) {
            ForEach(StockCatalog.FilmGroup.allCases, id: \.self) { g in
                Text(title(of: g)).tag(g)
            }
        }
        .pickerStyle(.segmented)

        ScrollView(.horizontal) {
            LazyHStack(spacing: 10) {
                ForEach(catalog.films(in: group)) { stock in
                    StockCard(stock: stock, selected: stock.id == model.settings.filmStock) {
                        model.settings.selectFilm(stock.id)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .scrollIndicators(.hidden)

        PanelHeading(text: L(.filmFormat))
        Picker(L(.filmFormat), selection: $model.settings.frame) {
            ForEach(FilmFrame.all) { frame in
                Text(frame.isCine ? "\(frame.id) · CINE" : frame.id).tag(frame.id)
            }
        }
        .pickerStyle(.menu)
        .tint(Palette.accent)
        Text(L(.filmFormatCaption)).font(.caption).foregroundStyle(Palette.dim)
    }

    private func title(of group: StockCatalog.FilmGroup) -> String {
        switch group {
        case .negative: L(.filmGroupNegative)
        case .positive: L(.filmGroupPositive)
        case .blackAndWhite: L(.filmGroupBlackAndWhite)
        case .cine: L(.filmGroupCine)
        }
    }
}

/// A film box: its cover where the catalogue has one, its name otherwise.
/// Names are never translated (they are product names, and catalogue keys).
struct StockCard: View {
    let stock: Stock
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(Palette.card)
                    if let url = stock.coverURL, let image = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: image).resizable().scaledToFill()
                    } else {
                        Image(systemName: stock.isMonochrome ? "circle.lefthalf.filled" : "film")
                            .font(.title)
                            .foregroundStyle(Palette.dim)
                    }
                }
                .frame(width: 92, height: 92)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Palette.accent : Color.clear, lineWidth: 2))
                Text(stock.name)
                    .font(.caption2)
                    .foregroundStyle(selected ? Palette.text : Palette.dim)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .frame(width: 92, height: 30, alignment: .top)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Print

struct PrintPanel: View {
    @Bindable var model: EditorModel
    private let catalog = StockCatalog.shared

    var body: some View {
        let film = model.settings.filmStock
        Toggle(L(.printNone), isOn: $model.settings.scanFilm)
            .tint(Palette.accent)
            .foregroundStyle(Palette.text)
        if !model.settings.scanFilm {
            PanelHeading(text: L(.printPaper))
            ForEach(catalog.papers.filter { catalog.canPrint(film: film, on: $0.id) }) { paper in
                Button {
                    model.settings.printStock = paper.id
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(paper.name).foregroundStyle(Palette.text)
                            if catalog.isDeclaredPairing(film: film, paper: paper.id) {
                                Text(L(.printDeclared)).font(.caption2).foregroundStyle(Palette.dim)
                            } else if paper.isCine {
                                Text(L(.printGroupCine)).font(.caption2).foregroundStyle(Palette.dim)
                            }
                        }
                        Spacer()
                        if paper.id == model.settings.printStock {
                            Image(systemName: "checkmark").foregroundStyle(Palette.accent)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.vertical, 4)
            }
            PanelHeading(text: L(.sectionEnlarger))
            ParamSlider(title: L(.printBrightness), value: $model.settings.printBrightness, range: -2...2,
                        format: { String(format: "%+.2f EV", $0) })
            ParamSlider(title: L(.printYellow), value: $model.settings.yellowShift, range: -1...1,
                        format: { String(format: "%+.0f CC", $0 * FilmSettings.filterShiftCC) })
            ParamSlider(title: L(.printMagenta), value: $model.settings.magentaShift, range: -1...1,
                        format: { String(format: "%+.0f CC", $0 * FilmSettings.filterShiftCC) })
        }
    }
}

// MARK: - Camera

struct CameraPanel: View {
    @Bindable var model: EditorModel

    var body: some View {
        PanelHeading(text: L(.cameraMetering))
        Picker(L(.cameraMetering), selection: $model.settings.metering) {
            ForEach(Metering.allCases) { m in Text(title(of: m)).tag(m) }
        }
        .pickerStyle(.menu)
        .tint(Palette.accent)

        ParamSlider(title: L(.cameraFilmExposure), value: $model.settings.exposureEV, range: -4...4,
                    format: { String(format: "%+.1f EV", $0) })
        if model.settings.metering != .custom, let ev = model.meteredEV {
            Text(L(.cameraMetered, String(format: "%+.1f", ev)))
                .font(.caption).foregroundStyle(Palette.dim)
        }

        PanelHeading(text: L(.sectionWhiteBalance))
        Toggle(L(.cameraAsShot), isOn: Binding(
            get: { model.settings.whiteBalance.asShot },
            set: { asShot in
                var wb = model.settings.whiteBalance
                wb.asShot = asShot
                if !asShot {
                    wb.temperature = model.asShotTemperature ?? 6500
                    wb.tint = model.asShotTint ?? 0
                }
                model.settings.whiteBalance = wb
            }))
            .tint(Palette.accent)
            .foregroundStyle(Palette.text)
        ParamSlider(title: L(.cameraTemperature), value: $model.settings.whiteBalance.temperature,
                    range: 2000...12000, defaultValue: model.asShotTemperature ?? 6500,
                    format: { String(format: "%.0f K", $0) }, commitOnRelease: true,
                    disabled: model.settings.whiteBalance.asShot)
        ParamSlider(title: L(.cameraTint), value: $model.settings.whiteBalance.tint,
                    range: -150...150, defaultValue: model.asShotTint ?? 0,
                    format: { String(format: "%+.0f", $0) }, commitOnRelease: true,
                    disabled: model.settings.whiteBalance.asShot)
        if !model.isRAW {
            Text(L(.cameraFlatWhiteBalance)).font(.caption).foregroundStyle(Palette.dim)
        }
    }

    private func title(of m: Metering) -> String {
        switch m {
        case .custom: L(.meteringCustom)
        case .balanced: L(.meteringBalanced)
        case .center: L(.meteringCenter)
        case .protectHighlights: L(.meteringProtectHighlights)
        case .protectShadows: L(.meteringProtectShadows)
        }
    }
}

// MARK: - Effects

struct EffectsPanel: View {
    @Bindable var model: EditorModel

    var body: some View {
        EffectRow(title: L(.filmGrain), active: $model.settings.grainActive,
                  strength: $model.settings.grain, range: 0...2)
        EffectRow(title: L(.filmHalation), active: $model.settings.halationActive,
                  strength: $model.settings.halation, range: 0...4)
        EffectRow(title: L(.filmCouplers), active: $model.settings.couplersActive,
                  strength: $model.settings.couplers, range: 0...1.5)
        EffectRow(title: L(.filmGlare), active: $model.settings.glareActive,
                  strength: $model.settings.glare, range: 0...10)
        Button(L(.actionResetLook), role: .destructive) { model.resetLook() }
            .padding(.top, 8)
    }
}

// MARK: - Finish

struct FinishPanel: View {
    @Bindable var model: EditorModel

    var body: some View {
        Text(L(.finishCaption)).font(.caption).foregroundStyle(Palette.dim)
        ParamSlider(title: L(.finishExposure), value: $model.finish.exposure, range: -3...3,
                    format: { String(format: "%+.2f EV", $0) })
        ParamSlider(title: L(.finishContrast), value: $model.finish.contrast, range: -50...50,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.finishSaturation), value: $model.finish.saturation, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.finishHighlights), value: $model.finish.highlights, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.finishShadows), value: $model.finish.shadows, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.cameraTemperature), value: $model.finish.temperature, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.cameraTint), value: $model.finish.tint, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        ParamSlider(title: L(.finishVignette), value: $model.finish.vignette, range: -100...100,
                    format: { String(format: "%+.0f", $0) })
        Button(L(.actionReset)) { model.finish = .neutral }
            .tint(Palette.accent)
            .padding(.top, 6)
    }
}
