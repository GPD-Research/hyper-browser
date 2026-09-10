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
 * TIFF. RAW files are served from their embedded full-size JPEG preview, which is what every RAW
 * viewer shows until a full demosaic is requested; TIFF is decoded from its strips, pulling one
 * row at a time so the source size never dictates memory use.
 *
 * Everything here parses untrusted bytes, so every offset and length is bounds checked and every
 * allocation is capped.
 */
object RawImage {

    /** Ceiling for pulling a whole file onto the heap; larger sources have to be mapped instead. */
    private const val MAX_FILE_BYTES = 192 * 1024 * 1024

    /**
     * Matches the viewer's limit: RecordingCanvas rejects bitmaps over 100 MB and GPUs cap texture
     * edges, so a decoded frame has to come back small enough to draw.
     */
    private const val MAX_OUTPUT_PIXELS = 16_000_000L
    private const val MAX_OUTPUT_EDGE = 8192

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

    fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int, lowQuality: Boolean, useFullRaw: Boolean = false): Bitmap? {
        val source = ArraySource(bytes)
        if (useFullRaw) {
            return openTiff(source)?.let { tiff ->
                demosaicTiff(tiff, targetWidth, targetHeight)
            }
        }
        return embeddedPreview(source, targetWidth, targetHeight, lowQuality)
            ?: openTiff(source)?.render(targetWidth, targetHeight, null)
    }

    fun orientationDegrees(bytes: ByteArray): Int {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull() ?: return 0
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
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

    // ---- Baseline TIFF ------------------------------------------------------------------------

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
    private const val TAG_COLOR_MAP = 0x0140
    private const val TAG_SUB_IFDS = 0x014A

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_DEFLATE_ADOBE = 32946
    private const val COMPRESSION_PACKBITS = 32773

    internal class Directory(val entries: Map<Int, LongArray>) {
        fun first(tag: Int, fallback: Long): Long = entries[tag]?.firstOrNull() ?: fallback
        fun values(tag: Int): LongArray? = entries[tag]
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

    /** An opened TIFF: cheap to hold onto, and renders any crop at any scale on demand. */
    class TiffImage internal constructor(
        private val file: TiffFile,
        private val directory: Directory,
        val width: Int,
        val height: Int,
    ) {
        /** [region] is in source pixels; null renders the whole frame. */
        fun render(targetWidth: Int, targetHeight: Int, region: Rect?): Bitmap? =
            renderDirectory(file, directory, targetWidth, targetHeight, region)
    }

    fun openTiff(source: ByteSource): TiffImage? {
        val file = tiffHeader(source) ?: return null
        val directories = mutableListOf<Directory>()
        readDirectories(file, file.u32(4).toInt(), directories, depth = 0)

        // RAW files also parse as TIFF, but their full-resolution IFD holds undemosaiced sensor
        // data; only directories this decoder understands are considered.
        val best = directories
            .filter { supportedDirectory(it) }
            .maxByOrNull { it.first(TAG_IMAGE_WIDTH, 0) * it.first(TAG_IMAGE_LENGTH, 0) }
            ?: return null
        return TiffImage(
            file = file,
            directory = best,
            width = best.first(TAG_IMAGE_WIDTH, 0).toInt(),
            height = best.first(TAG_IMAGE_LENGTH, 0).toInt(),
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
            val entries = HashMap<Int, LongArray>(count)
            for (i in 0 until count) {
                val entry = next + 2 + i * 12
                val tag = file.u16(entry)
                val values = readValues(file, entry) ?: continue
                entries[tag] = values
            }
            val directory = Directory(entries)
            into += directory
            if (depth < 3) {
                directory.values(TAG_SUB_IFDS)?.forEach { sub ->
                    readDirectories(file, sub.toInt(), into, depth + 1)
                }
            }
            next = file.u32(end).toInt()
        }
    }

    private fun readValues(file: TiffFile, entry: Int): LongArray? {
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
        val values = LongArray(count.toInt())
        for (i in values.indices) {
            val at = start + i * unit
            values[i] = when (unit) {
                1 -> file.u8(at).toLong()
                2 -> file.u16(at).toLong()
                else -> file.u32(at)
            }
        }
        return values
    }

    private fun supportedDirectory(directory: Directory): Boolean {
        val width = directory.first(TAG_IMAGE_WIDTH, 0)
        val height = directory.first(TAG_IMAGE_LENGTH, 0)
        if (width <= 0 || height <= 0) return false
        if (directory.values(TAG_STRIP_OFFSETS) == null) return false
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
        return when (directory.first(TAG_COMPRESSION, 1).toInt()) {
            COMPRESSION_NONE, COMPRESSION_LZW, COMPRESSION_PACKBITS,
            COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_ADOBE -> true
            else -> false
        }
    }

    private fun renderDirectory(
        file: TiffFile,
        directory: Directory,
        targetWidth: Int,
        targetHeight: Int,
        region: Rect?,
    ): Bitmap? {
        val width = directory.first(TAG_IMAGE_WIDTH, 0).toInt()
        val height = directory.first(TAG_IMAGE_LENGTH, 0).toInt()
        val bits = (directory.values(TAG_BITS_PER_SAMPLE) ?: longArrayOf(8)).first().toInt()
        val samples = directory.first(TAG_SAMPLES_PER_PIXEL, 1).toInt()
        val photometric = directory.first(TAG_PHOTOMETRIC, 1).toInt()
        val predictor = directory.first(TAG_PREDICTOR, 1).toInt()
        val compression = directory.first(TAG_COMPRESSION, 1).toInt()
        val rowsPerStrip = directory.first(TAG_ROWS_PER_STRIP, height.toLong())
            .coerceIn(1L, height.toLong()).toInt()
        val offsets = directory.values(TAG_STRIP_OFFSETS) ?: return null
        val counts = directory.values(TAG_STRIP_BYTE_COUNTS) ?: return null
        if (offsets.size != counts.size) return null
        val palette = if (photometric == 3) directory.values(TAG_COLOR_MAP) else null

        val bytesPerSample = bits / 8
        val bytesPerRow = width.toLong() * samples * bytesPerSample
        if (bytesPerRow <= 0 || bytesPerRow > Int.MAX_VALUE / 2) return null

        val crop = Rect(0, 0, width, height)
        if (region != null && !crop.setIntersect(region, Rect(0, 0, width, height))) return null
        val cropWidth = crop.width()
        val cropHeight = crop.height()
        if (cropWidth <= 0 || cropHeight <= 0) return null

        var step = 1
        while (
            (cropWidth / step).toLong() * (cropHeight / step).toLong() > MAX_OUTPUT_PIXELS ||
            cropWidth / step > MAX_OUTPUT_EDGE ||
            cropHeight / step > MAX_OUTPUT_EDGE ||
            (targetWidth > 0 && targetHeight > 0 && cropWidth / (step * 2) >= targetWidth && cropHeight / (step * 2) >= targetHeight)
        ) {
            step *= 2
            if (step > 4096) return null
        }

        val outWidth = (cropWidth + step - 1) / step
        val outHeight = (cropHeight + step - 1) / step
        if (outWidth <= 0 || outHeight <= 0) return null
        val pixels = IntArray(outWidth * outHeight)
        val row = ByteArray(bytesPerRow.toInt())
        val scratch = ByteArray(minOf(bytesPerRow, 128L * 1024L).toInt().coerceAtLeast(1))

        for (strip in offsets.indices) {
            val firstRow = strip * rowsPerStrip
            if (firstRow >= crop.bottom) break
            val rowsInStrip = minOf(rowsPerStrip, height - firstRow)
            if (rowsInStrip <= 0) break
            if (firstRow + rowsInStrip <= crop.top) continue

            val stream = stripStream(file.source, offsets[strip].toInt(), counts[strip].toInt(), compression)
                ?: continue
            stream.use { input ->
                for (index in 0 until rowsInStrip) {
                    val y = firstRow + index
                    if (y >= crop.bottom) break
                    if (y < crop.top || (y - crop.top) % step != 0) {
                        // Compressed rows still have to be consumed to stay in sync.
                        skipFully(input, bytesPerRow, scratch)
                        continue
                    }
                    if (!readFully(input, row)) return@use
                    if (predictor == 2) applyHorizontalPredictor(row, width, samples)
                    val outY = (y - crop.top) / step
                    if (outY >= outHeight) break
                    for (outX in 0 until outWidth) {
                        val x = crop.left + outX * step
                        val at = (x.toLong() * samples * bytesPerSample).toInt()
                        if (at + samples * bytesPerSample > row.size) break
                        pixels[outY * outWidth + outX] =
                            pixelAt(row, at, samples, bytesPerSample, photometric, file.littleEndian, palette)
                    }
                }
            }
        }

        return runCatching {
            Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
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
    private fun stripStream(source: ByteSource, offset: Int, length: Int, compression: Int): InputStream? {
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

    private fun pixelAt(
        raw: ByteArray,
        at: Int,
        samples: Int,
        bytesPerSample: Int,
        photometric: Int,
        littleEndian: Boolean,
        palette: LongArray?,
    ): Int {
        fun sample(index: Int): Int {
            val offset = at + index * bytesPerSample
            return if (bytesPerSample == 1) {
                raw[offset].toInt() and 0xFF
            } else {
                val low = raw[offset].toInt() and 0xFF
                val high = raw[offset + 1].toInt() and 0xFF
                if (littleEndian) high else low
            }
        }

        return when {
            palette != null -> {
                val index = sample(0)
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

    /**
     * Demosaics RAW sensor data using bilinear interpolation.
     * This converts Bayer pattern sensor data to full RGB, allowing photographers to view
     * the full sensor data instead of just the embedded JPEG preview.
     * Note: This is computationally expensive and may be slow on older devices.
     */
    private fun demosaicTiff(tiff: TiffImage, targetWidth: Int, targetHeight: Int): Bitmap? {
        val directory = tiff.directory
        val width = directory.first(TAG_IMAGE_WIDTH, 0).toInt()
        val height = directory.first(TAG_IMAGE_LENGTH, 0).toInt()
        val bits = (directory.values(TAG_BITS_PER_SAMPLE) ?: longArrayOf(16)).first().toInt()
        val samples = directory.first(TAG_SAMPLES_PER_PIXEL, 1).toInt()
        val photometric = directory.first(TAG_PHOTOMETRIC, 1).toInt()
        val predictor = directory.first(TAG_PREDICTOR, 1).toInt()
        val compression = directory.first(TAG_COMPRESSION, 1).toInt()
        val rowsPerStrip = directory.first(TAG_ROWS_PER_STRIP, height.toLong())
            .coerceIn(1L, height.toLong()).toInt()
        val offsets = directory.values(TAG_STRIP_OFFSETS) ?: return null
        val counts = directory.values(TAG_STRIP_BYTE_COUNTS) ?: return null
        if (offsets.size != counts.size) return null

        // Only support 16-bit single-channel RAW data (Bayer pattern)
        if (bits != 16 || samples != 1 || photometric != 1) return null

        val bytesPerSample = 2
        val bytesPerRow = width.toLong() * samples * bytesPerSample
        if (bytesPerRow <= 0 || bytesPerRow > Int.MAX_VALUE / 2) return null

        // Apply downsampling if target dimensions are specified
        var step = 1
        while (
            (width / step).toLong() * (height / step).toLong() > MAX_OUTPUT_PIXELS ||
            width / step > MAX_OUTPUT_EDGE ||
            height / step > MAX_OUTPUT_EDGE ||
            (targetWidth > 0 && targetHeight > 0 && width / (step * 2) >= targetWidth && height / (step * 2) >= targetHeight)
        ) {
            step *= 2
            if (step > 4096) return null
        }

        val outWidth = (width + step - 1) / step
        val outHeight = (height + step - 1) / step
        if (outWidth <= 0 || outHeight <= 0) return null

        // Read all RAW sensor data into memory (required for demosaicing)
        val rawData = ByteArray(width * height * bytesPerSample)
        val row = ByteArray(bytesPerRow.toInt())
        val scratch = ByteArray(minOf(bytesPerRow, 128L * 1024L).toInt().coerceAtLeast(1))

        var rawDataOffset = 0
        for (strip in offsets.indices) {
            val firstRow = strip * rowsPerStrip
            if (firstRow >= height) break
            val rowsInStrip = minOf(rowsPerStrip, height - firstRow)
            if (rowsInStrip <= 0) break

            val stream = stripStream(tiff.file.source, offsets[strip].toInt(), counts[strip].toInt(), compression)
                ?: continue
            stream.use { input ->
                for (index in 0 until rowsInStrip) {
                    if (!readFully(input, row)) break
                    if (predictor == 2) applyHorizontalPredictor(row, width, samples)
                    val copyLen = minOf(row.size, rawData.size - rawDataOffset)
                    System.arraycopy(row, 0, rawData, rawDataOffset, copyLen)
                    rawDataOffset += copyLen
                }
            }
        }

        // Demosaic using bilinear interpolation
        val pixels = IntArray(outWidth * outHeight)
        val littleEndian = tiff.file.littleEndian

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val srcX = x * step
                val srcY = y * step
                val pixel = demosaicPixel(rawData, srcX, srcY, width, height, littleEndian)
                pixels[y * outWidth + x] = pixel
            }
        }

        return runCatching {
            Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /**
     * Demosaics a single pixel using bilinear interpolation.
     * Assumes RGGB Bayer pattern (common for most cameras).
     */
    private fun demosaicPixel(
        rawData: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        littleEndian: Boolean,
    ): Int {
        fun getSample(sx: Int, sy: Int): Int {
            if (sx < 0 || sx >= width || sy < 0 || sy >= height) return 0
            val offset = (sy * width + sx) * 2
            val low = rawData[offset].toInt() and 0xFF
            val high = rawData[offset + 1].toInt() and 0xFF
            return if (littleEndian) (high shl 8) or low else (low shl 8) or high
        }

        // Determine which color this pixel is in the Bayer pattern (RGGB)
        val isRed = (x % 2 == 0) && (y % 2 == 0)
        val isGreen = ((x % 2) != (y % 2))
        val isBlue = (x % 2 != 0) && (y % 2 != 0)

        var red: Int
        var green: Int
        var blue: Int

        if (isRed) {
            red = getSample(x, y)
            // Green: average of 4 neighboring green pixels
            green = (getSample(x - 1, y) + getSample(x + 1, y) +
                    getSample(x, y - 1) + getSample(x, y + 1)) / 4
            // Blue: average of 4 diagonal blue pixels
            blue = (getSample(x - 1, y - 1) + getSample(x + 1, y - 1) +
                    getSample(x - 1, y + 1) + getSample(x + 1, y + 1)) / 4
        } else if (isGreen) {
            red = if (x % 2 == 0) {
                // Green at even x, odd y - average of left/right red
                (getSample(x - 1, y) + getSample(x + 1, y)) / 2
            } else {
                // Green at odd x, even y - average of top/bottom red
                (getSample(x, y - 1) + getSample(x, y + 1)) / 2
            }
            green = getSample(x, y)
            blue = if (x % 2 == 0) {
                // Green at even x, odd y - average of top/bottom blue
                (getSample(x, y - 1) + getSample(x, y + 1)) / 2
            } else {
                // Green at odd x, even y - average of left/right blue
                (getSample(x - 1, y) + getSample(x + 1, y)) / 2
            }
        } else { // isBlue
            // Red: average of 4 diagonal red pixels
            red = (getSample(x - 1, y - 1) + getSample(x + 1, y - 1) +
                    getSample(x - 1, y + 1) + getSample(x + 1, y + 1)) / 4
            // Green: average of 4 neighboring green pixels
            green = (getSample(x - 1, y) + getSample(x + 1, y) +
                    getSample(x, y - 1) + getSample(x, y + 1)) / 4
            blue = getSample(x, y)
        }

        // Scale 16-bit to 8-bit and pack into ARGB
        val r8 = (red shr 8).coerceIn(0, 255)
        val g8 = (green shr 8).coerceIn(0, 255)
        val b8 = (blue shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
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
