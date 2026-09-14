package halo.engine

import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FrameInputStream
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * LZ4 frame compression matching `frame.compression.decompress(data, block_size)`.
 *
 * The firmware expects the standard LZ4 frame format — what `lz4 -9 -B4096`
 * produces on the host — with independent blocks so each decompressed block
 * can be delivered to the registered `process_function` on its own. The
 * frame is assembled here instead of using `LZ4FrameOutputStream`, which
 * emits *dependent* (linked) blocks that neither `LZ4FrameInputStream` nor
 * the firmware's streaming decode accept.
 *
 * The safe (pure-Java) factories are used so this works on Android without
 * native libraries or `sun.misc.Unsafe`.
 */
object HaloLz4 {

    private const val BLOCK_SIZE = 64 * 1024

    /**
     * Compress [data] into a standalone LZ4 frame: magic + descriptor
     * (independent 64 KiB blocks, content size present, no checksums),
     * compressed blocks, end mark.
     */
    fun compress(data: ByteArray): ByteArray {
        val compressor = LZ4Factory.safeInstance().fastCompressor()
        val out = ByteArrayOutputStream(data.size / 2 + 64)

        out.write(byteArrayOf(0x04, 0x22, 0x4D, 0x18)) // frame magic
        // FLG: version=01, B.Indep=1, no block checksum, content size set,
        //      no content checksum -> 0x68. BD: 64 KiB max block -> 0x40.
        val descriptor = byteArrayOf(0x68, 0x40)
        val contentSize = ByteArray(8) { (data.size.toLong() ushr (8 * it)).toByte() }
        out.write(descriptor)
        out.write(contentSize)
        // Header checksum: xxhash32(descriptor .. end of optional fields) >> 8.
        val hc = XXHashFactory.safeInstance().hash32()
            .hash(descriptor + contentSize, 0, descriptor.size + contentSize.size, 0)
        out.write((hc ushr 8) and 0xFF)

        var offset = 0
        while (offset < data.size) {
            val len = minOf(BLOCK_SIZE, data.size - offset)
            val bound = compressor.maxCompressedLength(len)
            val buf = ByteArray(bound)
            val clen = compressor.compress(data, offset, len, buf, 0, bound)
            if (clen >= len) {
                writeLe32(out, len or Int.MIN_VALUE) // uncompressed block marker
                out.write(data, offset, len)
            } else {
                writeLe32(out, clen)
                out.write(buf, 0, clen)
            }
            offset += len
        }
        writeLe32(out, 0) // EndMark
        return out.toByteArray()
    }

    /** Decompress a frame produced by [compress] (or the `lz4` CLI). */
    fun decompress(frame: ByteArray): ByteArray =
        LZ4FrameInputStream(ByteArrayInputStream(frame)).use { input ->
            // copyTo, not readBytes: LZ4FrameInputStream.available() NPEs
            // before the first read() because its buffer is still null.
            ByteArrayOutputStream().also(input::copyTo).toByteArray()
        }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
