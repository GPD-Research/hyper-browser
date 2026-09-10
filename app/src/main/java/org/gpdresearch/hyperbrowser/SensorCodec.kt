package org.gpdresearch.hyperbrowser

/**
 * Receives decoded photosite rows. [samples] holds [count] values for the columns starting at
 * [xStart] of sensor row [y]; the array is reused between calls, so a sink must not keep it.
 */
internal fun interface SensorRowSink {
    fun row(y: Int, xStart: Int, samples: IntArray, count: Int)
}

/**
 * Reads the undemosaiced photosites of one RAW image. Rows are pushed rather than pulled because
 * the compressed formats decide the order themselves: Canon writes its sensor in vertical slices,
 * so one pass of the entropy stream produces rows all over the frame.
 *
 * [scan] is expected to be called once per render and may be called again on a fresh instance.
 */
internal interface SensorCodec {
    /** What the inspector calls this data, e.g. "Sony compressed RAW". */
    val label: String

    /**
     * Decodes the image, handing every row [wanted] accepts to [sink]. Formats whose rows are
     * independent skip the rest outright; entropy coded formats have to decode them anyway and
     * only skip the accumulation.
     */
    fun scan(wanted: (Int) -> Boolean, sink: SensorRowSink)
}

/**
 * Sensor data stored the way baseline TIFF stores pixels: whole rows, optionally compressed with
 * a stream codec, with samples packed at 8, 12, 14 or 16 bits.
 */
internal class PackedSensorCodec(
    private val source: ByteSource,
    private val blocks: RawImage.Blocks,
    private val width: Int,
    private val height: Int,
    /** Width of the container each sample sits in, which is not always its bit depth: an
     * "uncompressed 14-bit" ARW writes every sample in a 16 bit word. */
    private val bits: Int,
    private val compression: Int,
    private val littleEndian: Boolean,
    private val predictor: Int,
    override val label: String,
) : SensorCodec {

    override fun scan(wanted: (Int) -> Boolean, sink: SensorRowSink) {
        val rowBytes = ((blocks.width.toLong() * bits + 7) / 8)
        if (rowBytes <= 0 || rowBytes > Int.MAX_VALUE / 2) return
        val bytes = ByteArray(rowBytes.toInt())
        val samples = IntArray(blocks.width)
        val scratch = ByteArray(minOf(rowBytes, 128L * 1024L).toInt().coerceAtLeast(1))

        for (blockY in 0 until blocks.down) {
            val top = blockY * blocks.height
            if (top >= height) break
            val rows = if (blocks.padded) blocks.height else minOf(blocks.height, height - top)
            // Decompressing a block whose rows are all skipped costs as much as one that is drawn.
            if ((0 until rows).none { wanted(top + it) }) continue
            for (blockX in 0 until blocks.across) {
                val left = blockX * blocks.width
                if (left >= width) break
                val index = blockY * blocks.across + blockX
                if (index >= blocks.offsets.size || index >= blocks.counts.size) return
                val stream = RawImage.blockStream(
                    source,
                    blocks.offsets[index].toInt(),
                    blocks.counts[index].toInt(),
                    compression,
                ) ?: continue
                val count = minOf(blocks.width, width - left)
                stream.use { input ->
                    for (offsetY in 0 until rows) {
                        val y = top + offsetY
                        if (!wanted(y)) {
                            RawImage.skipFully(input, rowBytes, scratch)
                            continue
                        }
                        if (!RawImage.readFully(input, bytes)) return@use
                        if (predictor == 2 && bits == 8) {
                            RawImage.applyHorizontalPredictor(bytes, blocks.width, 1)
                        }
                        for (i in 0 until count) {
                            samples[i] = RawImage.sampleValue(bytes, i, bits, littleEndian)
                        }
                        sink.row(y, left, samples, count)
                    }
                }
            }
        }
    }
}

/**
 * Sony's lossy compressed RAW (the ARW2 layout every recent body writes by default). Each row is
 * a fixed number of bytes, so a crop only decodes the rows it needs.
 *
 * Sixteen photosites of one colour share 16 bytes: the brightest and darkest of the group are
 * kept at 11 bits and the other fourteen at 7 bits, scaled to the group's range. [curve] is the
 * camera's tone curve, which puts the 11-bit values back on the sensor's own scale.
 */
internal class Arw2SensorCodec(
    private val source: ByteSource,
    private val offset: Int,
    private val width: Int,
    private val height: Int,
    private val curve: IntArray?,
) : SensorCodec {

    override val label: String get() = "Sony compressed RAW"

    override fun scan(wanted: (Int) -> Boolean, sink: SensorRowSink) {
        val samples = IntArray(width)
        val pix = IntArray(16)
        for (y in 0 until height) {
            if (!wanted(y)) continue
            val data = source.copyRange(offset + y * width, width) ?: return
            decodeRow(data, samples, pix)
            sink.row(y, 0, samples, width)
        }
    }

    private fun decodeRow(data: ByteArray, out: IntArray, pix: IntArray) {
        var column = 0
        var at = 0
        while (column < width - 30 && at + 16 <= data.size) {
            val header = le32(data, at)
            val max = header and 0x7FF
            val min = (header ushr 11) and 0x7FF
            val indexOfMax = (header ushr 22) and 0x0F
            val indexOfMin = (header ushr 26) and 0x0F
            var shift = 0
            while (shift < 4 && (0x80 shl shift) <= max - min) shift += 1
            var bit = 30
            for (i in 0 until 16) {
                pix[i] = when (i) {
                    indexOfMax -> max
                    indexOfMin -> min
                    else -> {
                        val value = (((le16(data, at + (bit shr 3)) shr (bit and 7)) and 0x7F) shl shift) + min
                        bit += 7
                        if (value > 0x7FF) 0x7FF else value
                    }
                }
            }
            for (i in 0 until 16) {
                if (column < out.size) out[column] = tone(pix[i])
                column += 2
            }
            // Each group covers every other column, so the second group of a 32 pixel span
            // fills the columns the first one stepped over.
            column -= if (column and 1 != 0) 1 else 31
            at += 16
        }
    }

    private fun tone(value: Int): Int {
        val curve = curve ?: return value shl 3
        val index = (value shl 1).coerceIn(0, curve.size - 1)
        return curve[index]
    }

    private fun le16(data: ByteArray, at: Int): Int {
        if (at < 0 || at + 1 >= data.size) return 0
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(data: ByteArray, at: Int): Int {
        if (at < 0 || at + 3 >= data.size) return 0
        return (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Lossless JPEG (SOF3), which is how Canon writes CR2, Nikon writes some NEFs and Adobe writes
 * lossless DNG. Nothing about it resembles ordinary JPEG: there is no transform and no
 * quantisation, only Huffman coded differences from the sample to the left.
 *
 * The entropy stream has to be decoded from the start whatever crop is wanted, so a skipped row
 * saves the accumulation but not the decode.
 */
internal class LosslessJpegSensorCodec private constructor(
    private val source: ByteSource,
    private val dataOffset: Int,
    private val dataLength: Int,
    private val frame: JpegFrame,
    private val width: Int,
    private val height: Int,
    /** Canon splits the sensor into vertical slices: {count, width, width of the last one}. */
    private val slices: IntArray?,
    override val label: String,
) : SensorCodec {

    val bits: Int get() = frame.precision

    companion object {
        /** Reads the frame header, or returns null when this is not a lossless JPEG. */
        fun open(
            source: ByteSource,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            slices: IntArray?,
            label: String,
        ): LosslessJpegSensorCodec? {
            if (offset < 0 || length <= 4 || offset.toLong() + length > source.size) return null
            val frame = JpegFrame.parse(source, offset, length) ?: return null
            if (frame.components !in 1..4 || frame.predictor != 1) return null
            if (frame.width <= 0 || frame.height <= 0) return null
            return LosslessJpegSensorCodec(
                source, offset, length, frame, width, height, slices, label,
            )
        }
    }

    override fun scan(wanted: (Int) -> Boolean, sink: SensorRowSink) {
        val stream = source.stream(dataOffset + frame.scanOffset, dataLength - frame.scanOffset) ?: return
        val reader = JpegBitReader(stream)
        val components = frame.components
        val across = frame.width * components
        val row = IntArray(across)
        val emit = IntArray(maxOf(across, width))
        val vertical = IntArray(components) { 1 shl (frame.precision - 1) }
        var restart = 0
        stream.use {
            for (jrow in 0 until frame.height) {
                if (frame.restartInterval > 0 && restart == frame.restartInterval) {
                    reader.restart()
                    for (c in 0 until components) vertical[c] = 1 shl (frame.precision - 1)
                    restart = 0
                }
                restart += 1
                for (column in 0 until frame.width) {
                    for (c in 0 until components) {
                        val table = frame.tables[frame.tableFor[c]] ?: return
                        val diff = table.diff(reader)
                        val predicted = if (column > 0) {
                            row[(column - 1) * components + c]
                        } else {
                            vertical[c] += diff
                            vertical[c] - diff
                        }
                        row[column * components + c] = predicted + diff
                    }
                }
                emitRow(jrow, row, across, emit, wanted, sink)
            }
        }
    }

    /**
     * Places one JPEG row on the sensor. Without slices the two are the same row; with them a
     * JPEG row is a horizontal run inside one slice, and the run has to be cut where it crosses
     * into the next sensor row.
     */
    private fun emitRow(
        jrow: Int,
        row: IntArray,
        across: Int,
        emit: IntArray,
        wanted: (Int) -> Boolean,
        sink: SensorRowSink,
    ) {
        val slices = slices
        if (slices == null || slices.size < 3 || slices[1] <= 0) {
            if (jrow >= height || !wanted(jrow)) return
            val count = minOf(across, width)
            System.arraycopy(row, 0, emit, 0, count)
            sink.row(jrow, 0, emit, count)
            return
        }
        // A slice is a full height column of the sensor, however many JPEG rows its samples
        // arrive in: the 5D Mark IV codes four components per row and so half as many rows.
        val perSlice = slices[1].toLong() * height
        if (perSlice <= 0) return
        var at = 0
        while (at < across) {
            val index = jrow.toLong() * across + at
            var slice = (index / perSlice).toInt()
            val last = if (slice >= slices[0]) 1 else 0
            if (last == 1) slice = slices[0]
            val within = index - slice.toLong() * perSlice
            val sliceWidth = slices[1 + last]
            if (sliceWidth <= 0) return
            val y = (within / sliceWidth).toInt()
            val x = (within % sliceWidth).toInt() + slice * slices[1]
            val run = minOf((across - at).toLong(), sliceWidth - within % sliceWidth).toInt()
            if (y < height && wanted(y) && x < width) {
                val count = minOf(run, width - x)
                System.arraycopy(row, at, emit, 0, count)
                sink.row(y, x, emit, count)
            }
            at += run
        }
    }
}

/** The parts of a lossless JPEG header this decoder needs. */
internal class JpegFrame(
    val precision: Int,
    val width: Int,
    val height: Int,
    val components: Int,
    val predictor: Int,
    val restartInterval: Int,
    val tables: Array<JpegHuffmanTable?>,
    val tableFor: IntArray,
    /** Where the entropy coded data starts, relative to the start of the JPEG. */
    val scanOffset: Int,
) {
    companion object {
        fun parse(source: ByteSource, offset: Int, length: Int): JpegFrame? {
            if (source.u8(offset) != 0xFF || source.u8(offset + 1) != 0xD8) return null
            var at = offset + 2
            val end = offset + length
            var precision = 0
            var width = 0
            var height = 0
            var components = 0
            var restartInterval = 0
            val tables = arrayOfNulls<JpegHuffmanTable>(4)
            var tableFor = IntArray(0)
            while (at + 4 <= end) {
                if (source.u8(at) != 0xFF) return null
                val marker = source.u8(at + 1)
                if (marker == 0xD8 || (marker in 0xD0..0xD9)) {
                    at += 2
                    continue
                }
                val size = (source.u8(at + 2) shl 8) or source.u8(at + 3)
                if (size < 2 || at + 2 + size > end) return null
                val body = at + 4
                when (marker) {
                    0xC3 -> { // SOF3: lossless, Huffman coded
                        precision = source.u8(body)
                        height = (source.u8(body + 1) shl 8) or source.u8(body + 2)
                        width = (source.u8(body + 3) shl 8) or source.u8(body + 4)
                        components = source.u8(body + 5)
                        if (precision !in 8..16 || components !in 1..4) return null
                        // Subsampling would mean fewer samples than photosites.
                        for (c in 0 until components) {
                            if (source.u8(body + 6 + c * 3 + 1) != 0x11) return null
                        }
                    }
                    0xC4 -> { // DHT
                        var read = body
                        while (read < at + 2 + size) {
                            val id = source.u8(read)
                            if (id shr 4 != 0 || (id and 15) > 3) return null
                            val table = JpegHuffmanTable.parse(source, read + 1) ?: return null
                            tables[id and 15] = table
                            read += 1 + 16 + table.symbols.size
                        }
                    }
                    0xDD -> restartInterval = (source.u8(body) shl 8) or source.u8(body + 1)
                    0xDA -> { // SOS
                        val scanComponents = source.u8(body)
                        if (scanComponents != components) return null
                        tableFor = IntArray(components) { source.u8(body + 1 + it * 2 + 1) shr 4 }
                        val predictor = source.u8(body + 1 + components * 2)
                        return JpegFrame(
                            precision = precision,
                            width = width,
                            height = height,
                            components = components,
                            predictor = predictor,
                            restartInterval = restartInterval,
                            tables = tables,
                            tableFor = tableFor,
                            scanOffset = at + 2 + size - offset,
                        )
                    }
                    0xD9 -> return null
                }
                at += 2 + size
            }
            return null
        }
    }
}

/** A canonical Huffman table; in lossless JPEG a symbol is the bit length of the difference. */
internal class JpegHuffmanTable(private val counts: IntArray, val symbols: IntArray) {
    private val minCode = IntArray(17)
    private val maxCode = IntArray(17)
    private val firstSymbol = IntArray(17)

    init {
        var code = 0
        var index = 0
        for (bits in 1..16) {
            firstSymbol[bits] = index
            minCode[bits] = code
            code += counts[bits - 1]
            index += counts[bits - 1]
            maxCode[bits] = if (counts[bits - 1] == 0) -1 else code - 1
            code = code shl 1
        }
    }

    companion object {
        fun parse(source: ByteSource, at: Int): JpegHuffmanTable? {
            val counts = IntArray(16) { source.u8(at + it) }
            val total = counts.sum()
            if (total <= 0 || total > 256) return null
            val symbols = IntArray(total) { source.u8(at + 16 + it) }
            return JpegHuffmanTable(counts, symbols)
        }
    }

    private fun symbol(reader: JpegBitReader): Int {
        var code = 0
        for (bits in 1..16) {
            code = (code shl 1) or reader.bit()
            if (maxCode[bits] >= 0 && code <= maxCode[bits]) {
                val index = firstSymbol[bits] + code - minCode[bits]
                if (index < 0 || index >= symbols.size) return 0
                return symbols[index]
            }
        }
        return 0
    }

    /** The signed difference from the predicted sample, as lossless JPEG codes it. */
    fun diff(reader: JpegBitReader): Int {
        val length = symbol(reader)
        if (length == 0) return 0
        // 16 means the full range: the difference is the largest negative value.
        if (length == 16) return -32768
        val raw = reader.bits(length)
        return if (raw < (1 shl (length - 1))) raw - (1 shl length) + 1 else raw
    }
}

/** MSB-first bit reader over JPEG entropy coded data, unstuffing the 0xFF00 escapes. */
internal class JpegBitReader(private val stream: java.io.InputStream) {
    private var buffer = 0
    private var count = 0
    private var ended = false

    fun restart() {
        count = 0
        // The marker itself is two bytes and always byte aligned.
        stream.read()
        stream.read()
    }

    fun bit(): Int {
        if (count == 0) {
            var next = stream.read()
            if (next < 0) {
                ended = true
                next = 0
            } else if (next == 0xFF) {
                val following = stream.read()
                if (following != 0) {
                    // A marker: the scan is over, keep feeding zeroes rather than reading past it.
                    ended = true
                    next = 0
                }
            }
            buffer = next
            count = 8
        }
        count -= 1
        return (buffer shr count) and 1
    }

    fun bits(length: Int): Int {
        var value = 0
        for (i in 0 until length) value = (value shl 1) or bit()
        return value
    }
}

/**
 * Nikon's compressed NEF, used for both its lossy and lossless settings. Differences from the
 * previous photosite of the same colour are Huffman coded with one of six fixed trees, and the
 * result is an index into the camera's linearisation curve.
 */
internal class NikonSensorCodec(
    private val source: ByteSource,
    private val offset: Int,
    private val length: Int,
    private val width: Int,
    private val height: Int,
    private val bits: Int,
    private val curve: IntArray,
    private val vertical: IntArray,
    private val tree: Int,
    private val split: Int,
) : SensorCodec {

    override val label: String get() = "Nikon compressed RAW"

    override fun scan(wanted: (Int) -> Boolean, sink: SensorRowSink) {
        val stream = source.stream(offset, length) ?: return
        val reader = NikonBitReader(stream)
        var table = NikonTrees.decoder(tree)
        val samples = IntArray(width)
        val predicted = IntArray(2)
        val running = vertical.copyOf(4)
        stream.use {
            for (y in 0 until height) {
                if (split > 0 && y == split) table = NikonTrees.decoder(tree + 1)
                for (x in 0 until width) {
                    val coded = table.symbol(reader)
                    val codeLength = coded and 15
                    val shift = coded shr 4
                    var diff = ((reader.bits(codeLength - shift) shl 1) + 1) shl shift shr 1
                    if (codeLength > 0 && (diff and (1 shl (codeLength - 1))) == 0) {
                        diff -= (1 shl codeLength) - (if (shift == 0) 1 else 0)
                    }
                    if (x < 2) {
                        running[(y and 1) * 2 + x] += diff
                        predicted[x] = running[(y and 1) * 2 + x]
                    } else {
                        predicted[x and 1] += diff
                    }
                    samples[x] = curve[predicted[x and 1].coerceIn(0, curve.size - 1)]
                }
                if (wanted(y)) sink.row(y, 0, samples, width)
            }
        }
    }
}

/** MSB-first bit reader over Nikon's entropy coded data. */
internal class NikonBitReader(private val stream: java.io.InputStream) {
    private var buffer = 0L
    private var count = 0

    fun bits(length: Int): Int {
        if (length <= 0) return 0
        while (count < length) {
            val next = stream.read()
            buffer = (buffer shl 8) or (if (next < 0) 0L else next.toLong())
            count += 8
        }
        count -= length
        return ((buffer shr count) and ((1L shl length) - 1L)).toInt()
    }

    fun bit(): Int = bits(1)
}

/**
 * The six Huffman trees Nikon's firmware uses, in the {counts, symbols} form the format defines:
 * a lossy and a lossless tree for 12-bit data and the same for 14, plus the alternate trees the
 * lossy modes switch to partway down the frame.
 */
internal object NikonTrees {
    private val trees = arrayOf(
        intArrayOf(
            0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0,
            5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12,
        ),
        intArrayOf(
            0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0,
            0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12,
        ),
        intArrayOf(
            0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12,
        ),
        intArrayOf(
            0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0,
            5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14,
        ),
        intArrayOf(
            0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0,
            8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14,
        ),
        intArrayOf(
            0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
            7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14,
        ),
    )

    fun decoder(index: Int): NikonHuffmanTable =
        NikonHuffmanTable(trees[index.coerceIn(0, trees.size - 1)])
}

/** Canonical Huffman decoding over a Nikon tree. */
internal class NikonHuffmanTable(tree: IntArray) {
    private val minCode = IntArray(17)
    private val maxCode = IntArray(17)
    private val firstSymbol = IntArray(17)
    private val symbols = IntArray(tree.size - 16) { tree[16 + it] }

    init {
        var code = 0
        var index = 0
        for (bits in 1..16) {
            val count = if (bits <= 16) tree[bits - 1] else 0
            firstSymbol[bits] = index
            minCode[bits] = code
            code += count
            index += count
            maxCode[bits] = if (count == 0) -1 else code - 1
            code = code shl 1
        }
    }

    fun symbol(reader: NikonBitReader): Int {
        var code = 0
        for (bits in 1..16) {
            code = (code shl 1) or reader.bit()
            if (maxCode[bits] >= 0 && code <= maxCode[bits]) {
                val index = firstSymbol[bits] + code - minCode[bits]
                if (index < 0 || index >= symbols.size) return 0
                return symbols[index]
            }
        }
        return 0
    }
}
