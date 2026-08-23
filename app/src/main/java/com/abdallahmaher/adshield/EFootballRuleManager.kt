package com.abdallahmaher.adshield

import android.content.Context
import org.json.JSONObject

/**
 * قواعد خاصة بلعبة eFootball لتقليل اللاج:
 * - blockedHosts: سيرفرات AWS Load Balancer بتتغير باستمرار وبتسبب توجيه لسيرفرات بعيدة/بطيئة، بنحجبها.
 * - redirectedHosts: سيرفر اللعبة الرئيسي بيتوجّه لعنوان IP محدد يدويًا بدل ما النظام يختار سيرفر بعيد تلقائيًا.
 */
data class EFootballRules(
    val blockedHosts: Set<String>,
    val redirectedHosts: Map<String, String>
)

object EFootballRuleManager {
    private const val ASSET_FILE = "efootball_no_lag.json"

    fun load(context: Context): EFootballRules {
        return try {
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val blocked = HashSet<String>()
            json.optJSONArray("blocked")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optBoolean("enabled", true)) {
                        blocked.add(obj.getString("host").lowercase())
                    }
                }
            }

            val redirected = HashMap<String, String>()
            json.optJSONArray("redirected")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optBoolean("enabled", true)) {
                        redirected[obj.getString("host").lowercase()] = obj.getString("redirect")
                    }
                }
            }

            EFootballRules(blocked, redirected)
        } catch (e: Exception) {
            EFootballRules(emptySet(), emptyMap())
        }
    }
}
