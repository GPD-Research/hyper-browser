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
     * The browsing view of a file: the embedded preview when there is one, and a downscaled
     * render of the image itself for TIFF. Sensor data is the inspector's job, not this one's.
     */
    fun decode(source: ByteSource, targetWidth: Int, targetHeight: Int, lowQuality: Boolean): Bitmap? =
        embeddedPreview(source, targetWidth, targetHeight, lowQuality)
            ?: openTiff(source)?.render(targetWidth, targetHeight, null)

    fun orientationDegrees(bytes: ByteArray): Int {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull() ?: return 0
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
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bestWidth, bestHeight, targetWidth, targetHeight)
            if (lowQuality) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(payload, 0, payload.size, options) }.getOrNull()
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

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_DEFLATE_ADOBE = 32946
    private const val COMPRESSION_PACKBITS = 32773

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
    private class Blocks(
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

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int = buffer.size): Boolean {
        var filled = 0
        while (filled < length) {
            val read = stream.read(buffer, filled, length - filled)
            if (read <= 0) return false
            filled += read
        }
        return true
    }

    private fun skipFully(stream: InputStream, count: Long, scratch: ByteArray) {
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
    private fun blockStream(source: ByteSource, offset: Int, length: Int, compression: Int): InputStream? {
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
    private fun sampleValue(row: ByteArray, index: Int, bits: Int, littleEndian: Boolean): Int = when (bits) {
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

    private fun applyHorizontalPredictor(row: ByteArray, width: Int, samples: Int) {
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
        internal val file: TiffFile,
        internal val directory: Directory,
        val width: Int,
        val height: Int,
    ) {
        fun render(targetWidth: Int, targetHeight: Int, region: Rect?): Bitmap? =
            renderSensor(file, directory, targetWidth, targetHeight, region)
    }

    /**
     * Opens the sensor data of a RAW file. Returns null when the file's sensor data uses a
     * compression this decoder cannot read (lossless JPEG, proprietary maker formats), so callers
     * can say so instead of quietly showing the embedded preview again.
     */
    fun openSensor(source: ByteSource): SensorImage? {
        val file = tiffHeader(source) ?: return null
        val directories = mutableListOf<Directory>()
        readDirectories(file, file.u32(4).toInt(), directories, depth = 0)
        val best = directories
            .filter { sensorDirectory(it) }
            .maxByOrNull { it.first(TAG_IMAGE_WIDTH, 0) * it.first(TAG_IMAGE_LENGTH, 0) }
            ?: return null
        return SensorImage(
            file = file,
            directory = best,
            width = best.first(TAG_IMAGE_WIDTH, 0).toInt(),
            height = best.first(TAG_IMAGE_LENGTH, 0).toInt(),
        )
    }

    /** Kept for callers that only want a whole-frame demosaic. */
    fun demosaic(source: ByteSource, targetWidth: Int, targetHeight: Int): Bitmap? =
        openSensor(source)?.render(targetWidth, targetHeight, null)

    private fun sensorDirectory(directory: Directory): Boolean {
        val width = directory.first(TAG_IMAGE_WIDTH, 0)
        val height = directory.first(TAG_IMAGE_LENGTH, 0)
        if (width < 16 || height < 16) return false
        if (blocksFor(directory, width.toInt(), height.toInt()) == null) return false
        if (directory.first(TAG_PLANAR_CONFIGURATION, 1) != 1L) return false
        if (directory.first(TAG_SAMPLES_PER_PIXEL, 1) != 1L) return false
        val bits = (directory.values(TAG_BITS_PER_SAMPLE) ?: return false).first()
        if (bits != 8L && bits != 12L && bits != 14L && bits != 16L) return false
        val photometric = directory.first(TAG_PHOTOMETRIC, -1)
        // 1 = BlackIsZero (some backs write plain greyscale), 32803 = colour filter array.
        if (photometric != 1L && photometric != PHOTOMETRIC_CFA.toLong()) return false
        return supportedCompression(directory.first(TAG_COMPRESSION, 1).toInt())
    }

    /** Colour of each photosite in the 2x2 filter cell: 0 red, 1 green, 2 blue. */
    private fun cfaPattern(directory: Directory): IntArray {
        val dim = directory.values(TAG_CFA_REPEAT_DIM)
        val pattern = directory.values(TAG_CFA_PATTERN)
        if (pattern == null || pattern.size < 4) return intArrayOf(0, 1, 1, 2)
        if (dim != null && dim.size >= 2 && (dim[0] != 2L || dim[1] != 2L)) return intArrayOf(0, 1, 1, 2)
        val colours = IntArray(4) { pattern[it].toInt() }
        return if (colours.any { it !in 0..2 }) intArrayOf(0, 1, 1, 2) else colours
    }

    private class SensorColour(
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

    private fun sensorColour(directory: Directory, bits: Int): SensorColour {
        val maximum = ((1 shl bits) - 1).toFloat()
        val black = directory.doubles(TAG_BLACK_LEVEL)?.firstOrNull()?.toFloat() ?: 0f
        val white = directory.doubles(TAG_WHITE_LEVEL)?.firstOrNull()?.toFloat() ?: maximum
        val neutral = directory.doubles(TAG_AS_SHOT_NEUTRAL)
        val gains = FloatArray(3) { 1f }
        if (neutral != null && neutral.size >= 3 && neutral.all { it > 0.0 }) {
            // Green is left at unity so exposure is unchanged and only the cast is removed.
            for (colour in 0..2) gains[colour] = (neutral[1] / neutral[colour]).toFloat().coerceIn(0.25f, 8f)
        }
        return SensorColour(
            black = black.coerceIn(0f, maximum),
            white = white.coerceIn(1f, maximum).coerceAtLeast(black + 1f),
            gains = gains,
        )
    }

    /**
     * Demosaics [region] of the sensor. Each output pixel averages the photosites of one or more
     * complete filter cells, so colour is reconstructed without interpolating across the crop's
     * edges and without ever materialising the full frame.
     */
    private fun renderSensor(
        file: TiffFile,
        directory: Directory,
        targetWidth: Int,
        targetHeight: Int,
        region: Rect?,
    ): Bitmap? {
        val width = directory.first(TAG_IMAGE_WIDTH, 0).toInt()
        val height = directory.first(TAG_IMAGE_LENGTH, 0).toInt()
        val bits = (directory.values(TAG_BITS_PER_SAMPLE) ?: longArrayOf(16)).first().toInt()
        val compression = directory.first(TAG_COMPRESSION, 1).toInt()
        val predictor = directory.first(TAG_PREDICTOR, 1).toInt()
        val blocks = blocksFor(directory, width, height) ?: return null
        val pattern = cfaPattern(directory)
        val colour = sensorColour(directory, bits)

        val crop = Rect(0, 0, width, height)
        if (region != null && !crop.setIntersect(region, Rect(0, 0, width, height))) return null
        // Filter cells are 2x2, so a crop that starts mid-cell would swap the colours.
        crop.left = crop.left and 1.inv()
        crop.top = crop.top and 1.inv()
        crop.right = (crop.right + 1) and 1.inv()
        crop.bottom = (crop.bottom + 1) and 1.inv()
        if (!crop.intersect(0, 0, width and 1.inv(), height and 1.inv())) return null
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

        val rowBytes = ((blocks.width.toLong() * bits + 7) / 8)
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
                // Skipped blocks cost as much to decompress as drawn ones, so they are not opened.
                if (
                    !blockHasWantedRow(
                        blockTop, rowsInBlock, crop.top, crop.bottom, step, tapOffset, effectiveTaps, cellRows = 2,
                    )
                ) {
                    continue
                }
                val stream = blockStream(
                    file.source,
                    blocks.offsets[index].toInt(),
                    blocks.counts[index].toInt(),
                    compression,
                ) ?: continue
                stream.use { input ->
                    for (offsetY in 0 until rowsInBlock) {
                        val y = blockTop + offsetY
                        if (y >= crop.bottom) return@use
                        val cellRow = (y - crop.top) / 2
                        val inCell = cellRow % step
                        val wanted = y >= crop.top && inCell >= tapOffset && inCell < tapOffset + effectiveTaps
                        if (!wanted) {
                            skipFully(input, rowBytes, scratch)
                            continue
                        }
                        if (!readFully(input, row)) return@use
                        if (predictor == 2 && bits == 8) applyHorizontalPredictor(row, blocks.width, 1)
                        val outY = cellRow / step
                        if (outY >= outHeight) return@use
                        // Both rows of a cell are read, so red, green and blue all contribute.
                        val patternRow = (y - crop.top) and 1
                        val firstOutX = ((maxOf(crop.left, blockLeft) - crop.left) / 2 / step).coerceAtLeast(0)
                        val lastOutX = ((minOf(crop.right, blockLeft + blocks.width) - 1 - crop.left) / 2 / step)
                            .coerceAtMost(outWidth - 1)
                        for (outX in firstOutX..lastOutX) {
                            val baseCell = outX * step + tapOffset
                            var tap = 0
                            while (tap < effectiveTaps) {
                                val cell = baseCell + tap
                                tap += 1
                                if (cell >= cellsWide) continue
                                for (patternColumn in 0..1) {
                                    val x = crop.left + cell * 2 + patternColumn
                                    if (x < blockLeft || x >= blockLeft + blocks.width) continue
                                    if (x >= crop.right || x >= width) continue
                                    val channel = pattern[patternRow * 2 + patternColumn]
                                    val raw = sampleValue(row, x - blockLeft, bits, file.littleEndian)
                                    val at = (outY * outWidth + outX) * 3 + channel
                                    sums[at] += colour.encode(raw, channel)
                                    hits[at] += 1
                                }
                            }
                        }
                    }
                }
            }
        }

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
