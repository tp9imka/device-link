// Renders the DeviceLink app icons from the Android receiver's adaptive icon, so both platforms
// share one source of truth:
//   apps/receiver/src/main/res/drawable/ic_launcher_background.xml  (gradient)
//   apps/receiver/src/main/res/drawable/ic_launcher_foreground.xml  (vector glyph)
//
// Run from the repository root:  swift ios/Tools/make_app_icon.swift
// Writes:
//   ios/DeviceLink/Assets.xcassets/AppIcon.appiconset  (1024x1024, opaque: iOS rejects alpha)
//   ios/Sample/Assets.xcassets/AppIcon.appiconset      (same image)
//   ios/Mac/Assets.xcassets/AppIcon.appiconset         (macOS grid: rounded tile with margin, 16-1024 px)
//
// Only CoreGraphics/ImageIO; no installs. Supports the vector subset Android icons use here
// (M L H V C A Z, absolute and relative, implicit repeats) and a two-colour linear gradient.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let res = root.appendingPathComponent("apps/receiver/src/main/res/drawable")

func read(_ name: String) -> String {
    guard let text = try? String(contentsOf: res.appendingPathComponent(name), encoding: .utf8) else {
        fatalError("Run from the repository root: missing \(name)")
    }
    return text
}

func attribute(_ name: String, in text: String) -> String? {
    guard let range = text.range(of: "android:\(name)=\"") else { return nil }
    let rest = text[range.upperBound...]
    return rest.firstIndex(of: "\"").map { String(rest[..<$0]) }
}

func color(_ hex: String) -> CGColor {
    var value: UInt64 = 0
    Scanner(string: String(hex.dropFirst())).scanHexInt64(&value)
    let rgb = hex.count == 9 ? value & 0xFFFFFF : value
    return CGColor(srgbRed: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                   blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
}

// MARK: Vector path parsing

struct PathParser {
    private let chars: [Character]
    private var i = 0
    init(_ text: String) { chars = Array(text) }

    private mutating func skip() { while i < chars.count, chars[i] == " " || chars[i] == "," || chars[i] == "\n" { i += 1 } }

    private mutating func number() -> CGFloat? {
        skip()
        let start = i
        if i < chars.count, chars[i] == "-" || chars[i] == "+" { i += 1 }
        var dot = false
        while i < chars.count, chars[i].isNumber || (chars[i] == "." && !dot) { if chars[i] == "." { dot = true }; i += 1 }
        if i < chars.count, chars[i] == "e" || chars[i] == "E" {
            i += 1
            if i < chars.count, chars[i] == "-" || chars[i] == "+" { i += 1 }
            while i < chars.count, chars[i].isNumber { i += 1 }
        }
        return i > start ? Double(String(chars[start..<i])).map { CGFloat($0) } : nil
    }

    /// Arc flags may be written without separators ("0,0 1" or "01").
    private mutating func flag() -> Bool? {
        skip()
        guard i < chars.count, chars[i] == "0" || chars[i] == "1" else { return nil }
        defer { i += 1 }
        return chars[i] == "1"
    }

    mutating func parse() -> CGPath {
        let path = CGMutablePath()
        var current = CGPoint.zero, start = CGPoint.zero
        var command: Character = "M"
        while true {
            skip()
            if i >= chars.count { break }
            if chars[i].isLetter { command = chars[i]; i += 1 }
            let relative = command.isLowercase
            let base = relative ? current : .zero
            switch command.uppercased() {
            case "M":
                guard let x = number(), let y = number() else { return path }
                current = CGPoint(x: base.x + x, y: base.y + y); start = current
                path.move(to: current)
                command = relative ? "l" : "L" // implicit repeats after M are line-tos
            case "L":
                guard let x = number(), let y = number() else { return path }
                current = CGPoint(x: base.x + x, y: base.y + y); path.addLine(to: current)
            case "H":
                guard let x = number() else { return path }
                current.x = base.x + x; path.addLine(to: current)
            case "V":
                guard let y = number() else { return path }
                current.y = (relative ? current.y : 0) + y; path.addLine(to: current)
            case "C":
                guard let x1 = number(), let y1 = number(), let x2 = number(), let y2 = number(),
                      let x = number(), let y = number() else { return path }
                let end = CGPoint(x: base.x + x, y: base.y + y)
                path.addCurve(to: end, control1: CGPoint(x: base.x + x1, y: base.y + y1), control2: CGPoint(x: base.x + x2, y: base.y + y2))
                current = end
            case "A":
                guard let rx = number(), let ry = number(), let rotation = number(), let large = flag(), let sweep = flag(),
                      let x = number(), let y = number() else { return path }
                let end = CGPoint(x: base.x + x, y: base.y + y)
                addArc(path, from: current, to: end, rx: rx, ry: ry, degrees: rotation, large: large, sweep: sweep)
                current = end
            case "Z":
                path.closeSubpath(); current = start
                continue
            default:
                fatalError("Unsupported path command \(command)")
            }
        }
        return path
    }
}

/// SVG endpoint arc to cubic Béziers (SVG 1.1 implementation notes, F.6.5).
func addArc(_ path: CGMutablePath, from p0: CGPoint, to p1: CGPoint, rx rx0: CGFloat, ry ry0: CGFloat,
            degrees: CGFloat, large: Bool, sweep: Bool) {
    var rx = abs(rx0), ry = abs(ry0)
    guard rx > 0, ry > 0, p0 != p1 else { path.addLine(to: p1); return }
    let phi = degrees * .pi / 180, c = cos(phi), s = sin(phi)
    let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
    let x1 = c * dx + s * dy, y1 = -s * dx + c * dy
    let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
    let num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    let den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    var k = sqrt(max(0, num / den))
    if large == sweep { k = -k }
    let cx1 = k * rx * y1 / ry, cy1 = -k * ry * x1 / rx
    let cx = c * cx1 - s * cy1 + (p0.x + p1.x) / 2, cy = s * cx1 + c * cy1 + (p0.y + p1.y) / 2
    func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
        let a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a
    }
    let theta = angle(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
    var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
    if !sweep && delta > 0 { delta -= 2 * .pi }
    if sweep && delta < 0 { delta += 2 * .pi }
    let segments = Int(ceil(abs(delta) / (.pi / 2)))
    let step = delta / CGFloat(segments)
    let t = 4.0 / 3.0 * tan(step / 4)
    func point(_ a: CGFloat) -> CGPoint {
        CGPoint(x: cx + rx * cos(a) * c - ry * sin(a) * s, y: cy + rx * cos(a) * s + ry * sin(a) * c)
    }
    func derivative(_ a: CGFloat) -> CGPoint {
        CGPoint(x: -rx * sin(a) * c - ry * cos(a) * s, y: -rx * sin(a) * s + ry * cos(a) * c)
    }
    var a = theta
    for _ in 0..<segments {
        let b = a + step
        let pa = point(a), pb = point(b), da = derivative(a), db = derivative(b)
        path.addCurve(to: pb, control1: CGPoint(x: pa.x + t * da.x, y: pa.y + t * da.y),
                      control2: CGPoint(x: pb.x - t * db.x, y: pb.y - t * db.y))
        a = b
    }
}

// MARK: Layers

let background = read("ic_launcher_background.xml")
let foreground = read("ic_launcher_foreground.xml")
let startColor = color(attribute("startColor", in: background) ?? "#2F6FED")
let endColor = color(attribute("endColor", in: background) ?? "#5B3FD9")
let gradientAngle = CGFloat(Double(attribute("angle", in: background) ?? "0") ?? 0)
let viewport = CGFloat(Double(attribute("viewportWidth", in: foreground) ?? "108") ?? 108)
let translate = CGPoint(x: CGFloat(Double(attribute("translateX", in: foreground) ?? "0") ?? 0),
                        y: CGFloat(Double(attribute("translateY", in: foreground) ?? "0") ?? 0))
let scale = CGPoint(x: CGFloat(Double(attribute("scaleX", in: foreground) ?? "1") ?? 1),
                    y: CGFloat(Double(attribute("scaleY", in: foreground) ?? "1") ?? 1))
let glyphColor = color(attribute("fillColor", in: foreground) ?? "#FFFFFF")
guard let pathData = attribute("pathData", in: foreground) else { fatalError("No pathData in foreground") }
var parser = PathParser(pathData)
let glyph = parser.parse()

/// A launcher shows the middle 72 of the adaptive icon's 108 units; the iOS icon shows the same region.
let visible: CGFloat = 72

/// Draws the icon into `rect` of a top-left-origin context (y flipped by the caller).
func drawIcon(_ context: CGContext, in rect: CGRect) {
    context.saveGState()
    context.clip(to: rect)
    // Android gradient angle: 0 = left to right, 90 = bottom to top (counterclockwise, y up).
    let radians = gradientAngle * .pi / 180
    let direction = CGPoint(x: cos(radians), y: -sin(radians)) // y down in this context
    let half = rect.width / 2
    let center = CGPoint(x: rect.midX, y: rect.midY)
    let from = CGPoint(x: center.x - direction.x * half, y: center.y - direction.y * half)
    let to = CGPoint(x: center.x + direction.x * half, y: center.y + direction.y * half)
    let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [startColor, endColor] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: from, end: to, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])

    let unit = rect.width / visible
    context.translateBy(x: rect.minX - (viewport - visible) / 2 * unit, y: rect.minY - (viewport - visible) / 2 * unit)
    context.scaleBy(x: unit, y: unit)
    context.translateBy(x: translate.x, y: translate.y)
    context.scaleBy(x: scale.x, y: scale.y)
    context.addPath(glyph)
    context.setFillColor(glyphColor)
    context.fillPath()
    context.restoreGState()
}

func makeContext(_ size: Int, opaque: Bool) -> CGContext {
    let info = opaque ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: info)!
    // Top-left origin, like Android's vector coordinates.
    context.translateBy(x: 0, y: CGFloat(size))
    context.scaleBy(x: 1, y: -1)
    context.interpolationQuality = .high
    return context
}

func write(_ image: CGImage, to url: URL) {
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { fatalError("PNG") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Could not write \(url.path)") }
}

/// iOS: full-bleed and opaque; the system applies the rounded mask.
func iosIcon() -> CGImage {
    let context = makeContext(1024, opaque: true)
    drawIcon(context, in: CGRect(x: 0, y: 0, width: 1024, height: 1024))
    return context.makeImage()!
}

/// macOS grid: an 824 pt rounded tile centred on a transparent 1024 canvas, scaled to `size`.
func macIcon(_ size: Int) -> CGImage {
    let context = makeContext(size, opaque: false)
    let s = CGFloat(size) / 1024
    let tile = CGRect(x: 100 * s, y: 100 * s, width: 824 * s, height: 824 * s)
    context.addPath(CGPath(roundedRect: tile, cornerWidth: 185 * s, cornerHeight: 185 * s, transform: nil))
    context.clip()
    drawIcon(context, in: tile)
    return context.makeImage()!
}

let fileManager = FileManager.default
for app in ["DeviceLink", "Sample"] {
    let set = root.appendingPathComponent("ios/\(app)/Assets.xcassets/AppIcon.appiconset")
    write(iosIcon(), to: set.appendingPathComponent("AppIcon-1024.png"))
    let contents = """
    {
      "images" : [
        { "filename" : "AppIcon-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
      ],
      "info" : { "author" : "ios/Tools/make_app_icon.swift", "version" : 1 }
    }

    """
    try! contents.write(to: set.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
    try! "{\n  \"info\" : { \"author\" : \"ios/Tools/make_app_icon.swift\", \"version\" : 1 }\n}\n"
        .write(to: set.deletingLastPathComponent().appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
}

let macSet = root.appendingPathComponent("ios/Mac/Assets.xcassets/AppIcon.appiconset")
var entries: [String] = []
for points in [16, 32, 128, 256, 512] {
    for scaleFactor in [1, 2] {
        let pixels = points * scaleFactor
        let name = "AppIcon-\(points)@\(scaleFactor)x.png"
        write(macIcon(pixels), to: macSet.appendingPathComponent(name))
        entries.append("    { \"filename\" : \"\(name)\", \"idiom\" : \"mac\", \"scale\" : \"\(scaleFactor)x\", \"size\" : \"\(points)x\(points)\" }")
    }
}
try! "{\n  \"images\" : [\n\(entries.joined(separator: ",\n"))\n  ],\n  \"info\" : { \"author\" : \"ios/Tools/make_app_icon.swift\", \"version\" : 1 }\n}\n"
    .write(to: macSet.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
try! "{\n  \"info\" : { \"author\" : \"ios/Tools/make_app_icon.swift\", \"version\" : 1 }\n}\n"
    .write(to: macSet.deletingLastPathComponent().appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)

print("Wrote AppIcon sets for DeviceLink, Sample and Mac")
