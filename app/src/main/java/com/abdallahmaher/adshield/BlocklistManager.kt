package com.abdallahmaher.adshield

import android.content.Context

object BlocklistManager {

    /** يحمّل قائمة النطاقات المحجوبة من res/raw/blocklist.txt */
    fun load(context: Context): Set<String> {
        val set = HashSet<String>()
        try {
            context.resources.openRawResource(R.raw.blocklist).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            // في حالة فشل القراءة، نكمل بقائمة فاضية بدل ما نوقف الخدمة
        }
        return set
    }
}
