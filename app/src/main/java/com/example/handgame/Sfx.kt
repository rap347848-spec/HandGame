package com.example.handgame

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** أصوات مولّدة بالكود (بدون ملفات صوت خارجية) */
object Sfx {
    private const val SR = 22050
    private const val TWO_PI = (2 * PI).toFloat()

    private fun play(d: ShortArray) {
        thread {
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(d.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(d, 0, d.size); t.play()
                Thread.sleep(d.size * 1000L / SR + 150)
                t.release()
            } catch (_: Exception) {}
        }
    }

    private fun pcm(sec: Float, f: (Float) -> Float): ShortArray {
        val n = (SR * sec).toInt()
        return ShortArray(n) { i -> (f(i / SR.toFloat()).coerceIn(-1f, 1f) * 32000).toInt().toShort() }
    }

    // ---- الأصوات الناطقة: ثلاثة كرت / اثنين كرت / كرت / هاند / رمي ----
    private var appCtx: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val words = mapOf(
        "c3" to "ثَلاثَة كَرْت",
        "c2" to "إثْنَيْن كَرْت",
        "c1" to "كَرْت",
        "hand" to "هَانْد",
        "rami" to "رِمِيْ"
    )

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("ar"))
                tts?.setSpeechRate(0.95f)
                ttsReady = true
            }
        }
    }

    /**
     * ينطق الكلمة. إن وُضع تسجيل صوتي في res/raw باسم voice_c3 / voice_c2 / voice_c1 / voice_hand / voice_rami
     * (mp3 أو ogg أو wav) يُشغَّل بدل صوت الجوال الآلي.
     */
    fun say(key: String) {
        val ctx = appCtx ?: return
        val id = ctx.resources.getIdentifier("voice_$key", "raw", ctx.packageName)
        if (id != 0) {
            try { MediaPlayer.create(ctx, id)?.apply { setOnCompletionListener { it.release() }; start() } } catch (_: Exception) {}
            return
        }
        val text = words[key] ?: return
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "v$key")
    }

    /** حسب نوع المؤثر: egg / slipper / laugh */
    fun play(kind: String) = when (kind) {
        "egg" -> splat()
        "slipper" -> slap()
        else -> laugh()
    }

    /** صفعة الشبشب */
    fun slap() = play(pcm(0.22f) { t ->
        val n = Random.nextFloat() * 2 - 1
        n * exp(-t * 45f) * 0.9f + sin(TWO_PI * 150f * t) * exp(-t * 28f) * 0.7f
    })

    /** بيضة تنفجر */
    fun splat() {
        var y = 0f
        play(pcm(0.45f) { t ->
            val n = Random.nextFloat() * 2 - 1
            y += 0.18f * (n - y)
            y * exp(-t * 8f) * 2.2f + sin(TWO_PI * (320f - 420f * t) * t) * exp(-t * 14f) * 0.5f
        })
    }

    /** ضحكة خبيثة خفيفة: «ها ها ها ها ها» */
    fun laugh() = play(pcm(0.9f) { t ->
        val k = (t / 0.17f).toInt()
        val lt = t - k * 0.17f
        if (k > 4 || lt > 0.11f) 0f else {
            val f = 260f - 18f * k
            val env = sin(PI.toFloat() * lt / 0.11f)
            val w = sin(TWO_PI * f * lt) + 0.5f * sin(2 * TWO_PI * f * lt) + 0.3f * sin(3 * TWO_PI * f * lt)
            w * env * 0.14f
        }
    })
}
