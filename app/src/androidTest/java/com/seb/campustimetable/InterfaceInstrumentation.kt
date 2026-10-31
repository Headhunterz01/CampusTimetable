package com.seb.campustimetable

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatDelegate
import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.defaultPeriods
import com.seb.campustimetable.data.validatePeriods
import com.seb.campustimetable.data.backup.TimetableBackup
import com.seb.campustimetable.data.importing.TimetableImport
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** Exercises actual Activity locale recreation and animation preferences in an isolated package. */
class InterfaceInstrumentation : Instrumentation() {
    private val current = AtomicReference<MainActivity>()
    private fun field(a: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun call(a: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredMethod(name, if (value is Int) Int::class.javaPrimitiveType!! else String::class.java)
            .apply { isAccessible = true }.invoke(a, value)
    }
    private fun onMain(block: (MainActivity) -> Unit) = runOnMainSync { block(checkNotNull(current.get())) }
    private fun await(message: String, test: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            runOnMainSync { current.get()?.let { ready = test(it) } }
            if (ready) return
            SystemClock.sleep(20)
        }
        error("Timed out: $message")
    }
    private fun scroll(a: MainActivity) = (field(a, "settingsOverlay") as LinearLayout).getChildAt(1) as ScrollView
    private fun laidOut(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).isLaidOut
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) { if (a is MainActivity) current.set(a) }
            override fun onActivityDestroyed(a: Activity) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        }
        try {
            check(targetContext.packageName.endsWith(".verification"))
            val scales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            val originalScales = scales.map { Settings.Global.getString(targetContext.contentResolver, it) }
            InterfacePreferences.setAnimationsEnabled(targetContext, false)
            targetContext.getSharedPreferences("reminder_intro", 0).edit().putBoolean("shown", true).commit()
            (targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycle)
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("initial data") { field(it, "semesterLoaded") == true && field(it, "periodsLoaded") == true && laidOut(it) }
            val original = runBlocking { TimetableBackup.capture(targetContext) }
            onMain { a ->
                call(a, "changeWeek", -99)
                call(a, "changeWeek", 1)
                check(field(a, "selectedWeek") == 2)
                check(field(a, "weekMotionRunning") == false)
                call(a, "openSettingsPage", "settings")
                call(a, "openSettingsPage", "theme")
                check(field(a, "renderedSettingsPage") == "theme")
                check(field(a, "pageMotionRunning") == false)
                check(field(a, "motionShield") == null)
            }
            waitForIdleSync()
            onMain { scroll(it).scrollTo(0, 40) }
            var old: MainActivity? = null
            onMain { a -> old = a; call(a, "applyLanguage", "en") }
            await("English recreation") { it !== old && laidOut(it) && field(it, "periodsLoaded") == true }
            waitForIdleSync()
            onMain { a ->
                check(a.getString(R.string.settings) == "Settings")
                check(a.getString(R.string.timetable_title) == "My timetable")
                check(AppCompatDelegate.getApplicationLocales().toLanguageTags() == "en")
                check(field(a, "renderedSettingsPage") == "theme")
                check(scroll(a).scrollY == 40) { "Scroll not restored: ${scroll(a).scrollY}" }
                check(field(a, "selectedWeek") == 2)
                check(field(a, "themeApplying") == false)
                check(!InterfacePreferences.animationsEnabled(a))
                check(InterfacePreferences.localized(a.applicationContext).getString(R.string.settings) == "Settings")
                check(validatePeriods(listOf(PeriodSlot("10:00", "09:00")), a)?.startsWith("Period 1") == true)
                val error = runCatching { TimetableImport.parse("{\"format\":\"wrong\"}", defaultPeriods, 19, a) }.exceptionOrNull()
                check(error?.message == "format must be campus-timetable") { error?.message.orEmpty() }
                old = a
                call(a, "applyTheme", "oled")
            }
            await("theme while animations disabled") { it !== old && laidOut(it) && field(it, "periodsLoaded") == true }
            onMain { a ->
                check(field(a, "themeApplying") == false)
                check(field(a, "renderedSettingsPage") == "theme")
                check(scroll(a).scrollY == 40)
                old = a
                a.recreate()
            }
            await("ordinary recreation") { it !== old && laidOut(it) }
            onMain { a ->
                check(a.getString(R.string.settings) == "Settings")
                check(!InterfacePreferences.animationsEnabled(a))
                old = a
                call(a, "applyLanguage", "zh-Hans")
            }
            await("Chinese recreation") { it !== old && laidOut(it) && field(it, "periodsLoaded") == true }
            onMain { a ->
                check(a.getString(R.string.settings) == "设置")
                check(field(a, "renderedSettingsPage") == "theme")
                call(a, "settingsAction", "animations")
                check(InterfacePreferences.animationsEnabled(a))
                call(a, "openSettingsPage", "about")
                check(field(a, "pageMotionRunning") == android.animation.ValueAnimator.areAnimatorsEnabled())
            }
            await("enabled motion cleanup") { field(it, "pageMotionRunning") == false }
            val after = runBlocking { TimetableBackup.capture(targetContext) }
            check(original.courses == after.courses && original.sessions == after.sessions && original.semester == after.semester)
            check(originalScales == scales.map { Settings.Global.getString(targetContext.contentResolver, it) })
            result.putString("stream", "PASS: English/Chinese locale recreation, page/week/scroll retention, localized dates and errors, receiver locale, disabled/enabled animations, theme and ordinary recreation, unchanged course data and system animation settings\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            (targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }
}
