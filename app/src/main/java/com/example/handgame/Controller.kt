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
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

const val WIFI_PORT = 47321          // اتصال اللعبة عبر الواي فاي (TCP)
const val DISCOVERY_PORT = 47322     // البحث عن الغرف على نفس الشبكة (UDP)

val APP_UUID: UUID = UUID.fromString("8f4c6a1e-3b7d-4c52-9a1f-5d2e7b9c0a41")

/** اتصال بلوتوث (RFCOMM) — رسائل JSON، كل رسالة في سطر */
class Link(inS: InputStream, outS: OutputStream, private val closer: () -> Unit) {
    constructor(socket: BluetoothSocket) : this(socket.inputStream, socket.outputStream, { socket.close() })
    constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream(), { socket.close() })

    private val out = outS.bufferedWriter(Charsets.UTF_8)
    private val inp = inS.bufferedReader(Charsets.UTF_8)
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

    fun close() { try { closer() } catch (_: Exception) {}; q.shutdown() }
}

/** غرفة وجدناها على نفس الشبكة */
class WifiHost(val name: String, val ip: String, val port: Int, val players: Int)

class PInfo(
    val id: Int, val name: String, val n: Int, val total: Int, val laid: Boolean, val delta: Int, val hist: List<Int>,
    val wh: Int = 0, val bot: Boolean = false, val hands: Int = 0, val jokers: Int = 0, val throws: Int = 0,
    val out: Boolean = false
)

class UiState(
    val me: Int, val round: Int, val rounds: Int, val turn: Int, val drew: Boolean, val sec: Int,
    val stock: Int, val discTop: Int, val players: List<PInfo>, val hand: List<Int>,
    val melds: List<List<Int>>, val roundOver: Boolean, val gameOver: Boolean, val note: String,
    val dby: Int, val rw: Int, val hw: Boolean, val taken: Int, val gid: Int, val dn: Int = 0,
    val ps: Int = 0, val pby: Int = -1, val pans: List<Int> = emptyList(), val pm: String = "", val pms: Int = 0
)

class FxEvent(val seq: Int, val from: Int, val to: Int, val kind: String)
class Hit(val seq: Int, val kind: String)

/** الفائز = أقل نقاط، والخاسر = أعلى نقاط (والتعادل للأسوأ في آخر جولة) */
fun finalWinner(ps: List<PInfo>) =
    ps.filter { !it.out }.minWithOrNull(compareBy<PInfo>({ it.total }, { it.hist.lastOrNull() ?: 0 })) ?: ps.firstOrNull()
/** المنسحب هو الخاسر دائماً */
fun finalLoser(ps: List<PInfo>): PInfo? =
    ps.filter { it.out }.maxWithOrNull(compareBy<PInfo>({ it.total }, { -it.id }))
        ?: ps.maxWithOrNull(compareBy<PInfo>({ it.total }, { it.hist.lastOrNull() ?: 0 }))

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

    // ---- اختصارات سرية في شعار القائمة (بدون أي مؤشر أثناء الضغط) ----
    // 10 ضغطات لكل من: شيخ الكبا، بنت البستوني، قص الديناري ← مستوى 80
    // قص الديناري ×3 + شيخ الكبا ×4 + بنت البستوني ×7 ← مستوى 100 (كل الإنجازات)
    private val secret = IntArray(3)       // 0 = بنت البستوني، 1 = شيخ الكبا، 2 = قص الديناري
    private var secretTok = 0

    fun secretTap(which: Int) {
        secret[which]++
        val tok = ++secretTok
        if (secret.all { it >= 10 }) { secret.fill(0); setLevel(80); return }
        // يتوقف الضغط 1.5 ثانية وأعدادك بالضبط (7، 4، 3) فيُفعَّل المستوى 100، وبعد 10 ثوانٍ خمول يبدأ العدّ من جديد
        main.postDelayed({
            if (tok == secretTok && secret[0] == 7 && secret[1] == 4 && secret[2] == 3) { secret.fill(0); setLevel(100) }
        }, 1500)
        main.postDelayed({ if (tok == secretTok) secret.fill(0) }, 10000)
    }

    private fun setLevel(l: Int) {
        val target = Progress.cum[l] * 2
        if (winsHalf < target) {
            winsHalf = target
            onRecord?.invoke(winsHalf)
            msg = if (l == 100) "مستواك 100 — كل الإنجازات مفتوحة" else "مستواك $l"
        } else msg = "مستواك الحالي ${Progress.level(winsHalf)} أعلى"
    }
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
    val chunks = mutableStateListOf<List<Int>>()   // قطع الترتيب التلقائي (للعرض فقط، لا تُنزَّل)

    // ---- العودة للعبة السابقة بعد انقطاع الاتصال ----
    var session by mutableStateOf<String?>(null)          // آخر لعبة كنت فيها (محفوظة على الجوال)
    var onSession: ((String?) -> Unit)? = null
    private var lastConn: JSONObject? = null              // كيف اتصلت (بلوتوث أو واي فاي)
    private var myToken = ""
    private val tokens = mutableMapOf<Int, String>()      // رموز اللاعبين (عند المضيف)

    private fun saveSession(st: UiState) {
        val conn = lastConn ?: return
        if (isHost || myToken.isEmpty()) return
        if (st.roundOver && st.gameOver) { clearSession(); return }
        if (st.players.firstOrNull { it.id == st.me }?.out == true) { clearSession(); return }
        val s = JSONObject(conn.toString()).put("pid", st.me).put("tok", myToken).put("gid", st.gid).toString()
        if (s != session) { session = s; onSession?.invoke(s) }
    }

    fun clearSession() { if (session != null) { session = null; onSession?.invoke(null) } }

    private var autoTries = 0

    /** إعادة محاولة العودة تلقائياً بعد انقطاع الاتصال (حتى 5 محاولات) */
    private fun scheduleRejoin() {
        if (session == null || autoTries >= 5) return
        autoTries++
        main.postDelayed({ if (screen == "menu" && ui == null && session != null) doRejoin() }, 3000)
    }

    /** العودة للعبة السابقة: يتصل بنفس المضيف ويطلب مقعدك */
    fun rejoin() { autoTries = 0; doRejoin() }

    private fun doRejoin() {
        val s = try { JSONObject(session ?: return) } catch (_: Exception) { return }
        myToken = s.optString("tok")
        val hello = JSONObject().put("t", "rejoin").put("pid", s.optInt("pid", -1))
            .put("tok", myToken).put("gid", s.optInt("gid")).put("name", myName)
        if (s.optString("kind") == "wifi") {
            connectWifi(s.optString("ip"), s.optInt("port", WIFI_PORT), hello)
        } else {
            if (adapter?.isEnabled != true) { msg = "فعّل البلوتوث أولاً"; return }
            val d = try { adapter?.getRemoteDevice(s.optString("addr")) } catch (_: Exception) { null }
            if (d == null) { msg = "تعذّرت العودة للعبة"; return }
            connect(d, hello)
        }
    }

    /** انسحاب من اللعبة: تُحسب هزيمة، ولا عودة بعده */
    fun withdraw() {
        send(JSONObject().put("t", "withdraw"))
        if (!isHost) clearSession()
    }

    /** خروج نهائي إلى القائمة */
    fun leave() { clearSession(); reset() }

    // ---- سجل الفوز (محفوظ على الجوال) ----
    var winsHalf by mutableIntStateOf(0)                   // انتصارات × 2
    var onRecord: ((Int) -> Unit)? = null
    var lastGain by mutableStateOf<String?>(null)          // «+1 فوز، ترقّيت إلى مستوى N» في نهاية اللعبة
    val reacts = mutableStateMapOf<Int, Pair<Int, String>>() // تفاعلات سريعة طائرة فوق الأفاتار
    private val h2h = mutableMapOf<String, IntArray>()       // سجل المواجهات بين الأصدقاء (اسم الخصم → فوزي، خسارتي)
    var onH2h: ((String) -> Unit)? = null
    private var recordedGid = 0
    private val recs = mutableMapOf<Int, Int>()              // سجل اللاعبين الذين انضموا (للمضيف)

    fun loadH2h(s: String) {
        try { val o = JSONObject(s); for (k in o.keys()) { val a = o.getJSONArray(k); h2h[k] = intArrayOf(a.getInt(0), a.getInt(1)) } } catch (_: Exception) {}
    }
    fun h2hOf(name: String): IntArray = h2h[name] ?: intArrayOf(0, 0)
    private fun saveH2h() {
        val o = JSONObject(); for ((k, v) in h2h) o.put(k, JSONArray(listOf(v[0], v[1]))); onH2h?.invoke(o.toString())
    }

    /** نهاية اللعبة: فوز كامل إن كان معي لاعب حقيقي، ونصف فوز إن كان الخصوم بوتات فقط */
    private fun recordGame(st: UiState) {
        val me = st.players.firstOrNull { it.id == st.me } ?: return
        val minTotal = st.players.filter { !it.out }.minOfOrNull { it.total } ?: me.total
        val iWon = !me.out && me.total == minTotal
        val humans = st.players.count { !it.bot }
        val gain = if (iWon) (if (humans >= 2) 2 else 1) else 0
        val old = Progress.level(winsHalf)
        if (gain > 0) {
            winsHalf += gain
            val lv = Progress.level(winsHalf)
            lastGain = "+" + (if (gain == 2) "1" else "0.5") + " فوز" + (if (lv > old) " • ترقّيت إلى مستوى $lv 🎉" else "")
            onRecord?.invoke(winsHalf)
            if (isHost) engine?.players?.getOrNull(0)?.winsHalf = winsHalf
            else clientLink?.send(JSONObject().put("t", "rec").put("wh", winsHalf))
        }
        if (humans >= 2) {
            for (p in st.players) {
                if (p.bot || p.id == me.id) continue
                val won = iWon && (p.out || p.total != minTotal)
                val lost = !iWon && !p.out && p.total == minTotal
                if (won || lost) { val a = h2h.getOrPut(p.name) { IntArray(2) }; if (won) a[0]++ else a[1]++ }
            }
            saveH2h()
        }
    }

    private var engine: Engine? = null
    private val links = mutableMapOf<Int, Link>()
    private val orders = mutableMapOf<Int, List<Int>>()   // ترتيب ورق اللاعبين (للكشف السري)
    private var server: BluetoothServerSocket? = null
    private var tcpServer: ServerSocket? = null
    private var udp: DatagramSocket? = null
    var wifiIp by mutableStateOf<String?>(null)            // عنوان المضيف على الشبكة (يظهر في شاشة الانتظار)
    val wifiHosts = mutableStateListOf<WifiHost>()          // الغرف التي وجدها الضيف
    var scanning by mutableStateOf(false)
    private var clientLink: Link? = null
    private var fxSeen = 0

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun openJoin() {
        if (adapter?.isEnabled != true) { msg = "فعّل البلوتوث أولاً"; return }
        screen = "join"
    }

    // ---------------- المضيف ----------------
    /** المضيف يفتح البلوتوث والواي فاي معاً: يدخل اللاعبون من أيٍّ منهما */
    fun beginListening() {
        isHost = true; lobby.clear(); lobby.add(myName); links.clear(); orders.clear(); engine = null
        closeServers()
        startBluetoothServer()
        startWifiServer()
        if (server == null && tcpServer == null) { msg = "تعذّر بدء الاستضافة"; screen = "menu"; return }
        screen = "hostLobby"
    }

    private fun startBluetoothServer() {
        val a = adapter ?: return
        try {
            if (!a.isEnabled) return
            val ss = a.listenUsingRfcommWithServiceRecord("HandGame", APP_UUID)
            server = ss
            thread { try { while (true) onIncoming(Link(ss.accept())) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun startWifiServer() {
        wifiIp = localIp()
        try {
            val ts = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(WIFI_PORT)) }
            tcpServer = ts
            thread { try { while (true) onIncoming(Link(ts.accept())) } catch (_: Exception) {} }
        } catch (_: Exception) { return }
        // يردّ على بحث الضيوف عن الغرف (UDP broadcast)
        try {
            val s = DatagramSocket(null as SocketAddress?).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT)) }
            udp = s
            thread {
                val buf = ByteArray(256)
                try {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (String(p.data, 0, p.length) == "HANDREMMY?" && engine == null) {
                            val reply = "HANDREMMY|$myName|$WIFI_PORT|${lobby.size}".toByteArray()
                            s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun closeServers() {
        try { server?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        server = null; tcpServer = null; udp = null
    }

    /** عنوان الجهاز على شبكة الواي فاي / الهوتسبوت */
    private fun localIp(): String? = try {
        (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.map { ia -> ia.address } }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (it !in out) out.add(it) }
            }
        } catch (_: Exception) {}
        return out
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
                        recs[pid] = m.optInt("wh", 0)
                        links[pid] = link
                        tokens[pid] = UUID.randomUUID().toString().replace("-", "").take(16)
                        link.send(JSONObject().put("t", "joined").put("id", pid).put("tok", tokens[pid]))
                        sendLobby()
                    }
                } else if (m.optString("t") == "rejoin" && pid < 0) {
                    val e = engine
                    val rp = m.optInt("pid", -1)
                    val ok = e != null && rp in e.players.indices && !e.players[rp].isBot &&
                        tokens[rp] != null && tokens[rp] == m.optString("tok") && !e.players[rp].out
                    if (ok) {
                        links[rp]?.close()
                        links[rp] = link; pid = rp
                        link.send(JSONObject().put("t", "joined").put("id", rp).put("tok", tokens[rp]))
                        pushState()
                    } else {
                        val why = if (e != null && rp in e.players.indices && e.players[rp].out) "انسحبت من هذه اللعبة"
                            else "اللعبة انتهت أو لم تعد متاحة"
                        link.send(JSONObject().put("t", "norejoin").put("msg", why)); link.close()
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
        // تبقى الخوادم مفتوحة أثناء اللعب ليتمكن من انقطع اتصاله من العودة
        val humans = lobby.mapIndexed { i, n -> Player(i, n).also { it.winsHalf = if (i == 0) winsHalf else recs[i] ?: 0 } }
        val bots = List(nb) { Player(lobby.size + it, botName(it), isBot = true) }
        val e = Engine(humans + bots, rounds)
        e.startRound(); engine = e
        screen = "table"; pushState(); tick()
    }

    private val PAUSE_BLOCKED = setOf("draw", "take", "return", "meld", "add", "discard", "throw", "peek")

    private fun tick() {
        main.postDelayed({
            val e = engine ?: return@postDelayed
            if (!e.roundOver && e.pauseState != 2 && System.currentTimeMillis() > e.deadline) e.timeout()
            e.pauseTick()
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
        if (botPending || e.roundOver || e.pauseState == 2 || !e.players[e.turn].isBot) return
        botPending = true
        main.postDelayed({
            botPending = false
            val en = engine ?: return@postDelayed
            if (!en.roundOver && en.pauseState != 2 && en.players[en.turn].isBot) { en.botStep(); pushState() }
        }, 1500)
    }

    /** لعبة جديدة بنفس اللاعبين والبوتات وعدد الجولات */
    private fun restart(old: Engine) {
        val e = Engine(old.players.map { p -> Player(p.id, p.name, p.isBot).also { it.winsHalf = p.winsHalf; it.out = p.out } }, old.totalRounds)
        e.startRound(); engine = e; orders.clear()
    }

    private fun groupsOf(m: JSONObject): List<List<Int>> {
        val arr = m.getJSONArray("groups")
        return List(arr.length()) { i -> val g = arr.getJSONArray(i); List(g.length()) { j -> g.getInt(j) } }
    }

    private fun act(pid: Int, m: JSONObject) {
        val e = engine ?: return
        try {
            val t = m.optString("t")
            val err: String? = if (e.pauseState == 2 && t in PAUSE_BLOCKED) "اللعبة متوقفة مؤقتاً" else when (t) {
                "pausereq" -> e.requestPause(pid)
                "pauseans" -> e.answerPause(pid, m.optBoolean("ok"))
                "pausecancel" -> e.cancelPause(pid)
                "resume" -> e.resume(pid)
                "draw" -> e.draw(pid)
                "take" -> e.takeDiscard(pid)
                "return" -> e.returnTaken(pid)
                "meld" -> e.meld(pid, groupsOf(m))
                "add" -> e.addToMeld(pid, m.getInt("meld"), m.getInt("card"), m.optInt("end", -1))
                "discard" -> e.discardCard(pid, m.getInt("card"))
                "throw" -> e.throwAt(pid, m.getInt("to"), m.getString("kind"))
                "peek" -> { handlePeek(e, pid, m.getInt("to")); null }
                "react" -> e.react(pid, m.optString("e"))
                "rec" -> { e.players.getOrNull(pid)?.winsHalf = m.optInt("wh", 0); null }
                "order" -> { val a = m.getJSONArray("ids"); orders[pid] = List(a.length()) { a.getInt(it) }; null }
                "withdraw" -> e.withdraw(pid)
                "newgame" -> {
                    if (e.roundOver && e.gameOver) {
                        if (e.players.count { !it.out } < 2) "انسحب اللاعبون الآخرون — لا يمكن بدء لعبة جديدة"
                        else { restart(e); null }
                    } else null
                }
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
    fun connect(d: BluetoothDevice, hello: JSONObject? = null) {
        isHost = false; msg = "جارٍ الاتصال..."
        lastConn = JSONObject().put("kind", "bt").put("addr", d.address)
        thread {
            try {
                adapter?.cancelDiscovery()
                val s = d.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                onConnected(Link(s), hello)
            } catch (e: Exception) { main.post { msg = "فشل الاتصال — تأكد أن المضيف فتح الاستضافة"; if (hello != null) scheduleRejoin() } }
        }
    }

    /** الانضمام عبر الواي فاي إلى عنوان المضيف */
    fun connectWifi(ip: String, port: Int = WIFI_PORT, hello: JSONObject? = null) {
        isHost = false; msg = "جارٍ الاتصال..."
        lastConn = JSONObject().put("kind", "wifi").put("ip", ip.trim()).put("port", port)
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip.trim(), port), 4000)
                onConnected(Link(s), hello)
            } catch (e: Exception) { main.post { msg = "فشل الاتصال بالمضيف — تأكد أنكما على نفس الراوتر"; if (hello != null) scheduleRejoin() } }
        }
    }

    private fun onConnected(l: Link, hello: JSONObject? = null) {
        clientLink = l
        main.post { msg = ""; if (hello == null) screen = "clientLobby" }
        l.send(hello ?: JSONObject().put("t", "join").put("name", myName).put("wh", winsHalf))
        l.readLoop({ m -> main.post { onServerMsg(m) } }, {
            main.post {
                if (screen != "menu") {
                    msg = if (session != null) "انقطع الاتصال — جارٍ محاولة العودة للعبة..." else "انقطع الاتصال"
                    reset()
                    scheduleRejoin()
                }
            }
        })
    }

    fun openWifiJoin() { screen = "joinWifi"; scanWifi() }

    /** يبحث 5 ثوانٍ عن غرف على نفس الشبكة */
    fun scanWifi() {
        if (scanning) return
        scanning = true; wifiHosts.clear()
        thread {
            try {
                val s = DatagramSocket().apply { broadcast = true; soTimeout = 600 }
                val req = "HANDREMMY?".toByteArray()
                val targets = broadcastTargets()
                val end = System.currentTimeMillis() + 5000
                var lastSend = 0L
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 1000) {
                        for (t in targets) try { s.send(DatagramPacket(req, req.size, t, DISCOVERY_PORT)) } catch (_: Exception) {}
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val f = String(p.data, 0, p.length).split("|")
                        if (f.size >= 4 && f[0] == "HANDREMMY") {
                            val h = WifiHost(f[1], p.address.hostAddress ?: "", f[2].toIntOrNull() ?: WIFI_PORT, f[3].toIntOrNull() ?: 1)
                            main.post { if (wifiHosts.none { it.ip == h.ip }) wifiHosts.add(h) }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
                s.close()
            } catch (_: Exception) {}
            main.post { scanning = false }
        }
    }

    private fun onServerMsg(m: JSONObject) {
        when (m.optString("t")) {
            "lobby" -> { lobby.clear(); val a = m.getJSONArray("names"); for (i in 0 until a.length()) lobby.add(a.getString(i)) }
            "joined" -> { myToken = m.optString("tok") }
            "norejoin" -> { clearSession(); msg = m.optString("msg", "تعذّرت العودة للعبة"); reset() }
            "state" -> {
                if (ui == null) m.optJSONObject("fx")?.let { fxSeen = it.optInt("seq", 0) }   // لا نعيد مؤثرات قديمة بعد العودة
                screen = "table"; autoTries = 0; applyView(m); ui?.let { saveSession(it) }
            }
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
            val sa = o.optJSONArray("st")
            PInfo(
                o.getInt("id"), o.getString("name"), o.getInt("n"), o.getInt("total"), o.getBoolean("laid"), o.getInt("delta"),
                List(h.length()) { k -> h.getInt(k) }, o.optInt("wh", 0), o.optBoolean("bot", false),
                sa?.optInt(0) ?: 0, sa?.optInt(1) ?: 0, sa?.optInt(2) ?: 0, o.optBoolean("out", false)
            )
        }
        val hand = ints(v.getJSONArray("hand"))
        val prev = ui
        val ma = v.getJSONArray("melds")
        val st = UiState(
            v.getInt("me"), v.getInt("round"), v.getInt("rounds"), v.getInt("turn"), v.getBoolean("drew"),
            v.getInt("sec"), v.getInt("stock"), v.getInt("discTop"), ps, hand,
            List(ma.length()) { ints(ma.getJSONArray(it)) },
            v.getBoolean("roundOver"), v.getBoolean("gameOver"), v.getString("note"),
            v.getInt("dby"), v.getInt("rw"), v.getBoolean("hw"), v.getInt("taken"), v.getInt("gid"), v.optInt("dn", 0),
            v.optInt("ps", 0), v.optInt("pby", -1), ints(v.optJSONArray("pans") ?: JSONArray()),
            v.optString("pm", ""), v.optInt("pms", 0)
        )
        ui = st
        if (prev != null && st.pm.isNotEmpty() && st.pms != prev.pms) msg = st.pm      // «فلان رفض الإيقاف»
        if (prev != null && prev.gid != st.gid) lastGain = null
        // صوت سحب الورقة (من الكومة أو من المرمية) وصوت رمي الورقة على الكومة
        if (prev != null && prev.round == st.round && prev.gid == st.gid) {
            if (st.stock < prev.stock || st.dn < prev.dn) Sfx.cardDraw()
            else if (st.dn > prev.dn) Sfx.cardThrow()
        }
        if (st.gameOver && st.roundOver && st.gid != recordedGid) { recordedGid = st.gid; recordGame(st) }
        val sameRound = prev != null && prev.round == st.round && prev.gid == st.gid
        if (!sameRound) { order.clear(); fresh.clear(); groups.clear(); chunks.clear(); arrangeMode = 0 }
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
        if (kind == "voice") { Sfx.say(f.optString("data")); return }
        if (kind == "react") {
            reacts[from] = Pair(seq, f.optString("data"))
            main.postDelayed({ if (reacts[from]?.first == seq) reacts.remove(from) }, 2200)
            return
        }
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
        if (from != to && from in order.indices && to in order.indices) {
            val id = order[from]
            val ci = chunks.indexOfFirst { id in it }
            if (ci >= 0) chunks[ci] = chunks[ci] - id
            order.add(to, order.removeAt(from)); pushOrder()
        }
    }

    /** نقل مجموعة مكوّنة كاملة كأنها ورقة واحدة: anchor هي الورقة التي سُحبت، وtarget مكانها الجديد */
    fun moveGroup(ids: List<Int>, anchor: Int, target: Int) {
        val inOrder = order.filter { it in ids }
        if (inOrder.size < 2) return
        val k = inOrder.indexOf(anchor).coerceAtLeast(0)
        val rest = order.filter { it !in ids }
        val start = (target - k).coerceIn(0, rest.size)
        val newOrder = rest.toMutableList().also { it.addAll(start, inOrder) }
        if (newOrder != order.toList()) { order.clear(); order.addAll(newOrder); pushOrder() }
    }

    private var arrangeMode = 0

    /**
     * ترتيب: كل ضغطة تعطي ترتيباً مختلفاً (حسب المجموعات، ثم النوع، ثم الرقم) والأوراق متجاورة بلا فراغات.
     * المجموعات المكوّنة تبقى كما هي في أول اليد.
     */
    fun autoArrange() {
        if (order.size < 2) return
        val inHand = order.toList()
        val kept = groups.map { g -> inHand.filter { it in g } }.filter { it.isNotEmpty() }
        val keptIds = kept.flatten().toSet()
        val free = inHand.filter { it !in keptIds }
        chunks.clear()
        for (attempt in 0 until Arranger.MODES) {
            val mode = arrangeMode
            arrangeMode = (arrangeMode + 1) % Arranger.MODES
            val newOrder = kept.flatten() + Arranger.byMode(free, mode)
            if (newOrder.size != inHand.size || newOrder.toSet() != inHand.toSet()) return
            if (newOrder == inHand && attempt < Arranger.MODES - 1) continue     // نفس الترتيب الحالي: جرّب التالي
            order.clear(); order.addAll(newOrder)
            msg = "ترتيب " + Arranger.MODE_NAMES[mode]
            pushOrder()
            return
        }
    }

    /** تكوين: يجمع الأوراق المحددة في مجموعة داخل اليد بدون إنزالها */
    fun formGroup(ids: List<Int>) {
        if (ids.size < 2) return
        chunks.removeAll { ch -> ch.any { it in ids } }
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
        engine = null; ui = null; order.clear(); lobby.clear(); groups.clear(); fresh.clear(); chunks.clear()
        fxEvents.clear(); hits.clear(); laughs.clear(); reacts.clear(); centers.clear(); peek = null; fxSeen = 0; orders.clear()
        closeServers(); wifiHosts.clear(); scanning = false; wifiIp = null
        links.values.forEach { it.close() }; links.clear()
        clientLink?.close(); clientLink = null
        botPending = false
        screen = "menu"
    }
}
