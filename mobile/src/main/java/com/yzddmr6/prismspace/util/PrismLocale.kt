package com.yzddmr6.prismspace.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.preference.PreferenceManager
import java.util.Locale

/**
 * Dependency-free per-app language override. The app has no appcompat, so instead of
 * AppCompatDelegate.setApplicationLocales we persist the user's choice and wrap each Activity's
 * base context with an overridden [Configuration] locale (works on all API levels).
 *
 * Traditional Chinese deliberately falls back to Simplified Chinese before the default English
 * resources, so the partial Traditional translation never produces avoidable English holes.
 *
 * Each Activity must call [wrap] in attachBaseContext; the language picker calls [setStored] +
 * Activity.recreate() (and ideally restarts the task) to apply.
 */
object PrismLocale {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val ZH_TW = "zh-TW"
    const val EN = "en"
    private const val KEY = "prism_locale"

    fun getStored(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getString(KEY, SYSTEM) ?: SYSTEM

    fun setStored(context: Context, tag: String) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit().putString(KEY, tag).apply()
    }

    internal fun localeTagsFor(tag: String): List<String> = when (tag) {
        ZH -> listOf("zh-CN")
        ZH_TW -> listOf("zh-TW", "zh-CN")
        EN -> listOf("en")
        else -> emptyList()
    }

    /** Wrap [base] with the stored locale; returns [base] unchanged when following the system. */
    @JvmStatic
    fun wrap(base: Context): Context {
        val locales = localeTagsFor(getStored(base))
            .map(Locale::forLanguageTag)
            .toTypedArray()
        if (locales.isEmpty()) return base
        Locale.setDefault(locales.first())
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(*locales))
        return base.createConfigurationContext(config)
    }
}
