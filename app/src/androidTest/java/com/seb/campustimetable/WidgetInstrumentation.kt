package com.seb.campustimetable

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.defaultPeriods
import com.seb.campustimetable.data.settingsStore
import com.seb.campustimetable.data.reminderMinutesKey
import com.seb.campustimetable.data.local.*
import com.seb.campustimetable.widget.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Uses an isolated package and a temporary widget host; never edits the user's launcher or database. */
class WidgetInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var host: AppWidgetHost? = null
        try {
            check(targetContext.packageName.endsWith(".verification"))
            val term = SemesterEntity(name = "Widget fixture", startDateEpochDay = LocalDate.of(2026, 8, 31).toEpochDay())
            fun row(id: String, weekday: Int, start: Int, end: Int, weeks: String = "9") =
                CourseSessionRow(id, id, "Example $id", "", 0xff6750a4.toInt(), "", weekday, start, end, "Room 204", weeks)
            val rows = listOf(row("morning", 4, 1, 2), row("afternoon", 4, 6, 7), row("friday", 5, 3, 4),
                row("wrong-week", 4, 3, 4, "8"), row("hidden", 4, 13, 14), row("placeholder", 4, 5, 5))
            val now = LocalDateTime.of(2026, 10, 29, 9, 0)
            val day = WidgetSchedule.project(term, rows, defaultPeriods, now)
            check(day.week == 9 && day.classes.map { it.row.sessionId } == listOf("morning", "afternoon"))
            check(day.next?.row?.sessionId == "morning" && day.nextRefresh == now.withHour(10).withMinute(10))
            val atEnd = WidgetSchedule.project(term, rows, defaultPeriods, now.withHour(10).withMinute(10))
            check(atEnd.next?.row?.sessionId == "afternoon")
            val tomorrow = WidgetSchedule.project(term, rows, defaultPeriods, now.withHour(18))
            check(tomorrow.next?.row?.sessionId == "friday" && tomorrow.nextRefresh == now.toLocalDate().plusDays(1).atStartOfDay())
            check(WidgetSchedule.project(term, rows, defaultPeriods, now.plusDays(2)).next == null)
            check(WidgetSchedule.project(term, rows, defaultPeriods, now.plusYears(1)).week == null)
            check(WidgetSchedule.project(term, rows, defaultPeriods, now.minusMonths(3)).next?.row?.sessionId == "wrong-week")
            check(WidgetSchedule.project(null, rows, defaultPeriods, now).classes.isEmpty())
            val custom = defaultPeriods.toMutableList().apply { this[0] = PeriodSlot("07:30", "08:10") }
            check(WidgetSchedule.project(term, rows, custom, now).classes.first().start.hour == 7)
            val recurring = listOf(row("odd", 4, 1, 4, "1,3,5,7,9"), row("every", 4, 6, 7, ""))
            check(WidgetSchedule.project(term, recurring, defaultPeriods, now.plusWeeks(1)).classes.map { it.row.sessionId } == listOf("every"))
            check(WidgetSchedule.project(term, recurring, defaultPeriods, now).classes.first().end.hour == 12)

            runOnMainSync {
                for (language in listOf("zh-Hans", "en")) for (theme in listOf("light", "oled", "system", "monet")) {
                    val config = Configuration(targetContext.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))) }
                    val localized = targetContext.createConfigurationContext(config)
                    for (next in listOf(false, true)) {
                        val remote = TimetableWidgets.renderForVerification(localized, day, now, next, if (next) 170 else 240, theme)
                        val root = remote.apply(localized, FrameLayout(localized))
                        check(root.findViewById<LinearLayout>(R.id.widget_body).childCount == if (next) 1 else 2)
                        val width = if (next) 180 else 340
                        val height = if (next) 170 else 240
                        val density = localized.resources.displayMetrics.density
                        val w = (width * density).toInt(); val h = (height * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, w, h)
                        check(root.findViewById<TextView>(R.id.widget_course_name).height > 0)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(targetContext.getExternalFilesDir(null), "widget-$language-$theme-$next.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    val empty = TimetableWidgets.renderForVerification(localized, WidgetSchedule.project(null, emptyList(), defaultPeriods, now), now, false, 110, theme).apply(localized, FrameLayout(localized))
                    check(empty.findViewById<View>(R.id.widget_body).visibility == View.GONE)
                    check(empty.findViewById<TextView>(R.id.widget_empty).visibility == View.VISIBLE)
                }
            }

            val manager = AppWidgetManager.getInstance(targetContext)
            host = AppWidgetHost(targetContext, 1800)
            runBlocking {
                val dao = AppDatabase.get(targetContext).courseDao()
                val monday = LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
                dao.insertSemester(term.copy(startDateEpochDay = monday.minusWeeks(8).toEpochDay()))
                dao.importSchedule(listOf(CourseEntity("fixture", "Widget fixture", "", 0xff6750a4.toInt())),
                    listOf(CourseSessionEntity("fixture", "fixture", LocalDate.now().dayOfWeek.value, 1, 2, "Room 204", "9")), true)
            }
            for (type in listOf(TodayWidgetProvider::class.java, NextClassWidgetProvider::class.java)) {
                val id = host.allocateAppWidgetId()
                check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(targetContext, type))) { "Grant widget bind permission to the isolated verification package first" }
                val info = manager.getAppWidgetInfo(id)
                check(info != null && info.updatePeriodMillis == 1_800_000)
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 340)
                })
                runBlocking { TimetableWidgets.update(targetContext) }
                runOnMainSync {
                    val view = host.createView(targetContext, id, info)
                    check(view.findViewById<TextView>(R.id.widget_title) != null) { "RemoteViews failed to inflate in AppWidgetHostView" }
                }
                if (type == TodayWidgetProvider::class.java) {
                    runBlocking {
                        targetContext.settingsStore.edit { it[reminderMinutesKey] = -1 }
                        AppDatabase.get(targetContext).courseDao().insertCourse(CourseEntity("fixture", "Updated fixture", "", 0xff6750a4.toInt()))
                        com.seb.campustimetable.reminder.Reminders.reschedule(targetContext)
                    }
                    runOnMainSync {
                        val view = host.createView(targetContext, id, info)
                        check(view.findViewById<TextView>(R.id.widget_course_name).text.toString() == "Updated fixture") { "Editing did not refresh widget with notifications disabled" }
                    }
                }
            }
            result.putString("stream", "PASS: semester/week/weekday filtering, alternating weeks, spanning/hidden/placeholder periods, custom times, ongoing/end/next-day transitions, four themes and two languages, RemoteViews rendering, real AppWidgetHost binding, edits refresh even with notifications disabled\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            host?.deleteHost()
            runBlocking { TimetableWidgets.update(targetContext) }
        }
    }
}
