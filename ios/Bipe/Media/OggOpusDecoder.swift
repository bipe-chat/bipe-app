// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AudioToolbox
import AVFoundation
import Foundation

/// Voice messages on Matrix are Opus in an Ogg container (audio/ogg). AVAudioPlayer has no Ogg
/// reader, but the system has an Opus decoder. This reads the Ogg pages (RFC 3533), takes the
/// Opus packets (RFC 7845), decodes them with AVAudioConverter and writes a PCM file that
/// AVAudioPlayer plays and seeks.
enum OggOpusDecoder {
    enum DecodeError: Error {
        case notOgg
        case noOpusHeader
        case converter
    }

    static func isOgg(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: 4)) == Data("OggS".utf8)
    }

    /// Decodes the Ogg Opus file at source into a CAF file of 32-bit float PCM at destination.
    static func decode(_ source: URL, to destination: URL) throws {
        let packets = try packets(Data(contentsOf: source))
        guard let head = packets.first, head.starts(with: Data("OpusHead".utf8)), head.count >= 19 else {
            throw DecodeError.noOpusHeader
        }
        let channels = UInt32(max(1, min(2, Int(head[head.startIndex + 9]))))
        let preSkip = Int(head[head.startIndex + 10]) | Int(head[head.startIndex + 11]) << 8

        var input = AudioStreamBasicDescription(mSampleRate: 48000,
                                                mFormatID: kAudioFormatOpus,
                                                mFormatFlags: 0,
                                                mBytesPerPacket: 0,
                                                mFramesPerPacket: 960,
                                                mBytesPerFrame: 0,
                                                mChannelsPerFrame: channels,
                                                mBitsPerChannel: 0,
                                                mReserved: 0)
        guard let inputFormat = AVAudioFormat(streamDescription: &input),
              let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                               sampleRate: 48000,
                                               channels: AVAudioChannelCount(channels),
                                               interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            throw DecodeError.converter
        }
        try? FileManager.default.removeItem(at: destination)
        let file = try AVAudioFile(forWriting: destination,
                                   settings: outputFormat.settings,
                                   commonFormat: .pcmFormatFloat32,
                                   interleaved: false)
        var toSkip = preSkip
        // Packet 0 is OpusHead, packet 1 is OpusTags; audio follows.
        for packet in packets.dropFirst(2) where !packet.isEmpty {
            let compressed = AVAudioCompressedBuffer(format: inputFormat, packetCapacity: 1, maximumPacketSize: packet.count)
            packet.withUnsafeBytes { bytes in
                compressed.data.copyMemory(from: bytes.baseAddress!, byteCount: packet.count)
            }
            compressed.packetDescriptions?[0] = AudioStreamPacketDescription(mStartOffset: 0,
                                                                            mVariableFramesInPacket: UInt32(frames(packet)),
                                                                            mDataByteSize: UInt32(packet.count))
            compressed.packetCount = 1
            compressed.byteLength = UInt32(packet.count)

            guard let pcm = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: 5760) else { throw DecodeError.converter }
            var supplied = false
            var error: NSError?
            let status = converter.convert(to: pcm, error: &error) { _, inputStatus in
                if supplied {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                supplied = true
                inputStatus.pointee = .haveData
                return compressed
            }
            if status == .error {
                if let error { throw error }
                throw DecodeError.converter
            }
            if toSkip > 0 {
                let skip = min(toSkip, Int(pcm.frameLength))
                toSkip -= skip
                guard skip < Int(pcm.frameLength) else { continue }
                pcm.dropFirst(skip)
            }
            if pcm.frameLength > 0 {
                try file.write(from: pcm)
            }
        }
    }

    /// The packets of the first logical stream, in order.
    static func packets(_ data: Data) throws -> [Data] {
        let bytes = [UInt8](data)
        var packets: [Data] = []
        var current = [UInt8]()
        var offset = 0
        var serial: UInt32?
        guard bytes.count >= 27, bytes[0..<4].elementsEqual("OggS".utf8) else { throw DecodeError.notOgg }
        while offset + 27 <= bytes.count {
            guard bytes[offset..<(offset + 4)].elementsEqual("OggS".utf8) else { throw DecodeError.notOgg }
            let pageSerial = UInt32(bytes[offset + 14]) | UInt32(bytes[offset + 15]) << 8
                | UInt32(bytes[offset + 16]) << 16 | UInt32(bytes[offset + 17]) << 24
            let segmentCount = Int(bytes[offset + 26])
            let tableStart = offset + 27
            guard tableStart + segmentCount <= bytes.count else { break }
            let lacing = bytes[tableStart..<(tableStart + segmentCount)]
            var dataOffset = tableStart + segmentCount
            let isOurs = serial == nil || serial == pageSerial
            if serial == nil { serial = pageSerial }
            for size in lacing {
                let end = min(dataOffset + Int(size), bytes.count)
                if isOurs {
                    current.append(contentsOf: bytes[dataOffset..<end])
                    // A segment under 255 bytes ends the packet.
                    if size < 255 {
                        packets.append(Data(current))
                        current.removeAll(keepingCapacity: true)
                    }
                }
                dataOffset = end
            }
            offset = dataOffset
        }
        return packets
    }

    /// Samples at 48 kHz in an Opus packet, from its TOC byte (RFC 6716 section 3.1).
    static func frames(_ packet: Data) -> Int {
        guard let toc = packet.first else { return 0 }
        let config = Int(toc >> 3)
        let frameSize: Int
        switch config {
        case 0...11:
            frameSize = [480, 960, 1920, 2880][config % 4]
        case 12...15:
            frameSize = [480, 960][config % 2]
        default:
            frameSize = [120, 240, 480, 960][config % 4]
        }
        let count: Int
        switch toc & 0x3 {
        case 0: count = 1
        case 1, 2: count = 2
        default: count = packet.count > 1 ? Int(packet[packet.startIndex + 1] & 0x3F) : 0
        }
        return frameSize * count
    }
}

private extension AVAudioPCMBuffer {
    /// Drops the first frames (the Opus pre-skip).
    func dropFirst(_ frames: Int) {
        let remaining = Int(frameLength) - frames
        guard remaining > 0, let channels = floatChannelData else { return }
        for channel in 0..<Int(format.channelCount) {
            let samples = channels[channel]
            memmove(samples, samples + frames, remaining * MemoryLayout<Float>.size)
        }
        frameLength = AVAudioFrameCount(remaining)
    }
}
