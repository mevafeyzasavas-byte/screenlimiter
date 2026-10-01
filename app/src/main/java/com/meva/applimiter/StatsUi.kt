package com.meva.applimiter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Üç yükselen yuvarlak çubuk + ince taban çizgisi: kod ile çizilen sade bar-grafik ikonu (dosya gerektirmez). */
class StatsIconView(context: Context, tint: Int) : View(context) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint; alpha = 110 }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w * 0.20f
        val gap = w * 0.12f
        val total = bw * 3 + gap * 2
        val x0 = (w - total) / 2f
        val floor = h * 0.80f
        val heights = floatArrayOf(h * 0.30f, h * 0.50f, h * 0.70f)
        for (i in 0..2) {
            val left = x0 + i * (bw + gap)
            rect.set(left, floor - heights[i], left + bw, floor)
            c.drawRoundRect(rect, bw / 2.5f, bw / 2.5f, bar)
        }
        rect.set(x0 - gap * 0.6f, floor + h * 0.07f, x0 + total + gap * 0.6f, floor + h * 0.12f)
        c.drawRoundRect(rect, h * 0.03f, h * 0.03f, base)
    }
}

/** Kilit kartı, şifre ekranları ve ana ekranda ortak kullanılan istatistik butonu ile istatistik paneli. */
object StatsUi {
    private val DARK = 0xFF0F172A.toInt()
    private val MUTED = 0xFF64748B.toInt()
    private val TEAL = 0xFF0F9C93.toInt()
    private val TEAL_LIGHT = 0xFF34C9BE.toInt()
    private val GREEN = 0xFF22A06B.toInt()
    private val RED = 0xFFDC5B5B.toInt()
    private val BORDER = 0xFFEDF1F6.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dpOf(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun badge(context: Context, size: Int, icon: Int, radius: Int): View {
        return FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(TEAL_LIGHT, TEAL)
            ).apply { cornerRadius = dpOf(context, radius).toFloat() }
            addView(StatsIconView(context, WHITE), FrameLayout.LayoutParams(dpOf(context, icon), dpOf(context, icon), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpOf(context, size), dpOf(context, size))
        }
    }

    /** Premium görünümlü "Soru istatistikleri" butonu (ikon rozeti + başlık + ok). */
    fun button(context: Context, onClick: () -> Unit): View {
        fun dp(v: Int) = dpOf(context, v)
        val title = TextView(context).apply {
            text = "Soru istatistikleri"
            textSize = 15f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(context).apply {
            text = "Sonuçlar · tarih · doğru / yanlış"
            textSize = 11.5f
            setTextColor(MUTED)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
        }
        val chevron = TextView(context).apply {
            text = "›"
            textSize = 26f
            setTextColor(TEAL)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(WHITE)
                setStroke(dp(1), BORDER)
            }
            elevation = dp(4).toFloat()
            addView(badge(context, 40, 22, 12))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
            addView(chevron, LinearLayout.LayoutParams(WRAP, WRAP))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** İstatistik paneli: kişi başına özet kartı + son denemeler listesi + kapat butonu. */
    fun pane(context: Context, closeLabel: String, onClose: () -> Unit): View {
        fun dp(v: Int) = dpOf(context, v)
        val log = QuizLog.load(context)

        fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
        fun card() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(WHITE)
                setStroke(dp(1), BORDER)
            }
            elevation = dp(4).toFloat()
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        // başlık
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(context, 36, 20, 11))
            addView(tv("Soru istatistikleri", 20f, DARK, true),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }, lp())

        // kişi özet kartları
        for (p in Person.values()) {
            val l = log.filter { it.person == p }
            val c = card()
            c.addView(tv(p.label, 17f, DARK, true))
            if (l.isEmpty()) {
                c.addView(tv("Henüz tamamlanmış deneme yok", 13f, MUTED), lp(4))
            } else {
                val total = l.sumOf { it.total }
                val correct = l.sumOf { it.correct }
                val wrong = total - correct
                val pct = if (total > 0) correct * 100 / total else 0
                val passed = l.count { it.passed }
                c.addView(tv("%$pct doğru", 26f, if (pct >= 50) GREEN else RED, true), lp(6))

                val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                if (correct > 0) bar.addView(View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(GREEN) }
                }, LinearLayout.LayoutParams(0, dp(8), correct.toFloat()))
                if (wrong > 0) bar.addView(View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(RED) }
                }, LinearLayout.LayoutParams(0, dp(8), wrong.toFloat()).apply { marginStart = dp(2) })
                c.addView(bar, lp(8))

                c.addView(tv("✅ $correct   ❌ $wrong   ·   toplam $total soru", 13f, DARK, true), lp(10))
                c.addView(tv("Deneme: ${l.size}  ·  geçti: $passed  ·  kaldı: ${l.size - passed}", 13f, MUTED), lp(4))
                c.addView(tv("Son deneme: ${QuizLog.fmt(l.first().time)}", 12f, MUTED), lp(2))
            }
            root.addView(c, lp(14))
        }

        // son denemeler
        if (log.isNotEmpty()) {
            root.addView(tv("Son denemeler", 14f, MUTED, true), lp(20))
            val listCard = card()
            for ((i, e) in log.take(30).withIndex()) {
                if (i > 0) listCard.addView(View(context).apply { setBackgroundColor(BORDER) },
                    LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
                val left = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tv(QuizLog.fmt(e.time), 13f, DARK, true))
                    addView(tv(e.person.label, 12f, MUTED))
                }
                val marks = e.pattern.map { if (it == '1') "✅" else "❌" }.joinToString("")
                val right = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(tv("${e.correct}/${e.total}", 15f, if (e.passed) GREEN else RED, true).apply { gravity = Gravity.END })
                    addView(tv(marks, 12f, DARK).apply { gravity = Gravity.END })
                }
                listCard.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(right, LinearLayout.LayoutParams(WRAP, WRAP))
                }, lp())
            }
            root.addView(listCard, lp(8))
        }

        // kapat / geri
        root.addView(TextView(context).apply {
            text = closeLabel
            textSize = 16f
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(TEAL_LIGHT, TEAL)
            ).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(4).toFloat()
            isClickable = true
            setOnClickListener { onClose() }
        }, lp(20))

        return root
    }
}
