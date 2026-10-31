package com.seb.campustimetable

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/** Full-screen destinations; settings actions reuse the existing validated editors. */
class SettingsPages(
    private val context: Context,
    private val activeTheme: String,
    private val summary: Map<String, String>,
    private val action: (String) -> Unit,
    private val selectTheme: (String) -> Unit,
    private val goBack: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun color(attr: Int) = MaterialColors.getColor(context, attr, Color.GRAY)
    private val onSurface get() = color(com.google.android.material.R.attr.colorOnSurface)
    private val secondary get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val systemDark get() = context.applicationContext.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun label(value: String, size: Float = 14f, muted: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (muted) secondary else onSurface)
    }
    private fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun section(parent: LinearLayout, title: String) {
        parent.addView(label(title, 13f, true).apply { setPadding(dp(4), dp(22), 0, dp(8)) })
    }
    private fun card() = MaterialCardView(context).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer))
    }
    private fun row(parent: LinearLayout, title: String, detail: String, id: String) {
        val content = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(16), dp(14))
            minimumHeight = dp(76)
        }
        val text = vertical()
        text.addView(label(title, 16f))
        text.addView(label(detail, 13f, true).apply { setPadding(0, dp(5), 0, 0) })
        content.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        parent.addView(card().apply {
            addView(content)
            isClickable = true; isFocusable = true
            contentDescription = "$title，$detail"
            setOnClickListener { action(id) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    fun create(page: String): View {
        val title = when (page) {
            "theme" -> context.getString(R.string.ui_theme_and_appearance)
            "about" -> context.getString(R.string.ui_about)
            "components" -> context.getString(R.string.ui_open_source_components)
            "license" -> "Apache License 2.0"
            "source" -> context.getString(R.string.ui_source_code_and_feedback)
            else -> context.getString(R.string.ui_settings)
        }
        val root = vertical().apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(MaterialToolbar(context).apply {
            this.title = title
            setTitleTextColor(onSurface)
            setNavigationIcon(R.drawable.ic_chevron_left)
            setNavigationIconTint(onSurface)
            navigationContentDescription = context.getString(R.string.ui_back)
            setNavigationOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(-1, dp(64)))
        val content = vertical().apply { setPadding(dp(20), 0, dp(20), dp(32)) }
        root.addView(ScrollView(context).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            "theme" -> themes(content)
            "about" -> about(content)
            "components" -> components(content)
            "license" -> content.addView(label(context.assets.open("Apache-2.0.txt").bufferedReader().use { it.readText() }, 13f).apply { setTextIsSelectable(true) })
            "source" -> source(content)
            else -> settings(content)
        }
        return root
    }

    private fun settings(content: LinearLayout) {
        content.addView(label(context.getString(R.string.ui_make_your_timetable_feel_familiar), 14f, true).apply { setPadding(dp(4), dp(6), 0, dp(4)) })
        section(content, context.getString(R.string.ui_appearance))
        row(content, context.getString(R.string.ui_theme_and_appearance), themeName(activeTheme), "theme")
        row(content, context.getString(R.string.language), languageName(), "language")
        row(content, context.getString(R.string.ui_course_color_presets), context.getString(R.string.ui_color_previews_rgb_names_and_order), "palette")
        section(content, context.getString(R.string.ui_timetable))
        row(content, context.getString(R.string.ui_semester_settings), summary["semester"].orEmpty(), "semester")
        row(content, context.getString(R.string.ui_periods_and_times), summary["periods"].orEmpty(), "periods")
        section(content, context.getString(R.string.ui_reminders))
        row(content, context.getString(R.string.ui_default_course_reminder), summary["reminders"].orEmpty(), "reminders")
        content.addView(label(context.getString(R.string.ui_set_a_course_reminder_in_course_details_more), 12f, true).apply { setPadding(dp(8), dp(4), dp(8), 0) })
        section(content, context.getString(R.string.ui_data))
        row(content, context.getString(R.string.ui_backup_restore_and_migration), context.getString(R.string.ui_save_courses_periods_theme_and_reminders), "backup")
        row(content, context.getString(R.string.ui_import_timetable), context.getString(R.string.ui_append_or_replace_courses_from_json_files_or), "import")
        section(content, context.getString(R.string.ui_project))
        row(content, context.getString(R.string.ui_about_campustimetable), context.getString(R.string.ui_version_github_open_source_components), "about")
    }

    private fun themeName(value: String) = when (value) {
        "light" -> context.getString(R.string.ui_light)
        "oled" -> context.getString(R.string.ui_oled_black)
        "monet" -> context.getString(R.string.ui_monet_dynamic_color)
        else -> context.getString(R.string.ui_follow_system)
    }

    private fun languageName(): String = when (AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
        "en" -> "English"
        "zh-Hans" -> "简体中文"
        else -> context.getString(R.string.follow_system)
    }

    private fun themes(content: LinearLayout) {
        content.addView(label(context.getString(R.string.ui_choose_an_appearance), 24f).apply { setPadding(dp(4), dp(10), 0, dp(8)) })
        content.addView(label(context.getString(R.string.ui_tap_to_apply_all_previews_show_the_same_sampl), 14f, true).apply { setPadding(dp(4), 0, dp(4), dp(18)) })
        val modes = listOf("light", "oled", "system", "monet")
        modes.chunked(2).forEach { pair ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            pair.forEachIndexed { index, mode ->
                val selected = activeTheme == mode
                val body = vertical().apply { setPadding(dp(10), dp(10), dp(10), dp(14)) }
                body.addView(ThemePreviewView(context, mode), LinearLayout.LayoutParams(-1, dp(122)))
                body.addView(label((if (selected) "✓  " else "") + themeName(mode), 15f).apply { setPadding(dp(4), dp(12), dp(4), 0) })
                body.addView(label(when (mode) {
                    "light" -> context.getString(R.string.ui_bright_and_clear)
                    "oled" -> context.getString(R.string.ui_pure_black_background)
                    "system" -> context.getString(R.string.ui_follow_system_light_dark_mode)
                    else -> if (DynamicColors.isDynamicColorAvailable()) context.getString(R.string.ui_colors_from_your_system_wallpaper) else context.getString(R.string.ui_default_colors_on_this_device)
                }, 12f, true).apply { setPadding(dp(4), dp(5), dp(4), 0) })
                val tile = card().apply {
                    addView(body)
                    strokeWidth = if (selected) dp(2) else dp(1)
                    strokeColor = color(if (selected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant)
                    isClickable = true; isFocusable = true; isSelected = selected
                    contentDescription = "${themeName(mode)}, ${context.getString(if (selected) R.string.selected else R.string.not_selected)}"
                    setOnClickListener { selectTheme(mode) }
                }
                line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        section(content, context.getString(R.string.interaction))
        val motionSwitch = MaterialSwitch(context).apply {
            text = context.getString(R.string.app_animations)
            isChecked = InterfacePreferences.animationsEnabled(context)
            setTextColor(onSurface)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnCheckedChangeListener { _, _ -> action("animations") }
        }
        content.addView(card().apply { addView(motionSwitch, LinearLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
        content.addView(label(context.getString(R.string.app_animations_detail), 13f, true).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
        row(content, context.getString(R.string.language), languageName(), "language")
        section(content, context.getString(R.string.ui_current_appearance))
        content.addView(label(when (activeTheme) {
            "monet" -> if (DynamicColors.isDynamicColorAvailable()) context.getString(R.string.ui_buttons_accents_and_dividers_use_system_dynam) else context.getString(R.string.ui_dynamic_color_is_unavailable_on_this_device_d)
            "system" -> context.getString(R.string.ui_the_system_is_currently_in_mode_the_app_follo, if (systemDark) context.getString(R.string.ui_dark) else context.getString(R.string.ui_light))
            "oled" -> context.getString(R.string.ui_pure_black_page_backgrounds_for_oled_screens)
            else -> context.getString(R.string.ui_always_use_light_page_backgrounds)
        }, 14f, true))
    }

    private fun about(content: LinearLayout) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val hero = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(24), 0, dp(18)) }
        hero.addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = context.getString(R.string.ui_app_icon) }, LinearLayout.LayoutParams(dp(76), dp(76)))
        hero.addView(label("CampusTimetable", 24f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) })
        hero.addView(label("${info.versionName} · ${context.getString(if (debug) R.string.debug_build else R.string.release_build)}", 13f, true).apply { gravity = Gravity.CENTER })
        content.addView(hero)
        content.addView(label(context.getString(R.string.ui_a_timetable_of_your_own), 20f).apply { setPadding(dp(4), dp(8), 0, dp(10)) })
        content.addView(label(context.getString(R.string.ui_save_courses_locally_view_weekly_schedules_an), 14f, true).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        section(content, context.getString(R.string.ui_open_source_and_community))
        val repository = context.getString(R.string.project_repository_url)
        row(content, context.getString(R.string.ui_github_source_and_feedback), if (repository.isBlank()) context.getString(R.string.ui_repository_coming_soon) else repository.removePrefix("https://"), "source")
        row(content, context.getString(R.string.ui_project_license), context.getString(R.string.project_license_name).ifBlank { context.getString(R.string.ui_not_yet_chosen) }, "source")
        row(content, context.getString(R.string.ui_open_source_components), "Material Components · AndroidX · Kotlin", "components")
        section(content, context.getString(R.string.ui_data_and_permissions))
        content.addView(label(context.getString(R.string.ui_courses_and_settings_are_stored_on_this_devic), 13f, true).apply { setPadding(dp(4), 0, dp(4), 0) })
    }

    private fun source(content: LinearLayout) {
        val repository = context.getString(R.string.project_repository_url)
        if (repository.isBlank()) {
            section(content, "GitHub")
            content.addView(label(context.getString(R.string.ui_repository_coming_soon), 24f))
            content.addView(label(context.getString(R.string.ui_the_project_is_preparing_to_go_open_source_on), 14f, true).apply { setPadding(0, dp(12), 0, dp(12)) })
        } else {
            section(content, "GitHub")
            row(content, context.getString(R.string.ui_view_source_code), repository, "open_repository")
            row(content, context.getString(R.string.ui_feedback), context.getString(R.string.ui_report_a_problem_or_suggest_an_idea), "open_issues")
            row(content, context.getString(R.string.ui_releases), context.getString(R.string.ui_version_history_and_apk_downloads), "open_releases")
        }
        section(content, context.getString(R.string.ui_project_license))
        content.addView(label(context.getString(R.string.project_license_name).ifBlank { context.getString(R.string.ui_the_project_license_has_not_been_chosen_refer) }, 14f, true))
    }

    private fun components(content: LinearLayout) {
        section(content, context.getString(R.string.ui_thanks_to_the_open_source_community))
        content.addView(label(context.getString(R.string.ui_the_app_directly_uses_these_components_depend), 14f, true))
        row(content, "Material Components for Android", context.getString(R.string.ui_material_design_ui_apache_2_0), "license")
        row(content, "AndroidX", "AppCompat、Core、Room、DataStore、Lifecycle · Apache 2.0", "license")
        row(content, context.getString(R.string.ui_kotlin_and_kotlin_coroutines), context.getString(R.string.ui_language_and_coroutine_support_apache_2_0), "license")
    }
}

/** Vector-drawn miniature timetable: no image assets, uses the actual theme resources. */
private class ThemePreviewView(context: Context, private val mode: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val systemDark = context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun themed(dark: Boolean, monet: Boolean): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val wrapper = ContextThemeWrapper(context.createConfigurationContext(configuration), if (dark && !monet) R.style.Theme_CampusTimetable_Oled else R.style.Theme_CampusTimetable)
        return if (monet) DynamicColors.wrapContextIfAvailable(wrapper) else wrapper
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 140f, height / 122f)
        canvas.save(); canvas.translate((width - 140 * scale) / 2, 0f); canvas.scale(scale, scale)
        if (mode == "system") {
            canvas.save(); canvas.clipRect(0f, 0f, 70f, 122f); miniature(canvas, false, false); canvas.restore()
            canvas.save(); canvas.clipRect(70f, 0f, 140f, 122f); miniature(canvas, true, false); canvas.restore()
        } else miniature(canvas, mode == "oled" || mode == "monet" && systemDark, mode == "monet")
        canvas.restore()
    }
    private fun miniature(canvas: Canvas, dark: Boolean, monet: Boolean) {
        val theme = themed(dark, monet)
        fun c(attr: Int) = MaterialColors.getColor(theme, attr, Color.GRAY)
        fun rounded(left: Float, top: Float, right: Float, bottom: Float, fill: Int, radius: Float = 5f) {
            paint.color = fill; canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
        }
        val surface = c(com.google.android.material.R.attr.colorSurface)
        val foreground = c(com.google.android.material.R.attr.colorOnSurface)
        val accent = c(androidx.appcompat.R.attr.colorPrimary)
        val container = c(com.google.android.material.R.attr.colorSecondaryContainer)
        rounded(0f, 0f, 140f, 122f, surface, 10f)
        paint.color = foreground; paint.textSize = 8f; paint.isFakeBoldText = true
        canvas.drawText(context.getString(R.string.ui_my_timetable), 9f, 15f, paint); paint.isFakeBoldText = false
        rounded(8f, 22f, 27f, 36f, container)
        rounded(113f, 22f, 132f, 36f, container)
        paint.color = c(com.google.android.material.R.attr.colorOnSecondaryContainer); paint.textSize = 11f
        canvas.drawText("‹", 15f, 33f, paint); canvas.drawText("›", 120f, 33f, paint)
        paint.color = foreground; paint.textSize = 7f; canvas.drawText(context.getString(R.string.ui_week_9), 56f, 32f, paint)
        val days = listOf(context.getString(R.string.ui_m), context.getString(R.string.ui_t), context.getString(R.string.ui_w), context.getString(R.string.ui_t_2), context.getString(R.string.ui_f))
        days.forEachIndexed { index, text ->
            if (monet && index == 2) rounded(64f, 40f, 84f, 51f, c(com.google.android.material.R.attr.colorPrimaryContainer), 4f)
            paint.color = if (index == 2) accent else foreground; paint.textSize = 6f
            canvas.drawText(text, 19f + index * 25f, 48f, paint)
        }
        val colors = listOf(0xff6f85d7.toInt(), 0xff3db4aa.toInt(), 0xffe19428.toInt())
        listOf(floatArrayOf(12f, 56f, 32f, 83f), floatArrayOf(62f, 56f, 82f, 72f), floatArrayOf(87f, 76f, 107f, 103f), floatArrayOf(37f, 88f, 57f, 111f)).forEachIndexed { i, r ->
            rounded(r[0], r[1], r[2], r[3], colors[i % colors.size], 4f)
            paint.color = Color.BLACK; paint.textSize = 5f
            canvas.drawText(if (i % 2 == 0) context.getString(R.string.ui_class) else context.getString(R.string.ui_study), r[0] + 3f, r[1] + 9f, paint)
        }
        rounded(113f, 98f, 132f, 117f, c(com.google.android.material.R.attr.colorPrimaryContainer), 7f)
        paint.color = c(com.google.android.material.R.attr.colorOnPrimaryContainer); paint.textSize = 12f
        canvas.drawText("+", 118f, 112f, paint)
    }
}
