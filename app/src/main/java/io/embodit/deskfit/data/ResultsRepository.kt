package io.embodit.deskfit.data

import android.content.Context
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.SavedResult
import io.embodit.deskfit.domain.model.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Local store of the last five results and the two preferences (design `persistence`: offline, no account). */
interface ResultsRepository {
    fun loadResults(): List<SavedResult>

    /** Prepends [result]; only the newest [MAX] are kept. */
    fun saveResult(result: SavedResult)

    fun clearResults()

    fun loadSettings(default: Settings): Settings

    fun saveSettings(settings: Settings)

    companion object {
        const val MAX = 5
    }
}

/** SharedPreferences + JSON (org.json is part of the platform). */
class PrefsResultsRepository(context: Context) : ResultsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("deskfit", Context.MODE_PRIVATE)

    override fun loadResults(): List<SavedResult> =
        runCatching {
            val array = JSONArray(prefs.getString(KEY_RESULTS, null) ?: return emptyList())
            List(array.length()) { i -> array.getJSONObject(i).toResult() }
        }.getOrDefault(emptyList())

    override fun saveResult(result: SavedResult) {
        val all = (listOf(result) + loadResults()).take(ResultsRepository.MAX)
        val array = JSONArray()
        all.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RESULTS, array.toString()).apply()
    }

    override fun clearResults() {
        prefs.edit().remove(KEY_RESULTS).apply()
    }

    override fun loadSettings(default: Settings): Settings {
        val lang = prefs.getString(KEY_LANG, null)?.let { tag -> LanguagePref.entries.firstOrNull { it.tag == tag } } ?: default.language
        val unit = prefs.getString(KEY_UNIT, null)?.let { tag -> LengthUnit.entries.firstOrNull { it.tag == tag } } ?: default.unit
        return Settings(lang, unit)
    }

    override fun saveSettings(settings: Settings) {
        prefs.edit().putString(KEY_LANG, settings.language.tag).putString(KEY_UNIT, settings.unit.tag).apply()
    }

    private fun SavedResult.toJson(): JSONObject =
        JSONObject()
            .put("at", epochMs)
            .put("eye", eyeH.toDouble())
            .put("desk", deskH.toDouble())
            .put("forearm", forearmH.toDouble())
            .put("top", topH.toDouble())
            .put("dist", distance.toDouble())
            .put("side", sideDeg.toDouble())
            .put("chair", chairDelta.toDouble())
            .put("screen", screenDelta.toDouble())
            .put("distDelta", distanceDelta.toDouble())
            .put("off", offCentre)

    private fun JSONObject.toResult(): SavedResult =
        SavedResult(
            epochMs = getLong("at"),
            eyeH = getDouble("eye").toFloat(),
            deskH = getDouble("desk").toFloat(),
            forearmH = getDouble("forearm").toFloat(),
            topH = getDouble("top").toFloat(),
            distance = getDouble("dist").toFloat(),
            sideDeg = optDouble("side", 0.0).toFloat(),
            chairDelta = getDouble("chair").toFloat(),
            screenDelta = getDouble("screen").toFloat(),
            distanceDelta = getDouble("distDelta").toFloat(),
            offCentre = optBoolean("off", false),
        )

    private companion object {
        const val KEY_RESULTS = "results"
        const val KEY_LANG = "lang"
        const val KEY_UNIT = "unit"
    }
}
