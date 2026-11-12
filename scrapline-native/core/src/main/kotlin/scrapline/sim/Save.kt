package scrapline.sim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import com.badlogic.gdx.utils.Base64Coder

private const val SAVE_MAGIC = 0x5343524c // "SCRL"
const val SAVE_VERSION = 3

/** Snapshot of a game as a Base64 string. Terrain, nodes and deposits are rebuilt from the seed. */
fun serialize(sim: Sim): String {
    val bytes = ByteArrayOutputStream()
    val o = DataOutputStream(bytes)
    o.writeInt(SAVE_MAGIC)
    o.writeInt(SAVE_VERSION)
    o.writeInt(sim.seed)
    o.writeInt(sim.tick)
    o.writeInt(sim.nights)
    val p = sim.player
    o.writeFloat(p.x); o.writeFloat(p.y); o.writeFloat(p.z); o.writeFloat(p.yaw); o.writeFloat(p.pitch)
    o.writeInt(sim.vitals.health); o.writeInt(sim.vitals.charge); o.writeInt(sim.vitals.warmth)
    o.writeInt(sim.selected); o.writeInt(sim.buildRot)
    writeContainer(o, sim.inventory)
    o.writeInt(sim.nodes.count)
    for (i in 0 until sim.nodes.count) o.writeByte(sim.nodes.hp[i])

    val es = sim.entities
    // Entities are written densely; rowOf maps old ids to their row for the logic nodes.
    val rowOf = IntArray(MAX_ENTITIES) { -1 }
    var rows = 0
    for (id in 1 until es.high) if (es.kind[id] != 0) rowOf[id] = rows++
    o.writeInt(rows)
    for (id in 1 until es.high) {
        if (es.kind[id] == 0) continue
        o.writeByte(es.kind[id]); o.writeShort(es.cx[id]); o.writeShort(es.cz[id]); o.writeByte(es.rot[id])
        o.writeInt(es.progress[id]); o.writeInt(es.fuel[id]); o.writeByte(es.enabled[id]); o.writeByte(es.active[id])
        for (s in 0 until BELT_SLOTS) o.writeShort(es.belt[id * BELT_SLOTS + s])
        val store = es.containers[id]
        o.writeBoolean(store != null)
        if (store != null) writeContainer(o, store)
    }

    val g = sim.graph
    var nodeCount = 0
    for (n in 1 until g.high) if (g.type[n] != NodeType.FREE) nodeCount++
    o.writeInt(nodeCount)
    for (n in 1 until g.high) {
        if (g.type[n] == NodeType.FREE) continue
        o.writeShort(n); o.writeByte(g.type[n])
        o.writeInt(if (g.type[n] == NodeType.DEVICE) rowOf[g.entity[n]] else -1)
        o.writeInt(g.param[n * 2]); o.writeInt(g.param[n * 2 + 1])
        o.writeInt(g.state[n * 2]); o.writeInt(g.state[n * 2 + 1])
        o.writeInt(g.bx[n]); o.writeInt(g.by[n])
        for (q in 0 until PORTS) o.writeInt(g.outCur[n * PORTS + q])
    }
    o.writeInt(g.wireCount)
    for (i in 0 until g.wireCount) o.writeInt(g.wires[i])
    o.flush()
    return String(Base64Coder.encode(bytes.toByteArray()))
}

/** Rebuilds a game from a snapshot. Returns null if the data is missing, damaged or from another version. */
fun deserialize(data: String?): Sim? {
    if (data.isNullOrEmpty()) return null
    return try {
        read(DataInputStream(ByteArrayInputStream(Base64Coder.decode(data))))
    } catch (e: Exception) {
        null
    }
}

private fun read(i: DataInputStream): Sim? {
    if (i.readInt() != SAVE_MAGIC || i.readInt() != SAVE_VERSION) return null
    val sim = Sim(i.readInt())
    sim.tick = i.readInt()
    sim.nights = i.readInt()
    val p = sim.player
    p.x = i.readFloat(); p.y = i.readFloat(); p.z = i.readFloat(); p.yaw = i.readFloat(); p.pitch = i.readFloat()
    sim.vitals.health = i.readInt(); sim.vitals.charge = i.readInt(); sim.vitals.warmth = i.readInt()
    sim.selected = i.readInt().coerceIn(0, HOTBAR_SIZE - 1); sim.buildRot = i.readInt() and 3
    if (!readContainer(i, sim.inventory)) return null
    if (i.readInt() != sim.nodes.count) return null
    for (n in 0 until sim.nodes.count) sim.nodes.hp[n] = i.readUnsignedByte()

    val es = sim.entities
    val rows = i.readInt()
    val idOfRow = IntArray(rows)
    for (row in 0 until rows) {
        val kind = i.readUnsignedByte()
        val cx = i.readUnsignedShort()
        val cz = i.readUnsignedShort()
        val rot = i.readUnsignedByte()
        val valid = kind in 1 until EntityKind.COUNT
        val id = if (valid) es.place(kind, cx, cz, rot, sim.world.heightAt(cx + 0.5f, cz + 0.5f)) else 0
        idOfRow[row] = id
        val progress = i.readInt()
        val fuel = i.readInt()
        val enabled = i.readUnsignedByte()
        val active = i.readUnsignedByte()
        val belt = IntArray(BELT_SLOTS) { i.readUnsignedShort() }
        val hasStore = i.readBoolean()
        val store = if (id != 0) es.containers[id] else null
        if (hasStore && !readContainer(i, store)) return null
        if (id == 0) continue
        es.progress[id] = progress
        es.fuel[id] = fuel
        es.enabled[id] = enabled
        es.active[id] = active
        for (s in 0 until BELT_SLOTS) es.belt[id * BELT_SLOTS + s] = belt[s]
    }

    // Logic nodes go back at their original ids so the packed wires stay valid.
    val g = sim.graph
    val nodeCount = i.readInt()
    for (k in 0 until nodeCount) {
        val n = i.readUnsignedShort()
        val type = i.readUnsignedByte()
        val row = i.readInt()
        val vals = IntArray(6 + PORTS) { i.readInt() }
        if (n !in 1 until MAX_NODES || type !in NodeType.DEVICE..NodeType.TIMER) continue
        val entity = if (type == NodeType.DEVICE) idOfRow.getOrElse(row) { 0 } else 0
        if (type == NodeType.DEVICE && entity == 0) continue
        g.type[n] = type
        g.entity[n] = entity
        g.param[n * 2] = vals[0]; g.param[n * 2 + 1] = vals[1]
        g.state[n * 2] = vals[2]; g.state[n * 2 + 1] = vals[3]
        g.bx[n] = vals[4]; g.by[n] = vals[5]
        for (q in 0 until PORTS) {
            g.outCur[n * PORTS + q] = vals[6 + q]
            g.outNext[n * PORTS + q] = vals[6 + q]
        }
        if (entity != 0) es.node[entity] = n
        if (n >= g.high) g.high = n + 1
    }
    val wireCount = i.readInt()
    for (k in 0 until wireCount) {
        val w = i.readInt()
        if (g.wireCount < MAX_WIRES && g.type[wireSrcNode(w)] != NodeType.FREE && g.type[wireDstNode(w)] != NodeType.FREE) {
            g.wires[g.wireCount++] = w
        }
    }
    g.afterLoad()
    return sim
}

private fun writeContainer(o: DataOutputStream, c: Container) {
    o.writeShort(c.size)
    for (s in 0 until c.size) {
        o.writeShort(c.ids[s]); o.writeShort(c.counts[s])
    }
}

/** Reads a container's slots. With `into` null the data is skipped. Returns false on a size mismatch. */
private fun readContainer(i: DataInputStream, into: Container?): Boolean {
    val size = i.readUnsignedShort()
    for (s in 0 until size) {
        val id = i.readUnsignedShort()
        val n = i.readUnsignedShort()
        if (into != null && s < into.size && id < ITEMS.size) {
            into.ids[s] = id
            into.counts[s] = n
        }
    }
    return into == null || into.size == size
}
