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

    /** هل المجموعة تشكيلة (نفس الرقم بأنواع مختلفة، حتى 4 أوراق)؟ */
    fun isSet(ids: List<Int>): Boolean {
        val reg = ids.filter { !Cards.isJoker(it) }
        return reg.isNotEmpty() && ids.size <= 4 &&
            reg.map { Cards.rank(it) }.toSet().size == 1 &&
            reg.map { Cards.suit(it) }.toSet().size == reg.size
    }

    /** هل الورقة تساوي المرتبة المتوقعة (الآص يصلح 1 أو 14)؟ */
    fun matches(card: Int, expected: Int) =
        Cards.rank(card) == expected || (Cards.rank(card) == 1 && expected == 14)

    /**
     * مراتب المواقع (1..14) لتسلسل مرتب كما نزل. الاتجاه (صاعد أو نازل) يُعرف من الورق الحقيقي،
     * والجوكر يمثل ما يناسب موقعه. يرجع null إن لم يكن الترتيب المعطى تسلسلاً صحيحاً.
     */
    fun runRanks(order: List<Int>): List<Int>? {
        val n = order.size
        if (n < 3) return null
        val first = order.indexOfFirst { !Cards.isJoker(it) }
        if (first < 0) return null
        if (order.filter { !Cards.isJoker(it) }.map { Cards.suit(it) }.toSet().size != 1) return null
        val r0s = if (Cards.rank(order[first]) == 1) listOf(1, 14) else listOf(Cards.rank(order[first]))
        for (dir in listOf(1, -1)) {
            for (r0 in r0s) {
                val exp = List(n) { k -> r0 + dir * (k - first) }
                if (exp.any { it < 1 || it > 14 }) continue
                if (order.indices.all { k -> Cards.isJoker(order[k]) || matches(order[k], exp[k]) }) return exp
            }
        }
        return null
    }

    /** أفضل ترتيب صاعد لتسلسل (الجوكر في مكانه) عندما لا يكون ترتيب اليد تسلسلاً */
    private fun layout(ids: List<Int>): List<Int> {
        val n = ids.size
        val jokers = ids.filter { Cards.isJoker(it) }
        val reg = ids.filter { !Cards.isJoker(it) }
        var best: List<Int>? = null
        var bestV = -1
        for (aceHigh in listOf(false, true)) {
            val pos = reg.groupBy { if (Cards.rank(it) == 1 && aceHigh) 14 else Cards.rank(it) }
            if (pos.isEmpty() || pos.any { it.value.size > 1 }) continue
            val mn = pos.keys.minOrNull()!!
            val mx = pos.keys.maxOrNull()!!
            if (mx - mn + 1 > n) continue
            for (s in 1..(15 - n)) {
                if (s <= mn && s + n - 1 >= mx) {
                    var v = 0
                    val js = jokers.toMutableList()
                    val out = ArrayList<Int>()
                    for (r in s until s + n) { v += rv(r); out.add(pos[r]?.first() ?: js.removeAt(0)) }
                    if (v > bestV) { bestV = v; best = out }
                }
            }
        }
        return best ?: ids
    }

    /** ترتيب المجموعة للنزول: يبقى كما نزّله اللاعب إن كان صحيحاً، وإلا يُرتَّب تلقائياً */
    fun arrange(ids: List<Int>): List<Int> =
        if (isSet(ids) || runRanks(ids) != null) ids else layout(ids)

    fun display(ids: List<Int>): List<Int> = arrange(ids)

    /**
     * يضيف ورقة إلى مجموعة على الطاولة (بدون تغيير مواقع ما فيها)، أو null إن لم تناسب.
     * التسلسل يمتد من طرفيه فقط، والتشكيلة حتى 4 أوراق.
     */
    fun extend(cs: List<Int>, card: Int): List<Int>? {
        if (isSet(cs)) {
            if (cs.size >= 4) return null
            if (Cards.isJoker(card)) return cs + card
            val reg = cs.filter { !Cards.isJoker(it) }
            if (Cards.rank(card) != Cards.rank(reg[0]) || reg.any { Cards.suit(it) == Cards.suit(card) }) return null
            return cs + card
        }
        val ranks = runRanks(cs) ?: return null
        val dir = if (ranks[1] > ranks[0]) 1 else -1
        val after = ranks.last() + dir
        val before = ranks.first() - dir
        if (Cards.isJoker(card)) {
            if (after in 1..14) return cs + card
            if (before in 1..14) return listOf(card) + cs
            return null
        }
        val suit = Cards.suit(cs.first { !Cards.isJoker(it) })
        if (Cards.suit(card) != suit) return null
        if (after in 1..14 && matches(card, after)) return cs + card
        if (before in 1..14 && matches(card, before)) return listOf(card) + cs
        return null
    }
}
