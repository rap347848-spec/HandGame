package com.example.handgame

/** إعلانات AdMob (بانر). التطبيق: «هاند رمي» */
object Ads {
    /**
     * أثناء التجربة اتركها false فتظهر إعلانات Google التجريبية. الضغط على إعلاناتك الحقيقية
     * وأنت تجرّب قد يوقف حساب AdMob. اجعلها true فقط في النسخة التي تنشرها على Google Play.
     */
    const val USE_REAL_ADS = false

    private const val REAL_BANNER = "ca-app-pub-3407676171751539/6614556874"
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"

    val bannerId: String get() = if (USE_REAL_ADS) REAL_BANNER else TEST_BANNER
}
