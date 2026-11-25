package com.meva.applimiter

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private val selected = mutableSetOf<String>()
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var mainView: View
    private lateinit var svcVal: TextView
    private lateinit var adminVal: TextView
    private lateinit var screenVal: TextView
    private lateinit var exemptVal: TextView
    private lateinit var sumIcon: ImageView
    private lateinit var sumCircle: FrameLayout
    private lateinit var sumTitle: TextView
    private lateinit var sumSub: TextView
    private var guardMode = false     // koruma ekranından (silme/ayar) açıldı mı
    private var unlockPkg: String? = null   // "sınırsız" bir uygulamayı açmak için şifre isteniyor mu
    private var extraPkg: String? = null    // kilitli bir uygulamaya 15 dk'lık ek süre için şifre isteniyor mu
    private var scrExtra = false            // ekran süresi kilidinde 15 dk ek süre için şifre isteniyor mu

    private var unlocked = false      // şifre girildi mi
    private var skipRelock = false    // ayarlara gidip dönerken tekrar şifre sorma

    private val handler = Handler(Looper.getMainLooper())
    private var lockTickRunnable: Runnable? = null
    private var remainingTickRunnable: Runnable? = null
    private var gifRotateRunnable: Runnable? = null
    private var homeRefreshRunnable: Runnable? = null
    private var lastWaitGif = -1

    // "Kalan süre" ekranında dönen GIF'ler (2.gif + yenileri)
    private val waitGifs = intArrayOf(
        R.raw.wait_gif, R.raw.wait_gif_3, R.raw.wait_gif_4,
        R.raw.wait_gif_5, R.raw.wait_gif_6, R.raw.wait_gif_7
    )
    private val gifRotateMs = 6_000L   // GIF kaç saniyede bir rastgele değişsin

    // Bir öncekinden farklı rastgele bir GIF seç
    private fun pickWaitGif(): Int {
        var i: Int
        do { i = waitGifs.indices.random() } while (waitGifs.size > 1 && i == lastWaitGif)
        lastWaitGif = i
        return waitGifs[i]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guardMode = intent.getBooleanExtra("guard", false)
        unlockPkg = intent.getStringExtra("unlockPkg")
        extraPkg = intent.getStringExtra("extraPkg")
        scrExtra = intent.getBooleanExtra("scrExtra", false)
        prefs = getSharedPreferences("limiter", Context.MODE_PRIVATE)
        selected.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())

        header = TextView(this)   // eski yapıyla uyumluluk için (ekranda gösterilmez)
        updateHeader()
        status = TextView(this)

        val ink = 0xFF0F172A.toInt()
        val muted = 0xFF64748B.toInt()

        fun valueTv() = TextView(this).apply {
            textSize = 13.5f
            setTextColor(muted)
            gravity = Gravity.END
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        svcVal = valueTv(); adminVal = valueTv(); screenVal = valueTv(); exemptVal = valueTv()

        // ---- özet kartı: koruma durumu tek bakışta ----
        sumIcon = ImageView(this)
        sumCircle = FrameLayout(this).apply {
            addView(sumIcon, FrameLayout.LayoutParams(dpi(26), dpi(26), Gravity.CENTER))
        }
        sumTitle = TextView(this).apply {
            textSize = 18f
            setTextColor(ink)
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        sumSub = TextView(this).apply { textSize = 13.5f; setTextColor(muted) }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(18), dpi(18), dpi(18), dpi(18))
            background = rounded(0xFFFFFFFF.toInt(), 22, 0xFFEDF1F6.toInt())
            elevation = dpi(2).toFloat()
            addView(sumCircle, LinearLayout.LayoutParams(dpi(52), dpi(52)).apply { rightMargin = dpi(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(sumTitle)
                addView(sumSub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val svcRow = settingRow("Erişilebilirlik servisi", svcVal, true) {
            grantGrace()
            skipRelock = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val adminRow = settingRow("Silme koruması", adminVal, false) {
            if (!isAdminActive()) {
                grantGrace()
                skipRelock = true
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@MainActivity, AdminReceiver::class.java)
                    )
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Açıkken Screen Limiter doğrudan silinemez."
                    )
                startActivity(i)
            }
        }
        val screenRow = settingRow("Ekran süresi limiti", screenVal, true) { showScreenLimitDialog() }
        val exemptRow = settingRow("Limit dışı uygulamalar", exemptVal, false) { showExemptDialog() }
        val statsRow = settingRow("Soru istatistikleri", TextView(this), false) { showQuizStats() }

        val lockBtn = actionButton("Kilitle ve çık", R.drawable.ic_lock, true) {
            lockNow()
            unlocked = false
            finish()
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(28), dpi(16), dpi(28))
            addView(TextView(this@MainActivity).apply {
                text = "Ayarlar"
                textSize = 28f
                setTextColor(ink)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setPadding(dpi(4), 0, 0, dpi(14))
            })
            addView(summary)
            addView(sectionLabel("Koruma"))
            addView(settingCard(svcRow, adminRow))
            addView(sectionLabel("Ekran süresi"))
            addView(settingCard(screenRow, exemptRow))
            addView(sectionLabel("İlerleme"))
            addView(settingCard(statsRow))
            addView(lockBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(28) })
        }

        // tablette içerik çok yayılmasın: en fazla 560dp genişlikte ortalanır
        val maxW = minOf(resources.displayMetrics.widthPixels, dpi(560))
        mainView = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt())
            )
            addView(FrameLayout(this@MainActivity).apply {
                addView(column, FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            })
        }
    }

    // ---- ayar ekranı yapı taşları ----

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase(Locale.getDefault())
        textSize = 11.5f
        letterSpacing = 0.08f
        setTextColor(0xFF94A3B8.toInt())
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setPadding(dpi(6), dpi(24), 0, dpi(8))
    }

    private fun settingCard(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), 20, 0xFFEDF1F6.toInt())
        elevation = dpi(2).toFloat()
        rows.forEach { addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun settingRow(title: String, value: TextView, divider: Boolean, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(18), dpi(17), dpi(14), dpi(17))
                background = ripple(ColorDrawable(Color.TRANSPARENT), 0x14000000)
                isClickable = true
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15.5f
                    setTextColor(0xFF0F172A.toInt())
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(0xFFA0AEC0.toInt())
                    setPadding(dpi(10), 0, 0, 0)
                })
                setOnClickListener { onClick() }
            })
            if (divider) addView(View(this@MainActivity).apply {
                setBackgroundColor(0xFFEDF1F6.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1))
                .apply { leftMargin = dpi(18) })
        }

    override fun onResume() {
        super.onResume()
        val pkg = unlockPkg
        val ePkg = extraPkg
        if (pkg != null) {
            showAppUnlockGate(pkg)
        } else if (ePkg != null) {
            showExtraUnlockGate(ePkg)
        } else if (scrExtra) {
            showScreenExtraGate()
        } else if (unlocked) {
            showMain()
        } else if (guardMode) {
            showGate()
        } else {
            showHome()
        }
    }

    private fun isPromptMode() = unlockPkg != null || extraPkg != null || scrExtra

    // Şifre ekranındayken geri tuşu: ana ekrana git (kilitli uygulamaya dönme)
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isPromptMode()) {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finishAndRemoveTask()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        homeDialog?.dismiss()
        homeDialog = null
        GateState.scrGateOpen = false
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        GateState.scrGateOpen = false
        // Şifre girilmeden ekrandan çıkıldıysa (Ana ekran / son uygulamalar) bu şifre
        // ekranını tamamen kapat; yoksa arkada kalıp kilitli uygulamayı geri çağırıyor.
        if (isPromptMode() && !isFinishing) finishAndRemoveTask()
        lockTickRunnable?.let { handler.removeCallbacks(it) }
        lockTickRunnable = null
        remainingTickRunnable?.let { handler.removeCallbacks(it) }
        remainingTickRunnable = null
        gifRotateRunnable?.let { handler.removeCallbacks(it) }
        gifRotateRunnable = null
        homeRefreshRunnable?.let { handler.removeCallbacks(it) }
        homeRefreshRunnable = null
        if (skipRelock) {
            skipRelock = false
        } else {
            // Ayarlardan (App) çıkılınca hemen kilitle. Koruma kaynaklı şifre ekranında
            // (guardMode: silme vb.) işlem için süre tanınır, o yüzden kilitlenmez.
            if (unlocked && !guardMode && !isPromptMode()) endGrace()
            unlocked = false
        }
    }

    private fun showMain() {
        setContentView(mainView)
        val green = 0xFF0F9C93.toInt()
        val red = 0xFFDC2626.toInt()
        val svc = isServiceEnabled()
        val adm = isAdminActive()
        svcVal.text = if (svc) "Açık" else "Kapalı"
        svcVal.setTextColor(if (svc) green else red)
        adminVal.text = if (adm) "Açık" else "Kapalı"
        adminVal.setTextColor(if (adm) green else red)
        val ok = svc && adm
        val tone = if (ok) green else 0xFFE8890C.toInt()
        sumCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (ok) 0xFFE6F6F4.toInt() else 0xFFFFF1DC.toInt())
        }
        sumIcon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_alert)
        sumIcon.setColorFilter(tone)
        sumTitle.text = if (ok) "Koruma aktif" else "Koruma eksik"
        sumSub.text = if (ok) "Her şey yolunda" else "Kapalı olanlara dokunup aç"
        updateScreenBtn()
        updateExemptBtn()
    }

    // ---- premium şifre ekranı ----

    // showGate(), showAppUnlockGate() ve showExtraUnlockGate() tarafından paylaşılan
    // kart tasarımlı modern şifre ekranı. Şifre doğruysa onCorrect() çalışır; yanlış
    // girişleri ve 3 başarısız denemede 5 dakikalık kilitlenmeyi burada merkezi olarak yönetir.
    private fun buildPasswordScreen(
        headline: String,
        subtitle: String,
        note: String?,
        iconDrawable: Drawable?,
        extraView: View? = null,
        requirePassword: Boolean = true,
        buttonText: String = "Aç",
        bottomView: View? = null,
        onCorrect: () -> Unit
    ): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Marka rengi: teal (kart/rozet vurgusu ve buton gradyanı bu tondan)
        val accentDark = 0xFF0F9C93.toInt()
        val accentLight = 0xFF34C9BE.toInt()

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

        val button = Button(this).apply {
            text = buttonText
            textSize = 16f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accentLight, accentDark)
            ).apply { cornerRadius = dp(16).toFloat() }
            setPadding(0, dp(16), 0, dp(16))
            elevation = dp(4).toFloat()
        }

        val iconBadge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEAF7F6.toInt())
                setStroke(dp(1), 0xFFCFEFEC.toInt())
            }
            elevation = dp(2).toFloat()
            addView(
                ImageView(this@MainActivity).apply {
                    if (iconDrawable != null) setImageDrawable(iconDrawable)
                    else setImageResource(R.mipmap.ic_launcher)
                },
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER)
            )
        }

        // Çok fazla yanlış denemede gösterilen kilit/kalan-süre uyarısı
        val lockLabel = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFFDC5B5B.toInt())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1), 0xFFEDF1F6.toInt())
            }
            elevation = dp(10).toFloat()
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(lockLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(iconBadge, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(this@MainActivity).apply {
                text = headline
                textSize = 22f
                setTextColor(0xFF0F172A.toInt())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 14f
                setTextColor(0xFF64748B.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(24), 0, dp(24), dp(28))
            })
            if (extraView != null) {
                addView(extraView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(20) })
            }
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (note != null) {
                addView(TextView(this@MainActivity).apply {
                    text = note
                    textSize = 12f
                    setTextColor(0xFF94A3B8.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(20), dp(12), 0)
                })
            }
            if (bottomView != null) {
                addView(bottomView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(28) })
            }
        }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt())
            )
            // büyük GIF'li ekranda içerik sığmayabilir: kaydırılabilir yap, üst boşluğu azalt
            content.setPadding(dp(28), dp(96), dp(28), dp(32))
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Şifresiz mod (ör. "Kilitle"): alan gizli, butona tek tıkla çalışır
        if (!requirePassword) {
            input.visibility = View.GONE
            button.setOnClickListener { onCorrect() }
            root.addView(
                StatsUi.button(this) { showQuizStats() },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply { setMargins(dp(28), dp(16), dp(28), 0) }
            )
            return root
        }

        // ---- yanlış deneme sınırı: 3 yanlıştan sonra 5 dakika şifre alanını kilitle ----
        lockTickRunnable?.let { handler.removeCallbacks(it) }

        fun fmtLock(ms: Long): String {
            val s = maxOf(0L, (ms + 999) / 1000)
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun setLocked(isLocked: Boolean) {
            input.isEnabled = !isLocked
            button.isEnabled = !isLocked
            button.alpha = if (isLocked) 0.45f else 1f
            lockLabel.visibility = if (isLocked) View.VISIBLE else View.GONE
        }

        fun tickLock() {
            val left = prefs.getLong("pwd_lock_until", 0L) - System.currentTimeMillis()
            if (left <= 0) {
                setLocked(false)
                lockTickRunnable = null
                return
            }
            lockLabel.text = "Çok fazla yanlış deneme\nKalan: ${fmtLock(left)}"
            val tick = Runnable { tickLock() }
            lockTickRunnable = tick
            handler.postDelayed(tick, 1000)
        }

        val lockedUntilNow = prefs.getLong("pwd_lock_until", 0L)
        if (System.currentTimeMillis() < lockedUntilNow) {
            setLocked(true)
            tickLock()
        } else {
            setLocked(false)
        }

        val check = {
            if (System.currentTimeMillis() < prefs.getLong("pwd_lock_until", 0L)) {
                // kilitliyken normalde buton/alan devre dışı, ekstra güvence
            } else if (input.text.toString() == Config.PASSWORD) {
                prefs.edit().putInt("pwd_fail_count", 0).apply()
                onCorrect()
            } else {
                shakeWrong(input)
                input.setText("")
                val fails = prefs.getInt("pwd_fail_count", 0) + 1
                if (fails >= Config.PASSWORD_MAX_ATTEMPTS) {
                    val until = System.currentTimeMillis() + Config.PASSWORD_LOCKOUT_MS
                    prefs.edit().putInt("pwd_fail_count", 0).putLong("pwd_lock_until", until).apply()
                    showBanner("Çok fazla yanlış deneme · 5 dk kilitlendi", B_ERR)
                    setLocked(true)
                    tickLock()
                } else {
                    prefs.edit().putInt("pwd_fail_count", fails).apply()
                    val left = Config.PASSWORD_MAX_ATTEMPTS - fails
                    showBanner("Yanlış şifre · Kalan deneme: $left", B_ERR)
                }
            }
        }
        input.setOnEditorActionListener { _, _, _ -> check(); true }
        button.setOnClickListener { check() }

        // şifresiz: tüm şifre ekranlarının en üstünde soru istatistikleri
        root.addView(
            StatsUi.button(this) { showQuizStats() },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(28), dp(16), dp(28), 0) }
        )
        return root
    }

    // ---- açılış: küçük pop-up. Kilitli değilse [Lock][App], kilitliyse [Unlock][App] ----

    // Unlock/Lock artık ekran süresi limiti içindir: limit açık = Kilitli, limit kapalı = Kilit açık.
    // Varsayılan açık (daha önce belirlenen süreler sürekli geçerli).
    private fun isLimitOn() = prefs.getBoolean("scr_enabled", true)
    private fun isProtectionOpen() = !isLimitOn()   // "kilit açık" = limit kapalı

    private fun setLimitOn(on: Boolean) {
        prefs.edit().putBoolean("scr_enabled", on).apply()
    }

    // Silme/ayar koruması için verilen kısa süreyi bitirir (limite dokunmaz)
    private fun endGrace() {
        prefs.edit().putLong("grace_until", 0L).apply()
    }

    // Kilitle: koruma süresini bitir + ekran süresi limitini tekrar aç
    private fun lockNow() {
        endGrace()
        setLimitOn(true)
    }

    private var homeDialog: Dialog? = null
    private var currentBanner: View? = null

    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpi(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dpi(1), strokeColor)
        }

    private fun ripple(bg: Drawable, color: Int = 0x22000000): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), bg, null)

    // ---- üstten kayarak inen uyarı bandı (Toast yerine) ----
    private fun showBanner(msg: String, kind: Int = B_INFO) {
        val decor = window.decorView as? FrameLayout ?: return
        currentBanner?.let { decor.removeView(it) }
        val accent = when (kind) {
            B_OK -> 0xFF2DD4A7.toInt()
            B_ERR -> 0xFFF87171.toInt()
            else -> 0xFF34C9BE.toInt()
        }
        val iconRes = when (kind) { B_OK -> R.drawable.ic_check; B_ERR -> R.drawable.ic_alert; else -> R.drawable.ic_lock }

        val chip = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((accent and 0x00FFFFFF) or 0x33000000)
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(accent)
            }, FrameLayout.LayoutParams(dpi(16), dpi(16), Gravity.CENTER))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(11), dpi(20), dpi(11))
            background = rounded(0xFF111827.toInt(), 20)
            elevation = dpi(12).toFloat()
            addView(chip, LinearLayout.LayoutParams(dpi(30), dpi(30)))
            addView(TextView(this@MainActivity).apply {
                text = msg
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dpi(12) })
        }
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dpi(24)
        decor.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = sbH + dpi(10) })
        row.alpha = 0f
        row.translationY = -dpi(24).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(240).start()
        currentBanner = row
        handler.postDelayed({
            if (currentBanner === row) {
                row.animate().alpha(0f).translationY(-dpi(24).toFloat()).setDuration(200)
                    .withEndAction {
                        decor.removeView(row)
                        if (currentBanner === row) currentBanner = null
                    }.start()
            }
        }, 2600)
    }

    // Bandın görünmesi için kısa bir süre bekleyip kapat
    private fun delayedFinish(ms: Long = 1000L) {
        handler.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, ms)
    }

    private fun actionButton(label: String, iconRes: Int, primary: Boolean, onClick: () -> Unit): View {
        val teal = 0xFF0F9C93.toInt()
        val tealLight = 0xFF34C9BE.toInt()
        val fg = if (primary) 0xFFFFFFFF.toInt() else 0xFF334155.toInt()
        val bg: Drawable = if (primary)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(tealLight, teal))
                .apply { cornerRadius = dpi(16).toFloat() }
        else rounded(0xFFFFFFFF.toInt(), 16, 0xFFDCE3EC.toInt())
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpi(10), dpi(15), dpi(10), dpi(15))
            background = ripple(bg, if (primary) 0x33FFFFFF else 0x14000000)
            if (primary) elevation = dpi(3).toFloat()
            isClickable = true
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(fg)
            }, LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { rightMargin = dpi(8) })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(fg)
                typeface = Typeface.create(typeface, Typeface.BOLD)
            })
            setOnClickListener { onClick() }
        }
    }

    // ---- açılış pop-up'ı: Kilitli değilse [Lock][App], kilitliyse [Unlock][App] ----
    private fun showHome() {
        homeDialog?.dismiss()
        homeDialog = null

        // pop-up'ın arkasında sade bir arka plan
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt())
            )
        })

        val locked = !isProtectionOpen()
        // kilitli = korumalı (teal), açık = dikkat (amber)
        val status = if (locked) 0xFF0F9C93.toInt() else 0xFFE8890C.toInt()
        val statusTint = if (locked) 0xFFE6F6F4.toInt() else 0xFFFFF1DC.toInt()

        val iconView = ImageView(this).apply {
            setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
            setColorFilter(status)
        }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusTint)
            }
            addView(iconView, FrameLayout.LayoutParams(dpi(34), dpi(34), Gravity.CENTER))
        }
        val titleTv = TextView(this).apply {
            text = "Screen Limiter"
            textSize = 20f
            setTextColor(0xFF0F172A.toInt())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(status) }
        }
        val pillText = TextView(this).apply {
            text = if (locked) "Limit açık" else "Limit kapalı"
            textSize = 12.5f
            setTextColor(status)
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(6), dpi(14), dpi(6))
            background = rounded(statusTint, 20)
            addView(dot, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { rightMargin = dpi(8) })
            addView(pillText)
        }
        val subtitleTv = TextView(this).apply {
            text = if (locked) "Ekran süresi limiti çalışıyor.\nKapatmak (Unlock) için şifre gerekir"
                   else "Ekran süresi limiti kapalı.\nLock ile hemen tekrar açabilirsin"
            textSize = 13.5f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dpi(2).toFloat(), 1f)
        }

        lateinit var dlg: Dialog
        lateinit var rowRef: View

        // Koruma eksikse (servis ya da silme koruması kapalı) pop-up'ta uyar
        val problems = mutableListOf<String>()
        if (!isServiceEnabled()) problems += "Erişilebilirlik servisi kapalı"
        if (!isAdminActive()) problems += "Silme koruması kapalı"
        val warn: View? = if (problems.isEmpty()) null else LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
            background = ripple(rounded(0xFFFEECEC.toInt(), 14, 0xFFF9C9C9.toInt()), 0x14000000)
            isClickable = true
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_alert)
                setColorFilter(0xFFDC2626.toInt())
            }, LinearLayout.LayoutParams(dpi(20), dpi(20)).apply { rightMargin = dpi(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Koruma eksik"
                    textSize = 13.5f
                    setTextColor(0xFF991B1B.toInt())
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = problems.joinToString(" · ") + "\nDüzeltmek için dokun"
                    textSize = 12f
                    setTextColor(0xFFB91C1C.toInt())
                })
            })
            setOnClickListener {
                dlg.dismiss(); homeDialog = null
                showGate()
            }
        }
        val primaryBtn = if (locked)
            actionButton("Unlock", R.drawable.ic_lock_open, true) {
                dlg.dismiss(); homeDialog = null
                showGate(unlockOnly = true)
            }
        else
            actionButton("Lock", R.drawable.ic_lock, true) {
                lockNow()
                unlocked = false
                // başarı durumu: kart güncellenir, kısa süre sonra kapanır
                val ok = 0xFF0F9C93.toInt()
                iconView.setImageResource(R.drawable.ic_check)
                iconView.setColorFilter(ok)
                badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE6F6F4.toInt()) }
                titleTv.text = "Limit açıldı"
                pillText.text = "Limit açık"
                pillText.setTextColor(ok)
                (dot.background as GradientDrawable).setColor(ok)
                pill.background = rounded(0xFFE6F6F4.toInt(), 20)
                subtitleTv.text = "Ekran süresi limiti çalışıyor"
                rowRef.visibility = View.GONE
                handler.postDelayed({
                    if (dlg.isShowing) dlg.dismiss()
                    homeDialog = null
                    finish()
                }, 900)
            }
        val appBtn = actionButton("App", R.drawable.ic_apps, false) {
            dlg.dismiss(); homeDialog = null
            showGate()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dpi(6) })
            addView(appBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dpi(6) })
        }
        rowRef = row

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(22), dpi(28), dpi(22), dpi(22))
            background = rounded(0xFFFFFFFF.toInt(), 28)
            elevation = dpi(14).toFloat()
            addView(badge, LinearLayout.LayoutParams(dpi(76), dpi(76)))
            addView(titleTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(16) })
            addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(10) })
            addView(subtitleTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(14); bottomMargin = dpi(if (warn != null) 16 else 24) })
            if (warn != null) addView(warn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpi(20) })
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // kartın gölgesi pencere sınırında kesilmesin diye kenar boşluğu
        val wrapper = FrameLayout(this).apply {
            setPadding(dpi(14), dpi(14), dpi(14), dpi(18))
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dlg = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(wrapper)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { finish() }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.55f)
                setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        homeDialog = dlg
        dlg.show()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun showGate(unlockOnly: Boolean = false) {
        val root = buildPasswordScreen(
            headline = "Screen Limiter",
            subtitle = if (guardMode) "Korumalı bir işlem için şifre gerekli"
                       else "Devam etmek için şifreni gir",
            note = if (guardMode)
                "Doğru şifreyle ${Config.GRACE_MS / 60_000} dakika içinde işlemini tamamlayabilirsin"
            else null,
            iconDrawable = null
        ) {
            if (!unlockOnly) unlocked = true
            // Silme/ayar koruması hep açık: şifre ancak koruma ekranından (guardMode) 3 dk süre verir.
            // "Unlock" sadece uygulama kilidini açar ve manuel Lock'a kadar kalır.
            if (guardMode) grantGrace()
            if (unlockOnly) setLimitOn(false)
            if (unlockOnly) {
                showBanner("Ekran süresi limiti kapatıldı", B_OK)
                delayedFinish()
            } else if (guardMode) {
                showBanner("${Config.GRACE_MS / 60_000} dk içinde işlemini yapabilirsin", B_OK)
                delayedFinish(1200)
            } else {
                showMain()
            }
        }
        setContentView(root)
    }

    // Sınırsız işaretli bir uygulama açılmak istendiğinde: şifre iste, doğruysa
    // o uygulamayı bu açılış için serbest bırak ve doğrudan başlat.
    private fun showAppUnlockGate(pkg: String) {
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        val appIcon = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }

        val root = buildPasswordScreen(
            headline = appLabel,
            subtitle = "Bu uygulama sınırsız olarak işaretli\nAçmak için şifreni gir",
            note = "Doğru şifreyle bu açılış boyunca serbest kalır",
            iconDrawable = appIcon
        ) {
            UnlockState.unlock(pkg)
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            finish()
        }
        setContentView(root)
    }

    // Kilitli (süresi dolmuş) bir uygulama tekrar açılmaya çalışıldığında: şifre iste,
    // doğruysa bu kilit döngüsü için tek seferlik 15 dakikalık ek süre ver ve uygulamayı aç.
    // 15 dakika bitince LimiterService normal kilit süresiyle uygulamayı yeniden kilitler.
    private fun showExtraUnlockGate(pkg: String) {
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        val appIcon = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }

        val remainingTitle = TextView(this).apply {
            text = "Kalan süre:"
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val remainingLabel = TextView(this).apply {
            text = "--:--"
            textSize = 30f
            setTextColor(0xFF0F9C93.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        val waitGifView = GifView(this).apply { setGifResource(pickWaitGif()) }
        gifRotateRunnable?.let { handler.removeCallbacks(it) }
        val rotate = object : Runnable {
            override fun run() {
                waitGifView.setGifResource(pickWaitGif())
                handler.postDelayed(this, gifRotateMs)
            }
        }
        gifRotateRunnable = rotate
        handler.postDelayed(rotate, gifRotateMs)

        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(waitGifView,
                LinearLayout.LayoutParams(dp(320), dp(320)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(remainingTitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(remainingLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        remainingTickRunnable?.let { handler.removeCallbacks(it) }
        fun tickRemaining() {
            val left = prefs.getLong("lock_$pkg", 0L) - System.currentTimeMillis()
            remainingLabel.text = fmtRemaining(left)
            if (left > 0) {
                val t = Runnable { tickRemaining() }
                remainingTickRunnable = t
                handler.postDelayed(t, 1000)
            } else {
                remainingTickRunnable = null
            }
        }
        tickRemaining()

        val root = buildPasswordScreen(
            headline = appLabel,
            subtitle = "Bu uygulama şu anda kilitli\nAçmak için şifreni gir",
            note = "Doğru şifreyle tek seferlik 15 dakika ek süre kazanırsın",
            iconDrawable = appIcon,
            extraView = extra
        ) {
            prefs.edit()
                .putBoolean("extra_active_$pkg", true)
                .putLong("extra_used_$pkg", 0L)
                .apply()
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            finish()
        }
        setContentView(root)
    }

    // Ekran süresi kilidi (tüm telefon) sırasında: şifre iste, doğruysa bu kilit döngüsü için
    // tek seferlik ek süre ver. Süre bitince LimiterService kalan kilit süresi için kartı geri getirir.
    private fun showScreenExtraGate() {
        GateState.scrGateOpen = true
        val remainingTitle = TextView(this).apply {
            text = "Kalan süre:"
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val remainingLabel = TextView(this).apply {
            text = "--:--"
            textSize = 30f
            setTextColor(0xFF0F9C93.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        val waitGifView = GifView(this).apply { setGifResource(pickWaitGif()) }
        gifRotateRunnable?.let { handler.removeCallbacks(it) }
        val rotate = object : Runnable {
            override fun run() {
                waitGifView.setGifResource(pickWaitGif())
                handler.postDelayed(this, gifRotateMs)
            }
        }
        gifRotateRunnable = rotate
        handler.postDelayed(rotate, gifRotateMs)

        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(waitGifView,
                LinearLayout.LayoutParams(dp(320), dp(320)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(remainingTitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(remainingLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        remainingTickRunnable?.let { handler.removeCallbacks(it) }
        fun tickRemaining() {
            val left = prefs.getLong("scr_lock_until", 0L) - System.currentTimeMillis()
            remainingLabel.text = fmtRemaining(left)
            if (left > 0) {
                val t = Runnable { tickRemaining() }
                remainingTickRunnable = t
                handler.postDelayed(t, 1000)
            } else {
                remainingTickRunnable = null
            }
        }
        tickRemaining()

        val root = buildPasswordScreen(
            headline = "Ekran süresi doldu",
            subtitle = "Telefon şu anda kilitli\nEk süre için şifreni gir",
            note = "Doğru şifreyle tek seferlik ${Config.EXTRA_MS / 60_000} dakika ek süre kazanırsın",
            iconDrawable = null,
            extraView = extra
        ) {
            if (prefs.getBoolean("scr_extra_used", false)) {
                showBanner("Bu kilit döngüsünde ek süre zaten kullanıldı", B_ERR)
            } else {
                prefs.edit()
                    .putBoolean("scr_extra_used", true)
                    .putLong("scr_extra_until", System.currentTimeMillis() + Config.EXTRA_MS)
                    .apply()
                showBanner("${Config.EXTRA_MS / 60_000} dk ek süre verildi", B_OK)
            }
            delayedFinish(1000)
        }
        setContentView(root)
    }

    // Soru istatistikleri: ortak panel (StatsUi) bir pencere içinde
    private fun showQuizStats() {
        lateinit var dlg: AlertDialog
        val pane = StatsUi.pane(this, "Kapat") { dlg.dismiss() }
        dlg = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { setPadding(32, 24, 32, 8); addView(pane) })
            .create()
        dlg.show()
    }

    private fun updateScreenBtn() {
        val on = prefs.getBoolean("scr_enabled", true)
        screenVal.text = if (on) "Açık" else "Kapalı"
        screenVal.setTextColor(if (on) 0xFF0F9C93.toInt() else 0xFF64748B.toInt())
    }

    // ---- ekran süresi limiti kapsamı dışındaki uygulamalar ----

    private fun exemptSet(): MutableSet<String> =
        HashSet(prefs.getStringSet("scr_exempt", emptySet()) ?: emptySet())

    private fun updateExemptBtn() {
        val n = exemptSet().size
        exemptVal.text = if (n == 0) "Yok" else "$n uygulama"
    }

    // Telefondaki başlatılabilir (launcher'da görünen) uygulamalar, kendimiz hariç
    private fun loadLaunchableApps(): List<AppItem> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    // Seçilen uygulamalarda ekran süresi sayılmaz ve kilit kartı bu uygulamaların üstünü kapatmaz.
    private fun showExemptDialog() {
        val items = loadLaunchableApps()
        val chosen = exemptSet()

        val exAdapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val ctx = this@MainActivity
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 16, 32, 16)
                    addView(ImageView(ctx), LinearLayout.LayoutParams(96, 96))
                    addView(TextView(ctx).apply {
                        textSize = 16f
                        setPadding(32, 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(ctx).apply {
                        isClickable = false
                        isFocusable = false
                    })
                }
                val item = items[position]
                (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
                (row.getChildAt(1) as TextView).text = item.label
                (row.getChildAt(2) as CheckBox).isChecked = item.pkg in chosen
                return row
            }
        }

        val list = ListView(this).apply {
            this.adapter = exAdapter
            setOnItemClickListener { _, _, pos, _ ->
                val pkg = items[pos].pkg
                if (!chosen.remove(pkg)) chosen.add(pkg)
                exAdapter.notifyDataSetChanged()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Ekran süresi kapsamı dışındaki uygulamalar")
            .setView(list)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putStringSet("scr_exempt", HashSet(chosen)).apply()
                updateExemptBtn()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // 0 = kapalı; dakika değeri, gösterim etiketi
    private val scrOptions = listOf(
        0 to "Kapalı", 30 to "30 dakika", 60 to "1 saat", 120 to "2 saat", 180 to "3 saat", 1 to "1 dakika (test)"
    )

    // Tüm telefon için ekran süresi limiti: kesintisiz süre, gün içi toplam süre ve kilit süresi.
    private fun showScreenLimitDialog() {
        fun curMin(key: String, def: Long) = (prefs.getLong(key, def) / 60_000L).toInt()
        fun spinner(opts: List<Pair<Int, String>>, cur: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                opts.map { it.second }.toTypedArray()
            )
            setSelection(opts.indexOfFirst { it.first == cur }.coerceAtLeast(0))
        }
        fun lbl(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 0) }

        val enabled = CheckBox(this).apply {
            text = "Ekran süresi limiti açık"
            isChecked = prefs.getBoolean("scr_enabled", true)
        }
        val contSp = spinner(scrOptions, curMin("scr_cont_ms", Config.SCREEN_CONT_MS))
        val dailySp = spinner(scrOptions, curMin("scr_daily_ms", Config.SCREEN_DAILY_MS))
        val lockSp = spinner(lockOptions, curMin("scr_lock_ms", Config.SCREEN_LOCK_MS))
        val note = TextView(this).apply {
            text = "Hangisi önce dolarsa telefon kilit süresince geri sayım kartıyla kaplanır. " +
                "Acil arama, SMS ve şifre (tek seferlik ek süre) serbesttir."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(enabled)
            addView(lbl("Kesintisiz ekran süresi"))
            addView(contSp)
            addView(lbl("Gün içi toplam ekran süresi"))
            addView(dailySp)
            addView(lbl("Kilit süresi"))
            addView(lockSp)
            addView(note)
        }

        AlertDialog.Builder(this)
            .setTitle("Ekran süresi limiti")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putLong("scr_cont_ms", scrOptions[contSp.selectedItemPosition].first * 60_000L)
                    .putLong("scr_daily_ms", scrOptions[dailySp.selectedItemPosition].first * 60_000L)
                    .putLong("scr_lock_ms", lockOptions[lockSp.selectedItemPosition].first * 60_000L)
                    .putBoolean("scr_enabled", enabled.isChecked)
                    .apply()
                updateScreenBtn()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // Yanlış şifrede küçük bir "sallanma" geri bildirimi (premium his için)
    private fun shakeWrong(view: View) {
        ObjectAnimator.ofFloat(
            view, "translationX",
            0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f
        ).apply {
            duration = 380
            start()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // "Kalan süre" metni için biçimlendirme (saat varsa s:dd:ss, yoksa dd:ss)
    private fun fmtRemaining(ms: Long): String {
        val s = maxOf(0L, (ms + 999) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    private fun grantGrace() {
        prefs.edit().putLong("grace_until", System.currentTimeMillis() + Config.GRACE_MS).apply()
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains("$packageName/")
    }

    private fun updateHeader() {
        header.text = "Ekran süresi limiti"
    }

    // dakika değeri, gösterim etiketi
    private val runOptions = listOf(
        30 to "30 dakika", 60 to "1 saat", 120 to "2 saat", 180 to "3 saat", -1 to "Sınırsız", 1 to "1 dakika (test)"
    )
    private val lockOptions = listOf(
        30 to "30 dakika", 60 to "1 saat", 120 to "2 saat", 180 to "3 saat", 1 to "1 dakika (test)"
    )

    private fun fmt(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}sa ${m}dk"
            h > 0 -> "${h}sa"
            else -> "${m}dk"
        }
    }

    // Seçilen uygulama için çalışma/kilit sürelerini ayarlama diyalogu.
    // "Sınırsız" seçilirse uygulama her açılışta şifre ister (bkz. showAppUnlockGate / LimiterService).
    private fun showLimitDialog(pkg: String, label: String, isNew: Boolean, listAdapter: AppAdapter) {
        val isUnlimitedNow = prefs.getBoolean("unlimited_$pkg", false)
        val runDefaultMin = if (isUnlimitedNow) -1
            else (prefs.getLong("runlimit_$pkg", Config.RUN_LIMIT_MS) / 60_000L).toInt()
        val lockDefaultMin = (prefs.getLong("locklen_$pkg", Config.LOCK_MS) / 60_000L).toInt()

        val runLabels = runOptions.map { it.second }.toTypedArray()
        val lockLabels = lockOptions.map { it.second }.toTypedArray()

        val runSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, runLabels)
            setSelection(runOptions.indexOfFirst { it.first == runDefaultMin }.coerceAtLeast(0))
        }
        val lockLabelView = TextView(this).apply {
            text = "Kilit süresi"
            setPadding(0, 24, 0, 0)
        }
        val lockSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, lockLabels)
            setSelection(lockOptions.indexOfFirst { it.first == lockDefaultMin }.coerceAtLeast(0))
        }
        val note = TextView(this).apply {
            text = "Sınırsız: uygulama her açılışta şifre (123@) ister, girilince o açılış boyunca serbest kalır."
            textSize = 12f
            setPadding(0, 16, 0, 0)
        }

        fun updateVisibility(pos: Int) {
            val unlimited = runOptions[pos].first == -1
            lockLabelView.visibility = if (unlimited) View.GONE else View.VISIBLE
            lockSpinner.visibility = if (unlimited) View.GONE else View.VISIBLE
            note.visibility = if (unlimited) View.VISIBLE else View.GONE
        }
        runSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) =
                updateVisibility(pos)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateVisibility(runSpinner.selectedItemPosition)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@MainActivity).apply { text = "Çalışma süresi" })
            addView(runSpinner)
            addView(lockLabelView)
            addView(lockSpinner)
            addView(note)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(label)
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                val runMin = runOptions[runSpinner.selectedItemPosition].first
                if (runMin == -1) {
                    UnlockState.lock(pkg)
                    prefs.edit()
                        .putBoolean("unlimited_$pkg", true)
                        .remove("runlimit_$pkg")
                        .remove("locklen_$pkg")
                        .remove("lock_$pkg")
                        .apply()
                } else {
                    val lockMin = lockOptions[lockSpinner.selectedItemPosition].first
                    prefs.edit()
                        .putBoolean("unlimited_$pkg", false)
                        .putLong("runlimit_$pkg", runMin * 60_000L)
                        .putLong("locklen_$pkg", lockMin * 60_000L)
                        .putBoolean("extra_active_$pkg", false)
                        .remove("extra_used_$pkg")
                        .apply()
                }
                listAdapter.notifyDataSetChanged()
            }

        if (!isNew) {
            builder.setNeutralButton("Kaldır") { _, _ ->
                selected.remove(pkg)
                UnlockState.lock(pkg)
                prefs.edit()
                    .putStringSet("selected", HashSet(selected))
                    .remove("runlimit_$pkg")
                    .remove("locklen_$pkg")
                    .remove("unlimited_$pkg")
                    .remove("used_$pkg")
                    .remove("lock_$pkg")
                    .remove("extra_active_$pkg")
                    .remove("extra_used_$pkg")
                    .apply()
                listAdapter.notifyDataSetChanged()
                updateHeader()
            }
        } else {
            builder.setNegativeButton("İptal") { _, _ ->
                selected.remove(pkg)
                prefs.edit().putStringSet("selected", HashSet(selected)).apply()
                listAdapter.notifyDataSetChanged()
                updateHeader()
            }
        }
        builder.setCancelable(false)
        builder.show()
    }

    private inner class AppAdapter(val items: List<AppItem>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = this@MainActivity
            val row: LinearLayout
            if (convertView == null) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 16, 32, 16)
                    addView(ImageView(ctx), LinearLayout.LayoutParams(96, 96))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ctx).apply {
                            textSize = 16f
                            setPadding(32, 0, 0, 0)
                        })
                        addView(TextView(ctx).apply {
                            textSize = 12f
                            setTextColor(0xFF9CA3AF.toInt())
                            setPadding(32, 0, 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(ctx).apply {
                        isClickable = false
                        isFocusable = false
                    })
                }
            } else {
                row = convertView as LinearLayout
            }
            val item = items[position]
            val textCol = row.getChildAt(1) as LinearLayout
            val labelView = textCol.getChildAt(0) as TextView
            val subtitleView = textCol.getChildAt(1) as TextView
            (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            labelView.text = item.label
            val isSel = item.pkg in selected
            subtitleView.text = if (!isSel) "" else if (prefs.getBoolean("unlimited_${item.pkg}", false)) {
                "Sınırsız (şifreli)"
            } else {
                val runMin = prefs.getLong("runlimit_${item.pkg}", Config.RUN_LIMIT_MS) / 60_000L
                val lockMin = prefs.getLong("locklen_${item.pkg}", Config.LOCK_MS) / 60_000L
                "${fmt(runMin)} çalışma / ${fmt(lockMin)} kilit"
            }
            (row.getChildAt(2) as CheckBox).isChecked = isSel
            return row
        }
    }
}

// uyarı bandı türleri
private const val B_INFO = 0
private const val B_OK = 1
private const val B_ERR = 2
