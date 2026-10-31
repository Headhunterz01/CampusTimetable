package com.seb.campustimetable.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsStore by preferencesDataStore(name = "settings")
val themeKey = stringPreferencesKey("theme")
// -1 disables reminders; otherwise the value is minutes before class.
val reminderMinutesKey = intPreferencesKey("reminder_minutes")
const val DEFAULT_REMINDER_MINUTES = 10
val courseReminderMinutesKey = stringPreferencesKey("course_reminder_minutes")

fun decodeCourseReminders(encoded: String?): Map<String, Int> = try {
    val json = org.json.JSONObject(encoded ?: "{}")
    json.keys().asSequence().associateWith { json.getInt(it) }.filterValues { it in -1..10080 }
} catch (_: Exception) { emptyMap() }
