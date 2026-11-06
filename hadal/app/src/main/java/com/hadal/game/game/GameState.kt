package com.hadal.game.game

import com.hadal.game.engine.DEG2RAD
import com.hadal.game.engine.Vec3
import com.hadal.game.engine.angleDiff
import com.hadal.game.engine.clamp
import com.hadal.game.engine.clamp01
import com.hadal.game.engine.smoothstep
import com.hadal.game.input.Action
import com.hadal.game.input.InputState
import com.hadal.game.world.ChunkManager
import com.hadal.game.world.FloraType
import com.hadal.game.world.HarvestRegistry
import com.hadal.game.world.InteractKind
import com.hadal.game.world.Node
import com.hadal.game.world.PoiType
import com.hadal.game.world.SeaGen
import com.hadal.game.world.Structures
import com.hadal.game.world.Zone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Whole simulation. Updated on the GL thread under synchronized(this); UI reads snapshots. */
class GameState(val settings: Settings) {
    @Volatile var sound: SoundSink = NoSound
    val sub = Sub()
    val harvest = HarvestRegistry()
    val chunks = ChunkManager(harvest)
    val ecology = Ecology(this)
    val story = Story()
    val fleet = DroneFleet(this)

    /** Items carried in the sub (lost on death) and stored at the station. */
    val cargo = IntArray(Item.entries.size)
    val storage = IntArray(Item.entries.size)
    val upgrades = IntArray(Upgrade.entries.size)
    val decor = BooleanArray(Decor.entries.size)
    val biodex = LinkedHashSet<Species>()
    val discovered = HashSet<Int>()
    val flares = ArrayList<Flare>()

    var time = 0f
    var playTime = 0.0
    @Volatile var paused = false
    private val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** Runs [r] on the game thread before the next update. */
    fun post(r: () -> Unit) { tasks.add(r) }
    var docked = true
    var started = false
    var worldReady = false
    @Volatile var saveRequested = false
    var dead = false
    var deathTimer = 0f
    var ending = false
    var endingRequested = false
    var zone = Zone.SHELF
    var depth = 0f
    var floorY = -60f

    // ui hooks (called on the GL thread; UI posts to its own thread)
    @Volatile var onDocked: (() -> Unit)? = null
    @Volatile var onDeath: (() -> Unit)? = null
    @Volatile var onEnding: (() -> Unit)? = null
    @Volatile var onVibrate: ((Int) -> Unit)? = null

    // camera for the renderer
    val camPos = Vec3()
    val camTarget = Vec3()
    var camShake = 0f

    // feedback
    var damageFlash = 0f
    var pulseFlash = 0f
    var paleFear = 0f
    private val messages = ArrayDeque<Message>()
    private var message: Message? = null
    private var messageT = 0f
    private var toast = ""
    private var toastT = 0f
    var scanTarget: Creature? = null
    var scanProgress = 0f
    private var scanTick = 0f
    var useLabel = ""
    private var useNode: Node? = null
    private var useInteract: com.hadal.game.world.Interact? = null

    // sonar
    var pingAge = 99f
    var pingRadius = 0f
    val pingOrigin = Vec3()
    private class Blip(val x: Float, val y: Float, val z: Float, val kind: Int, val delay: Float)
    private val blips = ArrayList<Blip>()

    private val frame = InputState.Frame()
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private var autosave = 0f
    private var respawnTimer = 0f
    private var creakTimer = 0f
    private var alarmTimer = 0f
    private var heartbeat = 0f
    private val amb = Ambience()
    val hud = HudData()
    var fps = 0

    init {
        sub.applyUpgrades(upgrades)
    }

    // ------------------------------------------------------------------ setup

    fun newGame() {
        cargo.fill(0); storage.fill(0); upgrades.fill(0); decor.fill(false)
        biodex.clear(); discovered.clear(); flares.clear(); harvest.respawnAt.clear(); fleet.reset()
        storage[Item.SCRAP.ordinal] = 2
        sub.hull = 100f; sub.power = 100f; sub.oxygen = 240f; sub.flares = 0; sub.repairKits = 1
        sub.applyUpgrades(upgrades)
        sub.hull = sub.maxHull; sub.power = sub.maxPower; sub.oxygen = sub.maxOxygen
        story.chapter = 0; story.step = 0; story.started = false; story.finished = false
        playTime = 0.0
        placeAtDock()
        started = true
    }

    fun placeAtDock() {
        Structures.buildAll()
        sub.pos.set(Structures.dockX, Structures.dockY - 3.5f, Structures.dockZ)
        sub.vel.set(0f, 0f, 0f)
        sub.yaw = 0f; sub.pitch = -0.15f; sub.bodyYaw = 0f; sub.bodyPitch = 0f
        docked = true
        ecology.clear()
        updateCamera(1f, snap = true)
    }

    fun requestSave() { saveRequested = true }

    fun say(m: Message) { messages.addLast(m) }
    fun toast(t: String) { toast = t; toastT = 3f }

    fun count(i: Item) = cargo[i.ordinal]
    fun cargoUsed(): Int {
        var n = 0
        for (i in Item.entries) if (i != Item.FLARE && i != Item.REPAIR_KIT && i != Item.DATA_CORE) n += cargo[i.ordinal]
        return n
    }

    fun nearestFlare(p: Vec3, range: Float): Flare? {
        var best: Flare? = null; var bd = range
        for (f in flares) { val d = f.pos.dist(p); if (d < bd) { bd = d; best = f } }
        return best
    }

    // ------------------------------------------------------------------ main update

    fun update(dt: Float, input: InputState) {
        while (true) { val t = tasks.poll() ?: break; try { t() } catch (e: Throwable) { android.util.Log.e("Hadal", "task", e) } }
        input.poll(frame)
        time += dt
        if (!started) { sub.yaw += dt * 0.04f; sub.pitch = 0.12f; updateCamera(dt); return }
        if (paused) {
            amb.paused = true; amb.menu = true; sound.setAmbience(amb)
            fillHud(dt)
            return
        }
        chunks.now = playTime
        if (!worldReady) {
            worldReady = chunks.ready(sub.pos.x, sub.pos.z) && Structures.built
            updateCamera(dt, snap = true)
            fillHud(dt)
            return
        }
        playTime += dt
        if (Structures.built) fleet.update(dt)
        if (dead) {
            deathTimer += dt
            sub.vel.scale(0.98f); sub.pos.y -= dt * 1.5f
            fillHud(dt)
            return
        }
        val diff = settings.difficulty

        // look
        val sens = settings.lookSensitivity
        val inv = if (settings.invertY) -1f else 1f
        sub.yaw -= frame.lookX * DEG2RAD * sens + frame.padLookX * 140f * DEG2RAD * sens * dt
        sub.pitch = clamp(sub.pitch - (frame.lookY * DEG2RAD * sens + frame.padLookY * 110f * DEG2RAD * sens * dt) * inv, -1.35f, 1.35f)

        for (a in frame.actions) handleAction(a)

        if (docked) {
            sub.power = sub.maxPower; sub.oxygen = sub.maxOxygen
            sub.hull = min(sub.maxHull, sub.hull + dt * 8f)
            sub.vel.set(0f, 0f, 0f)
            sub.pos.set(Structures.dockX, Structures.dockY - 3.5f, Structures.dockZ)
            sub.bodyYaw += angleDiff(sub.bodyYaw, sub.yaw) * min(1f, dt * 2f)
            updateCamera(dt)
            story.update(this)
            autosave += dt
            if (autosave > 30f) { autosave = 0f; requestSave() }
            updateAmbience(dt)
            fillHud(dt)
            return
        }

        // ---------------- movement
        val f = sub.forward(tmp)
        val r = sub.right(tmp2)
        val lowPow = sub.power <= 0.01f
        val boosting = frame.boost && !lowPow && (frame.moveY > 0.2f || abs(frame.vertical) > 0.2f)
        sub.boosting = boosting
        var maxSpeed = sub.thrust * (if (boosting) 1.65f else 1f) * (if (lowPow) 0.35f else 1f)
        val dx = f.x * frame.moveY + r.x * frame.moveX * 0.7f
        var dy = f.y * frame.moveY + frame.vertical * 0.85f
        val dz = f.z * frame.moveY + r.z * frame.moveX * 0.7f
        // pressure slows the ascent so the surface is not a free escape
        if (dy > 0f && sub.pos.y > -2.5f) dy = 0f
        val accel = if (abs(frame.moveX) + abs(frame.moveY) + abs(frame.vertical) > 0.05f) 1.3f else 0.55f
        val k = min(1f, accel * dt)
        sub.vel.x += (dx * maxSpeed - sub.vel.x) * k
        sub.vel.y += (dy * maxSpeed - sub.vel.y) * k
        sub.vel.z += (dz * maxSpeed - sub.vel.z) * k
        // gentle current on the shelf
        if (zone == Zone.SHELF) { sub.vel.x += sin(time * 0.05f) * 0.04f * dt; sub.vel.z += cos(time * 0.04f) * 0.03f * dt }
        val before = sub.vel.length()
        sub.pos.addScaled(sub.vel, dt)
        collide(dt, before)
        sub.speed = sub.vel.length()

        // body orientation follows the look direction with a little banking
        val dyaw = angleDiff(sub.bodyYaw, sub.yaw)
        sub.bodyYaw += dyaw * min(1f, dt * 3f)
        sub.bodyPitch += (sub.pitch * 0.7f - sub.bodyPitch) * min(1f, dt * 2.5f)
        sub.bodyRoll += (clamp(-dyaw * 1.4f - frame.moveX * 0.15f, -0.5f, 0.5f) - sub.bodyRoll) * min(1f, dt * 2f)

        // ---------------- resources
        val thrustLoad = clamp01((abs(frame.moveX) + abs(frame.moveY) + abs(frame.vertical)) * (if (boosting) 2.5f else 1f))
        val lampLvl = upgrades[Upgrade.LAMP.ordinal]
        var drain = 0.05f + thrustLoad * 0.22f + (if (sub.lampOn) 0.09f + lampLvl * 0.02f else 0f)
        if (upgrades[Upgrade.THERMAL.ordinal] > 0 && nearVent()) drain -= 1.6f
        sub.power = clamp(sub.power - drain * diff.drain * dt, 0f, sub.maxPower)
        if (sub.power <= 0f && sub.lampOn) { sub.lampOn = false; say(Message("ARIA", "Power is gone. Lamps off, thrusters on reserve. Head home slowly.", 4f)) }
        depth = -sub.pos.y
        if (sub.pos.y > -3f) sub.oxygen = min(sub.maxOxygen, sub.oxygen + dt * 40f)
        else sub.oxygen = max(0f, sub.oxygen - dt * diff.drain)
        if (sub.oxygen <= 0f) damageSub(dt * 3f, DamageKind.PRESSURE, quiet = true)
        if (depth > sub.rating) {
            val over = depth - sub.rating
            damageSub(dt * (0.6f + over / 35f), DamageKind.PRESSURE, quiet = true)
            creakTimer -= dt
            if (creakTimer <= 0f) { creakTimer = max(0.6f, 4f - over / 20f); sound.play(if (over > 60f) Sfx.GROAN else Sfx.CREAK, 0.8f, 0.8f + Math.random().toFloat() * 0.3f); camShake = max(camShake, 0.3f) }
        } else if (depth > sub.rating * 0.88f) {
            creakTimer -= dt
            if (creakTimer <= 0f) { creakTimer = 7f + Math.random().toFloat() * 6f; sound.play(Sfx.CREAK, 0.4f, 1f) }
        }
        story.deepest = max(story.deepest, depth)

        // noise: thrust, boost and impacts; dampened by silent running
        val thrustNoise = (sub.speed / 11f) * (if (sub.silent) 0.45f else 1f) + (if (boosting) 0.35f else 0f)
        sub.noise = max(thrustNoise, sub.noise - dt * 0.35f)

        // ---------------- world
        floorY = SeaGen.height(sub.pos.x, sub.pos.z)
        zone = SeaGen.zone(sub.pos.x, sub.pos.z, floorY)
        for (p in SeaGen.pois) if (!discovered.contains(p.id) && sub.pos.distXZ(p.x, p.z) < 110f) { discovered.add(p.id); if (p.type != PoiType.STATION) toast("Discovered: ${p.name}") }
        updateFlares(dt)
        ecology.update(dt)
        updateScan(dt)
        updateUse()
        updateSonar(dt)
        respawnTimer += dt
        if (respawnTimer > 10f) { respawnTimer = 0f; chunks.refreshRespawns() }
        story.update(this)

        // fear: the Pale near, and lights going dark
        val pale = ecology.pale
        paleFear = if (pale != null) clamp01(1f - (pale.pos.dist(sub.pos) - 10f) / 70f) else max(0f, paleFear - dt * 0.3f)
        if (paleFear > 0.4f) {
            heartbeat -= dt
            if (heartbeat <= 0f) { heartbeat = 1.15f - paleFear * 0.5f; sound.play(Sfx.HEARTBEAT, 0.3f + paleFear * 0.5f) }
        }
        sub.flicker = max(0f, sub.flicker - dt)
        // warnings
        alarmTimer -= dt
        if (alarmTimer <= 0f && (sub.hull < sub.maxHull * 0.25f || sub.power < sub.maxPower * 0.12f || sub.oxygen < sub.maxOxygen * 0.15f)) {
            alarmTimer = 5f; sound.play(Sfx.ALARM, 0.5f)
        }

        updateCamera(dt)
        updateAmbience(dt)
        fillHud(dt)
    }

    private fun nearVent(): Boolean {
        if (zone != Zone.VENTS) return false
        var near = false
        chunks.forEachNodeNear(sub.pos.x, sub.pos.z, 14f) { n -> if (n.type == FloraType.CHIMNEY && abs(n.y + 12f - sub.pos.y) < 18f) near = true }
        return near
    }

    // ------------------------------------------------------------------ collisions

    private val nrm = FloatArray(3)

    private fun collide(dt: Float, speedBefore: Float) {
        val rad = 1.5f
        var hit = 0f
        // floor
        val h = SeaGen.height(sub.pos.x, sub.pos.z)
        if (sub.pos.y - rad < h) {
            SeaGen.normal(sub.pos.x, sub.pos.z, nrm)
            val pen = h - (sub.pos.y - rad)
            sub.pos.y += pen
            val vn = sub.vel.x * nrm[0] + sub.vel.y * nrm[1] + sub.vel.z * nrm[2]
            if (vn < 0f) { hit = max(hit, -vn); sub.vel.x -= vn * nrm[0] * 1.3f; sub.vel.y -= vn * nrm[1] * 1.3f; sub.vel.z -= vn * nrm[2] * 1.3f }
            if (pen > 0.05f) {
                // a steep wall can also push sideways
                sub.pos.x += nrm[0] * pen * 0.5f; sub.pos.z += nrm[2] * pen * 0.5f
            }
        }
        if (sub.pos.y > -1.2f) { sub.pos.y = -1.2f; if (sub.vel.y > 0f) sub.vel.y = 0f }
        // structures
        for (c in Structures.colliders) {
            if (sub.pos.x < c.minX - rad || sub.pos.x > c.maxX + rad || sub.pos.z < c.minZ - rad || sub.pos.z > c.maxZ + rad) continue
            if (sub.pos.y < c.minY - rad || sub.pos.y > c.maxY + rad) continue
            if (c.cylinder) {
                val dx = sub.pos.x - c.cx; val dz = sub.pos.z - c.cz
                val d = sqrt(dx * dx + dz * dz)
                val rr = c.radius + rad
                if (d < rr) {
                    // resolve on the shallower axis: side or top/bottom
                    val side = rr - d
                    val top = c.maxY + rad - sub.pos.y
                    val bot = sub.pos.y - (c.minY - rad)
                    if (side < top && side < bot && d > 1e-3f) {
                        sub.pos.x += dx / d * side; sub.pos.z += dz / d * side
                        val vn = (sub.vel.x * dx + sub.vel.z * dz) / d
                        if (vn < 0f) { hit = max(hit, -vn); sub.vel.x -= vn * dx / d * 1.3f; sub.vel.z -= vn * dz / d * 1.3f }
                    } else if (top < bot) { sub.pos.y += top; if (sub.vel.y < 0f) { hit = max(hit, -sub.vel.y); sub.vel.y *= -0.3f } }
                    else { sub.pos.y -= bot; if (sub.vel.y > 0f) { hit = max(hit, sub.vel.y); sub.vel.y *= -0.3f } }
                }
            } else {
                val px = clamp(sub.pos.x, c.minX, c.maxX); val py = clamp(sub.pos.y, c.minY, c.maxY); val pz = clamp(sub.pos.z, c.minZ, c.maxZ)
                var dx = sub.pos.x - px; var dy = sub.pos.y - py; var dz = sub.pos.z - pz
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < rad * rad) {
                    var d = sqrt(d2)
                    if (d < 1e-3f) { dy = 1f; dx = 0f; dz = 0f; d = 1f }
                    val pen = rad - d
                    sub.pos.add(dx / d * pen, dy / d * pen, dz / d * pen)
                    val vn = (sub.vel.x * dx + sub.vel.y * dy + sub.vel.z * dz) / d
                    if (vn < 0f) { hit = max(hit, -vn); sub.vel.x -= vn * dx / d * 1.3f; sub.vel.y -= vn * dy / d * 1.3f; sub.vel.z -= vn * dz / d * 1.3f }
                }
            }
        }
        // rocks, pillars, chimneys
        chunks.forEachNodeNear(sub.pos.x, sub.pos.z, 12f) { n ->
            val t = n.type
            if (t.collide <= 0f) return@forEachNodeNear
            val cr = t.collide * n.scale + rad
            val top = n.y + t.collideHeight * n.scale
            if (sub.pos.y > top + rad * 0.5f) return@forEachNodeNear
            val dx = sub.pos.x - n.x; val dz = sub.pos.z - n.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < cr && d > 1e-3f) {
                val sidePen = cr - d
                val topPen = top + rad * 0.5f - sub.pos.y
                if (topPen < sidePen) { sub.pos.y += topPen; if (sub.vel.y < 0f) { hit = max(hit, -sub.vel.y); sub.vel.y *= -0.3f } }
                else {
                    sub.pos.x += dx / d * sidePen; sub.pos.z += dz / d * sidePen
                    val vn = (sub.vel.x * dx + sub.vel.z * dz) / d
                    if (vn < 0f) { hit = max(hit, -vn); sub.vel.x -= vn * dx / d * 1.3f; sub.vel.z -= vn * dz / d * 1.3f }
                }
            }
        }
        if (hit > 1.2f) {
            sound.play(Sfx.CLANG, clamp01(hit / 8f) + 0.2f, 0.9f + Math.random().toFloat() * 0.2f)
            sub.addNoise(clamp01(hit / 6f))
            camShake = max(camShake, clamp01(hit / 8f))
            if (hit > 4f) damageSub((hit - 4f) * 3.5f, DamageKind.COLLISION)
        }
    }

    // ------------------------------------------------------------------ damage

    fun damageSub(amount: Float, kind: DamageKind, quiet: Boolean = false) {
        if (dead || docked || amount <= 0f) return
        sub.hull -= amount * settings.difficulty.damage
        if (!quiet) {
            damageFlash = clamp01(damageFlash + 0.35f + amount / 30f)
            camShake = max(camShake, clamp01(0.4f + amount / 25f))
            sound.play(Sfx.HULL_HIT, clamp01(0.5f + amount / 25f))
            onVibrate?.invoke((40 + amount * 6).toInt().coerceAtMost(400))
            sub.addNoise(0.5f)
        } else damageFlash = max(damageFlash, 0.08f)
        if (sub.hull <= 0f) die(kind)
    }

    fun paleGrab() {
        camShake = 1.5f
        sub.flicker = 2.2f
        sub.power = max(0f, sub.power - 15f)
        sound.play(Sfx.ROAR, 1.2f, 0.55f)
        onVibrate?.invoke(600)
    }

    private fun die(kind: DamageKind) {
        if (dead) return
        dead = true; deathTimer = 0f
        sub.hull = 0f
        sound.play(Sfx.IMPLODE, 1.2f)
        onVibrate?.invoke(900)
        val why = when (kind) {
            DamageKind.PRESSURE -> if (sub.oxygen <= 0f) "The air ran out." else "The hull gave in to the pressure."
            DamageKind.PALE -> "The Pale took the Wren."
            DamageKind.BITE -> "Something bit through the hull."
            DamageKind.STING -> "The electrics failed in the dark."
            DamageKind.COLLISION -> "The hull cracked on the rocks."
        }
        deathReason = why
        onDeath?.invoke()
    }

    var deathReason = ""

    /** Called from the death screen: the station's emergency recall drone brings the Wren back. */
    fun respawn() {
        dead = false
        val lost = cargoUsed()
        for (i in Item.entries) if (i != Item.DATA_CORE) cargo[i.ordinal] = 0
        sub.hull = sub.maxHull * 0.6f
        sub.power = sub.maxPower; sub.oxygen = sub.maxOxygen
        sub.lampOn = true
        flares.clear()
        placeAtDock()
        if (lost > 0) say(Message("ARIA", "The recall drone found the Wren. The cargo did not survive the trip. I am glad you did.", 5f))
        onDocked?.invoke()
    }

    // ------------------------------------------------------------------ actions

    private fun handleAction(a: Action) {
        when (a) {
            Action.LAMP -> if (!docked) {
                if (sub.power > 0f) { sub.lampOn = !sub.lampOn; sound.play(Sfx.LAMP, 0.7f) }
            }
            Action.SONAR -> if (!docked) ping()
            Action.USE -> if (docked) undock() else use()
            Action.FLARE -> if (!docked) launchFlare()
            Action.PULSE -> if (!docked) shockPulse()
            Action.REPAIR -> if (!docked && sub.repairKits > 0 && sub.hull < sub.maxHull) {
                sub.repairKits--; sub.hull = min(sub.maxHull, sub.hull + 40f); sound.play(Sfx.REPAIR); toast("Hull patched")
            }
            Action.CAMERA -> settings.cockpitCamera = !settings.cockpitCamera
            Action.MENU, Action.SKIP -> { if (a == Action.SKIP) { message = null; messageT = 0f } }
        }
    }

    fun undock() {
        if (!docked) return
        docked = false
        story.undocked = true
        sub.vel.set(0f, -1.5f, 0.5f)
        sub.pos.y -= 1f
        sound.play(Sfx.UNDOCK)
    }

    fun dock() {
        docked = true
        sound.play(Sfx.DOCK)
        // unload cargo into station storage
        var moved = 0
        for (i in Item.entries) {
            if (i == Item.DATA_CORE) continue
            storage[i.ordinal] += cargo[i.ordinal]; moved += cargo[i.ordinal]; cargo[i.ordinal] = 0
        }
        // read data cores
        for (l in story.logsFound) if (!story.logsRead.contains(l)) story.logsRead.add(l)
        cargo[Item.DATA_CORE.ordinal] = 0
        if (moved > 0) toast("Unloaded $moved materials")
        flares.clear()
        ecology.clear()
        requestSave()
        onDocked?.invoke()
    }

    private fun ping() {
        if (sub.power < 3f) { sound.play(Sfx.UI, 0.5f, 0.6f); return }
        sub.power -= 3f
        pingAge = 0f
        pingOrigin.set(sub.pos)
        val lvl = upgrades[Upgrade.SONAR.ordinal]
        sub.addNoise(1f - lvl * 0.2f)
        sound.play(Sfx.PING, 0.9f)
        blips.clear()
        val range = sub.sonarRange
        val speed = 140f
        chunks.forEachNodeNear(sub.pos.x, sub.pos.z, range) { n ->
            if (n.type.harvestable && blips.size < 120) {
                val d = sqrt((n.x - sub.pos.x).let { it * it } + (n.z - sub.pos.z).let { it * it })
                blips.add(Blip(n.x, n.y, n.z, 0, d / speed))
            }
        }
        for (c in ecology.list) {
            val d = c.pos.dist(sub.pos)
            if (d < range) blips.add(Blip(c.pos.x, c.pos.y, c.pos.z, if (c.species.hostile) 2 else 1, d / speed))
        }
        for (p in SeaGen.pois) {
            val d = sub.pos.distXZ(p.x, p.z)
            if (d < range * 3f) { blips.add(Blip(p.x, p.y, p.z, if (p.type == PoiType.STATION) 5 else 3, min(d, range) / speed)); if (d < range * 2f) discovered.add(p.id) }
        }
        for ((i, e) in SeaGen.echoStones.withIndex()) {
            if (story.echoes.contains(i)) continue
            val d = sub.pos.distXZ(e.x, e.z)
            if (d < range * 1.5f) blips.add(Blip(e.x, e.y, e.z, 4, min(d, range) / speed))
        }
        // songwhales answer the sonar
        for (c in ecology.list) if (c.species == Species.SONGWHALE && c.pos.dist(sub.pos) < range * 1.5f) { c.cool = 0f }
        ecology.paleThreat = clamp01(ecology.paleThreat + 0.12f)
    }

    private fun updateSonar(dt: Float) {
        pingAge += dt
        pingRadius = pingAge * 140f
    }

    private fun launchFlare() {
        if (upgrades[Upgrade.FLARES.ordinal] == 0) { toast("Build the Flare Launcher first"); return }
        if (sub.flares <= 0) { toast("No flares. Craft them at the station"); return }
        sub.flares--
        val f = sub.forward(tmp)
        val n = sub.nose(tmp2)
        flares.add(Flare(n.x, n.y, n.z, f.x * 14f + sub.vel.x, f.y * 14f + sub.vel.y + 1f, f.z * 14f + sub.vel.z))
        sound.play(Sfx.FLARE)
    }

    private fun updateFlares(dt: Float) {
        val it = flares.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.life -= dt
            f.vel.scale(1f - min(1f, dt * 1.2f))
            f.vel.y -= 0.3f * dt
            f.pos.addScaled(f.vel, dt)
            val h = SeaGen.height(f.pos.x, f.pos.z)
            if (f.pos.y < h + 0.2f) { f.pos.y = h + 0.2f; f.vel.set(0f, 0f, 0f) }
            if (f.life <= 0f) it.remove()
        }
    }

    private fun shockPulse() {
        val lvl = upgrades[Upgrade.PULSE.ordinal]
        if (lvl == 0) { toast("Build the Shock Pulse first"); return }
        val cost = 22f - lvl * 4f
        if (sub.power < cost) { toast("Not enough power"); return }
        sub.power -= cost
        ecology.pulse(14f + lvl * 8f)
        pulseFlash = 1f
        sub.addNoise(0.8f)
        sound.play(Sfx.PULSE)
        camShake = max(camShake, 0.4f)
    }

    // ------------------------------------------------------------------ interaction

    private fun updateUse() {
        useNode = null; useInteract = null; useLabel = ""
        val nose = sub.nose(tmp)
        var best = 5.5f
        chunks.forEachNodeNear(nose.x, nose.z, 6f) { n ->
            if (!n.type.harvestable) return@forEachNodeNear
            val dx = n.x - nose.x; val dy = n.y + 0.4f - nose.y; val dz = n.z - nose.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < best) { best = d; useNode = n }
        }
        useNode?.let { useLabel = "Grab ${it.type.item!!.title}"; return }
        var bi = Float.MAX_VALUE
        for (it in Structures.interacts) {
            val d = sqrt((it.x - sub.pos.x).let { v -> v * v } + (it.y - sub.pos.y).let { v -> v * v } + (it.z - sub.pos.z).let { v -> v * v })
            if (d > it.radius + 2f || d > bi) continue
            val label = when (it.kind) {
                InteractKind.DOCK -> "Dock"
                InteractKind.RELAY -> if (story.relayFixed) "" else "Repair Relay"
                InteractKind.LOG -> if (story.logsFound.contains(logIndex(it))) "" else "Download Core"
                InteractKind.BEACON -> if (story.beacons.contains(it.index)) "" else "Wake Beacon"
                InteractKind.CHOIR -> if (story.chapter == 6 && story.step >= 2) "Touch the Choir" else ""
                InteractKind.ECHO -> if (story.echoes.contains(it.index)) "" else "Take Echo Stone"
            }
            if (label.isEmpty()) continue
            bi = d; useInteract = it; useLabel = label
        }
    }

    fun logIndex(it: com.hadal.game.world.Interact): Int = when (it.poi.type) {
        PoiType.MERIDIAN -> 1 + it.index
        PoiType.WRECK -> if (it.poi.x > 0) 4 else 5
        PoiType.CINDER -> 6 + it.index
        else -> 0
    }

    private fun use() {
        useNode?.let { n ->
            val item = n.type.item!!
            if (cargoUsed() + n.type.amount > sub.cargoCap) { toast("Cargo hold full. Dock to unload."); sound.play(Sfx.UI, 0.6f, 0.6f); return }
            cargo[item.ordinal] += n.type.amount
            story.collected[item.ordinal] += n.type.amount
            n.alive = false
            harvest.harvest(n.key, playTime, n.type.respawnMin)
            chunks.markDirty(n.x, n.z)
            sound.play(Sfx.GRAB, 0.8f)
            sound.play(Sfx.PICKUP, 0.6f)
            toast("+${n.type.amount} ${item.title}")
            sub.addNoise(0.25f)
            return
        }
        val it = useInteract ?: run { toast("Nothing in reach of the arm"); return }
        when (it.kind) {
            InteractKind.DOCK -> dock()
            InteractKind.RELAY -> {
                if (count(Item.COPPER) >= 4) {
                    cargo[Item.COPPER.ordinal] -= 4
                    story.relayFixed = true
                    sound.play(Sfx.REPAIR); sound.play(Sfx.BEACON, 0.8f)
                    for (p in SeaGen.pois) if (p.type != PoiType.CHOIR) discovered.add(p.id)
                    toast("Relay online")
                    requestSave()
                } else toast("Need 4 Copper Ore in the cargo hold")
            }
            InteractKind.LOG -> {
                val li = logIndex(it)
                story.logsFound.add(li)
                cargo[Item.DATA_CORE.ordinal]++
                sound.play(Sfx.DATA)
                toast("Data core recovered: ${Logs.entries[li].first}")
            }
            InteractKind.BEACON -> {
                story.beacons.add(it.index)
                story.logsFound.add(8 + it.index); story.logsRead.add(8 + it.index)
                sound.play(Sfx.BEACON)
                say(Message("Beacon", Logs.entries[8 + it.index].second, 7f))
                requestSave()
            }
            InteractKind.CHOIR -> {
                story.logsFound.add(11); story.logsRead.add(11)
                sound.play(Sfx.CHOIR, 1f)
                endingRequested = true
                onEnding?.invoke()
            }
            InteractKind.ECHO -> {
                story.echoes.add(it.index)
                sound.play(Sfx.CHOIR, 0.6f, 1.4f)
                say(Message("Echo Stone ${story.echoes.size}/${Logs.echoes.size}", Logs.echoes[it.index], 6f))
                requestSave()
            }
        }
    }

    /** Ending choice from the UI: 1 stay, 2 go home. Both continue into free play at the station. */
    fun chooseEnding(choice: Int) {
        story.choice = choice
        endingRequested = false
        if (choice == 1) say(Message("The Choir", "A note is kept for you. It will always be here.", 5f))
        story.update(this)
        placeAtDock()
        onDocked?.invoke()
        requestSave()
    }

    private fun updateScan(dt: Float) {
        if (!frame.scan) { scanProgress = 0f; scanTarget = null; return }
        val t = ecology.scanTarget(sub.scanRange)
        if (t == null || t !== scanTarget) { scanTarget = t; scanProgress = 0f }
        if (t == null) return
        if (biodex.contains(t.species)) return
        scanProgress += dt * sub.scanSpeed / t.species.scanSeconds
        scanTick -= dt
        if (scanTick <= 0f) { scanTick = 0.25f; sound.play(Sfx.SCAN_TICK, 0.4f, 0.8f + scanProgress * 0.6f) }
        if (scanProgress >= 1f) {
            biodex.add(t.species)
            sound.play(Sfx.SCAN_DONE, 0.8f)
            toast("Biodex: ${t.species.title} (${biodex.size}/${Species.entries.size})")
            if (cargoUsed() < sub.cargoCap) { cargo[Item.BIOSAMPLE.ordinal]++; story.collected[Item.BIOSAMPLE.ordinal]++ }
            scanProgress = 0f
            requestSave()
        }
    }

    // ------------------------------------------------------------------ fabrication (station UI, called under lock)

    fun storageHas(cost: Map<Item, Int>) = cost.all { (i, n) -> storage[i.ordinal] >= n }
    private fun pay(cost: Map<Item, Int>) { for ((i, n) in cost) storage[i.ordinal] -= n }

    fun craftUpgrade(u: Upgrade): Boolean {
        val lvl = upgrades[u.ordinal]
        if (lvl >= u.maxLevel) return false
        val cost = Costs.upgrade(u, lvl)
        if (!storageHas(cost)) return false
        pay(cost)
        upgrades[u.ordinal]++
        sub.applyUpgrades(upgrades)
        if (u == Upgrade.HULL) sub.hull = sub.maxHull
        if (u == Upgrade.FLARES) sub.flares = max(sub.flares, 3)
        sound.play(Sfx.CRAFT)
        story.update(this)
        requestSave()
        return true
    }

    fun craftFlare(): Boolean {
        if (upgrades[Upgrade.FLARES.ordinal] == 0 || sub.flares >= 6 || !storageHas(Costs.flare)) return false
        pay(Costs.flare); sub.flares++; sound.play(Sfx.CRAFT, 0.7f); requestSave(); return true
    }

    fun craftKit(): Boolean {
        if (sub.repairKits >= 3 || !storageHas(Costs.repairKit)) return false
        pay(Costs.repairKit); sub.repairKits++; sound.play(Sfx.CRAFT, 0.7f); requestSave(); return true
    }

    fun buildDrone(): Boolean {
        val n = fleet.drones.size
        if (n >= DroneStats.MAX_DRONES) return false
        val cost = DroneStats.buildCost(n)
        if (!storageHas(cost)) return false
        pay(cost)
        fleet.addDrone()
        sound.play(Sfx.CRAFT)
        if (n == 0) say(Message("ARIA", "Drone one is awake. It will fly out on its own, look around and bring back whatever it finds.", 6f))
        requestSave()
        return true
    }

    fun upgradeDrones(u: DroneUpgrade): Boolean {
        val lvl = fleet.levels[u.ordinal]
        if (lvl >= DroneStats.MAX_LEVEL) return false
        val cost = DroneStats.upgradeCost(u, lvl)
        if (!storageHas(cost)) return false
        pay(cost)
        fleet.levels[u.ordinal]++
        sound.play(Sfx.CRAFT, 0.8f)
        requestSave()
        return true
    }

    fun buildDecor(d: Decor): Boolean {
        if (decor[d.ordinal] || !storageHas(d.cost)) return false
        pay(d.cost); decor[d.ordinal] = true; sound.play(Sfx.CRAFT, 0.8f); requestSave(); return true
    }

    // ------------------------------------------------------------------ camera & feedback

    private val camTmp = Vec3()

    fun updateCamera(dt: Float, snap: Boolean = false) {
        camShake = max(0f, camShake - dt * 1.5f)
        damageFlash = max(0f, damageFlash - dt * 1.2f)
        pulseFlash = max(0f, pulseFlash - dt * 2.5f)
        val f = sub.forward(camTmp)
        val tx: Float; val ty: Float; val tz: Float
        if (settings.cockpitCamera && !docked) {
            val n = sub.nose(tmp2)
            tx = n.x - f.x * 0.6f; ty = n.y + 0.35f; tz = n.z - f.z * 0.6f
            camPos.set(tx, ty, tz)
        } else {
            val dist = if (docked) 12f else 8.5f
            val px = sub.pos.x; val py = sub.pos.y + 1.1f; val pz = sub.pos.z
            var cx = px - f.x * dist; var cy = py - f.y * dist + 1.2f; var cz = pz - f.z * dist
            // pull in if the floor is in the way
            var t = 1f
            for (i in 1..8) {
                val s = i / 8f
                val sx = px + (cx - px) * s; val sy = py + (cy - py) * s; val sz = pz + (cz - pz) * s
                if (sy < SeaGen.height(sx, sz) + 0.9f) { t = (s - 0.125f).coerceAtLeast(0.2f); break }
            }
            cx = px + (cx - px) * t; cy = py + (cy - py) * t; cz = pz + (cz - pz) * t
            cy = max(cy, SeaGen.height(cx, cz) + 0.9f).coerceAtMost(-0.4f)
            if (snap) camPos.set(cx, cy, cz)
            else {
                val k = min(1f, dt * 7f)
                camPos.x += (cx - camPos.x) * k; camPos.y += (cy - camPos.y) * k; camPos.z += (cz - camPos.z) * k
            }
        }
        // look a little above the sub so it sits low in the frame and the view ahead stays clear
        val lift = if (settings.cockpitCamera && !docked) 0f else 1.0f
        camTarget.set(camPos.x + f.x * 10f, camPos.y + f.y * 10f + lift, camPos.z + f.z * 10f)
        if (camShake > 0f) {
            val s = camShake * camShake * 0.25f
            camPos.add(sin(time * 53f) * s, sin(time * 61f + 1f) * s, sin(time * 47f + 2f) * s)
        }
    }

    private fun updateAmbience(dt: Float) {
        amb.depth = clamp01(depth / 1600f)
        amb.thrust = if (docked) 0f else clamp01(sub.speed / sub.thrust)
        amb.boost = sub.boosting
        amb.docked = docked
        val sd = sqrt(sub.pos.x * sub.pos.x + sub.pos.z * sub.pos.z)
        amb.station = clamp01(1f - sd / 90f)
        amb.vents = if (zone == Zone.VENTS) clamp01(1f - (sub.pos.y - floorY) / 40f) * 0.8f + 0.2f else 0f
        amb.fear = paleFear
        amb.stress = if (depth > sub.rating) clamp01((depth - sub.rating) / 80f + 0.3f) else 0f
        amb.surface = smoothstep(-25f, -2f, sub.pos.y)
        amb.lowPower = sub.power <= 0.01f
        amb.menu = false; amb.paused = false
        sound.setAmbience(amb)
        sound.setListener(camPos.x, camPos.y, camPos.z, sub.yaw)
    }

    private fun fillHud(dt: Float) = synchronized(hud) { fillHudLocked(dt) }

    private fun fillHudLocked(dt: Float) {
        val h = hud
        h.hull = sub.hull / sub.maxHull; h.power = sub.power / sub.maxPower; h.oxygen = sub.oxygen / sub.maxOxygen
        h.hullV = sub.hull.toInt(); h.powerV = sub.power.toInt(); h.oxygenV = sub.oxygen.toInt()
        h.depth = depth; h.rating = sub.rating
        h.heading = sub.yaw
        h.speed = sub.speed
        h.zone = zone.title
        h.chapter = story.currentChapter?.title ?: "Free Dive"
        h.objective = story.objectiveText(this)
        h.useLabel = if (docked) "Undock" else useLabel
        val st = scanTarget
        h.scanLabel = if (st != null) (if (biodex.contains(st.species)) "${st.species.title}: known" else "Scanning ${st.species.title}") else ""
        h.scanProgress = if (st != null && !biodex.contains(st.species)) scanProgress else -1f
        h.cargo = cargoUsed(); h.cargoCap = sub.cargoCap
        h.flares = sub.flares; h.kits = sub.repairKits
        h.hasFlares = upgrades[Upgrade.FLARES.ordinal] > 0
        h.hasPulse = upgrades[Upgrade.PULSE.ordinal] > 0
        h.lampOn = sub.lampOn
        h.noise = sub.noise
        h.fear = paleFear
        h.damageFlash = damageFlash
        h.lowPower = sub.power < sub.maxPower * 0.15f
        h.lowOxygen = sub.oxygen < sub.maxOxygen * 0.2f
        h.pressure = depth > sub.rating
        h.docked = docked
        h.dead = dead
        h.fps = fps
        // messages
        if (message == null && messages.isNotEmpty()) { message = messages.removeFirst(); messageT = 0f }
        message?.let { m ->
            messageT += dt
            if (messageT > m.seconds + 0.6f) message = null
        }
        h.message = message
        h.messageAlpha = message?.let { clamp01(messageT / 0.3f) * clamp01((it.seconds + 0.6f - messageT) / 0.6f) } ?: 0f
        toastT = max(0f, toastT - dt)
        h.toast = toast; h.toastAlpha = clamp01(toastT / 0.5f)
        h.sonarRange = sub.sonarRange
        h.pingAge = pingAge
        // blips fade 12 s after the ping
        var n = 0
        if (pingAge < 12f) {
            if (h.blips.size < blips.size * 4) h.blips = FloatArray(blips.size * 4 + 16)
            for (b in blips) {
                if (pingAge < b.delay) continue
                h.blips[n * 4] = b.x - sub.pos.x; h.blips[n * 4 + 1] = b.z - sub.pos.z
                h.blips[n * 4 + 2] = b.y - sub.pos.y; h.blips[n * 4 + 3] = b.kind.toFloat()
                n++
            }
        }
        h.blipCount = n
        h.stationDx = -sub.pos.x; h.stationDz = -sub.pos.z
        h.loading = !worldReady
    }
}
