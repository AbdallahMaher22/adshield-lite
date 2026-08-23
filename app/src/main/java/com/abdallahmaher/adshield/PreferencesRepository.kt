package com.abdallahmaher.adshield

import android.content.Context

object PreferencesRepository {
    private const val PREFS_NAME = "adshield_prefs"
    private const val KEY_EFOOTBALL_ENABLED = "efootball_enabled"
    private const val KEY_EFOOTBALL_APPS = "efootball_target_apps"

    fun isEFootballEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EFOOTBALL_ENABLED, false)

    fun setEFootballEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EFOOTBALL_ENABLED, enabled).apply()
    }

    fun getEFootballTargetApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EFOOTBALL_APPS, emptySet()) ?: emptySet()

    fun setEFootballTargetApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_EFOOTBALL_APPS, packages).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
