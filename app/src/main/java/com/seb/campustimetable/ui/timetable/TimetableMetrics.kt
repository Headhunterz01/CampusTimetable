package com.seb.campustimetable.ui.timetable

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue

/** The header and timetable must use the same rail width. */
internal fun timetableRailWidth(context: Context): Float {
    val metrics = context.resources.displayMetrics
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, metrics)
    }
    return maxOf(46f * metrics.density, paint.measureText("00:00") + 10f * metrics.density)
}
