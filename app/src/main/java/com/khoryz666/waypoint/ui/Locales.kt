package com.khoryz666.waypoint.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the UI language picked at onboarding to resources, on every API level (no AppCompat).
 * The tag is mirrored into SharedPreferences because attachBaseContext can't wait for DataStore.
 */
object Locales {
    private const val PREFS = "locale"
    private const val KEY = "tag"

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    fun tag(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** [base] with the saved language applied, or unchanged before onboarding picks one. */
    fun wrap(base: Context): Context {
        val tag = tag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}

/** Resources in the app's chosen language, for notifications and other non-Activity text. */
fun Context.localized(): Context = Locales.wrap(applicationContext)
