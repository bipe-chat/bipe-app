// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Compression
import Foundation

/// Reads the entries of a ZIP file in memory (PKWARE APPNOTE: the end of central directory record,
/// the central directory, then each local file header). Stored and deflated entries; no ZIP64, no encryption.
public struct ZipReader {
    public enum ZipError: Error {
        case notZip
        case unsupported
        case corrupt
    }

    public struct Entry {
        public let name: String
        public let isDirectory: Bool
        public let uncompressedSize: Int
        fileprivate let method: UInt16
        fileprivate let compressedSize: Int
        fileprivate let localHeaderOffset: Int
        fileprivate let data: Data

        /// The entry's bytes.
        public func read() throws -> Data {
            let bytes = data
            guard localHeaderOffset + 30 <= bytes.count, bytes.uint32(at: localHeaderOffset) == 0x0403_4B50 else { throw ZipError.corrupt }
            let nameLength = Int(bytes.uint16(at: localHeaderOffset + 26))
            let extraLength = Int(bytes.uint16(at: localHeaderOffset + 28))
            let start = localHeaderOffset + 30 + nameLength + extraLength
            guard start + compressedSize <= bytes.count else { throw ZipError.corrupt }
            let compressed = bytes.subdata(in: (bytes.startIndex + start)..<(bytes.startIndex + start + compressedSize))
            switch method {
            case 0:
                return compressed
            case 8:
                return try Self.inflate(compressed, size: uncompressedSize)
            default:
                throw ZipError.unsupported
            }
        }

        /// Raw deflate (RFC 1951) with the Compression framework, whose zlib mode reads raw deflate streams.
        private static func inflate(_ data: Data, size: Int) throws -> Data {
            guard size > 0 else { return Data() }
            var output = Data(count: size)
            let written = output.withUnsafeMutableBytes { destination in
                data.withUnsafeBytes { source in
                    compression_decode_buffer(destination.bindMemory(to: UInt8.self).baseAddress!, size,
                                              source.bindMemory(to: UInt8.self).baseAddress!, data.count,
                                              nil, COMPRESSION_ZLIB)
                }
            }
            guard written == size else { throw ZipError.corrupt }
            return output
        }
    }

    private let data: Data

    public init(data: Data) {
        self.data = Data(data)
    }

    public func entries() throws -> [Entry] {
        // The end of central directory record is in the last 65,557 bytes.
        let minimum = 22
        guard data.count >= minimum else { throw ZipError.notZip }
        var end = -1
        var index = data.count - minimum
        let lowest = max(0, data.count - 65_557)
        while index >= lowest {
            if data.uint32(at: index) == 0x0605_4B50 {
                end = index
                break
            }
            index -= 1
        }
        guard end >= 0 else { throw ZipError.notZip }
        let count = Int(data.uint16(at: end + 10))
        var offset = Int(data.uint32(at: end + 16))
        var entries: [Entry] = []
        for _ in 0..<count {
            guard offset + 46 <= data.count, data.uint32(at: offset) == 0x0201_4B50 else { throw ZipError.corrupt }
            let method = data.uint16(at: offset + 10)
            let compressedSize = Int(data.uint32(at: offset + 20))
            let uncompressedSize = Int(data.uint32(at: offset + 24))
            let nameLength = Int(data.uint16(at: offset + 28))
            let extraLength = Int(data.uint16(at: offset + 30))
            let commentLength = Int(data.uint16(at: offset + 32))
            let localHeaderOffset = Int(data.uint32(at: offset + 42))
            guard offset + 46 + nameLength <= data.count else { throw ZipError.corrupt }
            let nameData = data.subdata(in: (data.startIndex + offset + 46)..<(data.startIndex + offset + 46 + nameLength))
            let name = String(decoding: nameData, as: UTF8.self)
            entries.append(Entry(name: name,
                                 isDirectory: name.hasSuffix("/"),
                                 uncompressedSize: uncompressedSize,
                                 method: method,
                                 compressedSize: compressedSize,
                                 localHeaderOffset: localHeaderOffset,
                                 data: data))
            offset += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }
}

private extension Data {
    func uint16(at offset: Int) -> UInt16 {
        UInt16(self[startIndex + offset]) | UInt16(self[startIndex + offset + 1]) << 8
    }

    func uint32(at offset: Int) -> UInt32 {
        UInt32(uint16(at: offset)) | UInt32(uint16(at: offset + 2)) << 16
    }
}
