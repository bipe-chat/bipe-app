// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.core.windowsDecodeVoice
import chat.bipe.core.windowsEncodeVoice
import com.sun.jna.Platform
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsVoiceCodecTest {
    @Test fun transfersAnOggOpusRecordingThroughTheWindowsDll() {
        assumeTrue("Windows core DLL required", Platform.isWindows())
        // Include a partial final frame to exercise Ogg pre-skip and granule trimming.
        val pcm = ByteArray(4_801 * 2)
        val ogg = checkNotNull(windowsEncodeVoice(pcm))
        assertEquals("OggS", String(ogg, 0, 4, Charsets.US_ASCII))
        val decoded = checkNotNull(windowsDecodeVoice(ogg))
        assertEquals(1, decoded.channels.toInt())
        assertEquals(pcm.size, decoded.pcm.size)
        assertNull(windowsDecodeVoice(ogg.copyOf(19)))
    }
}
