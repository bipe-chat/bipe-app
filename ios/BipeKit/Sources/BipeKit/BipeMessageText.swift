// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import SwiftSoup

/// Parses strings only. It never loads images, styles, scripts or other remote resources.
public struct BipeMessageText: Equatable, Sendable {
    public struct Run: Equatable, Sendable {
        public var text: String
        public var bold = false
        public var italic = false
        public var strike = false
        public var code = false
        public var link: String?
    }

    public let runs: [Run]
    public var text: String { runs.map(\.text).joined() }
    /// Check the visible text and destinations, including a destination hidden behind a friendly label.
    public var safetyText: String { ([text] + runs.compactMap(\.link)).joined(separator: "\n") }

    public init(body: String, html: String? = nil) {
        let parsed = html.flatMap { value -> [Run]? in
            guard value.utf16.count <= BipeMessageFormatting.maxHtmlLength else { return nil }
            return try? Builder().parse(value)
        }
        runs = Self.linkify(parsed?.isEmpty == false ? parsed! : [Run(text: body)])
    }

    public static func safeLink(_ raw: String) -> String? {
        guard !raw.isEmpty, !raw.contains(where: { $0.isWhitespace || $0.isNewline }),
              !raw.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }),
              let url = URLComponents(string: raw), let scheme = url.scheme?.lowercased(),
              BipeMessageFormatting.safeSchemes.contains(scheme) else { return nil }
        if scheme == "http" || scheme == "https" {
            guard let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else { return nil }
        }
        return raw
    }

    private static func linkify(_ runs: [Run]) -> [Run] {
        let patterns = [BipeMessageFormatting.linkPattern, BipeMessageFormatting.phonePattern]
        return runs.flatMap { run -> [Run] in
            guard run.link == nil, !run.code else { return [run] }
            let ns = run.text as NSString
            let candidates = patterns.enumerated().flatMap { index, pattern in
                (try? NSRegularExpression(pattern: pattern, options: .caseInsensitive))?
                    .matches(in: run.text, range: NSRange(location: 0, length: ns.length))
                    .map { ($0.range, index) } ?? []
            }.sorted { $0.0.location == $1.0.location ? $0.1 < $1.1 : $0.0.location < $1.0.location }
            var result: [Run] = []
            var cursor = 0
            for (range, kind) in candidates where range.location >= cursor {
                var value = ns.substring(with: range)
                while let last = value.last, ".,;!?".contains(last) { value.removeLast() }
                for (open, close) in [("(", ")"), ("[", "]"), ("{", "}")] {
                    while value.hasSuffix(close), value.filter({ String($0) == close }).count > value.filter({ String($0) == open }).count {
                        value.removeLast()
                    }
                }
                let destination = kind == 1 ? "tel:" + value.filter { $0.isNumber || $0 == "+" }
                    : value.lowercased().hasPrefix("www.") ? "https://" + value
                    : value.contains("@") && !value.contains("://") ? "mailto:" + value : value
                guard let link = safeLink(destination), !value.isEmpty else { continue }
                if range.location > cursor {
                    var before = run
                    before.text = ns.substring(with: NSRange(location: cursor, length: range.location - cursor))
                    result.append(before)
                }
                var linked = run
                linked.text = value
                linked.link = link
                result.append(linked)
                cursor = range.location + (value as NSString).length
            }
            if cursor < ns.length {
                var tail = run
                tail.text = ns.substring(from: cursor)
                result.append(tail)
            }
            return result
        }
    }

    private final class Builder {
        private enum Limit: Error { case depth }
        var runs: [Run] = []
        var lists: [(ordered: Bool, next: Int)] = []

        func parse(_ html: String) throws -> [Run] {
            let document = try SwiftSoup.parseBodyFragment(html)
            if let body = document.body() { try visit(body, style: Run(text: ""), depth: 0) }
            while let last = runs.last, last.text.last?.isWhitespace == true {
                runs[runs.count - 1].text.removeLast()
                if runs.last?.text.isEmpty == true { runs.removeLast() }
            }
            return runs
        }

        func append(_ raw: String, style: Run, preserve: Bool = false) {
            var value = preserve ? raw : raw.replacingOccurrences(of: "[\\t\\r\\n ]+", with: " ", options: .regularExpression)
            if !preserve, runs.isEmpty || runs.last?.text.last?.isWhitespace == true {
                while value.hasPrefix(" ") { value.removeFirst() }
            }
            guard !value.isEmpty else { return }
            var run = style
            run.text = value
            runs.append(run)
        }

        func line() {
            if let last = runs.last, !last.text.hasSuffix("\n") { runs.append(Run(text: "\n")) }
        }

        func visit(_ node: SwiftSoup.Node, style: Run, depth: Int) throws {
            guard depth <= BipeMessageFormatting.maxDepth else { throw Limit.depth }
            if let text = node as? TextNode {
                append(text.getWholeText(), style: style, preserve: style.code)
                return
            }
            guard let element = node as? SwiftSoup.Element else { return }
            let tag = element.tagName().lowercased()
            guard !BipeMessageFormatting.dropContentTags.contains(tag) else { return }
            if tag == "br" { runs.append(Run(text: "\n")); return }
            var next = style
            next.bold = next.bold || BipeMessageFormatting.boldTags.contains(tag)
            next.italic = next.italic || BipeMessageFormatting.italicTags.contains(tag)
            next.strike = next.strike || BipeMessageFormatting.strikeTags.contains(tag)
            next.code = next.code || BipeMessageFormatting.codeTags.contains(tag)
            if tag == "a" { next.link = BipeMessageText.safeLink(try element.attr("href")) }
            let block = BipeMessageFormatting.blockTags.contains(tag)
            if block { line() }
            if tag == "blockquote" { append("│ ", style: Run(text: "")) }
            if tag == "ul" || tag == "ol" {
                line()
                lists.append((tag == "ol", max(1, min(1000, Int(try element.attr("start")) ?? 1))))
            }
            if tag == "li" {
                line()
                let item = lists.last
                let prefix = String(repeating: "  ", count: max(0, lists.count - 1)) +
                    (item?.ordered == true ? "\(item!.next). " : "• ")
                append(prefix, style: Run(text: ""), preserve: true)
                if !lists.isEmpty { lists[lists.count - 1].next += 1 }
            }
            for child in element.getChildNodes() { try visit(child, style: next, depth: depth + 1) }
            if tag == "ul" || tag == "ol" { lists.removeLast(); line() }
            if block || tag == "li" { line() }
        }

    }
}
