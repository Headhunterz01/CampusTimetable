package com.seb.campustimetable.data.repository

import com.seb.campustimetable.data.local.CourseDao
import com.seb.campustimetable.data.local.CourseEntity
import com.seb.campustimetable.data.local.CourseSessionEntity
import com.seb.campustimetable.data.local.CourseSessionRow
import com.seb.campustimetable.data.local.SemesterEntity
import com.seb.campustimetable.data.model.CourseBlock
import com.seb.campustimetable.data.model.SampleSchedule
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class ScheduleRepository(private val dao: CourseDao) {
    val schedule: Flow<List<CourseSessionRow>> = dao.observeSchedule()
    val semester: Flow<SemesterEntity?> = dao.observeSemester()

    suspend fun seedIfEmpty() {
        dao.seed(
            SemesterEntity(
                name = "2026 秋季学期",
                startDateEpochDay = SampleSchedule.semesterStart.toEpochDay(),
                totalWeeks = 20,
            ), SampleSchedule.courses.map { it.toEntity() }, SampleSchedule.courses.map { it.toSession() },
        )
    }

    suspend fun saveSemester(semester: SemesterEntity) = dao.insertSemester(semester)
    suspend fun save(course: CourseEntity, session: CourseSessionEntity) = dao.save(course, session)
    suspend fun importSchedule(courses: List<CourseEntity>, sessions: List<CourseSessionEntity>, replace: Boolean) =
        dao.importSchedule(courses, sessions, replace)
    suspend fun deleteCourse(courseId: String) = dao.deleteCourse(courseId)
    suspend fun deleteSession(courseId: String, sessionId: String) = dao.deleteSession(courseId, sessionId)
}

private fun CourseBlock.toEntity() = CourseEntity(
    id = courseId,
    name = name,
    teacher = teacher,
    colorArgb = color,
    note = note,
)

private fun CourseBlock.toSession() = CourseSessionEntity(
    id = sessionId,
    courseId = courseId,
    weekday = weekday,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    location = location,
    weeksCsv = weeks.joinToString(","),
)
