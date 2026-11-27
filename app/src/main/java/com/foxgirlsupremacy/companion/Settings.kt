package com.foxgirlsupremacy.companion

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var companionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var scale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SCALE, value).apply()

    var reactionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_REACTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_REACTIONS, value).apply()

    var animationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()

    var wanderEnabled: Boolean
        get() = prefs.getBoolean(KEY_WANDER, true)
        set(value) = prefs.edit().putBoolean(KEY_WANDER, value).apply()

    var snowFox: Boolean
        get() = prefs.getBoolean(KEY_SNOW, false)
        set(value) = prefs.edit().putBoolean(KEY_SNOW, value).apply()

    var modelId: String
        get() = prefs.getString(KEY_MODEL, "hiyori") ?: "hiyori"
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var posX: Int
        get() = prefs.getInt(KEY_POS_X, -1)
        set(value) = prefs.edit().putInt(KEY_POS_X, value).apply()

    var posY: Int
        get() = prefs.getInt(KEY_POS_Y, -1)
        set(value) = prefs.edit().putInt(KEY_POS_Y, value).apply()

    fun savePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
    }

    fun animMapping(): AnimMapping = AnimMapping(prefs, modelId)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS = "fgs_settings"
        const val KEY_ENABLED = "companion_enabled"
        const val KEY_SCALE = "scale"
        const val KEY_REACTIONS = "reactions_enabled"
        const val KEY_ANIMATIONS = "animations_enabled"
        const val KEY_WANDER = "wander_enabled"
        const val KEY_SNOW = "snow_fox"
        const val KEY_MODEL = "model_id"
        const val KEY_POS_X = "pos_x"
        const val KEY_POS_Y = "pos_y"
    }
}
