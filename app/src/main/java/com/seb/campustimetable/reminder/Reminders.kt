package com.seb.campustimetable.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.seb.campustimetable.MainActivity
import com.seb.campustimetable.data.local.AppDatabase
import com.seb.campustimetable.data.local.CourseSessionRow
import com.seb.campustimetable.data.courseReminderMinutesKey
import com.seb.campustimetable.data.decodeCourseReminders
import com.seb.campustimetable.data.reminderMinutesKey
import com.seb.campustimetable.data.DEFAULT_REMINDER_MINUTES
import com.seb.campustimetable.data.settingsStore
import com.seb.campustimetable.data.decodePeriods
import com.seb.campustimetable.data.periodScheduleKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A single alarm points to the next occurrence. On delivery it schedules the next one. */
object Reminders {
    private const val CHANNEL = "course_reminders"
    private const val ALARM_ID = 1001
    private const val SCHEDULED_AT = "scheduled_at"
    private val lock = Mutex()
    private data class Occurrence(val at: Long, val row: CourseSessionRow, val leadMinutes: Int)
    private fun pending(context: Context, at: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setData(Uri.parse("campustimetable://reminder/$at"))
            .putExtra("at", at)
        return PendingIntent.getBroadcast(context, ALARM_ID, intent,
            flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(context: Context, at: Long) {
        if (at <= 0) return
        val operation = pending(context, at, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    private suspend fun occurrences(context: Context): List<Occurrence> {
        val settings = context.settingsStore.data.first()
        val minutes = settings[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES
        val overrides = decodeCourseReminders(settings[courseReminderMinutesKey])
        val dao = AppDatabase.get(context).courseDao()
        val semester = dao.observeSemester().first() ?: return emptyList()
        val start = LocalDate.ofEpochDay(semester.startDateEpochDay)
        val rows = dao.observeSchedule().first()
        val slots = decodePeriods(settings[periodScheduleKey])
        val zone = ZoneId.systemDefault()
        return rows.flatMap { row ->
            val lead = overrides[row.courseId] ?: minutes
            if (lead < 0) return@flatMap emptyList()
            val slot = slots.getOrNull(row.startPeriod - 1) ?: return@flatMap emptyList()
            if (slot.isPlaceholder) return@flatMap emptyList()
            val startTime = LocalTime.parse(slot.start)
            if (row.weekday !in 1..7) return@flatMap emptyList()
            val weeks = row.weeksCsv.split(',').mapNotNull(String::toIntOrNull).toSet()
            (1..semester.totalWeeks.coerceIn(0, 99)).asSequence()
                .filter { weeks.isEmpty() || it in weeks }
                .map { week ->
                    val date = start.plusWeeks((week - 1).toLong()).plusDays((row.weekday - 1).toLong())
                    val at = date.atTime(startTime).atZone(zone).minusMinutes(lead.toLong()).toInstant().toEpochMilli()
                    Occurrence(at, row, lead)
                }.toList()
        }
    }

    suspend fun reschedule(context: Context) = lock.withLock {
        val now = System.currentTimeMillis()
        val previous = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE).getLong(SCHEDULED_AT, 0L)
        if (previous > 0 && previous <= now) {
            cancelAlarm(context, previous)
            deliverLocked(context, previous, now)
        }
        scheduleLocked(context, now)
    }

    private suspend fun scheduleLocked(context: Context, after: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        // Remove the pre-identity alarm used by older app versions, if present.
        PendingIntent.getBroadcast(context, ALARM_ID, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { legacy ->
            alarm.cancel(legacy)
            legacy.cancel()
        }
        val state = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)
        cancelAlarm(context, state.getLong(SCHEDULED_AT, 0L))
        state.edit().remove(SCHEDULED_AT).commit()
        val next = occurrences(context).asSequence().map { it.at }.filter { it > after }.minOrNull() ?: return
        val operation = requireNotNull(pending(context, next, PendingIntent.FLAG_UPDATE_CURRENT))
        state.edit().putLong(SCHEDULED_AT, next).commit()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
                return
            } catch (_: SecurityException) { /* Permission changed while scheduling. */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
    }

    suspend fun deliver(context: Context, scheduledAt: Long) = lock.withLock {
        val now = System.currentTimeMillis()
        val state = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)
        if (scheduledAt <= 0 || state.getLong(SCHEDULED_AT, 0L) != scheduledAt) return@withLock
        deliverLocked(context, scheduledAt, now)
        scheduleLocked(context, maxOf(now, scheduledAt))
    }

    private suspend fun deliverLocked(context: Context, scheduledAt: Long, now: Long) {
        context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE).edit().remove(SCHEDULED_AT).commit()
        if (now < scheduledAt) return

        // If delivery was delayed, also catch nearby reminders that became due while the
        // device was idle. Older occurrences are discarded individually after the grace window.
        val due = occurrences(context).asSequence()
            .filter { it.at >= scheduledAt && it.at <= now && now - it.at < (it.leadMinutes + 10) * 60_000L }
            .groupBy { it.at }
        if (due.isEmpty()) return

        val localized = com.seb.campustimetable.InterfacePreferences.localized(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(com.seb.campustimetable.R.string.ui_course_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        due.forEach { (at, occurrencesAtTime) ->
            val description = occurrencesAtTime.joinToString("；") { item ->
                item.row.name + item.row.location.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            }
            val isLate = occurrencesAtTime.any { now >= it.at + it.leadMinutes * 60_000L }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (isLate) localized.getString(com.seb.campustimetable.R.string.ui_course_reminders) else if (occurrencesAtTime.size == 1) localized.getString(com.seb.campustimetable.R.string.ui_class_starting_soon) else localized.getString(com.seb.campustimetable.R.string.ui_classes_starting_soon, occurrencesAtTime.size))
                .setContentText(description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description))
                .setContentIntent(open).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(context).notify(at.hashCode(), notification) }
            catch (_: SecurityException) { /* Notification permission was revoked. */ }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { Reminders.deliver(context.applicationContext, intent.getLongExtra("at", 0L)) }
            finally { pending.finish() }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { Reminders.reschedule(context.applicationContext) } finally { pending.finish() }
        }
    }
}
