package dev.aliberke.sesli

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sesli", Context.MODE_PRIVATE)

    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(value) = sp.edit { putFloat("rate", value) }

    var enginePackage: String?
        get() = sp.getString("engine", null)
        set(value) = sp.edit { putString("engine", value) }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(value) = sp.edit { putBoolean("asked_notifications", value) }

    /** Neural models whose default voice has already been made the preferred voice once. */
    var adoptedModels: Set<String>
        get() = sp.getStringSet("adopted_models", emptySet()).orEmpty().toSet()
        set(value) = sp.edit { putStringSet("adopted_models", value.toSet()) }

    /** Preferred voice per primary language subtag ("en", "tr", ...). */
    fun voiceFor(language: String): String? = sp.getString("voice_$language", null)

    fun setVoiceFor(language: String, voiceName: String) = sp.edit { putString("voice_$language", voiceName) }
}
