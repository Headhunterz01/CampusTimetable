package com.seb.campustimetable.ui.timetable

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.seb.campustimetable.R
import com.seb.campustimetable.data.model.CourseBlock
import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.defaultPeriods
import kotlin.math.abs
import kotlin.math.floor

class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private data class CardHit(val course: CourseBlock, val bounds: RectF)

    companion object {
        private const val TOP_DP = 4f
        private const val BOTTOM_DP = 18f
        private const val PERIOD_HEIGHT_DP = 44f

    }

    var periods: List<PeriodSlot> = defaultPeriods
        set(value) { field = value; requestLayout(); invalidate() }

    var selectedWeek: Int = 9
        set(value) { field = value.coerceAtLeast(1); invalidate() }
    var courseBlocks: List<CourseBlock> = emptyList()
        set(value) { field = value; invalidate() }
    var onCourseClick: ((CourseBlock) -> Unit)? = null
    var onCourseConflictClick: ((List<CourseBlock>) -> Unit)? = null
    var onWeekSwipe: ((Int) -> Unit)? = null
    var useMonetDividers: Boolean = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val railWidth = timetableRailWidth(context)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dividerColor = ResourcesCompat.getColor(resources, R.color.timetable_divider, context.theme)
    private val cardHits = mutableListOf<CardHit>()
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        labelPaint.textSize = sp(11f)
        labelPaint.color = android.graphics.Color.WHITE
        timePaint.textSize = sp(9f)
        timePaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        timePaint.alpha = 185
        numberPaint.textSize = sp(11f)
        numberPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        numberPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    }

    private val periodHeight: Float by lazy {
        val numberHeight = numberPaint.fontMetrics.run { descent - ascent }
        val timeHeight = timePaint.fontMetrics.run { descent - ascent }
        maxOf(PERIOD_HEIGHT_DP * density, numberHeight + 2f * timeHeight + 8f * density)
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (TOP_DP * density + periods.size * periodHeight + BOTTOM_DP * density).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gridRight = width.toFloat()
        val columnWidth = (gridRight - railWidth) / 7f
        val top = TOP_DP * density
        val canvasHeight = height - BOTTOM_DP * density
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        canvas.drawColor(surface)
        cardHits.clear()

        gridPaint.color = if (useMonetDividers)
            ColorUtils.setAlphaComponent(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant), 112,
            )
        else dividerColor
        gridPaint.strokeWidth = density
        // Day separators and period boundaries keep the timeline legible without a heavy grid.
        for (day in 0..7) {
            val x = railWidth + day * columnWidth
            canvas.drawLine(x, top, x, canvasHeight, gridPaint)
        }
        for (number in 1..periods.size) {
            val yTop = yForPeriod(number, top)
            val yBottom = yTop + periodHeight
            canvas.drawLine(railWidth, yTop, gridRight, yTop, gridPaint)
            val period = periods[number - 1]
            if (!period.isPlaceholder) drawPeriodLabel(canvas, number, period.start, period.end, yTop, yBottom)
            else canvas.drawText(number.toString(), 5f * density, yTop + 2f * density - numberPaint.fontMetrics.ascent, numberPaint)
        }
        canvas.drawLine(railWidth, yForPeriod(periods.size + 1, top), gridRight, yForPeriod(periods.size + 1, top), gridPaint)

        val visibleCourses = courseBlocks.filter { isVisible(it, selectedWeek) }
        for (course in visibleCourses) {
            if (course.startPeriod !in 1..periods.size || course.endPeriod !in course.startPeriod..periods.size ||
                periods[course.startPeriod - 1].isPlaceholder || periods[course.endPeriod - 1].isPlaceholder) continue
            val left = railWidth + (course.weekday - 1) * columnWidth + 2f * density
            val right = railWidth + course.weekday * columnWidth - 2f * density
            // Reserved rows stay empty even when a course spans across them.
            val segments = mutableListOf<Pair<Int, Int>>()
            var segmentStart: Int? = null
            for (number in course.startPeriod..course.endPeriod) {
                if (periods[number - 1].isPlaceholder) {
                    segmentStart?.let { segments.add(it to number - 1) }
                    segmentStart = null
                } else if (segmentStart == null) segmentStart = number
            }
            segmentStart?.let { segments.add(it to course.endPeriod) }
            for ((first, last) in segments) {
                val rect = RectF(left, yForPeriod(first, top) + 1f * density,
                    right, yForPeriod(last + 1, top) - 1f * density)
                cardPaint.color = course.color
                canvas.drawRoundRect(rect, 10f * density, 10f * density, cardPaint)
                drawCourseText(canvas, course, rect)
                if (visibleCourses.any { other -> other !== course && other.weekday == course.weekday &&
                        other.startPeriod <= last && first <= other.endPeriod }) {
                    val badgeX = rect.right - 6f * density
                    val badgeY = rect.top + 6f * density
                    cardPaint.color = android.graphics.Color.BLACK
                    cardPaint.alpha = 170
                    canvas.drawCircle(badgeX, badgeY, 5f * density, cardPaint)
                    cardPaint.alpha = 255
                    numberPaint.color = android.graphics.Color.WHITE
                    numberPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("!", badgeX, badgeY + 4f * density, numberPaint)
                    numberPaint.textAlign = Paint.Align.LEFT
                    numberPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
                }
                cardHits += CardHit(course, rect)
            }
        }
    }

    private fun isVisible(course: CourseBlock, week: Int): Boolean = course.weeks.isEmpty() || week in course.weeks

    private fun yForPeriod(number: Int, top: Float): Float = top + (number - 1) * periodHeight

    private fun drawPeriodLabel(canvas: Canvas, number: Int, start: String, end: String, top: Float, bottom: Float) {
        val numberMetrics = numberPaint.fontMetrics
        val timeMetrics = timePaint.fontMetrics
        val numY = top + 2f * density - numberMetrics.ascent
        val startY = top + 4f * density + (numberMetrics.descent - numberMetrics.ascent) - timeMetrics.ascent
        canvas.drawText(number.toString(), 5f * density, numY, numberPaint)
        canvas.drawText(start, 5f * density, startY, timePaint)
        canvas.drawText(end, 5f * density, bottom - 2f * density - timeMetrics.descent, timePaint)
    }

    private fun drawCourseText(canvas: Canvas, course: CourseBlock, rect: RectF) {
        labelPaint.color = if (ColorUtils.calculateContrast(android.graphics.Color.BLACK, course.color) >=
            ColorUtils.calculateContrast(android.graphics.Color.WHITE, course.color)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val text = if (course.location.isBlank()) course.name else "${course.name}\n${course.location}"
        val pad = 2f * density
        val contentWidth = (rect.width() - 2 * pad).toInt().coerceAtLeast(1)
        val lineHeight = labelPaint.fontMetricsInt.run { (descent - ascent).toFloat() }.coerceAtLeast(1f)
        val maxLines = floor((rect.height() - 2 * pad).coerceAtLeast(0f) / lineHeight).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(contentWidth)
            .build()
        canvas.save()
        canvas.clipRect(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        canvas.translate(rect.left + pad, rect.top + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > 72f * density && abs(dx) > abs(dy) * 1.35f) {
                    onWeekSwipe?.invoke(if (dx < 0) 1 else -1)
                } else if (abs(dx) < 14f * density && abs(dy) < 14f * density) {
                    val hits = cardHits.filter { it.bounds.contains(event.x, event.y) }.map { it.course }
                    if (hits.size > 1) onCourseConflictClick?.invoke(hits)
                    else hits.firstOrNull()?.let { onCourseClick?.invoke(it) }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
