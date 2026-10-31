package com.seb.campustimetable

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import com.seb.campustimetable.data.backup.TimetableBackup
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle/rapid-input regression checks; deliberately refuses to run against the user's app. */
class MotionInstrumentation : Instrumentation() {
    private val current = AtomicReference<MainActivity>()
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(activity: MainActivity, name: String, argument: Any) {
        val type = if (argument is Int) Int::class.javaPrimitiveType!! else String::class.java
        MainActivity::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, argument)
    }
    private fun onMain(block: (MainActivity) -> Unit) { runOnMainSync { block(checkNotNull(current.get())) } }
    private fun await(message: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            runOnMainSync { current.get()?.let { ready = predicate(it) } }
            if (ready) return
            SystemClock.sleep(20)
        }
        error("Timed out: $message")
    }
    private fun idle() = await("animation cleanup") { field(it, "pageMotionRunning") == false &&
        field(it, "weekMotionRunning") == false && field(it, "themeApplying") == false }
    private fun root(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
    private fun clean(activity: MainActivity, page: String?) {
        check(field(activity, "renderedSettingsPage") == page)
        check(field(activity, "motionShield") == null)
        check(root(activity).childCount == if (page == null) 2 else 3) { "A page/shield was left attached" }
        val views = listOf(root(activity).getChildAt(0), activity.findViewById<View>(R.id.addCourseFab),
            activity.findViewById<View>(R.id.weekHeader), activity.findViewById<View>(R.id.timetableScroll)) +
            listOfNotNull(field(activity, "settingsOverlay") as? View)
        views.forEach { check(it.alpha == 1f && it.translationX == 0f) { "A transform was left behind" } }
    }
    private fun back(activity: MainActivity) = (field(activity, "settingsBack") as OnBackPressedCallback).handleOnBackPressed()
    private fun pageScroll(activity: MainActivity) = (field(activity, "settingsOverlay") as LinearLayout).getChildAt(1) as ScrollView
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) current.set(activity) }
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
        try {
            check(targetContext.packageName.endsWith(".verification")) { "Use -PisolatedVerification=true" }
            targetContext.getSharedPreferences("reminder_intro", 0).edit().putBoolean("shown", true).commit()
            (targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycle)
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("initial data") { field(it, "semesterLoaded") == true && field(it, "periodsLoaded") == true && root(it).isLaidOut }
            val before = runBlocking { TimetableBackup.capture(targetContext) }
            onMain { a ->
                call(a, "openSettingsPage", "settings")
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    check(field(a, "pageMotionRunning") == true)
                    call(a, "openSettingsPage", "about")
                    back(a)
                    check((field(a, "settingsPages") as List<*>).size == 1)
                }
            }
            idle()
            onMain { a -> clean(a, "settings"); pageScroll(a).scrollTo(0, 200); call(a, "openSettingsPage", "theme") }
            idle()
            onMain { a -> clean(a, "theme"); back(a) }
            idle()
            onMain { a -> clean(a, "settings"); check(pageScroll(a).scrollY == 200); back(a) }
            idle()
            onMain { clean(it, null) }
            var previous = 0
            onMain { a ->
                previous = field(a, "selectedWeek") as Int
                call(a, "changeWeek", 1)
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) repeat(8) { call(a, "changeWeek", 1) }
            }
            idle()
            onMain { a -> check(field(a, "selectedWeek") == previous + 1); clean(a, null); call(a, "changeWeek", -99) }
            idle()
            onMain { a ->
                check(field(a, "selectedWeek") == 1)
                call(a, "changeWeek", -1)
                check(field(a, "weekMotionRunning") == false)
                call(a, "changeWeek", 99)
            }
            idle()
            onMain { a ->
                val term = field(a, "semester") as com.seb.campustimetable.data.local.SemesterEntity
                check(field(a, "selectedWeek") == term.totalWeeks)
                call(a, "changeWeek", 1)
                check(field(a, "weekMotionRunning") == false)
                call(a, "changeWeek", -1)
            }
            idle()
            var old: MainActivity? = null
            var expectedWeek = 0
            onMain { a ->
                old = a
                expectedWeek = (field(a, "selectedWeek") as Int) - 1
                call(a, "changeWeek", -1)
                a.recreate()
            }
            await("recreate during week transition") { it !== old && !it.isDestroyed && root(it).isLaidOut }
            idle()
            onMain { a -> check(field(a, "selectedWeek") == expectedWeek); clean(a, null) }
            onMain { a -> old = a; call(a, "openSettingsPage", "settings"); a.recreate() }
            await("recreate during page transition") { it !== old && !it.isDestroyed && root(it).isLaidOut }
            idle()
            onMain { a -> clean(a, "settings"); call(a, "openSettingsPage", "theme") }
            idle()
            listOf("light", "oled", "monet", "system").forEach { theme ->
                var changed = false
                onMain { a -> old = a; changed = field(a, "activeTheme") != theme; call(a, "applyTheme", theme) }
                if (changed) await("theme recreation: $theme") { it !== old && root(it).isLaidOut }
                idle()
                onMain { a -> check(field(a, "activeTheme") == theme); clean(a, "theme") }
            }
            onMain { back(it) }; idle()
            onMain { back(it) }; idle()
            onMain { a -> call(a, "openSettingsPage", "about") }; idle()
            onMain { a -> call(a, "openSettingsPage", "components") }; idle()
            onMain { a -> call(a, "openSettingsPage", "license") }; idle()
            onMain { a -> clean(a, "license"); back(a) }; idle()
            onMain { a -> clean(a, "components"); back(a) }; idle()
            onMain { a -> clean(a, "about"); back(a) }; idle()
            onMain { a -> clean(a, null); check(!(field(a, "settingsBack") as OnBackPressedCallback).isEnabled) }
            val after = runBlocking { TimetableBackup.capture(targetContext) }
            check(before.courses == after.courses && before.sessions == after.sessions && before.semester == after.semester)
            result.putString("stream", "PASS: page enter/return, scroll restoration, rapid taps, week boundaries, mid-transition recreation, four themes, back stack, transform cleanup and unchanged course data\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            (targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }
}
