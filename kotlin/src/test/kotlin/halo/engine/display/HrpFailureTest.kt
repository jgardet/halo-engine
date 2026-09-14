package halo.engine.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Tests for typed HRP parse/render failures (E1-04).
 * Verifies that HrpRenderer throws HrpFailure with correct category,
 * offset, command index, and opcode metadata.
 */
class HrpFailureTest {

    /** Build a minimal valid HRP header with the given commands. */
    private fun hrp(vararg commands: ByteArray): ByteArray {
        val total = commands.sumOf { (it.size - 1) + 3 }
        val out = ByteArray(7 + total)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[4] = 0
        out[5] = ((commands.size shr 8) and 0xFF).toByte()
        out[6] = (commands.size and 0xFF).toByte()
        var off = 7
        for (cmd in commands) {
            val payloadLen = cmd.size - 1
            out[off] = cmd[0]
            out[off + 1] = ((payloadLen shr 8) and 0xFF).toByte()
            out[off + 2] = (payloadLen and 0xFF).toByte()
            cmd.copyInto(out, off + 3, 1)
            off += 3 + payloadLen
        }
        return out
    }

    /** Build a command with opcode and payload. */
    private fun cmd(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(1 + payload.size)
        out[0] = opcode.toByte()
        payload.copyInto(out, 1)
        return out
    }

    @Test
    fun invalidMagicThrowsHeaderFailure() {
        val payload = ByteArray(8)
        "HRP2".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload[4] = 0
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.INVALID_HEADER, ex.category)
        assertEquals(0, ex.offset)
        assertEquals(-1, ex.commandIndex)
        assertEquals(-1, ex.opcode)
        assertTrue(ex.message!!.contains("invalid HRP magic"))
    }

    @Test
    fun invalidReservedByteThrowsHeaderFailure() {
        val payload = ByteArray(8)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload[4] = 1
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.INVALID_HEADER, ex.category)
        assertEquals(4, ex.offset)
    }

    @Test
    fun payloadTooShortThrowsHeaderFailure() {
        val payload = ByteArray(3)
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.INVALID_HEADER, ex.category)
        assertEquals(3, ex.offset)
    }

    @Test
    fun truncatedCommandHeaderThrowsTruncatedFailure() {
        // Header says 1 command, but no command bytes follow
        val payload = ByteArray(7)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload[4] = 0
        payload[5] = 0; payload[6] = 1  // commandCount = 1
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.TRUNCATED, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(7, ex.offset)
    }

    @Test
    fun truncatedCommandPayloadThrowsTruncatedFailure() {
        // Command header says 10 bytes payload, but only 3 follow
        val payload = ByteArray(13)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload[4] = 0
        payload[5] = 0; payload[6] = 1  // commandCount = 1
        payload[7] = 0x03  // pixel opcode
        payload[8] = 0; payload[9] = 10  // length = 10
        // Only 3 bytes of payload (offset 10..12), need 10
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.TRUNCATED, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(0x03, ex.opcode)
    }

    @Test
    fun trailingBytesThrowsTrailingBytesFailure() {
        // 0 commands but 1 extra byte
        val payload = ByteArray(8)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(payload, 0)
        payload[4] = 0
        payload[5] = 0; payload[6] = 0  // commandCount = 0
        payload[7] = 0x42  // trailing byte
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.TRAILING_BYTES, ex.category)
        assertEquals(7, ex.offset)
    }

    @Test
    fun unsupportedOpcodeThrowsUnsupportedOpcodeFailure() {
        val payload = hrp(cmd(0xFF, ByteArray(0)))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.UNSUPPORTED_OPCODE, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(0xFF, ex.opcode)
    }

    @Test
    fun wrongPayloadSizeThrowsPayloadSizeFailure() {
        // clear expects 3 bytes, give 2
        val payload = hrp(cmd(0x01, ByteArray(2)))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_SIZE, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(0x01, ex.opcode)
        assertTrue(ex.message!!.contains("clear expects 3 bytes"))
    }

    @Test
    fun invalidPolygonPointCountThrowsPayloadContentFailure() {
        // polygon with 2 points (must be 3..64)
        val polyPayload = ByteArray(1 + 2 * 4 + 3)
        polyPayload[0] = 2  // nPoints = 2
        val payload = hrp(cmd(0x07, polyPayload))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_CONTENT, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(0x07, ex.opcode)
        assertTrue(ex.message!!.contains("polygon point count"))
    }

    @Test
    fun undefinedSpriteThrowsMissingResourceFailure() {
        // spriteDraw with undefined sprite ID
        val drawPayload = ByteArray(7)  // id=0, x=0, y=0, paletteOffset=0
        val payload = hrp(cmd(0x0B, drawPayload))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.MISSING_RESOURCE, ex.category)
        assertEquals(0, ex.commandIndex)
        assertEquals(0x0B, ex.opcode)
        assertTrue(ex.message!!.contains("sprite 0 not defined"))
    }

    @Test
    fun invalidCompressedSpriteThrowsPayloadContentFailure() {
        // spriteDefine with compressed=1 but garbage (non-LZ4) pixel data
        val defPayload = ByteArray(9)
        defPayload[6] = 1  // compressed
        defPayload[7] = 4  // bpp
        defPayload[8] = 0  // numColors
        val payload = hrp(cmd(0x0A, defPayload))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_CONTENT, ex.category)
        assertEquals(0x0A, ex.opcode)
        assertTrue(ex.message!!.contains("decompress"))
    }

    @Test
    fun invalidBppThrowsPayloadContentFailure() {
        // spriteDefine with bpp=3 (must be 1, 2, or 4)
        val defPayload = ByteArray(9)
        defPayload[6] = 0  // not compressed
        defPayload[7] = 3  // invalid bpp
        defPayload[8] = 0  // numColors
        val payload = hrp(cmd(0x0A, defPayload))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_CONTENT, ex.category)
        assertEquals(0x0A, ex.opcode)
        assertTrue(ex.message!!.contains("bpp"))
    }

    @Test
    fun textLengthMismatchThrowsPayloadContentFailure() {
        // text command: x(2) + y(2) + color(3) + len(2) = 9 bytes header
        // len says 10 but no text bytes follow
        val textPayload = ByteArray(9)
        textPayload[7] = 0; textPayload[8] = 10  // textLen = 10
        val payload = hrp(cmd(0x09, textPayload))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_CONTENT, ex.category)
        assertEquals(0x09, ex.opcode)
        assertTrue(ex.message!!.contains("text length field"))
    }

    @Test
    fun failureInSecondCommandHasCorrectIndex() {
        // First command: valid clear (3 bytes RGB)
        // Second command: invalid pixel (wrong size)
        val payload = hrp(
            cmd(0x01, byteArrayOf(0xFF.toByte(), 0, 0)),  // valid clear
            cmd(0x03, ByteArray(5)),  // pixel expects 7, give 5
        )
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        assertEquals(HrpFailure.Category.PAYLOAD_SIZE, ex.category)
        assertEquals(1, ex.commandIndex)  // second command
        assertEquals(0x03, ex.opcode)
    }

    @Test
    fun failureToStringIncludesMetadata() {
        val payload = hrp(cmd(0xFF, ByteArray(0)))
        val ex = assertFailsWith<HrpFailure> { HrpRenderer().render(payload) }
        val str = ex.toString()
        assertTrue(str.contains("UNSUPPORTED_OPCODE"))
        assertTrue(str.contains("command=0"))
        assertTrue(str.contains("opcode=0xff"))
    }

    @Test
    fun validRenderDoesNotThrow() {
        val payload = hrp(cmd(0x01, byteArrayOf(0, 0, 0)))  // clear black
        val count = HrpRenderer().render(payload)
        assertEquals(1, count)
    }
}
