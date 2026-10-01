package com.meva.applimiter

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Biten her soru denemesini kalıcı olarak kaydeder (SharedPreferences "limiter" → "quiz_log").
 * Satır biçimi: zaman_ms|kişi_key|soru_sayısı|doğru_sayısı|desen   (desen: her soru için 1 = doğru, 0 = yanlış)
 * En fazla MAX deneme tutulur; fazlası en eskiden silinir.
 */
object QuizLog {
    private const val KEY = "quiz_log"
    private const val MAX = 500

    data class Entry(val time: Long, val person: Person, val total: Int, val correct: Int, val pattern: String) {
        val wrong: Int get() = total - correct
        val passed: Boolean get() = total > 0 && correct == total
    }

    fun add(context: Context, s: QuizSession) {
        val prefs = context.getSharedPreferences("limiter", Context.MODE_PRIVATE)
        val pattern = s.results.joinToString("") { if (it) "1" else "0" }
        val line = "${System.currentTimeMillis()}|${s.person.key}|${s.results.size}|${s.results.count { it }}|$pattern"
        val lines = (prefs.getString(KEY, "") ?: "").lines().filter { it.isNotBlank() } + line
        prefs.edit().putString(KEY, lines.takeLast(MAX).joinToString("\n")).apply()
    }

    /** En yeni deneme başta. */
    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences("limiter", Context.MODE_PRIVATE)
        return (prefs.getString(KEY, "") ?: "").lines().mapNotNull { l ->
            val p = l.split("|")
            if (p.size < 5) return@mapNotNull null
            val person = Person.values().firstOrNull { it.key == p[1] } ?: return@mapNotNull null
            val time = p[0].toLongOrNull() ?: return@mapNotNull null
            val total = p[2].toIntOrNull() ?: return@mapNotNull null
            val correct = p[3].toIntOrNull() ?: return@mapNotNull null
            Entry(time, person, total, correct, p[4])
        }.sortedByDescending { it.time }
    }

    fun fmt(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(ms))
}
