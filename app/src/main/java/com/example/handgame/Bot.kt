package com.example.handgame

import kotlin.math.abs

/** لاعب آلي: يلعب على جوال المضيف بنفس قواعد البشر تماماً */
object Bot {
    private fun combos(list: List<Int>, k: Int): List<List<Int>> {
        if (k == 0) return listOf(emptyList())
        if (list.size < k) return emptyList()
        val out = ArrayList<List<Int>>()
        fun rec(start: Int, cur: List<Int>) {
            if (cur.size == k) { out.add(cur); return }
            for (i in start until list.size) rec(i + 1, cur + list[i])
        }
        rec(0, emptyList())
        return out
    }

    /** كل المجموعات الممكنة من اليد (تشكيلات وتسلسلات، مع الجوكر) */
    private fun candidates(hand: List<Int>): List<List<Int>> {
        val jokers = hand.filter { Cards.isJoker(it) }
        val regs = hand.filter { !Cards.isJoker(it) }
        val out = ArrayList<List<Int>>()
        for (r in 1..13) {
            val reps = regs.filter { Cards.rank(it) == r }.groupBy { Cards.suit(it) }.map { it.value.first() }
            for (size in 3..4) {
                for (k in 0..minOf(2, jokers.size)) {
                    val need = size - k
                    if (need < 1 || need > reps.size) continue
                    for (c in combos(reps, need)) out.add(c + jokers.take(k))
                }
            }
        }
        for (suit in 0..3) {
            val byRank = regs.filter { Cards.suit(it) == suit }.groupBy { Cards.rank(it) }.mapValues { it.value.first() }
            for (start in 1..12) {
                for (len in 3..(15 - start)) {
                    val cs = ArrayList<Int>()
                    var js = 0
                    for (p in start until start + len) {
                        val c = byRank[if (p == 14) 1 else p]
                        if (c != null) cs.add(c) else js++
                    }
                    if (cs.isNotEmpty() && js <= jokers.size) out.add(cs + jokers.take(js))
                }
            }
        }
        return out.filter { Melds.value(it) >= 0 }.distinctBy { it.sorted() }
    }

    /** أفضل تقسيم لليد إلى مجموعات منفصلة بأعلى نقاط */
    fun bestMelds(hand: List<Int>): List<List<Int>> {
        val cands = candidates(hand).sortedByDescending { Melds.value(it) }
        var best: List<List<Int>> = emptyList()
        var bestV = 0
        var nodes = 0
        fun rec(i: Int, used: Set<Int>, cur: List<List<Int>>, v: Int) {
            if (v > bestV) { bestV = v; best = cur }
            if (++nodes > 25000) return
            for (j in i until cands.size) {
                val c = cands[j]
                if (c.none { it in used }) rec(j + 1, used + c, cur + listOf(c), v + Melds.value(c))
            }
        }
        rec(0, emptySet(), emptyList(), 0)
        return best
    }

    /** يرمي الورقة الأعلى قيمة والأقل فائدة (لا يرمي الجوكر ولا الورقة المأخوذة) */
    private fun pickDiscard(hand: List<Int>, taken: Int): Int {
        fun keep(c: Int): Int {
            if (Cards.isJoker(c)) return 100
            var u = 0
            for (d in hand) {
                if (d == c || Cards.isJoker(d)) continue
                if (Cards.rank(d) == Cards.rank(c) && Cards.suit(d) != Cards.suit(c)) u += 2
                else if (Cards.suit(d) == Cards.suit(c)) {
                    val diff = abs(Cards.rank(d) - Cards.rank(c))
                    if (diff == 1) u += 3 else if (diff == 2) u += 1
                }
            }
            return u
        }
        val pool = hand.filter { it != taken }.ifEmpty { hand }
        return pool.maxByOrNull { Cards.points(it) - keep(it) * 5 } ?: hand.first()
    }

    /** يضيف ما يقدر من أوراقه على نزول الطاولة (ويأخذ الجوكر إن ناسبت الورقة) */
    private fun tryAdd(e: Engine, p: Player) {
        var guard = 0
        var progressed = true
        while (progressed && !e.roundOver && guard++ < 30) {
            progressed = false
            loop@ for (c in p.hand.toList()) {
                for (idx in e.melds.indices) {
                    if (e.addToMeld(p.id, idx, c) == null) { progressed = true; break@loop }
                }
            }
        }
    }

    /** خطوة واحدة: السحب أو الأخذ، ثم في الخطوة التالية النزول والإضافة والرمي */
    fun step(e: Engine, p: Player) {
        val pid = p.id
        if (!e.drew) {
            val top = e.discard.lastOrNull()
            if (top != null && !p.noTake) {
                val vb = bestMelds(p.hand).sumOf { Melds.value(it) }
                val with = bestMelds(p.hand + top)
                val vw = with.sumOf { Melds.value(it) }
                if (vw > vb && with.any { top in it } && (p.laid || vw >= Engine.OPEN_MIN)) {
                    e.takeDiscard(pid); return
                }
            }
            p.noTake = false
            if (e.draw(pid) != null) e.timeout()
            return
        }
        val parts = bestMelds(p.hand)
        val total = parts.sumOf { Melds.value(it) }
        if (parts.isNotEmpty() && (p.laid || total >= Engine.OPEN_MIN)) {
            e.meld(pid, parts)
            if (e.roundOver) return
        }
        if (p.laid || e.taken >= 0) {
            tryAdd(e, p)
            if (e.roundOver) return
        }
        if (e.taken >= 0) { e.returnTaken(pid); p.noTake = true; return }
        e.discardCard(pid, pickDiscard(p.hand, e.taken))
    }
}
