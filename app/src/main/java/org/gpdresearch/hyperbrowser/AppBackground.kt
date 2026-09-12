package org.gpdresearch.hyperbrowser

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/** Where a chosen image can be shown. */
enum class WallpaperTarget(val label: String) {
    APP("File browser background"),
    HOME("Home screen wallpaper"),
    LOCK("Lock screen wallpaper"),
    HOME_AND_LOCK("Home and lock screen"),
    EVERYWHERE("File browser, home and lock screen"),
    ;

    val app: Boolean get() = this == APP || this == EVERYWHERE
    val home: Boolean get() = this == HOME || this == HOME_AND_LOCK || this == EVERYWHERE
    val lock: Boolean get() = this == LOCK || this == HOME_AND_LOCK || this == EVERYWHERE
}

/**
 * The image drawn behind the file tree. It is copied into app storage rather than remembered by
 * URI: the original may live on a card that is unmounted, in Drive, or behind a folder grant that
 * is revoked, and a background that disappears is worse than none.
 */
object AppBackground {
    private const val FILE_NAME = "app_background.jpg"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun store(context: Context, bitmap: Bitmap): Boolean = runCatching {
        FileOutputStream(file(context)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
    }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * Decoded no larger than the screen it is drawn on: the stored image is wallpaper-sized, and
     * holding it at full resolution takes heap away from the previews drawn over it.
     */
    fun load(context: Context, maxWidth: Int = 0, maxHeight: Int = 0): Bitmap? {
        val stored = file(context)
        if (!stored.exists()) return null
        return runCatching {
            val options = BitmapFactory.Options()
            if (maxWidth > 0 && maxHeight > 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(stored.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) {
                    sample *= 2
                }
                options.inSampleSize = sample
            }
            BitmapFactory.decodeFile(stored.path, options)
        }.getOrNull()
    }
}

/** Sets the system wallpaper, home and lock screen being separately addressable since Android 7. */
object Wallpapers {
    /** The size the launcher wants, so the image is not stored smaller than it will be drawn. */
    fun desiredSize(context: Context): Pair<Int, Int> {
        val manager = WallpaperManager.getInstance(context)
        val width = manager.desiredMinimumWidth.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = manager.desiredMinimumHeight.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return width to height
    }

    fun apply(context: Context, bitmap: Bitmap, home: Boolean, lock: Boolean): Boolean = runCatching {
        val manager = WallpaperManager.getInstance(context)
        val (width, height) = desiredSize(context)
        val framed = cropToFill(bitmap, width, height)
        if (home) manager.setBitmap(framed, null, true, WallpaperManager.FLAG_SYSTEM)
        if (lock) manager.setBitmap(framed, null, true, WallpaperManager.FLAG_LOCK)
        if (framed !== bitmap) framed.recycle()
        true
    }.getOrDefault(false)

    /**
     * Scales the image by one factor on both axes until it covers the wallpaper and keeps the
     * middle of it, so a portrait screen takes the full height of a landscape photo and throws away
     * the sides rather than squeezing them in. The wallpaper service would otherwise be free to fit
     * the whole image into the frame, which distorts it or leaves bars.
     */
    fun cropToFill(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (width <= 0 || height <= 0) return bitmap
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val visibleWidth = (width / scale).coerceAtMost(bitmap.width.toFloat())
        val visibleHeight = (height / scale).coerceAtMost(bitmap.height.toFloat())
        val left = ((bitmap.width - visibleWidth) / 2f).toInt()
        val top = ((bitmap.height - visibleHeight) / 2f).toInt()
        val source = Rect(left, top, left + visibleWidth.toInt(), top + visibleHeight.toInt())
        // A hardware bitmap has no pixels to copy into, so the crop is drawn into a software one.
        val config = bitmap.config?.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
        val output = Bitmap.createBitmap(width, height, config)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(output).drawBitmap(bitmap, source, Rect(0, 0, width, height), paint)
        return output
    }
}
