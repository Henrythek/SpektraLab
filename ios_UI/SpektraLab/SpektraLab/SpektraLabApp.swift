//  SpektraLabApp.swift — SpektraLab for iPhone and iPad.
//
//  The Mac app's engine and film library, on a phone: open a photo from
//  Photos or Files, choose a film and a paper, set the camera and the
//  effects, finish the print, and export it. `ios_UI/README.md` says what the
//  phone has of the Mac's editor and what it does not yet.

import SwiftUI

@main
struct SpektraLabApp: App {
    @State private var model = EditorModel(app: AppSettings())

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
        }
    }
}

struct RootView: View {
    let model: EditorModel

    var body: some View {
        if model.showsEditor {
            EditorView(model: model)
        } else {
            LibraryView(model: model)
        }
    }
}
