package com.example.handgame

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

val APP_UUID: UUID = UUID.fromString("8f4c6a1e-3b7d-4c52-9a1f-5d2e7b9c0a41")

/** اتصال بلوتوث (RFCOMM) — رسائل JSON، كل رسالة في سطر */
class Link(private val socket: BluetoothSocket) {
    private val out = socket.outputStream.bufferedWriter(Charsets.UTF_8)
    private val inp = socket.inputStream.bufferedReader(Charsets.UTF_8)
    private val q = Executors.newSingleThreadExecutor()

    fun send(o: JSONObject) {
        q.execute { try { out.write(o.toString()); out.write("\n"); out.flush() } catch (_: Exception) {} }
    }

    fun readLoop(onMsg: (JSONObject) -> Unit, onClose: () -> Unit) {
        thread {
            try {
                while (true) { val l = inp.readLine() ?: break; onMsg(JSONObject(l)) }
            } catch (_: Exception) {}
            onClose()
        }
    }

    fun close() { try { socket.close() } catch (_: Exception) {}; q.shutdown() }
}

class PInfo(val id: Int, val name: String, val n: Int, val total: Int, val laid: Boolean, val delta: Int, val hist: List<Int>)

class UiState(
    val me: Int, val round: Int, val rounds: Int, val turn: Int, val drew: Boolean, val sec: Int,
    val stock: Int, val discTop: Int, val players: List<PInfo>, val hand: List<Int>,
    val melds: List<List<Int>>, val roundOver: Boolean, val gameOver: Boolean, val note: String,
    val dby: Int, val rw: Int, val hw: Boolean, val taken: Int, val gid: Int
)

class FxEvent(val seq: Int, val from: Int, val to: Int, val kind: String)
class Hit(val seq: Int, val kind: String)

/** الفائز = أقل نقاط، والخاسر = أعلى نقاط (والتعادل للأسوأ في آخر جولة) */
fun finalWinner(ps: List<PInfo>) = ps.minWithOrNull(compareBy<PInfo>({ it.total }, { it.hist.lastOrNull() ?: 0 }))
fun finalLoser(ps: List<PInfo>) = ps.maxWithOrNull(compareBy<PInfo>({ it.total }, { it.hist.lastOrNull() ?: 0 }))

@SuppressLint("MissingPermission")
class Controller {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    var screen by mutableStateOf("menu")      // menu | hostLobby | clientLobby | join | table
    var myName by mutableStateOf("لاعب")
    var rounds by mutableIntStateOf(3)
    var botCount by mutableIntStateOf(0)       // عدد البوتات يختاره المضيف
    var onName: ((String) -> Unit)? = null     // لحفظ الاسم على الجوال
    private val botNames = listOf("بوت سلطان", "بوت ريم", "بوت فهد")
    private var botPending = false

    /** عدد البوتات الفعلي: لا يزيد المجموع عن 4 لاعبين */
    fun effectiveBots() = botCount.coerceIn(0, maxOf(0, 4 - lobby.size))
    fun botName(i: Int) = botNames[i % botNames.size]

    fun rename(n: String) { myName = n.take(12); onName?.invoke(myName) }
    var msg by mutableStateOf("")
    val lobby = mutableStateListOf<String>()
    var ui by mutableStateOf<UiState?>(null)
    val order = mutableStateListOf<Int>()     // ترتيب الورق اليدوي عند اللاعب
    val fresh = mutableStateListOf<Int>()     // أوراق جديدة (إطار ملوّن 3 ثوانٍ)
    val groups = mutableStateListOf<List<Int>>()   // مجموعات مكوّنة في اليد (محلية، لم تُنزَّل)
    val fxEvents = mutableStateListOf<FxEvent>()   // بيض/شبشب طائر
    val hits = mutableStateMapOf<Int, Hit>()       // أثر الضربة (ثانيتان)
    val laughs = mutableStateMapOf<Int, Int>()     // أفاتار يضحك
    val centers = mutableStateMapOf<Int, Offset>() // مواضع الأفاتارات على الشاشة
    var peek by mutableStateOf<Pair<Int, List<Int>>?>(null)  // كشف ورق خصم (5 ثوانٍ)
    var isHost = false

    private var engine: Engine? = null
    private val links = mutableMapOf<Int, Link>()
    private val orders = mutableMapOf<Int, List<Int>>()   // ترتيب ورق اللاعبين (للكشف السري)
    private var server: BluetoothServerSocket? = null
    private var clientLink: Link? = null
    private var fxSeen = 0

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun openJoin() {
        if (adapter?.isEnabled != true) { msg = "فعّل البلوتوث أولاً"; return }
        screen = "join"
    }

    // ---------------- المضيف ----------------
    fun beginListening() {
        val a = adapter ?: run { msg = "الجهاز لا يدعم البلوتوث"; return }
        isHost = true; lobby.clear(); lobby.add(myName); links.clear(); orders.clear(); engine = null
        try {
            val ss = a.listenUsingRfcommWithServiceRecord("HandGame", APP_UUID)
            server = ss
            screen = "hostLobby"
            thread { try { while (true) onIncoming(Link(ss.accept())) } catch (_: Exception) {} }
        } catch (e: Exception) { msg = "تعذّر بدء الاستضافة" }
    }

    private fun onIncoming(link: Link) {
        var pid = -1
        link.readLoop({ m ->
            main.post {
                if (m.optString("t") == "join" && pid < 0) {
                    if (engine != null || lobby.size >= 4) {
                        link.send(JSONObject().put("t", "full")); link.close()
                    } else {
                        pid = lobby.size
                        lobby.add(m.optString("name", "لاعب"))
                        links[pid] = link
                        link.send(JSONObject().put("t", "joined").put("id", pid))
                        sendLobby()
                    }
                } else if (pid >= 0) act(pid, m)
            }
        }, {
            main.post { if (pid >= 0 && engine == null) lobby[pid] = lobby[pid] + " ⚠" }
        })
    }

    private fun sendLobby() {
        val o = JSONObject().put("t", "lobby").put("names", JSONArray(lobby.toList()))
        links.values.forEach { it.send(o) }
    }

    fun startGame() {
        val nb = effectiveBots()
        if (lobby.size + nb < 2) return
        try { server?.close() } catch (_: Exception) {}
        val humans = lobby.mapIndexed { i, n -> Player(i, n) }
        val bots = List(nb) { Player(lobby.size + it, botName(it), isBot = true) }
        val e = Engine(humans + bots, rounds)
        e.startRound(); engine = e
        screen = "table"; pushState(); tick()
    }

    private fun tick() {
        main.postDelayed({
            val e = engine ?: return@postDelayed
            if (!e.roundOver && System.currentTimeMillis() > e.deadline) e.timeout()
            pushState(); tick()
        }, 1000)
    }

    private fun pushState() {
        val e = engine ?: return
        for ((pid, l) in links) l.send(e.viewFor(pid))
        applyView(e.viewFor(0))
        scheduleBot()
    }

    /** البوت يفكر ~1.5 ثانية ثم يلعب خطوة (سحب، ثم نزول ورمي) */
    private fun scheduleBot() {
        val e = engine ?: return
        if (botPending || e.roundOver || !e.players[e.turn].isBot) return
        botPending = true
        main.postDelayed({
            botPending = false
            val en = engine ?: return@postDelayed
            if (!en.roundOver && en.players[en.turn].isBot) { en.botStep(); pushState() }
        }, 1500)
    }

    /** لعبة جديدة بنفس اللاعبين والبوتات وعدد الجولات */
    private fun restart(old: Engine) {
        val e = Engine(old.players.map { Player(it.id, it.name, it.isBot) }, old.totalRounds)
        e.startRound(); engine = e; orders.clear()
    }

    private fun groupsOf(m: JSONObject): List<List<Int>> {
        val arr = m.getJSONArray("groups")
        return List(arr.length()) { i -> val g = arr.getJSONArray(i); List(g.length()) { j -> g.getInt(j) } }
    }

    private fun act(pid: Int, m: JSONObject) {
        val e = engine ?: return
        try {
            val err: String? = when (m.optString("t")) {
                "draw" -> e.draw(pid)
                "take" -> e.takeDiscard(pid)
                "return" -> e.returnTaken(pid)
                "meld" -> e.meld(pid, groupsOf(m))
                "add" -> e.addToMeld(pid, m.getInt("meld"), m.getInt("card"))
                "discard" -> e.discardCard(pid, m.getInt("card"))
                "throw" -> e.throwAt(pid, m.getInt("to"), m.getString("kind"))
                "peek" -> { handlePeek(e, pid, m.getInt("to")); null }
                "order" -> { val a = m.getJSONArray("ids"); orders[pid] = List(a.length()) { a.getInt(it) }; null }
                "newgame" -> { if (e.roundOver && e.gameOver) restart(e); null }
                "next" -> { if (pid == 0 && e.roundOver && !e.gameOver) e.startRound(); null }
                else -> null
            }
            if (err != null) { if (pid == 0) msg = err else links[pid]?.send(JSONObject().put("t", "err").put("msg", err)) }
        } catch (_: Exception) {}
        pushState()
    }

    /** كشف ورق خصم: يصل للطالب وحده، ولا يعلم الخصم. الضحكة وحدها تظهر للجميع. */
    private fun handlePeek(e: Engine, pid: Int, target: Int) {
        if (target !in e.players.indices || target == pid) return
        val hand = e.players[target].hand
        val ord = (if (target == 0) order.toList() else orders[target] ?: emptyList()).filter { it in hand }
        val cards = ord + hand.filter { it !in ord }
        e.emitFx(pid, pid, "laugh")
        if (pid == 0) setPeek(target, cards)
        else links[pid]?.send(JSONObject().put("t", "peekres").put("to", target).put("cards", JSONArray(cards)))
    }

    private fun setPeek(target: Int, cards: List<Int>) {
        val p = Pair(target, cards)
        peek = p
        main.postDelayed({ if (peek === p) peek = null }, 5000)
    }

    fun send(o: JSONObject) { if (isHost) act(0, o) else clientLink?.send(o) }

    // ---------------- الضيف ----------------
    fun connect(d: BluetoothDevice) {
        isHost = false; msg = "جارٍ الاتصال..."
        thread {
            try {
                adapter?.cancelDiscovery()
                val s = d.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                val l = Link(s); clientLink = l
                main.post { msg = ""; screen = "clientLobby" }
                l.send(JSONObject().put("t", "join").put("name", myName))
                l.readLoop({ m -> main.post { onServerMsg(m) } }, {
                    main.post { if (screen != "menu") { msg = "انقطع الاتصال"; reset() } }
                })
            } catch (e: Exception) { main.post { msg = "فشل الاتصال — تأكد أن المضيف فتح الاستضافة" } }
        }
    }

    private fun onServerMsg(m: JSONObject) {
        when (m.optString("t")) {
            "lobby" -> { lobby.clear(); val a = m.getJSONArray("names"); for (i in 0 until a.length()) lobby.add(a.getString(i)) }
            "state" -> { screen = "table"; applyView(m) }
            "err" -> msg = m.optString("msg")
            "full" -> { msg = "الغرفة ممتلئة"; reset() }
            "peekres" -> {
                val a = m.getJSONArray("cards")
                setPeek(m.getInt("to"), List(a.length()) { a.getInt(it) })
            }
        }
    }

    // ---------------- مشترك ----------------
    private fun applyView(v: JSONObject) {
        fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
        val pa = v.getJSONArray("players")
        val ps = List(pa.length()) {
            val o = pa.getJSONObject(it)
            val h = o.getJSONArray("hist")
            PInfo(o.getInt("id"), o.getString("name"), o.getInt("n"), o.getInt("total"), o.getBoolean("laid"), o.getInt("delta"), List(h.length()) { k -> h.getInt(k) })
        }
        val hand = ints(v.getJSONArray("hand"))
        val prev = ui
        val ma = v.getJSONArray("melds")
        val st = UiState(
            v.getInt("me"), v.getInt("round"), v.getInt("rounds"), v.getInt("turn"), v.getBoolean("drew"),
            v.getInt("sec"), v.getInt("stock"), v.getInt("discTop"), ps, hand,
            List(ma.length()) { ints(ma.getJSONArray(it)) },
            v.getBoolean("roundOver"), v.getBoolean("gameOver"), v.getString("note"),
            v.getInt("dby"), v.getInt("rw"), v.getBoolean("hw"), v.getInt("taken"), v.getInt("gid")
        )
        ui = st
        val sameRound = prev != null && prev.round == st.round && prev.gid == st.gid
        if (!sameRound) { order.clear(); fresh.clear(); groups.clear() }
        val added = hand.filter { it !in order }
        // الورقة الجديدة تظهر في أقصى اليسار بإطار ملوّن لمدة 3 ثوانٍ
        val merged = added + order.filter { it in hand }
        if (sameRound && prev!!.hand.isNotEmpty()) {
            added.forEach { id -> fresh.add(id); main.postDelayed({ fresh.remove(id) }, 3000) }
        }
        if (merged != order.toList()) { order.clear(); order.addAll(merged); pushOrder() }
        groups.removeAll { g -> !hand.containsAll(g) }
        v.optJSONObject("fx")?.let { handleFx(it) }
    }

    private fun handleFx(f: JSONObject) {
        val seq = f.getInt("seq")
        if (seq <= fxSeen) return
        fxSeen = seq
        val from = f.getInt("from")
        val to = f.getInt("to")
        val kind = f.getString("kind")
        if (kind == "laugh") { laugh(from, seq); return }
        fxEvents.add(FxEvent(seq, from, to, kind))
        main.postDelayed({
            fxEvents.removeAll { it.seq == seq }
            val s = ui
            val champ = if (s != null && s.gameOver && s.roundOver) finalWinner(s.players)?.id else null
            Sfx.play(kind)
            if (champ == null || to != champ) {          // الفائز لا يترك الضرب أثراً على وجهه
                hits[to] = Hit(seq, kind)
                main.postDelayed({ if (hits[to]?.seq == seq) hits.remove(to) }, 2000)
            }
            if (champ != null && from == champ) laugh(from, seq)   // ضحكة الفائز المستفزة
        }, 650)
    }

    private fun laugh(pid: Int, seq: Int) {
        laughs[pid] = seq
        Sfx.play("laugh")
        main.postDelayed({ if (laughs[pid] == seq) laughs.remove(pid) }, 2500)
    }

    private fun pushOrder() {
        if (!isHost) clientLink?.send(JSONObject().put("t", "order").put("ids", JSONArray(order.toList())))
    }

    fun moveCard(from: Int, to: Int) {
        if (from != to && from in order.indices && to in order.indices) { order.add(to, order.removeAt(from)); pushOrder() }
    }

    /** تكوين: يجمع الأوراق المحددة في مجموعة داخل اليد بدون إنزالها */
    fun formGroup(ids: List<Int>) {
        if (ids.size < 2) return
        ungroupCards(ids)
        val sorted = ids.sortedBy { order.indexOf(it) }
        val pos = order.indexOf(sorted.first()).coerceAtLeast(0)
        val disp = if (Melds.value(sorted) >= 0) Melds.display(sorted) else sorted
        order.removeAll(ids.toSet())
        order.addAll(minOf(pos, order.size), disp)
        groups.add(disp)
        pushOrder()
    }

    /** فك التكوين: يفك كل مجموعة تحتوي أي ورقة من المحددة */
    fun ungroupCards(ids: Collection<Int>) { groups.removeAll { g -> g.any { it in ids } } }

    fun reset() {
        engine = null; ui = null; order.clear(); lobby.clear(); groups.clear(); fresh.clear()
        fxEvents.clear(); hits.clear(); laughs.clear(); centers.clear(); peek = null; fxSeen = 0; orders.clear()
        try { server?.close() } catch (_: Exception) {}
        links.values.forEach { it.close() }; links.clear()
        clientLink?.close(); clientLink = null
        botPending = false
        screen = "menu"
    }
}
