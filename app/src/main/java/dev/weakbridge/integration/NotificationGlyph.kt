package dev.weakbridge.integration

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * Status-bar icons for mirrored notifications.
 *
 * The system treats a small icon as an alpha mask and paints it in one colour, so the bitmap is
 * built in memory and handed over as [IconCompat]. An earlier version pre-registered XML drawables
 * and looked them up by name; a `<bitmap>` resource pointing at a vector cannot be decoded, so
 * every slot was null and every card fell back to the launcher mark.
 *
 * A real app icon is turned into a silhouette first. Tinting a full-colour icon without that step
 * paints a solid block, because the icon's alpha channel covers the whole square.
 */
class NotificationGlyph {
    /** Initials badge, used until the real icon is known. */
    fun initials(label: String, category: Int): IconCompat =
        IconCompat.createWithBitmap(draw(label, colourFor(category)))

    /** Silhouette of a downloaded app icon. */
    fun fromAppIcon(icon: Bitmap): IconCompat =
        IconCompat.createWithBitmap(silhouette(icon))

    /**
     * Draws the explicit-content badge into the corner of the cover, the way Apple Music does.
     * A drawn mark survives every surface that shows the artwork, while a character in the title
     * depended on the watch font having a glyph for it.
     */
    fun withExplicitBadge(cover: Bitmap, sizePx: Int = 64): Bitmap {
        val marked = cover.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(marked)
        val side = marked.width.coerceAtMost(marked.height)
        val badge = (side * 0.20f).toInt().coerceIn(24, sizePx)
        // The watch's media controller draws artwork full-bleed and centre-crops it to a circle,
        // so anything inside the outer ~15% is cut away. The badge sits just inside that circle.
        val margin = (side * 0.16f).toInt().coerceAtLeast(4)

        val background =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE6202124.toInt()
                style = Paint.Style.FILL
            }
        val rect =
            android.graphics.RectF(
                margin.toFloat(),
                margin.toFloat(),
                (margin + badge).toFloat(),
                (margin + badge).toFloat(),
            )
        val corner = badge * 0.22f
        canvas.drawRoundRect(rect, corner, corner, background)

        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = badge * 0.62f
            }
        canvas.drawText(
            "E",
            rect.centerX(),
            rect.centerY() - (text.descent() + text.ascent()) / 2f,
            text,
        )
        return marked
    }

    /**
     * Media-card status icon. A music note identifies the track itself, and the explicit mark is
     * drawn as a badge on top, so it is visible on every surface that shows the small icon -- the
     * notification list, the lock screen and the media controller.
     */
    fun media(explicit: Boolean): IconCompat {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7BDDFF.toInt() }
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, fill)

        val note =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = SIZE * 0.58f
            }
        canvas.drawText(
            "\u266A",
            SIZE / 2f,
            SIZE / 2f - (note.descent() + note.ascent()) / 2f,
            note,
        )
        if (explicit) drawCornerBadge(canvas, SIZE.toFloat())
        return IconCompat.createWithBitmap(bitmap)
    }

    /** Small filled "E" in the bottom-right corner of a 96 px icon. */
    private fun drawCornerBadge(canvas: Canvas, side: Float) {
        val badge = side * 0.46f
        val margin = side * 0.02f
        val left = side - badge - margin
        val top = side - badge - margin
        canvas.drawRoundRect(
            android.graphics.RectF(left, top, left + badge, top + badge),
            badge * 0.25f,
            badge * 0.25f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt() },
        )
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = badge * 0.78f
            }
        canvas.drawText(
            "E",
            left + badge / 2f,
            top + badge / 2f - (text.descent() + text.ascent()) / 2f,
            text,
        )
    }

    /** Full-size artwork for the media card, which is not tinted. */
    fun largeArtwork(label: String, category: Int, sizePx: Int = 320): Bitmap =
        Bitmap.createScaledBitmap(draw(label, colourFor(category)), sizePx, sizePx, true)

    private fun draw(label: String, background: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = SIZE / 2f
        canvas.drawCircle(
            radius,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
        )
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = SIZE * if (label.length <= 2) 0.44f else 0.30f
            }
        canvas.drawText(label, radius, radius - (text.descent() + text.ascent()) / 2f, text)
        return bitmap
    }

    /**
     * Keeps the shape but drops the colours: alpha comes from the distance between each pixel and
     * the icon's own background tone. Light-background icons are inverted, so both kinds leave a
     * readable mark instead of a filled square.
     */
    private fun silhouette(icon: Bitmap): Bitmap {
        val width = icon.width
        val height = icon.height
        val pixels = IntArray(width * height)
        icon.getPixels(pixels, 0, width, 0, 0, width, height)

        var total = 0L
        for (pixel in pixels) total += luminance(pixel)
        val invert = total / pixels.size > 170

        val out = IntArray(pixels.size)
        for (index in pixels.indices) {
            val value = luminance(pixels[index])
            val alpha = if (invert) 255 - value else value
            out[index] = (alpha shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun colourFor(category: Int): Int =
        when (category) {
            1,
            2,
            3 -> 0xFFFF6B5E.toInt()
            5,
            11 -> 0xFF7BDDFF.toInt()
            8 -> 0xFF8DE2BB.toInt()
            else -> 0xFF7BDDFF.toInt()
        }

    private companion object {
        const val SIZE = 96
    }
}
