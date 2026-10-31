package com.seb.campustimetable.data

import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A blank pair represents one reserved row, such as lunch. */
data class PeriodSlot(val start: String, val end: String) {
    val isPlaceholder: Boolean get() = start.isEmpty() && end.isEmpty()
}

val periodScheduleKey = stringPreferencesKey("period_schedule_v1")
val defaultPeriods = listOf(
    PeriodSlot("08:40", "09:20"), PeriodSlot("09:30", "10:10"),
    PeriodSlot("10:30", "11:10"), PeriodSlot("11:20", "12:00"),
    PeriodSlot("", ""),
    PeriodSlot("13:30", "14:10"), PeriodSlot("14:20", "15:00"),
    PeriodSlot("15:20", "16:00"), PeriodSlot("16:10", "16:50"),
    PeriodSlot("19:00", "19:40"), PeriodSlot("19:50", "20:30"), PeriodSlot("20:40", "21:20"),
)
private val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")

fun validatePeriods(slots: List<PeriodSlot>, context: android.content.Context? = null): String? {
    if (slots.size !in 1..24) return (context?.getString(com.seb.campustimetable.R.string.validation_there_must_be_1_24_periods) ?: "课时数须为 1–24 节")
    var lastEnd: LocalTime? = null
    var timed = 0
    slots.forEachIndexed { index, slot ->
        if (!slot.isPlaceholder) {
            if (!timePattern.matches(slot.start) || !timePattern.matches(slot.end)) return (context?.getString(com.seb.campustimetable.R.string.validation_period_has_an_incomplete_time, index + 1) ?: "第 ${index + 1} 节的时间不完整")
            val start = LocalTime.parse(slot.start)
            val end = LocalTime.parse(slot.end)
            if (start >= end) return (context?.getString(com.seb.campustimetable.R.string.validation_period_must_end_after_it_starts, index + 1) ?: "第 ${index + 1} 节的结束时间须晚于开始时间")
            if (lastEnd != null && start < lastEnd) return (context?.getString(com.seb.campustimetable.R.string.validation_period_overlaps_an_earlier_period_or_is_out_of_order, index + 1) ?: "第 ${index + 1} 节与前面的课时重叠或时间顺序颠倒")
            lastEnd = end
            timed++
        }
    }
    return if (timed == 0) (context?.getString(com.seb.campustimetable.R.string.validation_at_least_one_period_must_have_a_time) ?: "至少需要一节有时间的课时") else null
}

fun encodePeriods(slots: List<PeriodSlot>): String = JSONArray().apply {
    slots.forEach { put(JSONObject().put("start", it.start).put("end", it.end)) }
}.toString()

fun decodePeriods(encoded: String?): List<PeriodSlot> {
    if (encoded == null) return defaultPeriods
    return try {
        val array = JSONArray(encoded)
        val slots = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PeriodSlot(item.getString("start"), item.getString("end"))
        }
        if (validatePeriods(slots) == null) slots else defaultPeriods
    } catch (_: Exception) { defaultPeriods }
}

fun periodLabel(time: LocalTime): String = time.format(DateTimeFormatter.ofPattern("HH:mm"))
