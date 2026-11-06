package com.hadal.game.game

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON save game. Written atomically with a backup copy (see GameView). */
object SaveSystem {
    const val VERSION = 1

    fun saveFile(dir: File) = File(dir, "hadal_save.json")
    fun backupFile(dir: File) = File(dir, "hadal_save.bak")
    fun exists(dir: File) = saveFile(dir).exists() || backupFile(dir).exists()

    private fun ints(a: IntArray) = JSONArray().also { j -> a.forEach { j.put(it) } }
    private fun readInts(j: JSONArray?, out: IntArray) { if (j == null) return; for (i in 0 until minOf(j.length(), out.size)) out[i] = j.optInt(i) }
    private fun intSet(s: Set<Int>) = JSONArray().also { j -> s.forEach { j.put(it) } }
    private fun readIntSet(j: JSONArray?, out: MutableSet<Int>) { out.clear(); if (j == null) return; for (i in 0 until j.length()) out.add(j.optInt(i)) }

    fun toJson(g: GameState): JSONObject {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("playTime", g.playTime)
        o.put("cargo", ints(g.cargo)); o.put("storage", ints(g.storage)); o.put("upgrades", ints(g.upgrades))
        o.put("decor", JSONArray().also { j -> g.decor.forEach { j.put(it) } })
        o.put("biodex", JSONArray().also { j -> g.biodex.forEach { j.put(it.name) } })
        o.put("discovered", intSet(g.discovered))
        val s = g.sub
        o.put("sub", JSONObject().put("x", s.pos.x.toDouble()).put("y", s.pos.y.toDouble()).put("z", s.pos.z.toDouble())
            .put("yaw", s.yaw.toDouble()).put("hull", s.hull.toDouble()).put("power", s.power.toDouble()).put("oxygen", s.oxygen.toDouble())
            .put("flares", s.flares).put("kits", s.repairKits).put("lamp", s.lampOn))
        o.put("docked", g.docked)
        val st = g.story
        val baselines = JSONObject()
        for (i in Item.entries) baselines.put(i.name, st.baseline(i))
        o.put("story", JSONObject().put("chapter", st.chapter).put("step", st.step).put("finished", st.finished).put("choice", st.choice)
            .put("relay", st.relayFixed).put("undocked", st.undocked).put("deepest", st.deepest.toDouble())
            .put("logsFound", intSet(st.logsFound)).put("logsRead", intSet(st.logsRead)).put("beacons", intSet(st.beacons)).put("echoes", intSet(st.echoes))
            .put("collected", ints(st.collected)).put("baselines", baselines))
        val hv = JSONObject()
        for ((k, v) in g.harvest.respawnAt) hv.put(k.toString(), v)
        o.put("harvest", hv)
        o.put("pale", JSONObject().put("encounters", g.ecology.paleEncounters))
        o.put("bases", JSONObject().put("kits", g.outpostKits).put("active", g.activeBase)
            .put("outposts", JSONArray().also { arr -> for (op in g.outposts) arr.put(JSONArray().put(op.base.x.toDouble()).put(op.base.z.toDouble())) }))
        o.put("drones", JSONObject().put("count", g.fleet.drones.size).put("levels", ints(g.fleet.levels)).put("focus", ints(IntArray(g.fleet.drones.size) { g.fleet.drones[it].focus?.ordinal ?: -1 })))
        return o
    }

    fun load(g: GameState, dir: File): Boolean {
        for (f in listOf(saveFile(dir), backupFile(dir))) {
            if (!f.exists()) continue
            try { apply(g, JSONObject(f.readText())); return true } catch (t: Throwable) { android.util.Log.e("Hadal", "save load failed: ${f.name}", t) }
        }
        return false
    }

    fun apply(g: GameState, o: JSONObject) {
        g.playTime = o.optDouble("playTime", 0.0)
        readInts(o.optJSONArray("cargo"), g.cargo); readInts(o.optJSONArray("storage"), g.storage); readInts(o.optJSONArray("upgrades"), g.upgrades)
        o.optJSONArray("decor")?.let { j -> for (i in 0 until minOf(j.length(), g.decor.size)) g.decor[i] = j.optBoolean(i) }
        g.biodex.clear()
        o.optJSONArray("biodex")?.let { j -> for (i in 0 until j.length()) Species.entries.firstOrNull { it.name == j.optString(i) }?.let { g.biodex.add(it) } }
        readIntSet(o.optJSONArray("discovered"), g.discovered)
        val s = g.sub
        s.applyUpgrades(g.upgrades)
        o.optJSONObject("sub")?.let { j ->
            s.pos.set(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("z").toFloat())
            s.yaw = j.optDouble("yaw").toFloat(); s.bodyYaw = s.yaw
            s.hull = j.optDouble("hull", 100.0).toFloat(); s.power = j.optDouble("power", 100.0).toFloat(); s.oxygen = j.optDouble("oxygen", 240.0).toFloat()
            s.flares = j.optInt("flares"); s.repairKits = j.optInt("kits"); s.lampOn = j.optBoolean("lamp", true)
        }
        val st = g.story
        o.optJSONObject("story")?.let { j ->
            st.chapter = j.optInt("chapter"); st.step = j.optInt("step"); st.finished = j.optBoolean("finished"); st.choice = j.optInt("choice")
            st.relayFixed = j.optBoolean("relay"); st.undocked = j.optBoolean("undocked"); st.deepest = j.optDouble("deepest").toFloat()
            readIntSet(j.optJSONArray("logsFound"), st.logsFound); readIntSet(j.optJSONArray("logsRead"), st.logsRead)
            readIntSet(j.optJSONArray("beacons"), st.beacons); readIntSet(j.optJSONArray("echoes"), st.echoes)
            readInts(j.optJSONArray("collected"), st.collected)
            j.optJSONObject("baselines")?.let { b -> for (i in Item.entries) if (b.has(i.name)) st.restoreBaseline(i, b.optInt(i.name)) }
            st.started = true
        }
        g.harvest.respawnAt.clear()
        o.optJSONObject("harvest")?.let { j -> for (k in j.keys()) g.harvest.respawnAt[k.toLong()] = j.optDouble(k) }
        g.ecology.paleEncounters = o.optJSONObject("pale")?.optInt("encounters") ?: 0
        g.resetBases()
        o.optJSONObject("bases")?.let { j ->
            j.optJSONArray("outposts")?.let { arr -> for (i in 0 until minOf(arr.length(), OutpostRules.MAX_OUTPOSTS)) { val a = arr.optJSONArray(i) ?: continue; g.addOutpost(a.optDouble(0).toFloat(), a.optDouble(1).toFloat()) } }
            g.outpostKits = j.optInt("kits").coerceIn(0, OutpostRules.MAX_OUTPOSTS)
            g.activeBase = j.optInt("active").coerceIn(0, g.bases.size - 1)
        }
        g.fleet.reset()
        o.optJSONObject("drones")?.let { j ->
            readInts(j.optJSONArray("levels"), g.fleet.levels)
            val fo = j.optJSONArray("focus")
            for (i in 0 until j.optInt("count").coerceIn(0, g.maxDrones)) {
                val d = g.fleet.addDrone()
                d.focus = fo?.optInt(i, -1)?.let { Item.entries.getOrNull(it) }
            }
        }
        g.started = true
        // always resume safely at the station: the recall drone brings the sub home between sessions
        g.placeAtDock()
        g.sub.hull = g.sub.hull.coerceAtLeast(g.sub.maxHull * 0.5f)
    }
}
