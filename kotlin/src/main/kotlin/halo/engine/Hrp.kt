package halo.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hardware-bounded HRP v1 encoder. Payload is sent through official send_message framing. */
class HrpBuilder(private val maxBytes: Int = 32768) {
    private val commands = mutableListOf<ByteArray>()

    private fun u16(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun color(value: Any?): ByteArray {
        val n = HaloColor.parse(value)
        return byteArrayOf((n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }

    private fun add(opcode: Int, payload: ByteArray): HrpBuilder {
        require(opcode in 0..255)
        require(payload.size <= 0xFFFF)
        commands += byteArrayOf(opcode.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        checkSize()
        return this
    }

    fun clear(value: Any? = 0): HrpBuilder = add(0x01, color(value))
    fun brightness(value: Int): HrpBuilder {
        require(value in 0..100)
        return add(0x02, byteArrayOf(value.toByte()))
    }
    fun pixel(x: Int, y: Int, value: Any?): HrpBuilder = add(0x03, u16(x) + u16(y) + color(value))
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, value: Any?): HrpBuilder = add(0x04, u16(x0) + u16(y0) + u16(x1) + u16(y1) + color(value))
    fun rect(x: Int, y: Int, w: Int, h: Int, value: Any?, filled: Boolean = false): HrpBuilder = add(0x05, u16(x) + u16(y) + u16(w) + u16(h) + color(value) + byteArrayOf(if (filled) 1 else 0))
    fun circle(cx: Int, cy: Int, r: Int, value: Any?, filled: Boolean = false): HrpBuilder = add(0x06, u16(cx) + u16(cy) + u16(r) + color(value) + byteArrayOf(if (filled) 1 else 0))
    fun polygon(points: List<Pair<Int, Int>>, value: Any?): HrpBuilder {
        require(points.size in 3..64) { "Halo supports 3..64 polygon points" }
        val out = ByteArrayOutputStream()
        out.write(points.size)
        points.forEach { (x, y) -> out.write(u16(x)); out.write(u16(y)) }
        out.write(color(value))
        return add(0x07, out.toByteArray())
    }
    fun setFont(font: Int, size: Int, scale: Int = 1): HrpBuilder {
        require(font == 0 || font == 1)
        require(size in 8..255 && size % 8 == 0 && scale in 1..255)
        return add(0x08, byteArrayOf(font.toByte(), size.toByte(), scale.toByte()))
    }
    fun text(x: Int, y: Int, text: String, value: Any? = 0xFFFFFF): HrpBuilder {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(u16(x)); out.write(u16(y)); out.write(color(value)); out.write(u16(utf8.size)); out.write(utf8)
        return add(0x09, out.toByteArray())
    }
    fun spriteDefine(id: Int, asset: ByteArray): HrpBuilder = add(0x0A, u16(id) + asset)
    fun spriteDraw(id: Int, x: Int, y: Int, offset: Int = 0): HrpBuilder {
        require(offset in 0..255)
        return add(0x0B, u16(id) + u16(x) + u16(y) + byteArrayOf(offset.toByte()))
    }
    fun spriteRelease(id: Int): HrpBuilder = add(0x0C, u16(id))
    /**
     * Define sprite [id] from a device-cached asset (`spr_<key>` written by a
     * prior `SPRITE_STORE` message). Emits opcode 0x10 — keeps the HRP frame
     * small when the same sprite is presented repeatedly.
     */
    fun spriteDefineCached(id: Int, key: String): HrpBuilder {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size in 1..64) { "sprite cache key must be 1..64 bytes" }
        return add(0x10, u16(id) + byteArrayOf(keyBytes.size.toByte()) + keyBytes)
    }
    fun dirtyRegion(x: Int, y: Int, w: Int, h: Int): HrpBuilder = add(0x0D, u16(x) + u16(y) + u16(w) + u16(h))
    fun endFrame(): HrpBuilder = add(0x0E, byteArrayOf())
    fun features(value: Long): HrpBuilder {
        require(value in 0..0xFFFFFFFFL)
        return add(0x0F, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array())
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HRP1".toByteArray(Charsets.US_ASCII)); out.write(0); out.write(u16(commands.size))
        commands.forEach(out::write)
        return out.toByteArray()
    }

    private fun checkSize() {
        val size = 7 + commands.sumOf { it.size }
        require(size <= maxBytes) { "HRP frame is $size bytes; limit is $maxBytes" }
    }
}
