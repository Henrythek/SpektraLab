//  Controls.swift — the phone's palette and the two controls every panel uses.
//
//  The colours are the Mac's `Theme` values (v3 sample), so the two apps read
//  as one product: a near-black card, warm white text, the amber accent.

import SwiftUI

enum Palette {
    static let canvas = Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x19 / 255)
    static let card = Color(red: 0x2D / 255, green: 0x2D / 255, blue: 0x2C / 255)
    static let rule = Color(red: 0x3A / 255, green: 0x3B / 255, blue: 0x39 / 255)
    static let text = Color(red: 0xF9 / 255, green: 0xF7 / 255, blue: 0xF3 / 255)
    static let dim = Color(red: 0x89 / 255, green: 0x89 / 255, blue: 0x89 / 255)
    static let accent = Color(red: 0xE1 / 255, green: 0xA9 / 255, blue: 0x5F / 255)
}

/// A labelled slider with its value, reset on double-tap.
///
/// `commitOnRelease` holds the value locally while the finger is down and
/// writes it once on release: for a control whose every change costs a
/// re-decode (white balance), not a reprint.
struct ParamSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var defaultValue: Double = 0
    var format: (Double) -> String = { String(format: "%+.1f", $0) }
    var commitOnRelease = false
    var disabled = false

    @State private var dragging: Double?

    var body: some View {
        let shown = dragging ?? value
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title).foregroundStyle(disabled ? Palette.dim : Palette.text)
                Spacer()
                Text(format(shown)).monospacedDigit().foregroundStyle(Palette.dim)
            }
            .font(.subheadline)
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { if !disabled { value = defaultValue } }
            Slider(value: Binding(get: { shown }, set: { new in
                if commitOnRelease { dragging = new } else { value = new }
            }), in: range, onEditingChanged: { editing in
                if !editing, commitOnRelease, let final = dragging {
                    dragging = nil
                    value = final
                }
            })
            .tint(Palette.accent)
            .disabled(disabled)
        }
        .padding(.vertical, 2)
    }
}

/// A row that switches an effect on and off, with its strength below it.
struct EffectRow: View {
    let title: String
    @Binding var active: Bool
    @Binding var strength: Double
    let range: ClosedRange<Double>
    var defaultStrength: Double = 1

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle(title, isOn: $active).tint(Palette.accent).foregroundStyle(Palette.text)
            ParamSlider(title: L(.effectStrength), value: $strength, range: range,
                        defaultValue: defaultStrength, format: { String(format: "%.2f", $0) },
                        disabled: !active)
                .padding(.leading, 12)
        }
    }
}

/// A section heading inside a panel.
struct PanelHeading: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundStyle(Palette.dim)
            .padding(.top, 8)
    }
}
