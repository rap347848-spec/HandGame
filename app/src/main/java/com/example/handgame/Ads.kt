package com.example.handgame

/** إعلانات AdMob (بانر). التطبيق: «هاند رمي» */
object Ads {
    /**
     * true = الإعلان الحقيقي (معرّفك في AdMob). false = إعلان Google التجريبي.
     * لا تضغط أنت أو أصدقاؤك على الإعلانات الحقيقية، فهذا قد يوقف حساب AdMob.
     */
    const val USE_REAL_ADS = true

    private const val REAL_BANNER = "ca-app-pub-3407676171751539/6614556874"
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"

    val bannerId: String get() = if (USE_REAL_ADS) REAL_BANNER else TEST_BANNER
}
