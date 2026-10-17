package com.example.handgame

/** 106 ورقة = مجموعتان كاملتان (2×52) + جوكران. المعرّف 0..105 */
object Cards {
    const val DECK = 106
    fun isJoker(id: Int) = id >= 104
    fun suit(id: Int) = if (isJoker(id)) -1 else (id % 52) / 13      // 0♠ 1♥ 2♦ 3♣
    fun rank(id: Int) = if (isJoker(id)) 0 else (id % 52) % 13 + 1   // 1=آص .. 13=شايب

    fun points(id: Int) = when {
        isJoker(id) -> 15
        rank(id) == 1 -> 11
        rank(id) >= 11 -> 10
        else -> rank(id)
    }

    fun rankLabel(id: Int) = when (val r = rank(id)) {
        1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> r.toString()
    }

    fun suitSymbol(id: Int) = listOf("♠", "♥", "♦", "♣").getOrElse(suit(id)) { "" }
}

object Melds {
    private fun rv(r: Int) = when {
        r == 1 || r == 14 -> 11
        r >= 11 -> 10
        else -> r
    }

    /**
     * قيمة المجموعة بالنقاط، أو -1 إذا كانت غير صحيحة.
     * - تشكيلة: نفس الرقم/الصورة بأنواع مختلفة (3 أو 4 أوراق)
     * - تسلسل: نفس النوع، أرقام متسلسلة (3 فأكثر)، الآص قبل 2 أو بعد الشايب
     * - الجوكر يعوّض أي ورقة (وقيمته قيمة الورقة التي يعوّضها)
     */
    fun value(ids: List<Int>): Int {
        if (ids.size < 3) return -1
        val reg = ids.filter { !Cards.isJoker(it) }
        if (reg.isEmpty()) return -1
        val ranks = reg.map { Cards.rank(it) }
        val suits = reg.map { Cards.suit(it) }

        if (ranks.toSet().size == 1 && suits.toSet().size == reg.size && ids.size <= 4)
            return rv(ranks[0]) * ids.size

        if (suits.toSet().size != 1) return -1
        val n = ids.size
        var best = -1
        for (aceHigh in listOf(false, true)) {
            val rs = ranks.map { if (it == 1 && aceHigh) 14 else it }
            if (rs.toSet().size != rs.size) continue
            val mn = rs.min()
            val mx = rs.max()
            if (mx - mn + 1 > n) continue
            for (s in 1..(15 - n)) {
                if (s <= mn && s + n - 1 >= mx) {
                    var v = 0
                    for (r in s until s + n) v += rv(r)
                    if (v > best) best = v
                }
            }
        }
        return best
    }

    /** ترتيب العرض على الطاولة: التسلسل مرتب والجوكر في مكانه */
    fun display(ids: List<Int>): List<Int> {
        val jk = ids.filter { Cards.isJoker(it) }.toMutableList()
        val reg = ids.filter { !Cards.isJoker(it) }
        if (reg.isEmpty() || reg.map { Cards.rank(it) }.toSet().size == 1) return reg + jk
        val aceHigh = reg.any { Cards.rank(it) == 1 } && reg.any { Cards.rank(it) >= 11 }
        fun er(id: Int) = if (Cards.rank(id) == 1 && aceHigh) 14 else Cards.rank(id)
        val out = mutableListOf<Int>()
        var expected = er(reg.minByOrNull { er(it) }!!)
        for (c in reg.sortedBy { er(it) }) {
            while (expected < er(c) && jk.isNotEmpty()) { out.add(jk.removeAt(0)); expected++ }
            out.add(c); expected = er(c) + 1
        }
        out.addAll(jk)
        return out
    }
}
