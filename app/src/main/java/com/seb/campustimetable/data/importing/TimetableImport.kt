package com.seb.campustimetable.data.importing

import android.graphics.Color
import android.util.JsonReader
import android.util.JsonToken
import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.local.CourseEntity
import com.seb.campustimetable.data.local.CourseSessionEntity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.StringReader
import java.util.UUID

data class ImportedTimetable(val courses: List<CourseEntity>, val sessions: List<CourseSessionEntity>)

/** Validate the entire document before producing any database writes. */
object TimetableImport {
    fun parse(text: String, periods: List<PeriodSlot>, totalWeeks: Int, context: android.content.Context? = null): ImportedTimetable =
        Reader(context).parse(text, periods, totalWeeks)

    fun validateJson(input: String, context: android.content.Context? = null) = Reader(context).validateJson(input)

    private class Reader(private val context: android.content.Context?) {
        private val colors = intArrayOf(0xff6f85d7.toInt(), 0xff4ca5de.toInt(), 0xff3db4aa.toInt(),
            0xff3fb473.toInt(), 0xffe19428.toInt(), 0xffcb788c.toInt(), 0xffa078c8.toInt())

        fun parse(text: String, periods: List<PeriodSlot>, totalWeeks: Int): ImportedTimetable {
            val input = text.removePrefix("\uFEFF")
            val root = try {
                validateJson(input)
                val tokener = JSONTokener(input)
                val value = tokener.nextValue()
                require(tokener.nextClean() == '\u0000') { (context?.getString(com.seb.campustimetable.R.string.validation_extra_content_after_json) ?: "JSON 末尾存在多余内容") }
                value as? JSONObject ?: error((context?.getString(com.seb.campustimetable.R.string.validation_the_top_level_must_be_a_json_object) ?: "顶层必须是 JSON 对象"))
            } catch (e: Exception) {
                throw IllegalArgumentException((context?.getString(com.seb.campustimetable.R.string.validation_invalid_json, e.message ?: (context?.getString(com.seb.campustimetable.R.string.validation_unable_to_read) ?: "无法读取")) ?: "JSON 格式无效：${e.message ?: "无法读取"}"), e)
            }
            root.checkKeys((context?.getString(com.seb.campustimetable.R.string.validation_top_level) ?: "顶层"), setOf("format", "version", "courses"))
            require(root.string("format", (context?.getString(com.seb.campustimetable.R.string.validation_top_level) ?: "顶层"), required = true) == "campus-timetable") { (context?.getString(com.seb.campustimetable.R.string.validation_format_must_be_campus_timetable) ?: "format 必须是 campus-timetable") }
            require(root.integer("version", (context?.getString(com.seb.campustimetable.R.string.validation_top_level) ?: "顶层")) == 1) { (context?.getString(com.seb.campustimetable.R.string.validation_version_must_be_1) ?: "version 必须是 1") }
            val array = root.array("courses", (context?.getString(com.seb.campustimetable.R.string.validation_top_level) ?: "顶层"))
            require(array.length() in 1..300) { (context?.getString(com.seb.campustimetable.R.string.validation_courses_must_contain_1_300_courses) ?: "courses 须包含 1–300 门课程") }
            val courses = mutableListOf<CourseEntity>()
            val sessions = mutableListOf<CourseSessionEntity>()
            for (i in 0 until array.length()) {
                val at = (context?.getString(com.seb.campustimetable.R.string.validation_course, i + 1) ?: "第 ${i + 1} 门课程")
                val course = array.objectAt(i, "courses")
                course.checkKeys(at, setOf("name", "teacher", "note", "color", "sessions"))
                val name = course.string("name", at, required = true, limit = 200)
                val teacher = course.string("teacher", at, limit = 200)
                val note = course.string("note", at, limit = 2000)
                val colorText = course.string("color", at, limit = 7)
                val color = if (colorText.isEmpty()) colors[i % colors.size] else {
                    require(Regex("^#[0-9a-fA-F]{6}$").matches(colorText)) { (context?.getString(com.seb.campustimetable.R.string.validation_color_must_be_rrggbb, at) ?: "$at 的 color 须为 #RRGGBB") }
                    Color.parseColor(colorText)
                }
                val courseId = UUID.randomUUID().toString()
                courses.add(CourseEntity(courseId, name, teacher, color, note))
                val arrangements = course.array("sessions", at)
                require(arrangements.length() in 1..1000 && sessions.size + arrangements.length() <= 1000) {
                    (context?.getString(com.seb.campustimetable.R.string.validation_must_have_sessions_maximum_1000_sessions_per_file, at) ?: "$at 须有上课安排；整个文件最多 1000 条安排")
                }
                for (j in 0 until arrangements.length()) {
                    val where = (context?.getString(com.seb.campustimetable.R.string.validation_session, at, j + 1) ?: "$at 的第 ${j + 1} 条安排")
                    val session = arrangements.objectAt(j, (context?.getString(com.seb.campustimetable.R.string.validation_sessions, at) ?: "$at 的 sessions"))
                    session.checkKeys(where, setOf("weekday", "startPeriod", "endPeriod", "location", "weeks"))
                    val day = session.integer("weekday", where)
                    val start = session.integer("startPeriod", where)
                    val end = session.integer("endPeriod", where)
                    require(day in 1..7) { (context?.getString(com.seb.campustimetable.R.string.validation_weekday_must_be_1_7, where) ?: "$where 的 weekday 须为 1–7") }
                    require(start in 1..periods.size && end in start..periods.size) {
                        (context?.getString(com.seb.campustimetable.R.string.validation_periods_must_be_within_1_with_end_at_or_after_start, where, periods.size) ?: "$where 的节数须在 1–${periods.size} 内，且结束节数不能早于开始节数")
                    }
                    require(!periods[start - 1].isPlaceholder && !periods[end - 1].isPlaceholder) {
                        (context?.getString(com.seb.campustimetable.R.string.validation_starts_or_ends_in_a_lunch_placeholder_row_adjust_the_periods, where) ?: "$where 的开始或结束节数是午休 / 占位行，请调整课时设置或导入数据")
                    }
                    val location = session.string("location", where, limit = 200)
                    require(session.has("weeks") && !session.isNull("weeks")) { (context?.getString(com.seb.campustimetable.R.string.validation_weeks_is_missing_specify_week_numbers_or_all, where) ?: "$where 缺少 weeks，请明确填写周次或 all") }
                    val weeks = when (val value = session.get("weeks")) {
                        is String -> {
                            require(value == "all") { (context?.getString(com.seb.campustimetable.R.string.validation_the_only_supported_weeks_string_is_all, where) ?: "$where 的 weeks 字符串只能是 all") }
                            emptyList()
                        }
                        is JSONArray -> {
                            require(value.length() in 1..totalWeeks) { (context?.getString(com.seb.campustimetable.R.string.validation_weeks_must_be_a_nonempty_array_within_1, where, totalWeeks) ?: "$where 的 weeks 须为非空周次数组，范围 1–$totalWeeks") }
                            (0 until value.length()).map { index ->
                                val week = value.integerAt(index, (context?.getString(com.seb.campustimetable.R.string.validation_weeks, where) ?: "$where 的 weeks"))
                                require(week in 1..totalWeeks) { (context?.getString(com.seb.campustimetable.R.string.validation_week_is_outside_this_semester_1, where, week, totalWeeks) ?: "$where 的周次 $week 超出当前学期的 1–$totalWeeks 周") }
                                week
                            }.distinct().sorted()
                        }
                        else -> throw IllegalArgumentException((context?.getString(com.seb.campustimetable.R.string.validation_weeks_must_be_an_array_of_week_numbers_or_all, where) ?: "$where 的 weeks 须为周次数组或 all"))
                    }
                    sessions.add(CourseSessionEntity(UUID.randomUUID().toString(), courseId, day, start, end, location, weeks.joinToString(",")))
                }
            }
            return ImportedTimetable(courses, sessions)
        }

        fun validateJson(input: String) {
            JsonReader(StringReader(input)).use { reader ->
                reader.isLenient = false
                fun value(depth: Int) {
                    require(depth <= 32) { (context?.getString(com.seb.campustimetable.R.string.validation_json_nesting_is_too_deep) ?: "JSON 嵌套层级过深") }
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val names = mutableSetOf<String>()
                            while (reader.hasNext()) {
                                val name = reader.nextName()
                                require(names.add(name)) { (context?.getString(com.seb.campustimetable.R.string.validation_duplicate_json_field, name) ?: "JSON 对象存在重复字段：$name") }
                                value(depth + 1)
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) value(depth + 1)
                            reader.endArray()
                        }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> throw IllegalArgumentException((context?.getString(com.seb.campustimetable.R.string.validation_incomplete_json) ?: "JSON 不完整"))
                    }
                }
                value(0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { (context?.getString(com.seb.campustimetable.R.string.validation_extra_content_after_json) ?: "JSON 末尾存在多余内容") }
            }
        }

        private fun JSONObject.checkKeys(where: String, allowed: Set<String>) {
            keys().forEach { require(it in allowed) { (context?.getString(com.seb.campustimetable.R.string.validation_unsupported_field, where, it) ?: "$where 有不支持的字段：$it") } }
        }

        private fun JSONObject.string(key: String, where: String, required: Boolean = false, limit: Int = 200): String {
            if (!has(key)) {
                require(!required) { (context?.getString(com.seb.campustimetable.R.string.validation_missing, where, key) ?: "$where 缺少 $key") }
                return ""
            }
            val value = opt(key)
            require(value is String) { (context?.getString(com.seb.campustimetable.R.string.validation_must_be_a_string, where, key) ?: "$where 的 $key 须为字符串") }
            val result = value.trim()
            require(!required || result.isNotEmpty()) { (context?.getString(com.seb.campustimetable.R.string.validation_must_not_be_empty, where, key) ?: "$where 的 $key 不能为空") }
            require(result.length <= limit) { (context?.getString(com.seb.campustimetable.R.string.validation_must_not_exceed_characters, where, key, limit) ?: "$where 的 $key 最多 $limit 个字符") }
            return result
        }

        private fun JSONObject.integer(key: String, where: String): Int {
            require(has(key)) { (context?.getString(com.seb.campustimetable.R.string.validation_missing, where, key) ?: "$where 缺少 $key") }
            return integerValue(opt(key), (context?.getString(com.seb.campustimetable.R.string.validation_, where, key) ?: "$where 的 $key"))
        }

        private fun JSONObject.array(key: String, where: String): JSONArray {
            val value = opt(key)
            require(value is JSONArray) { (context?.getString(com.seb.campustimetable.R.string.validation_must_be_an_array, where, key) ?: "$where 的 $key 须为数组") }
            return value
        }

        private fun JSONArray.objectAt(index: Int, where: String): JSONObject {
            val value = opt(index)
            require(value is JSONObject) { (context?.getString(com.seb.campustimetable.R.string.validation_item_must_be_an_object, where, index + 1) ?: "$where 中第 ${index + 1} 项须为对象") }
            return value
        }

        private fun JSONArray.integerAt(index: Int, where: String) = integerValue(opt(index), (context?.getString(com.seb.campustimetable.R.string.validation_item, where, index + 1) ?: "$where 中第 ${index + 1} 项"))

        private fun integerValue(value: Any?, where: String): Int {
            require(value is Int || value is Long) { (context?.getString(com.seb.campustimetable.R.string.validation_must_be_an_integer, where) ?: "$where 须为整数") }
            val number = (value as Number).toLong()
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { (context?.getString(com.seb.campustimetable.R.string.validation_is_too_large, where) ?: "$where 数值过大") }
            return number.toInt()
        }
    }
}
