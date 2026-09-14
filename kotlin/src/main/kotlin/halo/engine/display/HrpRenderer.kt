package halo.engine.display

import halo.engine.HaloColor
import halo.engine.HaloLimits
import halo.engine.HaloLz4
import halo.engine.StockHaloLimits
import halo.engine.validateHrpMessage

/**
 * Direct HRP v1 interpreter — executes HRP commands against a [DisplayBuffer]
 * without requiring a Lua VM.
 *
 * HRP coordinates are 0-indexed (unlike the Lua `frame.display.*` API which is
 * 1-based). The interpreter adds 1 before calling [DisplayBuffer] methods,
 * matching the device-side `he_runtime.lua` behavior.
 *
 * All parse and render failures throw [HrpFailure] with a typed [HrpFailure.Category],
 * byte offset, command index, and opcode. Callers can catch [HrpFailure] and map
 * it to higher-level error categories.
 *
 * Authoritative sources:
 * - `lua/he_runtime.lua` lines 127-209 (HRP parsing)
 * - `docs/HRP.md` (opcode reference)
 * - the installed `halo-emulator` package's display implementation
 */
class HrpRenderer(
    private val buffer: DisplayBuffer = DisplayBuffer(),
    private val limits: HaloLimits = StockHaloLimits,
) {
    private val sprites = mutableMapOf<Int, SpriteAsset>()

    /** Render an HRP payload into the framebuffer. Returns the number of commands executed. */
    fun render(payload: ByteArray): Int {
        validateHrpMessage(payload, limits)
        if (payload.size < 7) {
            throw HrpFailure.Header(HrpFailure.Category.INVALID_HEADER, payload.size,
                "HRP payload too short: ${payload.size} bytes (need at least 7)")
        }
        val magic = String(payload, 0, 4, Charsets.US_ASCII)
        if (magic != "HRP1") {
            throw HrpFailure.Header(HrpFailure.Category.INVALID_HEADER, 0,
                "invalid HRP magic: '$magic'")
        }
        if (payload[4].toInt() != 0) {
            throw HrpFailure.Header(HrpFailure.Category.INVALID_HEADER, 4,
                "invalid HRP reserved byte: ${payload[4]}")
        }
        val commandCount = u16(payload, 5)
        var offset = 7
        var executed = 0

        repeat(commandCount) { cmdIndex ->
            if (offset + 3 > payload.size) {
                throw HrpFailure.Command(HrpFailure.Category.TRUNCATED, offset, cmdIndex, -1,
                    "truncated HRP command header at command $cmdIndex (offset=$offset)")
            }
            val opcode = payload[offset].toInt() and 0xFF
            val length = u16(payload, offset + 1)
            val cmdHeaderOffset = offset
            offset += 3
            if (offset + length > payload.size) {
                throw HrpFailure.Command(HrpFailure.Category.TRUNCATED, cmdHeaderOffset, cmdIndex, opcode,
                    "truncated HRP command payload at command $cmdIndex (opcode=0x${opcode.toString(16)}, length=$length, offset=$offset)")
            }
            val cmdPayload = payload.copyOfRange(offset, offset + length)
            offset += length
            executeCommand(opcode, cmdPayload, cmdIndex, cmdHeaderOffset)
            executed++
        }

        if (offset != payload.size) {
            throw HrpFailure.Header(HrpFailure.Category.TRAILING_BYTES, offset,
                "trailing bytes after $commandCount HRP commands: ${payload.size - offset} bytes")
        }
        return executed
    }

    fun framebuffer(): DisplayBuffer = buffer

    fun snapshot(): IntArray = buffer.snapshot()

    private fun executeCommand(opcode: Int, payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        when (opcode) {
            0x01 -> cmdClear(payload, cmdIndex, cmdOffset)
            0x02 -> cmdBrightness(payload, cmdIndex, cmdOffset)
            0x03 -> cmdPixel(payload, cmdIndex, cmdOffset)
            0x04 -> cmdLine(payload, cmdIndex, cmdOffset)
            0x05 -> cmdRect(payload, cmdIndex, cmdOffset)
            0x06 -> cmdCircle(payload, cmdIndex, cmdOffset)
            0x07 -> cmdPolygon(payload, cmdIndex, cmdOffset)
            0x08 -> cmdSetFont(payload, cmdIndex, cmdOffset)
            0x09 -> cmdText(payload, cmdIndex, cmdOffset)
            0x0A -> cmdSpriteDefine(payload, cmdIndex, cmdOffset)
            0x0B -> cmdSpriteDraw(payload, cmdIndex, cmdOffset)
            0x0C -> cmdSpriteRelease(payload, cmdIndex, cmdOffset)
            0x0D -> { /* dirty-region hint; no-op */ }
            0x0E -> { /* end frame / show; no-op on Halo */ }
            0x0F -> { /* feature negotiation; no-op */ }
            else -> throw HrpFailure.Command(HrpFailure.Category.UNSUPPORTED_OPCODE, cmdOffset, cmdIndex, opcode,
                "unsupported HRP opcode: 0x${opcode.toString(16)}")
        }
    }

    private fun cmdClear(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 3) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x01,
                "clear expects 3 bytes (RGB), got ${payload.size}")
        }
        buffer.clear(rgb(payload, 0))
    }

    private fun cmdBrightness(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 1) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x02,
                "brightness expects 1 byte, got ${payload.size}")
        }
        buffer.brightnessPercent((payload[0].toInt() and 0xFF))
    }

    private fun cmdPixel(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 7) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x03,
                "pixel expects 7 bytes, got ${payload.size}")
        }
        buffer.setPixel(u16(payload, 0) + 1, u16(payload, 2) + 1, rgb(payload, 4))
    }

    private fun cmdLine(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 11) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x04,
                "line expects 11 bytes, got ${payload.size}")
        }
        buffer.line(
            u16(payload, 0) + 1, u16(payload, 2) + 1,
            u16(payload, 4) + 1, u16(payload, 6) + 1,
            rgb(payload, 8),
        )
    }

    private fun cmdRect(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 12) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x05,
                "rect expects 12 bytes, got ${payload.size}")
        }
        buffer.rect(
            u16(payload, 0) + 1, u16(payload, 2) + 1,
            u16(payload, 4), u16(payload, 6),
            rgb(payload, 8),
            filled = (payload[11].toInt() and 0xFF) != 0,
        )
    }

    private fun cmdCircle(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 10) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x06,
                "circle expects 10 bytes, got ${payload.size}")
        }
        buffer.circle(
            u16(payload, 0) + 1, u16(payload, 2) + 1,
            u16(payload, 4),
            rgb(payload, 6),
            filled = (payload[9].toInt() and 0xFF) != 0,
        )
    }

    private fun cmdPolygon(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size < 1) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x07,
                "polygon expects at least 1 byte, got ${payload.size}")
        }
        val nPoints = payload[0].toInt() and 0xFF
        if (nPoints !in 3..64) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_CONTENT, cmdOffset, cmdIndex, 0x07,
                "polygon point count must be 3..64, got $nPoints")
        }
        val expectedSize = 1 + nPoints * 4 + 3
        if (payload.size != expectedSize) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x07,
                "polygon expects $expectedSize bytes for $nPoints points, got ${payload.size}")
        }
        val points = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until nPoints) {
            points += (u16(payload, 1 + i * 4) + 1 to u16(payload, 1 + i * 4 + 2) + 1)
        }
        buffer.polygon(points, rgb(payload, 1 + nPoints * 4))
    }

    private fun cmdSetFont(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 3) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x08,
                "setFont expects 3 bytes, got ${payload.size}")
        }
        buffer.setFont(
            payload[0].toInt() and 0xFF,
            payload[1].toInt() and 0xFF,
            payload[2].toInt() and 0xFF,
        )
    }

    private fun cmdText(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size < 9) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x09,
                "text expects at least 9 bytes (x+y+color+len), got ${payload.size}")
        }
        val x = u16(payload, 0)
        val y = u16(payload, 2)
        val color = rgb(payload, 4)
        val textLen = u16(payload, 7)
        if (payload.size != 9 + textLen) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_CONTENT, cmdOffset, cmdIndex, 0x09,
                "text length field ($textLen) does not match payload (${payload.size - 9})")
        }
        val text = String(payload, 9, textLen, Charsets.UTF_8)
        buffer.text(text, x + 1, y + 1, color)
    }

    private fun cmdSpriteDefine(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size < 7) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x0A,
                "spriteDefine expects at least 7 bytes, got ${payload.size}")
        }
        val id = u16(payload, 0)
        val width = u16(payload, 2)
        val height = u16(payload, 4)
        val compressed = payload[6].toInt() and 0xFF
        if (payload.size < 9) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x0A,
                "spriteDefine expects at least 9 bytes (header+bpp+numColors), got ${payload.size}")
        }
        val bpp = payload[7].toInt() and 0xFF
        if (bpp !in setOf(1, 2, 4)) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_CONTENT, cmdOffset, cmdIndex, 0x0A,
                "sprite bpp must be 1, 2, or 4, got $bpp")
        }
        val numColors = payload[8].toInt() and 0xFF
        val paletteSize = numColors * 3
        if (payload.size < 9 + paletteSize) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x0A,
                "spriteDefine palette truncated: need $paletteSize bytes for $numColors colors, got ${payload.size - 9}")
        }
        val palette = payload.copyOfRange(9, 9 + paletteSize)
        val rawPixels = payload.copyOfRange(9 + paletteSize, payload.size)
        val pixelData = if (compressed != 0) {
            try {
                HaloLz4.decompress(rawPixels)
            } catch (e: Exception) {
                throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_CONTENT, cmdOffset, cmdIndex, 0x0A,
                    "sprite LZ4 decompress failed: ${e.message}")
            }
        } else {
            rawPixels
        }
        sprites[id] = SpriteAsset(width, height, bpp, numColors, palette, pixelData)
    }

    private fun cmdSpriteDraw(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 7) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x0B,
                "spriteDraw expects 7 bytes, got ${payload.size}")
        }
        val id = u16(payload, 0)
        val x = u16(payload, 2)
        val y = u16(payload, 4)
        val paletteOffset = payload[6].toInt() and 0xFF
        val sprite = sprites[id] ?: throw HrpFailure.Command(
            HrpFailure.Category.MISSING_RESOURCE, cmdOffset, cmdIndex, 0x0B,
            "sprite $id not defined")
        buffer.bitmap(
            x + 1, y + 1,
            sprite.width,
            (1 shl sprite.bpp),
            paletteOffset,
            sprite.pixelData,
            customPalette = sprite.palette.takeIf { it.isNotEmpty() },
        )
    }

    private fun cmdSpriteRelease(payload: ByteArray, cmdIndex: Int, cmdOffset: Int) {
        if (payload.size != 2) {
            throw HrpFailure.Command(HrpFailure.Category.PAYLOAD_SIZE, cmdOffset, cmdIndex, 0x0C,
                "spriteRelease expects 2 bytes, got ${payload.size}")
        }
        sprites.remove(u16(payload, 0))
    }

    private data class SpriteAsset(
        val width: Int,
        val height: Int,
        val bpp: Int,
        val numColors: Int,
        val palette: ByteArray,
        val pixelData: ByteArray,
    )

    companion object {
        /**
         * Legacy untyped render exception. Kept for backward compatibility;
         * new code should catch [HrpFailure] instead.
         */
        class HrpRenderException(message: String) : RuntimeException(message)

        private fun u16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun rgb(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }
}
