package com.interpreter.live

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.util.Locale
import java.util.concurrent.Executors
import org.vosk.android.RecognitionListener as VoskListener

/**
 * Everything that runs on the phone, exposed to the page as `Android`:
 * speech recognition (bundled Vosk engine, with the system recognizer as a fallback),
 * ML Kit translation, text-to-speech and automatic pack downloads.
 */
class Bridge(private val act: Activity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var vosk: SpeechService? = null
    private val voskModels = HashMap<String, Model>()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsStatus = -1
    private val translators = HashMap<String, Translator>()
    private var lastRms = 0L
    @Volatile private var listenGen = 0
    @Volatile private var dead = false

    init {
        tts = TextToSpeech(act) { status ->
            ttsStatus = status
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { emit("tts", "1") }
                    override fun onDone(utteranceId: String?) { emit("tts", "0") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { emit("tts", "0") }
                    override fun onError(utteranceId: String?, errorCode: Int) { emit("tts", "0") }
                })
            }
        }
        Packs.listener = Packs.Listener { t, d -> emit(t, d) }
        // A native crash while loading the built-in voice engine leaves this marker behind; switch that engine off.
        if (loadMarker.exists()) { offFlag.writeText("1"); loadMarker.delete() }
    }

    private val loadMarker get() = java.io.File(act.filesDir, "vosk_loading")
    private val offFlag get() = java.io.File(act.filesDir, "vosk_disabled")
    private val diagFile get() = java.io.File(act.filesDir, "diag.log")
    private fun voskDisabled() = offFlag.exists()

    @JavascriptInterface
    fun lastCrash(): String {
        val sb = StringBuilder()
        val f = java.io.File(act.filesDir, "crash.txt")
        if (f.exists()) { sb.append(f.readText().take(600)); f.delete() }
        if (diagFile.exists()) {
            val d = diagFile.readText().takeLast(600)
            diagFile.delete()
            if (d.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append("Last check before crash: ").append(d.replace('\n', ';'))
            }
        }
        if (voskDisabled()) sb.append(" | built-in voice engine was switched off after a native crash")
        return sb.toString()
    }

    @JavascriptInterface
    fun enableVosk() { offFlag.delete(); loadMarker.delete() }

    private fun emit(type: String, data: String) {
        val js = "window.nativeEvent&&window.nativeEvent(${JSONObject.quote(type)},${JSONObject.quote(data)})"
        if (dead) return
        main.post { if (!dead) web.evaluateJavascript(js, null) }
    }

    // ---------------------------------------------------------------- pack downloads (run in a foreground service)

    private fun startSvc(i: Intent) {
        try { act.startForegroundService(i) } catch (e: Exception) {
            emit("ofFail", "Couldn't start the download service: ${e.message}")
        }
    }

    @JavascriptInterface
    fun ensureOffline(c1: String, c2: String, wifiOnly: Boolean) {
        main.post {
            startSvc(Intent(act, DownloadService::class.java)
                .putExtra("mode", "ensure").putExtra("c1", c1).putExtra("c2", c2).putExtra("wifi", wifiOnly))
        }
    }

    @JavascriptInterface
    fun downloadAll(wifiOnly: Boolean) {
        main.post { startSvc(Intent(act, DownloadService::class.java).putExtra("mode", "all").putExtra("wifi", wifiOnly)) }
    }

    @JavascriptInterface
    fun cancelDownloads() { Packs.cancel = true }

    // ---------------------------------------------------------------- listening

    @JavascriptInterface
    fun listen(tag: String, silenceMs: Int, pref: String) {
        val gen = ++listenGen
        main.post {
            if (gen != listenGen || dead) return@post
            vosk?.let { try { it.shutdown() } catch (_: Exception) { } }
            vosk = null
            recognizer?.let { try { it.destroy() } catch (_: Exception) { } }
            recognizer = null
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                emit("error", "Microphone permission is off.")
                return@post
            }
            val key = Packs.voskKey(tag)
            val vOk = Packs.voskReady(act, key) && !voskDisabled()
            val gOk = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)
            when {
                pref == "zinc" && vOk -> listenVosk(key, tag, silenceMs, gen)
                pref == "google" -> if (gOk) listenGoogle(tag, silenceMs, null, gen) else listenSystem(tag, silenceMs, gen)
                gOk -> listenGoogle(tag, silenceMs, if (vOk) key else null, gen)
                vOk -> listenVosk(key, tag, silenceMs, gen)
                else -> listenSystem(tag, silenceMs, gen)
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        listenGen++
        main.post {
            vosk?.let { try { it.stop() } catch (_: Exception) { } }
            recognizer?.let { try { it.stopListening() } catch (_: Exception) { } }
        }
    }

    private fun listenVosk(key: String, tag: String, silenceMs: Int, gen: Int) {
        if (voskDisabled()) {
            emit("error", "The built-in voice engine crashed on this phone and is switched off.")
            return
        }
        loader.execute {
            if (gen != listenGen || dead) return@execute
            try {
                emit("which", "zinc")
                val path = Packs.voskPath(act, key) ?: throw IllegalStateException("voice pack is missing")
                if (!voskModels.containsKey(path)) emit("engine", "Loading voice engine…")
                loadMarker.writeText("1")
                val model = voskModels.getOrPut(path) { Model(path) }
                val svc = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
                loadMarker.delete()
                if (gen != listenGen || dead) {
                    try { svc.shutdown() } catch (_: Exception) { }
                    return@execute
                }
                emit("engine", "")
                main.post {
                    if (gen != listenGen || dead) {
                        try { svc.shutdown() } catch (_: Exception) { }
                        return@post
                    }
                    try { runVosk(svc, tag, silenceMs) }
                    catch (t: Throwable) {
                        try { svc.shutdown() } catch (_: Exception) { }
                        emit("error", "Couldn't start the microphone: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                loadMarker.delete()
                if (gen == listenGen && !dead) emit("error", "Voice engine failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    private fun runVosk(svc: SpeechService, tag: String, silenceMs: Int) {
        vosk = svc
        val sb = StringBuilder()
        var finished = false
        var lastAct = System.currentTimeMillis()
        var lastPartial = ""
        val cjk = tag.startsWith("zh") || tag.startsWith("ja")
        fun clean(s: String) = if (cjk) s.replace(" ", "") else s

        fun close() {
            finished = true
            if (vosk === svc) vosk = null
            try { svc.shutdown() } catch (_: Exception) { }
        }
        fun finish(extra: String) {
            if (finished) return
            val t = clean((sb.toString() + " " + extra).trim())
            close()
            emit("final", t)
        }

        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                val idle = System.currentTimeMillis() - lastAct
                val heard = sb.isNotEmpty() || lastPartial.isNotEmpty()
                if ((heard && idle > silenceMs) || (!heard && idle > 9000)) {
                    try { svc.stop() } catch (_: Exception) { }
                    main.postDelayed({ finish("") }, 1500)
                    return
                }
                main.postDelayed(this, 150)
            }
        }

        svc.startListening(object : VoskListener {
            override fun onPartialResult(hypothesis: String?) {
                val p = JSONObject(hypothesis ?: "{}").optString("partial")
                if (p.isNotEmpty() && p != lastPartial) {
                    lastPartial = p
                    lastAct = System.currentTimeMillis()
                    emit("partial", clean((sb.toString() + " " + p).trim()))
                }
            }
            override fun onResult(hypothesis: String?) {
                val t = JSONObject(hypothesis ?: "{}").optString("text")
                if (t.isNotEmpty()) {
                    sb.append(' ').append(t)
                    lastPartial = ""
                    lastAct = System.currentTimeMillis()
                    emit("partial", clean(sb.toString().trim()))
                }
            }
            override fun onFinalResult(hypothesis: String?) {
                finish(JSONObject(hypothesis ?: "{}").optString("text"))
            }
            override fun onError(exception: Exception?) {
                close()
                emit("error", "Voice engine error: ${exception?.message}")
            }
            override fun onTimeout() { finish("") }
        })
        main.postDelayed(tick, 400)
    }

    // Fallback for languages that have no bundled voice pack: Android's own recognizer.

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private fun errText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
        12, 13 -> "No offline voice for this language. Connect to the internet for this one, or pick a supported language."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "This language needs internet for voice input."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy. Tap again."
        else -> "Speech recognition error ($e)."
    }

    private fun listenSystem(tag: String, silenceMs: Int, gen: Int) {
        if (gen != listenGen || dead) return
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            emit("error", "No speech recognizer on this phone.")
            return
        }
        emit("which", "system")
        runRecognizer(SpeechRecognizer.createSpeechRecognizer(act), tag, silenceMs, null, false, gen)
    }

    /** Google's on-device recognizer (Android 13+). Much better with accents than the bundled engine. */
    private fun listenGoogle(tag: String, silenceMs: Int, fallbackKey: String?, gen: Int) {
        if (gen != listenGen || dead) return
        try {
            emit("which", "google")
            runRecognizer(SpeechRecognizer.createOnDeviceSpeechRecognizer(act), tag, silenceMs, fallbackKey, true, gen)
        } catch (e: Exception) {
            if (fallbackKey != null) listenVosk(fallbackKey, tag, silenceMs, gen) else listenSystem(tag, silenceMs, gen)
        }
    }

    private fun runRecognizer(r: SpeechRecognizer, tag: String, silenceMs: Int, fallbackKey: String?, google: Boolean, gen: Int) {
        recognizer = r
        var done = false
        fun release() {
            if (recognizer === r) recognizer = null
            try { r.destroy() } catch (_: Exception) { }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (done) return
                val now = System.currentTimeMillis()
                if (now - lastRms > 80) { lastRms = now; emit("rms", rmsdB.toString()) }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (done) return
                done = true
                // Anything other than "didn't hear you" / audio / busy / permission: hand over to another engine instead of giving up.
                val benign = error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (google && !benign) {
                    main.post {
                        release()
                        if (gen != listenGen || dead) return@post
                        emit("engine", "Switching voice engine…")
                        if (fallbackKey != null) listenVosk(fallbackKey, tag, silenceMs, gen) else listenSystem(tag, silenceMs, gen)
                    }
                } else {
                    main.post { release() }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        emit("final", "")
                    } else {
                        emit("error", errText(error))
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                if (done) return
                done = true
                val txt = first(results)
                main.post { release() }
                emit("final", txt)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (done) return
                val t = first(partialResults)
                if (t.isNotEmpty()) emit("partial", t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, google || !Packs.online(act))
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            )
        } catch (e: Exception) {
            done = true
            release()
            emit("error", "Couldn't start speech recognizer: ${e.message}")
        }
    }

    /** Android 13+: ask the system recognizer to fetch its own pack in the background (best effort). */
    @JavascriptInterface
    fun prepareSpeech(tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        main.post {
            try {
                if (Packs.voskReady(act, Packs.voskKey(tag)) || !SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) return@post
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(act)
                r.triggerModelDownload(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                )
                main.postDelayed({ r.destroy() }, 4000)
            } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- translation

    @JavascriptInterface
    fun translate(id: String, text: String, src: String, tgt: String) {
        main.post {
            try {
                val tr = translators.getOrPut("$src>$tgt") {
                    Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                    )
                }
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.translate(text)
                            .addOnSuccessListener { out ->
                                emit("translated", JSONObject().put("id", id).put("text", out).toString())
                            }
                            .addOnFailureListener { emit("error", "Translation failed: ${it.message}") }
                    }
                    .addOnFailureListener { emit("error", "Translation pack missing and no internet.") }
            } catch (e: Exception) {
                emit("error", "Unsupported language pair: $src to $tgt")
            }
        }
    }

    // ---------------------------------------------------------------- voice out

    @JavascriptInterface
    fun speak(text: String, tag: String, rate: Float, pitch: Float) {
        main.post {
            val t = tts ?: return@post
            if (ttsStatus != TextToSpeech.SUCCESS) {
                emit("error", "Text-to-speech engine is not ready yet.")
                return@post
            }
            val res = t.setLanguage(Locale.forLanguageTag(tag))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                emit("error", "This phone has no voice installed for that language (Settings, Text-to-speech).")
                return@post
            }
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u") == TextToSpeech.ERROR) emit("tts", "0")
        }
    }

    @JavascriptInterface
    fun share(text: String) {
        main.post {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            act.startActivity(Intent.createChooser(i, "Share"))
        }
    }

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = act.getSystemService(Activity.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                act.getSystemService(Activity.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    @JavascriptInterface
    fun vibrate(type: String) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type) {
                    "tick" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    } else {
                        VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    "click" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    } else {
                        VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    "heavy" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    } else {
                        VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    "done" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    } else {
                        VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 25), -1)
                    }
                    "error" -> VibrationEffect.createWaveform(longArrayOf(0, 45, 60, 45), -1)
                    else -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(25)
            }
        } catch (_: Throwable) { }
    }

    /** Step-by-step check of everything offline mode needs. Results arrive as "selftest" events. */
    @JavascriptInterface
    fun selfTest(c1: String, c2: String) {
        loader.execute {
            try { diagFile.writeText("") } catch (_: Throwable) { }
            fun out(s: String) {
                try { diagFile.appendText(s + "\n") } catch (_: Throwable) { }
                emit("selftest", s)
            }
            try {
                out("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                val ps = try { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) } catch (t: Throwable) { -1L }
                out("Page size $ps bytes, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
                out("Microphone: " + if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "allowed" else "NOT allowed")
                out("Internet: " + if (Packs.online(act)) "yes" else "no")
                out("Speech service: " + if (SpeechRecognizer.isRecognitionAvailable(act)) "available" else "missing")
                val g = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)
                out("Google on-device recognizer: " + if (g) "available" else "not available")
                for (c in listOf(c1, c2).distinct()) {
                    val key = Packs.voskKey(c)
                    val path = Packs.voskPath(act, key)
                    if (path == null) { out("$c ($key) built-in voice pack: not downloaded"); continue }
                    if (voskDisabled()) { out("$c ($key) built-in voice pack: downloaded, but the engine is switched off"); continue }
                    out("$c ($key) built-in voice pack: loading (if the app closes here, the engine crashes this phone)")
                    loadMarker.writeText("1")
                    try { voskModels.getOrPut(path) { Model(path) }; out("$c ($key) built-in voice pack: loads OK") }
                    catch (t: Throwable) { out("$c ($key) built-in voice pack FAILED: ${t.javaClass.simpleName} ${t.message}") }
                    loadMarker.delete()
                }
                val have = com.google.android.gms.tasks.Tasks.await(
                    com.google.mlkit.common.model.RemoteModelManager.getInstance()
                        .getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel::class.java)
                ).map { it.language }.toSet()
                for (c in linkedSetOf("en", Packs.mlCode(c1), Packs.mlCode(c2))) {
                    val status = if (TranslateLanguage.fromLanguageTag(c) == null) "NOT supported offline"
                    else if (c in have) "downloaded" else "MISSING"
                    out("Translation pack $c: $status")
                }
                for (c in listOf(c1, c2).distinct()) {
                    val msg = if (ttsStatus != TextToSpeech.SUCCESS) "TTS engine not ready ($ttsStatus)"
                    else {
                        val r = tts?.isLanguageAvailable(Locale.forLanguageTag(c)) ?: -2
                        if (r >= 0) "available" else "NOT installed (Settings, Text-to-speech)"
                    }
                    out("Voice output $c: $msg")
                }
                out("Check finished.")
                try { diagFile.delete() } catch (_: Throwable) { }
            } catch (t: Throwable) {
                out("Check stopped: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() { main.post { tts?.stop(); emit("tts", "0") } }

    fun shutdown() {
        dead = true
        listenGen++
        vosk?.let { try { it.shutdown() } catch (_: Exception) { } }
        recognizer?.destroy()
        tts?.shutdown()
        translators.values.forEach { it.close() }
        voskModels.values.forEach { try { it.close() } catch (_: Exception) { } }
        Packs.listener = null
        loader.shutdownNow()
    }
}
