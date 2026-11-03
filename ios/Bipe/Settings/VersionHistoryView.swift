// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec version-history: what changed in each version and build, newest first. The list is tools/changelog.mjs --json,
/// which bipe-web publishes at the versionsData url (registries/settings.json). Only the changes for iOS show.
struct VersionHistoryView: View {
    struct Update: Decodable, Hashable {
        let id: String
        let kind: String
        let platforms: [String]
        let headline: String
        let detail: String
    }

    struct Build: Decodable, Hashable {
        let build: Int
        let version: String?
        let released: Bool?
        let releaseDate: String?
        let date: String
        let updates: [Update]
    }

    /// The builds of one version, newest first.
    struct Version: Hashable {
        let version: String?
        let released: Bool
        let releaseDate: String?
        let builds: [Build]
    }

    private enum Phase { case loading, failed, loaded([Version]) }

    @State private var phase = Phase.loading

    var body: some View {
        List {
            switch phase {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity)
            case .failed:
                VStack(spacing: 12) {
                    Text(BipeStrings.versionsError)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Button {
                        Task { await load() }
                    } label: {
                        Text(BipeStrings.appRetry)
                    }
                }
                .frame(maxWidth: .infinity)
            case .loaded(let versions):
                if AppVersion.language == "pt" {
                    Text(BipeStrings.versionsNote)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                ForEach(versions, id: \.self) { version in
                    Section {
                        ForEach(version.builds, id: \.build) { build in
                            ForEach(build.updates, id: \.id) { update in
                                UpdateRow(update: update, build: build)
                            }
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(version.version.map(BipeStrings.helpVersion) ?? "")
                            Text(Self.status(version))
                                .textCase(nil)
                        }
                    }
                }
            }
        }
        .navigationTitle(BipeStrings.helpVersions)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.versionHistory.rawValue)
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        if case .failed = phase { phase = .loading }
        guard let url = BipeSettingsUrl.versionsData.url(language: AppVersion.language) else {
            phase = .failed
            return
        }
        do {
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadRevalidatingCacheData
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            phase = .loaded(Self.versions(try JSONDecoder().decode([Build].self, from: data)))
        } catch {
            phase = .failed
        }
    }

    /// The builds grouped by version, with only the iOS changes. Builds with no iOS change drop out.
    static func versions(_ builds: [Build]) -> [Version] {
        var out: [Version] = []
        for build in builds {
            let updates = build.updates.filter { $0.platforms.contains("ios") }
            guard !updates.isEmpty else { continue }
            let mine = Build(build: build.build, version: build.version, released: build.released,
                             releaseDate: build.releaseDate, date: build.date, updates: updates)
            if let last = out.last, last.version == build.version {
                out[out.count - 1] = Version(version: last.version, released: last.released,
                                             releaseDate: last.releaseDate, builds: last.builds + [mine])
            } else {
                out.append(Version(version: build.version, released: build.released ?? false,
                                   releaseDate: build.releaseDate, builds: [mine]))
            }
        }
        return out
    }

    private static func status(_ version: Version) -> String {
        if version.released, let date = version.releaseDate.flatMap(day) {
            return BipeStrings.versionsReleased(date)
        }
        return BipeStrings.versionsTesting
    }

    /// "2026-11-03" as a day in the app language, for example "3 de novembro de 2026".
    static func day(_ text: String) -> String? {
        let parse = DateFormatter()
        parse.calendar = Calendar(identifier: .gregorian)
        parse.locale = Locale(identifier: "en_US_POSIX")
        parse.timeZone = TimeZone(identifier: "UTC")
        parse.dateFormat = "yyyy-MM-dd"
        guard let date = parse.date(from: text) else { return nil }
        let show = DateFormatter()
        show.locale = Locale(identifier: AppVersion.language == "pt" ? "pt_BR" : "en_GB")
        show.timeZone = TimeZone(identifier: "UTC")
        show.dateStyle = .long
        show.timeStyle = .none
        return show.string(from: date)
    }
}

/// One change: the headline, the detail, and its build, kind and day. Headline and detail are English and not
/// translated.
private struct UpdateRow: View {
    let update: VersionHistoryView.Update
    let build: VersionHistoryView.Build

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: update.headline)
                .fontWeight(.semibold)
                .foregroundStyle(BipeColor.inkPrimary)
            if !update.detail.isEmpty {
                Text(verbatim: update.detail)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            Text(verbatim: tags)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var tags: String {
        [BipeStrings.versionsBuild(String(build.build)), Self.kind(update.kind), VersionHistoryView.day(build.date)]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private static func kind(_ kind: String) -> String? {
        switch kind {
        case "new": BipeStrings.versionsKindNew
        case "improved": BipeStrings.versionsKindImproved
        case "fixed": BipeStrings.versionsKindFixed
        case "removed": BipeStrings.versionsKindRemoved
        default: nil
        }
    }
}
