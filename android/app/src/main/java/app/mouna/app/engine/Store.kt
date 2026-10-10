package app.mouna.app.engine

import android.content.Context
import android.util.Log
import app.mouna.core.GazeModel
import app.mouna.core.SwitchChannel
import app.mouna.core.SwitchModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * This person's taught examples, kept on the phone only (app-private storage, no backup, no network).
 * Embeddings are stored per encoder: a landmark example can't be compared with an NPU one.
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "person").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("mouna", Context.MODE_PRIVATE)

    /** A stored string, or [default] if the preference is missing or of another type: a bad value must not stop the app starting. */
    private fun text(key: String, default: String): String = runCatching { prefs.getString(key, default) }.getOrNull() ?: default

    data class Examples(val samples: MutableMap<String, MutableList<FloatArray>>, val negatives: MutableList<FloatArray>)

    fun load(encoderId: String): Examples = loadExamples(dir, encoderId) { Log.e("Mouna", it) }

    fun save(encoderId: String, e: Examples) {
        val s = JSONObject()
        for ((k, xs) in e.samples) s.put(k, json(xs))
        val o = JSONObject().put("samples", s).put("negatives", json(e.negatives))
        val f = File(dir, "examples-$encoderId.json")
        val tmp = File(dir, "${f.name}.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    var pack: List<String>?
        get() = runCatching { prefs.getString("pack", null)?.split(",")?.filter { it.isNotBlank() } }.getOrNull()
        set(v) = prefs.edit().putString("pack", v?.joinToString(",")).apply()

    var lang: Lang
        get() = runCatching { Lang.valueOf(prefs.getString("lang", "EN")!!) }.getOrDefault(Lang.EN)
        set(v) = prefs.edit().putString("lang", v.name).apply()

    var voice: String
        get() = text("voice", "kavitha")
        set(v) = prefs.edit().putString("voice", v).apply()

    var careful: Boolean
        get() = prefs.getBoolean("careful", false)
        set(v) = prefs.edit().putBoolean("careful", v).apply()

    /** Keep very clear matches the person doesn't correct as extra examples (see [SelfTrain]). Off until the person turns it on. */
    var selfTrain: Boolean
        get() = prefs.getBoolean("self_train", false)
        set(v) = prefs.edit().putBoolean("self_train", v).apply()
    /** The Welcome screen has been read (Continue): only then is the camera permission asked for. */
    var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(v) = prefs.edit().putBoolean("welcomed", v).apply()

    var gaze: GazeModel?
        get() = runCatching {
            prefs.getString("gaze", null)?.let { s -> s.split(",").map { it.toDouble() }.let { GazeModel(it[0], it[1], it[2]) } }
        }.getOrNull()
        set(v) = prefs.edit().putString("gaze", v?.let { "${it.center},${it.left},${it.right}" }).apply()

    var switchModel: SwitchModel?
        get() = prefs.getString("switch", null)?.let { s ->
            runCatching {
                val a = JSONArray(s)
                SwitchModel(List(a.length()) { i ->
                    val c = a.getJSONObject(i)
                    SwitchChannel(c.getInt("index"), c.getString("name"), c.getDouble("rest"), c.getDouble("peak"), c.getDouble("z"))
                })
            }.getOrNull()
        }
        set(v) = prefs.edit().putString("switch", v?.let { m ->
            JSONArray(m.channels.map { c ->
                JSONObject().put("index", c.index).put("name", c.name).put("rest", c.rest).put("peak", c.peak).put("z", c.z)
            }).toString()
        }).apply()

    /** Free talk: the model chosen in Settings (avsr/models/<id>); null = the first one. */
    var freeTalkModel: String?
        get() = prefs.getString("freeTalkModel", null)
        set(v) = prefs.edit().putString("freeTalkModel", v).apply()

    /** Free talk: sentences this person confirmed (issue #5 A), offered again by the model's own score. Newest last. */
    var freeTalkSentences: List<String>
        get() = runCatching {
            prefs.getString("freeTalk", null)?.let { s -> org.json.JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }
        }.getOrNull() ?: emptyList()
        set(v) = prefs.edit().putString("freeTalk", org.json.JSONArray(v).toString()).apply()

    /** What Whisper heard each time the person said a phrase: their personal voice templates. */
    var voiceTemplates: Map<String, List<String>>
        get() = prefs.getString("voiceTemplates", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } }
            }.getOrNull()
        } ?: emptyMap()
        set(v) = prefs.edit().putString("voiceTemplates", JSONObject().apply { v.forEach { (k, xs) -> put(k, JSONArray(xs)) } }.toString()).apply()

    /** Words the family added ("I love you, Amma"): id -> English text. */
    var custom: List<Pair<String, String>>
        get() = prefs.getString("custom", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("text") } } } }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString("custom", JSONArray(v.map { (id, t) -> JSONObject().put("id", id).put("text", t) }).toString()).apply()

    var listenWith: String
        get() = text("listenWith", "lips")
        set(v) = prefs.edit().putString("listenWith", v).apply()

    // ---------------- calls ----------------

    /** Spoken in the intro on a call ("this is Ravi speaking through the Mouna app"). */
    var callerName: String
        get() = text("callerName", "")
        set(v) = prefs.edit().putString("callerName", v.trim()).apply()

    /** A Sarvam key typed in Settings; overrides the one built in from local.properties. Never logged. */
    var sarvamKey: String
        get() = text("sarvamKey", "")
        set(v) = prefs.edit().putString("sarvamKey", v.trim()).apply()

    /** Which AudioAttributes usage carries Mouna's voice into a call: "voice" or "media" (see [CallUsage]). */
    var callUsage: String
        get() = text("callUsage", "voice")
        set(v) = prefs.edit().putString("callUsage", v).apply()

    /** People the person calls most: name to number. */
    var favourites: List<Pair<String, String>>
        get() = prefs.getString("favourites", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("number") } } } }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString("favourites", JSONArray(v.map { (n, num) -> JSONObject().put("name", n).put("number", num) }).toString()).apply()

    /** The call relay typed in Settings; overrides the one built in from local.properties (blank means use that one). */
    var callServer: String
        get() = text("callServer", "")
        set(v) = prefs.edit().putString("callServer", v.trim()).apply()

    /** "phone" or "web" once the person has chosen; blank means pick by whether the phone has a SIM. */
    var callMode: String
        get() = text("callMode", "")
        set(v) = prefs.edit().putString("callMode", v).apply()

    /** People who keep a fixed web-call link ("Amma's link"): name to room id. */
    var webFavourites: List<Pair<String, String>>
        get() = prefs.getString("webFavourites", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("room") } } } }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit().putString("webFavourites", JSONArray(v.map { (n, r) -> JSONObject().put("name", n).put("room", r) }).toString()).apply()

    /**
     * The key for each favourite web room (room id to key). The relay names the first key that opens a room its owner,
     * so a favourite's fixed room is only ever reclaimed by this phone. Kept in app-private storage, never logged.
     */
    var webTokens: Map<String, String>
        get() = prefs.getString("webTokens", null)?.let { s ->
            runCatching { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull()
        } ?: emptyMap()
        set(v) = prefs.edit().putString("webTokens", JSONObject(v).toString()).apply()

    private fun json(xs: List<FloatArray>) = JSONArray(xs.map { x -> JSONArray(x.map { it.toDouble() }) })

    companion object {
        /**
         * The saved examples for [encoderId] in [dir]; none yet is an empty set. A file that won't parse is the person's
         * hours of teaching: it is moved aside as `<name>.corrupt-<time>` for rescue, never overwritten by the next save.
         */
        fun loadExamples(dir: File, encoderId: String, nowMs: Long = System.currentTimeMillis(), log: (String) -> Unit = {}): Examples {
            val f = File(dir, "examples-$encoderId.json")
            if (!f.exists()) return Examples(LinkedHashMap(), mutableListOf())
            return runCatching {
                val o = JSONObject(f.readText())
                val s = o.getJSONObject("samples")
                val samples = LinkedHashMap<String, MutableList<FloatArray>>()
                for (k in s.keys()) samples[k] = vectors(s.getJSONArray(k))
                Examples(samples, vectors(o.getJSONArray("negatives")))
            }.getOrElse {
                val aside = File(dir, "${f.name}.corrupt-$nowMs")
                val moved = runCatching { f.renameTo(aside) }.getOrDefault(false)
                log("examples file unreadable (${it.javaClass.simpleName}); ${if (moved) "kept as ${aside.name}" else "could not be moved aside"}")
                Examples(LinkedHashMap(), mutableListOf())
            }
        }

        private fun vectors(a: JSONArray): MutableList<FloatArray> = MutableList(a.length()) { i ->
            val v = a.getJSONArray(i)
            FloatArray(v.length()) { v.getDouble(it).toFloat() }
        }
    }
}
