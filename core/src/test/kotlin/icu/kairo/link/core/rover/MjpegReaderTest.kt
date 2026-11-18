package icu.kairo.link.core.rover

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MjpegReaderTest {
    private val boundary = "kairorover0frame"

    private fun jpeg(fill: Int, size: Int) =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(size) { fill.toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun part(body: ByteArray, declared: Int = body.size) = ByteArrayOutputStream().apply {
        write("\r\n--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: $declared\r\n\r\n".toByteArray())
        write(body)
    }.toByteArray()

    @Test fun mjpeg_reads_two_frames() {
        val a = jpeg(1, 100); val b = jpeg(2, 50)
        val r = MjpegReader(ByteArrayInputStream(part(a) + part(b)))
        assertArrayEquals(a, r.next())
        assertArrayEquals(b, r.next())
        assertNull(r.next())
    }

    @Test fun mjpeg_skips_truncated_part() {
        val good = jpeg(3, 40)
        // Declares 500 bytes but the body is cut short and the next part begins.
        val truncated = part(jpeg(9, 20), declared = 500).copyOf(70)
        val r = MjpegReader(ByteArrayInputStream(truncated + part(good)))
        assertArrayEquals(good, r.next())
    }

    @Test fun mjpeg_rejects_oversize_frame() {
        val big = jpeg(4, 2000); val small = jpeg(5, 10)
        val r = MjpegReader(ByteArrayInputStream(part(big) + part(small)), maxFrameBytes = 1000)
        assertArrayEquals(small, r.next())
    }
}
