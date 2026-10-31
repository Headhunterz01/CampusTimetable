package com.seb.campustimetable.data.model

import androidx.annotation.ColorInt

data class CourseBlock(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    /** Monday = 1 … Sunday = 7. */
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    @param:ColorInt val color: Int,
    val courseId: String = id,
    val sessionId: String = id,
    val weeks: List<Int> = emptyList(),
    val note: String = "",
)
