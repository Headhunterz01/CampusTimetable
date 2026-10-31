package com.seb.campustimetable.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.seb.campustimetable.data.*
import com.seb.campustimetable.data.importing.TimetableImport
import com.seb.campustimetable.data.local.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Portable across signing keys. Notification permissions are granted separately by Android. */
data class BackupData(val semester: SemesterEntity, val courses: List<CourseEntity>,
    val sessions: List<CourseSessionEntity>, val settings: JSONObject, val palette: JSONObject)

object TimetableBackup {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val mutex = Mutex()
    suspend fun capture(context: Context): BackupData = mutex.withLock { captureUnlocked(context) }
    private suspend fun captureUnlocked(context: Context): BackupData {
        val db = AppDatabase.get(context)
        val preferences = context.settingsStore.data.first()
        val settings = JSONObject().put("theme", preferences[themeKey] ?: "system")
            .put("reminderMinutes", preferences[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES)
            .put("periods", JSONArray(encodePeriods(decodePeriods(preferences[periodScheduleKey]))))
            .put("courseReminders", JSONObject(preferences[courseReminderMinutesKey] ?: "{}"))
        val palette = JSONObject()
        val storedPalette = context.getSharedPreferences("course_color_presets", Context.MODE_PRIVATE)
        listOf("colors", "names").forEach { key -> storedPalette.getString(key, null)?.let { palette.put(key, it) } }
        return db.withTransaction {
            val dao = db.courseDao()
            BackupData(requireNotNull(dao.observeSemester().first()) { (context?.getString(com.seb.campustimetable.R.string.validation_the_semester_has_not_been_initialized) ?: "学期尚未初始化") },
                dao.allCourses(), dao.allSessions(), settings, palette)
        }
    }

    fun encode(data: BackupData): String = JSONObject().apply {
        put("format", "campus-timetable-backup"); put("version", 1)
        put("createdAt", java.time.Instant.now().toString())
        put("semester", JSONObject().put("name", data.semester.name).put("startDate", data.semester.startDateEpochDay)
            .put("totalWeeks", data.semester.totalWeeks))
        put("courses", JSONArray().apply { data.courses.forEach { c -> put(JSONObject().put("id", c.id)
            .put("name", c.name).put("teacher", c.teacher).put("color", c.colorArgb).put("note", c.note)) } })
        put("sessions", JSONArray().apply { data.sessions.forEach { s -> put(JSONObject().put("id", s.id)
            .put("courseId", s.courseId).put("weekday", s.weekday).put("start", s.startPeriod)
            .put("end", s.endPeriod).put("location", s.location).put("weeks", s.weeksCsv)) } })
        put("settings", data.settings); put("palette", data.palette)
    }.toString(2)

    fun parse(text: String, context: Context? = null): BackupData = Reader(context).parse(text)

    private class Reader(private val context: Context?) {
        private fun JSONObject.text(key: String): String = get(key).let {
            require(it is String) { (context?.getString(com.seb.campustimetable.R.string.validation_must_be_a_string_2, key) ?: "$key 必须是字符串") }; it
        }
        private fun JSONObject.number(key: String): Long = get(key).let {
            require(it is Int || it is Long) { (context?.getString(com.seb.campustimetable.R.string.validation_must_be_an_integer_2, key) ?: "$key 必须是整数") }; (it as Number).toLong()
        }
        private fun JSONObject.intIn(key: String, range: IntRange): Int {
            val n = number(key); require(n in range.first.toLong()..range.last.toLong()) { (context?.getString(com.seb.campustimetable.R.string.validation_is_out_of_range, key) ?: "$key 超出范围") }; return n.toInt()
        }

        fun parse(text: String): BackupData {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { (context?.getString(com.seb.campustimetable.R.string.validation_backup_must_not_exceed_16_mb) ?: "备份不能超过 16 MB") }
            val input = text.removePrefix("\uFEFF")
            TimetableImport.validateJson(input, context)
            val root = JSONObject(input)
            require(root.text("format") == "campus-timetable-backup" && root.number("version") == 1L) { (context?.getString(com.seb.campustimetable.R.string.validation_unsupported_timetable_backup_file) ?: "不是支持的课表备份文件") }
            val term = root.getJSONObject("semester")
            val date = term.number("startDate")
            require(LocalDate.ofEpochDay(date).year in 1..9999) { (context?.getString(com.seb.campustimetable.R.string.validation_invalid_semester_date) ?: "学期日期无效") }
            val semester = SemesterEntity(name = term.text("name"), startDateEpochDay = date,
                totalWeeks = term.intIn("totalWeeks", 1..99))
            val courseArray = root.getJSONArray("courses")
            val courses = (0 until courseArray.length()).map { i -> courseArray.getJSONObject(i).let { c ->
                val color = c.number("color")
                require(color in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && color.toInt().ushr(24) == 255) { (context?.getString(com.seb.campustimetable.R.string.validation_invalid_course_color) ?: "课程颜色无效") }
                CourseEntity(c.text("id"), c.text("name"), c.text("teacher"), color.toInt(), c.text("note"))
            } }
            require(courses.all { it.id.isNotBlank() && it.name.isNotBlank() } && courses.map { it.id }.distinct().size == courses.size) { (context?.getString(com.seb.campustimetable.R.string.validation_duplicate_course_ids_or_empty_course_names) ?: "课程标识重复或名称为空") }
            val ids = courses.map { it.id }.toSet()
            val sessionArray = root.getJSONArray("sessions")
            val sessions = (0 until sessionArray.length()).map { i -> sessionArray.getJSONObject(i).let { s ->
                val start = s.intIn("start", 1..24)
                val weeks = s.text("weeks")
                require(weeks.isEmpty() || weeks.split(',').all { it.toIntOrNull()?.let { w -> w in 1..99 } == true }) { (context?.getString(com.seb.campustimetable.R.string.validation_invalid_class_weeks) ?: "上课周次无效") }
                CourseSessionEntity(s.text("id"), s.text("courseId"), s.intIn("weekday", 1..7), start,
                    s.intIn("end", start..24), s.text("location"), weeks)
            } }
            require(sessions.all { it.id.isNotBlank() && it.courseId in ids } && sessions.map { it.id }.distinct().size == sessions.size) { (context?.getString(com.seb.campustimetable.R.string.validation_duplicate_session_ids_or_missing_course) ?: "安排标识重复或课程不存在") }
            val settings = root.getJSONObject("settings")
            require(settings.text("theme") in listOf("light", "oled", "system", "monet")) { (context?.getString(com.seb.campustimetable.R.string.validation_invalid_theme) ?: "主题无效") }
            settings.intIn("reminderMinutes", -1..10080)
            val periodArray = settings.getJSONArray("periods")
            val slots = (0 until periodArray.length()).map { i -> periodArray.getJSONObject(i).let { PeriodSlot(it.text("start"), it.text("end")) } }
            require(validatePeriods(slots, context) == null) { validatePeriods(slots, context) ?: (context?.getString(com.seb.campustimetable.R.string.validation_invalid_periods) ?: "课时无效") }
            val overrides = settings.getJSONObject("courseReminders")
            overrides.keys().forEach { overrides.intIn(it, -1..10080) }
            val palette = root.getJSONObject("palette")
            if (palette.has("colors")) {
                val colors = JSONArray(palette.text("colors"))
                (0 until colors.length()).forEach { i ->
                    val n = colors.get(i)
                    require(n is Int && n.ushr(24) == 255) { (context?.getString(com.seb.campustimetable.R.string.validation_invalid_preset_color) ?: "预设颜色无效") }
                }
            }
            if (palette.has("names")) {
                val names = JSONObject(palette.text("names"))
                names.keys().forEach { names.text(it) }
            }
            return BackupData(semester, courses, sessions, settings, palette)
        }

    }

    private suspend fun applySettings(context: Context, data: BackupData) {
        context.settingsStore.edit {
            it[themeKey] = data.settings.getString("theme")
            it[reminderMinutesKey] = data.settings.getInt("reminderMinutes")
            it[periodScheduleKey] = data.settings.getJSONArray("periods").toString()
            it[courseReminderMinutesKey] = data.settings.getJSONObject("courseReminders").toString()
        }
        val editor = context.getSharedPreferences("course_color_presets", Context.MODE_PRIVATE).edit()
        listOf("colors", "names").forEach { key ->
            if (data.palette.has(key)) editor.putString(key, data.palette.getString(key)) else editor.remove(key)
        }
        check(editor.commit()) { (context?.getString(com.seb.campustimetable.R.string.validation_unable_to_save_color_presets) ?: "无法保存颜色预设") }
    }

    suspend fun restore(context: Context, data: BackupData) = mutex.withLock {
        val previous = captureUnlocked(context)
        val db = AppDatabase.get(context)
        try {
            db.withTransaction {
                db.courseDao().importSchedule(data.courses, data.sessions, true)
                db.courseDao().insertSemester(data.semester)
                applySettings(context, data)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { applySettings(context, previous) }
            throw error
        }
        context.getSharedPreferences("appearance_cache", Context.MODE_PRIVATE).edit()
            .putString("theme", data.settings.getString("theme")).commit()
    }
}
