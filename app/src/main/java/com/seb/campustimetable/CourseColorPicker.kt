package com.seb.campustimetable

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** The palette belongs to the device and remains editable independently of any course. */
object CourseColorPicker {
    private val defaultColors = listOf(0xff6f85d7.toInt(), 0xff4ca5de.toInt(), 0xff3db4aa.toInt(),
        0xff3fb473.toInt(), 0xffe19428.toInt(), 0xffcb788c.toInt(), 0xffa078c8.toInt())
    private const val PREFS = "course_color_presets"
    private const val PRESETS = "colors"

    private fun loadPresets(context: Context): MutableList<Int> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PRESETS, null)
            ?: return defaultColors.toMutableList()
        return try {
            val json = JSONArray(stored)
            MutableList(json.length()) { json.getInt(it) }
        } catch (_: Exception) {
            defaultColors.toMutableList()
        }
    }

    private fun savePresets(context: Context, colors: List<Int>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PRESETS, JSONArray(colors).toString()).apply()
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xffffff)

    fun show(context: Context, initial: Int, manageOnly: Boolean = false, onSelect: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        fun circle(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1).coerceAtLeast(1), outline)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) }
            contentDescription = context.getString(R.string.ui_current_color_preview)
        }
        val previewLabel = TextView(context).apply { textSize = 18f }
        previewRow.addView(preview)
        previewRow.addView(previewLabel)
        container.addView(previewRow)
        container.addView(TextView(context).apply {
            text = context.getString(R.string.ui_adjust_rgb_0_255)
            setPadding(0, dp(16), 0, dp(4))
        })
        val inputs = mutableListOf<EditText>()
        val sliders = mutableListOf<SeekBar>()
        var updating = false
        var selected = initial or (0xff shl 24)
        fun setPreview(color: Int) {
            selected = color
            preview.background = circle(color)
            preview.contentDescription = context.getString(R.string.ui_current_color, hex(color))
            previewLabel.text = hex(color)
        }
        fun readColor(): Int? {
            val channels = inputs.map { it.text.toString().toIntOrNull() }
            if (channels.size != 3 || channels.any { it == null || it !in 0..255 }) return null
            return Color.rgb(channels[0]!!, channels[1]!!, channels[2]!!)
        }
        fun showColor(color: Int) {
            updating = true
            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
            inputs.forEachIndexed { index, input ->
                input.setText(channels[index].toString())
                sliders[index].progress = channels[index]
                input.error = null
            }
            updating = false
            setPreview(color)
        }
        listOf("R", "G", "B").forEachIndexed { index, channel ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = channel
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val slider = SeekBar(context).apply {
                max = 255
                contentDescription = context.getString(R.string.ui_color_slider, channel)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(3))
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.ui_value, channel)
                layoutParams = LinearLayout.LayoutParams(dp(66), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            sliders.add(slider)
            inputs.add(input)
            row.addView(slider)
            row.addView(input)
            container.addView(row)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating) {
                        input.setText(progress.toString())
                        input.setSelection(input.text.length)
                    }
                }
            })
            input.doAfterTextChanged {
                if (!updating) {
                    val value = input.text.toString().toIntOrNull()
                    if (value != null && value in 0..255) {
                        sliders[index].progress = value
                        input.error = null
                        readColor()?.let(::setPreview)
                    }
                }
            }
        }
        showColor(selected)
        val presets = loadPresets(context)
        val presetStore = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = try { JSONObject(presetStore.getString("names", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        fun persistNames() { presetStore.edit().putString("names", names.toString()).apply() }
        val presetsList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun redrawPresets() {
            presetsList.removeAllViews()
            if (presets.isEmpty()) {
                presetsList.addView(TextView(context).apply { text = context.getString(R.string.ui_no_color_presets_yet_add_the_current_color_to) })
            }
            presets.toList().forEach { color ->
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                }
                val swatch = View(context).apply {
                    background = circle(color)
                    contentDescription = context.getString(R.string.ui_choose_color, hex(color))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
                    setOnClickListener { showColor(color) }
                }
                val name = TextView(context).apply {
                    text = names.optString(hex(color)).takeIf { it.isNotBlank() }?.let { "$it\n${hex(color)}" } ?: hex(color)
                    textSize = 16f
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(R.string.ui_choose_color, hex(color))
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { showColor(color) }
                }
                val remove = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = context.getString(R.string.ui_manage)
                    setTextColor(onSurface)
                    contentDescription = context.getString(R.string.ui_manage_color_preset, hex(color))
                    setOnClickListener {
                        val actions = mutableListOf(context.getString(R.string.ui_rename))
                        val index = presets.indexOf(color)
                        if (index > 0) actions.add(context.getString(R.string.ui_move_up))
                        if (index < presets.lastIndex) actions.add(context.getString(R.string.ui_move_down))
                        actions.add(context.getString(R.string.ui_delete))
                        MaterialAlertDialogBuilder(context).setTitle(hex(color))
                            .setItems(actions.toTypedArray()) { _, which ->
                                when (actions[which]) {
                                    context.getString(R.string.ui_rename) -> {
                                        val input = EditText(context).apply {
                                            isSingleLine = true
                                            hint = context.getString(R.string.ui_preset_name)
                                            filters = arrayOf(InputFilter.LengthFilter(40))
                                            setText(names.optString(hex(color)))
                                        }
                                        val wrap = LinearLayout(context).apply {
                                            setPadding(dp(24), 0, dp(24), 0)
                                            addView(input, LinearLayout.LayoutParams(-1, -2))
                                        }
                                        MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.ui_color_preset_name)).setView(wrap)
                                            .setNegativeButton(context.getString(R.string.ui_cancel), null).setPositiveButton(context.getString(R.string.ui_save)) { _, _ ->
                                                names.put(hex(color), input.text.toString().trim())
                                                persistNames(); redrawPresets()
                                            }.show().also { d ->
                                                d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(onSurface)
                                                d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(onSurface)
                                            }
                                    }
                                    context.getString(R.string.ui_move_up), context.getString(R.string.ui_move_down) -> {
                                        val target = index + if (actions[which] == context.getString(R.string.ui_move_up)) -1 else 1
                                        java.util.Collections.swap(presets, index, target)
                                        savePresets(context, presets); redrawPresets()
                                    }
                                    context.getString(R.string.ui_delete) -> {
                                        presets.remove(color); names.remove(hex(color))
                                        savePresets(context, presets); persistNames(); redrawPresets()
                                    }
                                }
                            }.setNegativeButton(context.getString(R.string.ui_cancel), null).show().also { d -> d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(onSurface) }
                    }
                }
                row.addView(swatch)
                row.addView(name)
                row.addView(remove)
                presetsList.addView(row)
            }
        }
        val addPreset = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.ui_add_current_color_to_presets)
            setTextColor(onSurface)
            setOnClickListener {
                val color = readColor()
                if (color == null) {
                    Toast.makeText(context, context.getString(R.string.ui_enter_rgb_values_from_0_to_255), Toast.LENGTH_SHORT).show()
                } else if (color in presets) {
                    Toast.makeText(context, context.getString(R.string.ui_this_color_is_already_in_your_presets), Toast.LENGTH_SHORT).show()
                } else {
                    presets.add(color)
                    savePresets(context, presets)
                    redrawPresets()
                }
            }
        }
        container.addView(addPreset)
        container.addView(TextView(context).apply {
            text = context.getString(R.string.ui_preset_colors)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
        })
        container.addView(presetsList)
        redrawPresets()
        val scroll = ScrollView(context).apply { addView(container) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(if (manageOnly) context.getString(R.string.ui_color_presets) else context.getString(R.string.ui_course_color_2))
            .setView(scroll).setNegativeButton(context.getString(R.string.ui_cancel), null).setPositiveButton(if (manageOnly) context.getString(R.string.ui_done) else context.getString(R.string.ui_use_color), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(onSurface)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(onSurface)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (manageOnly) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val color = readColor()
                if (color == null) {
                    inputs.forEach { input ->
                        if (input.text.toString().toIntOrNull()?.let { it in 0..255 } != true) input.error = "0–255"
                    }
                } else {
                    onSelect(color)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
