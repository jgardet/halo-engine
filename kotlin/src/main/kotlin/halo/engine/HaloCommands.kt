package halo.engine

/**
 * Payload builders for the `he_runtime.lua` v3 message codes.
 *
 * The wire format lives here so the device runtime and every host
 * (Kotlin endpoint, tests, CLI) encode identically. Payloads are what goes
 * after the message code inside a framed data transfer.
 */
object HaloCommands {

    /** Recognized `frame.sound` presets on stock Halo firmware. */
    val SOUND_PRESETS = setOf("pickup", "laser", "explosion", "powerup", "hit", "jump", "blip")

    /**
     * `DEVICE_STATUS` (0x72) battery response: `[level][voltage_mv u16][charging]`.
     * Device-to-host payload; built here so the state machine and simulated
     * transports share the encoding.
     */
    fun batteryStatus(level: Int, voltageMv: Int, charging: Boolean): ByteArray =
        byteArrayOf(
            level.coerceIn(0, 100).toByte(),
            (voltageMv ushr 8).toByte(), voltageMv.toByte(),
            if (charging) 1 else 0,
        )

    /** `BUTTON` (0x0B) device-to-host event: single gesture byte. */
    fun buttonEvent(gesture: Int): ByteArray = byteArrayOf(gesture.toByte())

    /** `CLEAR_DISPLAY` (0x10): no payload. */
    fun clearDisplay(): ByteArray = byteArrayOf()

    /** `PLAIN_TEXT` (0x11): UTF-8 text drawn line by line on the display. */
    fun plainText(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** `TAP` (0x09) device-to-host event: single kind byte. */
    fun tapEvent(kind: Int): ByteArray = byteArrayOf(kind.toByte())

    /**
     * `SOUND_PLAY` (0x50): flags byte (b0=volume, b1=duration, b2=seed),
     * optional fields in that order, then the preset name.
     */
    fun soundPlay(
        name: String,
        volume: Int? = null,
        durationMs: Int? = null,
        seed: Int? = null,
    ): ByteArray {
        require(name in SOUND_PRESETS) { "unknown sound preset: $name" }
        var flags = 0
        if (volume != null) flags = flags or 0x01
        if (durationMs != null) flags = flags or 0x02
        if (seed != null) flags = flags or 0x04
        val out = ArrayList<Byte>()
        out.add(flags.toByte())
        volume?.let { out.add(it.coerceIn(0, 100).toByte()) }
        durationMs?.let {
            out.add((it ushr 8).toByte()); out.add(it.toByte())
        }
        seed?.let {
            out.add((it ushr 8).toByte()); out.add(it.toByte())
        }
        name.toByteArray(Charsets.UTF_8).forEach { out.add(it) }
        return out.toByteArray()
    }

    /**
     * One op in the optional `frame.camera.mpix` pipeline tail of
     * `CAPTURE_PHOTO`. The runtime inserts these between the auto
     * white/black-level correction and `jpeg_encode`.
     */
    sealed class MpixOp {
        internal abstract fun encode(): ByteArray

        class Crop(val x: Int, val y: Int, val w: Int, val h: Int) : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(
                0x01,
                (x ushr 8).toByte(), x.toByte(),
                (y ushr 8).toByte(), y.toByte(),
                (w ushr 8).toByte(), w.toByte(),
                (h ushr 8).toByte(), h.toByte(),
            )
        }

        class ResizeSubsample(val w: Int, val h: Int) : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(
                0x02,
                (w ushr 8).toByte(), w.toByte(),
                (h ushr 8).toByte(), h.toByte(),
            )
        }

        object Denoise3x3 : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(0x03)
        }

        object Denoise5x5 : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(0x04)
        }

        class Convolve3x3(val kernel: Kernel) : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(0x05, kernel.id.toByte())
        }

        class Convolve5x5(val kernel: Kernel) : MpixOp() {
            override fun encode(): ByteArray = byteArrayOf(0x06, kernel.id.toByte())
        }

        /** JPEG quality 0–100 applied via `mpix.cid.JPEG_QUALITY`. */
        class JpegQuality(val quality: Int) : MpixOp() {
            override fun encode(): ByteArray =
                byteArrayOf(0x07, quality.coerceIn(0, 100).toByte())
        }

        enum class Kernel(val id: Int) {
            EDGE_DETECT(0),
            GAUSSIAN_BLUR(1),
            IDENTITY(2),
            SHARPEN(3),
        }
    }

    /**
     * `CAPTURE_PHOTO` (0x20): `[quality][half_res u16][pan u16][raw]` plus an
     * optional `[op_count]` + encoded [ops] tail. An empty [ops] list emits
     * the legacy 6-byte payload.
     */
    fun capturePhoto(
        qualityIndex: Int = 4,
        halfResolution: Int = 320,
        panShifted: Int = 140,
        raw: Boolean = false,
        ops: List<MpixOp> = emptyList(),
    ): ByteArray {
        require(qualityIndex in 0..4) { "qualityIndex out of range: $qualityIndex" }
        require(ops.size <= 255) { "too many mpix ops: ${ops.size}" }
        val base = byteArrayOf(
            qualityIndex.toByte(),
            (halfResolution ushr 8).toByte(), halfResolution.toByte(),
            (panShifted ushr 8).toByte(), panShifted.toByte(),
            if (raw) 1 else 0,
        )
        if (ops.isEmpty()) return base
        return base + byteArrayOf(ops.size.toByte()) +
            ops.fold(ByteArray(0)) { acc, op -> acc + op.encode() }
    }

    /** `SYSTEM` (0x51): subcommand byte + optional argument bytes. */
    fun system(subcommand: Int, arg: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(subcommand.toByte()) + arg

    /** `SYSTEM` subcommand taking a single 0/1 byte. */
    fun systemFlag(subcommand: Int, enabled: Boolean): ByteArray =
        system(subcommand, byteArrayOf(if (enabled) 1 else 0))

    /** `SYSTEM` subcommand taking a u16 seconds argument (0 = indefinite). */
    fun systemSeconds(subcommand: Int, seconds: Int): ByteArray {
        require(seconds in 0..0xFFFF) { "seconds out of range: $seconds" }
        return system(subcommand, byteArrayOf((seconds ushr 8).toByte(), seconds.toByte()))
    }

    /** `SET_TIME` (0x52): u32 unix seconds (big-endian) + optional `±hh:mm` zone. */
    fun setTime(epochSeconds: Long, zone: String? = null): ByteArray {
        require(epochSeconds in 0..0xFFFFFFFFL) { "epoch out of range: $epochSeconds" }
        val base = byteArrayOf(
            (epochSeconds ushr 24).toByte(),
            (epochSeconds ushr 16).toByte(),
            (epochSeconds ushr 8).toByte(),
            epochSeconds.toByte(),
        )
        return if (zone.isNullOrEmpty()) base else base + zone.toByteArray(Charsets.US_ASCII)
    }

    /**
     * `MICROPHONE_START` (0x30): `[gain+10][aec][voice]` plus optional
     * `[encoder][sample_rate u16][bit_depth][channels][lc3_bitrate u16]`.
     */
    fun microphoneStart(
        gain: Int = 0,
        aec: Boolean = true,
        voice: Boolean = false,
        encoder: String = "pcm",
        sampleRate: Int = 16000,
        bitDepth: Int = 16,
        channels: Int = 1,
        lc3Bitrate: Int = 16000,
    ): ByteArray {
        require(encoder == "pcm" || encoder == "lc3") { "encoder must be pcm or lc3" }
        require(sampleRate == 8000 || sampleRate == 16000) { "sampleRate must be 8000 or 16000" }
        require(bitDepth == 8 || bitDepth == 16) { "bitDepth must be 8 or 16" }
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        val out = ArrayList<Byte>()
        out.add((gain.coerceIn(-10, 10) + 10).toByte())
        out.add(if (aec) 1 else 0)
        out.add(if (voice) 1 else 0)
        out.add(if (encoder == "lc3") 1 else 0)
        out.add((sampleRate ushr 8).toByte()); out.add(sampleRate.toByte())
        out.add(bitDepth.toByte())
        out.add(channels.toByte())
        if (encoder == "lc3") {
            out.add((lc3Bitrate ushr 8).toByte()); out.add(lc3Bitrate.toByte())
        }
        return out.toByteArray()
    }

    /**
     * `SPEAKER_START` (0x40): `[encoder][sample_rate][bit_depth][channels]
     * [volume]` plus optional `[gain][budget][lc3_duration u16][lc3_bitrate u16]`.
     */
    fun speakerStart(
        encoder: String = "pcm",
        sampleRate: Int = 16000,
        bitDepth: Int = 16,
        channels: Int = 1,
        volume: Int = 80,
        gain: Int = 0,
        budget: Int = 0,
        lc3Duration: Int = 1000,
        lc3Bitrate: Int = 16000,
    ): ByteArray {
        require(encoder == "pcm" || encoder == "lc3") { "encoder must be pcm or lc3" }
        val out = ArrayList<Byte>()
        out.add(if (encoder == "lc3") 1 else 0)
        out.add((sampleRate ushr 8).toByte()); out.add(sampleRate.toByte())
        out.add(bitDepth.toByte())
        out.add(channels.toByte())
        out.add(volume.coerceIn(0, 100).toByte())
        out.add(gain.coerceIn(0, 12).toByte())
        out.add(budget.coerceIn(0, 100).toByte())
        if (encoder == "lc3") {
            out.add((lc3Duration ushr 8).toByte()); out.add(lc3Duration.toByte())
            out.add((lc3Bitrate ushr 8).toByte()); out.add(lc3Bitrate.toByte())
        }
        return out.toByteArray()
    }

    /**
     * `TAP_CONFIG` (0x54): flags byte selecting fields in order —
     * b0=mode, b1=axis, b2=threshold(u16), b3=gesture_duration,
     * b4=wait_for_timeout.
     */
    fun tapConfig(
        mode: String? = null,
        axis: String? = null,
        threshold: Int? = null,
        gestureDuration: Int? = null,
        waitForTimeout: Boolean? = null,
    ): ByteArray {
        val modes = listOf("sensitive", "normal", "robust")
        val axes = listOf("x", "y", "z")
        var flags = 0
        if (mode != null) flags = flags or 0x01
        if (axis != null) flags = flags or 0x02
        if (threshold != null) flags = flags or 0x04
        if (gestureDuration != null) flags = flags or 0x08
        if (waitForTimeout != null) flags = flags or 0x10
        val out = ArrayList<Byte>()
        out.add(flags.toByte())
        mode?.let { out.add(modes.indexOf(it).also { i -> require(i >= 0) { "bad tap mode: $it" } }.toByte()) }
        axis?.let { out.add(axes.indexOf(it).also { i -> require(i >= 0) { "bad tap axis: $it" } }.toByte()) }
        threshold?.let { out.add((it ushr 8).toByte()); out.add(it.toByte()) }
        gestureDuration?.let { out.add(it.toByte()) }
        waitForTimeout?.let { out.add(if (it) 1 else 0) }
        return out.toByteArray()
    }
}
