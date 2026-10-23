//  LibraryView.swift — the first screen, and the two ways a photo arrives.
//
//  From Photos, the picker runs out of process and needs no library
//  permission. `preferredItemEncoding: .current` asks for the original file
//  rather than a compatible JPEG, so a ProRAW arrives as its DNG and goes
//  through `CIRAWFilter` like any RAW. From Files, a security-scoped URL is
//  copied in before the scope closes. Either way the model owns a copy in the
//  temporary directory and never touches the original.

import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A photo from the picker, as a file. RAW is listed first so that a RAW (or
/// a RAW+JPEG pair) arrives as the RAW.
struct PickedPhoto: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .rawImage) { received in
            PickedPhoto(url: try PhotoImport.copy(received.file))
        }
        FileRepresentation(importedContentType: .image) { received in
            PickedPhoto(url: try PhotoImport.copy(received.file))
        }
    }
}

enum PhotoImport {
    static func copy(_ file: URL) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appending(path: "Imports", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let ext = file.pathExtension.isEmpty ? "img" : file.pathExtension
        let dest = folder.appending(path: "\(UUID().uuidString).\(ext)")
        try FileManager.default.copyItem(at: file, to: dest)
        return dest
    }

    @MainActor
    static func open(_ item: PhotosPickerItem, into model: EditorModel) async {
        do {
            guard let photo = try await item.loadTransferable(type: PickedPhoto.self) else { return }
            await model.open(photo.url)
        } catch {
            model.failToOpen(error)
        }
    }

    @MainActor
    static func open(file: URL, into model: EditorModel) async {
        let scoped = file.startAccessingSecurityScopedResource()
        defer { if scoped { file.stopAccessingSecurityScopedResource() } }
        do {
            let url = try copy(file)
            await model.open(url)
        } catch {
            model.failToOpen(error)
        }
    }
}

struct LibraryView: View {
    let model: EditorModel
    @State private var picked: PhotosPickerItem?
    @State private var showFiles = false
    @State private var showSettings = false

    var body: some View {
        VStack(spacing: 22) {
            HStack {
                Spacer()
                Button { showSettings = true } label: { Image(systemName: "gearshape").font(.title3) }
                    .foregroundStyle(Palette.text)
                    .accessibilityLabel(L(.settingsTitle))
            }
            Spacer()
            Image(systemName: "film.stack")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(Palette.accent)
            Text("SpektraLab")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(Palette.text)
            Text(L(.libraryPrompt))
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.dim)
                .padding(.horizontal, 32)
            VStack(spacing: 12) {
                PhotosPicker(selection: $picked, matching: .images, preferredItemEncoding: .current) {
                    Label(L(.actionChoosePhoto), systemImage: "photo.on.rectangle")
                        .frame(maxWidth: 280)
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.accent)
                .foregroundStyle(Color.black)
                Button { showFiles = true } label: {
                    Label(L(.actionOpenFile), systemImage: "folder").frame(maxWidth: 280)
                }
                .buttonStyle(.bordered)
                .tint(Palette.text)
            }
            .controlSize(.large)
            Text(L(.libraryFormats)).font(.caption).foregroundStyle(Palette.dim)
            if case .failed(let message) = model.phase {
                Text(message).font(.footnote).foregroundStyle(.red).padding(.horizontal, 24)
            }
            Spacer()
        }
        .padding(16)
        .background(Palette.canvas.ignoresSafeArea())
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            Task { await PhotoImport.open(item, into: model) }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.rawImage, .image]) { result in
            guard case .success(let url) = result else { return }
            Task { await PhotoImport.open(file: url, into: model) }
        }
        .sheet(isPresented: $showSettings) { SettingsView(model: model) }
    }
}
