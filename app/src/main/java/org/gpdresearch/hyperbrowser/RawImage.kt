package org.gpdresearch.hyperbrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.InflaterInputStream
import kotlin.math.pow

/**
 * Random access to an image's bytes. A memory-mapped source lets a 500 MB TIFF be decoded without
 * ever holding it on the heap, which is the only way a file that size can be opened at all.
 */
interface ByteSource {
    val size: Int

    fun u8(offset: Int): Int

    fun copyRange(offset: Int, length: Int): ByteArray?

    fun stream(offset: Int, length: Int): InputStream?
}

private class ArraySource(private val data: ByteArray) : ByteSource {
    override val size: Int get() = data.size

    override fun u8(offset: Int): Int = data[offset].toInt() and 0xFF

    override fun copyRange(offset: Int, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > data.size) return null
        return data.copyOfRange(offset, offset + length)
    }

    override fun stream(offset: Int, length: Int): InputStream? {
        if (offset < 0 || length < 0 || offset + length > data.size) return null
        return ByteArrayInputStream(data, offset, length)
    }
}

private class BufferSource(private val buffer: ByteBuffer) : ByteSource {
    override val size: Int get() = buffer.limit()

    override fun u8(offset: Int): Int = buffer.get(offset).toInt() and 0xFF

    override fun copyRange(offset: Int, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        val out = ByteArray(length)
        val view = buffer.duplicate()
        view.position(offset)
        view.get(out)
        return out
    }

    override fun stream(offset: Int, length: Int): InputStream? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        return BufferInputStream(buffer.duplicate(), offset, length)
    }
}

private class BufferInputStream(
    private val buffer: ByteBuffer,
    offset: Int,
    length: Int,
) : InputStream() {
    private var position = offset
    private val end = offset + length

    override fun read(): Int = if (position >= end) -1 else buffer.get(position++).toInt() and 0xFF

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= end) return -1
        val count = minOf(length, end - position)
        buffer.position(position)
        buffer.get(destination, offset, count)
        position += count
        return count
    }

    override fun skip(count: Long): Long {
        val moved = minOf(count, (end - position).toLong()).coerceAtLeast(0L)
        position += moved.toInt()
        return moved
    }

    override fun available(): Int = end - position
}

/**
 * Decoder for the formats [BitmapFactory] cannot read: camera RAW (ARW, CR2, NEF, DNG, RAF …) and
 * TIFF. Both are exposed twice: as a whole-frame overview for browsing, and as [TiffImage] /
 * [SensorImage] handles that render an arbitrary crop at an arbitrary scale, which is what lets a
 * gigapixel TIFF or a full sensor frame be inspected at 1:1 inside a fixed memory budget.
 *
 * Everything here parses untrusted bytes, so every offset and length is bounds checked and every
 * allocation is capped.
 */
object RawImage {

    /** Ceiling for pulling a whole file onto the heap; larger sources have to be mapped instead. */
    private const val MAX_FILE_BYTES = 192 * 1024 * 1024

    /** EXIF metadata sits near the start of a file, so a prefix is enough to read orientation. */
    private const val EXIF_HEAD_BYTES = 4 * 1024 * 1024

    /**
     * Matches the viewer's limit: RecordingCanvas rejects bitmaps over 100 MB and GPUs cap texture
     * edges, so a decoded frame has to come back small enough to draw.
     */
    private const val MAX_OUTPUT_PIXELS = 16_000_000L
    private const val MAX_OUTPUT_EDGE = 8192

    /** Beyond this the accumulator for box filtering costs more than the sharper result is worth. */
    private const val MAX_FILTERED_PIXELS = 4_000_000L

    /**
     * Taps per axis inside one output pixel's source block. The taps are adjacent and centred in
     * the block rather than spread across it: a compressed strip has to be decoded in full to
     * reach any row inside it, so clustering the sampled rows is what decides how much of a
     * 500 MB file is touched, and 4x4 is already visually indistinguishable from a full box.
     */
    private const val MAX_TAPS = 4

    fun readAll(stream: InputStream): ByteArray? = runCatching {
        stream.buffered().use { input ->
            val buffer = java.io.ByteArrayOutputStream(minOf(input.available(), 1 shl 20).coerceAtLeast(8192))
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (buffer.size() + read > MAX_FILE_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    }.getOrNull()

    fun arraySource(bytes: ByteArray): ByteSource = ArraySource(bytes)

    /** Maps the whole file; the mapping outlives the descriptor, so the caller may close it. */
    fun mappedSource(descriptor: FileDescriptor): ByteSource? = runCatching {
        FileInputStream(descriptor).use { input ->
            val channel: FileChannel = input.channel
            val length = channel.size()
            if (length <= 0L || length > Int.MAX_VALUE) return@use null
            BufferSource(channel.map(FileChannel.MapMode.READ_ONLY, 0L, length))
        }
    }.getOrNull()

    fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int, lowQuality: Boolean): Bitmap? =
        decode(arraySource(bytes), targetWidth, targetHeight, lowQuality)

    /**
     * The browsing view of a file: the embedded preview when it is large enough to fill the
     * target, and a downscaled render of the image itself otherwise. A TIFF's embedded preview
     * is often a few hundred pixels wide, so taking it regardless of the target is what left
     * large TIFFs looking soft. Sensor data is the inspector's job, not this one's.
     */
    fun decode(source: ByteSource, targetWidth: Int, targetHeight: Int, lowQuality: Boolean): Bitmap? {
        val preview = embeddedJpeg(source)
        if (preview == null || preview.width < targetWidth || preview.height < targetHeight) {
            openTiff(source)?.render(targetWidth, targetHeight, null)?.let { return it }
        }
        preview ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(preview.width, preview.height, targetWidth, targetHeight)
            if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, options)
        }.getOrNull()
    }

    fun orientationDegrees(bytes: ByteArray): Int = orientationDegrees(ByteArrayInputStream(bytes))

    fun orientationDegrees(stream: InputStream): Int {
        val exif = runCatching { ExifInterface(stream) }.getOrNull() ?: return 0
        return exifOrientationToDegrees(
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
    }

    /**
     * Orientation for a [ByteSource], which may be a memory-mapped file far too large to copy
     * onto the heap. TIFF-based files (TIFF, DNG, NEF, CR2, ARW …) keep the tag in IFD0, so it
     * is read directly; other containers go through [ExifInterface] on a bounded prefix, since
     * EXIF data always sits near the start of the file.
     */
    fun orientationDegrees(source: ByteSource): Int {
        tiffHeader(source)?.let { file ->
            val directories = mutableListOf<Directory>()
            readDirectories(file, file.u32(4).toInt(), directories, depth = 0)
            val orientation = directories.firstOrNull()
                ?.first(TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toLong())
                ?.toInt()
                ?: ExifInterface.ORIENTATION_NORMAL
            return exifOrientationToDegrees(orientation)
        }
        val head = source.copyRange(0, minOf(source.size, EXIF_HEAD_BYTES)) ?: return 0
        return orientationDegrees(head)
    }

    private fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * RAW containers embed one or more JPEG previews. Scanning for start-of-image markers and
     * keeping the largest one that really decodes covers TIFF-based RAW (ARW, CR2, NEF, DNG) as
     * well as the containers that are not TIFF at all (RAF, CR3).
     */
    fun embeddedPreview(source: ByteSource, targetWidth: Int, targetHeight: Int, lowQuality: Boolean): Bitmap? {
        val jpeg = embeddedJpeg(source) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(jpeg.width, jpeg.height, targetWidth, targetHeight)
            if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size, options) }.getOrNull()
    }

    /** The bytes of an embedded JPEG, kept whole so a region decoder can work over them. */
    class EmbeddedJpeg(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * The largest JPEG embedded in the file — the best the camera itself wrote, which is what the
     * inspector shows as the compressed rendition.
     */
    fun embeddedJpeg(source: ByteSource): EmbeddedJpeg? {
        var bestOffset = -1
        var bestArea = 0L
        var bestWidth = 0
        var bestHeight = 0
        var index = 0
        var candidates = 0
        val limit = source.size - 3
        while (index < limit && candidates < 256) {
            val isStart = source.u8(index) == 0xFF &&
                source.u8(index + 1) == 0xD8 &&
                source.u8(index + 2) == 0xFF &&
                source.u8(index + 3) >= 0xC0
            if (!isStart) {
                index += 1
                continue
            }
            candidates += 1
            // Only the header is needed to size a candidate up.
            val header = source.copyRange(index, minOf(8192, source.size - index))
            if (header != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(header, 0, header.size, bounds)
                val area = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                if (bounds.outWidth > 0 && bounds.outHeight > 0 && area > bestArea) {
                    bestOffset = index
                    bestArea = area
                    bestWidth = bounds.outWidth
                    bestHeight = bounds.outHeight
                }
            }
            index += 2
        }
        if (bestOffset < 0) return null
        val payload = source.copyRange(bestOffset, jpegLength(source, bestOffset)) ?: return null
        return EmbeddedJpeg(payload, bestWidth, bestHeight)
    }

    /** Length through the end-of-image marker, so only the preview itself gets copied. */
    private fun jpegLength(source: ByteSource, offset: Int): Int {
        var index = offset + 2
        val limit = source.size - 1
        while (index < limit) {
            if (source.u8(index) == 0xFF && source.u8(index + 1) == 0xD9) return index + 2 - offset
            index += 1
        }
        return source.size - offset
    }

    private fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        val wanted = if (targetWidth > 0) targetWidth else width
        val tall = if (targetHeight > 0) targetHeight else height
        var sample = 1
        while (width / (sample * 2) >= wanted && height / (sample * 2) >= tall) sample *= 2
        while (
            (width / sample).toLong() * (height / sample).toLong() > MAX_OUTPUT_PIXELS ||
            width / sample > MAX_OUTPUT_EDGE ||
            height / sample > MAX_OUTPUT_EDGE
        ) {
            sample *= 2
        }
        return sample
    }

    // ---- TIFF containers ----------------------------------------------------------------------

    private const val TAG_NEW_SUBFILE_TYPE = 0x00FE
    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_LENGTH = 0x0101
    private const val TAG_BITS_PER_SAMPLE = 0x0102
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_PHOTOMETRIC = 0x0106
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_SAMPLES_PER_PIXEL = 0x0115
    private const val TAG_ROWS_PER_STRIP = 0x0116
    private const val TAG_STRIP_BYTE_COUNTS = 0x0117
    private const val TAG_PLANAR_CONFIGURATION = 0x011C
    private const val TAG_PREDICTOR = 0x013D
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_COLOR_MAP = 0x0140
    private const val TAG_TILE_WIDTH = 0x0142
    private const val TAG_TILE_LENGTH = 0x0143
    private const val TAG_TILE_OFFSETS = 0x0144
    private const val TAG_TILE_BYTE_COUNTS = 0x0145
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_CFA_REPEAT_DIM = 0x828D
    private const val TAG_CFA_PATTERN = 0x828E
    private const val TAG_BLACK_LEVEL = 0xC61A
    private const val TAG_WHITE_LEVEL = 0xC61D
    private const val TAG_AS_SHOT_NEUTRAL = 0xC628
    private const val TAG_MAKE = 0x010F
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_DEFAULT_CROP_ORIGIN = 0xC61F
    private const val TAG_DEFAULT_CROP_SIZE = 0xC620
    private const val TAG_CR2_SLICE = 0xC640
    private const val TAG_SONY_TONE_CURVE = 0x7010
    private const val TAG_SONY_SR2_OFFSET = 0x7200
    private const val TAG_SONY_SR2_LENGTH = 0x7201
    private const val TAG_SONY_SR2_KEY = 0x7221
    private const val TAG_DNG_PRIVATE = 0xC634
    private const val TAG_SONY_BLACK_LEVEL = 0x7310
    private const val TAG_SONY_WHITE_LEVEL = 0x787F
    private const val TAG_SONY_WB_LEVELS = 0x7313
    private const val TAG_NIKON_WB_LEVELS = 0x000C
    private const val TAG_NIKON_BLACK_LEVEL = 0x003D
    private const val TAG_NIKON_LINEARIZATION = 0x0096
    private const val TAG_CANON_SENSOR_INFO = 0x00E0
    private const val TAG_CANON_COLOR_DATA = 0x4001

    /** "Nikon\u0000" plus a version and a pad, and then a TIFF header of its own. */
    private const val NIKON_NOTE_HEADER = 10

    /** Where as-shot levels sit in the Canon colour data blocks whose layout is known. */
    private val CANON_WHITE_BALANCE_OFFSETS = intArrayOf(63, 71, 55, 583, 727, 479, 384, 58, 62)

    /** Enough rows of the masked border to average out sensor noise. */
    private const val BLACK_SAMPLE_ROWS = 16

    /** Sony's private directory is a few tens of kilobytes; anything larger is not one. */
    private const val MAX_SONY_PRIVATE_BYTES = 1 shl 20

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_DEFLATE_ADOBE = 32946
    private const val COMPRESSION_PACKBITS = 32773
    private const val COMPRESSION_OLD_JPEG = 6
    private const val COMPRESSION_LOSSLESS_JPEG = 7
    private const val COMPRESSION_SONY_ARW = 32767
    private const val COMPRESSION_NIKON = 34713

    private const val PHOTOMETRIC_CFA = 32803

    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    internal class Field(val type: Int, val values: LongArray)

    internal class Directory(val fields: Map<Int, Field>) {
        fun values(tag: Int): LongArray? {
            val field = fields[tag] ?: return null
            if (field.type != TYPE_RATIONAL && field.type != TYPE_SRATIONAL) return field.values
            return LongArray(field.values.size / 2) { index ->
                val denominator = field.values[index * 2 + 1]
                if (denominator == 0L) 0L else field.values[index * 2] / denominator
            }
        }

        fun first(tag: Int, fallback: Long): Long = values(tag)?.firstOrNull() ?: fallback

        /** An ASCII field, e.g. the camera maker, without its terminator. */
        fun text(tag: Int): String? {
            val field = fields[tag] ?: return null
            val chars = field.values.takeWhile { it in 32..126 }
            if (chars.isEmpty()) return null
            return chars.map { it.toInt().toChar() }.joinToString("").trim()
        }

        fun doubles(tag: Int): DoubleArray? {
            val field = fields[tag] ?: return null
            if (field.type != TYPE_RATIONAL && field.type != TYPE_SRATIONAL) {
                return DoubleArray(field.values.size) { field.values[it].toDouble() }
            }
            return DoubleArray(field.values.size / 2) { index ->
                val denominator = field.values[index * 2 + 1]
                if (denominator == 0L) 0.0 else field.values[index * 2].toDouble() / denominator
            }
        }
    }

    /** A view of a byte source starting part way in, for formats nested inside a file. */
    internal class SubSource(private val base: ByteSource, private val start: Int) : ByteSource {
        override val size: Int get() = (base.size - start).coerceAtLeast(0)
        override fun u8(offset: Int): Int = base.u8(start + offset)
        override fun copyRange(offset: Int, length: Int): ByteArray? = base.copyRange(start + offset, length)
        override fun stream(offset: Int, length: Int): InputStream? = base.stream(start + offset, length)
    }

    /**
     * A block of bytes read out of a file, addressed by the offsets it had in that file. Sony's
     * private directory has to be decrypted before it can be parsed, but its offsets still point
     * at the original file positions.
     */
    internal class OffsetSource(private val data: ByteArray, private val start: Int) : ByteSource {
        override val size: Int get() = start + data.size
        override fun u8(offset: Int): Int {
            val at = offset - start
            return if (at < 0 || at >= data.size) 0 else data[at].toInt() and 0xFF
        }

        override fun copyRange(offset: Int, length: Int): ByteArray? {
            val at = offset - start
            if (at < 0 || length < 0 || at + length > data.size) return null
            return data.copyOfRange(at, at + length)
        }

        override fun stream(offset: Int, length: Int): InputStream? =
            copyRange(offset, length)?.let { ByteArrayInputStream(it) }
    }

    internal class TiffFile(val source: ByteSource, val littleEndian: Boolean) {
        fun u8(offset: Int): Int = source.u8(offset)

        fun u16(offset: Int): Int = if (littleEndian) {
            u8(offset) or (u8(offset + 1) shl 8)
        } else {
            (u8(offset) shl 8) or u8(offset + 1)
        }

        fun u32(offset: Int): Long = if (littleEndian) {
            (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)
        } else {
            (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
        }
    }

    /**
     * Where the pixels of one directory live. Strips and tiles differ only in how the image is
     * cut up, so both are described the same way and read by the same loop; tiles are what make a
     * crop of a huge TIFF cost the crop rather than everything above it.
     */
    internal class Blocks(
        val width: Int,
        val height: Int,
        val across: Int,
        val down: Int,
        val offsets: LongArray,
        val counts: LongArray,
        /** Tiles pad their last row/column, strips do not. */
        val padded: Boolean,
    )

    /**
     * An opened TIFF. Sub-resolution directories (the pyramid many large TIFFs carry) are kept
     * alongside the full-resolution one, so an overview is read from a small level instead of
     * decoding the whole frame.
     */
    class TiffImage internal constructor(
        internal val file: TiffFile,
        internal val levels: List<Directory>,
        val width: Int,
        val height: Int,
    ) {
        /** [region] is in full-resolution pixels; null renders the whole frame. */
        fun render(targetWidth: Int, targetHeight: Int, region: Rect?): Bitmap? {
            val crop = Rect(0, 0, width, height)
            if (region != null && !crop.setIntersect(region, Rect(0, 0, width, height))) return null
            val level = chooseLevel(crop, targetWidth)
            val scale = level.first(TAG_IMAGE_WIDTH, width.toLong()).toDouble() / width
            val scaled = Rect(
                (crop.left * scale).toInt(),
                (crop.top * scale).toInt(),
                (crop.right * scale).toInt().coerceAtLeast((crop.left * scale).toInt() + 1),
                (crop.bottom * scale).toInt().coerceAtLeast((crop.top * scale).toInt() + 1),
            )
            return renderDirectory(file, level, targetWidth, targetHeight, scaled)
        }

        /** The smallest level that still has a source pixel for every pixel that will be drawn. */
        private fun chooseLevel(crop: Rect, targetWidth: Int): Directory {
            val full = levels.first()
            if (targetWidth <= 0 || crop.width() <= 0) return full
            val wanted = targetWidth.toLong() * width / crop.width()
            return levels.lastOrNull { it.first(TAG_IMAGE_WIDTH, 0) >= wanted } ?: full
        }
    }

    fun openTiff(source: ByteSource): TiffImage? {
        val file = tiffHeader(source) ?: return null
        val directories = mutableListOf<Directory>()
        readDirectories(file, file.u32(4).toInt(), directories, depth = 0)

        // RAW files also parse as TIFF, but their full-resolution IFD holds undemosaiced sensor
        // data; only directories this decoder understands are considered.
        val supported = directories
            .filter { supportedDirectory(it) }
            .sortedByDescending { it.first(TAG_IMAGE_WIDTH, 0) * it.first(TAG_IMAGE_LENGTH, 0) }
        val full = supported.firstOrNull() ?: return null
        return TiffImage(
            file = file,
            levels = supported,
            width = full.first(TAG_IMAGE_WIDTH, 0).toInt(),
            height = full.first(TAG_IMAGE_LENGTH, 0).toInt(),
        )
    }

    private fun tiffHeader(source: ByteSource): TiffFile? {
        if (source.size < 16) return null
        val littleEndian = when {
            source.u8(0) == 'I'.code && source.u8(1) == 'I'.code -> true
            source.u8(0) == 'M'.code && source.u8(1) == 'M'.code -> false
            else -> return null
        }
        val file = TiffFile(source, littleEndian)
        // 42 is baseline TIFF; RAW dialects use their own magic in the same layout. BigTIFF (43)
        // is a different container with 64-bit offsets and is deliberately not claimed here.
        val magic = file.u16(2)
        if (magic != 42 && magic != 0x55 && magic != 0x4F52 && magic != 0x5352) return null
        return file
    }

    private fun readDirectories(file: TiffFile, offset: Int, into: MutableList<Directory>, depth: Int) {
        var next = offset
        var guard = 0
        while (next > 0 && next + 2 <= file.source.size && guard < 32 && into.size < 64) {
            guard += 1
            val count = file.u16(next)
            val end = next + 2 + count * 12
            if (count <= 0 || end + 4 > file.source.size) return
            val fields = HashMap<Int, Field>(count)
            for (i in 0 until count) {
                val entry = next + 2 + i * 12
                val tag = file.u16(entry)
                val field = readField(file, entry) ?: continue
                fields[tag] = field
            }
            val directory = Directory(fields)
            into += directory
            if (depth < 3) {
                directory.values(TAG_SUB_IFDS)?.forEach { sub ->
                    readDirectories(file, sub.toInt(), into, depth + 1)
                }
            }
            next = file.u32(end).toInt()
        }
    }

    private fun readField(file: TiffFile, entry: Int): Field? {
        val type = file.u16(entry + 2)
        val count = file.u32(entry + 4)
        val unit = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> return null
        }
        // A gigapixel image can carry hundreds of thousands of strips, so the cap is generous.
        if (count <= 0 || count > 4_000_000) return null
        val total = count * unit
        val start = if (total <= 4) entry + 8 else file.u32(entry + 8).toInt()
        if (start < 0 || start + total > file.source.size) return null
        val components = if (unit == 8) 2 else 1
        val values = LongArray(count.toInt() * components)
        for (i in 0 until count.toInt()) {
            val at = start + i * unit
            when (unit) {
                1 -> values[i] = file.u8(at).toLong()
                2 -> values[i] = file.u16(at).toLong()
                4 -> values[i] = file.u32(at)
                else -> {
                    values[i * 2] = file.u32(at)
                    values[i * 2 + 1] = file.u32(at + 4)
                }
            }
        }
        return Field(type, values)
    }

    private fun supportedDirectory(directory: Directory): Boolean {
        val width = directory.first(TAG_IMAGE_WIDTH, 0)
        val height = directory.first(TAG_IMAGE_LENGTH, 0)
        if (width <= 0 || height <= 0) return false
        if (blocksFor(directory, width.toInt(), height.toInt()) == null) return false
        if (directory.first(TAG_PLANAR_CONFIGURATION, 1) != 1L) return false
        val bits = directory.values(TAG_BITS_PER_SAMPLE) ?: longArrayOf(1)
        if (bits.any { it != 8L && it != 16L }) return false
        val samples = directory.first(TAG_SAMPLES_PER_PIXEL, bits.size.toLong()).toInt()
        if (samples !in 1..4) return false
        val photometric = directory.first(TAG_PHOTOMETRIC, -1)
        if (photometric !in 0L..3L) return false
        if (photometric == 3L && directory.values(TAG_COLOR_MAP) == null) return false
        // Horizontal differencing is only unwound for 8-bit samples.
        if (directory.first(TAG_PREDICTOR, 1) == 2L && bits.any { it != 8L }) return false
        return supportedCompression(directory.first(TAG_COMPRESSION, 1).toInt())
    }

    private fun supportedCompression(compression: Int): Boolean = when (compression) {
        COMPRESSION_NONE, COMPRESSION_LZW, COMPRESSION_PACKBITS,
        COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_ADOBE -> true
        else -> false
    }

    private fun blocksFor(directory: Directory, width: Int, height: Int): Blocks? {
        val tileWidth = directory.first(TAG_TILE_WIDTH, 0).toInt()
        val tileHeight = directory.first(TAG_TILE_LENGTH, 0).toInt()
        val tileOffsets = directory.values(TAG_TILE_OFFSETS)
        val tileCounts = directory.values(TAG_TILE_BYTE_COUNTS)
        if (tileOffsets != null && tileCounts != null && tileWidth > 0 && tileHeight > 0) {
            val across = (width + tileWidth - 1) / tileWidth
            val down = (height + tileHeight - 1) / tileHeight
            if (tileOffsets.size < across * down || tileCounts.size < tileOffsets.size) return null
            return Blocks(tileWidth, tileHeight, across, down, tileOffsets, tileCounts, padded = true)
        }
        val offsets = directory.values(TAG_STRIP_OFFSETS) ?: return null
        val counts = directory.values(TAG_STRIP_BYTE_COUNTS) ?: return null
        if (offsets.size != counts.size) return null
        val rowsPerStrip = directory.first(TAG_ROWS_PER_STRIP, height.toLong())
            .coerceIn(1L, height.toLong()).toInt()
        val down = (height + rowsPerStrip - 1) / rowsPerStrip
        if (offsets.size < down) return null
        return Blocks(width, rowsPerStrip, 1, down, offsets, counts, padded = false)
    }

    /**
     * Renders [region] of one directory. Output pixels are box filtered over up to [MAX_TAPS] taps
     * per axis, which is what stops a downscaled TIFF from looking like a nearest-neighbour mess.
     */
    private fun renderDirectory(
        file: TiffFile,
        directory: Directory,
        targetWidth: Int,
        targetHeight: Int,
        region: Rect?,
    ): Bitmap? {
        val width = directory.first(TAG_IMAGE_WIDTH, 0).toInt()
        val height = directory.first(TAG_IMAGE_LENGTH, 0).toInt()
        val bitsPerSample = (directory.values(TAG_BITS_PER_SAMPLE) ?: longArrayOf(8)).first().toInt()
        val samples = directory.first(TAG_SAMPLES_PER_PIXEL, 1).toInt()
        val photometric = directory.first(TAG_PHOTOMETRIC, 1).toInt()
        val predictor = directory.first(TAG_PREDICTOR, 1).toInt()
        val compression = directory.first(TAG_COMPRESSION, 1).toInt()
        val blocks = blocksFor(directory, width, height) ?: return null
        val palette = if (photometric == 3) directory.values(TAG_COLOR_MAP) else null

        val crop = Rect(0, 0, width, height)
        if (region != null && !crop.setIntersect(region, Rect(0, 0, width, height))) return null
        if (crop.width() <= 0 || crop.height() <= 0) return null

        val step = stepFor(crop.width(), crop.height(), targetWidth, targetHeight) ?: return null
        val outWidth = (crop.width() + step - 1) / step
        val outHeight = (crop.height() + step - 1) / step
        if (outWidth <= 0 || outHeight <= 0) return null

        val taps = if (step > 1) minOf(step, MAX_TAPS) else 1
        val filtered = taps > 1 && outWidth.toLong() * outHeight <= MAX_FILTERED_PIXELS
        val effectiveTaps = if (filtered) taps else 1
        val tapOffset = (step - effectiveTaps) / 2

        val pixels = IntArray(outWidth * outHeight)
        val sums = if (filtered) IntArray(outWidth * outHeight * 3) else null
        val hits = if (filtered) IntArray(outWidth * outHeight) else null

        val rowBytes = ((blocks.width.toLong() * samples * bitsPerSample + 7) / 8)
        if (rowBytes <= 0 || rowBytes > Int.MAX_VALUE / 2) return null
        val row = ByteArray(rowBytes.toInt())
        val scratch = ByteArray(minOf(rowBytes, 128L * 1024L).toInt().coerceAtLeast(1))

        for (blockY in 0 until blocks.down) {
            val blockTop = blockY * blocks.height
            if (blockTop >= crop.bottom) break
            if (blockTop + blocks.height <= crop.top) continue
            for (blockX in 0 until blocks.across) {
                val blockLeft = blockX * blocks.width
                if (blockLeft >= crop.right) break
                if (blockLeft + blocks.width <= crop.left) continue
                val index = blockY * blocks.across + blockX
                if (index >= blocks.offsets.size) break
                val rowsInBlock = if (blocks.padded) blocks.height else minOf(blocks.height, height - blockTop)
                // A strip TIFF this size runs to thousands of strips, and decompressing one
                // whose rows are all skipped costs as much as one that is drawn.
                if (!blockHasWantedRow(blockTop, rowsInBlock, crop.top, crop.bottom, step, tapOffset, effectiveTaps)) {
                    continue
                }
                val stream = blockStream(
                    file.source,
                    blocks.offsets[index].toInt(),
                    blocks.counts[index].toInt(),
                    compression,
                ) ?: continue
                // Strips run the full width of the image, so a crop on the left of a gigapixel
                // frame is not worth decompressing all the way to the right edge.
                val columns = (minOf(crop.right, blockLeft + blocks.width) - blockLeft)
                    .coerceIn(1, blocks.width)
                val prefix = (((columns.toLong() * samples * bitsPerSample + 7) / 8).toInt())
                    .coerceIn(1, row.size)
                stream.use { input ->
                    for (offsetY in 0 until rowsInBlock) {
                        val y = blockTop + offsetY
                        if (y >= crop.bottom) return@use
                        val fromTop = y - crop.top
                        val inCell = fromTop % step
                        val wanted = y >= crop.top && inCell >= tapOffset && inCell < tapOffset + effectiveTaps
                        if (!wanted) {
                            skipFully(input, rowBytes, scratch)
                            continue
                        }
                        if (!readFully(input, row, prefix)) return@use
                        if (predictor == 2) applyHorizontalPredictor(row, columns, samples)
                        val outY = fromTop / step
                        if (outY >= outHeight) return@use
                        val firstOutX = ((maxOf(crop.left, blockLeft) - crop.left) / step).coerceAtLeast(0)
                        val lastOutX = ((minOf(crop.right, blockLeft + blocks.width) - 1 - crop.left) / step)
                            .coerceAtMost(outWidth - 1)
                        for (outX in firstOutX..lastOutX) {
                            val baseX = crop.left + outX * step + tapOffset
                            var tap = 0
                            while (tap < effectiveTaps) {
                                val x = baseX + tap
                                tap += 1
                                if (x < blockLeft || x >= blockLeft + blocks.width) continue
                                if (x >= crop.right || x >= width) continue
                                val colour = pixelAt(
                                    row,
                                    (x - blockLeft) * samples,
                                    samples,
                                    bitsPerSample,
                                    photometric,
                                    file.littleEndian,
                                    palette,
                                )
                                val at = outY * outWidth + outX
                                if (sums == null || hits == null) {
                                    pixels[at] = colour
                                } else {
                                    sums[at * 3] += (colour shr 16) and 0xFF
                                    sums[at * 3 + 1] += (colour shr 8) and 0xFF
                                    sums[at * 3 + 2] += colour and 0xFF
                                    hits[at] += 1
                                }
                            }
                        }
                        val remaining = rowsInBlock - offsetY - 1
                        if (
                            remaining <= 0 ||
                            !blockHasWantedRow(y + 1, remaining, crop.top, crop.bottom, step, tapOffset, effectiveTaps)
                        ) {
                            return@use
                        }
                        skipFully(input, rowBytes - prefix, scratch)
                    }
                }
            }
        }

        if (sums != null && hits != null) {
            for (at in pixels.indices) {
                val count = hits[at]
                pixels[at] = if (count == 0) {
                    0xFF000000.toInt()
                } else {
                    0xFF000000.toInt() or
                        ((sums[at * 3] / count) shl 16) or
                        ((sums[at * 3 + 1] / count) shl 8) or
                        (sums[at * 3 + 2] / count)
                }
            }
        }

        return runCatching {
            Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /**
     * Whether any row of a block survives the subsampling, and so is worth decompressing.
     * [cellRows] is how many source rows make up one output row of the sampling grid: one for
     * ordinary images, two for sensor data where a filter cell spans two photosite rows.
     */
    private fun blockHasWantedRow(
        blockTop: Int,
        rows: Int,
        cropTop: Int,
        cropBottom: Int,
        step: Int,
        tapOffset: Int,
        taps: Int,
        cellRows: Int = 1,
    ): Boolean {
        for (offsetY in 0 until rows) {
            val y = blockTop + offsetY
            if (y < cropTop) continue
            if (y >= cropBottom) return false
            val inCell = ((y - cropTop) / cellRows) % step
            if (inCell >= tapOffset && inCell < tapOffset + taps) return true
        }
        return false
    }

    /** Coarsest power-of-two subsampling that still fills the target and fits in a bitmap. */
    private fun stepFor(cropWidth: Int, cropHeight: Int, targetWidth: Int, targetHeight: Int): Int? {
        var step = 1
        while (
            (cropWidth / step).toLong() * (cropHeight / step).toLong() > MAX_OUTPUT_PIXELS ||
            cropWidth / step > MAX_OUTPUT_EDGE ||
            cropHeight / step > MAX_OUTPUT_EDGE ||
            // The crop is drawn fitted, so it is the axis that fills the target first that
            // decides how many source pixels are actually worth reading.
            (targetWidth > 0 && targetHeight > 0 &&
                (cropWidth / (step * 2) >= targetWidth || cropHeight / (step * 2) >= targetHeight))
        ) {
            step *= 2
            if (step > 4096) return null
        }
        return step
    }

    internal fun readFully(stream: InputStream, buffer: ByteArray, length: Int = buffer.size): Boolean {
        var filled = 0
        while (filled < length) {
            val read = stream.read(buffer, filled, length - filled)
            if (read <= 0) return false
            filled += read
        }
        return true
    }

    internal fun skipFully(stream: InputStream, count: Long, scratch: ByteArray) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read <= 0) return
            remaining -= read
        }
    }

    /** Rows are pulled through a stream, so even a single 500 MB strip costs one row of memory. */
    internal fun blockStream(source: ByteSource, offset: Int, length: Int, compression: Int): InputStream? {
        if (offset < 0 || length <= 0 || offset + length > source.size) return null
        val base = source.stream(offset, length) ?: return null
        return when (compression) {
            COMPRESSION_NONE -> base
            COMPRESSION_PACKBITS -> PackBitsInputStream(base)
            COMPRESSION_LZW -> LzwInputStream(base)
            COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_ADOBE -> InflaterInputStream(base)
            else -> null
        }
    }

    /** Raw sample [index] of a row, for 8, 16 and the packed 12/14-bit layouts RAW files use. */
    internal fun sampleValue(row: ByteArray, index: Int, bits: Int, littleEndian: Boolean): Int = when (bits) {
        8 -> {
            if (index >= row.size) 0 else row[index].toInt() and 0xFF
        }
        16 -> {
            val at = index * 2
            if (at + 1 >= row.size) {
                0
            } else {
                val low = row[at].toInt() and 0xFF
                val high = row[at + 1].toInt() and 0xFF
                if (littleEndian) (high shl 8) or low else (low shl 8) or high
            }
        }
        else -> {
            // Packed samples are written most-significant bit first, whatever the file's byte order.
            var bit = index * bits
            var got = 0
            var value = 0
            while (got < bits) {
                val at = bit ushr 3
                if (at >= row.size) break
                val free = 8 - (bit and 7)
                val take = minOf(free, bits - got)
                val chunk = ((row[at].toInt() and 0xFF) shr (free - take)) and ((1 shl take) - 1)
                value = (value shl take) or chunk
                got += take
                bit += take
            }
            value
        }
    }

    private fun pixelAt(
        row: ByteArray,
        at: Int,
        samples: Int,
        bits: Int,
        photometric: Int,
        littleEndian: Boolean,
        palette: LongArray?,
    ): Int {
        val shift = if (bits > 8) bits - 8 else 0
        fun sample(index: Int): Int = sampleValue(row, at + index, bits, littleEndian) shr shift

        return when {
            palette != null -> {
                val index = sampleValue(row, at, bits, littleEndian)
                val entries = palette.size / 3
                if (index >= entries) {
                    0xFF000000.toInt()
                } else {
                    val red = (palette[index] shr 8).toInt() and 0xFF
                    val green = (palette[index + entries] shr 8).toInt() and 0xFF
                    val blue = (palette[index + 2 * entries] shr 8).toInt() and 0xFF
                    0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                }
            }
            samples >= 3 -> {
                val alpha = if (samples >= 4) sample(3) else 0xFF
                (alpha shl 24) or (sample(0) shl 16) or (sample(1) shl 8) or sample(2)
            }
            else -> {
                val value = sample(0)
                val grey = if (photometric == 0) 255 - value else value
                0xFF000000.toInt() or (grey shl 16) or (grey shl 8) or grey
            }
        }
    }

    internal fun applyHorizontalPredictor(row: ByteArray, width: Int, samples: Int) {
        val end = minOf(width * samples, row.size)
        for (x in samples until end) {
            row[x] = (row[x] + row[x - samples]).toByte()
        }
    }

    // ---- Undemosaiced sensor data -------------------------------------------------------------

    /**
     * The full-resolution sensor image behind a RAW file: one sample per photosite, arranged in a
     * colour filter array. Like [TiffImage] it renders a crop at a scale, so the inspector can go
     * to 1:1 on a 60 MP frame without ever holding the whole demosaiced image.
     */
    class SensorImage internal constructor(
        internal val info: SensorInfo,
    ) {
        /** The visible frame, in photosites: the masked border a sensor carries is left out. */
        val width: Int get() = info.width
        val height: Int get() = info.height

        /** How the sensor data is stored, so the inspector can name what it is showing. */
        val source: String get() = info.label

        fun render(targetWidth: Int, targetHeight: Int, region: Rect?): Bitmap? =
            renderSensor(info, targetWidth, targetHeight, region)
    }

    /** Everything needed to decode and develop one RAW file's photosites. */
    internal class SensorInfo(
        /** A codec holds decoding state, so each render gets its own. */
        val open: () -> SensorCodec?,
        val label: String,
        val sensorWidth: Int,
        val sensorHeight: Int,
        val originX: Int,
        val originY: Int,
        val width: Int,
        val height: Int,
        val pattern: IntArray,
        val colour: SensorColour,
    )

    /**
     * Opens the sensor data of a RAW file. Returns null when the sensor data is stored in a way
     * this decoder cannot read (Canon's CR3 codec, Fujifilm's compressed RAF, …), so callers can
     * say so instead of quietly showing the embedded preview again.
     */
    fun openSensor(source: ByteSource): SensorImage? {
        val file = tiffHeader(source) ?: return null
        val directories = mutableListOf<Directory>()
        readDirectories(file, file.u32(4).toInt(), directories, depth = 0)
        val best = directories
            .mapNotNull { sensorInfo(file, directories, it) }
            .maxByOrNull { it.sensorWidth.toLong() * it.sensorHeight }
            ?: return null
        return SensorImage(best)
    }

    /** Kept for callers that only want a whole-frame demosaic. */
    fun demosaic(source: ByteSource, targetWidth: Int, targetHeight: Int): Bitmap? =
        openSensor(source)?.render(targetWidth, targetHeight, null)

    /**
     * Describes one directory's sensor data, or null when it holds something else (a preview, a
     * thumbnail) or a layout with no codec here.
     */
    private fun sensorInfo(file: TiffFile, all: List<Directory>, directory: Directory): SensorInfo? {
        val width = directory.first(TAG_IMAGE_WIDTH, 0).toInt()
        val height = directory.first(TAG_IMAGE_LENGTH, 0).toInt()
        if (width < 16 || height < 16) return null
        if (directory.first(TAG_PLANAR_CONFIGURATION, 1) != 1L) return null
        val compression = directory.first(TAG_COMPRESSION, 1).toInt()
        val photometric = directory.first(TAG_PHOTOMETRIC, -1)
        // 1 = BlackIsZero (some backs write plain greyscale), 32803 = colour filter array.
        if (photometric != -1L && photometric != 1L && photometric != PHOTOMETRIC_CFA.toLong()) return null

        val blocks = blocksFor(directory, width, height)
        val tagBits = directory.values(TAG_BITS_PER_SAMPLE)?.firstOrNull()?.toInt()
        val maker = makerFor(file, all)

        var bits = tagBits ?: 0
        val label: String
        val open: () -> SensorCodec?

        when {
            supportedCompression(compression) -> {
                if (blocks == null) return null
                if (directory.first(TAG_SAMPLES_PER_PIXEL, 1) != 1L) return null
                if (bits != 8 && bits != 12 && bits != 14 && bits != 16) return null
                val predictor = directory.first(TAG_PREDICTOR, 1).toInt()
                val container = containerBits(blocks, bits, compression)
                label = if (compression == COMPRESSION_NONE) "uncompressed sensor data" else "sensor data"
                open = {
                    PackedSensorCodec(
                        source = file.source,
                        blocks = blocks,
                        width = width,
                        height = height,
                        bits = container,
                        compression = compression,
                        littleEndian = file.littleEndian,
                        predictor = predictor,
                        label = label,
                    )
                }
            }
            compression == COMPRESSION_SONY_ARW -> {
                if (blocks == null || blocks.across != 1) return null
                // Sony packs sixteen photosites into sixteen bytes, so a row is one byte wide
                // per photosite whatever the sensor's bit depth is.
                val offset = blocks.offsets.firstOrNull()?.toInt() ?: return null
                val stored = blocks.counts.firstOrNull() ?: return null
                if (stored < width.toLong() * height) return null
                if (offset < 0 || offset.toLong() + stored > file.source.size) return null
                if (bits <= 0) bits = 14
                val curve = sonyToneCurve(directory)
                label = "Sony compressed RAW"
                open = { Arw2SensorCodec(file.source, offset, width, height, curve) }
            }
            compression == COMPRESSION_NIKON -> {
                if (blocks == null || blocks.across != 1) return null
                val offset = blocks.offsets.firstOrNull()?.toInt() ?: return null
                val length = blocks.counts.firstOrNull()?.toInt() ?: return null
                if (bits != 12 && bits != 14) return null
                val nikon = maker?.let { nikonCompression(it, bits) } ?: return null
                label = "Nikon compressed RAW"
                open = {
                    NikonSensorCodec(
                        source = file.source,
                        offset = offset,
                        length = length,
                        width = width,
                        height = height,
                        bits = bits,
                        curve = nikon.curve,
                        vertical = nikon.vertical,
                        tree = nikon.tree,
                        split = nikon.split,
                    )
                }
            }
            compression == COMPRESSION_LOSSLESS_JPEG || compression == COMPRESSION_OLD_JPEG -> {
                // An ordinary JPEG preview is stored the same way; only a lossless frame whose
                // samples cover the whole sensor is sensor data.
                if (blocks == null || blocks.across != 1) return null
                val offset = blocks.offsets.firstOrNull()?.toInt() ?: return null
                val length = blocks.counts.firstOrNull()?.toInt() ?: return null
                val slices = directory.values(TAG_CR2_SLICE)
                    ?.takeIf { it.size >= 3 }
                    ?.let { intArrayOf(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
                val codec = LosslessJpegSensorCodec.open(
                    file.source, offset, length, width, height, slices, "lossless JPEG RAW",
                ) ?: return null
                // Canon writes 16 in the tag whatever the sensor's depth is; the frame's own
                // precision is the one the samples are actually coded at.
                bits = codec.bits
                if (bits !in 8..16) return null
                label = "lossless JPEG RAW"
                open = {
                    LosslessJpegSensorCodec.open(
                        file.source, offset, length, width, height, slices, label,
                    )
                }
            }
            else -> return null
        }

        val crop = visibleFrame(directory, maker, width, height)
        val colour = sensorColour(directory, maker, bits, open, crop.left)
        return SensorInfo(
            open = open,
            label = label,
            sensorWidth = width,
            sensorHeight = height,
            originX = crop.left,
            originY = crop.top,
            width = crop.width(),
            height = crop.height(),
            pattern = cfaPattern(directory),
            colour = colour,
        )
    }

    /**
     * How wide the container holding each sample is. A camera that calls its RAW "14-bit
     * uncompressed" usually means 14 bits of data in a 16 bit word, which the block's byte count
     * gives away: it is twice as long as packed samples would need.
     */
    private fun containerBits(blocks: Blocks, bits: Int, compression: Int): Int {
        if (compression != COMPRESSION_NONE || bits >= 16) return bits
        val stored = blocks.counts.firstOrNull() ?: return bits
        val rows = blocks.height.toLong().coerceAtLeast(1L)
        val packedRow = (blocks.width.toLong() * bits + 7) / 8
        val storedRow = stored / rows
        return if (storedRow >= blocks.width.toLong() * 2 && storedRow > packedRow) 16 else bits
    }

    /**
     * The part of the sensor the camera actually images. Sensors carry masked and optically
     * black borders which are not part of the photograph, and Canon's are wide enough to be
     * obvious in the inspector.
     */
    private fun visibleFrame(directory: Directory, maker: MakerNote?, width: Int, height: Int): Rect {
        val origin = directory.values(TAG_DEFAULT_CROP_ORIGIN)
        val size = directory.values(TAG_DEFAULT_CROP_SIZE)
        if (origin != null && origin.size >= 2 && size != null && size.size >= 2) {
            val rect = Rect(
                origin[0].toInt(),
                origin[1].toInt(),
                origin[0].toInt() + size[0].toInt(),
                origin[1].toInt() + size[1].toInt(),
            )
            if (rect.intersect(0, 0, width, height) && rect.width() >= 16 && rect.height() >= 16) {
                return alignToFilterCell(rect)
            }
        }
        val borders = maker?.canonSensorBorders()
        if (borders != null) {
            val rect = Rect(borders[0], borders[1], borders[2] + 1, borders[3] + 1)
            if (rect.intersect(0, 0, width, height) && rect.width() >= 16 && rect.height() >= 16) {
                return alignToFilterCell(rect)
            }
        }
        return Rect(0, 0, width, height)
    }

    /** A crop that starts mid-cell would swap the colours of every pixel in the frame. */
    private fun alignToFilterCell(rect: Rect): Rect = Rect(
        rect.left and 1.inv(),
        rect.top and 1.inv(),
        rect.right and 1.inv(),
        rect.bottom and 1.inv(),
    )

    /** Colour of each photosite in the 2x2 filter cell: 0 red, 1 green, 2 blue. */
    private fun cfaPattern(directory: Directory): IntArray {
        val dim = directory.values(TAG_CFA_REPEAT_DIM)
        val pattern = directory.values(TAG_CFA_PATTERN)
        if (pattern == null || pattern.size < 4) return intArrayOf(0, 1, 1, 2)
        if (dim != null && dim.size >= 2 && (dim[0] != 2L || dim[1] != 2L)) return intArrayOf(0, 1, 1, 2)
        val colours = IntArray(4) { pattern[it].toInt() }
        return if (colours.any { it !in 0..2 }) intArrayOf(0, 1, 1, 2) else colours
    }

    internal class SensorColour(
        val black: Float,
        val white: Float,
        val gains: FloatArray,
    ) {
        /** Normalised, white balanced and gamma encoded, which is the minimum a sensor value
         * needs before it looks like a photograph rather than a dark green cast. */
        fun encode(raw: Int, colour: Int): Int {
            val range = (white - black).coerceAtLeast(1f)
            val linear = ((raw - black) / range).coerceIn(0f, 1f) * gains[colour]
            val clamped = linear.coerceIn(0f, 1f)
            val encoded = if (clamped <= 0.0031308f) {
                clamped * 12.92f
            } else {
                1.055f * clamped.pow(1f / 2.4f) - 0.055f
            }
            return (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /**
     * Black point, white point and white balance. DNG records all three in ordinary tags;
     * every camera maker records them somewhere of its own, and without them a RAW develops
     * as a flat green frame, so each maker's own location is read too.
     */
    private fun sensorColour(
        directory: Directory,
        maker: MakerNote?,
        bits: Int,
        open: () -> SensorCodec?,
        maskedColumns: Int,
    ): SensorColour {
        val maximum = ((1 shl bits) - 1).toFloat()
        var black = directory.doubles(TAG_BLACK_LEVEL)?.firstOrNull()?.toFloat()
        var white = directory.doubles(TAG_WHITE_LEVEL)?.firstOrNull()?.toFloat()
        var gains = gainsFrom(directory.doubles(TAG_AS_SHOT_NEUTRAL)?.let { neutral ->
            if (neutral.size >= 3 && neutral.all { it > 0.0 }) {
                floatArrayOf(
                    (neutral[1] / neutral[0]).toFloat(),
                    1f,
                    (neutral[1] / neutral[2]).toFloat(),
                )
            } else {
                null
            }
        })

        if (maker != null) {
            when {
                maker.isSony -> maker.sonyPrivate()?.let { sony ->
                    if (black == null) black = sony.black
                    // Sony clips its sensor below the bit depth's maximum.
                    if (sony.white != null) white = sony.white
                    if (gains == null) gains = gainsFrom(sony.gains)
                }
                maker.isNikon -> {
                    if (black == null) black = maker.shorts(TAG_NIKON_BLACK_LEVEL)?.firstOrNull()?.toFloat()
                    if (gains == null) gains = gainsFrom(maker.nikonWhiteBalance())
                }
                maker.isCanon -> if (gains == null) gains = gainsFrom(maker.canonWhiteBalance())
            }
        }
        // Canon states its black point nowhere a decoder can rely on, but leaves a masked
        // border on the sensor: what those photosites read is the black point by definition.
        if (black == null && maskedColumns >= 32) black = estimateBlack(open, maskedColumns)

        val resolved = (black ?: 0f).coerceIn(0f, maximum)
        return SensorColour(
            black = resolved,
            white = (white ?: maximum).coerceIn(1f, maximum).coerceAtLeast(resolved + 1f),
            gains = gains ?: FloatArray(3) { 1f },
        )
    }

    /** Green is left at unity so only the cast is removed and the exposure is unchanged. */
    private fun gainsFrom(multipliers: FloatArray?): FloatArray? {
        if (multipliers == null || multipliers.size < 3) return null
        if (multipliers.any { !it.isFinite() || it <= 0f }) return null
        return FloatArray(3) { multipliers[it].coerceIn(0.25f, 8f) }
    }

    /** Averages the optically black columns of the first rows of the frame. */
    private fun estimateBlack(open: () -> SensorCodec?, maskedColumns: Int): Float? {
        val codec = open() ?: return null
        val from = 4
        val to = (maskedColumns - 8).coerceAtMost(from + 64)
        if (to <= from) return null
        var total = 0L
        var count = 0
        val sink = SensorRowSink { y, xStart, samples, samplesCount ->
            if (y < BLACK_SAMPLE_ROWS) {
                for (x in maxOf(from, xStart) until minOf(to, xStart + samplesCount)) {
                    total += samples[x - xStart].toLong()
                    count += 1
                }
            }
        }
        runCatching { codec.scan({ y -> y < BLACK_SAMPLE_ROWS }, sink) }
        return if (count < 64) null else (total.toDouble() / count).toFloat()
    }

    // ---- Maker notes --------------------------------------------------------------------------

    /**
     * A camera maker's private metadata. It is a TIFF directory like any other, but Nikon keeps
     * it in its own little TIFF with its own byte order and offsets, and Sony encrypts the block
     * holding the values that matter, so each is reached differently.
     */
    internal class MakerNote(
        val make: String,
        private val file: TiffFile,
        private val notes: Directory?,
        private val notesOffset: Int,
        private val rootFile: TiffFile,
        private val directories: List<Directory>,
    ) {
        val isSony: Boolean get() = make.startsWith("SONY", ignoreCase = true)
        val isNikon: Boolean get() = make.startsWith("NIKON", ignoreCase = true)
        val isCanon: Boolean get() = make.startsWith("CANON", ignoreCase = true)

        fun shorts(tag: Int): LongArray? = notes?.values(tag)

        /** Where a maker note tag's data starts, for values this decoder parses itself. */
        fun dataOffset(tag: Int): Int? = fieldOffset(file, notesOffset, tag)

        fun notesFile(): TiffFile = file

        fun canonSensorBorders(): IntArray? {
            val info = shorts(TAG_CANON_SENSOR_INFO) ?: return null
            if (info.size < 9) return null
            val borders = IntArray(4) { info[5 + it].toInt() }
            if (borders[2] <= borders[0] || borders[3] <= borders[1]) return null
            return borders
        }

        /**
         * Canon's as-shot levels live inside its colour data block at an offset that depends on
         * the block's version. Only the versions with a known offset are read, and the values
         * are checked for the shape white balance levels have before they are believed.
         */
        fun canonWhiteBalance(): FloatArray? {
            val data = shorts(TAG_CANON_COLOR_DATA) ?: return null
            if (data.isEmpty()) return null
            for (at in CANON_WHITE_BALANCE_OFFSETS) {
                if (at + 3 >= data.size) continue
                val red = data[at].toInt()
                val greenOne = data[at + 1].toInt()
                val greenTwo = data[at + 2].toInt()
                val blue = data[at + 3].toInt()
                if (greenOne != greenTwo || greenOne !in 512..4096) continue
                if (red !in 256..16384 || blue !in 256..16384) continue
                return floatArrayOf(
                    red.toFloat() / greenOne,
                    1f,
                    blue.toFloat() / greenOne,
                )
            }
            return null
        }

        /** Nikon stores the red and blue multipliers directly, already relative to green. */
        fun nikonWhiteBalance(): FloatArray? {
            val levels = notes?.doubles(TAG_NIKON_WB_LEVELS) ?: return null
            if (levels.size < 2 || levels[0] <= 0.0 || levels[1] <= 0.0) return null
            return floatArrayOf(levels[0].toFloat(), 1f, levels[1].toFloat())
        }

        /**
         * Sony hides its levels in an encrypted directory whose location is written into the
         * DNG private data tag, so it is reached through the file rather than the maker note.
         */
        fun sonyPrivate(): SonyPrivate? {
            val root = directories.firstOrNull() ?: return null
            if (root.fields[TAG_DNG_PRIVATE] == null) return null
            val pointer = fieldOffset(rootFile, rootFile.u32(4).toInt(), TAG_DNG_PRIVATE) ?: return null
            val privateOffset = rootFile.u32(pointer).toInt()
            val privateIfd = readSingleDirectory(rootFile, privateOffset) ?: return null
            val offset = privateIfd.first(TAG_SONY_SR2_OFFSET, 0).toInt()
            val length = privateIfd.first(TAG_SONY_SR2_LENGTH, 0).toInt()
            val keyAt = fieldOffset(rootFile, privateOffset, TAG_SONY_SR2_KEY) ?: return null
            val key = rootFile.u32(keyAt).toInt()
            if (offset <= 0 || length <= 16 || length > MAX_SONY_PRIVATE_BYTES) return null
            if (offset.toLong() + length > rootFile.source.size) return null
            val encrypted = rootFile.source.copyRange(offset, length) ?: return null
            sonyDecrypt(encrypted, key)
            val decrypted = TiffFile(OffsetSource(encrypted, offset), rootFile.littleEndian)
            val directory = readSingleDirectory(decrypted, offset) ?: return null
            val black = directory.values(TAG_SONY_BLACK_LEVEL)?.firstOrNull()?.toFloat()
            val white = directory.values(TAG_SONY_WHITE_LEVEL)?.firstOrNull()?.toFloat()
            val levels = directory.values(TAG_SONY_WB_LEVELS)
            val gains = if (levels != null && levels.size >= 4 && levels[1] > 0) {
                floatArrayOf(
                    levels[0].toFloat() / levels[1],
                    1f,
                    levels[3].toFloat() / levels[1],
                )
            } else {
                null
            }
            if (black == null && gains == null && white == null) return null
            return SonyPrivate(black, white, gains)
        }
    }

    internal class SonyPrivate(val black: Float?, val white: Float?, val gains: FloatArray?)

    private fun makerFor(file: TiffFile, directories: List<Directory>): MakerNote? {
        val root = directories.firstOrNull() ?: return null
        val make = root.text(TAG_MAKE) ?: return null
        var notes: Directory? = null
        var notesFile = file
        var notesOffset = 0
        val exif = root.first(TAG_EXIF_IFD, 0).toInt()
        if (exif > 0) {
            val at = fieldOffset(file, exif, TAG_MAKER_NOTE)
            if (at != null && at > 0) {
                if (make.startsWith("NIKON", ignoreCase = true) && looksLikeNikonNote(file, at)) {
                    // Nikon's note is a TIFF of its own: its offsets count from its own header.
                    val inner = tiffHeader(SubSource(file.source, at + NIKON_NOTE_HEADER))
                    if (inner != null) {
                        notesFile = inner
                        notesOffset = inner.u32(4).toInt()
                        notes = readSingleDirectory(inner, notesOffset)
                    }
                } else {
                    notesOffset = at
                    notes = readSingleDirectory(file, at)
                }
            }
        }
        return MakerNote(make, notesFile, notes, notesOffset, file, directories)
    }

    private fun looksLikeNikonNote(file: TiffFile, at: Int): Boolean {
        if (at + NIKON_NOTE_HEADER + 8 > file.source.size) return false
        return file.u8(at) == 'N'.code && file.u8(at + 1) == 'i'.code && file.u8(at + 2) == 'k'.code
    }

    /** Reads one directory without following its chain, for notes that are not part of one. */
    private fun readSingleDirectory(file: TiffFile, offset: Int): Directory? {
        if (offset <= 0 || offset + 2 > file.source.size) return null
        val count = file.u16(offset)
        if (count <= 0 || count > 1024) return null
        if (offset + 2 + count * 12 > file.source.size) return null
        val fields = HashMap<Int, Field>(count)
        for (i in 0 until count) {
            val entry = offset + 2 + i * 12
            val field = readField(file, entry) ?: continue
            fields[file.u16(entry)] = field
        }
        return Directory(fields)
    }

    /** The file offset of a field's data, which is what a nested parser needs. */
    private fun fieldOffset(file: TiffFile, ifd: Int, tag: Int): Int? {
        if (ifd <= 0 || ifd + 2 > file.source.size) return null
        val count = file.u16(ifd)
        if (count <= 0 || count > 1024) return null
        if (ifd + 2 + count * 12 > file.source.size) return null
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (file.u16(entry) != tag) continue
            val type = file.u16(entry + 2)
            val length = file.u32(entry + 4) * when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                else -> 8
            }
            return if (length <= 4) entry + 8 else file.u32(entry + 8).toInt()
        }
        return null
    }

    /**
     * Sony's block cipher: a 128 word pad generated from a key in the file, XORed over the data
     * a word at a time. The pad's own words are folded together as it is consumed.
     */
    private fun sonyDecrypt(data: ByteArray, key: Int) {
        val pad = IntArray(128)
        var seed = key
        for (p in 0 until 4) {
            seed = seed * 48828125 + 1
            pad[p] = seed
        }
        pad[3] = (pad[3] shl 1) or ((pad[0] xor pad[2]) ushr 31)
        for (p in 4 until 127) {
            pad[p] = ((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) ushr 31)
        }
        for (p in 0 until 127) pad[p] = Integer.reverseBytes(pad[p])
        // The pad is consumed from where its generation left off, not from its start.
        var p = 127
        var at = 0
        while (at + 4 <= data.size) {
            pad[p and 127] = pad[(p + 1) and 127] xor pad[(p + 65) and 127]
            val word = (data[at].toInt() and 0xFF) or
                ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or
                ((data[at + 3].toInt() and 0xFF) shl 24)
            val plain = word xor pad[p and 127]
            data[at] = plain.toByte()
            data[at + 1] = (plain shr 8).toByte()
            data[at + 2] = (plain shr 16).toByte()
            data[at + 3] = (plain shr 24).toByte()
            p += 1
            at += 4
        }
    }

    /** Sony's tone curve: the five segments that put 11-bit compressed values back on scale. */
    private fun sonyToneCurve(directory: Directory): IntArray? {
        val points = directory.values(TAG_SONY_TONE_CURVE) ?: return null
        if (points.size < 4) return null
        val breaks = IntArray(6)
        for (i in 0 until 4) breaks[i + 1] = ((points[i] shr 2) and 0xFFF).toInt()
        breaks[5] = 0xFFF
        if (breaks.toList() != breaks.sorted()) return null
        val curve = IntArray(0x1000)
        for (segment in 0 until 5) {
            for (i in (breaks[segment] + 1)..breaks[segment + 1]) {
                curve[i] = curve[i - 1] + (1 shl segment)
            }
        }
        return curve
    }

    /** Nikon's Huffman tree choice, linearisation curve and the row its tree changes on. */
    internal class NikonMeta(val curve: IntArray, val vertical: IntArray, val tree: Int, val split: Int)

    private fun nikonCompression(maker: MakerNote, bits: Int): NikonMeta? {
        if (!maker.isNikon) return null
        val file = maker.notesFile()
        val start = maker.dataOffset(TAG_NIKON_LINEARIZATION) ?: return null
        if (start <= 0 || start + 16 > file.source.size) return null
        var at = start
        val version = file.u8(at)
        val revision = file.u8(at + 1)
        at += 2
        if (version == 0x49 || revision == 0x58) at += 2110
        var tree = if (version == 0x46) 2 else 0
        if (bits == 14) tree += 3
        if (at + 10 > file.source.size) return null
        val vertical = IntArray(4) { file.u16(at + it * 2) }
        at += 8
        var max = (1 shl bits) and 0x7FFF
        val size = file.u16(at)
        at += 2
        // As wide as a sample index can be, so the curve's last point lands inside it.
        val curve = IntArray(1 shl 16) { it }
        var split = 0
        val step = if (size > 1) max / (size - 1) else 0
        if (version == 0x44 && revision == 0x20 && step > 0) {
            if (at + size * 2 > file.source.size) return null
            for (i in 0 until size) {
                val index = i * step
                if (index < curve.size) curve[index] = file.u16(at + i * 2)
            }
            for (i in 0 until minOf(max, curve.size)) {
                val base = i - i % step
                val next = minOf(base + step, curve.size - 1)
                curve[i] = (curve[base] * (step - i % step) + curve[next] * (i % step)) / step
            }
            split = file.u16(start + 562)
        } else if (version != 0x46 && size in 1..0x4001) {
            if (at + size * 2 > file.source.size) return null
            max = minOf(size, curve.size)
            for (i in 0 until max) curve[i] = file.u16(at + i * 2)
        }
        max = max.coerceIn(2, curve.size)
        while (max > 2 && curve[max - 2] == curve[max - 1]) max -= 1
        // Everything above the curve's last meaningful entry is clipped, not extrapolated.
        for (i in max until curve.size) curve[i] = curve[max - 1]
        if (split !in 0 until (1 shl 16)) split = 0
        return NikonMeta(curve, vertical, tree, split)
    }

    /**
     * Demosaics [region] of the sensor. Each output pixel averages the photosites of one or more
     * complete filter cells, so colour is reconstructed without interpolating across the crop's
     * edges and without ever materialising the full frame.
     */
    private fun renderSensor(
        info: SensorInfo,
        targetWidth: Int,
        targetHeight: Int,
        region: Rect?,
    ): Bitmap? {
        // The caller works in the visible frame; the codec works in whole-sensor coordinates.
        val crop = Rect(0, 0, info.width, info.height)
        if (region != null && !crop.setIntersect(region, Rect(0, 0, info.width, info.height))) return null
        crop.offset(info.originX, info.originY)
        // Filter cells are 2x2, so a crop that starts mid-cell would swap the colours.
        crop.left = crop.left and 1.inv()
        crop.top = crop.top and 1.inv()
        crop.right = (crop.right + 1) and 1.inv()
        crop.bottom = (crop.bottom + 1) and 1.inv()
        if (!crop.intersect(0, 0, info.sensorWidth and 1.inv(), info.sensorHeight and 1.inv())) return null
        if (crop.width() < 2 || crop.height() < 2) return null

        // One output pixel per filter cell at 1:1; coarser scales average whole cells.
        val cellsWide = crop.width() / 2
        val cellsHigh = crop.height() / 2
        val step = stepFor(cellsWide, cellsHigh, targetWidth, targetHeight) ?: return null
        val outWidth = (cellsWide + step - 1) / step
        val outHeight = (cellsHigh + step - 1) / step
        if (outWidth <= 0 || outHeight <= 0) return null

        val taps = if (step > 1) minOf(step, MAX_TAPS) else 1
        val filtered = taps > 1 && outWidth.toLong() * outHeight <= MAX_FILTERED_PIXELS
        val effectiveTaps = if (filtered) taps else 1
        val tapOffset = (step - effectiveTaps) / 2

        val pixels = IntArray(outWidth * outHeight)
        val sums = IntArray(outWidth * outHeight * 3)
        val hits = IntArray(outWidth * outHeight * 3)
        val colour = info.colour
        val pattern = info.pattern

        val wanted = { y: Int ->
            if (y < crop.top || y >= crop.bottom) {
                false
            } else {
                val inCell = ((y - crop.top) / 2) % step
                inCell >= tapOffset && inCell < tapOffset + effectiveTaps
            }
        }
        val sink = SensorRowSink { y, xStart, samples, count ->
            val outY = ((y - crop.top) / 2) / step
            if (outY in 0 until outHeight) {
                val patternRow = (y - crop.top) and 1
                val from = maxOf(crop.left, xStart)
                val to = minOf(crop.right, xStart + count)
                var x = from
                while (x < to) {
                    val cell = (x - crop.left) / 2
                    val inCell = cell % step
                    if (inCell >= tapOffset && inCell < tapOffset + effectiveTaps) {
                        val outX = cell / step
                        if (outX < outWidth) {
                            val channel = pattern[patternRow * 2 + ((x - crop.left) and 1)]
                            val at = (outY * outWidth + outX) * 3 + channel
                            sums[at] += colour.encode(samples[x - xStart], channel)
                            hits[at] += 1
                        }
                    }
                    x += 1
                }
            }
        }

        val codec = info.open() ?: return null
        codec.scan(wanted, sink)

        for (at in pixels.indices) {
            val red = if (hits[at * 3] > 0) sums[at * 3] / hits[at * 3] else 0
            val green = if (hits[at * 3 + 1] > 0) sums[at * 3 + 1] / hits[at * 3 + 1] else 0
            val blue = if (hits[at * 3 + 2] > 0) sums[at * 3 + 2] / hits[at * 3 + 2] else 0
            pixels[at] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
        }

        return runCatching {
            Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}

/** PackBits run-length decoding as a stream, so callers never buffer a whole strip. */
private class PackBitsInputStream(private val base: InputStream) : InputStream() {
    private var literal = 0
    private var repeat = 0
    private var repeatValue = 0

    override fun read(): Int {
        while (true) {
            if (repeat > 0) {
                repeat -= 1
                return repeatValue
            }
            if (literal > 0) {
                literal -= 1
                return base.read()
            }
            val control = base.read()
            if (control < 0) return -1
            val signed = control.toByte().toInt()
            when {
                signed == -128 -> Unit
                signed >= 0 -> literal = signed + 1
                else -> {
                    val value = base.read()
                    if (value < 0) return -1
                    repeat = -signed + 1
                    repeatValue = value
                }
            }
        }
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        var written = 0
        while (written < length) {
            val value = read()
            if (value < 0) break
            destination[offset + written] = value.toByte()
            written += 1
        }
        return if (written == 0) -1 else written
    }
}

/** TIFF flavour of LZW: MSB-first codes, 9-12 bits wide, with the classic early change. */
private class LzwInputStream(private val base: InputStream) : InputStream() {
    private val table = arrayOfNulls<ByteArray>(4096)
    private var nextCode = 258
    private var codeWidth = 9
    private var previous: ByteArray? = null
    private var pending: ByteArray? = null
    private var pendingPosition = 0
    private var bitBuffer = 0L
    private var bitCount = 0
    private var finished = false

    init {
        for (i in 0 until 256) table[i] = byteArrayOf(i.toByte())
    }

    private fun nextCodeValue(): Int {
        while (bitCount < codeWidth) {
            val byte = base.read()
            if (byte < 0) return -1
            bitBuffer = (bitBuffer shl 8) or byte.toLong()
            bitCount += 8
        }
        val shift = bitCount - codeWidth
        val code = ((bitBuffer shr shift) and ((1L shl codeWidth) - 1L)).toInt()
        bitCount = shift
        return code
    }

    private fun advance(): Boolean {
        while (true) {
            if (finished) return false
            val code = nextCodeValue()
            if (code < 0 || code == 257) {
                finished = true
                return false
            }
            if (code == 256) {
                nextCode = 258
                codeWidth = 9
                previous = null
                continue
            }
            val entry = when {
                code < nextCode && table[code] != null -> table[code]!!
                previous != null -> previous!! + previous!![0]
                else -> {
                    finished = true
                    return false
                }
            }
            val earlier = previous
            if (earlier != null && nextCode < 4096) {
                table[nextCode] = earlier + entry[0]
                nextCode += 1
            }
            previous = entry
            if (nextCode >= (1 shl codeWidth) - 1 && codeWidth < 12) codeWidth += 1
            pending = entry
            pendingPosition = 0
            return true
        }
    }

    override fun read(): Int {
        val current = pending
        if (current == null || pendingPosition >= current.size) {
            if (!advance()) return -1
            return read()
        }
        return current[pendingPosition++].toInt() and 0xFF
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        var written = 0
        while (written < length) {
            val current = pending
            if (current == null || pendingPosition >= current.size) {
                if (!advance()) break
                continue
            }
            val chunk = minOf(length - written, current.size - pendingPosition)
            System.arraycopy(current, pendingPosition, destination, offset + written, chunk)
            pendingPosition += chunk
            written += chunk
        }
        return if (written == 0) -1 else written
    }
}
