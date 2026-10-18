package com.example.handgame

/** سجل الفوز: كل نقطة = مستوى (1..100)، والمستوى 100 بعد 2000 فوز بلعبة كاملة */
object Progress {
    /** الانتصارات التراكمية اللازمة لكل مستوى: 1، 3، 7 ثم يزداد الثمن تدريجياً حتى 2000 عند المستوى 100 */
    val cum: IntArray = run {
        val a = IntArray(101)
        var sum = 0.0
        val slope = (1993.0 - 97 * 8) / (97.0 * 96 / 2)
        for (l in 1..100) {
            sum += when (l) { 1 -> 1.0; 2 -> 2.0; 3 -> 4.0; else -> 8 + (l - 4) * slope }
            a[l] = Math.round(sum).toInt()
        }
        a[100] = 2000
        a
    }

    /** winsHalf = عدد الانتصارات × 2 (الفوز على البوتات فقط = نصف فوز) */
    fun level(winsHalf: Int): Int {
        val w = winsHalf / 2.0
        var l = 0
        while (l < 100 && cum[l + 1] <= w) l++
        return l
    }

    fun winsText(winsHalf: Int) = if (winsHalf % 2 == 0) "${winsHalf / 2}" else "${winsHalf / 2}.5"

    /** الانتصارات المتبقية للمستوى التالي، أو null عند المستوى الأعلى */
    fun toNext(winsHalf: Int): Double? {
        val l = level(winsHalf)
        return if (l >= 100) null else cum[l + 1] - winsHalf / 2.0
    }

    fun fmt(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.US, "%.1f", d)

    fun title(level: Int): String = when {
        level <= 0 -> "لاعب جديد"
        level < 10 -> "مبتدئ"
        level < 20 -> "لاعب"
        level < 30 -> "ماهر"
        level < 40 -> "خبير"
        level < 50 -> "محترف"
        level < 60 -> "بطل"
        level < 70 -> "أسطورة"
        level < 80 -> "ملك الورق"
        level < 90 -> "إمبراطور"
        level < 100 -> "سلطان"
        else -> "سلطان الهاند"
    }
}
