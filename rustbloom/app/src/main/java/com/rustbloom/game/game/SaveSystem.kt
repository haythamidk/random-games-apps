package com.rustbloom.game.game

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON save/load of the full game state. */
object SaveSystem {
    const val VERSION = 1

    fun saveFile(dir: File) = File(dir, "rustbloom_save.json")

    fun exists(dir: File) = saveFile(dir).exists()

    fun summary(dir: File): String? {
        return try {
            val o = JSONObject(saveFile(dir).readText())
            val day = o.getJSONObject("env").getInt("day")
            val lvl = o.getJSONObject("player").getInt("level")
            val q = o.optInt("questIndex", 0)
            val title = Story.quests.getOrNull(q)?.title ?: "Endless Wilds"
            "Day $day  -  Level $lvl  -  $title"
        } catch (t: Throwable) { null }
    }

    private fun stackJson(s: ItemStack?): Any = if (s == null) JSONObject.NULL else JSONObject().put("i", s.item.name).put("c", s.count).put("d", s.durability)

    private fun readStack(o: Any?): ItemStack? {
        if (o == null || o == JSONObject.NULL || o !is JSONObject) return null
        val item = try { Item.valueOf(o.getString("i")) } catch (e: Exception) { return null }
        return ItemStack(item, o.getInt("c"), o.optInt("d", item.durability))
    }

    private fun invJson(inv: Inventory): JSONArray {
        val a = JSONArray()
        for (s in inv.slots) a.put(stackJson(s))
        return a
    }

    private fun readInv(inv: Inventory, a: JSONArray?) {
        if (a == null) return
        for (i in 0 until minOf(a.length(), inv.capacity)) inv.slots[i] = readStack(a.opt(i))
    }

    fun toJson(g: GameState): JSONObject {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("difficulty", g.difficulty.name)
        o.put("playTime", g.playTime)
        val p = g.player
        o.put("player", JSONObject().apply {
            put("x", p.pos.x.toDouble()); put("y", p.pos.y.toDouble()); put("z", p.pos.z.toDouble())
            put("yaw", p.yaw.toDouble()); put("pitch", p.pitch.toDouble())
            put("hp", p.hp.toDouble()); put("hunger", p.hunger.toDouble()); put("thirst", p.thirst.toDouble())
            put("stamina", p.stamina.toDouble()); put("temp", p.feltTemp.toDouble())
            put("xp", p.xp); put("level", p.level); put("sp", p.skillPoints)
            put("skills", JSONArray().apply { for (s in Skill.entries) put(p.skills[s.ordinal]) })
            put("selected", p.selected); put("ammo", p.ammo.name)
            put("spawnX", p.spawnX.toDouble()); put("spawnZ", p.spawnZ.toDouble()); put("bed", p.bedId)
            put("bonusHp", p.bonusHp.toDouble()); put("bonusWarmth", p.bonusWarmth.toDouble())
            put("thirstPerk", p.thirstPerk.toDouble()); put("hungerPerk", p.hungerPerk.toDouble())
            put("invSize", p.inv.size)
            put("inv", invJson(p.inv))
            put("armor", stackJson(p.inv.armor))
        })
        o.put("env", JSONObject().apply {
            put("time", g.env.time.toDouble()); put("day", g.env.day)
            put("weather", g.env.weather.name); put("nextWeather", g.env.nextWeatherIn.toDouble())
        })
        o.put("questIndex", g.questIndex)
        o.put("counters", JSONObject().apply { for ((k, v) in g.counters) put(k, v) })
        o.put("baselines", JSONObject().apply { for ((k, v) in g.baselines) put(k, v) })
        o.put("spires", JSONArray(g.spiresActive.toList()))
        o.put("logs", JSONArray(g.logsRead.toList()))
        o.put("caches", JSONArray(g.cachesOpened.toList()))
        o.put("discovered", JSONArray(g.discovered.toList()))
        o.put("wrecks", JSONObject().apply { for ((k, v) in g.wreckCooldown) put(k.toString(), v) })
        o.put("guards", JSONObject().apply { for ((k, v) in g.guardianKilledAt) put(k.toString(), v) })
        o.put("bossDefeated", g.bossDefeated)
        o.put("tinker", g.tinkerUnlocked)
        o.put("harvest", JSONObject().apply { for ((k, v) in g.harvest.respawnAt) put(k.toString(), v) })
        o.put("placed", JSONArray().apply {
            for (pl in g.placed) put(JSONObject().apply {
                put("id", pl.id); put("t", pl.type.name)
                put("x", pl.x.toDouble()); put("y", pl.y.toDouble()); put("z", pl.z.toDouble()); put("r", pl.rot)
                put("w", pl.water)
                pl.storage?.let { put("inv", invJson(it)) }
            })
        })
        o.put("bountyDay", g.bountyDay)
        o.put("bounties", JSONArray().apply {
            for (b in g.bounties) put(JSONObject().apply {
                put("title", b.title); put("kill", b.kill?.name ?: ""); put("deliver", b.deliver?.name ?: "")
                put("count", b.count); put("xp", b.xp); put("progress", b.progress); put("claimed", b.claimed)
                put("reward", JSONArray().apply { for ((i, n) in b.reward) put(JSONObject().put("i", i.name).put("n", n)) })
            })
        })
        return o
    }

    fun fromJson(g: GameState, o: JSONObject) {
        g.resetProgress()
        g.difficulty = try { Difficulty.valueOf(o.optString("difficulty", "NORMAL")) } catch (e: Exception) { Difficulty.NORMAL }
        g.playTime = o.optDouble("playTime", 0.0)
        val pj = o.getJSONObject("player")
        val p = g.player
        p.pos.set(pj.getDouble("x").toFloat(), pj.getDouble("y").toFloat(), pj.getDouble("z").toFloat())
        p.yaw = pj.optDouble("yaw", 0.0).toFloat(); p.pitch = pj.optDouble("pitch", 0.0).toFloat()
        p.hunger = pj.optDouble("hunger", 100.0).toFloat(); p.thirst = pj.optDouble("thirst", 100.0).toFloat()
        p.stamina = pj.optDouble("stamina", 100.0).toFloat(); p.feltTemp = pj.optDouble("temp", 18.0).toFloat()
        p.xp = pj.optInt("xp"); p.level = pj.optInt("level", 1); p.skillPoints = pj.optInt("sp")
        pj.optJSONArray("skills")?.let { a -> for (i in 0 until minOf(a.length(), p.skills.size)) p.skills[i] = a.getInt(i) }
        p.selected = pj.optInt("selected")
        p.ammo = try { Item.valueOf(pj.optString("ammo", "ARROW")) } catch (e: Exception) { Item.ARROW }
        p.spawnX = pj.optDouble("spawnX", p.pos.x.toDouble()).toFloat(); p.spawnZ = pj.optDouble("spawnZ", p.pos.z.toDouble()).toFloat()
        p.bedId = pj.optInt("bed", -1)
        p.bonusHp = pj.optDouble("bonusHp", 0.0).toFloat(); p.bonusWarmth = pj.optDouble("bonusWarmth", 0.0).toFloat()
        p.thirstPerk = pj.optDouble("thirstPerk", 1.0).toFloat(); p.hungerPerk = pj.optDouble("hungerPerk", 1.0).toFloat()
        p.inv.size = pj.optInt("invSize", 36)
        readInv(p.inv, pj.optJSONArray("inv"))
        p.inv.armor = readStack(pj.opt("armor"))
        p.hp = pj.optDouble("hp", 100.0).toFloat().coerceIn(1f, p.maxHp)

        val ej = o.getJSONObject("env")
        g.env.time = ej.optDouble("time", 8.0).toFloat()
        g.env.day = ej.optInt("day", 1)
        g.env.forceWeather(try { Weather.valueOf(ej.optString("weather", "CLEAR")) } catch (e: Exception) { Weather.CLEAR })
        g.env.nextWeatherIn = ej.optDouble("nextWeather", 6.0).toFloat()

        g.questIndex = o.optInt("questIndex")
        o.optJSONObject("counters")?.let { c -> for (k in c.keys()) g.counters[k] = c.getInt(k) }
        o.optJSONObject("baselines")?.let { c -> for (k in c.keys()) g.baselines[k] = c.getInt(k) }
        o.optJSONArray("spires")?.let { a -> for (i in 0 until a.length()) g.spiresActive.add(a.getString(i)) }
        o.optJSONArray("logs")?.let { a -> for (i in 0 until a.length()) g.logsRead.add(a.getInt(i)) }
        o.optJSONArray("caches")?.let { a -> for (i in 0 until a.length()) g.cachesOpened.add(a.getInt(i)) }
        o.optJSONArray("discovered")?.let { a -> for (i in 0 until a.length()) g.discovered.add(a.getInt(i)) }
        o.optJSONObject("wrecks")?.let { c -> for (k in c.keys()) g.wreckCooldown[k.toInt()] = c.getDouble(k) }
        o.optJSONObject("guards")?.let { c -> for (k in c.keys()) g.guardianKilledAt[k.toInt()] = c.getDouble(k) }
        g.bossDefeated = o.optBoolean("bossDefeated")
        g.tinkerUnlocked = o.optBoolean("tinker")
        o.optJSONObject("harvest")?.let { c -> for (k in c.keys()) g.harvest.respawnAt[k.toLong()] = c.getDouble(k) }
        o.optJSONArray("placed")?.let { a ->
            for (i in 0 until a.length()) {
                val pj2 = a.getJSONObject(i)
                val t = try { BuildType.valueOf(pj2.getString("t")) } catch (e: Exception) { continue }
                val pl = Placed(pj2.getInt("id"), t, pj2.getDouble("x").toFloat(), pj2.getDouble("y").toFloat(), pj2.getDouble("z").toFloat(), pj2.optInt("r"))
                pl.water = pj2.optInt("w")
                pl.storage?.let { readInv(it, pj2.optJSONArray("inv")) }
                g.placed.add(pl)
                g.bumpPlacedId(pl.id)
            }
        }
        g.bountyDay = o.optInt("bountyDay", -1)
        o.optJSONArray("bounties")?.let { a ->
            for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                val kill = b.optString("kill").let { if (it.isEmpty()) null else try { CreatureType.valueOf(it) } catch (e: Exception) { null } }
                val del = b.optString("deliver").let { if (it.isEmpty()) null else try { Item.valueOf(it) } catch (e: Exception) { null } }
                val rw = ArrayList<Pair<Item, Int>>()
                b.optJSONArray("reward")?.let { r -> for (j in 0 until r.length()) { val ro = r.getJSONObject(j); try { rw.add(Item.valueOf(ro.getString("i")) to ro.getInt("n")) } catch (_: Exception) {} } }
                val bb = Bounty(b.getString("title"), kill, del, b.getInt("count"), b.getInt("xp"), rw)
                bb.progress = b.optInt("progress"); bb.claimed = b.optBoolean("claimed")
                g.bounties.add(bb)
            }
        }
        g.rebuildColliders()
        g.started = true
        g.worldReady = false
        val q = g.quest
        if (q != null) g.queueDialog(listOf(q.dialog.lastOrNull() ?: ""))
    }

    fun save(g: GameState, dir: File): Boolean {
        return try {
            val json = toJson(g).toString()
            val tmp = File(dir, "rustbloom_save.tmp")
            tmp.writeText(json)
            val f = saveFile(dir)
            if (f.exists()) f.copyTo(File(dir, "rustbloom_save.bak"), overwrite = true)
            tmp.renameTo(f)
        } catch (t: Throwable) {
            android.util.Log.e("Rustbloom", "save failed", t)
            false
        }
    }

    fun load(g: GameState, dir: File): Boolean {
        val f = saveFile(dir)
        val candidates = listOf(f, File(dir, "rustbloom_save.bak"))
        for (c in candidates) {
            if (!c.exists()) continue
            try {
                fromJson(g, JSONObject(c.readText()))
                return true
            } catch (t: Throwable) {
                android.util.Log.e("Rustbloom", "load failed for ${c.name}", t)
            }
        }
        return false
    }
}
