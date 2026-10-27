// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.calls.BipeCall
import chat.bipe.shared.calls.BipeCallMember
import chat.bipe.shared.calls.BipeCallOpenId
import chat.bipe.shared.calls.BipeCallRing
import org.matrix.rustcomponents.sdk.CallDeclineListener
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.KeyProvider
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm
import livekit.org.webrtc.FrameCryptorKeyProvider
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import io.livekit.android.room.Room as MediaRoom
import org.matrix.rustcomponents.sdk.Room as MatrixRoom

/** One member device on the call screen (type CallMember of spec call). */
data class CallTile(
    val id: String,
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val speaking: Boolean,
    val microphoneOn: Boolean,
    val cameraOn: Boolean,
    val track: VideoTrack?,
)

/** Who a new call rings (spec call start): the other person of a DM, or a notification to a group. */
data class CallRingTarget(val isDm: Boolean, val peerUserId: String?)

/** State of spec call. */
data class CallUi(
    val phase: CallSession.Phase = CallSession.Phase.CONNECTING,
    val tiles: List<CallTile> = emptyList(),
    val microphoneOn: Boolean = true,
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val speakerOn: Boolean = false,
    val microphoneDenied: Boolean = false,
    val cameraDenied: Boolean = false,
    val connectedAt: Long? = null,
    val localTrack: VideoTrack? = null,
)

/**
 * Spec call (bipe-chat/bipe#758, ADR 0186): one MatrixRTC call of one room. Membership with a delayed leave
 * (MSC4143, MSC4140), the SFU token (MSC4195), LiveKit media with a per-participant key for each device.
 */
class CallSession(
    context: Context,
    private val session: AccountSession,
    private val room: MatrixRoom,
    val roomName: String,
    private val focusUrl: String,
    val video: Boolean,
    /** Who to ring when the room has no call yet; null when answering a ring. */
    private val ringTarget: CallRingTarget? = null,
) {
    enum class Phase { CONNECTING, CALLING, CONNECTED, RECONNECTING, DECLINED, NO_ANSWER, ENDED, FAILED }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val roomId: String = room.id()
    private val userId = session.userId
    private val deviceId = session.client.deviceId()
    private val ownStateKey = BipeCall.stateKey(userId, deviceId)
    private val ownIdentity = BipeCall.livekitIdentity(userId, deviceId)
    private val _ui = MutableStateFlow(CallUi(speakerOn = video))
    val ui: StateFlow<CallUi> = _ui.asStateFlow()
    private val keys = CallKeyProvider()
    private var widget: CallWidget? = null
    var media: MediaRoom? = null
        private set
    private val members = mutableMapOf<String, BipeCallMember>()
    private val names = mutableMapOf<String, Pair<String, String?>>()
    private var ownKey = ByteArray(0)
    private var delayId: String? = null
    private var restartJob: Job? = null
    private var joined = false
    private var leaving = false
    private var stateSeen = false
    private var reportedActive = false
    private var declineHandle: org.matrix.rustcomponents.sdk.TaskHandle? = null
    private var noAnswerJob: Job? = null
    /** Called once the call is over, so CallCenter can drop it. */
    var onFinished: (() -> Unit)? = null

    /** Members after the first [MAX_TILES], for the list. */
    val extraMembers: List<CallTile> get() = _ui.value.tiles.drop(MAX_TILES)

    // MARK: Join

    /** true once CallActivity gave the permission answers. */
    var started = false
        private set

    /** After the permission answers of CallActivity. */
    fun start(microphoneGranted: Boolean, cameraGranted: Boolean) {
        if (started) return
        started = true
        if (!microphoneGranted) {
            _ui.update { it.copy(microphoneDenied = true, phase = Phase.FAILED) }
            return
        }
        val withCamera = video && cameraGranted
        if (video && !cameraGranted) _ui.update { it.copy(cameraDenied = true) }
        CallService.start(appContext, withCamera)
        scope.launch {
            runCatching { join(withCamera) }.onFailure { finish(failed = true) }
        }
    }

    private suspend fun join(withCamera: Boolean) {
        val widget = CallWidget(userId, deviceId, scope, listOf(BipeCallRing.EVENT_TYPE), listOf(BipeCallRing.DECLINE_EVENT_TYPE)) { receive(it) }
        this.widget = widget
        widget.start(room)
        widget.ready()
        // The driver sends the room's call state right after the capabilities: wait for it, so the SFU choice sees the others.
        repeat(30) { if (!stateSeen) delay(100) }

        // Delayed leave first, so a crash after the join still ends the membership (MSC4140).
        val delayed = widget.sendEvent(BipeCall.MEMBER_EVENT_TYPE, JsonObject(emptyMap()), ownStateKey, BipeCall.LEAVE_DELAY_MS)
        delayId = delayed.text("delay_id")
        startRestarting()
        val membership = widget.sendEvent(BipeCall.MEMBER_EVENT_TYPE,
            BipeCall.membershipContent(deviceId, roomId, focusUrl, System.currentTimeMillis(), video), ownStateKey)
        joined = true
        val membershipId = membership.text("event_id")
        if (members.keys.all { it == ownStateKey } && membershipId != null) ring(membershipId)

        ownKey = randomKey()
        keys.setRawKey(ownIdentity, BipeCall.OWN_KEY_INDEX, ownKey)
        sendOwnKey()

        val others = members.filterKeys { it != ownStateKey }.values.toList()
        val (url, jwt) = token(BipeCall.oldestFocus(others, focusUrl))

        val media = LiveKit.create(appContext, RoomOptions(adaptiveStream = true, dynacast = true, e2eeOptions = E2EEOptions(keys)))
        this.media = media
        scope.launch { media.events.collect { mediaEvent(it) } }
        media.connect(url, jwt)
        media.localParticipant.setMicrophoneEnabled(true)
        if (withCamera) {
            media.localParticipant.setCameraEnabled(true)
            _ui.update { it.copy(cameraOn = true) }
        }
        applySpeaker(_ui.value.speakerOn)
        _ui.update { it.copy(connectedAt = System.currentTimeMillis()) }
        refresh()
    }

    /** Spec call start: the ring event (MSC4075), the decline watch and the no-answer timer. */
    private suspend fun ring(membershipId: String) {
        val target = ringTarget ?: return
        val widget = widget ?: return
        val content = BipeCallRing.content(if (target.isDm) "ring" else "notification", System.currentTimeMillis(), membershipId,
            if (target.isDm) listOfNotNull(target.peerUserId) else emptyList(), video)
        val eventId = runCatching { widget.sendEvent(BipeCallRing.EVENT_TYPE, content) }.getOrNull()?.text("event_id") ?: return
        if (target.isDm) {
            declineHandle = runCatching {
                room.subscribeToCallDeclineEvents(eventId, object : CallDeclineListener {
                    override fun call(declinerUserId: String) {
                        scope.launch { declined(declinerUserId) }
                    }
                })
            }.getOrNull()
        }
        noAnswerJob = scope.launch {
            delay(BipeCallRing.LIFETIME_MS)
            if (otherMembers().isEmpty()) finish(failed = false, final = Phase.NO_ANSWER)
        }
    }

    private suspend fun declined(userId: String) {
        if (userId == this.userId || otherMembers().isNotEmpty()) return
        finish(failed = false, final = Phase.DECLINED)
    }

    private fun otherMembers(): List<BipeCallMember> = members.filterKeys { it != ownStateKey }.values.toList()

    private suspend fun token(sfu: String): Pair<String, String> {
        val openId = session.client.requestOpenidToken()
        val body = BipeCall.sfuRequestBody(roomId, deviceId,
            BipeCallOpenId(openId.accessToken, openId.tokenType, openId.matrixServerName, openId.expiresInSeconds.toLong()))
        return withContext(Dispatchers.IO) {
            val connection = URL(sfu.trimEnd('/') + "/sfu/get").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                if (connection.responseCode != 200) throw CallWidget.Failure("sfu ${connection.responseCode}")
                val json = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
                json?.let(BipeCall::sfuAnswer) ?: throw CallWidget.Failure("sfu answer")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun startRestarting() {
        restartJob?.cancel()
        restartJob = scope.launch {
            while (true) {
                delay(BipeCall.LEAVE_RESTART_MS)
                val widget = widget ?: return@launch
                val id = delayId ?: return@launch
                runCatching { widget.updateDelayed(id, "restart") }
            }
        }
    }

    // MARK: Controls

    fun toggleMicrophone() {
        val media = media ?: return
        val on = !_ui.value.microphoneOn
        _ui.update { it.copy(microphoneOn = on) }
        scope.launch { runCatching { media.localParticipant.setMicrophoneEnabled(on) } }
        CallService.update(appContext)
    }

    fun toggleCamera() {
        val media = media ?: return
        if (!_ui.value.cameraOn && _ui.value.cameraDenied) return
        val on = !_ui.value.cameraOn
        _ui.update { it.copy(cameraOn = on) }
        scope.launch {
            runCatching { media.localParticipant.setCameraEnabled(on) }
            CallService.update(appContext)
            refresh()
        }
    }

    fun flipCamera() {
        val track = media?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val front = !_ui.value.frontCamera
        track.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
        _ui.update { it.copy(frontCamera = front) }
    }

    fun toggleSpeaker() {
        val on = !_ui.value.speakerOn
        _ui.update { it.copy(speakerOn = on) }
        applySpeaker(on)
    }

    /** Speaker, or the best other route (Bluetooth, wired headset, earpiece) through AudioManager communication devices. */
    private fun applySpeaker(on: Boolean) {
        val handler = media?.audioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        val device = if (on) devices.firstOrNull { it is AudioDevice.Speakerphone }
        else devices.firstOrNull { it is AudioDevice.BluetoothHeadset } ?: devices.firstOrNull { it is AudioDevice.WiredHeadset }
            ?: devices.firstOrNull { it is AudioDevice.Earpiece }
        handler.selectDevice(device)
    }

    fun end() {
        scope.launch { finish(failed = false) }
    }

    private suspend fun finish(failed: Boolean, final: Phase? = null) {
        if (leaving) return
        leaving = true
        restartJob?.cancel()
        noAnswerJob?.cancel()
        declineHandle?.cancel()
        declineHandle = null
        media?.let {
            it.disconnect()
            it.release()
        }
        media = null
        widget?.let { widget ->
            if (joined) runCatching { widget.sendEvent(BipeCall.MEMBER_EVENT_TYPE, JsonObject(emptyMap()), ownStateKey) }
            delayId?.let { runCatching { widget.updateDelayed(it, "cancel") } }
            widget.close()
        }
        widget = null
        CallService.stop(appContext)
        TelecomCalls.end()
        if (!_ui.value.microphoneDenied) _ui.update { it.copy(phase = final ?: if (failed) Phase.FAILED else Phase.ENDED) }
        if (!failed) {
            delay(2_000)
            onFinished?.invoke()
            scope.cancel()
        }
    }

    /** Drops a call that never joined. */
    fun discard() {
        CallService.stop(appContext)
        scope.cancel()
    }

    // MARK: Matrix side

    private fun receive(incoming: CallWidget.Incoming) {
        when (incoming) {
            is CallWidget.Incoming.State -> {
                stateSeen = true
                updateMembers(incoming.events)
            }
            is CallWidget.Incoming.Event -> when (incoming.event.text("type")) {
                BipeCall.MEMBER_EVENT_TYPE -> if (incoming.event.text("state_key") != null) updateMembers(listOf(incoming.event))
                BipeCall.KEY_EVENT_TYPE -> takeKeys(incoming.event.text("sender"), incoming.event["content"] as? JsonObject)
            }
            is CallWidget.Incoming.ToDevice -> if (incoming.message.text("type") == BipeCall.KEY_EVENT_TYPE) {
                takeKeys(incoming.message.text("sender"), incoming.message["content"] as? JsonObject)
            }
        }
    }

    private fun updateMembers(events: List<JsonObject>) {
        val before = members.keys.toSet()
        val now = System.currentTimeMillis()
        for (event in events) {
            if (event.text("type") != BipeCall.MEMBER_EVENT_TYPE) continue
            val stateKey = event.text("state_key") ?: continue
            val sender = event.text("sender") ?: continue
            val member = BipeCall.member(sender, stateKey, event.number("origin_server_ts") ?: now,
                event["content"] as? JsonObject ?: JsonObject(emptyMap()), now)
            if (member != null) members[stateKey] = member else members.remove(stateKey)
        }
        val after = members.keys.toSet()
        if (joined && !leaving) {
            if ((after - before - ownStateKey).isNotEmpty()) scope.launch { sendOwnKey() }
            if ((before - after - ownStateKey).isNotEmpty()) scope.launch { rotateOwnKey() }
        }
        refresh()
    }

    private fun takeKeys(sender: String?, content: JsonObject?) {
        if (sender == null || content == null) return
        for (key in BipeCall.readKeys(sender, content)) {
            if (key.identity != ownIdentity) keys.setRawKey(key.identity, key.index, key.key)
        }
    }

    private suspend fun sendOwnKey() {
        val widget = widget ?: return
        runCatching {
            widget.sendEvent(BipeCall.KEY_EVENT_TYPE,
                BipeCall.keyEventContent(BipeCall.OWN_KEY_INDEX, ownKey, deviceId, System.currentTimeMillis()))
        }
    }

    /** A member left: a new key, so the leaver cannot follow the rest of the call. */
    private suspend fun rotateOwnKey() {
        val next = randomKey()
        ownKey = next
        sendOwnKey()
        delay(BipeCall.KEY_USE_DELAY_MS)
        if (ownKey.contentEquals(next)) keys.setRawKey(ownIdentity, BipeCall.OWN_KEY_INDEX, next)
    }

    private fun randomKey(): ByteArray = ByteArray(BipeCall.KEY_LENGTH).also { SecureRandom().nextBytes(it) }

    // MARK: Screen state

    private fun mediaEvent(event: RoomEvent) {
        if (leaving) return
        when (event) {
            is RoomEvent.Reconnecting -> _ui.update { it.copy(phase = Phase.RECONNECTING) }
            is RoomEvent.Reconnected -> refresh()
            is RoomEvent.Disconnected -> scope.launch { finish(failed = _ui.value.connectedAt == null) }
            else -> refresh()
        }
    }

    private fun refresh() {
        val others = members.filterKeys { it != ownStateKey }.values.sortedBy { it.createdTs }
        val remote = media?.remoteParticipants.orEmpty()
        val tiles = others.map { member ->
            val participant = remote[Participant.Identity(member.identity)]
            val known = names[member.userId]
            CallTile(
                id = member.identity,
                userId = member.userId,
                name = known?.first ?: member.userId,
                avatarUrl = known?.second,
                speaking = participant?.isSpeaking ?: false,
                microphoneOn = participant?.isMicrophoneEnabled ?: true,
                cameraOn = participant?.isCameraEnabled ?: false,
                track = participant?.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack,
            )
        }
        val local = if (_ui.value.cameraOn) media?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack else null
        if (others.isNotEmpty() && !reportedActive && _ui.value.connectedAt != null) {
            reportedActive = true
            TelecomCalls.active()
        }
        _ui.update { state ->
            val phase = when {
                leaving || state.connectedAt == null || state.phase == Phase.RECONNECTING && stillReconnecting() -> state.phase
                others.isEmpty() -> Phase.CALLING
                else -> Phase.CONNECTED
            }
            state.copy(tiles = tiles, localTrack = local, phase = phase)
        }
        loadNames(others.map { it.userId })
    }

    /** true while LiveKit still reconnects. */
    private fun stillReconnecting(): Boolean = media?.state == MediaRoom.State.RECONNECTING

    private fun loadNames(userIds: List<String>) {
        val missing = userIds.toSet().filter { it !in names }
        if (missing.isEmpty()) return
        missing.forEach { names[it] = it to null }
        scope.launch {
            for (id in missing) {
                val name = runCatching { room.memberDisplayName(id) }.getOrNull()
                val avatar = runCatching { room.memberAvatarUrl(id) }.getOrNull()
                names[id] = (name ?: id) to avatar
            }
            refresh()
        }
    }

    companion object {
        /** The most video tiles on the screen (ADR 0186). More members show as a list. */
        const val MAX_TILES = 8
    }
}

/**
 * Per-participant keys as raw bytes (spec call). LiveKit's BaseKeyProvider takes keys as strings, which loses bytes
 * that are not UTF-8, so this provider gives the bytes to WebRTC itself. HKDF, like Element Web.
 */
class CallKeyProvider : KeyProvider {
    override var enableSharedKey: Boolean = false

    override val rtcKeyProvider: FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
        false,
        "LKFrameEncryptionKey".toByteArray(),
        BipeCall.RATCHET_WINDOW_SIZE,
        "LK-ROCKS".toByteArray(),
        -1,
        BipeCall.KEY_RING_SIZE,
        false,
        FrameCryptorKeyDerivationAlgorithm.HKDF,
    )

    fun setRawKey(identity: String, index: Int, key: ByteArray) {
        rtcKeyProvider.setKey(identity, index, key)
    }

    override fun setSharedKey(key: String, keyIndex: Int?): Boolean = false
    override fun ratchetSharedKey(keyIndex: Int?): ByteArray = ByteArray(0)
    override fun exportSharedKey(keyIndex: Int?): ByteArray = ByteArray(0)
    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {}
    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray = rtcKeyProvider.ratchetKey(participantId, keyIndex ?: 0)
    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray = rtcKeyProvider.exportKey(participantId, keyIndex ?: 0)
    override fun setSifTrailer(trailer: ByteArray) = rtcKeyProvider.setSifTrailer(trailer)
    override fun getLatestKeyIndex(participantId: String): Int = BipeCall.OWN_KEY_INDEX
}
