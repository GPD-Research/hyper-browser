package org.gpdresearch.hyperbrowser

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Quarter-turns written as orientation metadata rather than as pixels. Re-encoding a 60-megapixel
 * RAW or a gigapixel TIFF to turn it would take minutes and lose the original data; every format
 * handled here records the turn in a tag the decoders already honour, so the file's pixels are
 * left untouched and the write is a few bytes.
 */
object ImageRotation {

    sealed class Outcome {
        object Rotated : Outcome()
        class Failed(val message: String) : Outcome()
    }

    /** Orientation after a quarter turn clockwise, mirrored orientations included. */
    private val CLOCKWISE = mapOf(
        ExifInterface.ORIENTATION_NORMAL to ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_90 to ExifInterface.ORIENTATION_ROTATE_180,
        ExifInterface.ORIENTATION_ROTATE_180 to ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_ROTATE_270 to ExifInterface.ORIENTATION_NORMAL,
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_TRANSVERSE to ExifInterface.ORIENTATION_FLIP_VERTICAL,
        ExifInterface.ORIENTATION_FLIP_VERTICAL to ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSPOSE to ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
    )

    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3

    fun rotateClockwise(context: Context, uri: Uri): Outcome {
        if (DriveUris.isDrive(uri)) {
            return Outcome.Failed("Copy this image to local storage to rotate it")
        }
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }
            .getOrNull()
            ?: return Outcome.Failed("This image cannot be opened for writing")
        return descriptor.use { open ->
            when {
                isTiffBased(open) -> rotateTiff(open)
                else -> rotateExif(open)
            }
        }
    }

    private fun isTiffBased(descriptor: ParcelFileDescriptor): Boolean {
        val header = readAt(descriptor, 0, 4) ?: return false
        val little = header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte()
        val big = header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte()
        if (!little && !big) return false
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        return ByteBuffer.wrap(header).order(order).getShort(2).toInt() == 42
    }

    /**
     * TIFF, and the RAW formats built on it, keep orientation in the first directory. Rewriting
     * that one field in place leaves the rest of the file — including the sensor data — as it was.
     */
    private fun rotateTiff(descriptor: ParcelFileDescriptor): Outcome {
        val header = readAt(descriptor, 0, 8) ?: return Outcome.Failed("This image could not be read")
        val order = if (header[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val directory = ByteBuffer.wrap(header).order(order).getInt(4)
        if (directory <= 0) return Outcome.Failed("This image has no orientation to turn")
        val count = readAt(descriptor, directory.toLong(), 2)
            ?.let { ByteBuffer.wrap(it).order(order).getShort(0).toInt() and 0xFFFF }
            ?: return Outcome.Failed("This image could not be read")
        for (index in 0 until count) {
            val entry = directory.toLong() + 2 + index * 12
            val bytes = readAt(descriptor, entry, 12) ?: break
            val field = ByteBuffer.wrap(bytes).order(order)
            if ((field.getShort(0).toInt() and 0xFFFF) != TAG_ORIENTATION) continue
            // Anything other than the single short the specification calls for is left alone
            // rather than guessed at; the value would not be where this writes it.
            if ((field.getShort(2).toInt() and 0xFFFF) != TYPE_SHORT || field.getInt(4) != 1) break
            val current = field.getShort(8).toInt() and 0xFFFF
            val turned = CLOCKWISE[current] ?: CLOCKWISE.getValue(ExifInterface.ORIENTATION_NORMAL)
            val payload = ByteBuffer.allocate(2).order(order).putShort(turned.toShort()).array()
            val written = runCatching {
                Os.pwrite(descriptor.fileDescriptor, payload, 0, payload.size, entry + 8)
                descriptor.fileDescriptor.sync()
            }.isSuccess
            return if (written) Outcome.Rotated else Outcome.Failed("This image could not be written")
        }
        return Outcome.Failed("This image has no orientation tag to turn")
    }

    private fun rotateExif(descriptor: ParcelFileDescriptor): Outcome {
        val exif = runCatching { ExifInterface(descriptor.fileDescriptor) }.getOrNull()
            ?: return Outcome.Failed("This format cannot be rotated in place")
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val turned = CLOCKWISE[current] ?: CLOCKWISE.getValue(ExifInterface.ORIENTATION_NORMAL)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, turned.toString())
        return runCatching { exif.saveAttributes() }.fold(
            onSuccess = { Outcome.Rotated },
            onFailure = { Outcome.Failed("This format cannot be rotated in place") },
        )
    }

    /** Positional reads, which leave the descriptor open for the write that follows. */
    private fun readAt(descriptor: ParcelFileDescriptor, position: Long, length: Int): ByteArray? = runCatching {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val step = Os.pread(descriptor.fileDescriptor, buffer, read, length - read, position + read)
            if (step <= 0) return@runCatching null
            read += step
        }
        buffer
    }.getOrNull()

    /**
     * What redrawing the file after the turn will cost. The write itself is a few bytes; it is the
     * re-decode that can stall or run out of memory, and that scales with the pixels the decoder
     * has to hold, so the heap the device gives this process is what decides whether it survives.
     */
    class Cost(val megapixels: Double, val heapMegabytes: Int, val risky: Boolean) {
        val summary: String
            get() = "about %.0f megapixels, against %d MB of heap".format(megapixels, heapMegabytes)
    }

    fun cost(context: Context, bytes: Long, raw: Boolean): Cost {
        val heap = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.largeMemoryClass
            ?: 256
        // A RAW frame carries roughly one compressed byte per photosite; a TIFF's own compression
        // varies far more, so this is an order of magnitude rather than a measurement.
        val megapixels = bytes.toDouble() / (1024 * 1024) * if (raw) 1.0 else 0.5
        // Decoding needs four bytes a pixel, and the previous frame is usually still resident.
        val needed = megapixels * 4 * 2
        return Cost(megapixels, heap, needed > heap * 0.6)
    }
}
