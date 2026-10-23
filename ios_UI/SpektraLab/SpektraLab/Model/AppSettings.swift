//  AppSettings.swift — the phone's two performance settings, persisted.
//
//  - **Preview size** is the engine's `live` tier long edge
//    (`io.preview_long_edge`, the Mac's "preview resolution"): every edit
//    renders at it.
//  - **Develop size** caps the frame the engine develops and the export's
//    full size. It is a memory decision rather than a look: the engine's
//    working planes scale with it, and a phone's memory limit is a fraction of
//    a Mac's. 12 MP is the default because it is what most iPhones shoot and
//    renders comfortably on a 4 GB device; 24 and 48 are there for newer ones.

import Foundation
import Observation

@MainActor
@Observable
final class AppSettings {
    static let previewChoices = [1280, 1600, 2048, 2560]
    static let developChoices = [8, 12, 24, 48]

    var previewLongEdge: Int {
        didSet { UserDefaults.standard.set(previewLongEdge, forKey: Keys.preview) }
    }
    var developMegapixels: Int {
        didSet { UserDefaults.standard.set(developMegapixels, forKey: Keys.develop) }
    }

    private enum Keys {
        static let preview = "ios.previewLongEdge"
        static let develop = "ios.developMegapixels"
    }

    init() {
        let preview = UserDefaults.standard.integer(forKey: Keys.preview)
        previewLongEdge = AppSettings.previewChoices.contains(preview) ? preview : 1600
        let develop = UserDefaults.standard.integer(forKey: Keys.develop)
        developMegapixels = AppSettings.developChoices.contains(develop) ? develop : 12
    }
}
