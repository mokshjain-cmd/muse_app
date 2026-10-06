package com.phonebridge

import android.content.Context
import androidx.core.content.edit

/** Connection settings for the optional integrations, kept in app-private preferences. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("integrations", Context.MODE_PRIVATE)

    private fun str(key: String) = prefs.getString(key, "")!!
    private fun put(key: String, v: String) = prefs.edit { putString(key, v.trim()) }

    var asanaToken: String get() = str("asana_token"); set(v) = put("asana_token", v)
    var haUrl: String get() = str("ha_url").trimEnd('/'); set(v) = put("ha_url", v)
    var haToken: String get() = str("ha_token"); set(v) = put("ha_token", v)
    var mqttUrl: String get() = str("mqtt_url"); set(v) = put("mqtt_url", v)
    var mqttUser: String get() = str("mqtt_user"); set(v) = put("mqtt_user", v)
    var mqttPassword: String get() = str("mqtt_password"); set(v) = put("mqtt_password", v)
}
