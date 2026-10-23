//  SettingsView.swift — language, the two performance settings, and the credits.

import SwiftUI

struct SettingsView: View {
    let model: EditorModel
    @Environment(\.dismiss) private var dismiss
    @State private var localization = Localization.shared

    var body: some View {
        @Bindable var app = model.app
        NavigationStack {
            Form {
                Section(L(.setLanguage)) {
                    Picker(L(.setLanguage), selection: $localization.language) {
                        ForEach(LanguageSetting.allCases) { Text($0.label).tag($0) }
                    }
                }
                Section {
                    Picker(L(.setPreviewSize), selection: $app.previewLongEdge) {
                        ForEach(AppSettings.previewChoices, id: \.self) { Text("\($0) px").tag($0) }
                    }
                    .onChange(of: app.previewLongEdge) { model.applyPreviewSize() }
                    Picker(L(.setDevelopSize), selection: $app.developMegapixels) {
                        ForEach(AppSettings.developChoices, id: \.self) { Text("\($0) MP").tag($0) }
                    }
                    .onChange(of: app.developMegapixels) { model.applyDevelopSize() }
                } header: {
                    Text(L(.setRendering))
                } footer: {
                    Text(L(.setDevelopSizeCaption))
                }
                Section(L(.setAbout)) {
                    Text(L(.setAboutText)).font(.footnote)
                    LabeledContent(L(.setEngine), value: String(cString: spk_build_info()))
                        .font(.footnote)
                }
            }
            .navigationTitle(L(.settingsTitle))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L(.actionDone)) { dismiss() } }
            }
        }
        .preferredColorScheme(.dark)
    }
}
