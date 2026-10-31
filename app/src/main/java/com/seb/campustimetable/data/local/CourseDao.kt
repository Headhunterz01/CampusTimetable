package com.seb.campustimetable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY id")
    suspend fun allCourses(): List<CourseEntity>

    @Query("SELECT * FROM course_sessions ORDER BY id")
    suspend fun allSessions(): List<CourseSessionEntity>

    @Query(
        """SELECT c.id AS courseId, s.id AS sessionId, c.name, c.teacher, c.colorArgb, c.note,
                  s.weekday, s.startPeriod, s.endPeriod, s.location, s.weeksCsv
           FROM courses c INNER JOIN course_sessions s ON s.courseId = c.id
           ORDER BY s.weekday, s.startPeriod, c.name""",
    )
    fun observeSchedule(): Flow<List<CourseSessionRow>>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun countCourses(): Int

    @androidx.room.Upsert
    suspend fun insertCourse(course: CourseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CourseSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterEntity)

    @Query("SELECT COUNT(*) FROM semesters WHERE id = 1")
    suspend fun countSemesters(): Int

    @Query("SELECT * FROM semesters WHERE id = 1 LIMIT 1")
    fun observeSemester(): Flow<SemesterEntity?>

    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourse(courseId: String)

    @Query("DELETE FROM courses")
    suspend fun deleteAllCourses()

    @Query("DELETE FROM course_sessions WHERE id = :sessionId AND courseId = :courseId")
    suspend fun deleteSessionRow(courseId: String, sessionId: String)

    @Query("DELETE FROM courses WHERE id = :courseId AND NOT EXISTS (SELECT 1 FROM course_sessions WHERE courseId = :courseId)")
    suspend fun deleteCourseIfEmpty(courseId: String)

    @Transaction
    suspend fun deleteSession(courseId: String, sessionId: String) {
        deleteSessionRow(courseId, sessionId)
        deleteCourseIfEmpty(courseId)
    }

    @Transaction
    suspend fun save(course: CourseEntity, session: CourseSessionEntity) {
        insertCourse(course)
        insertSession(session)
    }

    @Transaction
    suspend fun importSchedule(courses: List<CourseEntity>, sessions: List<CourseSessionEntity>, replace: Boolean) {
        if (replace) deleteAllCourses()
        courses.forEach { insertCourse(it) }
        sessions.forEach { insertSession(it) }
    }

    @Transaction
    suspend fun seed(semester: SemesterEntity, courses: List<CourseEntity>, sessions: List<CourseSessionEntity>) {
        if (countSemesters() != 0) return
        insertSemester(semester)
        courses.forEach { insertCourse(it) }
        sessions.forEach { insertSession(it) }
    }
}
