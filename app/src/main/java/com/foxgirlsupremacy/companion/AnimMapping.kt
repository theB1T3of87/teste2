package com.foxgirlsupremacy.companion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class AnimMapping(
    private val prefs: SharedPreferences,
    private val modelId: String,
) {
    private val actions = mutableMapOf<String, MutableSet<String>>()
    private val weights = mutableMapOf<String, Int>()

    init {
        val raw = prefs.getString(key(), null)
        if (raw != null) runCatching {
            val obj = JSONObject(raw)
            for (animKey in obj.keys()) {
                val entry = obj.getJSONObject(animKey)
                val set = mutableSetOf<String>()
                entry.optJSONArray("a")?.let { arr ->
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                }
                actions[animKey] = set
                weights[animKey] = entry.optInt("w", 1)
            }
        }
    }

    fun actionsOf(animKey: String): Set<String> = actions[animKey] ?: emptySet()

    fun weightOf(animKey: String): Int = weights[animKey] ?: 1

    fun toggle(animKey: String, action: String) {
        val set = actions.getOrPut(animKey) { mutableSetOf() }
        if (!set.add(action)) set.remove(action)
        save()
    }

    fun setWeight(animKey: String, weight: Int) {
        weights[animKey] = weight.coerceIn(1, MAX_WEIGHT)
        save()
    }

    fun animsForAction(action: String): List<Pair<String, Int>> =
        actions.filter { action in it.value }.map { it.key to weightOf(it.key) }

    private fun save() {
        val obj = JSONObject()
        for ((animKey, set) in actions) {
            if (set.isEmpty()) continue
            obj.put(
                animKey,
                JSONObject()
                    .put("a", JSONArray(set.toList()))
                    .put("w", weightOf(animKey)),
            )
        }
        prefs.edit().putString(key(), obj.toString()).apply()
    }

    private fun key() = "anim_map_$modelId"

    companion object {
        const val MAX_WEIGHT = 5

        val ACTIONS = linkedMapOf(
            "IDLE" to "Repos",
            "PET" to "Caresse",
            "FEED" to "Nourrir",
            "TALK" to "Parler",
        )

        fun motionKey(group: String, index: Int) = "m¦$group¦$index"
        fun expressionKey(name: String) = "e¦$name"
    }
}
