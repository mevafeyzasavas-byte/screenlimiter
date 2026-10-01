package com.meva.applimiter

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.EditText

/**
 * Overlay (LimiterService kilit kartı) penceresinde ekran klavyesini ve sistem çubuklarını izler.
 *
 * Neden var: overlay penceresinde klavye açılınca sistem pencereyi kaydırmıyor/küçültmüyor
 * (ADJUST_PAN overlay'de çalışmıyor), şifre alanı + "Aç" butonu klavyenin altında kalıyordu.
 *
 * onChange(bars, kbTotal, kbPad):
 *  - bars    : kartın içeriğinin kaçınması gereken sistem çubuğu payları (px)
 *  - kbTotal : klavyenin toplam yüksekliği (0 = kapalı)  -> kart "kompakt" moda geçer
 *  - kbPad   : klavyenin KARTIN ALTINDA KALAN kısmı. Sistem pencereyi zaten küçülttüyse 0,
 *              küçültmediyse klavye yüksekliği. Alt boşluk olarak uygulanınca çift boşluk olmaz.
 *
 * Birden fazla sinyal birleştirilir: WindowInsets ime, pencere görünür alanı farkı ve kök
 * view'ın küçülmesi. Layout olayı gelmeyen cihazlar için EditText odaktayken 250 ms'de bir ölçülür.
 */
class KeyboardWatcher(
    private val root: View,
    private val onChange: (bars: Rect, kbTotal: Int, kbPad: Int) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = root.resources.displayMetrics.density
    private val minKb = (110 * density).toInt()   // bundan küçük farklar klavye sayılmaz (gezinme çubuğu vb.)

    private var started = false
    private val bars = Rect()
    private var insetsSeen = false
    private var insetKb = 0
    private var maxRootH = 0
    private var lastW = 0
    private var sentTotal = -1
    private var sentPad = -1
    private val sentBars = Rect(-1, -1, -1, -1)

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { publish() }
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> kick() }
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            publish()
            if (root.findFocus() is EditText) handler.postDelayed(this, 250)
        }
    }

    fun start() {
        if (started) return
        started = true
        root.setOnApplyWindowInsetsListener { _, insets ->
            onInsets(insets)
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        if (root.isAttachedToWindow) root.requestApplyInsets()
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        root.setOnApplyWindowInsetsListener(null)
        val vto = root.viewTreeObserver
        if (vto.isAlive) {
            vto.removeOnGlobalLayoutListener(layoutListener)
            vto.removeOnGlobalFocusChangeListener(focusListener)
        }
    }

    private fun kick() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    @Suppress("DEPRECATION")
    private fun onInsets(i: WindowInsets) {
        insetsSeen = true
        if (Build.VERSION.SDK_INT >= 30) {
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            bars.set(b.left, b.top, b.right, b.bottom)
            insetKb = i.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            bars.set(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.stableInsetBottom)
            insetKb = maxOf(0, i.systemWindowInsetBottom - i.stableInsetBottom)
        }
        publish()
    }

    // Bazı cihazlarda overlay penceresine hiç inset gelmez: çubukların altında içerik kalmasın diye
    // kaynaklardan makul bir pay hesapla (alt pay en fazla 32 dp: arka plan aynı renk, görünür boşluk yaratmaz).
    private fun fallbackBars(out: Rect) {
        fun px(name: String): Int {
            val id = root.resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) root.resources.getDimensionPixelSize(id) else 0
        }
        out.set(0, px("status_bar_height"), 0, minOf(px("navigation_bar_height"), (32 * density).toInt()))
    }

    private fun publish() {
        if (!started) return

        // yön değişince referans yüksekliği sıfırla
        if (root.width != lastW) {
            lastW = root.width
            maxRootH = 0
        }
        if (root.height > maxRootH) maxRootH = root.height
        val shrink = maxRootH - root.height               // sistem pencereyi küçülttüyse

        val vis = Rect()
        root.getWindowVisibleDisplayFrame(vis)
        val gap = root.rootView.height - vis.bottom       // görünür alanın altında kalan kısım

        val total = maxOf(insetKb, gap, shrink).let { if (it >= minKb) it else 0 }
        val pad = if (total > 0) maxOf(0, total - shrink) else 0

        val b = Rect(bars)
        if (!insetsSeen || (b.left == 0 && b.top == 0 && b.right == 0 && b.bottom == 0)) fallbackBars(b)

        if (total == sentTotal && pad == sentPad && b == sentBars) return
        sentTotal = total
        sentPad = pad
        sentBars.set(b)
        onChange(b, total, pad)
    }
}
