package com.worldwake.game.game

import com.worldwake.game.world.Planet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON save. Written atomically by the view; a backup of the previous save is kept beside it. */
object SaveSystem {
    private const val VERSION = 1
    const val FILE = "worldwake_save.json"
    const val BACKUP = "worldwake_save.bak"

    fun saveFile(dir: File) = File(dir, FILE)
    fun exists(dir: File) = saveFile(dir).exists() || File(dir, BACKUP).exists()

    private fun invJson(inv: Inventory): JSONObject {
        val o = JSONObject()
        for (i in Item.entries) { val n = inv.count(i); if (n > 0) o.put(i.name, n) }
        return o
    }

    private fun readInv(o: JSONObject?, inv: Inventory) {
        inv.clear()
        if (o == null) return
        for (k in o.keys()) {
            val item = try { Item.valueOf(k) } catch (_: Throwable) { null } ?: continue
            inv.set(item, o.optInt(k))
        }
    }

    fun toJson(g: GameState): JSONObject {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("epoch", System.currentTimeMillis())
        o.put("time", g.time)
        o.put("heat", g.heat); o.put("press", g.press); o.put("oxy", g.oxy); o.put("bio", g.bio)
        o.put("stage", g.stageReached)
        o.put("tod", g.env.timeOfDay.toDouble())
        o.put("nextId", g.nextId)
        o.put("ended", g.ended)
        val p = g.player
        val pj = JSONObject()
        pj.put("x", p.pos.x.toDouble()); pj.put("y", p.pos.y.toDouble()); pj.put("z", p.pos.z.toDouble())
        pj.put("yaw", p.yaw.toDouble()); pj.put("pitch", p.pitch.toDouble())
        pj.put("o2", p.o2.toDouble()); pj.put("water", p.water.toDouble()); pj.put("food", p.food.toDouble()); pj.put("health", p.health.toDouble())
        pj.put("inv", invJson(p.inv))
        val ups = JSONArray()
        for (i in Item.entries) if (p.upgrades[i.ordinal]) ups.put(i.name)
        pj.put("upgrades", ups)
        o.put("player", pj)
        val arr = JSONArray()
        for (pl in g.placed) {
            val j = JSONObject()
            j.put("id", pl.id); j.put("t", pl.type.name)
            j.put("x", pl.x.toDouble()); j.put("y", pl.y.toDouble()); j.put("z", pl.z.toDouble()); j.put("yaw", pl.yaw.toDouble())
            if (pl.type == BuildType.POD) { j.put("gx", pl.gx); j.put("gz", pl.gz); j.put("door", pl.door) }
            if (pl.podId >= 0) j.put("pod", pl.podId)
            if (pl.timer > 0f) j.put("timer", pl.timer.toDouble())
            pl.ore?.let { j.put("ore", it.name) }
            pl.inv?.let { if (it.total > 0) j.put("inv", invJson(it)) }
            arr.put(j)
        }
        o.put("placed", arr)
        o.put("lander", invJson(g.landerInv))
        val loot = JSONObject()
        for ((id, inv) in g.lootInv) loot.put(id.toString(), invJson(inv))
        o.put("loot", loot)
        o.put("logs", JSONArray(g.logsRead.toList()))
        o.put("flags", JSONArray(g.flags.toList()))
        val hv = JSONObject()
        for ((k, v) in g.harvest.respawnAt) hv.put(k.toString(), v)
        o.put("harvest", hv)
        return o
    }

    /** Wake Index stored in the save, for the title screen backdrop. Returns -1 when there is no save. */
    fun peekStage(dir: File): Float {
        val o = read(dir) ?: return -1f
        return Planet.stageOf(o.optDouble("heat") + o.optDouble("press") + o.optDouble("oxy") + o.optDouble("bio"))
    }

    private fun read(dir: File): JSONObject? {
        for (name in arrayOf(FILE, BACKUP)) {
            val f = File(dir, name)
            if (!f.exists()) continue
            try { return JSONObject(f.readText()) } catch (_: Throwable) {}
        }
        return null
    }

    fun load(g: GameState, dir: File): Boolean {
        val o = read(dir) ?: return false
        return try { apply(g, o); true } catch (t: Throwable) { android.util.Log.e("Worldwake", "load failed", t); false }
    }

    fun apply(g: GameState, o: JSONObject) {
        g.time = o.optDouble("time", 0.0)
        g.heat = o.optDouble("heat", 0.0); g.press = o.optDouble("press", 0.0); g.oxy = o.optDouble("oxy", 0.0); g.bio = o.optDouble("bio", 0.0)
        g.stageReached = o.optInt("stage", 0)
        g.env.timeOfDay = o.optDouble("tod", 0.3).toFloat()
        g.nextId = o.optInt("nextId", 1)
        g.ended = o.optBoolean("ended", false)
        val p = g.player
        p.reset()
        val pj = o.getJSONObject("player")
        p.pos.set(pj.getDouble("x").toFloat(), pj.getDouble("y").toFloat(), pj.getDouble("z").toFloat())
        p.yaw = pj.optDouble("yaw", 0.0).toFloat(); p.pitch = pj.optDouble("pitch", 0.0).toFloat()
        val ups = pj.optJSONArray("upgrades")
        if (ups != null) for (i in 0 until ups.length()) { try { p.upgrades[Item.valueOf(ups.getString(i)).ordinal] = true } catch (_: Throwable) {} }
        p.inv.capacity = p.packSize
        readInv(pj.optJSONObject("inv"), p.inv)
        p.o2 = pj.optDouble("o2", 150.0).toFloat(); p.water = pj.optDouble("water", 100.0).toFloat()
        p.food = pj.optDouble("food", 100.0).toFloat(); p.health = pj.optDouble("health", 100.0).toFloat().coerceAtLeast(5f)
        g.placed.clear()
        val arr = o.optJSONArray("placed")
        if (arr != null) for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val type = try { BuildType.valueOf(j.getString("t")) } catch (_: Throwable) { null } ?: continue
            val pl = Placed(j.getInt("id"), type, j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("z").toFloat(), j.optDouble("yaw", 0.0).toFloat())
            pl.gx = j.optInt("gx"); pl.gz = j.optInt("gz"); pl.door = j.optInt("door")
            pl.podId = j.optInt("pod", -1)
            pl.timer = j.optDouble("timer", 0.0).toFloat()
            if (j.has("ore")) pl.ore = try { Item.valueOf(j.getString("ore")) } catch (_: Throwable) { null }
            pl.inv?.let { readInv(j.optJSONObject("inv"), it) }
            pl.born = -100.0
            g.placed.add(pl)
        }
        readInv(o.optJSONObject("lander"), g.landerInv)
        g.lootInv.clear()
        o.optJSONObject("loot")?.let { l -> for (k in l.keys()) { val inv = Inventory(60); readInv(l.optJSONObject(k), inv); g.lootInv[k.toInt()] = inv } }
        g.logsRead.clear()
        o.optJSONArray("logs")?.let { a -> for (i in 0 until a.length()) g.logsRead.add(a.getInt(i)) }
        g.flags.clear()
        o.optJSONArray("flags")?.let { a -> for (i in 0 until a.length()) g.flags.add(a.getString(i)) }
        g.harvest.respawnAt.clear()
        o.optJSONObject("harvest")?.let { h -> for (k in h.keys()) g.harvest.respawnAt[k.toLong()] = h.getDouble(k) }
        val epoch = o.optLong("epoch", System.currentTimeMillis())
        val elapsed = ((System.currentTimeMillis() - epoch) / 1000.0).coerceAtLeast(0.0)
        g.afterLoad(elapsed)
    }
}
