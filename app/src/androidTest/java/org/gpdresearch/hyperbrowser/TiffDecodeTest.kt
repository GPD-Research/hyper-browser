package org.gpdresearch.hyperbrowser

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the TIFF decoder against real gigapixel scientific TIFFs, which is the only way to
 * see that an overview and a 1:1 crop stay within a sane time and memory budget. Push the
 * fixtures first:
 *
 *   adb push heic0707a.tif /sdcard/Android/data/org.gpdresearch.hyperbrowser/files/
 *
 * from https://esahubble.org/media/archives/images/original/heic0707a.tif (and the 10k version
 * under publicationtiff10k/).
 */
@RunWith(AndroidJUnit4::class)
class TiffDecodeTest {

    private fun open(path: String): ByteSource {
        val file = File(dir(), path)
        // The fixtures are hundreds of megabytes, so they are pushed to the device by hand
        // rather than committed; without them there is nothing to measure.
        assumeTrue("push $path to ${dir()} to run this", file.exists())
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return RawImage.mappedSource(descriptor.fileDescriptor)!!
    }

    private fun dir(): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    private fun exercise(path: String) {
        val source = open(path)
        val started = System.currentTimeMillis()
        val tiff = RawImage.openTiff(source)
        assertNotNull("openTiff failed for $path", tiff)
        tiff!!
        android.util.Log.i("TiffDecodeTest", "$path dims=${tiff.width}x${tiff.height}")

        val overview = tiff.render(1080, 2000, null)
        assertNotNull("overview failed for $path", overview)
        overview!!
        val overviewMs = System.currentTimeMillis() - started
        android.util.Log.i(
            "TiffDecodeTest",
            "$path overview=${overview.width}x${overview.height} in ${overviewMs}ms " +
                "px=${Integer.toHexString(overview.getPixel(overview.width / 2, overview.height / 2))}",
        )
        assertTrue(overview.width in 1080..2160)

        val cropStarted = System.currentTimeMillis()
        val cx = tiff.width / 2
        val cy = tiff.height / 2
        val crop = tiff.render(1080, 2000, Rect(cx - 540, cy - 1000, cx + 540, cy + 1000))
        assertNotNull("crop failed for $path", crop)
        crop!!
        android.util.Log.i(
            "TiffDecodeTest",
            "$path crop=${crop.width}x${crop.height} in ${System.currentTimeMillis() - cropStarted}ms",
        )
        assertTrue(crop.width > 0 && crop.height > 0)
    }

    @Test
    fun decodesSmallHubbleTiff() = exercise("heic0707a_10k.tif")

    @Test
    fun decodesOriginalHubbleTiff() = exercise("heic0707a.tif")
}
