package com.meva.applimiter

import android.content.Context
import android.content.SharedPreferences

/** Kilit kartındaki iki soru bölümü. Sorular app/src/main/assets/<asset> dosyalarından okunur. */
enum class Person(val label: String, val key: String, val asset: String) {
    BEYZA("Beyza", "beyza", "beyza_sorular.txt"),
    FEYZA("Feyza", "feyza", "feyza_sorular.txt")
}

data class Question(val text: String, val options: List<String>, val correct: Int) {
    // Soru metni + şıklardan türeyen sabit kimlik: dosyadaki sıra değişse bile aynı soru aynı kimliği taşır
    val id: String get() = (text + "|" + options.joinToString("|")).hashCode().toString()
}

/**
 * Bir "soru çözme" denemesinin durumu. LimiterService içinde tutulur: ekran kapanıp açılınca,
 * acil arama açılıp dönülünce ya da ekran döndürülünce kart yeniden kurulsa da kaldığı yerden devam eder.
 */
class QuizSession(val person: Person, val cycle: Long, val available: Int) {
    var started = false                       // "Başla"ya basıldı, sorular çekildi
    var questions: List<Question> = emptyList()
    var index = 0                             // şu anki soru
    var chosen = -1                           // şu anki soruda seçilen şık (-1 = henüz cevaplanmadı)
    val results = ArrayList<Boolean>()        // cevaplanan her soru için doğru/yanlış
    var finished = false

    val passed: Boolean
        get() = finished && results.size == questions.size && results.all { it }
}

object QuizBank {
    const val COUNT = 3   // her kilitte sorulan soru sayısı

    private val NUM = Regex("""^\s*\d+\s*[.)]\s*(.*)$""")
    private val OPT = Regex("""^\s*(\*?)\s*([A-Da-d])\s*[.)\-:]\s*(\*?)\s*(.*?)\s*$""")
    private val ANS = Regex(
        """^\s*(?:doğru cevap|dogru cevap|cevap|doğru|dogru|answer)\s*[:=\-]\s*([A-Da-d])\b.*$""",
        RegexOption.IGNORE_CASE
    )

    fun load(context: Context, person: Person): List<Question> {
        val raw = try {
            context.assets.open(person.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        return parse(raw)
    }

    /**
     * Soru dosyası biçimi (boş satırlar önemsiz, # ile başlayan satırlar yorumdur):
     *
     *   1. Soru metni?
     *   A. birinci şık
     *   B. ikinci şık
     *   C. üçüncü şık
     *   D. dördüncü şık
     *   Cevap: C
     *
     * Cevap satırı yerine doğru şıkkın başına * da konabilir (örn. "*C. Cebrail"). Numara (1.) olmasa da olur.
     * Tam 4 şıkkı ve doğru cevabı olmayan kayıtlar atlanır.
     */
    fun parse(raw: String): List<Question> {
        val out = ArrayList<Question>()
        val text = StringBuilder()
        val opts = ArrayList<String>()
        var correct = -1

        fun flush() {
            if (text.isNotBlank() && opts.size == 4 && correct in 0..3) {
                out.add(Question(text.toString().trim(), opts.toList(), correct))
            }
            text.setLength(0)
            opts.clear()
            correct = -1
        }

        for (line in raw.removePrefix("\uFEFF").lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue

            val ans = ANS.matchEntire(l)
            if (ans != null) {
                if (opts.isNotEmpty()) correct = ans.groupValues[1].uppercase()[0] - 'A'
                continue
            }

            val opt = OPT.matchEntire(l)
            if (opt != null && text.isNotEmpty()) {
                val star = opt.groupValues[1].isNotEmpty() || opt.groupValues[3].isNotEmpty()
                if (star) correct = opts.size
                opts.add(opt.groupValues[4])
                continue
            }

            val num = NUM.matchEntire(l)
            if (num != null) {
                flush()
                text.append(num.groupValues[1])
                continue
            }

            // düz metin satırı
            when {
                opts.size >= 4 -> { flush(); text.append(l) }                 // yeni (numarasız) soru
                opts.isNotEmpty() -> opts[opts.lastIndex] = opts.last() + " " + l   // alt satıra taşan şık
                else -> { if (text.isNotEmpty()) text.append(' '); text.append(l) } // çok satırlı soru metni
            }
        }
        flush()
        return out
    }

    /**
     * COUNT adet soru seçer. Kurallar:
     *  - aynı denemede soru tekrar etmez
     *  - bir sonraki denemede önceki denemenin soruları gelmez
     *  - tüm sorular sırayla bir tur dönmeden hiçbir soru ikinci kez sorulmaz (kalıcı "görüldü" listesi)
     */
    fun draw(context: Context, prefs: SharedPreferences, person: Person): List<Question> =
        pick(load(context, person), prefs, person)

    fun pick(bank: List<Question>, prefs: SharedPreferences, person: Person): List<Question> {
        val all = bank.distinctBy { it.id }
        if (all.size <= COUNT) return all.shuffled()

        val seenKey = "quiz_seen_${person.key}"
        val lastKey = "quiz_last_${person.key}"
        val seen = prefs.getStringSet(seenKey, emptySet()) ?: emptySet()
        val last = prefs.getStringSet(lastKey, emptySet()) ?: emptySet()

        val leftover = all.filter { it.id !in seen }
        val picked = ArrayList<Question>()
        var newSeen = HashSet(seen)

        if (leftover.size >= COUNT) {
            picked.addAll(leftover.shuffled().take(COUNT))
        } else {
            // tur bitti: kalan sorular önce gelir, tamamlayıcılar yeni turdan (önceki denemeyi hariç tutarak)
            picked.addAll(leftover)
            newSeen = HashSet()
            val fill = all.filter { q -> q.id !in last && picked.none { it.id == q.id } }
                .shuffled().take(COUNT - picked.size)
            picked.addAll(fill)
            if (picked.size < COUNT) {
                picked.addAll(all.filter { q -> picked.none { it.id == q.id } }.shuffled().take(COUNT - picked.size))
            }
        }

        picked.forEach { newSeen.add(it.id) }
        prefs.edit()
            .putStringSet(seenKey, newSeen)
            .putStringSet(lastKey, HashSet(picked.map { it.id }))
            .apply()
        return picked.shuffled()
    }
}
