package com.meva.applimiter

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kilit kartının içindeki soru çözme paneli: giriş → 3 soru (her cevaptan sonra doğru şık gösterilir,
 * "Sonraki soru" ile ilerlenir) → sonuç. Durum QuizSession'da tutulur; panel yalnızca onu çizer.
 */
class QuizPane(context: Context) : LinearLayout(context) {

    interface Host {
        /** Soruları çek ve deneme hakkını tüket. false → başlatılamadı. */
        fun onBegin(session: QuizSession): Boolean
        /** Giriş ekranından geri dönüldü. */
        fun onBack()
        /** Panel yeniden çizildi (newPage: yeni soru/ekran → en üste kaydır, değilse alta kaydır). */
        fun onChanged(newPage: Boolean)
        /** Sonuç ekranında "Tamam"a basıldı. */
        fun onDone(passed: Boolean)
    }

    var host: Host? = null
    private var session: QuizSession? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val dark = 0xFF0F172A.toInt()
    private val muted = 0xFF64748B.toInt()
    private val teal = 0xFF0F9C93.toInt()
    private val tealLight = 0xFF34C9BE.toInt()
    private val green = 0xFF22A06B.toInt()
    private val red = 0xFFDC5B5B.toInt()

    init {
        orientation = VERTICAL
    }

    fun bind(s: QuizSession) {
        session = s
        render(true)
    }

    private fun render(newPage: Boolean) {
        val s = session ?: return
        removeAllViews()
        when {
            !s.started -> renderIntro(s)
            s.finished -> renderResult(s)
            else -> renderQuestion(s)
        }
        host?.onChanged(newPage)
    }

    // ---- yardımcılar ----

    private fun lp(top: Int = 0) =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, align: Int = Gravity.CENTER) =
        TextView(context).apply {
            text = t
            textSize = size
            setTextColor(color)
            gravity = align
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun shape(fill: Int, stroke: Int, radius: Int, strokeW: Int = 1) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(strokeW), stroke)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(tealLight, teal)
        ).apply { cornerRadius = dp(16).toFloat() }
        elevation = dp(4).toFloat()
        setOnClickListener { onClick() }
    }

    private fun linkButton(label: String, onClick: () -> Unit) = text(label, 14f, muted, true).apply {
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { onClick() }
    }

    // ---- giriş ----

    private fun renderIntro(s: QuizSession) {
        val enough = s.available >= QuizBank.COUNT
        addView(text(s.person.label, 24f, dark, true), lp())
        if (enough) {
            addView(text(
                "${QuizBank.COUNT} soru sorulacak.\nHepsini doğru bilirsen ${Config.EXTRA_MS / 60_000} dakika " +
                    "tek seferlik ek süre kazanırsın.\nBu kilitte yalnızca bir kez deneyebilirsin.",
                14f, muted
            ).apply { setLineSpacing(0f, 1.15f) }, lp(10))
            addView(primaryButton("Başla") {
                val ok = host?.onBegin(s) == true
                if (ok) render(true)
            }, lp(24))
        } else {
            addView(text("Bu bölüme henüz yeterli soru eklenmemiş.", 14f, red, true), lp(10))
        }
        addView(linkButton("← Geri") { host?.onBack() }, lp(8))
    }

    // ---- soru ----

    private fun renderQuestion(s: QuizSession) {
        val q = s.questions[s.index]
        val answered = s.chosen >= 0

        addView(text("${s.person.label} · Soru ${s.index + 1}/${s.questions.size}", 13f, muted, true), lp())

        // ilerleme: her soru için bir parça (doğru = yeşil, yanlış = kırmızı, şu an = turkuaz)
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (i in s.questions.indices) {
            val c = when {
                i < s.results.size -> if (s.results[i]) green else red
                i == s.index -> teal
                else -> 0xFFDCE3EC.toInt()
            }
            bar.addView(View(context).apply { background = shape(c, c, 3, 0) },
                LayoutParams(0, dp(6), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        addView(bar, lp(10))

        addView(text(q.text, 18f, dark, true, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = shape(0xFFFFFFFF.toInt(), 0xFFEDF1F6.toInt(), 20)
            elevation = dp(4).toFloat()
        }, lp(16))

        for ((i, opt) in q.options.withIndex()) addView(optionRow(s, q, i, opt), lp(10))

        if (answered) {
            val right = s.chosen == q.correct
            addView(text(
                if (right) "✅ Doğru!"
                else "❌ Yanlış. Doğru cevap: ${'A' + q.correct}) ${q.options[q.correct]}",
                15f, if (right) green else red, true
            ).apply { setPadding(dp(8), 0, dp(8), 0) }, lp(16))

            val last = s.index == s.questions.size - 1
            addView(primaryButton(if (last) "Sonucu gör" else "Sonraki soru  →") {
                s.index++
                s.chosen = -1
                if (s.index >= s.questions.size) {
                    s.finished = true
                    QuizLog.add(context, s)   // tarih/kişi/doğru-yanlış kaydı
                }
                render(true)
            }, lp(14))
        }
    }

    private fun optionRow(s: QuizSession, q: Question, i: Int, opt: String): View {
        val answered = s.chosen >= 0
        val isCorrect = i == q.correct
        val isChosenWrong = answered && i == s.chosen && !isCorrect

        val fill: Int
        val stroke: Int
        val badgeFill: Int
        val badgeText: Int
        when {
            answered && isCorrect -> { fill = 0xFFE8F7EF.toInt(); stroke = green; badgeFill = green; badgeText = 0xFFFFFFFF.toInt() }
            isChosenWrong -> { fill = 0xFFFDECEC.toInt(); stroke = red; badgeFill = red; badgeText = 0xFFFFFFFF.toInt() }
            else -> { fill = 0xFFFFFFFF.toInt(); stroke = 0xFFDCE3EC.toInt(); badgeFill = 0xFFEAF7F6.toInt(); badgeText = teal }
        }

        val badge = text(('A' + i).toString(), 14f, badgeText, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(badgeFill) }
        }
        val label = text(opt, 16f, dark, answered && isCorrect, Gravity.START or Gravity.CENTER_VERTICAL)
        val mark = text(
            when { answered && isCorrect -> "✓"; isChosenWrong -> "✗"; else -> "" },
            18f, if (isCorrect) green else red, true
        )

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = shape(fill, stroke, 16, if (answered && (isCorrect || isChosenWrong)) 2 else 1)
            alpha = if (answered && !isCorrect && !isChosenWrong) 0.55f else 1f
            addView(badge, LayoutParams(dp(32), dp(32)))
            addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
            addView(mark, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = !answered
            setOnClickListener {
                if (s.chosen >= 0) return@setOnClickListener
                s.chosen = i
                s.results.add(i == q.correct)
                render(false)
            }
        }
    }

    // ---- sonuç ----

    private fun renderResult(s: QuizSession) {
        val correct = s.results.count { it }
        val passed = s.passed
        addView(text(if (passed) "🎉" else "😕", 56f, dark), lp(8))
        addView(text(if (passed) "Harikasın!" else "Bu sefer olmadı", 24f, dark, true), lp(8))
        addView(text("$correct / ${s.questions.size} doğru", 18f, if (passed) green else red, true), lp(6))
        addView(text(
            if (passed) "${Config.EXTRA_MS / 60_000} dakika ek süre kazandın"
            else "Ek süre kazanılmadı. Bir sonraki kilitte tekrar deneyebilirsin.",
            14f, muted
        ).apply { setLineSpacing(0f, 1.15f) }, lp(8))

        // soru soru özet
        val summary = StringBuilder()
        for (i in s.results.indices) summary.append(if (s.results[i]) "✅ " else "❌ ")
        addView(text(summary.toString().trim(), 22f, dark), lp(14))

        addView(primaryButton(if (passed) "Süreyi başlat" else "Tamam") { host?.onDone(passed) }, lp(24))
    }
}
