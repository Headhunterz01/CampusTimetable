package com.seb.campustimetable.widget

import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.local.CourseSessionRow
import com.seb.campustimetable.data.local.SemesterEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class WidgetClass(val row: CourseSessionRow, val start: LocalDateTime, val end: LocalDateTime)

data class WidgetDay(
    val week: Int?,
    val classes: List<WidgetClass>,
    val next: WidgetClass?,
    val nextRefresh: LocalDateTime,
)

/** Read-only projection: uses the same semester, teaching weeks and configurable periods as the app. */
object WidgetSchedule {
    fun project(semester: SemesterEntity?, rows: List<CourseSessionRow>, periods: List<PeriodSlot>, now: LocalDateTime): WidgetDay {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        if (semester == null) return WidgetDay(null, emptyList(), null, midnight)
        val firstDay = LocalDate.ofEpochDay(semester.startDateEpochDay)
        val elapsed = ChronoUnit.DAYS.between(firstDay, now.toLocalDate())
        val week = if (elapsed in 0 until semester.totalWeeks * 7L) (elapsed / 7 + 1).toInt() else null
        val today = mutableListOf<WidgetClass>()
        val upcoming = mutableListOf<WidgetClass>()
        rows.forEach { row ->
            if (row.weekday !in 1..7 || row.startPeriod !in 1..periods.size || row.endPeriod !in row.startPeriod..periods.size) return@forEach
            val startSlot = periods[row.startPeriod - 1]
            val endSlot = periods[row.endPeriod - 1]
            if (startSlot.isPlaceholder || endSlot.isPlaceholder) return@forEach
            val start = runCatching { LocalTime.parse(startSlot.start) }.getOrNull() ?: return@forEach
            val end = runCatching { LocalTime.parse(endSlot.end) }.getOrNull() ?: return@forEach
            if (end <= start) return@forEach
            val weeks = row.weeksCsv.split(',').mapNotNull(String::toIntOrNull).toSet()
            for (w in 1..semester.totalWeeks.coerceIn(0, 99)) {
                if (weeks.isNotEmpty() && w !in weeks) continue
                val day = firstDay.plusWeeks((w - 1).toLong()).plusDays((row.weekday - 1).toLong())
                if (day < now.toLocalDate()) continue
                val item = WidgetClass(row, day.atTime(start), day.atTime(end))
                if (day == now.toLocalDate()) today.add(item)
                if (item.end > now) {
                    upcoming.add(item)
                    break
                }
            }
        }
        val order = compareBy<WidgetClass> { it.start }.thenBy { it.row.name }.thenBy { it.row.sessionId }
        val sorted = today.sortedWith(order)
        val refresh = (sorted.flatMap { listOf(it.start, it.end) } + midnight).filter { it > now }.minOrNull() ?: midnight
        return WidgetDay(week, sorted, upcoming.minWithOrNull(order), refresh)
    }
}
