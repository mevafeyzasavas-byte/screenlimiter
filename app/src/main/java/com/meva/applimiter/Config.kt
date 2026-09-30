package com.meva.applimiter

object Config {
    const val RUN_LIMIT_MS = 30 * 60_000L      // uygulamanın ekranda kalabileceği toplam süre: 30 dk
    const val LOCK_MS = 60 * 60_000L           // süre dolunca kilit süresi: 1 saat
    const val PASSWORD = "123@"                // App Limiter'ı açma şifresi
    const val OVERLAY_MS = 3_000L              // kilit anında gösterilen kartın (1.gif ile) ekranda kalma süresi
    const val GRACE_MS = 3 * 60_000L           // doğru şifreden sonra silme/ayar korumasının devre dışı kalma süresi: 3 dk
    const val EXTRA_MS = 15 * 60_000L          // kilitliyken şifreyle açılınca verilen tek seferlik ek süre: 15 dk
    const val PASSWORD_MAX_ATTEMPTS = 3        // şifre ekranında art arda izin verilen yanlış deneme sayısı
    const val PASSWORD_LOCKOUT_MS = 5 * 60_000L // yanlış deneme sınırı aşılınca şifre alanının kilitlenme süresi: 5 dk

    // ---- ekran süresi limiti (varsayılanlar; uygulama içinden değiştirilebilir) ----
    const val SCREEN_CONT_MS = 60 * 60_000L    // ekran kesintisiz açık kalabilir: 1 saat
    const val SCREEN_DAILY_MS = 60 * 60_000L   // gün içi toplam ekran süresi: 1 saat
    const val SCREEN_LOCK_MS = 60 * 60_000L    // limit dolunca telefon kilit süresi: 1 saat
    const val SCREEN_GAP_MS = 5 * 60_000L      // ekran bu kadar kapalı kalırsa "kesintisiz" sayaç sıfırlanır
}
