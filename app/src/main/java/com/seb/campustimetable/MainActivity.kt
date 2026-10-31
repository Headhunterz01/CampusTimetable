package com.seb.campustimetable

import com.seb.campustimetable.data.backup.TimetableBackup
import com.seb.campustimetable.data.courseReminderMinutesKey
import com.seb.campustimetable.data.decodeCourseReminders
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import android.os.Bundle
import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.widget.TextView
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.datastore.preferences.core.edit
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.seb.campustimetable.data.local.AppDatabase
import com.seb.campustimetable.data.local.CourseSessionRow
import com.seb.campustimetable.data.local.CourseEntity
import com.seb.campustimetable.data.local.CourseSessionEntity
import com.seb.campustimetable.data.local.SemesterEntity
import com.seb.campustimetable.data.model.CourseBlock
import com.seb.campustimetable.data.model.SampleSchedule
import com.seb.campustimetable.data.repository.ScheduleRepository
import com.seb.campustimetable.data.importing.ImportedTimetable
import com.seb.campustimetable.data.importing.TimetableImport
import com.seb.campustimetable.data.settingsStore
import com.seb.campustimetable.data.PeriodSlot
import com.seb.campustimetable.data.defaultPeriods
import com.seb.campustimetable.data.decodePeriods
import com.seb.campustimetable.data.encodePeriods
import com.seb.campustimetable.data.periodLabel
import com.seb.campustimetable.data.periodScheduleKey
import com.seb.campustimetable.data.validatePeriods
import com.seb.campustimetable.data.themeKey
import com.seb.campustimetable.data.reminderMinutesKey
import com.seb.campustimetable.data.DEFAULT_REMINDER_MINUTES
import com.seb.campustimetable.reminder.Reminders
import com.seb.campustimetable.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private enum class PageMotion { NONE, ENTER, RETURN }
    private var pageMotionRunning = false
    private var weekMotionRunning = false
    private var themeApplying = false
    private var themeFadeOnRecreate = false
    private var finishPageMotion: (() -> Unit)? = null
    private var finishWeekMotion: (() -> Unit)? = null
    private var finishThemeMotion: (() -> Unit)? = null
    private var motionShield: View? = null
    private var settingsRefreshPending = false
    private var destroying = false
    private val enterCurve = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val exitCurve = PathInterpolator(0.4f, 0f, 1f, 1f)
    private val motionBusy get() = pageMotionRunning || weekMotionRunning || themeApplying
    private val settingsPages = mutableListOf<String>()
    private val settingsScrolls = mutableMapOf<String, Int>()
    private var renderedSettingsPage: String? = null
    private var settingsScrollReady = false
    private var settingsOverlay: android.view.View? = null
    private var defaultReminderMinutes = DEFAULT_REMINDER_MINUTES
    private lateinit var settingsBack: androidx.activity.OnBackPressedCallback
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ScheduleRepository
    private var semester = SemesterEntity(name = "2026 秋季学期", startDateEpochDay = SampleSchedule.semesterStart.toEpochDay())
    private var selectedWeek = 1
    private var semesterLoaded = false
    private var restoredWeek = false
    private var lastSeenDate = LocalDate.now()
    private var activeTheme = "system"
    private var periods: List<PeriodSlot> = defaultPeriods
    private var periodsLoaded = false
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = TimetableBackup.encode(TimetableBackup.capture(applicationContext))
                    require(data.toByteArray(Charsets.UTF_8).size <= TimetableBackup.MAX_BYTES) { getString(R.string.ui_backup_exceeds_16_mb) }
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                }
                Toast.makeText(this@MainActivity, getString(R.string.ui_full_backup_saved), Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showBackupError(e) }
        }
    }
    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            require(output.size() + count <= TimetableBackup.MAX_BYTES) { getString(R.string.ui_backup_must_not_exceed_16_mb) }
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    }
                    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    TimetableBackup.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString(), this@MainActivity)
                }
                MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.ui_restore_full_backup))
                    .setMessage(getString(R.string.ui_courses_sessions_n_weeks_nfirst_week_n_nthis, data.courses.size, data.sessions.size, data.semester.name, data.semester.totalWeeks, LocalDate.ofEpochDay(data.semester.startDateEpochDay)))
                    .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_restore)) { _, _ -> lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { TimetableBackup.restore(applicationContext, data) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { showBackupError(e); return@launch }
                        try { Reminders.reschedule(applicationContext) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { Toast.makeText(this@MainActivity, getString(R.string.ui_data_restored_please_check_reminder_settings), Toast.LENGTH_LONG).show() }
                        Toast.makeText(this@MainActivity, getString(R.string.ui_backup_restored), Toast.LENGTH_LONG).show()
                        recreate()
                    } }.show().also(::styleImportDialogButtons)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showBackupError(e) }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readImportDocument(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            explainExactAlarmIfNeeded()
        } else {
            Toast.makeText(this, getString(R.string.ui_you_can_enable_notifications_in_reminder_sett), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activeTheme = getSharedPreferences("appearance_cache", MODE_PRIVATE).getString("theme", "system") ?: "system"
        delegate.localNightMode = when (activeTheme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "oled" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (activeTheme == "oled") setTheme(R.style.Theme_CampusTimetable_Oled)
        super.onCreate(savedInstanceState)
        InterfacePreferences.setLanguage(this, AppCompatDelegate.getApplicationLocales().toLanguageTags())
        if (activeTheme == "monet") DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsPages.addAll(savedInstanceState?.getStringArrayList("settingsPages").orEmpty())
        savedInstanceState?.getBundle("settingsScrolls")?.let { values ->
            values.keySet().forEach { settingsScrolls[it] = values.getInt(it) }
        }
        settingsBack = object : androidx.activity.OnBackPressedCallback(settingsPages.isNotEmpty()) {
            override fun handleOnBackPressed() {
                if (motionBusy) return
                saveSettingsScroll()
                if (settingsPages.isNotEmpty()) settingsPages.removeAt(settingsPages.lastIndex)
                renderSettingsPage(PageMotion.RETURN)
            }
        }
        onBackPressedDispatcher.addCallback(this, settingsBack)
        if (activeTheme == "monet") {
            binding.weekHeader.useMonetAccent = true
            binding.timetable.useMonetDividers = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        repository = ScheduleRepository(AppDatabase.get(this).courseDao())

        restoredWeek = savedInstanceState?.containsKey("selectedWeek") == true
        selectedWeek = savedInstanceState?.getInt("selectedWeek") ?: currentWeek() ?: 1
        binding.weekHeader.weekNumber = selectedWeek
        binding.timetable.selectedWeek = selectedWeek
        updateWeekTitle()

        binding.previousWeek.setOnClickListener { changeWeek(-1) }
        binding.nextWeek.setOnClickListener { changeWeek(1) }
        binding.addCourseFab.setOnClickListener { if (!motionBusy) showCourseEditor(null) }
        binding.timetable.onWeekSwipe = ::changeWeek
        binding.timetable.onCourseClick = { if (!motionBusy) showCourseDetails(it) }
        binding.timetable.onCourseConflictClick = conflict@ { courses ->
            if (motionBusy) return@conflict
            val labels = courses.map { getString(R.string.ui_periods, it.name, it.startPeriod, it.endPeriod) }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_overlapping_courses))
                .setItems(labels) { _, index -> showCourseDetails(courses[index]) }
                .setNegativeButton(getString(R.string.ui_cancel), null).show()
        }
        binding.topAppBar.setOnMenuItemClickListener { item ->
            if (motionBusy) return@setOnMenuItemClickListener true
            when (item.itemId) {
                R.id.action_more -> { showQuickActions(); true }
                else -> false
            }
        }
        renderSettingsPage()
        if (savedInstanceState?.getBoolean("themeFadeOnRecreate") == true) fadeRecreatedTheme()

        lifecycleScope.launch {
            val settings = settingsStore.data.first()
            val storedTheme = settings[themeKey] ?: "system"
            if (storedTheme != activeTheme) {
                getSharedPreferences("appearance_cache", MODE_PRIVATE).edit().putString("theme", storedTheme).apply()
                recreate()
                return@launch
            }
            defaultReminderMinutes = settings[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES
            periods = decodePeriods(settings[periodScheduleKey])
            periodsLoaded = true
            binding.timetable.periods = periods
            repository.seedIfEmpty()
            Reminders.reschedule(applicationContext)
            showReminderIntroductionIfNeeded(settings[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    settingsStore.data.collect { preferences ->
                        periods = decodePeriods(preferences[periodScheduleKey])
                        binding.timetable.periods = periods
                        defaultReminderMinutes = preferences[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES
                        if (settingsPages.lastOrNull() == "settings") renderSettingsPage()
                    }
                }
                launch {
                    repository.schedule.collect { rows ->
                        binding.timetable.courseBlocks = rows.map(::toCourseBlock)
                    }
                }
                launch {
                    repository.semester.collect { saved ->
                        if (saved != null) applySemester(saved)
                    }
                }
            }
        }
    }

    private fun showBackupError(error: Exception) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_backup_or_restore_failed))
            .setMessage(error.message ?: getString(R.string.ui_unable_to_read_or_write_the_file_please_selec))
            .setPositiveButton(getString(R.string.ui_ok), null).show().also(::styleImportDialogButtons)
    }

    private fun showBackupOptions() {
        if (!importReady()) return
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_backup_restore_and_migration))
            .setItems(arrayOf(getString(R.string.ui_export_full_backup), getString(R.string.ui_restore_from_backup), getString(R.string.ui_moving_from_debug_to_release))) { _, which ->
                when (which) {
                    0 -> exportBackup.launch("CampusTimetable-${LocalDate.now()}.json")
                    1 -> restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    2 -> MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_move_to_the_release_version))
                        .setMessage(getString(R.string.ui_1_export_a_full_backup_from_this_version_to_d))
                        .setPositiveButton(getString(R.string.ui_ok), null).show().also(::styleImportDialogButtons)
                }
            }.setNegativeButton(getString(R.string.close), null).show().also(::styleImportDialogButtons)
    }

    private fun shareTimetable() {
        if (!importReady()) return
        try {
            val width = binding.timetable.width
            val headerHeight = binding.weekHeader.height
            val titleHeight = (72 * resources.displayMetrics.density).toInt()
            val height = titleHeight + headerHeight + binding.timetable.height
            require(width > 0 && height > 0) { getString(R.string.ui_the_timetable_is_not_ready_yet) }
            val scale = minOf(1f, 4096f / height, 2048f / width)
            val bitmap = android.graphics.Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.drawColor(com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface))
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                    textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
                }
                canvas.drawText(getString(R.string.ui_week, semester.name, selectedWeek), 16 * resources.displayMetrics.density, titleHeight * 0.65f, paint)
                canvas.translate(0f, titleHeight.toFloat())
                binding.weekHeader.draw(canvas)
                canvas.translate(0f, headerHeight.toFloat())
                binding.timetable.draw(canvas)
                val directory = java.io.File(cacheDir, "shared_timetables").apply { mkdirs() }
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
                val file = java.io.File(directory, "week-$selectedWeek-${System.currentTimeMillis()}.png")
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                val share = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(getString(R.string.ui_timetable_image), uri) }
                startActivity(Intent.createChooser(share, getString(R.string.ui_share_week_timetable, selectedWeek)))
            } finally { bitmap.recycle() }
        } catch (e: Exception) { showBackupError(e) }
    }

    private fun importReady(): Boolean {
        if (semesterLoaded && periodsLoaded) return true
        Toast.makeText(this, getString(R.string.ui_loading_timetable_settings_please_try_again_s), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun showImportOptions() {
        if (!importReady()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_import_timetable_json))
            .setItems(arrayOf(getString(R.string.ui_choose_json_file), getString(R.string.ui_paste_json_text), getString(R.string.ui_format_and_ai_prompt))) { _, which ->
                when (which) {
                    0 -> importDocument.launch(arrayOf("*/*"))
                    1 -> showPasteImport()
                    2 -> showImportGuide()
                }
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show().also(::styleImportDialogButtons)
    }

    private fun styleImportDialogButtons(dialog: androidx.appcompat.app.AlertDialog) {
        val color = com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        listOf(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE, androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
            androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).forEach { dialog.getButton(it)?.setTextColor(color) }
    }

    private fun showPasteImport() {
        val text = EditText(this).apply {
            hint = getString(R.string.ui_paste_the_complete_json_here)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding((20 * resources.displayMetrics.density).toInt(), 0, (20 * resources.displayMetrics.density).toInt(), 0)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_paste_json_text))
            .setView(text)
            .setPositiveButton(getString(R.string.ui_check), null)
            .setNeutralButton(getString(R.string.ui_paste_from_clipboard), null)
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .create()
        dialog.setOnShowListener {
            styleImportDialogButtons(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
                if (pasted == null) Toast.makeText(this, getString(R.string.ui_the_clipboard_has_no_text), Toast.LENGTH_SHORT).show()
                else text.setText(pasted)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = text.text.toString()
                if (value.isBlank()) {
                    text.error = getString(R.string.ui_please_paste_json_first)
                } else {
                    dialog.dismiss()
                    validateAndPreview(value)
                }
            }
        }
        dialog.show()
    }

    private fun importPrompt(): String {
        val valid = periods.indices.filter { !periods[it].isPlaceholder }.map { it + 1 }
        val exampleStart = valid.first()
        val exampleEnd = valid.getOrElse(1) { exampleStart }
        val periodText = periods.mapIndexed { index, slot ->
            getString(R.string.ui_period, index + 1) + if (slot.isPlaceholder) getString(R.string.ui_lunch_placeholder) else "${slot.start}–${slot.end}"
        }.joinToString("\n")
        return assets.open(if (resources.configuration.locales[0].language == "en") "import_prompt_en.txt" else "import_prompt.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("{{TOTAL_WEEKS}}", semester.totalWeeks.toString())
            .replace("{{CURRENT_WEEK}}", selectedWeek.toString())
            .replace("{{PERIODS}}", periodText)
            .replace("{{EXAMPLE_START}}", exampleStart.toString())
            .replace("{{EXAMPLE_END}}", exampleEnd.toString())
    }

    private fun showImportGuide() {
        val prompt = importPrompt()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_json_format_and_ai_prompt))
            .setMessage(prompt)
            .setPositiveButton(getString(R.string.ui_copy_prompt)) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_timetable_json_recognition_prompt), prompt))
                Toast.makeText(this, getString(R.string.ui_prompt_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show().also(::styleImportDialogButtons)
    }

    private fun readImportDocument(uri: Uri) {
        if (!importReady()) return
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1024 * 1024) { getString(R.string.ui_file_must_not_exceed_1_mb) }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalArgumentException(getString(R.string.ui_unable_to_open_the_selected_file))
                    try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (e: java.nio.charset.CharacterCodingException) {
                        throw IllegalArgumentException(getString(R.string.ui_the_file_must_use_utf_8_encoding), e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showImportError(e)
                return@launch
            }
            validateAndPreview(text)
        }
    }

    private fun validateAndPreview(text: String) {
        if (!importReady()) return
        val currentPeriods = periods.toList()
        val totalWeeks = semester.totalWeeks
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { getString(R.string.ui_json_must_not_exceed_1_mb) }
                    TimetableImport.parse(text, currentPeriods, totalWeeks, this@MainActivity)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showImportError(e)
                return@launch
            }
            showImportPreview(result)
        }
    }

    private fun showImportError(error: Exception) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_unable_to_import))
            .setMessage(error.message ?: getString(R.string.ui_unable_to_read_the_content_please_check_the_j))
            .setPositiveButton(getString(R.string.ui_ok), null).show().also(::styleImportDialogButtons)
    }

    private fun showImportPreview(result: ImportedTimetable) {
        val weekdays = arrayOf("", getString(R.string.ui_mon), getString(R.string.ui_tue), getString(R.string.ui_wed), getString(R.string.ui_thu), getString(R.string.ui_fri), getString(R.string.ui_sat), getString(R.string.ui_sun))
        val byCourse = result.sessions.groupBy { it.courseId }
        val details = buildString {
            append(getString(R.string.ui_courses_class_sessions_n, result.courses.size, result.sessions.size))
            result.courses.forEach { course ->
                append("\n").append(course.name)
                if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
                if (course.note.isNotBlank()) append(getString(R.string.ui_nnote)).append(course.note)
                byCourse[course.id].orEmpty().forEach { session ->
                    append("\n  ").append(weekdays[session.weekday]).append(getString(R.string.ui_periods_2)).append(session.startPeriod)
                    if (session.endPeriod != session.startPeriod) append("–").append(session.endPeriod)
                    append(getString(R.string.ui_)).append(if (session.weeksCsv.isBlank()) getString(R.string.ui_every_week) else getString(R.string.ui_weeks, session.weeksCsv))
                    if (session.location.isNotBlank()) append(" · ").append(session.location)
                }
                append("\n")
            }
            append(getString(R.string.ui_nnext_choose_to_append_or_replace_appending_k))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_import_preview))
            .setMessage(details)
            .setPositiveButton(getString(R.string.ui_choose_import_method)) { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_import_method))
                    .setItems(arrayOf(getString(R.string.ui_append_keep_existing_courses), getString(R.string.ui_replace_existing_courses))) { _, choice ->
                        if (choice == 0) commitImport(result, replace = false)
                        else MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_replace_existing_courses_2))
                            .setMessage(getString(R.string.ui_all_current_courses_and_sessions_will_be_remo, result.courses.size))
                            .setNegativeButton(getString(R.string.ui_cancel), null)
                            .setPositiveButton(getString(R.string.ui_replace)) { _, _ -> commitImport(result, replace = true) }
                            .show().also(::styleImportDialogButtons)
                    }.setNegativeButton(getString(R.string.ui_cancel), null).show().also(::styleImportDialogButtons)
            }
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .show().also(::styleImportDialogButtons)
    }

    private fun commitImport(result: ImportedTimetable, replace: Boolean) {
        lifecycleScope.launch {
            try {
                repository.importSchedule(result.courses, result.sessions, replace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.ui_import_failed))
                    .setMessage(getString(R.string.ui_save_failed_course_data_has_not_changed_pleas))
                    .setPositiveButton(getString(R.string.ui_ok), null).show().also(::styleImportDialogButtons)
                return@launch
            }
            try {
                Reminders.reschedule(applicationContext)
                Toast.makeText(this@MainActivity, getString(R.string.ui_imported_courses, result.courses.size), Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.ui_courses_imported))
                    .setMessage(getString(R.string.ui_courses_saved_but_reminders_could_not_be_upda))
                    .setPositiveButton(getString(R.string.ui_ok), null).show().also(::styleImportDialogButtons)
            }
        }
    }

    private fun applySemester(value: SemesterEntity) {
        val changed = semester.startDateEpochDay != value.startDateEpochDay
        semester = value
        binding.weekHeader.semesterStart = LocalDate.ofEpochDay(value.startDateEpochDay)
        if ((!semesterLoaded && !restoredWeek) || (semesterLoaded && changed)) {
            selectedWeek = currentWeek() ?: 1
        }
        semesterLoaded = true
        selectedWeek = selectedWeek.coerceIn(1, value.totalWeeks.coerceAtLeast(1))
        binding.weekHeader.weekNumber = selectedWeek
        binding.timetable.selectedWeek = selectedWeek
        updateWeekTitle()
        if (settingsPages.lastOrNull() == "settings") renderSettingsPage()
    }

    private data class PeriodDraft(var start: String, var end: String, var placeholder: Boolean)

    private fun showPeriodSettings() {
        val density = resources.displayMetrics.density
        val drafts = periods.map { slot ->
            PeriodDraft(slot.start.ifEmpty { "12:10" }, slot.end.ifEmpty { "12:50" }, slot.isPlaceholder)
        }.toMutableList()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.ui_set_the_start_and_end_of_each_period_use_a_pl)
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply { text = getString(R.string.ui_add_period) }
        val remove = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = getString(R.string.ui_remove_last) }
        controls.addView(add, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(remove, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(controls)

        fun renderRows() {
            rows.removeAllViews()
            drafts.forEachIndexed { index, draft ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                val heading = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply { text = getString(R.string.ui_period_2, index + 1); textSize = 16f }
                val reserved = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                    text = getString(R.string.ui_lunch_placeholder)
                    isChecked = draft.placeholder
                    contentDescription = getString(R.string.ui_period_lunch_or_placeholder, index + 1)
                }
                heading.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(reserved)
                row.addView(heading)
                val times = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val startButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = draft.start; setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)); contentDescription = getString(R.string.ui_period_start_time, index + 1, draft.start) }
                val endButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = draft.end; setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)); contentDescription = getString(R.string.ui_period_end_time, index + 1, draft.end) }
                val dash = TextView(this).apply { text = getString(R.string.ui_to); setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0) }
                times.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
                times.addView(dash)
                times.addView(endButton, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(times)
                fun showTime(isStart: Boolean) {
                    val value = java.time.LocalTime.parse(if (isStart) draft.start else draft.end)
                    android.app.TimePickerDialog(this, { _, hour, minute ->
                        val formatted = periodLabel(java.time.LocalTime.of(hour, minute))
                        if (isStart) {
                            draft.start = formatted; startButton.text = formatted
                            startButton.contentDescription = getString(R.string.ui_period_start_time, index + 1, formatted)
                        } else {
                            draft.end = formatted; endButton.text = formatted
                            endButton.contentDescription = getString(R.string.ui_period_end_time, index + 1, formatted)
                        }
                    }, value.hour, value.minute, true).show()
                }
                startButton.setOnClickListener { showTime(true) }
                endButton.setOnClickListener { showTime(false) }
                times.visibility = if (draft.placeholder) android.view.View.GONE else android.view.View.VISIBLE
                reserved.setOnCheckedChangeListener { _, checked ->
                    draft.placeholder = checked
                    times.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
                }
                rows.addView(row)
            }
            add.isEnabled = drafts.size < 24
            remove.isEnabled = drafts.size > 1
        }
        add.setOnClickListener {
            val previous = drafts.lastOrNull { !it.placeholder }?.end?.let(java.time.LocalTime::parse)
                ?: java.time.LocalTime.of(8, 0)
            val start = previous.plusMinutes(10)
            val end = start.plusMinutes(40)
            if (start <= previous || end <= start) {
                Toast.makeText(this, getString(R.string.ui_not_enough_time_left_today_adjust_the_periods), Toast.LENGTH_LONG).show()
            } else {
                drafts.add(PeriodDraft(periodLabel(start), periodLabel(end), false))
                renderRows()
            }
        }
        remove.setOnClickListener { if (drafts.size > 1) { drafts.removeAt(drafts.lastIndex); renderRows() } }
        renderRows()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_period_settings))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .setPositiveButton(getString(R.string.ui_save), null)
            .create()
        dialog.setOnShowListener {
            val textColor = com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(textColor)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(textColor)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = drafts.map { if (it.placeholder) PeriodSlot("", "") else PeriodSlot(it.start, it.end) }
                val error = validatePeriods(updated, this)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val affected = repository.schedule.first().filter { row ->
                        row.startPeriod !in 1..updated.size || row.endPeriod !in 1..updated.size ||
                            updated[row.startPeriod - 1].isPlaceholder || updated[row.endPeriod - 1].isPlaceholder
                    }
                    if (affected.isNotEmpty()) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.ui_adjust_courses_first))
                            .setMessage(getString(R.string.ui_sessions_start_or_end_in_periods_that_would_b, affected.size, affected.take(3).joinToString("、") { it.name }))
                            .setPositiveButton(getString(R.string.ui_ok), null).show()
                        return@launch
                    }
                    settingsStore.edit { it[periodScheduleKey] = encodePeriods(updated) }
                    periods = updated
                    binding.timetable.periods = updated
                    Reminders.reschedule(applicationContext)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showSemesterSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.ui_semester_name); setText(semester.name) }
        var date = LocalDate.ofEpochDay(semester.startDateEpochDay)
        val dateButton = Button(this).apply { text = getString(R.string.ui_monday_of_week_1, date) }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                dateButton.text = getString(R.string.ui_monday_of_week_1, date)
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        val weeks = EditText(this).apply {
            hint = getString(R.string.ui_total_weeks_1_99)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(semester.totalWeeks.toString())
        }
        content.addView(name); content.addView(dateButton); content.addView(weeks)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_semester_settings))
            .setView(content).setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_save), null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val total = weeks.text.toString().toIntOrNull()
                if (name.text.isBlank() || total !in 1..99 || date.dayOfWeek != DayOfWeek.MONDAY) {
                    Toast.makeText(this, getString(R.string.ui_enter_a_semester_name_1_99_weeks_and_select_t), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val affected = if (total!! < semester.totalWeeks) repository.schedule.first().count { row ->
                        row.weeksCsv.split(',').mapNotNull(String::toIntOrNull).any { it > total }
                    } else 0
                    val updated = semester.copy(name = name.text.toString().trim(), startDateEpochDay = date.toEpochDay(), totalWeeks = total)
                    fun save() {
                        lifecycleScope.launch {
                            repository.saveSemester(updated)
                            Reminders.reschedule(applicationContext)
                            dialog.dismiss()
                        }
                    }
                    if (affected > 0) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.ui_shorten_semester))
                            .setMessage(getString(R.string.ui_sessions_include_week_or_later_these_weeks_wi, affected, total + 1))
                            .setNegativeButton(getString(R.string.ui_go_back_and_edit), null)
                            .setPositiveButton(getString(R.string.ui_continue)) { _, _ -> save() }.show()
                    } else save()
                }
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        val today = LocalDate.now()
        if (semesterLoaded && today != lastSeenDate) {
            val start = LocalDate.ofEpochDay(semester.startDateEpochDay)
            val previousDays = ChronoUnit.DAYS.between(start, lastSeenDate)
            val previousWeek = if (previousDays >= 0) (previousDays / 7 + 1).toInt().takeIf { it <= semester.totalWeeks } else null
            val current = currentWeek()
            if (previousWeek != null && selectedWeek == previousWeek && current != null) selectedWeek = current
            binding.weekHeader.weekNumber = selectedWeek
            binding.weekHeader.invalidate()
            binding.timetable.selectedWeek = selectedWeek
            updateWeekTitle()
        }
        lastSeenDate = today
        lifecycleScope.launch { Reminders.reschedule(applicationContext) }
    }

    private fun showReminderSettings() {
        lifecycleScope.launch {
            val current = settingsStore.data.first()[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES
            val values = intArrayOf(-1, 0, 5, 10, 15, 30)
            val labels = arrayOf(getString(R.string.ui_off), getString(R.string.ui_at_class_time), getString(R.string.ui_5_minutes_before), getString(R.string.ui_10_minutes_before), getString(R.string.ui_15_minutes_before), getString(R.string.ui_30_minutes_before), getString(R.string.ui_custom_minutes) + if (current > 0 && current !in values) getString(R.string.ui_currently_minutes_before, current) else "")
            val alarm = getSystemService(AlarmManager::class.java)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
            val status = when {
                !NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled() -> getString(R.string.ui_notifications_are_disabled_reminders_cannot_b)
                !exact -> getString(R.string.ui_exact_reminder_permission_is_disabled_notific)
                else -> getString(R.string.ui_all_reminder_permissions_are_granted)
            }
            val builder = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.ui_default_course_reminder_n, status))
                .setSingleChoiceItems(labels, values.indexOf(current).let { if (it < 0) labels.lastIndex else it }) { dialog, which ->
                    dialog.dismiss()
                    if (which == labels.lastIndex) showCustomReminderMinutes(current)
                    else saveReminderMinutes(values[which])
                }
                .setNegativeButton(getString(R.string.ui_cancel), null)
            if (!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) {
                builder.setNeutralButton(getString(R.string.ui_notification_settings)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
            builder.show().also(::styleImportDialogButtons)
        }
    }

    private fun saveReminderMinutes(minutes: Int) {
        lifecycleScope.launch {
            settingsStore.edit { it[reminderMinutesKey] = minutes }
            Reminders.reschedule(applicationContext)
            if (minutes >= 0) requestReminderPermissions()
        }
    }

    private fun showCustomReminderMinutes(current: Int, onSave: (Int) -> Unit = { saveReminderMinutes(it) }) {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(current.coerceAtLeast(0).toString())
            selectAll()
        }
        val wrapper = TextInputLayout(this).apply {
            hint = getString(R.string.ui_minutes_before_class_0_10080)
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_custom_reminder_time))
            .setView(wrapper).setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_save), null).create()
        dialog.setOnShowListener {
            styleImportDialogButtons(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 0..10080) {
                    wrapper.error = getString(R.string.ui_enter_a_number_of_minutes_from_0_to_10080)
                } else {
                    dialog.dismiss()
                    onSave(minutes)
                }
            }
        }
        dialog.show()
    }

    private fun showReminderIntroductionIfNeeded(minutes: Int) {
        val state = getSharedPreferences("reminder_intro", MODE_PRIVATE)
        if (minutes < 0 || state.getBoolean("shown", false)) return
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        if (notificationsEnabled && exact) return
        state.edit().putBoolean("shown", true).apply()
        val timing = if (minutes == 0) getString(R.string.ui_at_class_time) else getString(R.string.ui_minutes_before_class, minutes)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ui_course_reminders))
            .setMessage(getString(R.string.ui_course_notifications_are_set_to_enable_notifi, timing))
            .setNegativeButton(getString(R.string.ui_later), null)
            .setPositiveButton(getString(R.string.ui_set_up_reminders)) { _, _ -> requestReminderPermissions() }
            .show()
    }

    private fun requestReminderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            explainExactAlarmIfNeeded()
        }
    }

    private fun explainExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.ui_timely_course_reminders))
                .setMessage(getString(R.string.ui_allow_alarms_reminders_in_android_settings_fo))
                .setNegativeButton(getString(R.string.ui_later), null)
                .setPositiveButton(getString(R.string.ui_open_settings)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }.show()
        } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, getString(R.string.ui_please_allow_notifications_in_android_setting), Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        settleMotions()
        saveSettingsScroll()
        outState.putBoolean("themeFadeOnRecreate", themeFadeOnRecreate)
        outState.putStringArrayList("settingsPages", ArrayList(settingsPages))
        outState.putBundle("settingsScrolls", Bundle().apply { settingsScrolls.forEach { (key, y) -> putInt(key, y) } })
        outState.putInt("selectedWeek", selectedWeek)
        super.onSaveInstanceState(outState)
    }

    private fun saveSettingsScroll() {
        if (!settingsScrollReady) return
        val root = settingsOverlay as? LinearLayout ?: return
        val scroll = root.getChildAt(1) as? ScrollView ?: return
        renderedSettingsPage?.let { settingsScrolls[it] = scroll.scrollY }
    }

    private fun openSettingsPage(page: String) {
        if (motionBusy || settingsPages.lastOrNull() == page) return
        saveSettingsScroll()
        settingsPages.add(page)
        renderSettingsPage(PageMotion.ENTER)
    }

    private fun renderSettingsPage(motion: PageMotion = PageMotion.NONE) {
        if (pageMotionRunning || themeApplying) {
            if (motion == PageMotion.NONE) settingsRefreshPending = true
            return
        }
        saveSettingsScroll()
        val root = binding.root as android.view.ViewGroup
        val outgoing = settingsOverlay
        settingsScrollReady = false
        val page = settingsPages.lastOrNull()
        val animate = motion != PageMotion.NONE && animationsEnabled() && root.isLaidOut
        pageMotionRunning = animate
        updateMotionGuard()
        if (page == null) {
            settingsOverlay = null
            renderedSettingsPage = null
            if (animate) transitionPage(outgoing, null, motion)
            else {
                outgoing?.let { root.removeView(it) }
                homeViews().forEach { resetMotionView(it); it.visibility = View.VISIBLE }
            }
            return
        }
        val reminder = when {
            defaultReminderMinutes < 0 -> getString(R.string.ui_off_by_default_courses_can_override)
            defaultReminderMinutes == 0 -> getString(R.string.ui_notify_at_class_time)
            else -> getString(R.string.ui_minutes_before_by_default_courses_can_overrid, defaultReminderMinutes)
        }
        val summaries = mapOf("semester" to getString(R.string.ui_weeks_2, semester.name, semester.totalWeeks),
            "periods" to getString(R.string.ui_periods_placeholder_rows, periods.count { !it.isPlaceholder }, periods.count { it.isPlaceholder }),
            "reminders" to reminder)
        val overlay = SettingsPages(this, activeTheme, summaries, ::settingsAction, ::applyTheme) {
            settingsBack.handleOnBackPressed()
        }.create(page)
        renderedSettingsPage = page
        settingsOverlay = overlay
        root.addView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
        (overlay as? LinearLayout)?.getChildAt(1)?.let { view ->
            view.doOnPreDraw {
                if (settingsOverlay === overlay) {
                    (view as? ScrollView)?.scrollTo(0, settingsScrolls[page] ?: 0)
                    settingsScrollReady = true
                }
            }
        }
        if (animate) transitionPage(outgoing, overlay, motion)
        else {
            outgoing?.let { root.removeView(it) }
            homeViews().forEach { resetMotionView(it); it.visibility = View.GONE }
        }
    }

    private fun settingsAction(action: String) {
        if (motionBusy) return
        when (action) {
            "settings", "theme", "about", "components", "license", "source" -> openSettingsPage(action)
            "language" -> showLanguageSettings()
            "widgets" -> showWidgetChooser()
            "animations" -> {
                InterfacePreferences.setAnimationsEnabled(this, !InterfacePreferences.animationsEnabled(this))
                renderSettingsPage()
            }
            "semester" -> showSemesterSettings()
            "periods" -> showPeriodSettings()
            "reminders" -> showReminderSettings()
            "backup" -> showBackupOptions()
            "import" -> showImportOptions()
            "palette" -> CourseColorPicker.show(this, Color.rgb(111, 133, 215), manageOnly = true) { }
            "open_repository", "open_issues", "open_releases" -> {
                val base = getString(R.string.project_repository_url).trimEnd('/')
                val suffix = when (action) { "open_issues" -> "/issues"; "open_releases" -> "/releases"; else -> "" }
                val uri = Uri.parse(base + suffix)
                if (uri.scheme == "https" && uri.host == "github.com") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, getString(R.string.ui_no_app_can_open_this_link), Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun showQuickActions() {
        if (motionBusy) return
        val anchor = binding.topAppBar.findViewById<View>(R.id.action_more) ?: binding.topAppBar
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.quick_actions_menu, menu)
            setForceShowIcon(true)
            val iconTint = com.google.android.material.color.MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnSurface,
            )
            for (index in 0 until menu.size()) menu.getItem(index).icon?.setTint(iconTint)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_settings -> { openSettingsPage("settings"); true }
                    R.id.action_share -> { shareTimetable(); true }
                    R.id.action_about -> { openSettingsPage("about"); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun animationsEnabled() = InterfacePreferences.animationsEnabled(this) && ValueAnimator.areAnimatorsEnabled()

    private fun showWidgetChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.widget_choose))
            .setItems(arrayOf(getString(R.string.widget_today), getString(R.string.widget_next))) { _, which ->
                val manager = android.appwidget.AppWidgetManager.getInstance(this)
                val provider = if (which == 0) com.seb.campustimetable.widget.TodayWidgetProvider::class.java else com.seb.campustimetable.widget.NextClassWidgetProvider::class.java
                if (!manager.isRequestPinAppWidgetSupported || !manager.requestPinAppWidget(android.content.ComponentName(this, provider), null, null)) {
                    MaterialAlertDialogBuilder(this).setMessage(getString(R.string.widget_pin_unavailable))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            .setNeutralButton(getString(R.string.widget_help)) { _, _ ->
                MaterialAlertDialogBuilder(this).setMessage(getString(R.string.widget_pin_help)).setPositiveButton(android.R.string.ok, null).show()
            }
            .setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun showLanguageSettings() {
        val tags = listOf("", "zh-Hans", "en")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = tags.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language))
            .setSingleChoiceItems(arrayOf(getString(R.string.follow_system), "简体中文", "English"), selected) { dialog, which ->
                dialog.dismiss()
                applyLanguage(tags[which])
            }
            .setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun applyLanguage(tag: String) {
        if (tag !in listOf("", "zh-Hans", "en") || motionBusy ||
            tag == AppCompatDelegate.getApplicationLocales().toLanguageTags()) return
        saveSettingsScroll()
        InterfacePreferences.setLanguage(this, tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun applyTheme(selected: String) {
        if (selected == activeTheme || motionBusy) return
        themeApplying = true
        updateMotionGuard()
        lifecycleScope.launch {
            try {
                settingsStore.edit { it[themeKey] = selected }
                getSharedPreferences("appearance_cache", MODE_PRIVATE).edit().putString("theme", selected).apply()
                themeFadeOnRecreate = true
                recreate()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                themeApplying = false
                updateMotionGuard()
                Toast.makeText(this@MainActivity, getString(R.string.ui_unable_to_save_theme_please_try_again), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun homeViews(): List<View> = listOf((binding.root as android.view.ViewGroup).getChildAt(0), binding.addCourseFab)

    private fun resetMotionView(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun updateMotionGuard() {
        settingsBack.isEnabled = settingsPages.isNotEmpty() || motionBusy
        val root = binding.root as android.view.ViewGroup
        if (motionBusy && !destroying) {
            if (motionShield == null) {
                motionShield = View(this).apply {
                    isClickable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                root.addView(motionShield, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            motionShield?.bringToFront()
        } else {
            motionShield?.let { root.removeView(it) }
            motionShield = null
        }
    }

    private fun refreshSettingsAfterMotion() {
        if (settingsRefreshPending && !motionBusy && !destroying) {
            settingsRefreshPending = false
            if (settingsPages.lastOrNull() == "settings") renderSettingsPage()
        }
    }

    private fun transitionPage(outgoing: View?, incoming: View?, motion: PageMotion) {
        val root = binding.root as android.view.ViewGroup
        val exits = outgoing?.let { listOf(it) } ?: homeViews()
        val entries = incoming?.let { listOf(it) } ?: homeViews()
        val distance = 24f * resources.displayMetrics.density
        val forward = motion == PageMotion.ENTER
        val duration = if (forward) 200L else 180L
        val animator = AnimatorSet()
        var completed = false
        val finish = finish@ {
            if (completed) return@finish
            completed = true
            animator.removeAllListeners()
            animator.cancel()
            exits.forEach(::resetMotionView)
            entries.forEach(::resetMotionView)
            outgoing?.let { root.removeView(it) }
            homeViews().forEach { it.visibility = if (incoming == null) View.VISIBLE else View.GONE }
            pageMotionRunning = false
            finishPageMotion = null
            updateMotionGuard()
            refreshSettingsAfterMotion()
        }
        finishPageMotion = finish
        exits.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        entries.forEach {
            it.visibility = View.VISIBLE
            it.alpha = 0f
            it.translationX = if (forward) distance else -distance / 3f
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val tracks = exits.flatMap { view -> listOf(
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
            ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, if (forward) -distance / 3f else distance)) } +
            entries.flatMap { view -> listOf(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, view.translationX, 0f)) }
        animator.playTogether(tracks)
        animator.duration = duration
        animator.interpolator = enterCurve
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
        updateMotionGuard()
        incoming?.doOnPreDraw { if (!completed) animator.start() } ?: animator.start()
    }

    private fun fadeRecreatedTheme() {
        if (!animationsEnabled()) return
        val entries = settingsOverlay?.let { listOf(it) } ?: homeViews()
        val animator = AnimatorSet()
        var completed = false
        themeApplying = true
        entries.forEach { it.alpha = 0f; it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val finish = finish@ {
            if (completed) return@finish
            completed = true
            animator.removeAllListeners()
            animator.cancel()
            entries.forEach(::resetMotionView)
            themeApplying = false
            finishThemeMotion = null
            updateMotionGuard()
            refreshSettingsAfterMotion()
        }
        finishThemeMotion = finish
        animator.playTogether(entries.map { ObjectAnimator.ofFloat(it, View.ALPHA, 0f, 1f) })
        animator.duration = 150L
        animator.interpolator = enterCurve
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
        updateMotionGuard()
        binding.root.doOnPreDraw { if (!completed) animator.start() }
    }

    private fun settleMotions() {
        finishPageMotion?.invoke()
        finishWeekMotion?.invoke()
        finishThemeMotion?.invoke()
    }

    override fun onStop() {
        super.onStop()
        settleMotions()
    }

    override fun onDestroy() {
        destroying = true
        settleMotions()
        themeApplying = false
        updateMotionGuard()
        super.onDestroy()
    }

    private fun toCourseBlock(row: CourseSessionRow) = CourseBlock(
        id = row.sessionId,
        name = row.name,
        teacher = row.teacher,
        location = row.location,
        weekday = row.weekday,
        startPeriod = row.startPeriod,
        endPeriod = row.endPeriod,
        color = row.colorArgb,
        courseId = row.courseId,
        sessionId = row.sessionId,
        weeks = row.weeksCsv.split(',').mapNotNull(String::toIntOrNull),
        note = row.note,
    )

    private fun changeWeek(delta: Int) {
        if (motionBusy || settingsPages.isNotEmpty()) return
        val target = (selectedWeek + delta).coerceIn(1, semester.totalWeeks.coerceAtLeast(1))
        if (target == selectedWeek) return
        var applied = false
        fun applyTarget() {
            if (applied) return
            applied = true
            selectedWeek = target
            binding.weekHeader.weekNumber = selectedWeek
            binding.timetable.selectedWeek = selectedWeek
            updateWeekTitle()
            binding.timetableScroll.scrollTo(0, 0)
        }
        if (!animationsEnabled()) { applyTarget(); return }
        val views = listOf<View>(binding.weekHeader, binding.timetableScroll)
        val distance = 24f * resources.displayMetrics.density * if (target > selectedWeek) 1f else -1f
        val exit = AnimatorSet().apply {
            playTogether(views.flatMap { view -> listOf(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -distance)) })
            duration = 90L
            interpolator = exitCurve
        }
        val enter = AnimatorSet().apply {
            playTogether(views.flatMap { view -> listOf(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, distance, 0f)) })
            duration = 150L
            interpolator = enterCurve
        }
        exit.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { applyTarget() }
        })
        val animator = AnimatorSet().apply { playSequentially(exit, enter) }
        var completed = false
        val finish = finish@ {
            if (completed) return@finish
            completed = true
            animator.removeAllListeners()
            exit.removeAllListeners()
            animator.cancel()
            applyTarget()
            views.forEach(::resetMotionView)
            weekMotionRunning = false
            finishWeekMotion = null
            updateMotionGuard()
        }
        finishWeekMotion = finish
        weekMotionRunning = true
        views.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        updateMotionGuard()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { finish() }
        })
        animator.start()
    }

    private fun updateWeekTitle() {
        val suffix = if (selectedWeek == currentWeek()) getString(R.string.ui_this_week) else ""
        binding.weekTitle.text = getString(R.string.ui_week_2, selectedWeek, suffix)
    }

    private fun showCourseDetails(course: CourseBlock) {
        val weekdays = arrayOf("", getString(R.string.ui_mon), getString(R.string.ui_tue), getString(R.string.ui_wed), getString(R.string.ui_thu), getString(R.string.ui_fri), getString(R.string.ui_sat), getString(R.string.ui_sun))
        val startTime = periodTime(course.startPeriod).first
        val endTime = periodTime(course.endPeriod).second
        val details = buildString {
            append(weekdays[course.weekday]).append(getString(R.string.ui_periods_3))
            append(course.startPeriod)
            if (course.endPeriod != course.startPeriod) append("–").append(course.endPeriod)
            append(getString(R.string.ui__2)).append(periodSummary(course.startPeriod, course.endPeriod))
            if (startTime.isNotEmpty() && endTime.isNotEmpty()) append("\n").append(startTime).append("–").append(endTime)
            else append(getString(R.string.ui_ntime_not_set))
            append(getString(R.string.ui_n_nlocation)).append(course.location.ifBlank { getString(R.string.ui_not_provided) })
            append(getString(R.string.ui_nteacher)).append(course.teacher.ifBlank { getString(R.string.ui_not_provided) })
            append(getString(R.string.ui_nweeks)).append(if (course.weeks.isEmpty()) getString(R.string.ui_every_week) else course.weeks.joinToString("、"))
            if (course.note.isNotBlank()) append(getString(R.string.ui_nnote)).append(course.note)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(course.name)
            .setMessage(details)
            .setPositiveButton(getString(R.string.ui_edit)) { _, _ -> showCourseEditor(course) }
            .setNegativeButton(getString(R.string.ui_more)) { _, _ -> showCourseActions(course) }
            .show()
    }

    private fun showCourseReminder(course: CourseBlock) {
        lifecycleScope.launch {
            val preferences = settingsStore.data.first()
            val overrides = decodeCourseReminders(preferences[courseReminderMinutesKey])
            val default = preferences[reminderMinutesKey] ?: DEFAULT_REMINDER_MINUTES
            val current = overrides[course.courseId]
            val values = listOf<Int?>(null, -1, 0, 5, 10, 15, 30)
            val defaultLabel = if (default < 0) getString(R.string.ui_off) else if (default == 0) getString(R.string.ui_at_class_time) else getString(R.string.ui_minutes_before, default)
            val labels = arrayOf(getString(R.string.ui_use_default, defaultLabel), getString(R.string.ui_disable_reminders_for_this_course), getString(R.string.ui_at_class_time), getString(R.string.ui_5_minutes_before), getString(R.string.ui_10_minutes_before), getString(R.string.ui_15_minutes_before), getString(R.string.ui_30_minutes_before), getString(R.string.ui_custom_minutes) + if (current != null && current !in values) getString(R.string.ui_currently_minutes, current) else "")
            fun save(minutes: Int?) {
                lifecycleScope.launch {
                    settingsStore.edit { settings ->
                        val map = decodeCourseReminders(settings[courseReminderMinutesKey]).toMutableMap()
                        if (minutes == null) map.remove(course.courseId) else map[course.courseId] = minutes
                        settings[courseReminderMinutesKey] = org.json.JSONObject(map as Map<*, *>).toString()
                    }
                    Reminders.reschedule(applicationContext)
                    if ((minutes ?: default) >= 0) requestReminderPermissions()
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.ui_reminder, course.name))
                .setSingleChoiceItems(labels, values.indexOf(current).let { if (it < 0) labels.lastIndex else it }) { dialog, which ->
                    dialog.dismiss()
                    if (which == labels.lastIndex) showCustomReminderMinutes(current ?: default) { save(it) }
                    else save(values[which])
                }.setNegativeButton(getString(R.string.ui_cancel), null).show().also(::styleImportDialogButtons)
        }
    }

    private fun showCourseActions(course: CourseBlock) {
        val options = arrayOf(getString(R.string.ui_add_another_session), getString(R.string.ui_course_reminder), getString(R.string.ui_delete_this_session), getString(R.string.ui_delete_entire_course))
        MaterialAlertDialogBuilder(this).setTitle(course.name).setItems(options) { _, choice ->
            when (choice) {
                0 -> showCourseEditor(course, addSession = true)
                1 -> showCourseReminder(course)
                2, 3 -> {
                    val wholeCourse = choice == 3
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (wholeCourse) getString(R.string.ui_delete_entire_course_2) else getString(R.string.ui_delete_this_session_2))
                        .setMessage(if (wholeCourse) getString(R.string.ui_all_sessions_for_will_be_deleted, course.name) else getString(R.string.ui_only_this_weekday_and_period_arrangement_will))
                        .setNegativeButton(getString(R.string.ui_cancel), null)
                        .setPositiveButton(getString(R.string.ui_delete)) { _, _ -> lifecycleScope.launch {
                            if (wholeCourse) repository.deleteCourse(course.courseId)
                            else repository.deleteSession(course.courseId, course.sessionId)
                            Reminders.reschedule(applicationContext)
                        } }.show()
                }
            }
        }.setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun showCourseEditor(course: CourseBlock?, addSession: Boolean = false) {
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun field(hintText: String, value: String, numeric: Boolean = false): EditText {
            val wrapper = TextInputLayout(this).apply {
                hint = hintText
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (8 * density).toInt()
                }
            }
            val input = TextInputEditText(this).apply {
                isSingleLine = true
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
            }
            wrapper.addView(input)
            content.addView(wrapper)
            return input
        }
        if (course != null) content.addView(TextView(this).apply {
            text = getString(R.string.ui_the_name_teacher_note_and_color_apply_to_ever)
        })
        val name = field(getString(R.string.ui_course_name_required), course?.name.orEmpty())
        val teacher = field(getString(R.string.ui_teacher), course?.teacher.orEmpty())
        val note = field(getString(R.string.ui_note), course?.note.orEmpty())
        val location = field(getString(R.string.ui_location), course?.location.orEmpty())
        val weekday = field(getString(R.string.ui_weekday_1_7_monday_sunday), course?.weekday?.toString() ?: "1", true)
        val availablePeriods = periods.indices.filter { !periods[it].isPlaceholder }.map { it + 1 }
        val start = field(getString(R.string.ui_start_period_1, periods.size), course?.startPeriod?.toString() ?: availablePeriods.first().toString(), true)
        val end = field(getString(R.string.ui_end_period_1, periods.size), course?.endPeriod?.toString() ?: availablePeriods.getOrElse(1) { availablePeriods.first() }.toString(), true)
        val duration = TextView(this).apply {
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        content.addView(duration)
        fun updateDuration() {
            val first = start.text.toString().toIntOrNull()
            val last = end.text.toString().toIntOrNull()
            duration.text = if (first != null && last != null && first in 1..periods.size && last in first..periods.size && !periods[first - 1].isPlaceholder && !periods[last - 1].isPlaceholder)
                periodSummary(first, last) else getString(R.string.ui_select_valid_start_and_end_periods)
        }
        start.doAfterTextChanged { updateDuration() }
        end.doAfterTextChanged { updateDuration() }
        updateDuration()
        val weeks = field(getString(R.string.weeks_hint), course?.weeks?.joinToString(",").orEmpty())
        var chosenColor = course?.color ?: Color.rgb(111, 133, 215)
        val colorButton = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        fun updateColorButton() {
            colorButton.text = getString(R.string.ui_course_color, String.format(java.util.Locale.ROOT, "%06X", chosenColor and 0xffffff))
            val swatch = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(chosenColor)
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xff888888.toInt())
                setSize((24 * density).toInt(), (24 * density).toInt())
                setBounds(0, 0, (24 * density).toInt(), (24 * density).toInt())
            }
            colorButton.compoundDrawablePadding = (12 * density).toInt()
            colorButton.setCompoundDrawables(swatch, null, null, null)
        }
        updateColorButton()
        colorButton.setOnClickListener {
            CourseColorPicker.show(this, chosenColor) { color ->
                chosenColor = color
                updateColorButton()
            }
        }
        content.addView(colorButton)
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (course == null) getString(R.string.ui_add_course) else if (addSession) getString(R.string.ui_add_class_session) else getString(R.string.ui_edit_course_and_session))
            .setView(scroll)
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .setPositiveButton(getString(R.string.ui_save), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val day = weekday.text.toString().toIntOrNull()
                val first = start.text.toString().toIntOrNull()
                val last = end.text.toString().toIntOrNull()
                val parsedWeeks = parseWeeks(weeks.text.toString())
                if (name.text.isBlank() || day !in 1..7 || first !in 1..periods.size || last !in 1..periods.size || first!! > last!! || parsedWeeks == null) {
                    Toast.makeText(this, getString(R.string.ui_check_the_course_name_weekday_periods_and_wee), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (periods[first - 1].isPlaceholder || periods[last - 1].isPlaceholder) {
                    Toast.makeText(this, getString(R.string.ui_start_and_end_periods_cannot_be_lunch_or_plac), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (parsedWeeks.any { it > semester.totalWeeks }) {
                    weeks.error = getString(R.string.ui_this_semester_has_weeks_adjust_the_weeks_or_s, semester.totalWeeks)
                    weeks.requestFocus()
                    return@setOnClickListener
                }
                val courseId = course?.courseId ?: UUID.randomUUID().toString()
                val sessionId = if (course == null || addSession) UUID.randomUUID().toString() else course.sessionId
                val entity = CourseEntity(courseId, name.text.toString().trim(), teacher.text.toString().trim(), chosenColor, note.text.toString().trim())
                val session = CourseSessionEntity(sessionId, courseId, day!!, first, last, location.text.toString().trim(), parsedWeeks.joinToString(","))
                val saveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                fun persist() {
                    saveButton.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            repository.save(entity, session)
                            dialog.dismiss()
                            try { Reminders.reschedule(applicationContext) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { Toast.makeText(this@MainActivity, getString(R.string.ui_course_saved_please_check_reminder_settings), Toast.LENGTH_LONG).show() }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            saveButton.isEnabled = true
                            Toast.makeText(this@MainActivity, getString(R.string.ui_save_failed_please_try_again), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val conflicts = repository.schedule.first().filter { other ->
                            other.sessionId != sessionId && other.weekday == day &&
                                other.startPeriod <= last && first <= other.endPeriod &&
                                (1..semester.totalWeeks).any { week ->
                                    (parsedWeeks.isEmpty() || week in parsedWeeks) &&
                                        (other.weeksCsv.isEmpty() || week in other.weeksCsv.split(',').mapNotNull(String::toIntOrNull))
                                }
                        }
                        if (conflicts.isEmpty()) persist()
                        else {
                            saveButton.isEnabled = true
                            val message = conflicts.joinToString("\n") { other ->
                                val weeksInCommon = (1..semester.totalWeeks).filter { week ->
                                    (parsedWeeks.isEmpty() || week in parsedWeeks) &&
                                        (other.weeksCsv.isEmpty() || week in other.weeksCsv.split(',').mapNotNull(String::toIntOrNull))
                                }
                                getString(R.string.ui_periods_nconflicting_weeks, other.name, other.startPeriod, other.endPeriod, weeksInCommon.joinToString("、"))
                            }
                            MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.ui_found_time_conflicts, conflicts.size))
                                .setMessage(message).setNegativeButton(getString(R.string.ui_go_back_and_edit), null)
                                .setPositiveButton(getString(R.string.ui_save_anyway)) { _, _ -> persist() }.show().also(::styleImportDialogButtons)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        saveButton.isEnabled = true
                        Toast.makeText(this@MainActivity, getString(R.string.ui_unable_to_check_conflicts_please_try_again), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun periodSummary(first: Int, last: Int): String {
        val reserved = (first..last).count { periods.getOrNull(it - 1)?.isPlaceholder == true }
        val count = last - first + 1 - reserved
        return getString(R.string.ui_periods_in_total, count) + if (reserved > 0) getString(R.string.ui_across_placeholder_rows, reserved) else ""
    }

    private fun parseWeeks(input: String): List<Int>? {
        val value = input.trim().lowercase(java.util.Locale.ROOT).replace("odd", "单").replace("even", "双").replace('，', ',').replace(" ", "")
        if (value.isEmpty()) return emptyList()
        val result = mutableSetOf<Int>()
        for (part in value.split(',')) {
            val match = Regex("^(\\d+)(?:-(\\d+))?([单双])?$").matchEntire(part) ?: return null
            val first = match.groupValues[1].toIntOrNull() ?: return null
            val lastText = match.groupValues[2]
            val last = if (lastText.isEmpty()) first else lastText.toIntOrNull() ?: return null
            if (first !in 1..99 || last !in first..99) return null
            val parity = match.groupValues[3]
            result.addAll((first..last).filter { parity.isEmpty() || it % 2 == if (parity == "单") 1 else 0 })
        }
        return result.sorted().takeIf { it.isNotEmpty() }
    }

    private fun periodTime(period: Int): Pair<String, String> = periods.getOrNull(period - 1)
        ?.let { it.start to it.end } ?: ("" to "")

    private fun currentWeek(): Int? {
        val days = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(semester.startDateEpochDay), LocalDate.now())
        if (days < 0) return null
        return (days / 7 + 1).toInt().takeIf { it <= semester.totalWeeks }
    }
}
