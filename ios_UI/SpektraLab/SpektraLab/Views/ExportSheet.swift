//  ExportSheet.swift — print at full size, then save to Photos or share.
//
//  "Full" is the frame as the engine developed it, which is the photo capped
//  at Settings → Develop size; the smaller sizes scale the finished print.

import SwiftUI
import UIKit

struct ExportSheet: View {
    let model: EditorModel
    @Environment(\.dismiss) private var dismiss
    @State private var format = ExportFormat.jpeg
    @State private var longEdge = 0
    @State private var working = false
    @State private var message: String?
    @State private var shareURL: URL?

    private static let sizes = [0, 4096, 2048]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L(.exportFormat), selection: $format) {
                        ForEach(ExportFormat.allCases) { Text($0.title).tag($0) }
                    }
                    Picker(L(.exportSize), selection: $longEdge) {
                        ForEach(Self.sizes, id: \.self) { size in
                            Text(size == 0 ? L(.exportSizeFull) : "\(size) px").tag(size)
                        }
                    }
                }
                Section {
                    Button {
                        run { url in
                            try await model.saveToPhotos(url)
                            message = L(.exportSaved)
                        }
                    } label: {
                        Label(L(.exportSaveToPhotos), systemImage: "square.and.arrow.down")
                    }
                    Button {
                        run { url in shareURL = url }
                    } label: {
                        Label(L(.exportShare), systemImage: "square.and.arrow.up")
                    }
                } footer: {
                    if working {
                        HStack(spacing: 8) { ProgressView(); Text(L(.statusPrinting)) }
                    } else if let message {
                        Text(message)
                    }
                }
                .disabled(working)
            }
            .navigationTitle(L(.actionExport))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L(.actionDone)) { dismiss() } }
            }
            .sheet(item: $shareURL) { url in ShareSheet(items: [url]) }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }

    private func run(_ then: @escaping @MainActor (URL) async throws -> Void) {
        working = true
        message = nil
        Task {
            defer { working = false }
            do {
                let url = try await model.export(format: format, longEdge: longEdge == 0 ? nil : longEdge)
                try await then(url)
            } catch ExportError.photosDenied {
                message = L(.exportPhotosDenied)
            } catch {
                message = L(.exportFailed, error.localizedDescription)
            }
        }
    }
}

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

/// The system share sheet, for a finished file.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
