package com.sarab.vision.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Renders a map label to a bitmap.
 *
 * ## Why not a MapLibre symbol layer with text
 *
 * MapLibre draws text from signed-distance-field glyphs fetched over the
 * network from a `glyphs` endpoint. A raster style has no such endpoint, so
 * adding one would mean a second network dependency purely for lettering --
 * and the offline story is the thing this app sells.
 *
 * The deeper problem is Arabic. Glyph-based rendering needs the shaping engine
 * to join letters and apply contextual forms, and MapLibre's support for that
 * is inconsistent across versions. Getting "كلية الشريعة" wrong -- disjointed
 * letters, reversed order -- looks broken to every single user of this app.
 *
 * Android's own text engine shapes Arabic correctly, always. So the label is
 * drawn to a bitmap here and handed to MapLibre as an image, which it places
 * and scales like any other icon. The lettering is guaranteed right because
 * the platform drew it.
 */
object MapLabels {

    /** Density-independent sizing, resolved against the screen at call time. */
    private const val TEXT_SP = 13f
    private const val PADDING_DP = 7f
    private const val RADIUS_DP = 7f

    /**
     * Draws a rounded pill containing [text].
     *
     * @param density screen density, so the label is legible on any phone
     * @param highlight true for the current destination, which is drawn in the
     *   accent colour so it is findable at a glance among a dozen others
     */
    fun pill(text: String, density: Float, highlight: Boolean): Bitmap {
        val textSize = TEXT_SP * density
        val padding = PADDING_DP * density
        val radius = RADIUS_DP * density

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // measureText shapes the string first, so this is the width of the
        // joined Arabic rather than the sum of isolated letters.
        val textWidth = paint.measureText(text)
        val metrics = paint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent

        val width = ceil(textWidth + padding * 2).toInt().coerceAtLeast(1)
        val height = ceil(textHeight + padding * 1.4f).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) 0xFF1573B8.toInt() else 0xE6101822.toInt()
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = if (highlight) 0xFF7FD4FF.toInt() else 0x66FFFFFF
        }

        val rect = RectF(
            border.strokeWidth,
            border.strokeWidth,
            width - border.strokeWidth,
            height - border.strokeWidth
        )
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, border)

        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(
            text,
            padding,
            (height - textHeight) / 2f - metrics.ascent,
            paint
        )

        return bitmap
    }

    /**
     * The blue location puck, with a heading cone when the compass is trusted.
     *
     * Modelled on the convention every mapping app shares, because a user who
     * already knows what a blue dot with a cone means does not have to learn
     * anything here.
     */
    fun locationPuck(density: Float, withHeading: Boolean): Bitmap {
        val size = ceil(46f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f

        if (withHeading) {
            // The cone points up: the symbol layer rotates the whole icon to
            // the heading, so drawing it any other way would double-rotate.
            val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    centre, centre, centre,
                    intArrayOf(0x991E88E5.toInt(), 0x001E88E5),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            val path = android.graphics.Path().apply {
                moveTo(centre, centre)
                lineTo(centre - 13f * density, centre - 21f * density)
                quadTo(centre, centre - 26f * density, centre + 13f * density, centre - 21f * density)
                close()
            }
            canvas.drawPath(path, cone)
        }

        canvas.drawCircle(
            centre, centre, 9f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        )
        canvas.drawCircle(
            centre, centre, 7f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
        )
        return bitmap
    }

    /** A destination pin, drawn once and reused for every landmark. */
    fun pin(density: Float, highlight: Boolean): Bitmap {
        val width = ceil(22f * density).toInt()
        val height = ceil(30f * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) 0xFFFFB300.toInt() else 0xFF4FC3F7.toInt()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFF0B1520.toInt()
        }

        val cx = width / 2f
        val headRadius = 8f * density
        val path = android.graphics.Path().apply {
            // Teardrop: circular head, tapering to a point at the coordinate.
            addCircle(cx, headRadius + 2f * density, headRadius, android.graphics.Path.Direction.CW)
            moveTo(cx - headRadius * 0.62f, headRadius + 6f * density)
            lineTo(cx, height - 2f * density)
            lineTo(cx + headRadius * 0.62f, headRadius + 6f * density)
            close()
        }
        canvas.drawPath(path, body)
        canvas.drawPath(path, outline)
        canvas.drawCircle(
            cx, headRadius + 2f * density, headRadius * 0.38f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1520.toInt() }
        )
        return bitmap
    }
}
