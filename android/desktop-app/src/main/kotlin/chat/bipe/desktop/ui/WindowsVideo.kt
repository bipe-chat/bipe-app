// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import chat.bipe.desktop.chat.AccountMedia
import chat.bipe.desktop.chat.MessageMedia
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private enum class VideoMode { LOADING, STOPPED, PLAYING, PAUSED, FAILED }

/** Media Foundation renders into this heavyweight AWT peer. All COM calls run on the peer's EDT. */
private class VideoCanvas(private val source: Path, private val report: (VideoMode) -> Unit) : Canvas() {
    private var player: Pointer? = null
    private var uninitialize = false
    private var retired = false
    private var emptyTicks = 0
    private var mode = VideoMode.LOADING
    private val timer = Timer(200) { poll() }

    init {
        background = java.awt.Color.BLACK
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { updateVideo() }
        })
    }

    override fun addNotify() {
        super.addNotify()
        if (retired || player != null) return
        try {
            val hr = Ole.library.CoInitializeEx(null, 2)
            check(hr >= 0 || hr == -2147417850) // RPC_E_CHANGED_MODE: AWT already initialized a different apartment.
            uninitialize = hr >= 0
            val result = PointerByReference()
            check(MfPlay.library.MFPCreateMediaPlayer(WString(source.toString()), 1, 0, null,
                Native.getComponentPointer(this), result) >= 0)
            player = checkNotNull(result.value)
            emptyTicks = 0
            timer.start()
        } catch (_: LinkageError) {
            release()
            publish(VideoMode.FAILED)
        } catch (_: Exception) {
            release()
            publish(VideoMode.FAILED)
        }
    }

    override fun removeNotify() {
        release()
        super.removeNotify()
    }

    override fun paint(graphics: Graphics) {
        if (mode == VideoMode.LOADING || mode == VideoMode.FAILED) super.paint(graphics)
        else updateVideo()
    }

    fun toggle() {
        val native = player ?: return
        val hr = call(native, if (mode == VideoMode.PLAYING) 4 else 3)
        if (hr < 0) { release(); publish(VideoMode.FAILED) }
    }

    suspend fun disposePlayer() = withContext(NonCancellable) {
        val completion = CompletableDeferred<Unit>()
        SwingUtilities.invokeLater {
            try { retired = true; release() } finally { completion.complete(Unit) }
        }
        completion.await()
    }

    private fun poll() {
        val native = player ?: return
        val state = IntByReference()
        val next = if (call(native, 13, state) < 0) VideoMode.FAILED else when (state.value) {
            0 -> if (++emptyTicks < 30) VideoMode.LOADING else VideoMode.FAILED
            1 -> VideoMode.STOPPED
            2 -> VideoMode.PLAYING
            3 -> VideoMode.PAUSED
            else -> VideoMode.FAILED
        }
        if (next == VideoMode.FAILED) release()
        publish(next)
    }

    private fun publish(next: VideoMode) {
        if (mode != next) { mode = next; report(next) }
    }

    private fun updateVideo() { player?.let { call(it, 32) } }

    private fun release() {
        timer.stop()
        val native = player
        player = null
        if (native != null) {
            try { call(native, 38) } finally { call(native, 2) }
        }
        if (uninitialize) { uninitialize = false; Ole.library.CoUninitialize() }
    }

    /** Slots follow the Windows SDK IMFPMediaPlayer declaration, after the three IUnknown methods. */
    private fun call(native: Pointer, slot: Int, vararg args: Any): Int {
        val table = native.getPointer(0)
        val function = Function.getFunction(table.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return function.invokeInt(arrayOf(native, *args))
    }
}

private interface MfPlay : StdCallLibrary {
    fun MFPCreateMediaPlayer(path: WString, autoPlay: Int, options: Int, callback: Pointer?, window: Pointer, result: PointerByReference): Int
    companion object { val library: MfPlay by lazy { Native.load("mfplay", MfPlay::class.java) } }
}

private interface Ole : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, mode: Int): Int
    fun CoUninitialize()
    companion object { val library: Ole by lazy { Native.load("ole32", Ole::class.java) } }
}

@Composable internal fun WindowsVideoDialog(media: MessageMedia, loader: AccountMedia, s: DesktopCatalog, close: () -> Unit) {
    DialogWindow(onCloseRequest = close, title = media.filename, state = rememberDialogState(width = 900.dp, height = 700.dp)) {
        Surface(Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WindowsVideoPlayer(media, loader, s, Modifier.weight(1f).fillMaxWidth())
                TextButton(onClick = close) { Text(s.text("bipe_app_close")) }
            }
        }
    }
}

@Composable internal fun WindowsVideoPlayer(media: MessageMedia, loader: AccountMedia, s: DesktopCatalog, modifier: Modifier = Modifier) {
    var attempt by remember(media, loader) { mutableStateOf(0) }
    var mode by remember(media, loader, attempt) { mutableStateOf(VideoMode.LOADING) }
    var active by remember(media, loader, attempt) { mutableStateOf<VideoCanvas?>(null) }
    LaunchedEffect(media, loader, attempt) {
        try {
            loader.withVideo(media) { file ->
                val canvas = VideoCanvas(file) { mode = it }
                active = canvas
                try { awaitCancellation() } finally { canvas.disposePlayer() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mode = VideoMode.FAILED }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val canvas = active
        val current = mode
        if (current != VideoMode.FAILED && canvas != null) {
            SwingPanel(background = Color.Black, modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { JPanel(BorderLayout()).apply { add(canvas, BorderLayout.CENTER) } })
        } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (current == VideoMode.FAILED) Text(s.text("bipe_app_media_failed"))
            else CircularProgressIndicator()
        }
        if (current == VideoMode.FAILED) TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
        else if (current == VideoMode.LOADING) Text(s.text("bipe_web_loading"), Modifier.padding(8.dp))
        else TextButton(onClick = { canvas?.toggle() }) {
            Text(s.text(if (current == VideoMode.PLAYING) "bipe_app_voice_pause" else "bipe_app_voice_play"))
        }
    }
}
