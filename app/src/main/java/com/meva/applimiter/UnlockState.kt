package com.meva.applimiter

/**
 * "Sınırsız" işaretli uygulamalar için geçici (RAM içi) açık/kilitli durumu tutar.
 * MainActivity ve LimiterService aynı process içinde çalıştığı için burada
 * paylaşılan bir obje yeterli — diske yazmaya gerek yok, uygulama süreci
 * kapanınca (veya ilgili paket ön plandan çıkınca) otomatik olarak sıfırlanır.
 */
object UnlockState {
    private val unlockedPkgs = mutableSetOf<String>()

    fun isUnlocked(pkg: String): Boolean = pkg in unlockedPkgs

    fun unlock(pkg: String) {
        unlockedPkgs.add(pkg)
    }

    fun lock(pkg: String) {
        unlockedPkgs.remove(pkg)
    }
}

/**
 * Ekran süresi kilidinde şifre ekranı (MainActivity) açıkken true olur. Klavye / otomatik
 * doldurma gibi başka paketlerin pencere olayları geri sayım kartını şifre ekranının
 * üstüne geri getirmesin diye LimiterService bu bayrağa bakar.
 */
object GateState {
    @Volatile var scrGateOpen = false
}
