package com.seb.campustimetable.data.local

import androidx.room.ColumnInfo

data class CourseSessionRow(
    @ColumnInfo(name = "courseId") val courseId: String,
    @ColumnInfo(name = "sessionId") val sessionId: String,
    val name: String,
    val teacher: String,
    val colorArgb: Int,
    val note: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String,
    val weeksCsv: String,
)
