package com.seb.campustimetable.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.room.withTransaction
import com.seb.campustimetable.InterfacePreferences
import com.seb.campustimetable.MainActivity
import com.seb.campustimetable.R
import com.seb.campustimetable.data.decodePeriods
import com.seb.campustimetable.data.local.AppDatabase
import com.seb.campustimetable.data.periodScheduleKey
import com.seb.campustimetable.data.settingsStore
import com.seb.campustimetable.data.themeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimetableWidgets {
    const val REFRESH = "com.seb.campustimetable.widget.REFRESH"
    const val TICK = "com.seb.campustimetable.widget.TICK"
    private val lock = Mutex()
    private val providers = listOf(TodayWidgetProvider::class.java, NextClassWidgetProvider::class.java)
    private data class Palette(val surface: Int, val text: Int, val muted: Int, val accent: Int)

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(context, 2100,
        Intent(context, WidgetRefreshReceiver::class.java).setAction(TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** No observer or service stays alive. Reads a snapshot only when a widget needs refreshing. */
    suspend fun update(context: Context) = lock.withLock {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = providers.flatMap { type -> manager.getAppWidgetIds(ComponentName(app, type)).map { it to (type == NextClassWidgetProvider::class.java) } }
        val alarm = app.getSystemService(AlarmManager::class.java)
        if (ids.isEmpty()) {
            alarm.cancel(alarmIntent(app))
            return@withLock
        }
        val localized = InterfacePreferences.localized(app)
        val now = LocalDateTime.now()
        try {
            val settings = app.settingsStore.data.first()
            val db = AppDatabase.get(app)
            val day = db.withTransaction {
                val dao = db.courseDao()
                WidgetSchedule.project(dao.observeSemester().first(), dao.observeSchedule().first(), decodePeriods(settings[periodScheduleKey]), now)
            }
            val palette = palette(app, settings[themeKey] ?: "system")
            ids.forEach { (id, next) ->
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (next) 150 else 220)
                manager.updateAppWidget(id, render(localized, day, now, next, height, palette))
            }
            // Inexact, non-wakeup alarm: no exact-alarm permission and no extra wakeups while asleep.
            alarm.set(AlarmManager.RTC, day.nextRefresh.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarmIntent(app))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w("TimetableWidgets", "Widget refresh failed", error)
            ids.forEach { (id, _) ->
                val view = base(localized, palette(app, "system"))
                view.setTextViewText(R.id.widget_title, localized.getString(R.string.app_name))
                view.setTextViewText(R.id.widget_date, "")
                view.setTextViewText(R.id.widget_empty, localized.getString(R.string.widget_error))
                view.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                view.setViewVisibility(R.id.widget_body, View.GONE)
                manager.updateAppWidget(id, view)
            }
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 30 * 60_000L, alarmIntent(app))
        }
    }

    private fun palette(context: Context, theme: String): Palette {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = theme == "oled" || (theme != "light" && systemDark)
        if (theme == "monet" && Build.VERSION.SDK_INT >= 31) return Palette(
            context.getColor(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50),
            context.getColor(if (dark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900),
            context.getColor(if (dark) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700),
            context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600),
        )
        return if (dark) Palette(if (theme == "oled") Color.BLACK else Color.rgb(29, 27, 32), Color.rgb(230, 225, 229), Color.rgb(202, 196, 208), Color.rgb(208, 188, 255))
        else Palette(Color.rgb(247, 243, 252), Color.rgb(29, 27, 32), Color.rgb(97, 91, 104), Color.rgb(103, 80, 164))
    }

    private fun base(context: Context, colors: Palette) = RemoteViews(context.packageName, R.layout.widget_timetable).apply {
        setInt(R.id.widget_surface, "setColorFilter", colors.surface)
        setTextColor(R.id.widget_title, colors.text)
        listOf(R.id.widget_date, R.id.widget_footer, R.id.widget_empty).forEach { setTextColor(it, colors.muted) }
        setTextViewText(R.id.widget_footer, context.getString(R.string.widget_open))
        setContentDescription(R.id.widget_refresh, context.getString(R.string.widget_refresh))
        setInt(R.id.widget_refresh, "setColorFilter", colors.accent)
        setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 2101,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 2102,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        removeAllViews(R.id.widget_body)
    }

    internal fun renderForVerification(context: Context, day: WidgetDay, now: LocalDateTime, next: Boolean, height: Int, theme: String) =
        render(context, day, now, next, height, palette(context, theme))

    private fun render(context: Context, day: WidgetDay, now: LocalDateTime, next: Boolean, height: Int, colors: Palette): RemoteViews {
        val view = base(context, colors)
        val locale = context.resources.configuration.locales[0]
        val dateFormat = DateTimeFormatter.ofPattern(context.getString(R.string.widget_date_pattern), locale)
        val clock = DateTimeFormatter.ofPattern("HH:mm", locale)
        val current = day.next?.let { it.start <= now && it.end > now } == true
        view.setTextViewText(R.id.widget_title, context.getString(if (!next) R.string.widget_today else if (current) R.string.widget_ongoing else R.string.widget_next))
        view.setTextViewText(R.id.widget_date, now.format(dateFormat) + (day.week?.let { " · " + context.getString(R.string.widget_week, it) } ?: ""))
        val limit = ((height - 78) / 48).coerceIn(1, 8)
        val remaining = day.classes.filter { it.end > now }
        val visible = if (next) listOfNotNull(day.next) else (if (remaining.isEmpty()) day.classes.takeLast(limit) else remaining.take(limit))
        if (visible.isEmpty()) {
            view.setViewVisibility(R.id.widget_body, View.GONE)
            view.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            view.setTextViewText(R.id.widget_empty, context.getString(if (next) R.string.widget_no_upcoming else if (day.week == null) R.string.widget_outside_semester else R.string.widget_no_classes))
        } else {
            view.setViewVisibility(R.id.widget_empty, View.GONE)
            visible.forEach { item ->
                val row = RemoteViews(context.packageName, if (next) R.layout.widget_next_row else R.layout.widget_course_row)
                row.setInt(R.id.widget_course_color, "setColorFilter", item.row.colorArgb)
                row.setTextColor(R.id.widget_course_name, colors.text)
                row.setTextColor(R.id.widget_course_time, colors.accent)
                row.setTextColor(R.id.widget_course_location, colors.muted)
                row.setTextViewText(R.id.widget_course_name, item.row.name)
                row.setTextViewText(R.id.widget_course_time, item.start.format(clock) + (if (next) "–" else "\n") + item.end.format(clock))
                val place = item.row.location.ifBlank { context.getString(R.string.widget_periods, item.row.startPeriod, item.row.endPeriod) }
                row.setTextViewText(R.id.widget_course_location, if (item.start <= now && item.end > now) context.getString(R.string.widget_current_location, place) else place)
                view.addView(R.id.widget_body, row)
            }
            if (next) view.setTextViewText(R.id.widget_date, day.next!!.start.format(dateFormat))
            else view.setTextViewText(R.id.widget_footer, context.resources.getQuantityString(if (remaining.isEmpty()) R.plurals.widget_done else R.plurals.widget_total, day.classes.size, day.classes.size))
        }
        return view
    }
}

abstract class TimetableWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refreshAsync(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { refreshAsync(context) }
    override fun onDisabled(context: Context) { refreshAsync(context) }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED)) refreshAsync(context)
        else super.onReceive(context, intent)
    }
    private fun refreshAsync(context: Context) = launchWidgetUpdate(context, goAsync())
}

class TodayWidgetProvider : TimetableWidgetProvider()
class NextClassWidgetProvider : TimetableWidgetProvider()

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TimetableWidgets.REFRESH || intent.action == TimetableWidgets.TICK) launchWidgetUpdate(context, goAsync())
    }
}

private fun launchWidgetUpdate(context: Context, pending: BroadcastReceiver.PendingResult) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { withTimeout(8_000) { TimetableWidgets.update(context) } }
        catch (error: Exception) { Log.w("TimetableWidgets", "Widget update interrupted", error) }
        finally { pending.finish() }
    }
}
