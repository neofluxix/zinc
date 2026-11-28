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
import org.json.JSONArray
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
    @Volatile private var ttsGen = 0L
    @Volatile private var activeTtsId = ""
    private val translators = HashMap<String, Translator>()
    private var lastRms = 0L
    @Volatile private var listenGen = 0
    @Volatile private var dead = false

    init {
        tts = TextToSpeech(act) { status ->
            ttsStatus = status
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == activeTtsId) emit("tts", "1")
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == activeTtsId) emit("tts", "0")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == activeTtsId) emit("tts", "0")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == activeTtsId) emit("tts", "0")
                    }
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

    @JavascriptInterface
    fun isOnline(): Boolean = Packs.online(act)

    private fun speechTag(tag: String): String = when (tag.lowercase(Locale.ROOT)) {
        "zh-hans", "zh" -> "zh-CN"
        "fil" -> "fil-PH"
        else -> tag
    }

    private fun speakCandidates(tag: String): List<Locale> {
        val norm = speechTag(tag)
        val tags = linkedSetOf(tag, norm)
        val b = tag.substringBefore('-').lowercase(Locale.ROOT)
        if (b == "fil" || b == "tl") {
            tags.add("fil-PH"); tags.add("tl-PH"); tags.add("tl")
        } else if (b == "zh") {
            tags.add("zh-CN"); tags.add("zh")
        }
        tags.add(b)
        return tags.map { Locale.forLanguageTag(it) }
    }

    private fun releaseAudioInputs() {
        vosk?.let { try { it.shutdown() } catch (_: Exception) { } }
        vosk = null
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) { }
            try { it.destroy() } catch (_: Exception) { }
        }
        recognizer = null
    }

    // ---------------------------------------------------------------- listening

    @JavascriptInterface
    fun listen(tag: String, silenceMs: Int, pref: String) {
        val gen = ++listenGen
        main.post {
            if (gen != listenGen || dead) return@post
            releaseAudioInputs()
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                emit("error", "Microphone permission is off.")
                return@post
            }
            val key = Packs.voskKey(tag)
            val vOk = Packs.voskReady(act, key) && !voskDisabled()
            val onlineNow = Packs.online(act)
            val sysOk = SpeechRecognizer.isRecognitionAvailable(act)
            val onDevOk = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)
            when {
                pref == "zinc" && vOk -> listenVosk(key, tag, silenceMs, gen)
                pref == "google" -> if (onDevOk) listenGoogle(tag, silenceMs, null, gen) else listenSystem(tag, silenceMs, false, null, false, gen)
                onlineNow && sysOk -> listenSystem(tag, silenceMs, false, if (vOk) key else null, onDevOk, gen)
                onDevOk -> listenGoogle(tag, silenceMs, if (vOk) key else null, gen)
                vOk -> listenVosk(key, tag, silenceMs, gen)
                sysOk -> listenSystem(tag, silenceMs, true, null, false, gen)
                else -> emit("error", "No speech recognizer on this phone.")
            }
        }
    }

    /** Stops the active recognizer so any partial speech finalizes cleanly. */
    @JavascriptInterface
    fun stopListening() {
        main.post {
            vosk?.let { try { it.stop() } catch (_: Exception) { } }
            recognizer?.let { try { it.stopListening() } catch (_: Exception) { } }
        }
    }

    /** Aborts listening immediately without emitting a final result. */
    @JavascriptInterface
    fun cancelListening() {
        listenGen++
        main.post { releaseAudioInputs() }
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
                val rec = Recognizer(model, 16000.0f).apply {
                    try {
                        setWords(true)
                        setPartialWords(true)
                    } catch (_: Throwable) { }
                }
                val svc = SpeechService(rec, 16000.0f)
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
                    try { runVosk(svc, tag, silenceMs, gen) }
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

    private fun runVosk(svc: SpeechService, tag: String, silenceMs: Int, gen: Int) {
        vosk = svc
        val sb = StringBuilder()
        val accWords = JSONArray()
        var finished = false
        var lastAct = System.currentTimeMillis()
        var lastPartial = ""
        val cjk = tag.startsWith("zh") || tag.startsWith("ja")
        fun clean(s: String) = if (cjk) s.replace(" ", "") else s

        fun extractWords(obj: JSONObject?, key: String): List<JSONObject> {
            val arr = obj?.optJSONArray(key) ?: return emptyList()
            val list = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val w = item.optString("word")
                if (w.isNotEmpty()) {
                    list.add(JSONObject().put("w", w).put("c", item.optDouble("conf", -1.0)))
                }
            }
            return list
        }
        fun mergeWords(extra: List<JSONObject>): JSONArray {
            val out = JSONArray()
            for (i in 0 until accWords.length()) out.put(accWords.get(i))
            for (item in extra) out.put(item)
            return out
        }

        fun close() {
            finished = true
            if (vosk === svc) vosk = null
            try { svc.shutdown() } catch (_: Exception) { }
        }
        fun finish(extra: String, finalObj: JSONObject? = null) {
            if (finished) return
            for (item in extractWords(finalObj, "result")) accWords.put(item)
            var sum = 0.0
            var cnt = 0
            for (i in 0 until accWords.length()) {
                val c = accWords.optJSONObject(i)?.optDouble("c", -1.0) ?: -1.0
                if (c >= 0.0) { sum += c; cnt++ }
            }
            val avgConf = if (cnt > 0) sum / cnt else -1.0
            val t = clean((sb.toString() + " " + extra).trim())
            close()
            if (gen != listenGen || dead) return
            if (t.isNotEmpty()) {
                emit("surety", JSONObject().put("partial", false).put("conf", avgConf).put("words", accWords).toString())
            }
            emit("final", t)
        }

        val tick = object : Runnable {
            override fun run() {
                if (finished || gen != listenGen || dead) return
                val idle = System.currentTimeMillis() - lastAct
                val heard = sb.isNotEmpty() || lastPartial.isNotEmpty()
                if ((heard && idle > silenceMs) || (!heard && idle > 7000)) {
                    try { svc.stop() } catch (_: Exception) { }
                    main.postDelayed({ finish("") }, 450)
                    return
                }
                main.postDelayed(this, 100)
            }
        }

        svc.startListening(object : VoskListener {
            override fun onPartialResult(hypothesis: String?) {
                if (finished || gen != listenGen || dead) return
                val obj = JSONObject(hypothesis ?: "{}")
                val p = obj.optString("partial")
                if (p.isNotEmpty() && p != lastPartial) {
                    lastPartial = p
                    lastAct = System.currentTimeMillis()
                    val words = mergeWords(extractWords(obj, "partial_result"))
                    emit("surety", JSONObject().put("partial", true).put("words", words).toString())
                    emit("partial", clean((sb.toString() + " " + p).trim()))
                }
            }
            override fun onResult(hypothesis: String?) {
                if (finished || gen != listenGen || dead) return
                val obj = JSONObject(hypothesis ?: "{}")
                val t = obj.optString("text")
                for (item in extractWords(obj, "result")) accWords.put(item)
                if (t.isNotEmpty()) {
                    sb.append(' ').append(t)
                    lastPartial = ""
                    lastAct = System.currentTimeMillis()
                    emit("surety", JSONObject().put("partial", true).put("words", accWords).toString())
                    emit("partial", clean(sb.toString().trim()))
                }
            }
            override fun onFinalResult(hypothesis: String?) {
                val obj = JSONObject(hypothesis ?: "{}")
                finish(obj.optString("text"), obj)
            }
            override fun onError(exception: Exception?) {
                close()
                if (gen == listenGen && !dead) emit("error", "Voice engine error: ${exception?.message}")
            }
            override fun onTimeout() { finish("") }
        })
        main.postDelayed(tick, 300)
    }

    // System & Google on-device SpeechRecognizer runners.

    private fun errText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
        12, 13 -> "No offline voice for this language. Connect to the internet for this one, or pick a supported language."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Network issue with voice input."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy. Tap again."
        else -> "Speech recognition error ($e)."
    }

    private fun listenSystem(
        tag: String, silenceMs: Int, preferOffline: Boolean,
        fallbackKey: String?, fallbackOnDev: Boolean, gen: Int, retryCount: Int = 0
    ) {
        if (gen != listenGen || dead) return
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            if (fallbackOnDev) listenGoogle(tag, silenceMs, fallbackKey, gen)
            else if (fallbackKey != null) listenVosk(fallbackKey, tag, silenceMs, gen)
            else emit("error", "No speech recognizer on this phone.")
            return
        }
        emit("which", "system")
        runRecognizer(
            SpeechRecognizer.createSpeechRecognizer(act), tag, silenceMs,
            preferOffline, fallbackKey, google = false, fallbackOnDev = fallbackOnDev,
            gen = gen, retryCount = retryCount
        )
    }

    /** Google's on-device recognizer (Android 13+). Fallback when offline or system service is unavailable. */
    private fun listenGoogle(tag: String, silenceMs: Int, fallbackKey: String?, gen: Int, retryCount: Int = 0) {
        if (gen != listenGen || dead) return
        try {
            emit("which", "google")
            runRecognizer(
                SpeechRecognizer.createOnDeviceSpeechRecognizer(act), tag, silenceMs,
                preferOffline = true, fallbackKey = fallbackKey, google = true,
                fallbackOnDev = false, gen = gen, retryCount = retryCount
            )
        } catch (e: Exception) {
            if (fallbackKey != null) listenVosk(fallbackKey, tag, silenceMs, gen)
            else listenSystem(tag, silenceMs, true, null, false, gen)
        }
    }

    private fun runRecognizer(
        r: SpeechRecognizer, tag: String, silenceMs: Int, preferOffline: Boolean,
        fallbackKey: String?, google: Boolean, fallbackOnDev: Boolean, gen: Int, retryCount: Int = 0
    ) {
        recognizer = r
        var done = false
        var ended = false
        var endedAt = 0L
        var lastPartial = ""
        var lastPartialAt = 0L
        val cjk = tag.startsWith("zh") || tag.startsWith("ja")
        val watchdogHolder = arrayOfNulls<Runnable>(1)

        fun release() {
            watchdogHolder[0]?.let { main.removeCallbacks(it) }
            if (recognizer === r) recognizer = null
            try { r.destroy() } catch (_: Exception) { }
        }
        fun finalizeText(txt: String, conf: Float, alts: JSONArray) {
            if (done) return
            done = true
            main.post { release() }
            if (gen != listenGen || dead) return
            if (txt.isNotEmpty()) {
                emit("surety", JSONObject().put("partial", false).put("conf", conf.toDouble()).put("alts", alts).toString())
            }
            emit("final", txt)
        }

        // Nudge SpeechRecognizer to finalize promptly when partial text goes quiet, preventing multi-second OEM hangs.
        val watchdog = object : Runnable {
            override fun run() {
                if (done || gen != listenGen || dead) return
                val now = System.currentTimeMillis()
                if (!ended && lastPartial.isNotEmpty() && (now - lastPartialAt) >= silenceMs + 350L) {
                    ended = true
                    endedAt = now
                    try { r.stopListening() } catch (_: Exception) { }
                } else if (ended && endedAt > 0L && lastPartial.isNotEmpty() && (now - endedAt) >= 1200L) {
                    finalizeText(lastPartial, -1f, JSONArray())
                    return
                }
                main.postDelayed(this, 120)
            }
        }
        watchdogHolder[0] = watchdog

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (done || gen != listenGen || dead) return
                val now = System.currentTimeMillis()
                if (now - lastRms > 80) { lastRms = now; emit("rms", rmsdB.toString()) }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (!ended) { ended = true; endedAt = System.currentTimeMillis() }
            }
            override fun onError(error: Int) {
                if (done) return
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && retryCount == 0 && gen == listenGen && !dead) {
                    done = true
                    main.post {
                        release()
                        main.postDelayed({
                            if (gen != listenGen || dead) return@postDelayed
                            if (google) listenGoogle(tag, silenceMs, fallbackKey, gen, retryCount = 1)
                            else listenSystem(tag, silenceMs, preferOffline, fallbackKey, fallbackOnDev, gen, retryCount = 1)
                        }, 180)
                    }
                    return
                }
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartial.isNotBlank()) {
                    finalizeText(lastPartial, -1f, JSONArray())
                    return
                }
                val benign = error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (!benign && (fallbackOnDev || fallbackKey != null)) {
                    done = true
                    main.post {
                        release()
                        if (gen != listenGen || dead) return@post
                        emit("engine", "Switching voice engine…")
                        if (fallbackOnDev) listenGoogle(tag, silenceMs, fallbackKey, gen)
                        else if (fallbackKey != null) listenVosk(fallbackKey, tag, silenceMs, gen)
                    }
                    return
                }
                done = true
                main.post { release() }
                if (gen != listenGen || dead) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    emit("final", "")
                } else {
                    emit("error", errText(error))
                }
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val raw = list?.firstOrNull()?.trim().orEmpty()
                val txt = raw.ifBlank { lastPartial }
                val conf = scores?.firstOrNull()?.takeIf { it in 0f..1f } ?: -1f
                val alts = JSONArray()
                if (list != null) {
                    for (i in 1 until minOf(list.size, 3)) {
                        val a = list[i]
                        if (!a.isNullOrBlank() && a != txt) alts.put(a)
                    }
                }
                finalizeText(txt, conf, alts)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (done || gen != listenGen || dead) return
                val stable = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                val unstable = partialResults?.getStringArrayList("android.speech.extra.UNSTABLE_TEXT")?.firstOrNull()?.trim().orEmpty()
                val combined = when {
                    stable.isEmpty() -> unstable
                    unstable.isEmpty() -> stable
                    stable.endsWith(unstable, ignoreCase = true) -> stable
                    cjk -> stable + unstable
                    else -> "$stable $unstable"
                }
                if (combined.isNotEmpty() && combined != lastPartial) {
                    lastPartial = combined
                    lastPartialAt = System.currentTimeMillis()
                    emit("partial", combined)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(tag))
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            )
            main.postDelayed(watchdog, 250)
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
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag(tag))
                )
                main.postDelayed({ r.destroy() }, 4000)
            } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- translation

    private fun emitTranslateError(id: String, msg: String) {
        emit("translateError", JSONObject().put("id", id).put("error", msg).toString())
    }

    @JavascriptInterface
    fun translate(id: String, text: String, src: String, tgt: String) {
        main.post {
            try {
                val srcLang = TranslateLanguage.fromLanguageTag(src)
                val tgtLang = TranslateLanguage.fromLanguageTag(tgt)
                if (srcLang == null || tgtLang == null) {
                    emitTranslateError(id, "Offline translation isn't available for $src to $tgt.")
                    return@post
                }
                val tr = translators.getOrPut("$srcLang>$tgtLang") {
                    Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(srcLang).setTargetLanguage(tgtLang).build()
                    )
                }
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.translate(text)
                            .addOnSuccessListener { out ->
                                emit("translated", JSONObject().put("id", id).put("text", out).toString())
                            }
                            .addOnFailureListener { emitTranslateError(id, "Translation failed: ${it.message}") }
                    }
                    .addOnFailureListener { emitTranslateError(id, "Translation pack missing and no internet.") }
            } catch (e: Exception) {
                emitTranslateError(id, "Unsupported language pair: $src to $tgt")
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
            val ok = speakCandidates(tag).any { loc ->
                val res = t.setLanguage(loc)
                res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (!ok) {
                emit("error", "This phone has no voice installed for that language (Settings, Text-to-speech).")
                return@post
            }
            val uid = "u_${++ttsGen}"
            activeTtsId = uid
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid) == TextToSpeech.ERROR) {
                activeTtsId = ""
                emit("tts", "0")
            }
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
                        val avail = speakCandidates(c).any { (tts?.isLanguageAvailable(it) ?: -2) >= 0 }
                        if (avail) "available" else "NOT installed (Settings, Text-to-speech)"
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
    fun stopSpeaking() { main.post { activeTtsId = ""; tts?.stop(); emit("tts", "0") } }

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
