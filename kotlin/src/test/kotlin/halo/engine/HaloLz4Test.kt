package halo.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HaloLz4Test {

    @Test
    fun compressProducesStandardLz4Frame() {
        val frame = HaloLz4.compress("hello halo hello halo hello halo".toByteArray())
        // LZ4 frame magic 0x184D2204 (little-endian) at offset 0.
        assertContentEquals(
            byteArrayOf(0x04, 0x22, 0x4D, 0x18),
            frame.copyOfRange(0, 4),
        )
    }

    @Test
    fun roundtripRestoresSource() {
        val source = ByteArray(20_000) { (it * 31 % 256).toByte() }
        assertContentEquals(source, HaloLz4.decompress(HaloLz4.compress(source)))
    }

    @Test
    fun compressedSpriteAssetSetsFlagAndShrinks() {
        // Highly compressible pixels: a solid-color 4bpp sprite.
        val sprite = SpritePacker.Sprite(
            width = 64,
            height = 64,
            bpp = 4,
            numColors = 2,
            paletteData = byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            pixelData = ByteArray(64 * 64) { 1 },
        )
        val plain = HaloHost.packSpriteAsset(sprite, compress = false)
        val packed = HaloHost.packSpriteAsset(sprite, compress = true)
        // compressed flag is asset byte 4: [w u16][h u16][flag][bpp][colors]
        assertEquals(0, plain[4].toInt())
        assertEquals(1, packed[4].toInt())
        assertTrue(packed.size < plain.size)
        // Header + palette are untouched except the flag byte itself;
        // the pixel tail is an LZ4 frame that decodes back to the packed
        // indices.
        assertContentEquals(plain.copyOfRange(0, 4), packed.copyOfRange(0, 4))
        assertContentEquals(plain.copyOfRange(5, 13), packed.copyOfRange(5, 13))
        val decoded = HaloLz4.decompress(packed.copyOfRange(13, packed.size))
        assertContentEquals(plain.copyOfRange(13, plain.size), decoded)
    }
}
