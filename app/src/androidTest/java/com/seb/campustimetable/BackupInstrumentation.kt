package com.seb.campustimetable

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.seb.campustimetable.data.*
import com.seb.campustimetable.data.backup.TimetableBackup
import com.seb.campustimetable.data.local.*
import com.seb.campustimetable.data.repository.ScheduleRepository
import com.seb.campustimetable.reminder.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Runs only in an isolated application id; never touches the user's installed timetable. */
class BackupInstrumentation : Instrumentation() {
    private var externalBackup: String? = null
    override fun onCreate(arguments: Bundle?) {
        externalBackup = arguments?.getString("backup")
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val results = Bundle()
        try {
            check(targetContext.packageName.endsWith(".verification")) { "Use -PisolatedVerification=true" }
            runBlocking(Dispatchers.IO) {
                val context = targetContext
                val dao = AppDatabase.get(context).courseDao()
                ScheduleRepository(dao).seedIfEmpty()
                val original = TimetableBackup.capture(context)
                val date = LocalDate.now().plusDays(1)
                val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
                val settings = JSONObject(original.settings.toString()).put("theme", "oled")
                    .put("reminderMinutes", -1).put("courseReminders", JSONObject().put("fixture-a", 17))
                val palette = JSONObject().put("colors", "[-16777216,-1]")
                    .put("names", "{\"#000000\":\"深色预设\"}")
                val fixture = original.copy(
                    semester = SemesterEntity(name = "恢复检查学期", startDateEpochDay = monday.toEpochDay(), totalWeeks = 20),
                    courses = listOf(CourseEntity("fixture-a", "课程甲", "教师", -1, "备注"), CourseEntity("fixture-b", "课程乙", "", -16777216)),
                    sessions = listOf(CourseSessionEntity("a", "fixture-a", date.dayOfWeek.value, 1, 2, "教室甲"),
                        CourseSessionEntity("b", "fixture-b", date.dayOfWeek.value, 1, 4, "教室乙"),
                        CourseSessionEntity("hidden", "fixture-a", 7, 23, 24, "隐藏安排", "99")),
                    settings = settings, palette = palette)
                TimetableBackup.restore(context, fixture)
                val parsed = TimetableBackup.parse(TimetableBackup.encode(TimetableBackup.capture(context)))
                check(parsed.courses == fixture.courses && parsed.sessions == fixture.sessions.sortedBy { it.id })
                check(parsed.semester == fixture.semester)
                check(parsed.settings.toString() == settings.toString() && parsed.palette.toString() == palette.toString())
                TimetableBackup.restore(context, parsed)
                check(TimetableBackup.capture(context).sessions.any { it.startPeriod == 23 && it.weeksCsv == "99" })
                Reminders.reschedule(context)
                val expected = date.atTime(8, 40).atZone(ZoneId.systemDefault()).minusMinutes(17).toInstant().toEpochMilli()
                check(context.getSharedPreferences("reminder_state", 0).getLong("scheduled_at", 0) == expected) { "Per-course override must work with the default disabled" }
                fun rejected(change: (JSONObject) -> Unit) {
                    val json = JSONObject(TimetableBackup.encode(parsed)); change(json)
                    check(runCatching { TimetableBackup.parse(json.toString()) }.isFailure)
                }
                rejected { it.getJSONArray("sessions").getJSONObject(0).put("courseId", "missing") }
                rejected { it.getJSONObject("settings").put("reminderMinutes", 10081) }
                rejected { it.getJSONObject("settings").getJSONArray("periods").getJSONObject(0).put("start", "bad") }
                rejected { it.getJSONArray("courses").getJSONObject(0).put("color", 255) }
                val invalid = parsed.copy(sessions = listOf(CourseSessionEntity("bad", "missing", 1, 1, 2, "")))
                check(runCatching { TimetableBackup.restore(context, invalid) }.isFailure)
                check(TimetableBackup.capture(context).courses == parsed.courses)
                check(TimetableBackup.capture(context).sessions == parsed.sessions)
                val brokenPalette = object : JSONObject() {
                    override fun getString(name: String): String { error("Simulated preference write failure") }
                }.apply { put("names", "{}") }
                val settingsFailure = parsed.copy(settings = JSONObject(settings.toString()).put("theme", "light"), palette = brokenPalette)
                check(runCatching { TimetableBackup.restore(context, settingsFailure) }.isFailure)
                check(TimetableBackup.capture(context).settings.toString() == settings.toString())
                check(TimetableBackup.capture(context).palette.toString() == palette.toString())
                val empty = parsed.copy(courses = emptyList(), sessions = emptyList(), palette = JSONObject().put("colors", "[]"))
                TimetableBackup.restore(context, TimetableBackup.parse(TimetableBackup.encode(empty)))
                check(dao.countCourses() == 0 && TimetableBackup.capture(context).palette.getString("colors") == "[]")
                ScheduleRepository(dao).seedIfEmpty()
                check(dao.countCourses() == 0) { "An intentionally empty restored timetable must stay empty" }
                externalBackup?.let { encoded ->
                    val text = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    val phone = TimetableBackup.parse(text)
                    TimetableBackup.restore(context, phone)
                    val restored = TimetableBackup.capture(context)
                    check(restored.courses == phone.courses.sortedBy { it.id })
                    check(restored.sessions == phone.sessions.sortedBy { it.id })
                    check(restored.semester == phone.semester)
                    check(restored.settings.toString() == phone.settings.toString())
                    check(restored.palette.toString() == phone.palette.toString())
                    results.putString("phoneBackup", "PASS: restored actual phone backup (${phone.courses.size} courses, ${phone.sessions.size} sessions)")
                }
                TimetableBackup.restore(context, original)
            }
            results.putString("stream", "PASS: full backup round trip, hidden data, palette names/order, per-course alarm, invalid input rejection, DB/settings rollback, empty restore\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
