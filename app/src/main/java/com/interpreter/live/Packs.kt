package com.interpreter.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/** Offline language packs: ML Kit translation packs and Vosk voice packs. Blocking calls, run off the main thread. */
object Packs {

    fun interface Listener { fun onEvent(type: String, data: String) }

    @Volatile var listener: Listener? = null
    @Volatile var busy = false
    @Volatile var cancel = false

    private fun emit(type: String, data: String) { listener?.onEvent(type, data) }

    fun mlCode(code: String): String {
        val b = code.substringBefore('-').lowercase(Locale.ROOT)
        return if (b == "fil") "tl" else b
    }

    fun voskKey(code: String): String {
        val t = code.lowercase(Locale.ROOT)
        if (t == "en-in") return "en-in"
        return when (val b = t.substringBefore('-')) {
            "en" -> "en-us"
            "zh" -> "cn"
            "fil", "tl" -> "ph"
            "kk" -> "kz"
            else -> b
        }
    }

    fun voskDir(ctx: Context, key: String) = File(ctx.filesDir, "vosk/$key")
    fun voskReady(ctx: Context, key: String) = File(voskDir(ctx, key), ".ok").exists()
    fun voskPath(ctx: Context, key: String): String? =
        voskDir(ctx, key).listFiles()?.firstOrNull { it.isDirectory }?.absolutePath

    private fun caps(ctx: Context): NetworkCapabilities? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
    }
    fun online(ctx: Context) = caps(ctx)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    fun onWifi(ctx: Context) = caps(ctx)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } == true

    private fun modelList(ctx: Context): JSONArray {
        val cache = File(ctx.filesDir, "vosk-model-list.json")
        try {
            val c = URL("https://alphacephei.com/vosk/models/model-list.json").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            val s = c.inputStream.bufferedReader().use { it.readText() }
            cache.writeText(s)
            return JSONArray(s)
        } catch (e: Exception) {
            if (cache.exists()) return JSONArray(cache.readText())
            throw e
        }
    }

    private fun isSmall(m: JSONObject) =
        (m.optString("type") == "small" || m.optString("name").contains("small")) && m.optString("obsolete") != "true"

    private fun pickModel(list: JSONArray, key: String): JSONObject? {
        var best: JSONObject? = null
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            if (m.optString("lang").lowercase(Locale.ROOT) != key || !isSmall(m)) continue
            if (best == null || m.optLong("size") < best.optLong("size")) best = m
        }
        return best
    }

    private class CountIn(i: InputStream, val cb: (Int) -> Unit) : FilterInputStream(i) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) cb(n)
            return n
        }
    }

    private fun fetchVosk(ctx: Context, key: String, m: JSONObject, onPct: (Int) -> Unit) {
        val dir = voskDir(ctx, key)
        dir.deleteRecursively()
        dir.mkdirs()
        val c = URL(m.getString("url")).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val total = maxOf(c.contentLengthLong, m.optLong("size"), 1L)
        var done = 0L
        var last = -1
        val root = dir.canonicalPath
        ZipInputStream(BufferedInputStream(CountIn(c.inputStream) { n ->
            done += n
            val p = ((done * 100) / total).toInt().coerceIn(0, 99)
            if (p != last) { last = p; onPct(p) }
        })).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (cancel) throw IllegalStateException("Stopped")
                val f = File(dir, e.name)
                if (!f.canonicalPath.startsWith(root)) throw SecurityException("Bad archive")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                e = zin.nextEntry
            }
        }
        File(dir, ".ok").writeText("1")
    }

    private fun downloaded(): Set<String> =
        Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java))
            .map { it.language }.toSet()

    private fun cond(wifiOnly: Boolean) = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()

    /** Makes both languages usable with no internet. Emits ofProgress / ofReady / ofFail. */
    fun ensure(ctx: Context, c1: String, c2: String, wifiOnly: Boolean, notify: (String, Int) -> Unit) {
        val key = "$c1|$c2"
        try {
            val langs = listOf(c1, c2)
            val mlNeeded = langs.map { mlCode(it) }.toMutableSet().apply { add("en") }
            val have = downloaded()
            val mlMissing = mlNeeded.filter { it !in have }
            val voskMissing = langs.map { voskKey(it) }.distinct().filter { !voskReady(ctx, it) }

            var voskTodo = emptyList<Pair<String, JSONObject>>()
            if (voskMissing.isNotEmpty() && online(ctx)) {
                val models = modelList(ctx)
                voskTodo = voskMissing.mapNotNull { k -> pickModel(models, k)?.let { k to it } }
            }
            val steps = mlMissing.size + voskTodo.size
            if (steps > 0) {
                if (!online(ctx)) { emit("ofFail", "No internet. Connect once so Zinc can download the offline packs."); return }
                if (wifiOnly && !onWifi(ctx)) { emit("ofFail", "Waiting for Wi-Fi."); return }
            }
            var idx = 0
            fun report(label: String, sub: Int) {
                val pct = if (steps == 0) 100 else ((idx * 100 + sub) / steps).coerceIn(0, 100)
                emit("ofProgress", JSONObject().put("label", label).put("pct", pct).toString())
                notify(label, pct)
            }
            for (code in mlMissing) {
                report("Translation pack ($code)", 0)
                Tasks.await(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(code).build(), cond(wifiOnly)))
                idx++
            }
            for ((k, m) in voskTodo) {
                report("Voice pack ($k)", 0)
                fetchVosk(ctx, k, m) { p -> report("Voice pack ($k)", p) }
                idx++
            }
            val speech = langs.map { l -> if (voskReady(ctx, voskKey(l))) "$l: on-device voice" else "$l: system voice" }
            emit("ofReady", JSONObject().put("key", key).put("speech", JSONArray(speech)).toString())
        } catch (e: Exception) {
            emit("ofFail", "Download failed: ${e.message ?: "unknown error"}. Check your connection.")
        }
    }

    /** Every voice pack and every translation pack. Emits packProgress / packFail / packsDone. */
    fun all(ctx: Context, wifiOnly: Boolean, notify: (String, Int) -> Unit) {
        try {
            if (!online(ctx)) { emit("packFail", "No internet connection."); return }
            if (wifiOnly && !onWifi(ctx)) { emit("packFail", "Waiting for Wi-Fi. Connect to Wi-Fi to download every language."); return }
            val list = modelList(ctx)
            val voice = (0 until list.length()).map { list.getJSONObject(it) }
                .filter { isSmall(it) }
                .distinctBy { it.optString("lang") }
                .filter { !voskReady(ctx, it.optString("lang").lowercase(Locale.ROOT)) }
            val have = downloaded()
            val ml = TranslateLanguage.getAllLanguages().filter { it !in have }
            val total = voice.size + ml.size
            var done = 0
            fun step(label: String) {
                emit("packProgress", JSONObject().put("code", label).put("done", done).put("total", total).toString())
                notify(label, if (total == 0) 100 else done * 100 / total)
            }
            for (m in voice) {
                if (cancel) { emit("packFail", "Stopped."); return }
                val lang = m.optString("lang").lowercase(Locale.ROOT)
                step("voice $lang")
                try { fetchVosk(ctx, lang, m) { } } catch (e: Exception) { emit("packFail", "voice $lang: ${e.message}") }
                done++
            }
            var fails = 0
            for (code in ml) {
                if (cancel) { emit("packFail", "Stopped."); return }
                if (fails >= 3) { emit("packFail", "Several downloads failed. Check your connection."); return }
                step("translation $code")
                try {
                    Tasks.await(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(code).build(), cond(wifiOnly)))
                    fails = 0
                } catch (e: Exception) { fails++; emit("packFail", "$code: ${e.message}") }
                done++
            }
            emit("packsDone", "")
        } catch (e: Exception) {
            emit("packFail", "${e.message}")
        }
    }
}
