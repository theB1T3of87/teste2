package com.foxgirlsupremacy.companion

import android.content.Context
import org.json.JSONObject
import java.io.File

object Live2DModels {

    data class Entry(
        val id: String,

        val dir: String,

        val jsonFile: String,
    )

    private const val ROOT = "live2d"

    fun importRoot(context: Context): File = File(context.filesDir, ROOT)

    fun list(context: Context): List<Entry> {
        val result = LinkedHashMap<String, Entry>()

        val assets = context.applicationContext.assets
        assets.list(ROOT)?.sorted()?.forEach { folder ->
            val files = assets.list("$ROOT/$folder") ?: return@forEach
            val json = files.firstOrNull { it.endsWith(".model3.json") } ?: return@forEach
            result[folder] = Entry(folder, "$ROOT/$folder/", json)
        }

        scanImported(importRoot(context), result, MAX_SCAN_DEPTH)

        return result.values.toList()
    }

    private fun scanImported(dir: File, out: LinkedHashMap<String, Entry>, depth: Int) {
        val children = dir.listFiles() ?: return
        val json = children.firstOrNull { it.name.endsWith(".model3.json") }
        if (json != null) {
            out[dir.name] = Entry(dir.name, dir.absolutePath + "/", json.name)
            return
        }
        if (depth <= 0) return
        children.filter { it.isDirectory }.sortedBy { it.name }.forEach {
            scanImported(it, out, depth - 1)
        }
    }

    private const val MAX_SCAN_DEPTH = 5

    fun resolve(context: Context, id: String?): Entry? {
        val all = list(context)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    data class MotionRef(val group: String, val index: Int, val label: String)

    data class Inventory(val motions: List<MotionRef>, val expressions: List<String>)

    fun inventory(context: Context, entry: Entry): Inventory {
        val motions = mutableListOf<MotionRef>()
        val expressions = mutableListOf<String>()
        try {
            val root = JSONObject(String(readBytes(context, entry.dir + entry.jsonFile)))
            val fr = root.getJSONObject("FileReferences")

            fr.optJSONObject("Motions")?.let { groups ->
                for (group in groups.keys()) {
                    val arr = groups.getJSONArray(group)
                    for (i in 0 until arr.length()) {
                        val label = if (group.isEmpty()) "Idle ${i + 1}" else "$group ${i + 1}"
                        motions.add(MotionRef(group, i, label))
                    }
                }
            }
            fr.optJSONArray("Expressions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    expressions.add(arr.getJSONObject(i).getString("Name"))
                }
            }
        } catch (_: Exception) {
        }
        return Inventory(motions, expressions)
    }

    private fun readBytes(context: Context, path: String): ByteArray {
        return if (path.startsWith("/")) {
            File(path).readBytes()
        } else {
            context.applicationContext.assets.open(path).use { it.readBytes() }
        }
    }
}
