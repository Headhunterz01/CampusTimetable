package com.seb.campustimetable.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "course_sessions",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("courseId")],
)
data class CourseSessionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String,
    /** Empty means every week; otherwise comma-separated teaching weeks. */
    val weeksCsv: String = "",
)
