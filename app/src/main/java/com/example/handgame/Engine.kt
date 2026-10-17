package com.example.handgame

import org.json.JSONArray
import org.json.JSONObject

class Player(val id: Int, val name: String, val isBot: Boolean = false) {
    var noTake = false
    var hand = mutableListOf<Int>()
    var laid = false
    var total = 0
    var pen = 0                       // نقاط الرمي (بيض/شبشب) داخل الجولة الحالية
    val rounds = mutableListOf<Int>()
}

class Meld(val cards: MutableList<Int>)

private var fxCounter = 0   // يستمر بين الألعاب حتى لا تتكرر أرقام المؤثرات

/** محرك اللعبة — يعمل فقط على جوال المضيف، والبقية يستلمون الحالة. */
class Engine(val players: List<Player>, val totalRounds: Int) {
    companion object {
        const val TURN_MS = 60_000L   // دقيقة لكل لاعب
        const val OPEN_MIN = 51       // أول نزول
    }

    var round = 0
    val stock = ArrayDeque<Int>()
    val discard = ArrayDeque<Int>()
    val melds = mutableListOf<Meld>()
    var turn = 0
    var drew = false
    var taken = -1                    // ورقة مأخوذة من الرمي ولم تُستخدم بعد
    private var starter = -1
    var lastDiscBy = -1
    var roundOver = false
    var gameOver = false
    var roundWinner = -1
    var handWin = false
    var note = ""
    var deltas = mapOf<Int, Int>()
    var deadline = 0L
    val gid = (System.nanoTime() and 0x3fffffff).toInt()   // رقم اللعبة (يتغير عند لعبة جديدة)
    private var lastFx: JSONObject? = null

    /** مؤثر يراه الجميع (رمي بيض/شبشب، ضحكة): يصل للكل مع الحالة ويُميَّز برقم تسلسلي */
    fun emitFx(from: Int, to: Int, kind: String) {
        lastFx = JSONObject().put("seq", ++fxCounter).put("from", from).put("to", to).put("kind", kind)
    }

    /** يلعب البوت الحالي خطوة واحدة */
    fun botStep() {
        if (roundOver) return
        val p = players[turn]
        if (p.isBot) Bot.step(this, p)
    }

    fun startRound() {
        round++
        stock.clear(); stock.addAll((0 until Cards.DECK).shuffled())
        discard.clear(); melds.clear()
        for (p in players) {
            p.hand = MutableList(14) { stock.removeFirst() }
            p.laid = false
        }
        starter = (starter + 1) % players.size
        players[starter].hand.add(stock.removeFirst())      // الورقة رقم 15 لمن يبدأ
        turn = starter; drew = true; taken = -1; lastDiscBy = -1
        roundOver = false; roundWinner = -1; handWin = false; note = ""; deltas = emptyMap()
        deadline = System.currentTimeMillis() + TURN_MS
    }

    private fun refill() {
        if (stock.isEmpty() && discard.size > 1) {
            val top = discard.removeLast()
            stock.addAll(discard.shuffled())
            discard.clear(); discard.addLast(top)
        }
    }

    private fun check(pid: Int): String? = when {
        roundOver -> "الجولة انتهت"
        pid != turn -> "ليس دورك"
        else -> null
    }

    /** سحب من الكومة */
    fun draw(pid: Int): String? {
        check(pid)?.let { return it }
        if (drew) return "سحبت ورقة بالفعل"
        refill()
        if (stock.isEmpty()) return "لا توجد أوراق للسحب"
        players[pid].hand.add(stock.removeFirst())
        drew = true
        return null
    }

    /** أخذ الورقة المرمية بدل السحب: لا تبقى معك إلا إذا استخدمتها في نزول أو على الطاولة، وإلا ترجعها */
    fun takeDiscard(pid: Int): String? {
        check(pid)?.let { return it }
        if (drew) return "سحبت ورقة بالفعل"
        val top = discard.lastOrNull() ?: return "لا توجد ورقة مرمية"
        discard.removeLast(); players[pid].hand.add(top)
        drew = true; taken = top
        return null
    }

    /** إرجاع الورقة المأخوذة مكانها */
    fun returnTaken(pid: Int): String? {
        check(pid)?.let { return it }
        val p = players[pid]
        if (taken < 0 || taken !in p.hand) return "لا توجد ورقة لإرجاعها"
        p.hand.remove(taken); discard.addLast(taken)
        taken = -1; drew = false
        return null
    }

    /** نزول مجموعات (في دورك، قبل السحب أو بعده) */
    fun meld(pid: Int, groups: List<List<Int>>): String? {
        check(pid)?.let { return it }
        return lay(pid, groups)
    }

    private fun lay(pid: Int, groups: List<List<Int>>): String? {
        val p = players[pid]
        val all = groups.flatten()
        if (all.isEmpty() || all.toSet().size != all.size || !p.hand.containsAll(all)) return "أوراق غير صالحة"
        var sum = 0
        for (g in groups) {
            val v = Melds.value(g)
            if (v < 0) return "مجموعة غير صحيحة"
            sum += v
        }
        if (!p.laid && sum < OPEN_MIN) return "أول نزول لازم $OPEN_MIN نقطة أو أكثر (مجموعك $sum)"
        val first = !p.laid
        p.hand.removeAll(all.toSet())
        for (g in groups) melds.add(Meld(Melds.display(g).toMutableList()))
        p.laid = true
        if (taken in all) taken = -1
        when {
            first && p.hand.size <= 1 -> {      // هاند: نزّل كل أوراقه دفعة واحدة، والورقة الأخيرة تُرمى تلقائياً
                if (p.hand.size == 1) { discard.addLast(p.hand.removeAt(0)); lastDiscBy = pid }
                finish(pid, true)
            }
            p.hand.isEmpty() -> finish(pid, false)
        }
        return null
    }

    /**
     * وضع ورقة على مجموعة في الطاولة. الأولوية لاستبدال الجوكر إذا ناسبت الورقة مكانه
     * (فتأخذ الجوكر)، وإلا تُضاف للمجموعة بشرط أنك نزّلت سابقاً.
     */
    fun addToMeld(pid: Int, idx: Int, card: Int): String? {
        check(pid)?.let { return it }
        val p = players[pid]
        if (idx !in melds.indices || card !in p.hand) return "غير صالح"
        if (card == taken && p.hand.size < 3) return "تحتاج أكثر من ورقتين في يدك لتضع هذه الورقة"
        val m = melds[idx]
        var swapped = false
        if (!Cards.isJoker(card)) {
            for (j in m.cards.indices) {
                if (!Cards.isJoker(m.cards[j])) continue
                val trial = m.cards.toMutableList().also { it[j] = card }
                if (Melds.value(trial) >= 0) {
                    val joker = m.cards[j]
                    m.cards.clear(); m.cards.addAll(Melds.display(trial))
                    p.hand.remove(card); p.hand.add(joker)
                    swapped = true
                    break
                }
            }
        }
        if (!swapped) {
            if (!p.laid) return "لازم تنزّل أولاً (أو استبدل جوكر بالورقة المناسبة)"
            if (Melds.value(m.cards + card) < 0) return "الورقة لا تناسب هذه المجموعة"
            val nl = Melds.display(m.cards + card)
            m.cards.clear(); m.cards.addAll(nl); p.hand.remove(card)
        }
        if (card == taken) taken = -1
        if (p.hand.isEmpty()) finish(pid, false)
        return null
    }

    fun discardCard(pid: Int, card: Int): String? {
        check(pid)?.let { return it }
        if (!drew) return "اسحب ورقة أولاً"
        if (taken >= 0) return "استخدم الورقة المأخوذة أو أرجعها أولاً"
        val p = players[pid]
        if (card !in p.hand) return "غير صالح"
        p.hand.remove(card); discard.addLast(card); lastDiscBy = pid
        if (p.hand.isEmpty()) finish(pid, false) else nextTurn()
        return null
    }

    private fun nextTurn() {
        turn = (turn + 1) % players.size
        drew = false; taken = -1
        deadline = System.currentTimeMillis() + TURN_MS
    }

    /** انتهى الوقت: إرجاع الورقة المأخوذة إن وُجدت، سحب تلقائي، ثم رمي أعلى ورقة قيمة */
    fun timeout() {
        if (roundOver) return
        val p = players[turn]
        if (taken >= 0) returnTaken(turn)
        if (!drew) draw(turn)
        drew = true; taken = -1
        val c = p.hand.filter { !Cards.isJoker(it) }.maxByOrNull { Cards.points(it) } ?: p.hand.firstOrNull() ?: return
        discardCard(turn, c)
    }

    /** الخاسر: أعلى مجموع نقاط، وعند التعادل الأسوأ في الجولة الأخيرة */
    fun loserId(): Int = players.maxWithOrNull(
        compareBy<Player>({ it.total }, { it.rounds.lastOrNull() ?: 0 }, { it.id })
    )!!.id

    /**
     * رمي بيض/شبشب على لاعب. أثناء اللعب: المتصدر +1 والبقية +5 على نقاط الرامي.
     * بعد انتهاء كل الجولات: الضرب مجاني وعلى الخاسر فقط.
     */
    fun throwAt(from: Int, to: Int, kind: String): String? {
        if (from == to || from !in players.indices || to !in players.indices) return "غير صالح"
        if (kind != "egg" && kind != "slipper") return "غير صالح"
        if (gameOver && roundOver) {
            if (to != loserId()) return "الضرب على الخاسر فقط"
        } else {
            val p = players[from]
            val cost = if (p.total == players.minOf { it.total }) 1 else 5
            p.total += cost; p.pen += cost
        }
        emitFx(from, to, kind)
        return null
    }

    /**
     * هاند (نزول كل الأوراق دفعة واحدة): -60 والبقية يتضاعف حسابهم، ومن لم ينزّل 200.
     * ضمنة (إنهاء بعد نزول سابق): -30 والبقية بقيمة أوراقهم، ومن لم ينزّل 100.
     */
    private fun finish(w: Int, hand: Boolean) {
        val d = mutableMapOf<Int, Int>()
        for (p in players) {
            val s = if (p.id == w) (if (hand) -60 else -30) else {
                val base = if (!p.laid) 100 else p.hand.sumOf { Cards.points(it) }
                if (hand) base * 2 else base
            }
            d[p.id] = s; p.total += s
            p.rounds.add(s + p.pen); p.pen = 0
        }
        deltas = d; roundOver = true; gameOver = round >= totalRounds; taken = -1
        roundWinner = w; handWin = hand
        note = if (hand) "هاند!" else "ضمنة"
    }

    fun viewFor(pid: Int): JSONObject = JSONObject().apply {
        put("t", "state"); put("gid", gid); put("me", pid); put("round", round); put("rounds", totalRounds)
        put("turn", turn); put("drew", drew); put("dby", lastDiscBy)
        put("taken", if (pid == turn) taken else -1)
        lastFx?.let { put("fx", it) }
        put("sec", maxOf(0, ((deadline - System.currentTimeMillis()) / 1000).toInt()))
        put("stock", stock.size); put("discTop", discard.lastOrNull() ?: -1)
        put("roundOver", roundOver); put("gameOver", gameOver); put("note", note)
        put("rw", roundWinner); put("hw", handWin)
        put("players", JSONArray().apply {
            for (p in players) put(
                JSONObject().put("id", p.id).put("name", p.name).put("n", p.hand.size)
                    .put("total", p.total).put("laid", p.laid).put("delta", deltas[p.id] ?: 0)
                    .put("hist", JSONArray(p.rounds))
            )
        })
        put("hand", JSONArray(players[pid].hand))
        put("melds", JSONArray().apply { for (m in melds) put(JSONArray(m.cards)) })
    }
}
