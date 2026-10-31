package com.seb.campustimetable.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, CourseSessionEntity::class, SemesterEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao

    companion object {
        private val MIGRATION_1_2 = Migration(1, 2) { db ->
            // Correct the built-in afternoon examples while leaving any user-edited rows untouched.
            val samples = listOf(
                SampleAfternoon("calculus_mon", 1, 6, 6, 7, "教学楼②511", "高等数学 III（1）", "未填写", 63, 195, 126),
                SampleAfternoon("psychology", 2, 6, 6, 7, "教学楼①308", "大学生心理健康教育", "未填写", 61, 201, 184),
                SampleAfternoon("military", 3, 6, 6, 7, "楼206", "军事理论", "杨钊、杨勋", 255, 151, 0),
                SampleAfternoon("inorganic_pm", 5, 6, 6, 7, "楼506", "无机及分析化学", "杨钊、杨勋", 255, 151, 0),
                SampleAfternoon("sports", 1, 8, 8, 9, "北田径场", "体育 I", "白云", 247, 174, 190),
                SampleAfternoon("calculus_tue", 2, 8, 8, 9, "教学楼②511", "高等数学 III（1）", "未填写", 63, 195, 126),
                SampleAfternoon("xi_ideology_wed", 3, 8, 8, 9, "", "习近平新时代中国特色社会主义思想概论", "杨钊、杨勋", 104, 145, 219),
                SampleAfternoon("xi_ideology_fri", 5, 8, 8, 9, "", "习近平新时代中国特色社会主义思想概论", "杨钊、杨勋", 255, 151, 0),
            )
            for (sample in samples) {
                db.execSQL(
                    """UPDATE course_sessions SET endPeriod = ?
                       WHERE id = ? AND courseId = ? AND weekday = ? AND startPeriod = ? AND endPeriod = ?
                         AND location = ? AND weeksCsv = ''
                         AND EXISTS (
                           SELECT 1 FROM courses c WHERE c.id = course_sessions.courseId
                             AND c.name = ? AND c.teacher = ? AND c.colorArgb = ? AND c.note = ''
                         )""".trimIndent(),
                    arrayOf<Any?>(sample.newEnd, sample.id, sample.id, sample.weekday, sample.start, sample.oldEnd,
                        sample.location, sample.name, sample.teacher, sample.colorArgb),
                )
            }
        }

        private data class SampleAfternoon(
            val id: String, val weekday: Int, val start: Int, val oldEnd: Int, val newEnd: Int,
            val location: String, val name: String, val teacher: String,
            private val red: Int, private val green: Int, private val blue: Int,
        ) {
            val colorArgb: Int get() = android.graphics.Color.rgb(red, green, blue)
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "campus_timetable.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
