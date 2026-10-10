package app.mouna.app.engine

import android.content.Context
import org.json.JSONObject

/**
 * How to make a sign, shown while teaching it: one real INCLUDE signer per sign as keypoints (assets/sign_demos.json,
 * models/isl/eval/demo_signs.py; INCLUDE is CC BY 4.0). No video is bundled.
 */
object SignDemos {
    /** One frame: 27 points (Isl.V order), x and y in shoulder widths around the shoulders' midpoint; null = hand not seen. */
    class Demo(val sign: String, val frames: List<Array<FloatArray?>>, val fps: Int)

    const val CREDIT = "Shown by a signer from INCLUDE (AI4Bharat, CC BY 4.0)"

    /** Mouna's phrase -> the INCLUDE sign shown for it. Phrases with no INCLUDE sign show none (water, pain, yes, no…). */
    val FOR_PHRASE = mapOf(
        "nurse" to "doctor", "medicine" to "medicine", "toilet" to "bathroom", "hot" to "hot", "cold" to "cold",
        "family" to "family", "thank_you" to "thank_you", "okay" to "alright",
    )

    @Volatile private var cache: Map<String, Demo>? = null

    fun forPhrase(context: Context, phraseId: String): Demo? = FOR_PHRASE[phraseId]?.let { all(context)[it] }

    fun all(context: Context): Map<String, Demo> = cache ?: runCatching {
        val o = JSONObject(context.assets.open("sign_demos.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val fps = o.optInt("fps", 15)
        val d = o.getJSONObject("demos")
        d.keys().asSequence().associateWith { k ->
            val s = d.getJSONObject(k)
            val fr = s.getJSONArray("frames")
            Demo(s.getString("sign"), List(fr.length()) { i ->
                val row = fr.getJSONArray(i)
                Array(row.length()) { v -> if (row.isNull(v)) null else row.getJSONArray(v).let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
            }, fps)
        }
    }.getOrDefault(emptyMap()).also { cache = it }
}
