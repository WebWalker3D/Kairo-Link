package icu.kairo.link.core.rover

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pulls JPEG frames out of the rover's multipart MJPEG stream by their SOI/EOI
 * markers rather than trusting Content-Length, so a part cut short by a Wi-Fi
 * hiccup is skipped instead of swallowing the frames after it. OV sensors write
 * no embedded thumbnails, so FF D8 inside a frame means a new frame started.
 */
class MjpegReader(input: InputStream, private val maxFrameBytes: Int = 512 * 1024) {
    private val input = BufferedInputStream(input, 16 * 1024)

    fun next(): ByteArray? {
        var prev = -1
        var frame: ByteArrayOutputStream? = null
        var oversize = false
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (prev == 0xFF && b == 0xD8) { // SOI: (re)start a frame
                frame = ByteArrayOutputStream().apply { write(0xFF); write(0xD8) }
                oversize = false
                prev = b
                continue
            }
            if (frame != null && !oversize) {
                frame.write(b)
                if (frame.size() > maxFrameBytes) {
                    oversize = true
                    frame = null
                } else if (prev == 0xFF && b == 0xD9) { // EOI
                    return frame.toByteArray()
                }
            }
            prev = b
        }
    }
}
