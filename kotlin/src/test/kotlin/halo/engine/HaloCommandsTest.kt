package halo.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HaloCommandsTest {

    @Test
    fun capturePhotoLegacyPayloadWithoutOps() {
        assertContentEquals(
            byteArrayOf(4, 0x01, 0x40, 0x00, 0x8C.toByte(), 0),
            HaloCommands.capturePhoto(4, 320, 140, false),
        )
    }

    @Test
    fun capturePhotoEncodesOpTail() {
        val payload = HaloCommands.capturePhoto(
            ops = listOf(
                HaloCommands.MpixOp.ResizeSubsample(320, 320),
                HaloCommands.MpixOp.Denoise3x3,
                HaloCommands.MpixOp.JpegQuality(70),
            ),
        )
        // 6-byte header + count + ops
        assertEquals(6 + 1 + 5 + 1 + 2, payload.size)
        assertEquals(3, payload[6].toInt())
        // resize: 0x02, w=0x0140, h=0x0140
        assertContentEquals(
            byteArrayOf(0x02, 0x01, 0x40, 0x01, 0x40),
            payload.copyOfRange(7, 12),
        )
        // denoise_3x3: 0x03
        assertEquals(0x03, payload[12].toInt())
        // jpeg quality: 0x07, 70
        assertContentEquals(byteArrayOf(0x07, 70), payload.copyOfRange(13, 15))
    }

    @Test
    fun capturePhotoCropAndKernelOps() {
        val payload = HaloCommands.capturePhoto(
            ops = listOf(
                HaloCommands.MpixOp.Crop(10, 20, 300, 200),
                HaloCommands.MpixOp.Convolve3x3(HaloCommands.MpixOp.Kernel.SHARPEN),
            ),
        )
        assertEquals(2, payload[6].toInt())
        // crop: 0x01, x=10, y=20, w=300(0x012C), h=200(0x00C8)
        assertContentEquals(
            byteArrayOf(0x01, 0, 10, 0, 20, 0x01, 0x2C, 0x00, 0xC8.toByte()),
            payload.copyOfRange(7, 16),
        )
        // conv3x3 sharpen: 0x05, 3
        assertContentEquals(byteArrayOf(0x05, 3), payload.copyOfRange(16, 18))
    }

    @Test
    fun spriteStoreEncodesKeyThenAsset() {
        val payload = HaloCommands.spriteStore("icon_nav", byteArrayOf(1, 2, 3))
        assertContentEquals(
            byteArrayOf(8) + "icon_nav".toByteArray() + byteArrayOf(1, 2, 3),
            payload,
        )
    }

    @Test
    fun spriteStoreRejectsUnsafeKeys() {
        assertFailsWith<IllegalArgumentException> { HaloCommands.spriteStore("../escape", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { HaloCommands.spriteStore("", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { HaloCommands.spriteStore("k".repeat(65), byteArrayOf(1)) }
    }

    @Test
    fun soundPlayEncodesFlagsAndName() {
        val payload = HaloCommands.soundPlay("blip", volume = 50, seed = 7)
        assertEquals(0x05, payload[0].toInt()) // volume + seed flags
        assertEquals(50, payload[1].toInt())
        assertContentEquals(byteArrayOf(0, 7), payload.copyOfRange(2, 4))
        assertEquals("blip", String(payload, 4, payload.size - 4, Charsets.UTF_8))
    }

    @Test
    fun setTimeEncodesEpochAndZone() {
        val payload = HaloCommands.setTime(0x01020304L, "+05:30")
        assertContentEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04) + "+05:30".toByteArray(Charsets.US_ASCII),
            payload,
        )
    }

    @Test
    fun tapConfigEncodesSelectedFields() {
        val payload = HaloCommands.tapConfig(mode = "robust", threshold = 300, waitForTimeout = false)
        // flags: mode(0x01) + threshold(0x04) + wft(0x10) = 0x15
        assertEquals(0x15, payload[0].toInt())
        assertEquals(2, payload[1].toInt()) // robust index
        assertContentEquals(byteArrayOf(0x01, 0x2C), payload.copyOfRange(2, 4)) // 300
        assertEquals(0, payload[4].toInt()) // wait_for_timeout=false
    }

    @Test
    fun batteryStatusEncodesDeviceStatusPayload() {
        assertContentEquals(
            byteArrayOf(75, 0x0E, 0x74, 1), // 75%, 3700 mV, charging
            HaloCommands.batteryStatus(75, 3700, true),
        )
    }

    @Test
    fun inputEventsAreSingleGestureBytes() {
        assertContentEquals(byteArrayOf(2), HaloCommands.buttonEvent(2))
        assertContentEquals(byteArrayOf(3), HaloCommands.tapEvent(3))
    }
}
