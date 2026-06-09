package com.example.cxrglobal

import org.json.JSONObject

data class GlassInfo(
    val deviceName: String = "",
    val batteryLevel: Int = -1,
    val sound: Int = -1,
    val brightness: Int = -1,
    val systemVersion: String = "",
    val isCharging: Boolean = false,
    val sn: String = "",
    val wearingStatus: String = "",
) {
    val redactedSn: String
        get() = when {
            sn.isBlank() -> ""
            sn.length <= 4 -> "****"
            else -> "****${sn.takeLast(4)}"
        }

    companion object {
        fun fromJson(json: String): GlassInfo {
            val obj = JSONObject(json)
            return GlassInfo(
                deviceName = obj.optString("deviceName"),
                batteryLevel = obj.optInt("batteryLevel", -1),
                sound = obj.optInt("sound", -1),
                brightness = obj.optInt("brightness", -1),
                systemVersion = obj.optString("systemVersion"),
                isCharging = obj.optBoolean("ischarging", obj.optBoolean("isCharging", false)),
                sn = obj.optString("sn"),
                wearingStatus = obj.optString("wearingStatus"),
            )
        }
    }
}
