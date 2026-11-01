// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-colors, ADR 0213 and 0215: the chat background behind the timeline. marca draws the blobs and the Bipe
/// marks of BipeChatBackground on chat.background; frutas repeats the BipeChatFruits tile; cor is chat.background
/// alone; liso is surface.base. It stays still while
/// the messages scroll. The chat colours are themed, so it follows the palette; in high contrast every chat colour
/// is the flat chat.background, so marca shows only the colour.
struct ChatBackgroundView: View {
    /// A fixed option for the Cores preview; nil reads the stored choice.
    var option: BipeChatBackground?
    @AppStorage(AppTheme.chatBackgroundKey, store: AppTheme.defaults) private var stored = BipeChatBackground.standard.rawValue

    var body: some View {
        switch option ?? BipeChatBackground(rawValue: stored) ?? .standard {
        case .liso: BipeColor.surfaceBase
        case .cor: BipeColor.chatBackground
        case .marca: composition
        case .frutas: FruitTiles()
        }
    }

    private var composition: some View {
        GeometryReader { box in
            let size = box.size
            let long = max(size.width, size.height), short = min(size.width, size.height)
            ZStack(alignment: .topLeading) {
                BipeColor.chatBackground
                ForEach(BipeChatBackground.blobs.indices, id: \.self) { index in
                    let blob = BipeChatBackground.blobs[index]
                    RadialGradient(colors: [blob.tone.colour.opacity(BipeChatBackground.blobOpacity), blob.tone.colour.opacity(0)],
                                   center: UnitPoint(x: blob.x, y: blob.y), startRadius: 0, endRadius: blob.radius * long)
                }
                // In drawing order: the big mark is last, on top.
                ForEach(BipeChatBackground.marks.indices, id: \.self) { index in
                    let mark = BipeChatBackground.marks[index]
                    let side = mark.side * short
                    BipeMark()
                        .fill(mark.tone.colour.opacity(mark.opacity))
                        .frame(width: side, height: side)
                        .offset(x: mark.x * size.width, y: mark.y * size.height)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .clipped()
        }
        .accessibilityHidden(true)
    }
}

/// ADR 0215: the Salada de frutas tile, repeated over the view, each tile clipped to its square. Bodies are filled
/// with chatFruitFill and outlined, lines are outlined, dots are filled, all in chatFruit.
private struct FruitTiles: View {
    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(BipeColor.chatBackground))
            let side = BipeChatFruits.tile * BipeChatFruits.scale
            var y = 0.0
            while y < size.height {
                var x = 0.0
                while x < size.width {
                    var tile = context
                    tile.clip(to: Path(CGRect(x: x, y: y, width: side, height: side)))
                    tile.translateBy(x: x, y: y)
                    tile.scaleBy(x: BipeChatFruits.scale, y: BipeChatFruits.scale)
                    for placement in BipeChatFruits.placements { draw(placement, in: tile) }
                    x += side
                }
                y += side
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ placement: BipeChatFruits.Placement, in tile: GraphicsContext) {
        var context = tile
        let scale = placement.size / 48
        context.translateBy(x: placement.x, y: placement.y)
        context.rotate(by: .degrees(placement.rotation))
        context.scaleBy(x: placement.mirror ? -scale : scale, y: scale)
        context.translateBy(x: -24, y: -24)
        // The same outline width on every glyph, whatever its size.
        let outline = StrokeStyle(lineWidth: BipeChatFruits.stroke / scale, lineCap: .round, lineJoin: .round)
        let glyph = FruitPaths.glyphs[placement.glyph]
        for body in glyph.bodies {
            context.fill(body, with: .color(BipeColor.chatFruitFill))
            context.stroke(body, with: .color(BipeColor.chatFruit), style: outline)
        }
        for line in glyph.lines { context.stroke(line, with: .color(BipeColor.chatFruit), style: outline) }
        for dot in glyph.dots { context.fill(dot, with: .color(BipeColor.chatFruit)) }
    }
}

/// The BipeChatFruits glyphs as paths, built once.
private struct FruitPaths: Sendable {
    let bodies: [Path], lines: [Path], dots: [Path]

    static let glyphs: [FruitPaths] = BipeChatFruits.glyphs.map {
        FruitPaths(bodies: $0.bodies.map(FruitPaths.path), lines: $0.lines.map(FruitPaths.path), dots: $0.dots.map(FruitPaths.path))
    }

    /// A flat command list: 0 x y move, 1 x y line, 2 x1 y1 x2 y2 x y cubic, 3 close.
    private static func path(_ values: [Float]) -> Path {
        var path = Path()
        var index = 0
        func point(_ at: Int) -> CGPoint { CGPoint(x: CGFloat(values[at]), y: CGFloat(values[at + 1])) }
        while index < values.count {
            switch values[index] {
            case 0:
                path.move(to: point(index + 1))
                index += 3
            case 1:
                path.addLine(to: point(index + 1))
                index += 3
            case 2:
                path.addCurve(to: point(index + 5), control1: point(index + 1), control2: point(index + 3))
                index += 7
            default:
                path.closeSubpath()
                index += 1
            }
        }
        return path
    }
}

extension BipeChatBackground {
    /// The option name, bipe_chats_background_<option>.
    var title: String {
        switch self {
        case .marca: BipeStrings.chatsBackgroundMarca
        case .frutas: BipeStrings.chatsBackgroundFrutas
        case .cor: BipeStrings.chatsBackgroundCor
        case .liso: BipeStrings.chatsBackgroundLiso
        }
    }
}
