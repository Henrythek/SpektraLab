//  EditorView.swift — the print, and the tools that change it.
//
//  Portrait: the print above, the panel below, the tool bar at the bottom,
//  the shape of the phone's own Photos editor. Landscape and iPad: the panel
//  goes to the right, as the Mac's right rail does.
//
//  Press and hold the print to see the original (Apple's rendering of the
//  photo), the phone's form of the Mac's before/after.

import PhotosUI
import SwiftUI

struct EditorView: View {
    @Bindable var model: EditorModel
    @State private var tool = Tool.film
    @State private var showOriginal = false
    @State private var showSettings = false
    @State private var showExport = false
    @State private var picked: PhotosPickerItem?
    @State private var zoom: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @State private var offset = CGSize.zero
    @State private var drag = CGSize.zero

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height && geo.size.width > 600
            VStack(spacing: 0) {
                topBar
                if wide {
                    HStack(spacing: 0) {
                        canvas
                        VStack(spacing: 0) {
                            toolBar
                            Divider().overlay(Palette.rule)
                            ToolPanel(model: model, tool: tool)
                        }
                        .frame(width: min(380, geo.size.width * 0.4))
                        .background(Palette.card)
                    }
                } else {
                    canvas
                    ToolPanel(model: model, tool: tool)
                        .frame(height: min(300, geo.size.height * 0.38))
                        .background(Palette.card)
                    toolBar.background(Palette.card)
                }
            }
        }
        .background(Palette.canvas.ignoresSafeArea())
        .sheet(isPresented: $showSettings) { SettingsView(model: model) }
        .sheet(isPresented: $showExport) { ExportSheet(model: model) }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            Task { await PhotoImport.open(item, into: model) }
        }
    }

    private var topBar: some View {
        HStack(spacing: 16) {
            PhotosPicker(selection: $picked, matching: .images, preferredItemEncoding: .current) {
                Image(systemName: "photo.on.rectangle.angled")
            }
            .accessibilityLabel(L(.actionChoosePhoto))
            Spacer()
            if model.isRendering {
                ProgressView().tint(Palette.dim)
            } else if let ms = model.lastRenderMs {
                Text(String(format: "%.0f ms", ms)).font(.caption2).monospacedDigit().foregroundStyle(Palette.dim)
            }
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .accessibilityLabel(L(.settingsTitle))
            Button(L(.actionExport)) { showExport = true }
                .buttonStyle(.borderedProminent)
                .tint(Palette.accent)
                .foregroundStyle(Color.black)
                .disabled(model.phase != .ready)
        }
        .font(.title3)
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var canvas: some View {
        ZStack {
            Palette.canvas
            if let image = showOriginal ? (model.originalImage ?? model.displayImage) : model.displayImage {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .scaleEffect(zoom * pinch)
                    .offset(x: offset.width + drag.width, y: offset.height + drag.height)
                    .padding(12)
            } else if let original = model.originalImage {
                Image(decorative: original, scale: 1).resizable().scaledToFit().padding(12).opacity(0.5)
            }
            switch model.phase {
            case .opening:
                VStack(spacing: 8) {
                    ProgressView().tint(Palette.text)
                    Text(L(.statusDeveloping)).font(.footnote).foregroundStyle(Palette.dim)
                }
            case .failed(let message):
                Text(message).font(.footnote).foregroundStyle(Palette.text).padding()
            default:
                EmptyView()
            }
            VStack {
                if showOriginal {
                    Text(L(.actionOriginal)).font(.caption.weight(.semibold))
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(.black.opacity(0.6), in: Capsule())
                        .foregroundStyle(Palette.text)
                }
                Spacer()
                if let error = model.errorMessage {
                    Text(error).font(.caption).foregroundStyle(Palette.text)
                        .padding(8).background(.red.opacity(0.6), in: RoundedRectangle(cornerRadius: 6))
                }
            }
            .padding(8)
        }
        .clipped()
        .contentShape(Rectangle())
        .gesture(MagnifyGesture()
            .onChanged { pinch = $0.magnification }
            .onEnded { _ in
                zoom = min(max(zoom * pinch, 1), 8)
                pinch = 1
                if zoom == 1 { offset = .zero }
            })
        .simultaneousGesture(DragGesture()
            .onChanged { if zoom > 1 { drag = $0.translation } }
            .onEnded { _ in
                offset.width += drag.width
                offset.height += drag.height
                drag = .zero
            })
        .onTapGesture(count: 2) {
            withAnimation(.snappy) {
                zoom = zoom > 1 ? 1 : 2.5
                offset = .zero
            }
        }
        .onLongPressGesture(minimumDuration: 0.15, perform: {}, onPressingChanged: { pressing in
            showOriginal = pressing
        })
        .accessibilityHint(L(.helpHoldForOriginal))
    }

    private var toolBar: some View {
        HStack(spacing: 0) {
            ForEach(Tool.allCases) { t in
                Button { tool = t } label: {
                    VStack(spacing: 3) {
                        Image(systemName: t.symbol).font(.system(size: 18))
                        Text(t.title).font(.caption2)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .foregroundStyle(tool == t ? Palette.accent : Palette.dim)
                }
                .buttonStyle(.plain)
            }
        }
    }
}
