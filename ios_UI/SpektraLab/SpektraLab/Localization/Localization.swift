//  Localization.swift — which language the interface is drawn in.
//
//  The Mac app's design, kept on the phone: a Swift string table (`Strings.swift`)
//  rather than a strings catalogue, so the language can change from Settings
//  while the app runs, and an `@Observable` whose `resolved` every `L(_:)`
//  reads, so a change repaints every label. The resolution rule is the Mac's
//  `Localization.resolve`, unchanged: the first preferred tag this app has
//  copy for wins, and Traditional Chinese is deliberately *not* a match for
//  Simplified (it falls through to English).
//
//  The one string iOS itself shows, the Photos permission prompt, cannot come
//  from here; it is in `en.lproj` / `zh-Hans.lproj/InfoPlist.strings`.

import Foundation
import Observation

enum Language: String, CaseIterable, Sendable {
    case english, simplifiedChinese
}

enum LanguageSetting: String, CaseIterable, Identifiable, Sendable {
    case system, english, simplifiedChinese
    var id: String { rawValue }

    /// Each language is named in itself, never in the active one: a language
    /// menu is reached for *because* the current language is wrong.
    @MainActor var label: String {
        switch self {
        case .system: L(.languageFollowSystem)
        case .english: "English"
        case .simplifiedChinese: "简体中文"
        }
    }
}

@MainActor
@Observable
final class Localization {
    static let shared = Localization()

    var language: LanguageSetting {
        didSet {
            guard language != oldValue else { return }
            UserDefaults.standard.set(language.rawValue, forKey: Localization.key)
        }
    }

    var resolved: Language {
        switch language {
        case .english: .english
        case .simplifiedChinese: .simplifiedChinese
        case .system: Self.resolve(Locale.preferredLanguages)
        }
    }

    private static let key = "ios.language"

    private init() {
        language = UserDefaults.standard.string(forKey: Localization.key)
            .flatMap(LanguageSetting.init(rawValue:)) ?? .system
    }

    /// The Mac's rule, verbatim in behaviour.
    nonisolated static func resolve(_ preferred: [String]) -> Language {
        for tag in preferred {
            let parts = tag.lowercased().split(whereSeparator: { $0 == "-" || $0 == "_" }).map(String.init)
            guard let primary = parts.first else { continue }
            if primary == "en" { return .english }
            if primary == "zh" {
                if parts.contains("hant") { continue }
                if parts.contains("hans") { return .simplifiedChinese }
                if parts.contains("tw") || parts.contains("hk") || parts.contains("mo") { continue }
                return .simplifiedChinese
            }
        }
        return .english
    }
}

/// The string for `key` in the active language.
@MainActor func L(_ key: S) -> String {
    Localization.shared.resolved == .simplifiedChinese ? key.text.zh : key.text.en
}

/// A format string from the table, filled in.
@MainActor func L(_ key: S, _ args: CVarArg...) -> String {
    String(format: L(key), arguments: args)
}
