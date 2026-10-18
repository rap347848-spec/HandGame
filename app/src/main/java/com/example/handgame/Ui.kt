package com.example.handgame

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val Gold = Color(0xFFFFD54F)
private val Purple1 = Color(0xFF3B1478)
private val Purple2 = Color(0xFF12062E)
private val Felt1 = Color(0xFF2FA34F)
private val Felt2 = Color(0xFF145C2A)
private val Good = Color(0xFF69F0AE)
private val GroupColors = listOf(
    Color(0xFFFF7043), Color(0xFFEC407A), Color(0xFF9CCC65),
    Color(0xFF5C6BC0), Color(0xFFAB47BC), Color(0xFF8D6E63)
)
const val CREDIT = "تصميم طلال قاسم"

enum class Mood { HAPPY, SCARED, SAD, LAUGH }

/** الأول (أقل نقاط) مبتسم، الأخير (أعلى نقاط) أحمر وحزين، والبقية خائفون */
fun moodOf(ps: List<PInfo>, id: Int): Mood {
    val mn = ps.minOf { it.total }
    val mx = ps.maxOf { it.total }
    val me = ps.first { it.id == id }.total
    return if (mn == mx || me == mn) Mood.HAPPY else if (me == mx) Mood.SAD else Mood.SCARED
}

fun moodFor(c: Controller, ps: List<PInfo>, id: Int): Mood =
    if (c.laughs.containsKey(id)) Mood.LAUGH else moodOf(ps, id)

@Composable
fun App(c: Controller, onHost: () -> Unit, onJoin: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, onPrimary = Color.Black)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Purple1, Purple2)))) {
                when (c.screen) {
                    "menu" -> Menu(c, onHost, onJoin)
                    "hostLobby", "clientLobby" -> Lobby(c)
                    "join" -> JoinList(c)
                    "joinWifi" -> JoinWifi(c)
                    "table" -> Table(c)
                }
                FxLayer(c)
                if (c.msg.isNotEmpty()) {
                    LaunchedEffect(c.msg) { delay(3500); c.msg = "" }
                    Text(
                        c.msg, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).zIndex(90f)
                            .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                            .border(1.dp, Gold, RoundedCornerShape(12.dp))
                            .clickable { c.msg = "" }.padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- عناصر عامة

@Composable
fun Pill(text: String, color: Color, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(Brush.verticalGradient(listOf(color, lerp(color, Color.Black, 0.4f))))
            .border(1.dp, Color(0x88FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (big) 24.dp else 12.dp, vertical = if (big) 9.dp else 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 13.sp, maxLines = 1) }
}

@Composable
fun Avatar(seed: Int, mood: Mood, sz: Dp, hit: String? = null, nose: Boolean = false) {
    val skins = listOf(Color(0xFFF1C27D), Color(0xFFC68642), Color(0xFFE0AC69), Color(0xFF8D5524))
    val hairs = listOf(Color(0xFF3B1E08), Color(0xFF111111), Color(0xFF7A2E0E), Color(0xFF2B2B2B))
    val skin = skins[seed % 4]
    val hair = hairs[(seed + 1) % 4]
    val face = when (mood) {
        Mood.SAD -> lerp(skin, Color(0xFFE53935), 0.65f)
        Mood.SCARED -> lerp(skin, Color.White, 0.4f)
        else -> skin
    }
    val drip by rememberInfiniteTransition(label = "nose").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "drip"
    )
    Canvas(Modifier.size(sz).clip(CircleShape)) {
        val w = this.size.width
        drawCircle(Brush.linearGradient(listOf(Color(0xFF26C6DA), Color(0xFF5E35B1))), radius = w / 2)
        val cx = w / 2
        val cy = w * 0.54f
        val r = w * 0.34f
        drawArc(Color(0xFF455A64), 180f, 180f, true, Offset(w * 0.1f, w * 0.8f), Size(w * 0.8f, w * 0.5f))
        drawCircle(face, r * 0.16f, Offset(cx - r * 0.98f, cy))
        drawCircle(face, r * 0.16f, Offset(cx + r * 0.98f, cy))
        drawCircle(face, r, Offset(cx, cy))
        drawArc(hair, 180f, 180f, true, Offset(cx - r * 1.04f, cy - r * 1.12f), Size(r * 2.08f, r * 1.5f))
        val ey = cy - r * 0.08f
        val ex = r * 0.42f
        val stroke = Stroke(w * 0.028f, cap = StrokeCap.Round)
        when (mood) {
            Mood.HAPPY -> {
                for (sg in listOf(-1f, 1f))
                    drawArc(Color.Black, 200f, 140f, false, Offset(cx + sg * ex - r * 0.2f, ey - r * 0.1f), Size(r * 0.4f, r * 0.3f), style = stroke)
                drawArc(Color(0xFF5D1010), 0f, 180f, true, Offset(cx - r * 0.38f, cy + r * 0.28f), Size(r * 0.76f, r * 0.55f))
            }
            Mood.LAUGH -> {   // ضحكة خبيثة: حواجب مائلة وفم واسع
                for (sg in listOf(-1f, 1f)) {
                    drawArc(Color.Black, 200f, 140f, false, Offset(cx + sg * ex - r * 0.2f, ey - r * 0.02f), Size(r * 0.4f, r * 0.26f), style = stroke)
                    drawLine(Color.Black, Offset(cx + sg * (ex + r * 0.24f), ey - r * 0.38f), Offset(cx + sg * (ex - r * 0.2f), ey - r * 0.2f), w * 0.03f, StrokeCap.Round)
                }
                drawArc(Color(0xFF5D1010), 0f, 180f, true, Offset(cx - r * 0.5f, cy + r * 0.22f), Size(r * 1.0f, r * 0.72f))
                drawArc(Color.White, 0f, 180f, true, Offset(cx - r * 0.42f, cy + r * 0.24f), Size(r * 0.84f, r * 0.2f))
            }
            Mood.SCARED -> {
                for (sg in listOf(-1f, 1f)) {
                    drawCircle(Color.White, r * 0.2f, Offset(cx + sg * ex, ey))
                    drawCircle(Color.Black, r * 0.08f, Offset(cx + sg * ex, ey))
                    drawLine(Color.Black, Offset(cx + sg * (ex + r * 0.22f), ey - r * 0.22f), Offset(cx + sg * (ex - r * 0.22f), ey - r * 0.4f), w * 0.026f, StrokeCap.Round)
                }
                drawOval(Color(0xFF3E0F0F), Offset(cx - r * 0.14f, cy + r * 0.38f), Size(r * 0.28f, r * 0.4f))
                drawOval(Color(0xFF4FC3F7), Offset(cx + r * 0.78f, cy - r * 0.6f), Size(r * 0.14f, r * 0.24f))
            }
            Mood.SAD -> {
                for (sg in listOf(-1f, 1f)) {
                    drawCircle(Color.Black, r * 0.07f, Offset(cx + sg * ex, ey))
                    drawLine(Color.Black, Offset(cx + sg * (ex + r * 0.22f), ey - r * 0.2f), Offset(cx + sg * (ex - r * 0.2f), ey - r * 0.4f), w * 0.026f, StrokeCap.Round)
                }
                drawOval(Color(0xFF4FC3F7), Offset(cx - ex - r * 0.07f, ey + r * 0.14f), Size(r * 0.14f, r * 0.26f))
                drawArc(Color(0xFF5D1010), 180f, 180f, false, Offset(cx - r * 0.3f, cy + r * 0.45f), Size(r * 0.6f, r * 0.4f), style = stroke)
            }
        }
        if (nose) {   // سائل ينزل من الأنف
            val len = r * (0.25f + 0.55f * drip)
            drawLine(Color(0xFF81D4FA), Offset(cx - r * 0.1f, cy + r * 0.22f), Offset(cx - r * 0.1f, cy + r * 0.22f + len), w * 0.04f, StrokeCap.Round)
            drawCircle(Color(0xFF81D4FA), w * 0.03f, Offset(cx - r * 0.1f, cy + r * 0.24f + len))
        }
        if (hit == "egg") {
            drawCircle(Color(0xEEFFFDE7), r * 0.34f, Offset(cx - r * 0.2f, cy - r * 0.05f))
            drawCircle(Color(0xEEFFFDE7), r * 0.2f, Offset(cx + r * 0.2f, cy + r * 0.25f))
            drawCircle(Color(0xFFFFC107), r * 0.17f, Offset(cx - r * 0.2f, cy - r * 0.05f))
            drawCircle(Color(0xFFFFB300), r * 0.08f, Offset(cx + r * 0.38f, cy - r * 0.3f))
        }
        if (hit == "slipper") {
            drawOval(Color(0xAAD50000), Offset(cx - r * 0.8f, cy - r * 0.25f), Size(r * 0.95f, r * 1.05f))
            for (k in 0..2) drawLine(
                Color(0xCCB71C1C), Offset(cx - r * 0.62f + k * r * 0.22f, cy - r * 0.12f),
                Offset(cx - r * 0.56f + k * r * 0.22f, cy + r * 0.6f), w * 0.03f, StrokeCap.Round
            )
        }
    }
}

@Composable
fun Projectile(kind: String, sz: Dp) {
    Canvas(Modifier.size(sz)) {
        val w = this.size.width
        if (kind == "egg") {
            drawOval(Color(0xFFFFF8E1), Offset(w * 0.2f, w * 0.08f), Size(w * 0.6f, w * 0.82f))
            drawOval(Color(0x22000000), Offset(w * 0.5f, w * 0.3f), Size(w * 0.26f, w * 0.5f))
        } else {
            drawOval(Color(0xFFFF8F00), Offset(w * 0.3f, w * 0.05f), Size(w * 0.4f, w * 0.9f))
            drawOval(Color(0xFFFFB74D), Offset(w * 0.36f, w * 0.12f), Size(w * 0.28f, w * 0.55f))
            drawArc(Color(0xFF4E342E), 200f, 140f, false, Offset(w * 0.28f, w * 0.28f), Size(w * 0.44f, w * 0.4f), style = Stroke(w * 0.1f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun CardView(id: Int, w: Dp, h: Dp, alpha: Float = 1f, borderColor: Color? = null) {
    val joker = Cards.isJoker(id)
    val col = when { joker -> Color(0xFF6A1B9A); Cards.suit(id) in 1..2 -> Color(0xFFD32F2F); else -> Color(0xFF111111) }
    val shape = RoundedCornerShape(w * 0.12f)
    Box(
        Modifier.size(w, h).alpha(alpha).shadow(2.dp, shape)
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFEAEAEA))), shape)
            .border(if (borderColor != null) 3.dp else 1.dp, borderColor ?: Color(0xFFB0B0B0), shape)
    ) {
        if (joker) {
            Text("★", Modifier.align(Alignment.Center), color = col, fontSize = (h.value * 0.45f).sp)
            Text("JOKER", Modifier.align(Alignment.TopCenter).padding(top = 2.dp), color = col, fontSize = (w.value * 0.17f).sp, fontWeight = FontWeight.Bold)
        } else {
            Column(Modifier.padding(start = w * 0.1f, top = h * 0.03f)) {
                Text(Cards.rankLabel(id), color = col, fontWeight = FontWeight.Bold, fontSize = (h.value * 0.28f).sp, lineHeight = (h.value * 0.28f).sp)
                Text(Cards.suitSymbol(id), color = col, fontSize = (h.value * 0.2f).sp, lineHeight = (h.value * 0.2f).sp)
            }
            Text(Cards.suitSymbol(id), Modifier.align(Alignment.BottomEnd).padding(end = w * 0.1f, bottom = h * 0.04f), color = col, fontSize = (h.value * 0.34f).sp)
        }
    }
}

@Composable
fun Logo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(86.dp).width(120.dp)) {
            listOf(11 to -14f, 25 to 0f, 26 to 14f).forEachIndexed { i, (id, rot) ->
                Box(Modifier.align(Alignment.Center).offset(x = ((i - 1) * 26).dp).graphicsLayer { rotationZ = rot }) { CardView(id, 52.dp, 74.dp) }
            }
        }
        Text("هاند", fontSize = 58.sp, fontWeight = FontWeight.Black, color = Gold)
        Text(CREDIT, fontSize = 16.sp, color = Color(0xCCFFFFFF), fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- القوائم

@Composable
fun Menu(c: Controller, onHost: () -> Unit, onJoin: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Logo() }
        Column(
            Modifier.weight(1.4f).background(Color(0x33FFFFFF), RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(c.myName, { c.rename(it) }, label = { Text("اسمك") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("عدد الجولات (يحدده المضيف)", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 1..7) FilterChip(selected = c.rounds == r, onClick = { c.rounds = r }, label = { Text("$r") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Pill("استضافة لعبة", Color(0xFF2E7D32), onClick = onHost) }
                Box(Modifier.weight(1f)) { Pill("انضمام بلوتوث", Color(0xFF1565C0), onClick = onJoin) }
            }
            Pill("انضمام عبر الواي فاي (نفس الراوتر)", Color(0xFF00897B)) { c.openWifiJoin() }
        }
    }
}

@Composable
fun Lobby(c: Controller) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("اللاعبون (${c.lobby.size}/4)", fontSize = 20.sp, color = Gold, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            c.lobby.forEachIndexed { i, n ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(i, Mood.HAPPY, 46.dp)
                    Text(n, color = Color.White, fontSize = 13.sp)
                }
            }
            if (c.isHost) repeat(c.effectiveBots()) { b ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(c.lobby.size + b, Mood.SCARED, 46.dp)
                    Text(c.botName(b), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        if (c.isHost) {
            Text("بلوتوث: يختار الآخرون جوالك من «انضمام بلوتوث» بعد الاقتران", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(
                if (c.wifiIp != null) "واي فاي: يختار الآخرون غرفتك من «انضمام عبر الواي فاي»، أو يكتبون العنوان ${c.wifiIp}"
                else "واي فاي: اتصل بالراوتر أو شغّل الهوتسبوت ليلعب الآخرون عبره",
                color = Color(0xFF80CBC4), fontSize = 12.sp, textAlign = TextAlign.Center
            )
            Text("عدد البوتات", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in 0..3) FilterChip(
                    selected = c.effectiveBots() == b, enabled = b <= 4 - c.lobby.size,
                    onClick = { c.botCount = b }, label = { Text(if (b == 0) "بدون" else "$b") }
                )
            }
            Pill("ابدأ اللعبة (${c.rounds} جولات)", Color(0xFF2E7D32), enabled = c.lobby.size + c.effectiveBots() >= 2) { c.startGame() }
        } else Text("بانتظار المضيف ليبدأ اللعبة...", color = Color.White)
        Pill("خروج", Color(0xFF6D4C41)) { c.reset() }
    }
}

@Composable
fun JoinWifi(c: Controller) {
    var ip by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الانضمام عبر الواي فاي", fontSize = 22.sp, color = Gold, fontWeight = FontWeight.Bold)
        Text(
            when {
                c.wifiHosts.isNotEmpty() -> "اختر الغرفة:"
                c.scanning -> "جارٍ البحث عن غرف على نفس الراوتر..."
                else -> "لم تظهر أي غرفة. تأكد أنكما على نفس الراوتر، أو اكتب عنوان المضيف."
            }, color = Color.White
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(c.wifiHosts) { _, h -> Pill("${h.name} (${h.players}/4)", Color(0xFF00897B)) { c.connectWifi(h.ip, h.port) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ip, { ip = it }, label = { Text("عنوان المضيف مثل 192.168.1.5") }, singleLine = true, modifier = Modifier.weight(1f))
            Pill("اتصال", Color(0xFF2E7D32), enabled = ip.isNotBlank()) { c.connectWifi(ip) }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("بحث من جديد", Color(0xFF1565C0), enabled = !c.scanning) { c.scanWifi() }
            Pill("رجوع", Color(0xFF6D4C41)) { c.reset() }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
@Composable
fun JoinList(c: Controller) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("اختر جوال المضيف", fontSize = 22.sp, color = Gold, fontWeight = FontWeight.Bold)
        Text("لازم تكون مقترناً (Pair) مع جوال المضيف من إعدادات البلوتوث أولاً.", color = Color.White)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(c.bonded()) { _, d -> Pill(d.name ?: d.address, Color(0xFF1565C0)) { c.connect(d) } }
        }
        Spacer(Modifier.height(4.dp))
        Pill("رجوع", Color(0xFF6D4C41)) { c.reset() }
    }
}

// ---------------------------------------------------------------- المؤثرات (بيض / شبشب)

/** البيض أو الشبشب يطير من أفاتار الراميّ إلى أفاتار الهدف */
@Composable
fun FxLayer(c: Controller) {
    Box(Modifier.fillMaxSize().zIndex(80f)) {
        c.fxEvents.forEach { ev -> key(ev.seq) { Flyer(ev, c.centers) } }
    }
}

@Composable
fun Flyer(ev: FxEvent, centers: Map<Int, Offset>) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(600, easing = LinearEasing)) }
    val a = centers[ev.from] ?: Offset(200f, 600f)
    val b = centers[ev.to] ?: Offset(800f, 300f)
    val sizeDp = 34.dp
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    val t = p.value
    val x = a.x + (b.x - a.x) * t
    val y = a.y + (b.y - a.y) * t - sin(PI * t).toFloat() * 110f
    Box(
        Modifier.offset { IntOffset((x - px / 2).roundToInt(), (y - px / 2).roundToInt()) }
            .graphicsLayer { rotationZ = t * 720f }
    ) { Projectile(ev.kind, sizeDp) }
}

// ---------------------------------------------------------------- الطاولة

@Composable
fun Seat(
    p: PInfo, mood: Mood, turn: Boolean, sec: Int, hit: String?, picked: Boolean,
    onCenter: (Offset) -> Unit, onTap: () -> Unit, onSecret: () -> Unit, sz: Dp = 54.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.onGloballyPositioned { onCenter(it.boundsInRoot().center) }
                .pointerInput(p.id) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val t0 = System.currentTimeMillis()
                        val up = waitForUpOrCancellation()
                        val dt = System.currentTimeMillis() - t0
                        if (up != null) {
                            if (dt < 450) onTap()
                            else if (dt in 15000..18000) onSecret()   // الكشف السري
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (turn) CircularProgressIndicator(
                progress = { sec / 60f }, modifier = Modifier.size(sz + 10.dp),
                color = if (sec <= 10) Color.Red else Gold, trackColor = Color(0x33FFFFFF), strokeWidth = 3.dp
            )
            Box(Modifier.then(if (picked) Modifier.border(3.dp, Color(0xFFFF5252), CircleShape) else Modifier)) {
                Avatar(p.id, mood, sz, hit)
            }
            Box(Modifier.align(Alignment.BottomEnd).background(Color(0xFFE53935), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text("${p.n}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            p.name + if (turn) " · $sec" else "", color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp)
        )
    }
}

@Composable
fun ScoreBoard(s: UiState, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    // القائمة تُسحب بالإصبع وتُنقل لأي مكان على الشاشة، والضغطة تكبّرها وتصغّرها
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val cfg = LocalConfiguration.current
    val den = LocalDensity.current
    val pad = with(den) { 6.dp.toPx() }
    val maxLeft = with(den) { cfg.screenWidthDp.dp.toPx() } - sz.width - pad * 2     // أقصى حركة لليسار
    val maxDown = with(den) { cfg.screenHeightDp.dp.toPx() } - sz.height - pad * 2   // أقصى حركة للأسفل
    LaunchedEffect(sz) {                                                              // بعد التكبير/التصغير ابقَ داخل الشاشة
        dx = dx.coerceIn(-maxOf(0f, maxLeft), 0f)
        dy = dy.coerceIn(0f, maxOf(0f, maxDown))
    }
    Column(
        modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }.onSizeChanged { sz = it }
            .pointerInput(Unit) {
                detectDragGestures { ch, d ->
                    ch.consume()
                    dx = (dx + d.x).coerceIn(-maxOf(0f, maxLeft), 0f)
                    dy = (dy + d.y).coerceIn(0f, maxOf(0f, maxDown))
                }
            }
            .animateContentSize().background(Color(0xDD1A0B3B), RoundedCornerShape(12.dp))
            .border(1.dp, Gold, RoundedCornerShape(12.dp)).clickable { open = !open }.padding(7.dp)
    ) {
        if (!open) {
            Text("النتيجة", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            s.players.forEach {
                Row(Modifier.widthIn(min = 84.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(it.name.take(7), color = Color.White, fontSize = 11.sp)
                    Text("${it.total}", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Text("جولة ${s.round} من ${s.rounds}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row {
                Text("", Modifier.width(34.dp))
                s.players.forEach { Text(it.name.take(6), Modifier.width(52.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            }
            for (r in 0 until s.rounds) {
                Row {
                    Text("ج${r + 1}", Modifier.width(34.dp), color = Color(0xAAFFFFFF), fontSize = 11.sp)
                    s.players.forEach {
                        Text(it.hist.getOrNull(r)?.toString() ?: "-", Modifier.width(52.dp), color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Row {
                Text("المجموع", Modifier.width(34.dp), color = Gold, fontSize = 8.sp)
                s.players.forEach { Text("${it.total}", Modifier.width(52.dp), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
fun DiscardPile(top: Int, thrownByMe: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(52.dp, 74.dp).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(7.dp)).clickable(onClick = onClick)) {
        if (top >= 0) {
            val fly = remember(top) { Animatable(1f) }
            LaunchedEffect(top) { fly.snapTo(1f); fly.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) }
            val dist = with(LocalDensity.current) { 170.dp.toPx() }
            Box(Modifier.graphicsLayer {
                translationY = fly.value * dist * (if (thrownByMe) 1f else -1f)
                rotationZ = fly.value * 35f
                alpha = 1f - fly.value * 0.5f
            }) { CardView(top, 52.dp, 74.dp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Table(c: Controller) {
    val s = c.ui ?: return
    val n = s.players.size
    val myTurn = s.turn == s.me
    val meP = s.players.first { it.id == s.me }
    val selected = remember { mutableStateListOf<Int>() }
    val staged = remember { mutableStateListOf<List<Int>>() }
    var twoRows by remember { mutableStateOf(true) }
    var throwTo by remember { mutableStateOf<Int?>(null) }
    val meldRects = remember { mutableMapOf<Int, Rect>() }
    val feltRect = remember { arrayOfNulls<Rect>(1) }
    LaunchedEffect(s.hand) {
        selected.removeAll { it !in s.hand }
        staged.removeAll { g -> !s.hand.containsAll(g) }
    }
    val stagedIds = staged.flatten().toSet()
    val stagedPts = staged.sumOf { Melds.value(it).coerceAtLeast(0) }

    // المجموعات المكوّنة السليمة تُحسب وتنزل تلقائياً مثل «+ مجموعة» (تُفك بزر «فك التكوين»)
    val formedValid = c.groups.filter { g -> s.hand.containsAll(g) && Melds.value(g) >= 0 && g.none { it in stagedIds } }
    val formedIds = formedValid.flatten().toSet()
    val formedPts = formedValid.sumOf { Melds.value(it) }

    // باقي المحدد (غير المكوَّن السليم): كل مجموعة مكوّنة كاملة نزولٌ مستقل، والباقي نزول واحد
    val selSet = selected.toSet()
    val selFree = selected.filter { it !in formedIds }
    val chosen = selFree.toSet()
    val parts: List<List<Int>> = run {
        val full = c.groups.filter { chosen.containsAll(it) }
        val used = full.flatten().toSet()
        val rest = selFree.filter { it !in used }
        full + (if (rest.isNotEmpty()) listOf(rest) else emptyList())
    }
    val partsValid = parts.isNotEmpty() && parts.all { Melds.value(it) >= 0 }
    val selOk = selFree.isEmpty() || partsValid
    val selPts = if (partsValid) parts.sumOf { Melds.value(it) } else 0
    val rawSel = selFree.sumOf { Cards.points(it) }
    val total = stagedPts + formedPts + selPts
    val shown = stagedPts + formedPts + if (partsValid) selPts else rawSel
    val handSet = stagedIds + formedIds + (if (partsValid) chosen else emptySet())
    val canHand = myTurn && !meP.laid && total >= 51 && selOk && handSet.isNotEmpty() && s.hand.size - handSet.size <= 1
    val canLay = myTurn && selOk && (if (!meP.laid) total >= 51 else (staged.isNotEmpty() || formedValid.isNotEmpty() || partsValid))

    fun sendMeld() {
        // ترتيب كل مجموعة كما رتّبتها في يدك (يسار إلى يمين): موقع الجوكر يحدد بديله
        val g = (staged.toList() + formedValid + (if (partsValid) parts else emptyList()))
            .map { grp -> grp.sortedBy { c.order.indexOf(it) } }
        c.send(JSONObject().put("t", "meld").put("groups", JSONArray(g.map { JSONArray(it) })))
        staged.clear(); selected.clear()
    }
    fun discard(id: Int) {
        if (!myTurn) c.msg = "ليس دورك" else if (!s.drew) c.msg = "اسحب ورقة أولاً"
        else { c.send(JSONObject().put("t", "discard").put("card", id)); selected.remove(id); staged.clear() }
    }
    fun addTo(idx: Int, id: Int) {
        if (!myTurn) c.msg = "ليس دورك"
        else { c.send(JSONObject().put("t", "add").put("meld", idx).put("card", id)); selected.remove(id) }
    }
    fun takeTop() {
        when {
            !myTurn -> c.msg = "ليس دورك"
            s.drew -> c.msg = "سحبت ورقة بالفعل"
            s.discTop < 0 -> c.msg = "لا توجد ورقة مرمية"
            else -> c.send(JSONObject().put("t", "take"))
        }
    }
    /** إفلات ورقة من اليد: على مجموعة = وضعها، وعلى الطاولة = رميها. ترجع true إذا عولجت */
    fun dropCard(id: Int, p: Offset): Boolean {
        val mi = meldRects.entries.firstOrNull { it.key in s.melds.indices && it.value.contains(p) }?.key
        if (mi != null) { addTo(mi, id); return true }
        val fr = feltRect[0]
        if (fr != null && fr.contains(p)) { discard(id); return true }
        return false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // ------- منطقة الطاولة
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val feltShape = RoundedCornerShape(38.dp)
                Box(
                    Modifier.align(Alignment.Center).fillMaxHeight().fillMaxWidth(if (n == 3) 0.74f else 0.9f).clip(feltShape)
                        .background(Brush.radialGradient(listOf(Felt1, Felt2)))
                        .border(3.dp, Brush.linearGradient(listOf(Color(0xFFF5F5F5), Color(0xFF9E9E9E))), feltShape)
                        .onGloballyPositioned { feltRect[0] = it.boundsInRoot() }
                )
                val opp = (1 until n).map { s.players[(s.me + it) % n] }
                val spots = when (n) {
                    2 -> listOf(Alignment.TopCenter)
                    3 -> listOf(Alignment.CenterStart, Alignment.CenterEnd)
                    else -> listOf(Alignment.CenterStart, Alignment.TopCenter, Alignment.CenterEnd)
                }
                opp.forEachIndexed { i, p ->
                    Box(Modifier.align(spots[i]).padding(top = if (spots[i] == Alignment.TopCenter) 4.dp else 0.dp)) {
                        Seat(
                            p, moodFor(c, s.players, p.id), p.id == s.turn, s.sec, c.hits[p.id]?.kind, throwTo == p.id,
                            onCenter = { c.centers[p.id] = it },
                            onTap = { throwTo = if (throwTo == p.id) null else p.id },
                            onSecret = { c.send(JSONObject().put("t", "peek").put("to", p.id)) }
                        )
                    }
                }
                // الوسط: الكومة + الرمي + المجموعات
                Row(
                    Modifier.align(Alignment.Center).fillMaxWidth(if (n == 3) 0.6f else 0.78f)
                        .padding(top = if (n == 3) 0.dp else 62.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.size(52.dp, 74.dp).shadow(3.dp, RoundedCornerShape(7.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFFD32F2F), Color(0xFF7F0000))), RoundedCornerShape(7.dp))
                                    .border(2.dp, Color.White, RoundedCornerShape(7.dp))
                                    .clickable {
                                        if (!myTurn) c.msg = "ليس دورك" else if (s.drew) c.msg = "سحبت ورقة بالفعل"
                                        else c.send(JSONObject().put("t", "draw"))
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text("${s.stock}", color = Color.White, fontWeight = FontWeight.Bold) }
                            DiscardPile(s.discTop, s.dby == s.me) { takeTop() }
                        }
                        Text(
                            when {
                                !myTurn -> "دور ${s.players.firstOrNull { it.id == s.turn }?.name ?: ""}"
                                s.taken >= 0 -> "استخدمها أو أرجعها"
                                !s.drew -> "دورك: اسحب"
                                else -> "دورك: ارمِ ورقة"
                            },
                            color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    FlowRow(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        s.melds.forEachIndexed { idx, g ->
                            Row(
                                Modifier.onGloballyPositioned { meldRects[idx] = it.boundsInRoot() }
                                    .background(Color(0x33000000), RoundedCornerShape(6.dp)).padding(3.dp).clickable {
                                        if (myTurn && selected.size == 1) addTo(idx, selected[0])
                                        else if (myTurn) c.msg = "حدد ورقة واحدة ثم اضغط على المجموعة (أو اسحبها إليها)"
                                    },
                                horizontalArrangement = Arrangement.spacedBy((-12).dp)
                            ) { g.forEach { CardView(it, 28.dp, 40.dp) } }
                        }
                    }
                }
                // كشف سري لمدة 5 ثوانٍ (لا يراه غيرك)
                c.peek?.let { (pid, cards) ->
                    Column(
                        Modifier.align(Alignment.TopCenter).zIndex(20f).background(Color(0xE6000000), RoundedCornerShape(10.dp)).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ورق ${s.players.firstOrNull { it.id == pid }?.name ?: ""}", color = Gold, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy((-16).dp)) { cards.forEach { CardView(it, 30.dp, 44.dp) } }
                    }
                }
            }

            // ------- شريط الأزرار
            Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
              // يمين الشريط ثابت دائماً (أزرار اللعب + سطر/سطرين)، ويسار يتمرّر عند ضيق الشاشة
              Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                    Modifier.onGloballyPositioned { c.centers[s.me] = it.boundsInRoot().center },
                    contentAlignment = Alignment.Center
                ) {
                    if (myTurn) CircularProgressIndicator(
                        progress = { s.sec / 60f }, modifier = Modifier.size(44.dp),
                        color = if (s.sec <= 10) Color.Red else Gold, trackColor = Color(0x33FFFFFF), strokeWidth = 3.dp
                    )
                    Avatar(s.me, moodFor(c, s.players, s.me), 36.dp, c.hits[s.me]?.kind)
                }
                Column {
                    Text(meP.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(if (myTurn) "⏱ ${s.sec}" else "${meP.total} نقطة", color = if (myTurn && s.sec <= 10) Color.Red else Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // خيارات الرمي بجانب أفاتارك
                val tp = throwTo?.let { id -> s.players.firstOrNull { it.id == id } }
                if (tp != null && tp.id != s.me) {
                    Pill("🥚 بيض على ${tp.name}", Color(0xFFF9A825)) { c.send(JSONObject().put("t", "throw").put("to", tp.id).put("kind", "egg")); throwTo = null }
                    Pill("شبشب على ${tp.name}", Color(0xFFEF6C00)) { c.send(JSONObject().put("t", "throw").put("to", tp.id).put("kind", "slipper")); throwTo = null }
                    Pill("✕", Color(0xFF6D4C41)) { throwTo = null }
                }
                if (staged.isNotEmpty() || selected.isNotEmpty() || formedValid.isNotEmpty()) {
                    val ok = shown >= 51 && !meP.laid
                    Text(
                        "المجموع: $shown", color = if (ok) Good else Gold, fontWeight = FontWeight.Black, fontSize = 15.sp,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(10.dp)).border(1.dp, if (ok) Good else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
              }
              Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // تكوين / فك التكوين (داخل اليد بدون إنزال)
                if (selected.size >= 2 && c.groups.none { it.toSet() == selSet }) Pill("تكوين", Color(0xFF00897B)) { c.formGroup(selected.toList()); selected.clear() }
                if (selected.any { id -> c.groups.any { id in it } }) Pill("فك التكوين", Color(0xFF8D6E63)) { c.ungroupCards(selected.toList()); selected.clear() }
                if (myTurn && partsValid && parts.size == 1) Pill("+ مجموعة", Color(0xFF5E35B1)) { staged.addAll(parts); selected.clear() }
                if (staged.isNotEmpty()) Pill("إلغاء", Color(0xFF6D4C41)) { staged.clear() }
                if (myTurn && s.taken >= 0) Pill("إرجاع", Color(0xFFEF6C00)) { c.send(JSONObject().put("t", "return")) }
                if (canHand) {
                    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "s")
                    Box(Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse }) { Pill("هاند", Color(0xFFF9A825), big = true) { sendMeld() } }
                } else if (canLay) Pill("نزّل", Color(0xFF2E7D32), big = true) { sendMeld() }
                if (myTurn && s.drew && selected.size == 1 && s.taken < 0) Pill("ارمِ", Color(0xFFC62828)) { discard(selected[0]) }
                Pill(if (twoRows) "سطر واحد" else "سطرين", Color(0xFF455A64)) { twoRows = !twoRows }
              }
            }

            Hand(c, selected, stagedIds, s.taken, twoRows, ::discard, ::dropCard)
        }

        ScoreBoard(s, Modifier.align(Alignment.TopEnd).padding(6.dp).zIndex(10f))
        Text("هاند · $CREDIT", Modifier.align(Alignment.TopStart).padding(8.dp), color = Color(0x88FFFFFF), fontSize = 10.sp)
        if (s.roundOver) { if (s.gameOver) FinalScene(c, s) else RoundOver(c, s) }
    }
}

/** الورق باليد: سطر أو سطران، ترتيب يدوي بالسحب، ضغطتان للرمي، وسحب إلى الطاولة */
@Composable
fun Hand(
    c: Controller, selected: SnapshotStateList<Int>, stagedIds: Set<Int>, takenId: Int, twoRows: Boolean,
    onDiscard: (Int) -> Unit, onDrop: (Int, Offset) -> Boolean
) {
    val n = c.order.size
    val rowsCount = if (twoRows && n > 7) 2 else 1
    val discardNow = rememberUpdatedState(onDiscard)
    val dropNow = rememberUpdatedState(onDrop)
    var origin by remember { mutableStateOf(Offset.Zero) }

    // لكل مجموعة مكوّنة لونها الخاص، ويبقى لونها ثابتاً ما دامت قائمة (لا يتغير بفك مجموعة أخرى)
    val gColor = remember { mutableStateMapOf<Int, Int>() }
    val groupsSnap = c.groups.toList()
    LaunchedEffect(groupsSnap) {
        val inGroup = groupsSnap.flatten().toSet()
        gColor.keys.filter { it !in inGroup }.forEach { gColor.remove(it) }
        val taken = mutableSetOf<Int>()
        val pending = mutableListOf<List<Int>>()
        for (g in groupsSnap) {
            val have = g.mapNotNull { gColor[it] }.distinct()
            if (have.size == 1 && have[0] !in taken && g.all { gColor[it] == have[0] }) taken.add(have[0]) else pending.add(g)
        }
        for (g in pending) {
            var idx = (0 until GroupColors.size).firstOrNull { it !in taken } ?: (taken.size % GroupColors.size)
            taken.add(idx)
            g.forEach { gColor[it] = idx }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(if (rowsCount == 2) 112.dp else 84.dp).onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        val cw = 46.dp
        val ch = 66.dp
        val rowH = 34.dp
        val perRow = if (rowsCount == 2) maxOf(7, (n + 1) / 2) else maxOf(1, n)
        val d = LocalDensity.current
        val rowPx = with(d) { rowH.toPx() }
        val raisePx = with(d) { 12.dp.toPx() }
        val cwPx = with(d) { cw.toPx() }
        val chPx = with(d) { ch.toPx() }
        val gapPx = with(d) { 14.dp.toPx() }
        val widthPx = with(d) { maxWidth.toPx() }

        // كل مجموعة منعزلة عن غيرها: فراغ بين الورق المكوّن والذي بجانبه من مجموعة أخرى
        val gid = c.order.map { id -> c.groups.indexOfFirst { id in it } }
        val extra = FloatArray(maxOf(n, 1))
        val rowGap = FloatArray(maxOf(1, (n + perRow - 1) / perRow))
        var acc = 0f
        for (i in 0 until n) {
            val col = i % perRow
            if (col == 0) acc = 0f
            else if (gid[i] != gid[i - 1] && (gid[i] >= 0 || gid[i - 1] >= 0)) acc += gapPx
            extra[i] = acc
            rowGap[i / perRow] = acc
        }
        val maxGap = rowGap.maxOrNull() ?: 0f
        val stepPx = if (perRow <= 1) cwPx else minOf(cwPx * 0.84f, (widthPx - cwPx - maxGap) / (perRow - 1))
        fun rowStart(row: Int): Float {
            val cols = minOf(perRow, n - row * perRow).coerceAtLeast(1)
            return (widthPx - (stepPx * (cols - 1) + rowGap[row] + cwPx)) / 2f
        }

        c.order.forEachIndexed { i, id ->
            key(id) {
                var dx by remember { mutableFloatStateOf(0f) }
                var dy by remember { mutableFloatStateOf(0f) }
                var drag by remember { mutableStateOf(false) }
                val sel = id in selected
                val col = i % perRow
                val row = i / perRow
                val bColor: Color? = when {
                    id == takenId -> Color(0xFFFFEB3B)
                    id in c.fresh -> Color(0xFF00E5FF)
                    else -> gColor[id]?.let { GroupColors[it % GroupColors.size] }
                }
                val baseX = rowStart(row) + col * stepPx + extra[i]
                Box(
                    Modifier
                        .offset { IntOffset((baseX + dx).roundToInt(), (row * rowPx + (if (sel) 0f else raisePx) + dy).roundToInt()) }
                        .zIndex(if (drag) 100f else (row * 30 + col).toFloat())
                        .pointerInput(id, stagedIds) {
                            detectTapGestures(
                                onTap = {
                                    if (id !in stagedIds) {
                                        // الضغط على ورقة مكوّنة يحدد المجموعة كلها
                                        val ids = c.groups.firstOrNull { id in it } ?: listOf(id)
                                        if (id in selected) selected.removeAll(ids.toSet())
                                        else ids.forEach { if (it !in selected) selected.add(it) }
                                    }
                                },
                                onDoubleTap = { discardNow.value(id) }
                            )
                        }
                        .pointerInput(id, i, n, perRow) {
                            detectDragGestures(
                                onDragStart = { drag = true },
                                onDragEnd = {
                                    val center = Offset(
                                        origin.x + baseX + dx + cwPx / 2,
                                        origin.y + row * rowPx + (if (id in selected) 0f else raisePx) + dy + chPx / 2
                                    )
                                    val handled = dropNow.value(id, center)
                                    if (!handled) {
                                        val tc = (col + dx / stepPx).roundToInt().coerceIn(0, perRow - 1)
                                        val tr = (row + dy / rowPx).roundToInt().coerceIn(0, rowsCount - 1)
                                        val t = (tr * perRow + tc).coerceIn(0, n - 1)
                                        dx = 0f; dy = 0f; drag = false; c.moveCard(i, t)
                                    } else { dx = 0f; dy = 0f; drag = false }
                                },
                                onDragCancel = { dx = 0f; dy = 0f; drag = false },
                                onDrag = { ch2, a -> ch2.consume(); dx += a.x; dy += a.y }
                            )
                        }
                ) { CardView(id, cw, ch, if (id in stagedIds) 0.45f else 1f, bColor) }
            }
        }
    }
}

@Composable
fun RoundOver(c: Controller, s: UiState) {
    val sc = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) { sc.animateTo(1f, spring(dampingRatio = 0.45f)) }
    val winner = s.players.firstOrNull { it.id == s.rw }
    Box(Modifier.fillMaxSize().background(Color(0xBB000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.graphicsLayer { scaleX = sc.value; scaleY = sc.value }.background(Color(0xF01A0B3B), RoundedCornerShape(20.dp))
                .border(2.dp, Gold, RoundedCornerShape(20.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (s.hw) Text("هاند!", fontSize = 46.sp, fontWeight = FontWeight.Black, color = Gold)
            Text("فاز بالجولة ${s.round}: ${winner?.name ?: ""}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                s.players.sortedBy { it.total }.forEach {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(it.id, moodOf(s.players, it.id), 50.dp)
                        Text(it.name, color = Color.White, fontSize = 12.sp)
                        Text("${if (it.delta > 0) "+" else ""}${it.delta}", color = if (it.delta < 0) Good else Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                        Text("المجموع ${it.total}", color = Gold, fontSize = 12.sp)
                    }
                }
            }
            if (c.isHost) Pill("الجولة التالية", Color(0xFF2E7D32), big = true) { c.send(JSONObject().put("t", "next")) }
            else Text("بانتظار المضيف...", color = Color.White)
        }
    }
}

/** نهاية اللعبة: الفائز، والخاسر الواحد بأنف يسيل، والجميع يضربونه بالشبشب */
@Composable
fun FinalScene(c: Controller, s: UiState) {
    val loser = finalLoser(s.players) ?: return
    val winner = finalWinner(s.players)?.takeIf { it.id != loser.id } ?: s.players.first { it.id != loser.id }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("انتهت اللعبة", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("الفائز: ${winner.name}", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                s.players.sortedBy { it.total }.forEach { p ->
                    val isLoser = p.id == loser.id
                    val mood = when {
                        c.laughs.containsKey(p.id) -> Mood.LAUGH
                        p.id == winner.id -> Mood.HAPPY
                        isLoser -> Mood.SAD
                        else -> Mood.SCARED
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.onGloballyPositioned { c.centers[p.id] = it.boundsInRoot().center }
                                .clickable(enabled = isLoser && s.me != p.id) {
                                    c.send(JSONObject().put("t", "throw").put("to", p.id).put("kind", "slipper"))
                                }
                        ) { Avatar(p.id, mood, 84.dp, c.hits[p.id]?.kind, nose = isLoser) }
                        Text(p.name + if (p.id == s.me) " (أنت)" else "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${p.total}", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Text(
                if (s.me == loser.id) "أنت الخاسر... استعد 😅" else "اضغط على وجه الخاسر لضربه بالشبشب",
                color = Color(0xFFFFCC80), fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("لعبة جديدة بنفس اللاعبين", Color(0xFF2E7D32), big = true) { c.send(JSONObject().put("t", "newgame")) }
                Pill("خروج", Color(0xFF6D4C41), big = true) { c.reset() }
            }
        }
    }
}
