package org.echomap.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** A top-down view; height remains available in the CSV export. */
class ScanView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var points: List<Hit> = emptyList()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val grid = context.getColor(R.color.map_grid)
    private val ink = context.getColor(R.color.text_primary)
    private val muted = context.getColor(R.color.text_secondary)
    private val accent = context.getColor(R.color.accent)
    private val surface = context.getColor(R.color.surface)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val scale = ((min(width, height) - 56 * density) / 11f).coerceAtLeast(density)
        val edge = 5 * scale
        paint.strokeWidth = density
        paint.color = grid
        for (i in -5..5) {
            canvas.drawLine(cx + i * scale, cy - edge, cx + i * scale, cy + edge, paint)
            canvas.drawLine(cx - edge, cy + i * scale, cx + edge, cy + i * scale, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        paint.color = muted
        canvas.drawText(context.getString(R.string.north), cx, cy - edge - 10 * density, paint)
        canvas.drawText(context.getString(R.string.east), cx + edge + 14 * density, cy + 4 * density, paint)

        paint.color = accent
        for (point in points) canvas.drawCircle(cx + point.x * scale, cy - point.y * scale, 2.5f * density, paint)

        paint.color = surface
        canvas.drawCircle(cx, cy, 7 * density, paint)
        paint.color = ink
        canvas.drawCircle(cx, cy, 4 * density, paint)
        canvas.drawText(context.getString(R.string.phone), cx, cy + 23 * density, paint)

        if (points.isEmpty()) {
            val titleY = cy - 50 * density
            paint.textSize = 16 * resources.displayMetrics.scaledDensity
            val title = context.getString(R.string.empty_map)
            val hint = context.getString(R.string.empty_map_hint)
            val halfWidth = maxOf(paint.measureText(title), paint.measureText(hint)) / 2 + 12 * density
            paint.color = surface
            canvas.drawRect(cx - halfWidth, titleY - paint.textSize - 4 * density,
                cx + halfWidth, titleY + 30 * density, paint)
            paint.color = ink
            canvas.drawText(title, cx, titleY, paint)
            paint.color = muted
            paint.textSize = 13 * resources.displayMetrics.scaledDensity
            canvas.drawText(hint, cx, titleY + 24 * density, paint)
        }
    }
}
