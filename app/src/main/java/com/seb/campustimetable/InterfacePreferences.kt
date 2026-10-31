package com.seb.campustimetable

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Device UI choices, independent of course content and portable schedule backups. */
object InterfacePreferences {
    private fun store(context: Context) = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    fun animationsEnabled(context: Context) = store(context).getBoolean("animations", true)
    fun setAnimationsEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean("animations", enabled).apply()
    }
    fun language(context: Context): String = store(context).getString("language", "").orEmpty()
    fun setLanguage(context: Context, tag: String) {
        store(context).edit().putString("language", tag).apply()
    }
    /** Receivers also need the selected locale on Android versions before per-app languages. */
    fun localized(context: Context): Context {
        val tag = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags()
        else language(context)
        if (tag.isBlank()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return context.createConfigurationContext(configuration)
    }
}
