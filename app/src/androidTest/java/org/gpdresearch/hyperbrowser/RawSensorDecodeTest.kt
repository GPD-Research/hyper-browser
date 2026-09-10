package org.gpdresearch.hyperbrowser

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the sensor decoders against real camera files, which is the only way to know a
 * maker's compression is being read rather than its embedded preview. The fixtures are tens of
 * megabytes each, so they are pushed by hand rather than committed:
 *
 *   adb push _GPD0125.ARW /sdcard/Android/data/org.gpdresearch.hyperbrowser/files/
 *
 * A test whose fixture is absent is skipped.
 */
@RunWith(AndroidJUnit4::class)
class RawSensorDecodeTest {

    private fun dir(): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    private fun open(name: String): ByteSource {
        val file = File(dir(), name)
        assumeTrue("push $name to ${dir()} to run this", file.exists())
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return RawImage.mappedSource(descriptor.fileDescriptor)!!
    }

    /**
     * The decoder has to reach the photosites, report the visible frame rather than the whole
     * sensor, and produce an image with real tonal range: the failure that hides behind a
     * plausible-looking bitmap is sample packing read wrongly, which shows up as a flat frame.
     */
    private fun exercise(name: String, width: Int, height: Int, label: String) {
        val source = open(name)
        val sensor = RawImage.openSensor(source)
        assertNotNull("openSensor failed for $name", sensor)
        sensor!!
        assertEquals("$name width", width, sensor.width)
        assertEquals("$name height", height, sensor.height)
        assertEquals("$name source", label, sensor.source)

        val overview = sensor.render(1080, 2000, null)
        assertNotNull("overview failed for $name", overview)
        overview!!
        assertTrue("$name overview too small", overview.width in 540..2160)
        assertTrue("$name overview is flat", spread(overview) > 24)

        val crop = sensor.render(1080, 2000, Rect(width / 2 - 256, height / 2 - 256, width / 2 + 256, height / 2 + 256))
        assertNotNull("crop failed for $name", crop)
        crop!!
        // A filter cell becomes one pixel, so a 512 photosite crop is 256 pixels across.
        assertEquals("$name crop width", 256, crop.width)
    }

    /** Range of green between the darkest and brightest pixel sampled across the frame. */
    private fun spread(bitmap: android.graphics.Bitmap): Int {
        var low = 255
        var high = 0
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                val green = (bitmap.getPixel(x, y) shr 8) and 0xFF
                if (green < low) low = green
                if (green > high) high = green
            }
        }
        return high - low
    }

    @Test
    fun decodesUncompressedSonyArw() =
        exercise("_GPD0125.ARW", 5168, 3448, "uncompressed sensor data")

    @Test
    fun decodesCompressedSonyArw() =
        exercise("sony_14c.arw", 7952, 5304, "Sony compressed RAW")

    @Test
    fun decodesLosslessJpegCanonCr2() =
        exercise("canon_5d4.cr2", 6720, 4480, "lossless JPEG RAW")

    @Test
    fun decodesCompressedNikonNef() =
        exercise("nikon_d850_c.nef", 8288, 5520, "Nikon compressed RAW")

    /**
     * A format whose sensor data this app cannot read must not be presented as sensor data;
     * the inspector says it is showing the embedded preview instead. Canon's CR3 is an
     * ISO-BMFF file rather than a TIFF, so a CR3 header stands in for the whole class.
     */
    @Test
    fun refusesUnsupportedSensorData() {
        val header = byteArrayOf(
            0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'c'.code.toByte(), 'r'.code.toByte(), 'x'.code.toByte(), ' '.code.toByte(),
        ) + ByteArray(1024)
        assertNull(
            "a file this decoder cannot read must not be labelled sensor data",
            RawImage.openSensor(RawImage.arraySource(header)),
        )
    }
}
