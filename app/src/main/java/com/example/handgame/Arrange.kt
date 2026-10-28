package com.example.handgame

import kotlin.math.abs

/** ترتيب اليد تلقائياً: المجموعات الكاملة أولاً، ثم الجوكر، ثم الأزواج القريبة من التكوين، ثم الباقي حسب النوع */
object Arranger {
    /** درجة قرب ورقتين من تكوين مجموعة: 0 = لا علاقة */
    private fun pairScore(a: Int, b: Int): Int {
        if (Cards.isJoker(a) || Cards.isJoker(b)) return 0
        val ra = Cards.rank(a)
        val rb = Cards.rank(b)
        return if (Cards.suit(a) == Cards.suit(b)) {
            val d = abs(ra - rb)
            when {
                d == 1 || (minOf(ra, rb) == 1 && maxOf(ra, rb) == 13) -> 3     // متتاليتان (7 و8 من نفس النوع)
                d == 2 || (minOf(ra, rb) == 1 && maxOf(ra, rb) == 12) -> 2     // بينهما ورقة واحدة
                else -> 0
            }
        } else if (ra == rb) 3 else 0                                           // نفس الرقم بنوعين مختلفين
    }

    /** كل ضغطة على «ترتيب» تعطي ترتيباً مختلفاً: 0 حسب المجموعات، 1 حسب النوع، 2 حسب الرقم */
    const val MODES = 3
    val MODE_NAMES = listOf("حسب المجموعات", "حسب النوع", "حسب الرقم")

    fun byMode(free: List<Int>, mode: Int): List<Int> = when (mode) {
        1 -> free.sortedWith(compareBy({ if (Cards.isJoker(it)) -1 else Cards.suit(it) }, { Cards.rank(it) }))
        2 -> free.sortedWith(compareBy({ if (Cards.isJoker(it)) -1 else Cards.rank(it) }, { Cards.suit(it) }))
        else -> layout(free).flatten()
    }

    /**
     * يرجع قطعاً مرتبة: كل قطعة مجموعة كاملة، أو زوج قريب من التكوين، أو أوراق متفرقة من نوع واحد.
     * ترتيب القطع هو ترتيب العرض في اليد (من اليسار).
     */
    fun layout(free: List<Int>): List<List<Int>> {
        if (free.isEmpty()) return emptyList()
        val out = ArrayList<List<Int>>()
        val used = HashSet<Int>()
        for (m in Bot.bestMelds(free).sortedByDescending { Melds.value(it) }) {
            out.add(Melds.display(m)); used.addAll(m)
        }
        var rest = free.filter { it !in used }
        val jokers = rest.filter { Cards.isJoker(it) }
        if (jokers.isNotEmpty()) { out.add(jokers); rest = rest.filter { !Cards.isJoker(it) } }
        val pool = rest.toMutableList()
        while (true) {
            var best: Pair<Int, Int>? = null
            var bestScore = 0
            for (i in pool.indices) for (j in i + 1 until pool.size) {
                val sc = pairScore(pool[i], pool[j])
                if (sc > bestScore) { bestScore = sc; best = pool[i] to pool[j] }
            }
            val bp = best ?: break
            pool.remove(bp.first); pool.remove(bp.second)
            out.add(listOf(bp.first, bp.second).sortedWith(compareBy({ Cards.suit(it) }, { Cards.rank(it) })))
        }
        for (s in 0..3) {
            val g = pool.filter { Cards.suit(it) == s }.sortedBy { Cards.rank(it) }
            if (g.isNotEmpty()) out.add(g)
        }
        return out
    }
}
