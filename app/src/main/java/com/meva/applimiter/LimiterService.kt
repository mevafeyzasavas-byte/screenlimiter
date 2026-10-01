package com.meva.applimiter

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.transition.TransitionManager
import android.util.DisplayMetrics
import android.text.InputType
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class LimiterService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("limiter", Context.MODE_PRIVATE) }

    private var currentPkg: String? = null
    private var enteredAt = 0L              // 0 = sayaç çalışmıyor
    private var countingExtra = false       // true ise aktif sayaç 15 dk'lık ek süreye ait
    private var limitRunnable: Runnable? = null
    private var receiverRegistered = false

    private var overlayView: View? = null
    private var overlayTick: Runnable? = null

    private var lastUnlockPrompt = 0L

    // ---- ekran süresi limiti (tüm telefon) ----
    private var scrOnAt = 0L                 // 0 = ekran sayacı çalışmıyor
    private var scrRunnable: Runnable? = null
    private var scrCheck: Runnable? = null
    private var scrLockView: View? = null
    private var scrLockTick: Runnable? = null
    private var scrGifRotate: Runnable? = null   // kilit kartındaki gif'i 7 sn'de bir değiştirir
    private var scrPwdTick: Runnable? = null     // yanlış deneme kilidinin geri sayımı
    private var lastWaitGif = -1
    // gif durumu servis tarafında tutulur: ekran kapanıp açılınca kart yeniden kurulsa da
    // lock_gif'ten tekrar başlamaz, kaldığı yerden devam eder
    private var scrGifRes = R.raw.lock_gif       // kartta şu an gösterilen gif
    private var scrGifSince = 0L                 // o gif'in ekrana geldiği an (elapsedRealtime)
    private var scrGifCycle = 0L                 // hangi kilit döngüsüne ait (scr_lock_until)
    private var scrKb: KeyboardWatcher? = null   // kart içindeki klavye/sistem çubuğu izleyicisi
    private val waitGifs = intArrayOf(
        R.raw.wait_gif, R.raw.wait_gif_3, R.raw.wait_gif_4,
        R.raw.wait_gif_5, R.raw.wait_gif_6, R.raw.wait_gif_7
    )
    private var scrSuppressUntil = 0L        // acil arama/SMS/şifre açılırken kartı kısa süre gizli tut
    private var fgPkg: String? = null        // ekrandaki son uygulama (kilit kartı kararı için)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "scr_extra_until" || key == "scr_enabled") enforceScreenLock()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { stopCounting(); onScreenOff() }
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_USER_PRESENT -> {
                    currentPkg?.let { onForeground(it) }
                    if (scrEnabled()) enforceScreenLock()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, f)
            }
            receiverRegistered = true
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (scrEnabled() && isInteractive()) enforceScreenLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // silme / kapatma koruması: ayar, yükleyici ve launcher ekranlarını tara
        if (isSensitive(pkg) &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !isLauncher(pkg))
        ) {
            scheduleGuardScan()
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // ekran süresi kilidi: ön plandaki paketi izle (kendi kilit kartımızın olaylarını yok say,
        // ama şifre ekranı = MainActivity olaylarını say)
        val ownOverlayEvent = pkg == packageName &&
            event.className?.toString() != MainActivity::class.java.name
        if (!ownOverlayEvent && pkg !in ignoredPackages()) {
            fgPkg = pkg
            if (scrEnabled()) enforceScreenLock()
        }

        if (pkg == packageName && (overlayView != null || scrLockView != null)) return   // kendi kartımızı yok say
        if (pkg in ignoredPackages()) return
        onForeground(pkg)
    }

    // Yön değişince tam ekran kart yeni ekran boyutuyla yeniden kurulur (gif/zaman kaldığı yerden devam eder)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (scrLockView != null) {
            hideScreenLock()
            enforceScreenLock()
        }
    }

    override fun onInterrupt() {
        stopCounting()
        hideOverlay()
        scrStop()
        hideScreenLock()
    }

    override fun onDestroy() {
        stopCounting()
        hideOverlay()
        scrStop()
        hideScreenLock()
        scrCheck?.let { handler.removeCallbacks(it) }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    // ---- mantık ----

    private fun onForeground(pkg: String) {
        if (pkg != currentPkg) {
            stopCounting()          // eski uygulamanın süresini kaydet (ve sınırsızsa yeniden kilitle)
            currentPkg = pkg
        }
        if (pkg !in selected()) return

        if (isUnlimited(pkg)) {
            if (!UnlockState.isUnlocked(pkg)) {
                requestPassword(pkg)
            }
            return
        }

        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong("lock_$pkg", 0L)
        if (now < lockUntil) {
            if (isExtraActive(pkg)) {
                startExtraCounting(pkg)
                return
            }
            requestExtraUnlock(pkg)
            return
        }
        startCounting(pkg)
    }

    private fun isExtraActive(pkg: String): Boolean = prefs.getBoolean("extra_active_$pkg", false)

    // Kilitli bir uygulama tekrar açılmaya çalışıldığında şifre ekranını göster.
    // Doğru şifreyle bu kilit döngüsü için tek seferlik 15 dakikalık ek süre verilir.
    private fun requestExtraUnlock(pkg: String) {
        if (!isReallyForeground(pkg)) return
        if (SystemClock.elapsedRealtime() - lastUnlockPrompt < 1500) return
        lastUnlockPrompt = SystemClock.elapsedRealtime()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("extraPkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        ensureLeft(pkg)
    }

    // Olay gerçekten şu an ekranda olan uygulamadan mı geliyor? Arka plandaki (ör. şifre
    // ekranı yüzünden arkaya itilen) kilitli uygulamanın gecikmeli pencere olayları şifre
    // ekranını tekrar tekrar açıp "Ana ekran" tuşunu etkisiz bırakıyordu.
    private fun isReallyForeground(pkg: String): Boolean {
        val active = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        return active == null || active == pkg
    }

    // Şifre ekranı bir sebeple açılamadıysa (arka plandan başlatma engeli vb.) kilitli
    // uygulama ekranda kalmasın: hâlâ ondaysak ana ekrana gönder.
    private fun ensureLeft(pkg: String) {
        handler.postDelayed({
            val active = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (active == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 900)
    }

    private fun isUnlimited(pkg: String): Boolean = prefs.getBoolean("unlimited_$pkg", false)

    // "Sınırsız" işaretli uygulama açılmak istendiğinde şifre ekranını göster.
    // NOT: burada performGlobalAction(GLOBAL_ACTION_HOME) KULLANILMIYOR — HOME asenkron
    // çalıştığı için startActivity ile yarışıyor ve bazen MainActivity'yi daha o ekrana
    // gelmeden eve gönderiyordu (A kapanıyor ama şifre ekranı hiç görünmüyordu).
    // MainActivity'yi NEW_TASK ile başlatmak zaten A'nın önüne geçip onu ekrandan kaldırır.
    private fun requestPassword(pkg: String) {
        if (!isReallyForeground(pkg)) return
        if (SystemClock.elapsedRealtime() - lastUnlockPrompt < 1500) return
        lastUnlockPrompt = SystemClock.elapsedRealtime()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("unlockPkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        ensureLeft(pkg)
    }

    private fun startCounting(pkg: String) {
        if (enteredAt != 0L) return
        val runLimit = prefs.getLong("runlimit_$pkg", Config.RUN_LIMIT_MS)
        val remaining = runLimit - prefs.getLong("used_$pkg", 0L)
        if (remaining <= 0) {
            lock(pkg)
            return
        }
        enteredAt = SystemClock.elapsedRealtime()
        countingExtra = false
        val r = Runnable { lock(pkg) }
        limitRunnable = r
        handler.postDelayed(r, remaining)
    }

    // Şifreyle açılan kilitli uygulama için tek seferlik 15 dk'lık ek süre sayacı.
    // "used_$pkg" (ana döngü sayacı) ile karışmaması için ayrı bir anahtar kullanır.
    private fun startExtraCounting(pkg: String) {
        if (enteredAt != 0L) return
        val remaining = Config.EXTRA_MS - prefs.getLong("extra_used_$pkg", 0L)
        if (remaining <= 0) {
            lock(pkg)
            return
        }
        enteredAt = SystemClock.elapsedRealtime()
        countingExtra = true
        val r = Runnable { lock(pkg) }
        limitRunnable = r
        handler.postDelayed(r, remaining)
    }

    private fun stopCounting() {
        limitRunnable?.let { handler.removeCallbacks(it) }
        limitRunnable = null
        val pkg = currentPkg
        if (enteredAt != 0L && pkg != null) {
            val elapsed = SystemClock.elapsedRealtime() - enteredAt
            if (countingExtra) {
                val used = prefs.getLong("extra_used_$pkg", 0L) + elapsed
                prefs.edit().putLong("extra_used_$pkg", used).apply()
            } else {
                val used = prefs.getLong("used_$pkg", 0L) + elapsed
                prefs.edit().putLong("used_$pkg", used).apply()
            }
        }
        enteredAt = 0L
        countingExtra = false
        // sınırsız (şifreli) bir uygulamadan çıkılıyorsa, bir sonraki açılışta yine şifre sorulsun
        if (pkg != null && isUnlimited(pkg)) UnlockState.lock(pkg)
    }

    private fun lock(pkg: String) {
        limitRunnable = null
        enteredAt = 0L
        countingExtra = false
        val lockLen = prefs.getLong("locklen_$pkg", Config.LOCK_MS)
        val lockUntil = System.currentTimeMillis() + lockLen
        prefs.edit()
            .putLong("used_$pkg", 0L)
            .putLong("lock_$pkg", lockUntil)
            .putBoolean("extra_active_$pkg", false)
            .putLong("extra_used_$pkg", 0L)
            .apply()
        showOverlay(pkg, "Süre doldu", "Kilidin açılmasına kalan süre", lockUntil)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---- ekran süresi limiti ----
    // Ekran açık kalma süresi hem "kesintisiz" hem "gün içi toplam" olarak sayılır; hangisi
    // önce dolarsa telefon kilit süresi boyunca tam ekran geri sayım kartıyla kaplanır.
    // Kart altında yalnızca acil arama / SMS / şifre (ek süre) serbesttir.

    private fun scrEnabled() = prefs.getBoolean("scr_enabled", false)
    private fun scrLockUntil() = prefs.getLong("scr_lock_until", 0L)
    private fun scrExtraUntil() = prefs.getLong("scr_extra_until", 0L)

    private fun isInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    // gün değiştiyse günlük sayaç sıfırlanır
    private fun rollDay() {
        val t = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
        if (prefs.getString("scr_day", "") != t) {
            prefs.edit().putString("scr_day", t).putLong("scr_daily_used", 0L).apply()
        }
    }

    // çalışan sayaçtaki süreyi kalıcı sayaçlara ekler, saymaya kaldığı yerden devam eder
    private fun scrFlush() {
        if (scrOnAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val e = now - scrOnAt
        scrOnAt = now
        rollDay()
        prefs.edit()
            .putLong("scr_cont_used", prefs.getLong("scr_cont_used", 0L) + e)
            .putLong("scr_daily_used", prefs.getLong("scr_daily_used", 0L) + e)
            .apply()
    }

    private fun scrStop() {
        scrRunnable?.let { handler.removeCallbacks(it) }
        scrRunnable = null
        scrFlush()
        scrOnAt = 0L
    }

    private fun scrStart() {
        if (!scrEnabled() || scrOnAt != 0L) return
        if (System.currentTimeMillis() < scrLockUntil()) return   // kilit / ek süre sırasında sayma
        rollDay()
        val contLimit = prefs.getLong("scr_cont_ms", Config.SCREEN_CONT_MS)
        val dailyLimit = prefs.getLong("scr_daily_ms", Config.SCREEN_DAILY_MS)
        var remaining = Long.MAX_VALUE
        if (contLimit > 0) remaining = minOf(remaining, contLimit - prefs.getLong("scr_cont_used", 0L))
        if (dailyLimit > 0) remaining = minOf(remaining, dailyLimit - prefs.getLong("scr_daily_used", 0L))
        if (remaining == Long.MAX_VALUE) return                   // ikisi de kapalı
        if (remaining <= 0) {
            screenLimitReached()
            return
        }
        scrOnAt = SystemClock.elapsedRealtime()
        val r = Runnable { screenLimitReached() }
        scrRunnable = r
        handler.postDelayed(r, remaining)
    }

    private fun screenLimitReached() {
        scrRunnable = null
        scrOnAt = 0L
        val lockLen = prefs.getLong("scr_lock_ms", Config.SCREEN_LOCK_MS)
        prefs.edit()
            .putLong("scr_cont_used", 0L)
            .putLong("scr_daily_used", 0L)
            .putLong("scr_lock_until", System.currentTimeMillis() + lockLen)
            .putLong("scr_extra_until", 0L)
            .putBoolean("scr_extra_used", false)
            .apply()
        enforceScreenLock()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun onScreenOff() {
        scrStop()
        fgPkg = null
        prefs.edit().putLong("scr_off_at", System.currentTimeMillis()).apply()
        hideScreenLock()
    }

    private fun onScreenOn() {
        if (!scrEnabled()) return
        val off = prefs.getLong("scr_off_at", 0L)
        // ekran uzun süre kapalı kaldıysa "kesintisiz" sayaç sıfırlanır
        if (off != 0L && System.currentTimeMillis() - off >= Config.SCREEN_GAP_MS) {
            prefs.edit().putLong("scr_cont_used", 0L).apply()
        }
        enforceScreenLock()
    }

    // Tek karar noktası: kilit var mı, kartı göster/gizle, yoksa sayacı başlat.
    private fun enforceScreenLock() {
        if (!scrEnabled()) {
            hideScreenLock()
            scrStop()
            return
        }
        val now = System.currentTimeMillis()
        if (now < scrLockUntil()) {
            val p = fgPkg
            val allowed = now < scrExtraUntil() || GateState.scrGateOpen ||
                (p != null && p in scrAllowedPkgs())
            if (allowed) hideScreenLock()
            else if (SystemClock.elapsedRealtime() >= scrSuppressUntil && isInteractive()) showScreenLock()
            scheduleScrCheck()
        } else {
            hideScreenLock()
            if (isInteractive()) scrStart()
        }
    }

    // Kilit / ek süre bitişinde kartı ve sayacı olaysız da güncelle
    private fun scheduleScrCheck() {
        scrCheck?.let { handler.removeCallbacks(it) }
        scrCheck = null
        val now = System.currentTimeMillis()
        val until = scrLockUntil()
        if (!scrEnabled() || now >= until) return
        val next = if (now < scrExtraUntil()) minOf(scrExtraUntil(), until) else until
        val r = Runnable { enforceScreenLock() }
        scrCheck = r
        handler.postDelayed(r, maxOf(500L, next - now + 300))
    }

    // Kilitliyken kartın altında serbest kalan paketler: arama/SMS uygulamaları + kendimiz (şifre)
    private fun scrAllowedPkgs(): Set<String> {
        val s = mutableSetOf(
            packageName, "com.android.incallui", "com.android.server.telecom",
            "com.android.phone", "com.android.emergency", "com.samsung.android.incallui"
        )
        try { (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { s.add(it) } } catch (e: Exception) {}
        try { Telephony.Sms.getDefaultSmsPackage(this)?.let { s.add(it) } } catch (e: Exception) {}
        for (i in listOf(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")), Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))) {
            try {
                packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.let { s.add(it) }
            } catch (e: Exception) {}
        }
        return s
    }

    // Kartı kısa süre gizli tutup uygulamayı aç; açılamazsa kart geri gelir
    private fun openBelowCard(open: () -> Unit) {
        hideScreenLock()
        scrSuppressUntil = SystemClock.elapsedRealtime() + 2000
        try { open() } catch (e: Exception) { scrSuppressUntil = 0L; enforceScreenLock() }
        handler.postDelayed({ enforceScreenLock() }, 2200)
    }

    // Bir öncekinden farklı rastgele bekleme gif'i
    private fun pickWaitGif(): Int {
        var i: Int
        do { i = waitGifs.indices.random() } while (waitGifs.size > 1 && i == lastWaitGif)
        lastWaitGif = i
        return waitGifs[i]
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f).apply {
            duration = 380
            start()
        }
    }

    // Doğru şifre: bu kilit döngüsü için tek seferlik ek süre ver, kartı kaldır
    private fun grantScreenExtra() {
        if (prefs.getBoolean("scr_extra_used", false)) {
            toast("Bu kilit döngüsünde ek süre zaten kullanıldı")
            return
        }
        prefs.edit()
            .putBoolean("scr_extra_used", true)
            .putLong("scr_extra_until", System.currentTimeMillis() + Config.EXTRA_MS)
            .apply()
        toast("${Config.EXTRA_MS / 60_000} dk ek süre verildi")
        enforceScreenLock()
    }

    private fun gifPeriod(res: Int) = if (res == R.raw.lock_gif) GIF_FIRST_MS else GIF_ROTATE_MS

    // Ekranın gerçek (sistem çubukları dahil) boyutu: kart bunun tamamını kaplasın
    @Suppress("DEPRECATION")
    private fun realScreenSize(wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    // Tek ekran: şifre ekranı tasarımı + geri sayım + acil arama/SMS.
    // İlk GIF_FIRST_MS lock_gif, sonra her GIF_ROTATE_MS'de bir rastgele bekleme gif'i.
    //
    // Yerleşim: tam ekran pencere (çentik ve sistem çubukları dahil) →
    //   [ kaydırılabilir orta bölüm: gif, başlık, geri sayım, şifre kartı ]
    //   [ sabit alt çubuk: Acil arama / SMS ]  (her zaman tam görünür)
    // Klavye açılınca "kompakt mod": gif/başlık/alt çubuk gizlenir, geri sayım + şifre alanı + "Aç"
    // butonu klavyenin hemen üstünde kalır.
    private fun showScreenLock() {
        if (scrLockView != null) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val accentDark = 0xFF0F9C93.toInt()
        val accentLight = 0xFF34C9BE.toInt()

        fun label(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(top) }
            }

        fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFEDF1F6.toInt())
            }
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }

        // ---- gif durumu: kart ekran kapanıp açılınca yeniden kurulsa da kaldığı yerden devam eder ----
        val nowRt = SystemClock.elapsedRealtime()
        val cycle = scrLockUntil()
        if (scrGifCycle != cycle) {              // yeni kilit döngüsü: lock_gif ile başla
            scrGifCycle = cycle
            scrGifRes = R.raw.lock_gif
            scrGifSince = nowRt
        } else if (nowRt - scrGifSince >= gifPeriod(scrGifRes)) {   // ekran kapalıyken süre geçti
            scrGifRes = pickWaitGif()
            scrGifSince = nowRt
        }
        val gifSize = minOf(dp(220), (resources.displayMetrics.heightPixels * 0.26f).toInt())
        val gifView = GifView(this).apply { setGifResource(scrGifRes) }

        val title = label("Ekran süresi doldu", 22f, 0xFF0F172A.toInt(), true, 8)
        val countdown = label(clock(scrLockUntil() - System.currentTimeMillis()), 56f, accentDark, true, 12)
        countdown.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val subtitle = label("Kilidin açılmasına kalan süre", 14f, 0xFF64748B.toInt(), false, 4)

        val extraAvailable = !prefs.getBoolean("scr_extra_used", false)

        // ---- şifre kartı (MainActivity'deki şifre ekranıyla aynı tasarım) ----
        val input = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            textSize = 18f
            letterSpacing = 0.04f
            setTextColor(0xFF0F172A.toInt())
            setHintTextColor(0xFFA0AEC0.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF8FAFC.toInt())
                setStroke(dp(1), 0xFFDCE3EC.toInt())
            }
        }

        val okBtn = TextView(this).apply {
            text = "Aç"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accentLight, accentDark)
            ).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(4).toFloat()
        }

        val lockLabel = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFFDC5B5B.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFEDF1F6.toInt())
            }
            elevation = dp(10).toFloat()
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(okBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
            addView(lockLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- yanlış deneme sınırı (MainActivity ile aynı sayaçlar) ----
        fun setPwdLocked(isLocked: Boolean) {
            input.isEnabled = !isLocked
            okBtn.isEnabled = !isLocked
            okBtn.alpha = if (isLocked) 0.45f else 1f
            lockLabel.visibility = if (isLocked) View.VISIBLE else View.GONE
        }

        fun tickPwdLock() {
            val left = prefs.getLong("pwd_lock_until", 0L) - System.currentTimeMillis()
            if (left <= 0) {
                setPwdLocked(false)
                scrPwdTick = null
                return
            }
            lockLabel.text = "Çok fazla yanlış deneme\nKalan: ${clock(left)}"
            val t = Runnable { tickPwdLock() }
            scrPwdTick = t
            handler.postDelayed(t, 1000)
        }

        if (System.currentTimeMillis() < prefs.getLong("pwd_lock_until", 0L)) {
            setPwdLocked(true)
            tickPwdLock()
        }

        val check = {
            if (System.currentTimeMillis() < prefs.getLong("pwd_lock_until", 0L)) {
                // kilitliyken alan/buton zaten devre dışı
            } else if (input.text.toString() == Config.PASSWORD) {
                prefs.edit().putInt("pwd_fail_count", 0).apply()
                grantScreenExtra()
            } else {
                shake(input)
                input.setText("")
                val fails = prefs.getInt("pwd_fail_count", 0) + 1
                if (fails >= Config.PASSWORD_MAX_ATTEMPTS) {
                    val until = System.currentTimeMillis() + Config.PASSWORD_LOCKOUT_MS
                    prefs.edit().putInt("pwd_fail_count", 0).putLong("pwd_lock_until", until).apply()
                    toast("Çok fazla yanlış deneme, 5 dakika kilitlendi")
                    setPwdLocked(true)
                    tickPwdLock()
                } else {
                    prefs.edit().putInt("pwd_fail_count", fails).apply()
                    toast("Yanlış şifre. Kalan deneme: ${Config.PASSWORD_MAX_ATTEMPTS - fails}")
                }
            }
        }
        input.setOnEditorActionListener { _, _, _ -> check(); true }
        okBtn.setOnClickListener { check() }

        val hint = label(
            if (extraAvailable) "Doğru şifreyle tek seferlik ${Config.EXTRA_MS / 60_000} dakika ek süre kazanırsın"
            else "Bu kilit döngüsünde ek süre zaten kullanıldı",
            12f, 0xFF94A3B8.toInt(), false, if (extraAvailable) 12 else 24
        )

        // ---- orta bölüm (kaydırılabilir; kısa kalırsa dikey ortalanır) ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(16), dp(28), dp(16))
            isFocusableInTouchMode = true       // klavyeyi kapatırken odak EditText'e geri dönmesin
            addView(gifView,
                LinearLayout.LayoutParams(gifSize, gifSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(title)
            addView(countdown)
            addView(subtitle)
            if (extraAvailable) {
                addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24) })
            }
            addView(hint)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- sabit alt çubuk: acil arama / SMS (kilitliyken de serbest, her zaman tam görünür) ----
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
            addView(pill("📞 Acil arama") {
                openBelowCard { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            addView(pill("✉ SMS") {
                openBelowCard { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt())
            )
            isClickable = true   // dokunuşlar alttaki uygulamaya geçmesin
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // boş bir yere dokununca klavye kapansın
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val dismissKeyboard = {
            content.requestFocus()
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        root.setOnClickListener { dismissKeyboard() }
        content.setOnClickListener { dismissKeyboard() }

        // ---- klavye açılınca kompakt mod ----
        var compact = false
        fun setCompact(on: Boolean) {
            if (compact == on) return
            compact = on
            TransitionManager.beginDelayedTransition(root)
            val v = if (on) View.GONE else View.VISIBLE
            gifView.visibility = v
            title.visibility = v
            hint.visibility = v
            bottomBar.visibility = v
            countdown.textSize = if (on) 40f else 56f
            // şifre alanı + buton her durumda görünür kalsın
            if (on) scroll.post { scroll.smoothScrollTo(0, content.height) }
        }

        // pencere: tüm ekran (çentik + sistem çubukları altı dahil), klavye için yeniden boyutlanabilir
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // NOT_FOCUSABLE bilerek yok: şifre alanına yazı yazılabilsin (klavye açılsın)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                val (w, h) = realScreenSize(wm)
                width = w
                height = h
            } catch (e: Exception) {
            }
        }
        // sistem çubuklarını gizle (elle kaydırınca geçici görünür) – desteklemeyen cihazda zararsız
        @Suppress("DEPRECATION")
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val kb = KeyboardWatcher(root) { bars, kbTotal, kbPad ->
            setCompact(kbTotal > 0)
            // içerik sistem çubuklarının ve klavyenin altında kalmasın
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, kbPad))
        }

        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            scrPwdTick?.let { handler.removeCallbacks(it) }
            scrPwdTick = null
            return
        }
        scrLockView = root
        scrKb = kb
        kb.start()

        // ilk 7 sn lock_gif, sonra her 7 sn'de bir rastgele bekleme gif'i (kaldığı yerden devam eder)
        fun scheduleGif() {
            val wait = maxOf(50L, scrGifSince + gifPeriod(scrGifRes) - SystemClock.elapsedRealtime())
            val r = Runnable {
                scrGifRes = pickWaitGif()
                scrGifSince = SystemClock.elapsedRealtime()
                gifView.setGifResource(scrGifRes)
                scheduleGif()
            }
            scrGifRotate = r
            handler.postDelayed(r, wait)
        }
        scheduleGif()

        val tick = object : Runnable {
            override fun run() {
                val left = scrLockUntil() - System.currentTimeMillis()
                countdown.text = clock(left)
                if (left <= 0) {
                    hideScreenLock()
                    enforceScreenLock()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        scrLockTick = tick
        handler.postDelayed(tick, 1000)
    }

    private fun hideScreenLock() {
        scrLockTick?.let { handler.removeCallbacks(it) }
        scrLockTick = null
        scrGifRotate?.let { handler.removeCallbacks(it) }
        scrGifRotate = null
        scrPwdTick?.let { handler.removeCallbacks(it) }
        scrPwdTick = null
        scrKb?.stop()
        scrKb = null
        scrLockView?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        scrLockView = null
    }

    // ---- kalan süre kartı ----

    private fun showOverlay(pkg: String, title: String, subtitle: String, lockUntil: Long) {
        hideOverlay()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        fun label(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(top) }
            }

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        val countdown = label(clock(lockUntil - System.currentTimeMillis()), 56f, 0xFF0F9C93.toInt(), true, 12)
        countdown.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(28))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFEDF1F6.toInt())
            }
            elevation = dp(10).toFloat()
            addView(GifView(this@LimiterService).apply { setGifResource(R.raw.lock_gif) },
                LinearLayout.LayoutParams(dp(180), dp(180)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(label(appName, 15f, 0xFF64748B.toInt(), false, 8))
            addView(label(title, 22f, 0xFF0F172A.toInt(), true, 4))
            addView(countdown)
            addView(label(subtitle, 14f, 0xFF64748B.toInt(), false, 4))
            addView(label("Kapatmak için dokun", 12f, 0xFF94A3B8.toInt(), false, 24))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(card, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { hideOverlay() }
        }

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, lp)
        } catch (e: Exception) {
            return
        }
        overlayView = root

        val shownAt = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val left = lockUntil - System.currentTimeMillis()
                countdown.text = clock(left)
                if (left <= 0 || SystemClock.elapsedRealtime() - shownAt >= Config.OVERLAY_MS) {
                    hideOverlay()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        overlayTick = tick
        handler.postDelayed(tick, 1000)
    }

    private fun hideOverlay() {
        overlayTick?.let { handler.removeCallbacks(it) }
        overlayTick = null
        overlayView?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        overlayView = null
    }

    // ---- silme / kapatma koruması ----

    private var guardPending = false
    private var lastGuardTrigger = 0L

    private fun isSensitive(pkg: String): Boolean =
        pkg != packageName && SENSITIVE_HINTS.any { pkg.contains(it) }

    private fun isLauncher(pkg: String): Boolean =
        pkg.contains("launcher") || pkg.contains("miui.home")

    private fun scheduleGuardScan() {
        if (guardPending) return
        guardPending = true
        handler.postDelayed({
            guardPending = false
            guardScan()
        }, 600)
    }

    private fun guardScan() {
        if (System.currentTimeMillis() < prefs.getLong("grace_until", 0L)) return
        if (SystemClock.elapsedRealtime() - lastGuardTrigger < 3000) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isSensitive(pkg)) return

        val texts = ArrayList<String>()
        collectTexts(root, texts, 0)
        if (!isProtectedScreen(texts)) return

        lastGuardTrigger = SystemClock.elapsedRealtime()
        performGlobalAction(GLOBAL_ACTION_HOME)
        toast("Korumalı işlem. Şifre gerekli.")
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("guard", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 25 || out.size > 400) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(norm(it.trim())) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(norm(it.trim())) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out, depth + 1)
    }

    private fun isProtectedScreen(texts: List<String>): Boolean {
        // 1) Dilden bağımsız işaretler: servis sayfası ve cihaz yöneticisi sayfası açıklamaları
        val markers = listOf(
            norm(getString(R.string.accessibility_desc)).take(30),
            norm(getString(R.string.admin_desc)).take(30),
            norm(getString(R.string.admin_warn)).take(30)
        )
        if (markers.any { m -> texts.any { it.contains(m) } }) return true

        // 2) Uygulama adı + tehlikeli işlem (kaldır / zorla durdur / devre dışı bırak ...)
        if (texts.none { it.contains(LABEL) }) return false
        return texts.any { t -> t in DANGER_EXACT || DANGER_CONTAINS.any { t.contains(it) } }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val LABEL = "screen limiter"

        // ekran süresi kilit kartı: ilk gif (lock_gif) bu kadar kalır, sonra gif'ler bu aralıkla değişir
        private const val GIF_FIRST_MS = 7_000L
        private const val GIF_ROTATE_MS = 7_000L

        // Bu paket adı parçalarını içeren ekranlar taranır (marka farklarını kapsamak için geniş tutuldu)
        private val SENSITIVE_HINTS = listOf(
            "settings", "packageinstaller", "permissioncontroller", "securitycenter",
            "safecenter", "systemmanager", "appmanager", "iqoo.secure", "appdetail",
            "launcher", "miui.home"
        )
        private val DANGER_EXACT = setOf("sil", "delete", "remove", "kapat", "disable", "turn off")
            .map { norm(it) }.toSet()
        private val DANGER_CONTAINS = listOf(
            "kaldır", "uninstall", "zorla durdur", "durmaya zorla", "force stop",
            "devre dışı", "deactivate", "etkisizleştir"
        ).map { norm(it) }
    }

    // ---- yardımcılar ----

    private fun selected(): Set<String> =
        prefs.getStringSet("selected", emptySet()) ?: emptySet()

    private fun ignoredPackages(): Set<String> {
        val set = mutableSetOf("com.android.systemui", "android")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.enabledInputMethodList.forEach { set.add(it.packageName) }
        return set
    }

    private fun clock(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }
}

// Türkçe/İngilizce karşılaştırma için: küçük harf, i/ı farkını yok say
private fun norm(s: String): String =
    s.lowercase(Locale.ROOT).replace("\u0307", "").replace('ı', 'i')
