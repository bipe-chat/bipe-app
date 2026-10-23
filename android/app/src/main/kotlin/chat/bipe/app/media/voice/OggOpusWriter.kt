// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

/**
 * Writes Opus packets into an Ogg Opus file (RFC 3533 pages, RFC 7845 headers): the wire format of
 * voice messages, which every Matrix client plays (spec voice-recording notes).
 */
class OggOpusWriter(file: File, channels: Int = 1, private val preSkip: Int = 312, inputSampleRate: Int = 48000) {
    private val output: OutputStream = file.outputStream().buffered()
    private val serial = Random.nextInt()
    private var sequence = 0
    private var granule = preSkip.toLong()
    private val pending = mutableListOf<ByteArray>()
    private var pendingSamples = 0

    init {
        val head = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray())
            write(1)
            write(channels)
            writeLe(preSkip.toLong(), 2)
            writeLe(inputSampleRate.toLong(), 4)
            writeLe(0, 2)
            write(0)
        }.toByteArray()
        writePage(listOf(head), 0, FLAG_FIRST)
        val vendor = "Bipe".toByteArray()
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            writeLe(vendor.size.toLong(), 4)
            write(vendor)
            writeLe(0, 4)
        }.toByteArray()
        writePage(listOf(tags), 0, 0)
    }

    /** One Opus packet; its length in 48 kHz samples comes from the TOC byte. */
    fun append(packet: ByteArray) {
        pending.add(packet)
        pendingSamples += samples(packet)
        if (pending.size >= PACKETS_PER_PAGE) flush(last = false)
    }

    /** Ends the stream and closes the file. */
    fun finish() {
        flush(last = true)
        output.close()
    }

    private fun flush(last: Boolean) {
        if (pending.isEmpty() && !last) return
        granule += pendingSamples
        writePage(pending.toList(), granule, if (last) FLAG_LAST else 0)
        pending.clear()
        pendingSamples = 0
    }

    private fun writePage(packets: List<ByteArray>, granule: Long, flags: Int) {
        val lacing = ByteArrayOutputStream()
        for (packet in packets) {
            var remaining = packet.size
            while (remaining >= 255) {
                lacing.write(255)
                remaining -= 255
            }
            lacing.write(remaining)
        }
        val page = ByteArrayOutputStream().apply {
            write("OggS".toByteArray())
            write(0)
            write(flags)
            writeLe(granule, 8)
            writeLe(serial.toLong(), 4)
            writeLe(sequence.toLong(), 4)
            writeLe(0, 4)
            write(lacing.size())
            write(lacing.toByteArray())
            packets.forEach { write(it) }
        }.toByteArray()
        val crc = crc(page)
        for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
        output.write(page)
        sequence++
    }

    companion object {
        private const val FLAG_FIRST = 0x02
        private const val FLAG_LAST = 0x04

        /** Packets per page: 50 packets of 20 ms make a page of one second. */
        private const val PACKETS_PER_PAGE = 50

        private val TABLE = IntArray(256) { index ->
            var value = index shl 24
            repeat(8) { value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
            value
        }

        /** The Ogg CRC-32: polynomial 0x04C11DB7, no reflection, initial value 0. */
        fun crc(data: ByteArray): Int {
            var crc = 0
            for (byte in data) {
                crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
            }
            return crc
        }

        /** Samples at 48 kHz in an Opus packet, from its TOC byte (RFC 6716 section 3.1). */
        fun samples(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val toc = packet[0].toInt() and 0xFF
            val config = toc shr 3
            val frameSize = when (config) {
                in 0..11 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                in 12..15 -> intArrayOf(480, 960)[config % 2]
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
            val count = when (toc and 0x3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
            }
            return frameSize * count
        }

        private fun ByteArrayOutputStream.writeLe(value: Long, bytes: Int) {
            for (i in 0 until bytes) write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }
}
