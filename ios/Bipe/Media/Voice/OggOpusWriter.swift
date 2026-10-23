// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AudioToolbox
import Foundation

/// Writes Opus packets into an Ogg Opus file (RFC 3533 pages, RFC 7845 headers): the wire format of
/// voice messages, which every Matrix client plays (spec voice-recording notes).
struct OggOpusWriter {
    private var data = Data()
    private let serial = UInt32.random(in: 1...UInt32.max)
    private var sequence: UInt32 = 0
    private var granule: Int64 = 0
    private var pending: [Data] = []
    private var pendingSamples = 0
    private let preSkip: UInt16

    /// Packets per page: 50 packets of 20 ms make a page of one second.
    private static let packetsPerPage = 50

    init(channels: UInt8 = 1, preSkip: UInt16, inputSampleRate: UInt32 = 48000) {
        self.preSkip = preSkip
        var head = Data("OpusHead".utf8)
        head.append(1)
        head.append(channels)
        head.append(littleEndian: preSkip)
        head.append(littleEndian: inputSampleRate)
        head.append(littleEndian: UInt16(0))
        head.append(0)
        writePage([head], granule: 0, flags: 0x02)
        var tags = Data("OpusTags".utf8)
        let vendor = Data("Bipe".utf8)
        tags.append(littleEndian: UInt32(vendor.count))
        tags.append(vendor)
        tags.append(littleEndian: UInt32(0))
        writePage([tags], granule: 0, flags: 0)
        granule = Int64(preSkip)
    }

    /// One Opus packet of samples frames at 48 kHz.
    mutating func append(packet: Data, samples: Int) {
        pending.append(packet)
        pendingSamples += samples
        if pending.count >= Self.packetsPerPage {
            flush(last: false)
        }
    }

    /// Ends the stream and returns the file contents.
    mutating func finish() -> Data {
        flush(last: true)
        return data
    }

    private mutating func flush(last: Bool) {
        guard !pending.isEmpty || last else { return }
        granule += Int64(pendingSamples)
        writePage(pending, granule: granule, flags: last ? 0x04 : 0)
        pending.removeAll()
        pendingSamples = 0
    }

    private mutating func writePage(_ packets: [Data], granule: Int64, flags: UInt8) {
        var lacing: [UInt8] = []
        for packet in packets {
            var remaining = packet.count
            while remaining >= 255 {
                lacing.append(255)
                remaining -= 255
            }
            lacing.append(UInt8(remaining))
        }
        var page = Data("OggS".utf8)
        page.append(0)
        page.append(flags)
        page.append(littleEndian: UInt64(bitPattern: granule))
        page.append(littleEndian: serial)
        page.append(littleEndian: sequence)
        let crcOffset = page.count
        page.append(littleEndian: UInt32(0))
        page.append(UInt8(lacing.count))
        page.append(contentsOf: lacing)
        for packet in packets {
            page.append(packet)
        }
        let crc = Self.crc(page)
        page.replaceSubrange(crcOffset..<(crcOffset + 4), with: withUnsafeBytes(of: crc.littleEndian) { Data($0) })
        data.append(page)
        sequence += 1
    }

    /// The Ogg CRC-32: polynomial 0x04C11DB7, no reflection, initial value 0.
    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index) << 24
        for _ in 0..<8 {
            value = value & 0x8000_0000 != 0 ? (value << 1) ^ 0x04C1_1DB7 : value << 1
        }
        return value
    }

    static func crc(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0
        for byte in data {
            crc = (crc << 8) ^ table[Int(((crc >> 24) ^ UInt32(byte)) & 0xFF)]
        }
        return crc
    }
}

extension OggOpusWriter {
    /// Turns the Opus CAF file of AVAudioRecorder into an Ogg Opus file: the same packets, other pages.
    static func remux(caf source: URL, to destination: URL) throws {
        var file: AudioFileID?
        guard AudioFileOpenURL(source as CFURL, .readPermission, kAudioFileCAFType, &file) == noErr, let file else {
            throw CocoaError(.fileReadCorruptFile)
        }
        defer { AudioFileClose(file) }
        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        AudioFileGetProperty(file, kAudioFilePropertyDataFormat, &size, &format)
        guard format.mFormatID == kAudioFormatOpus else { throw CocoaError(.fileReadCorruptFile) }
        var table = AudioFilePacketTableInfo()
        size = UInt32(MemoryLayout<AudioFilePacketTableInfo>.size)
        let hasTable = AudioFileGetProperty(file, kAudioFilePropertyPacketTableInfo, &size, &table) == noErr
        var maxPacket: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        AudioFileGetProperty(file, kAudioFilePropertyMaximumPacketSize, &size, &maxPacket)
        maxPacket = max(maxPacket, 1500)

        let preSkip = hasTable ? UInt16(clamping: table.mPrimingFrames) : 312
        var writer = OggOpusWriter(channels: UInt8(clamping: format.mChannelsPerFrame), preSkip: preSkip)
        var buffer = [UInt8](repeating: 0, count: Int(maxPacket))
        var packet: Int64 = 0
        while true {
            var bytes = maxPacket
            var count: UInt32 = 1
            var description = AudioStreamPacketDescription()
            let status = AudioFileReadPacketData(file, false, &bytes, &description, packet, &count, &buffer)
            guard status == noErr || status == kAudioFileEndOfFileError, count > 0 else { break }
            let samples = description.mVariableFramesInPacket > 0 ? Int(description.mVariableFramesInPacket) : Int(format.mFramesPerPacket)
            writer.append(packet: Data(buffer[0..<Int(bytes)]), samples: samples)
            packet += 1
        }
        try writer.finish().write(to: destination)
    }
}

private extension Data {
    mutating func append<T: FixedWidthInteger>(littleEndian value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}
