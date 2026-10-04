package com.meva.applimiter

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? =
        context.getString(R.string.admin_warn)

    // Huawei'de "Sil > İZİN VER" cihaz yöneticiliğini şifresiz kapatıyor. Burada yakalayıp
    // açık sil/kaldır penceresini kapatır ve şifre ekranını açarız (şifreden sonraki 3 dk serbest).
    override fun onDisabled(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("limiter", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong("grace_until", 0L)) return
        LimiterService.requestHome()
        Handler(Looper.getMainLooper()).postDelayed({
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("guard", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }, 300)
    }
}
