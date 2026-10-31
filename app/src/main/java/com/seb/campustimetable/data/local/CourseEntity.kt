package com.seb.campustimetable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val teacher: String,
    val colorArgb: Int,
    val note: String = "",
)
