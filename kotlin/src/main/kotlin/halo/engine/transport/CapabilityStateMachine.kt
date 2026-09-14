package halo.engine.transport

import halo.engine.HaloCommands
import halo.engine.HaloProtocol
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Device-side capability state machines for the virtual Halo endpoint.
 *
 * Each state machine mirrors the behavior of `he_runtime.lua`'s
 * `handle_message()` function and the associated streaming helpers
 * (`send_mic_chunks`, `send_photo`, `send_battery`). The virtual endpoint
 * uses these to produce realistic device-to-host notifications in response
 * to host commands, without requiring real hardware.
 *
 * All state machines are driven by [MessageReassembler.Message] instances
 * (the output of [MessageReassembler.drainCompleted]). They emit
 * [DeviceEvent]s that the transport layer sends back to the host.
 */

/** A device-to-host event: a message code + payload, or a text print. */
sealed interface DeviceEvent {
    data class Message(val code: Int, val payload: ByteArray) : DeviceEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return code == other.code && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * code + payload.contentHashCode()
    }
    data class Text(val value: String) : DeviceEvent
}

/**
 * Configuration for simulated capabilities.
 *
 * Values are configurable so tests can inject deterministic battery levels,
 * photo payloads, microphone audio, etc.
 */
data class CapabilityConfig(
    val batteryLevel: Int = 75,
    val batteryVoltage: Int = 3700,
    val batteryCharging: Boolean = false,
    /** Microphone: bytes of PCM audio to emit per chunk. */
    val micChunkBytes: ByteArray = ByteArray(320) { 0 },
    /** Microphone: number of chunks before emitting AUDIO_FINAL. */
    val micChunkCount: Int = 5,
    /** Photo: complete JPEG bytes to emit in chunks. */
    val photoData: ByteArray = ByteArray(0),
    /** Photo: chunk size in bytes. */
    val photoChunkSize: Int = 200,
    /** Firmware version reported in the boot STATUS message. */
    val firmwareVersion: String = "0.0.0-test",
    /** Device EUI reported in the boot STATUS message; empty omits it. */
    val eui: String = "0011223344556677",
    /** IMU payload returned for `IMU_READ` (pitch;roll;cx;cy;cz;ax;ay;az). */
    val imuPayload: String = "0.00;0.00;0.0;0.0;0.0;0.0;0.0;1000.0",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapabilityConfig) return false
        return batteryLevel == other.batteryLevel &&
            batteryVoltage == other.batteryVoltage &&
            batteryCharging == other.batteryCharging &&
            micChunkCount == other.micChunkCount &&
            micChunkBytes.contentEquals(other.micChunkBytes) &&
            photoData.contentEquals(other.photoData) &&
            photoChunkSize == other.photoChunkSize
    }
    override fun hashCode(): Int {
        var result = batteryLevel
        result = 31 * result + batteryVoltage
        result = 31 * result + batteryCharging.hashCode()
        result = 31 * result + micChunkBytes.contentHashCode()
        result = 31 * result + micChunkCount
        result = 31 * result + photoData.contentHashCode()
        result = 31 * result + photoChunkSize
        return result
    }
}

/**
 * Virtual Halo capability state machine.
 *
 * Feed reassembled messages via [handleMessage]. The machine accumulates
 * [DeviceEvent]s in an internal queue; drain them with [drainEvents].
 * Call [tick] to advance time-dependent streaming (microphone chunks,
 * photo chunks).
 */
class CapabilityStateMachine(
    private val config: CapabilityConfig = CapabilityConfig(),
) {
    private val eventQueue = ConcurrentLinkedQueue<DeviceEvent>()

    // Microphone state
    private var micStreaming = false
    private var micChunksSent = 0

    // Photo state
    private var photoPending = false
    private var photoOffset = 0

    // Speaker state
    private var speakerActive = false

    // Display state
    private var displayActive = false
    private var displayCleared = false
    private var displayError: String? = null
    private var hrpFramesRendered = 0
    private var plainTextLines = 0
    private var displayPowerSave = false
    private var cameraPowerSave = false
    private var stayAwake = false

    // Recorded control commands (sound, system, time, tap config)
    private val soundRequests = mutableListOf<String>()
    private val systemOps = mutableListOf<Int>()
    private var lastTimeSync: Pair<Long, String>? = null
    private var lastTapConfig: ByteArray? = null

    /** Boot sequence: emit STATUS with capability string. */
    fun boot() {
        val caps = "HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery,sound,system,time,imu" +
            ",mpix,lz4" +
            ";fw=" + config.firmwareVersion +
            if (config.eui.isNotEmpty()) ";eui=" + config.eui else ""
        emit(DeviceEvent.Message(HaloProtocol.STATUS, caps.toByteArray()))
        emit(DeviceEvent.Text("Halo Engine v3 ready"))
    }

    /**
     * Handle a reassembled message from the host.
     *
     * Returns true if the message was recognized and handled.
     */
    fun handleMessage(code: Int, payload: ByteArray): Boolean {
        when (code) {
            HaloProtocol.HRP -> {
                handleHrp(payload)
                return true
            }
            HaloProtocol.CLEAR_DISPLAY -> {
                clearDisplay()
                return true
            }
            HaloProtocol.PLAIN_TEXT -> {
                handlePlainText(payload)
                return true
            }
            HaloProtocol.MICROPHONE_START -> {
                startMicrophone(payload)
                return true
            }
            HaloProtocol.MICROPHONE_STOP -> {
                stopMicrophone()
                return true
            }
            HaloProtocol.SPEAKER_START -> {
                startSpeaker(payload)
                return true
            }
            HaloProtocol.SPEAKER_STOP -> {
                stopSpeaker()
                return true
            }
            HaloProtocol.CAPTURE_PHOTO -> {
                capturePhoto(payload)
                return true
            }
            HaloProtocol.DEVICE_STATUS -> { // BATTERY_CODE in he_runtime.lua is 0x72
                sendBattery()
                return true
            }
            HaloProtocol.SOUND_PLAY -> {
                handleSound(payload)
                return true
            }
            HaloProtocol.SYSTEM -> {
                handleSystem(payload)
                return true
            }
            HaloProtocol.SET_TIME -> {
                handleSetTime(payload)
                return true
            }
            HaloProtocol.IMU_READ -> {
                emit(DeviceEvent.Message(HaloProtocol.IMU, config.imuPayload.toByteArray()))
                return true
            }
            HaloProtocol.TAP_CONFIG -> {
                lastTapConfig = payload.copyOf()
                return true
            }
            else -> return false
        }
    }

    /**
     * Advance streaming state. Called periodically by the transport.
     *
     * Emits microphone chunks or photo chunks as needed.
     */
    fun tick() {
        if (micStreaming) {
            sendMicChunks()
        }
        if (photoPending) {
            sendPhotoChunks()
        }
    }

    // ------------------------------------------------------------------ microphone

    private fun startMicrophone(payload: ByteArray) {
        micStreaming = true
        micChunksSent = 0
    }

    private fun stopMicrophone() {
        // The device stops recording; remaining buffered audio is drained
        // by subsequent tick() calls. AUDIO_FINAL is emitted when the
        // microphone read returns nil (after the stop).
        micStreaming = false
        // In he_runtime.lua, stop just calls frame.microphone.stop().
        // The send_mic_chunks loop in the main while loop will emit AUDIO_FINAL
        // when frame.microphone.read() returns nil.
        // For the virtual endpoint, we emit AUDIO_FINAL immediately after stop
        // since there's no real buffer to drain.
        emit(DeviceEvent.Message(HaloProtocol.AUDIO_FINAL, ByteArray(0)))
    }

    private fun sendMicChunks() {
        if (!micStreaming) return
        repeat(10) {
            if (micChunksSent >= config.micChunkCount) {
                emit(DeviceEvent.Message(HaloProtocol.AUDIO_FINAL, ByteArray(0)))
                micStreaming = false
                return
            }
            emit(DeviceEvent.Message(HaloProtocol.AUDIO_CHUNK, config.micChunkBytes))
            micChunksSent++
        }
    }

    // ------------------------------------------------------------------ photo

    private fun capturePhoto(payload: ByteArray) {
        if (photoPending) return  // busy
        photoPending = true
        photoOffset = 0
    }

    private fun sendPhotoChunks() {
        if (!photoPending) return
        if (config.photoData.isEmpty()) {
            emit(DeviceEvent.Message(HaloProtocol.PHOTO_FINAL, ByteArray(0)))
            photoPending = false
            return
        }
        while (photoOffset < config.photoData.size) {
            val chunkLen = minOf(config.photoData.size - photoOffset, config.photoChunkSize)
            val chunk = config.photoData.copyOfRange(photoOffset, photoOffset + chunkLen)
            emit(DeviceEvent.Message(HaloProtocol.PHOTO_JPEG, chunk))
            photoOffset += chunkLen
        }
        emit(DeviceEvent.Message(HaloProtocol.PHOTO_FINAL, ByteArray(0)))
        photoPending = false
    }

    // ------------------------------------------------------------------ speaker

    private fun startSpeaker(payload: ByteArray) {
        speakerActive = true
    }

    private fun stopSpeaker() {
        speakerActive = false
    }

    // ------------------------------------------------------------------ display

    /**
     * Handle an HRP display frame.
     *
     * Mirrors `he_runtime.lua` line 310-315: the HRP payload is parsed
     * and executed. On parse/render failure, an ERROR event is emitted
     * with the error message (matching the Lua `pcall` pattern).
     */
    private fun handleHrp(payload: ByteArray) {
        // The actual HRP rendering is done by HrpRenderer on the host side.
        // The device-side state machine just tracks lifecycle and emits
        // errors if the payload is invalid (matching he_runtime.lua pcall).
        if (payload.size < 7) {
            displayError = "invalid HRP header"
            emit(DeviceEvent.Message(HaloProtocol.ERROR, displayError!!.toByteArray()))
            return
        }
        val magic = String(payload, 0, 4, Charsets.US_ASCII)
        if (magic != "HRP1" || payload[4].toInt() != 0) {
            displayError = "invalid HRP header"
            emit(DeviceEvent.Message(HaloProtocol.ERROR, displayError!!.toByteArray()))
            return
        }
        displayActive = true
        displayCleared = false
        displayError = null
        hrpFramesRendered++
    }

    /**
     * Clear the display (CLEAR_DISPLAY = 0x10).
     * Mirrors `he_runtime.lua` line 316-317: `frame.display.clear()`.
     */
    private fun clearDisplay() {
        displayCleared = true
        displayActive = false
        displayError = null
    }

    /**
     * Handle plain text display (PLAIN_TEXT = 0x11).
     * Mirrors `he_runtime.lua` line 318-319: `pcall(draw_plain_text, payload)`.
     * Draws each non-empty line of text on the display.
     */
    private fun handlePlainText(payload: ByteArray) {
        displayActive = true
        displayCleared = false
        displayError = null
        val text = String(payload, Charsets.UTF_8)
        plainTextLines = text.split('\n').count { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ sound / system / time

    /**
     * Mirrors `he_runtime.lua` `play_sound()`: the name is the remainder of the
     * payload after the flags byte and optional fields. Unknown presets and
     * truncated payloads emit an ERROR event.
     */
    private fun handleSound(payload: ByteArray) {
        if (payload.size < 2) {
            emit(DeviceEvent.Message(HaloProtocol.ERROR, "sound payload too short".toByteArray()))
            return
        }
        val flags = payload[0].toInt()
        var pos = 1
        if (flags and 0x01 != 0) pos += 1
        if (flags and 0x02 != 0) pos += 2
        if (flags and 0x04 != 0) pos += 2
        if (pos > payload.size) {
            emit(DeviceEvent.Message(HaloProtocol.ERROR, "truncated command".toByteArray()))
            return
        }
        val name = String(payload, pos, payload.size - pos, Charsets.UTF_8)
        if (name !in HaloCommands.SOUND_PRESETS) {
            emit(DeviceEvent.Message(HaloProtocol.ERROR, "unknown sound preset".toByteArray()))
            return
        }
        soundRequests.add(name)
    }

    /** Mirrors `he_runtime.lua` `handle_system()` subcommand dispatch. */
    private fun handleSystem(payload: ByteArray) {
        if (payload.isEmpty()) {
            emit(DeviceEvent.Message(HaloProtocol.ERROR, "system payload too short".toByteArray()))
            return
        }
        when (val sub = payload[0].toInt() and 0xFF) {
            HaloProtocol.SYS_DISPLAY_SLEEP -> displayPowerSave = true
            HaloProtocol.SYS_DISPLAY_WAKE -> displayPowerSave = false
            HaloProtocol.SYS_STAY_AWAKE -> stayAwake = payload.getOrElse(1) { 0 }.toInt() != 0
            HaloProtocol.SYS_CAMERA_POWER_SAVE -> cameraPowerSave = payload.getOrElse(1) { 0 }.toInt() != 0
            HaloProtocol.SYS_STANDBY,
            HaloProtocol.SYS_LIGHT_SLEEP,
            HaloProtocol.SYS_DEEP_SLEEP,
            HaloProtocol.SYS_SHIP_MODE,
            HaloProtocol.SYS_CHARGE -> systemOps.add(sub)
            else -> emit(DeviceEvent.Message(HaloProtocol.ERROR, "unknown system subcommand $sub".toByteArray()))
        }
    }

    /** Mirrors `he_runtime.lua` `handle_set_time()`: u32 epoch + optional zone. */
    private fun handleSetTime(payload: ByteArray) {
        if (payload.size < 4) {
            emit(DeviceEvent.Message(HaloProtocol.ERROR, "set_time payload too short".toByteArray()))
            return
        }
        val epoch = ((payload[0].toInt() and 0xFF).toLong() shl 24) or
            ((payload[1].toInt() and 0xFF).toLong() shl 16) or
            ((payload[2].toInt() and 0xFF).toLong() shl 8) or
            (payload[3].toInt() and 0xFF).toLong()
        val zone = if (payload.size > 4) String(payload, 4, payload.size - 4, Charsets.US_ASCII) else ""
        lastTimeSync = epoch to zone
    }

    // ------------------------------------------------------------------ battery

    private fun sendBattery() {
        emit(DeviceEvent.Message(
            HaloProtocol.DEVICE_STATUS,
            HaloCommands.batteryStatus(config.batteryLevel, config.batteryVoltage, config.batteryCharging),
        ))
    }

    // ------------------------------------------------------------------ input

    /** Inject a button event (single=1, double=2, long=3). */
    fun buttonEvent(gesture: Int) {
        emit(DeviceEvent.Message(HaloProtocol.BUTTON, HaloCommands.buttonEvent(gesture)))
    }

    /** Inject a tap event (single=1, double=2, triple=3). */
    fun tapEvent(kind: Int) {
        emit(DeviceEvent.Message(HaloProtocol.TAP, HaloCommands.tapEvent(kind)))
    }

    // ------------------------------------------------------------------ state queries

    fun isMicStreaming(): Boolean = micStreaming
    fun isPhotoPending(): Boolean = photoPending
    fun isSpeakerActive(): Boolean = speakerActive
    fun isDisplayActive(): Boolean = displayActive
    fun isDisplayCleared(): Boolean = displayCleared
    fun displayError(): String? = displayError
    fun hrpFramesRendered(): Int = hrpFramesRendered
    fun plainTextLines(): Int = plainTextLines
    fun isDisplayPowerSave(): Boolean = displayPowerSave
    fun isCameraPowerSave(): Boolean = cameraPowerSave
    fun isStayAwake(): Boolean = stayAwake
    fun soundRequests(): List<String> = soundRequests.toList()
    fun systemOps(): List<Int> = systemOps.toList()
    fun lastTimeSync(): Pair<Long, String>? = lastTimeSync
    fun lastTapConfig(): ByteArray? = lastTapConfig?.copyOf()

    /** Reset all state (e.g. on disconnect). */
    fun reset() {
        micStreaming = false
        micChunksSent = 0
        photoPending = false
        photoOffset = 0
        speakerActive = false
        displayActive = false
        displayCleared = false
        displayError = null
        hrpFramesRendered = 0
        plainTextLines = 0
        displayPowerSave = false
        cameraPowerSave = false
        stayAwake = false
        soundRequests.clear()
        systemOps.clear()
        lastTimeSync = null
        lastTapConfig = null
    }

    /** Drain and return all queued events in emission order. */
    fun drainEvents(): List<DeviceEvent> {
        val result = mutableListOf<DeviceEvent>()
        while (true) {
            val e = eventQueue.poll() ?: break
            result.add(e)
        }
        return result
    }

    private fun emit(event: DeviceEvent) {
        eventQueue.add(event)
    }
}
