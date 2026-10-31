package com.seb.campustimetable.ui.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import com.seb.campustimetable.R
import com.seb.campustimetable.data.model.SampleSchedule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeekHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var weekNumber: Int = 9
        set(value) { field = value.coerceAtLeast(1); invalidate() }
    var semesterStart: LocalDate = SampleSchedule.semesterStart
        set(value) { field = value; invalidate() }
    var useMonetAccent: Boolean = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val railWidth = timetableRailWidth(context)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dateBaseline: Float get() = maxOf(21f * density, 4f * density - datePaint.fontMetrics.ascent)
    private val weekdayBaseline: Float get() = maxOf(43f * density,
        dateBaseline + datePaint.fontMetrics.descent + 4f * density - titlePaint.fontMetrics.ascent)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = maxOf(54f * density, weekdayBaseline + titlePaint.fontMetrics.descent + 5f * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val primaryContainer = if (useMonetAccent)
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer) else surface
        val onPrimaryContainer = if (useMonetAccent)
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer) else primary
        canvas.drawColor(surface)
        titlePaint.color = onSurface
        datePaint.color = onSurface
        val start = semesterStart.plusWeeks((weekNumber - 1).toLong())
        val month = start.format(DateTimeFormatter.ofPattern(context.getString(R.string.week_month), resources.configuration.locales[0]))
        canvas.drawText(month, railWidth / 2f, dateBaseline, titlePaint)

        val columnWidth = (width - railWidth) / 7f
        val weekdayNames = arrayOf(context.getString(R.string.ui_mon), context.getString(R.string.ui_tue), context.getString(R.string.ui_wed), context.getString(R.string.ui_thu), context.getString(R.string.ui_fri), context.getString(R.string.ui_sat), context.getString(R.string.ui_sun))
        val today = LocalDate.now()
        for (index in 0..6) {
            val date = start.plusDays(index.toLong())
            val centerX = railWidth + columnWidth * (index + 0.5f)
            val active = date == today
            if (active && useMonetAccent) {
                val halfWidth = minOf(columnWidth - 6f * density, 44f * density) / 2f
                val bounds = RectF(centerX - halfWidth, 2f * density,
                    centerX + halfWidth, weekdayBaseline + titlePaint.fontMetrics.descent + 4f * density)
                dividerPaint.color = primaryContainer
                canvas.drawRoundRect(bounds, 13f * density, 13f * density, dividerPaint)
            }
            datePaint.color = if (active) onPrimaryContainer else onSurface
            titlePaint.color = if (active) onPrimaryContainer else onSurface
            canvas.drawText(date.dayOfMonth.toString().padStart(2, '0'), centerX, dateBaseline, datePaint)
            canvas.drawText(weekdayNames[index], centerX, weekdayBaseline, titlePaint)
        }
        dividerPaint.color = if (useMonetAccent)
            ColorUtils.setAlphaComponent(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant), 112,
            )
        else 0x1F667085
        dividerPaint.strokeWidth = density
        canvas.drawLine(0f, height - density, width.toFloat(), height - density, dividerPaint)
    }
}
