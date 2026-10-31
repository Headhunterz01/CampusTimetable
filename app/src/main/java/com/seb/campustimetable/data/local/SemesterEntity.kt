package com.seb.campustimetable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val startDateEpochDay: Long,
    val totalWeeks: Int = 20,
)
