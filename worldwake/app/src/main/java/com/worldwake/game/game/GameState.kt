package com.worldwake.game.game

import com.worldwake.game.engine.PI_F
import com.worldwake.game.engine.TWO_PI
import com.worldwake.game.engine.Vec3
import com.worldwake.game.engine.angleDiff
import com.worldwake.game.engine.clamp
import com.worldwake.game.engine.clamp01
import com.worldwake.game.engine.lerp
import com.worldwake.game.engine.smoothstep
import com.worldwake.game.input.Action
import com.worldwake.game.input.InputState
import com.worldwake.game.world.Biome
import com.worldwake.game.world.ChunkManager
import com.worldwake.game.world.Grow
import com.worldwake.game.world.HarvestRegistry
import com.worldwake.game.world.Node
import com.worldwake.game.world.Planet
import com.worldwake.game.world.Poi
import com.worldwake.game.world.PoiKind
import com.worldwake.game.world.PropType
import com.worldwake.game.world.Structures
import com.worldwake.game.world.WorldGen
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TargetKind { NONE, ORE, PLACED, POI }

class Marker(val x: Float, val z: Float, val color: Int, val label: String)

const val POD_FLOOR = 0.2f
const val RAMP_LEN = 2.8f
const val GRAVITY = 7.2f

/** The whole simulation. Runs on the GL thread; the UI thread talks to it through [post]. */
class GameState(val settings: Settings) {
    val player = Player()
    val env = Environment()
    val harvest = HarvestRegistry()
    val chunks = ChunkManager(harvest)
    val structures = Structures()
    val placed = ArrayList<Placed>()
    val pods = HashMap<Long, Placed>()
    val fx = Fx()
    var sound: SoundSink? = null
    var ui: GameUi? = null

    @Volatile var started = false
    @Volatile var worldReady = false
    @Volatile var paused = true
    @Volatile var menuOpen = false
    @Volatile var saveRequested = false
    var nextId = 1

    // ---- terraforming
    var heat = 0.0
    var press = 0.0
    var oxy = 0.0
    var bio = 0.0
    val wi: Double get() = heat + press + oxy + bio
    var rateHeat = 0.0; var ratePress = 0.0; var rateOxy = 0.0; var rateBio = 0.0
    var powerGen = 0f
    var powerUse = 0f
    var efficiency = 1f
    @Volatile var stage = 0f
    var stageReached = 0
    val rate: Double get() = (rateHeat + ratePress + rateOxy + rateBio) * efficiency

    /** Seconds of play time. */
    var time = 0.0
    var lastEpochMs = 0L
    var menuStage = 0f

    // ---- story
    val logsRead = HashSet<Int>()
    val lootInv = HashMap<Int, Inventory>()
    val flags = HashSet<String>()
    val landerInv = Inventory(60)
    @Volatile var subtitle = ""
    var subtitleTime = 0f
    private val speech = ArrayDeque<String>()
    private var idleTimer = 140f
    @Volatile var objective = ""
    @Volatile var objectiveX = Float.NaN
    @Volatile var objectiveZ = 0f
    val feed = ArrayList<Pair<String, Float>>()
    @Volatile var feedText = ""
    @Volatile var banner = ""
    var bannerTime = 0f
    var ended = false

    // ---- building
    @Volatile var buildType: BuildType? = null
    val buildPos = Vec3()
    var buildYaw = 0f
    var buildDoor = 0
    @Volatile var buildValid = false
    @Volatile var buildWhy = ""
    @Volatile var deconMode = false
    var rotPreview = 0

    // ---- interaction
    @Volatile var targetKind = TargetKind.NONE
    var targetNode: Node? = null
    var targetPlaced: Placed? = null
    var targetPoi: Poi? = null
    @Volatile var prompt = ""

    // ---- shared with the HUD
    @Volatile var markers: Array<Marker> = emptyArray()
    @Volatile var oreMarks = FloatArray(0)
    val viewProj = FloatArray(16)
    @Volatile var shake = 0f
    /** 1 right after the tool is used, eases back to 0. */
    @Volatile var toolKick = 0f
    @Volatile var fps = 0

    val watcherPois: List<Poi> = WorldGen.pois.filter { it.kind == PoiKind.WATCHER }
    val watcherYaw = FloatArray(watcherPois.size) { it * 1.9f }
    private val watcherSeen = FloatArray(watcherPois.size) { it * 1.9f }
    var nearestWatcher = 999f
    var nearMachines = 0f
    private val amb = Ambience()
    private val frame = InputState.Frame()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private var slowTimer = 0f
    private var fxTimer = 0f
    private var alarmTimer = 0f
    private var menuAngle = 0f
    private var prevJump = false
    private val nrm = FloatArray(3)
    private val aim = Vec3()
    private val eye = Vec3()

    init {
        player.pos.set(WorldGen.lander.x + 6f, WorldGen.lander.y, WorldGen.lander.z + 9f)
        chunks.viewRadius = settings.viewDistance
    }

    fun post(fn: () -> Unit) { tasks.add(fn) }

    // ------------------------------------------------------------------ lifecycle

    fun newGame() {
        placed.clear(); pods.clear(); nextId = 1
        heat = 0.0; press = 0.0; oxy = 0.0; bio = 0.0
        stage = 0f; stageReached = 0; time = 0.0
        logsRead.clear(); lootInv.clear(); flags.clear(); feed.clear(); speech.clear()
        subtitle = ""; banner = ""; ended = false
        harvest.respawnAt.clear()
        landerInv.clear()
        landerInv.add(Item.RATION, 6); landerInv.add(Item.WATER, 4); landerInv.add(Item.O2_CAP, 2)
        landerInv.add(Item.SPORE, 3); landerInv.add(Item.FERRITE, 4); landerInv.add(Item.SILICA, 2)
        player.reset()
        val l = WorldGen.lander
        player.pos.set(l.x + sin(l.yaw) * 7f, 0f, l.z + cos(l.yaw) * 7f)
        player.pos.y = WorldGen.height(player.pos.x, player.pos.z)
        player.yaw = l.yaw
        env.timeOfDay = 0.31f
        buildType = null; deconMode = false
        fx.clear()
        recalc()
        started = true
        worldReady = false
        lastEpochMs = System.currentTimeMillis()
        for (line in Story.intro) say(line)
    }

    fun afterLoad(elapsedSec: Double) {
        pods.clear()
        for (p in placed) if (p.type == BuildType.POD) pods[cellKey(p.gx, p.gz)] = p
        recalc()
        stage = Planet.stageOf(wi)
        started = true
        worldReady = false
        fx.clear()
        if (elapsedSec > 90.0) {
            val gained = applyOffline(elapsedSec)
            if (gained > 0.0) {
                say(Story.offline)
                banner = "While you were away: +" + Fmt.wi(gained) + " Wake Index"
                bannerTime = 7f
            }
        }
        checkStage(false)
    }

    /** Machines run at a fifth of their pace while the game is closed. Returns the Wake Index gained. */
    fun applyOffline(elapsedSec: Double): Double {
        val t = min(elapsedSec, 21.0 * 86400.0) * 0.2
        val before = wi
        heat += rateHeat * efficiency * t; press += ratePress * efficiency * t
        oxy += rateOxy * efficiency * t; bio += rateBio * efficiency * t
        for (p in placed) produce(p, (t * efficiency).toFloat())
        return wi - before
    }

    // ------------------------------------------------------------------ helpers

    fun cellKey(gx: Int, gz: Int): Long = (gx.toLong() shl 32) xor (gz.toLong() and 0xffffffffL)
    fun podAt(x: Float, z: Float): Placed? = pods[cellKey(floor(x / POD_CELL).toInt(), floor(z / POD_CELL).toInt())]
    private val sideDx = intArrayOf(1, 0, -1, 0)
    private val sideDz = intArrayOf(0, 1, 0, -1)

    fun podNeighbor(p: Placed, side: Int): Placed? {
        val n = pods[cellKey(p.gx + sideDx[side], p.gz + sideDz[side])] ?: return null
        return if (abs(n.y - p.y) < 0.6f) n else null
    }

    /** 0 = wall with window, 1 = door, 2 = open to the next pod. */
    fun sideState(p: Placed, side: Int): Int = if (podNeighbor(p, side) != null) 2 else if (p.door == side) 1 else 0

    fun rampDrop(p: Placed): Float {
        val cx = (p.gx + 0.5f) * POD_CELL + sideDx[p.door] * (POD_CELL * 0.5f + RAMP_LEN)
        val cz = (p.gz + 0.5f) * POD_CELL + sideDz[p.door] * (POD_CELL * 0.5f + RAMP_LEN)
        return clamp(p.y + POD_FLOOR - WorldGen.height(cx, cz), 0.1f, 2.6f)
    }

    /** Highest surface the player can stand on at (x, z) given where their feet are. */
    fun groundAt(x: Float, z: Float, feetY: Float): Float {
        var g = WorldGen.height(x, z)
        if (pods.isEmpty()) return g
        val gx = floor(x / POD_CELL).toInt(); val gz = floor(z / POD_CELL).toInt()
        val pod = pods[cellKey(gx, gz)]
        if (pod != null) {
            val fy = pod.y + POD_FLOOR
            if (feetY >= pod.y + POD_HEIGHT - 0.5f) g = max(g, pod.y + POD_HEIGHT + 0.12f)
            else if (feetY >= fy - 0.75f) g = max(g, fy)
        }
        for (s in 0 until 4) {
            val q = pods[cellKey(gx - sideDx[s], gz - sideDz[s])] ?: continue
            if (q.door != s || sideState(q, s) != 1) continue
            val cx = (q.gx + 0.5f) * POD_CELL; val cz = (q.gz + 0.5f) * POD_CELL
            val out = (x - cx) * sideDx[s] + (z - cz) * sideDz[s] - POD_CELL * 0.5f
            val lat = (x - cx) * sideDz[s] - (z - cz) * sideDx[s]
            if (out < -0.05f || out > RAMP_LEN || abs(lat) > 1.0f) continue
            val ry = q.y + POD_FLOOR - (out / RAMP_LEN) * rampDrop(q)
            if (feetY >= ry - 0.7f) g = max(g, ry)
        }
        return g
    }

    fun unlocked(t: BuildType) = stageReached >= t.stage

    fun nearFabricator(): Boolean {
        val p = player.pos
        val l = WorldGen.lander
        if (p.distXZ(l.x, l.z) < 10f) return true
        for (pl in placed) if (pl.type == BuildType.FABRICATOR && abs(pl.x - p.x) < 6f && abs(pl.z - p.z) < 6f && abs(pl.y - p.y) < 3f) return true
        return false
    }

    fun say(text: String) {
        if (text.isEmpty()) return
        if (speech.size > 6) return
        speech.addLast(text)
    }

    fun note(text: String) {
        feed.add(Pair(text, 3.2f))
        while (feed.size > 5) feed.removeAt(0)
        rebuildFeed()
    }

    private fun rebuildFeed() {
        val sb = StringBuilder()
        for (f in feed) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(f.first) }
        feedText = sb.toString()
    }

    // ------------------------------------------------------------------ frame

    fun update(dt: Float, input: InputState) {
        while (true) { val t = tasks.poll() ?: break; try { t() } catch (e: Throwable) { android.util.Log.e("Worldwake", "task", e) } }
        chunks.viewRadius = settings.viewDistance
        chunks.highDetailNear = settings.terrainDetail > 0
        chunks.decorDensity = settings.decorDensity
        chunks.nowDays = time / 86400.0
        val p = player
        val dayLen = settings.dayLengthMinutes * 60f

        if (!started) {
            // title backdrop: drift slowly around the lander
            menuAngle += dt * 0.035f
            val l = WorldGen.lander
            val r = 17f
            p.pos.set(l.x + sin(menuAngle) * r, 0f, l.z + cos(menuAngle) * r)
            p.pos.y = WorldGen.height(p.pos.x, p.pos.z) + 0.6f
            p.yaw = menuAngle + PI_F + 0.25f
            p.pitch = 0.12f
            p.bobAmount = 0f
            stage = menuStage
            env.update(dt, menuStage, dayLen, false)
            chunks.update(p.pos.x, p.pos.z, 3)
            input.poll(frame)
            amb.paused = false; amb.menu = true; amb.atmo = env.v.atmo; amb.wind = env.wind; amb.stage = menuStage
            amb.rain = 0f; amb.hum = 0.2f; amb.breath = 0f; amb.indoors = false; amb.underwater = false
            amb.night = env.nightFactor; amb.life = env.v.motes; amb.machines = 0f; amb.watcher = 0f; amb.water = 0f; amb.helmet = true
            sound?.setAmbience(amb)
            return
        }

        val missing = chunks.update(p.pos.x, p.pos.z, if (worldReady) 2 else 6)
        if (!worldReady) {
            env.update(dt, stage, dayLen, false)
            input.poll(frame)
            if (missing == 0 && chunks.ready(p.pos.x, p.pos.z)) {
                worldReady = true
                p.pos.y = max(p.pos.y, groundAt(p.pos.x, p.pos.z, p.pos.y + 1f))
            }
            return
        }

        input.poll(frame)
        if (paused) {
            // menus freeze the player but not the planet
            if (menuOpen) { simulate(dt); time += dt }
            env.update(dt, stage, dayLen, false)
            updateAmbience(true)
            return
        }

        time += dt
        handleLook(dt)
        handleActions()
        if (!p.dead) {
            movePlayer(dt)
            survive(dt)
        }
        simulate(dt)
        env.update(dt, stage, dayLen, true)
        if (env.thunderPending) { env.thunderPending = false; sound?.play(Sfx.THUNDER, 0.9f); shake = max(shake, 0.25f) }
        updateBuildPreview()
        findTarget()
        updateWatchers(dt)
        updateSpeech(dt)
        updateFx(dt)
        shake = max(0f, shake - dt * 1.5f)
        toolKick = max(0f, toolKick - dt * 3.5f)

        slowTimer -= dt
        if (slowTimer <= 0f) {
            slowTimer = 0.5f
            chunks.refreshRespawns()
            updateObjective()
            updateMarkers()
            storyBeats()
        }
        for (i in feed.indices.reversed()) {
            val f = feed[i]
            val left = f.second - dt
            if (left <= 0f) { feed.removeAt(i); rebuildFeed() } else feed[i] = Pair(f.first, left)
        }
        if (bannerTime > 0f) { bannerTime -= dt; if (bannerTime <= 0f) banner = "" }
        updateAmbience(false)
        sound?.setListener(p.pos.x, p.pos.y + p.eyeHeight, p.pos.z, p.yaw)
        lastEpochMs = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ input

    private fun handleLook(dt: Float) {
        val p = player
        val sens = settings.lookSensitivity
        val inv = if (settings.invertY) -1f else 1f
        p.yaw -= frame.lookX * 0.0045f * sens
        p.pitch -= frame.lookY * 0.0045f * sens * inv
        // gamepad right stick, with a little response curve
        val lx = frame.padLookX; val ly = frame.padLookY
        p.yaw -= lx * abs(lx) * 2.6f * sens * dt
        p.pitch -= ly * abs(ly) * 1.9f * sens * inv * dt
        p.pitch = clamp(p.pitch, -1.45f, 1.45f)
        if (p.yaw > PI_F) p.yaw -= TWO_PI
        if (p.yaw < -PI_F) p.yaw += TWO_PI
    }

    private fun handleActions() {
        val p = player
        for (a in frame.actions) {
            when (a) {
                Action.JUMP -> if (!p.dead && p.onGround) {
                    p.vel.y = 4.9f; p.onGround = false
                }
                Action.INTERACT -> if (buildType != null) tryPlace() else if (deconMode) tryDeconstruct() else interact()
                Action.PLACE -> if (buildType != null) tryPlace() else if (deconMode) tryDeconstruct() else interact()
                Action.ROTATE -> { buildYaw += PI_F / 8f; rotPreview++ }
                Action.ROTATE_BACK -> { buildYaw -= PI_F / 8f; rotPreview-- }
                Action.CANCEL -> { buildType = null; deconMode = false }
                Action.NEXT_BUILD -> cycleBuild(1)
                Action.PREV_BUILD -> cycleBuild(-1)
                Action.DECON_TOGGLE -> { deconMode = !deconMode; buildType = null }
                Action.SKIP_DIALOG -> { subtitleTime = 0f }
            }
        }
    }

    private fun cycleBuild(dir: Int) {
        val cur = buildType ?: return
        val list = BuildType.entries.filter { it.cat == cur.cat && unlocked(it) }
        if (list.isEmpty()) return
        val i = list.indexOf(cur)
        buildType = list[(i + dir + list.size) % list.size]
    }

    fun startBuild(t: BuildType) {
        deconMode = false
        buildType = t
        buildYaw = player.yaw + PI_F
    }

    // ------------------------------------------------------------------ movement

    private fun movePlayer(dt: Float) {
        val p = player
        val v = env.v
        var mx = frame.moveX; var my = frame.moveY
        val mag = sqrt(mx * mx + my * my)
        if (mag > 1f) { mx /= mag; my /= mag }
        p.sprinting = frame.sprint || (settings.autoSprint && mag > 0.92f)
        val feetWater = v.waterLevel - p.pos.y
        p.inWater = feetWater > 0.35f
        p.underwater = feetWater > p.eyeHeight + 0.05f
        var speed = if (p.sprinting) 6.6f else 4.3f
        if (p.has(Item.BOOTS)) speed *= 1.28f
        if (p.inWater) speed *= lerp(0.8f, 0.5f, clamp01(feetWater / 1.6f))
        if (buildType != null || deconMode) speed = min(speed, 4.6f)
        val sy = sin(p.yaw); val cy = cos(p.yaw)
        // forward is (sin yaw, cos yaw); right is (-cos yaw, sin yaw)
        val wx = (sy * my - cy * mx) * speed
        val wz = (cy * my + sy * mx) * speed
        val accel = if (p.onGround) 14f else 4.5f
        val k = clamp01(accel * dt)
        p.vel.x += (wx - p.vel.x) * k
        p.vel.z += (wz - p.vel.z) * k

        // jetpack: hold jump while airborne
        val jump = frame.jump
        p.jetting = false
        if (p.has(Item.JETPACK)) {
            if (jump && !p.onGround && p.jetFuel > 0f && (p.airTime > 0.18f)) {
                p.vel.y += 15.5f * dt
                if (p.vel.y > 5.5f) p.vel.y = 5.5f
                p.jetFuel -= dt / 3.6f
                p.jetting = true
            } else if (p.onGround) p.jetFuel = min(1f, p.jetFuel + dt / 2.2f)
        }
        prevJump = jump

        // horizontal move with slope and obstacle checks
        val ox = p.pos.x; val oz = p.pos.z
        var nx = ox + p.vel.x * dt; var nz = oz + p.vel.z * dt
        val curG = groundAt(ox, oz, p.pos.y + 0.6f)
        fun blocked(x: Float, z: Float): Boolean {
            val g = groundAt(x, z, p.pos.y + 0.6f)
            val d = sqrt((x - ox) * (x - ox) + (z - oz) * (z - oz))
            if (d < 1e-4f) return false
            val rise = g - max(p.pos.y, curG)
            return rise > 0.55f && rise / max(d, 0.08f) > 1.25f
        }
        if (blocked(nx, nz)) {
            if (!blocked(nx, oz)) { nz = oz; p.vel.z = 0f }
            else if (!blocked(ox, nz)) { nx = ox; p.vel.x = 0f }
            else { nx = ox; nz = oz; p.vel.x = 0f; p.vel.z = 0f }
        }
        p.pos.x = nx; p.pos.z = nz
        collide()
        val rr = sqrt(p.pos.x * p.pos.x + p.pos.z * p.pos.z)
        if (rr > WorldGen.WORLD_R) { p.pos.x *= WorldGen.WORLD_R / rr; p.pos.z *= WorldGen.WORLD_R / rr }

        // vertical
        val grav = if (p.inWater) GRAVITY * 0.35f else GRAVITY
        p.vel.y -= grav * dt
        if (p.inWater && p.vel.y < -2.2f) p.vel.y = -2.2f
        p.pos.y += p.vel.y * dt
        val g = groundAt(p.pos.x, p.pos.z, p.pos.y + 0.6f)
        val wasGround = p.onGround
        if (p.pos.y <= g) {
            if (!wasGround && p.vel.y < -6f) {
                sound?.play(Sfx.LAND, clamp01(-p.vel.y / 14f))
                if (p.vel.y < -13f) { hurt((-p.vel.y - 13f) * 5f); shake = max(shake, 0.4f) }
            }
            p.pos.y = g; p.vel.y = 0f; p.onGround = true
        } else if (wasGround && p.vel.y <= 0f && p.pos.y - g < 0.55f) {
            p.pos.y = g; p.vel.y = 0f; p.onGround = true
        } else p.onGround = false
        if (p.onGround) p.airTime = 0f else p.airTime += dt

        // head bob and footsteps
        val hs = sqrt(p.vel.x * p.vel.x + p.vel.z * p.vel.z)
        val moving = p.onGround && hs > 0.8f
        p.bobAmount += ((if (moving) min(1f, hs / 5f) else 0f) - p.bobAmount) * clamp01(dt * 8f)
        if (moving) {
            p.headBob += dt * hs * 1.55f
            p.stepTimer -= dt * hs
            if (p.stepTimer <= 0f) {
                p.stepTimer = 2.3f
                sound?.play(stepSound(), 0.34f + (if (p.sprinting) 0.12f else 0f))
                if (v.frost > 0.4f && !p.indoors && fx.rnd() < 0.6f) {
                    fx.emit(p.pos.x + fx.rs() * 0.2f, p.pos.y + 0.1f, p.pos.z + fx.rs() * 0.2f, fx.rs() * 0.4f, 0.5f, fx.rs() * 0.4f, 0.9f, 0.5f, 0.9f, 0.8f, 0.82f, 1f, 0.16f, false)
                }
            }
        }
        if (p.jetting) {
            fxTimerJet -= dt
            if (fxTimerJet <= 0f) {
                fxTimerJet = 0.03f
                fx.emit(p.pos.x, p.pos.y + 0.8f, p.pos.z, fx.rs() * 0.6f, -3f, fx.rs() * 0.6f, 0.35f, 0.5f, 1.2f, 0.5f, 0.9f, 1.4f, 0.6f, true)
                sound?.play(Sfx.JET, 0.22f)
            }
        }
    }

    private var fxTimerJet = 0f

    private fun stepSound(): Sfx {
        val p = player
        if (p.indoors || podAt(p.pos.x, p.pos.z) != null && p.pos.y > WorldGen.height(p.pos.x, p.pos.z) + 0.1f) return Sfx.STEP_METAL
        if (p.inWater) return Sfx.STEP_WATER
        val h = p.pos.y
        WorldGen.normal(p.pos.x, p.pos.z, nrm, 1f)
        if (nrm[1] < 0.8f) return Sfx.STEP_ROCK
        val v = env.v
        if (v.frost > 0.45f || h > 60f) return Sfx.STEP_FROST
        val b = WorldGen.biomeAt(p.pos.x, p.pos.z)
        if (b == Biome.SPIRES || b == Biome.HIGHLAND || b == Biome.VENTS) return Sfx.STEP_ROCK
        return if (v.grass > 0.5f && WorldGen.fertility(p.pos.x, p.pos.z, h) > 0.5f) Sfx.STEP_GRASS else Sfx.STEP_SOIL
    }

    private fun pushOut(cx: Float, cz: Float, r: Float) {
        val p = player
        val dx = p.pos.x - cx; val dz = p.pos.z - cz
        val d2 = dx * dx + dz * dz
        val rr = r + 0.36f
        if (d2 >= rr * rr) return
        val d = sqrt(d2)
        if (d < 1e-4f) { p.pos.x += rr; return }
        p.pos.x = cx + dx / d * rr; p.pos.z = cz + dz / d * rr
    }

    private fun pushBox(x0: Float, z0: Float, x1: Float, z1: Float) {
        val p = player
        val r = 0.34f
        val qx = clamp(p.pos.x, x0, x1); val qz = clamp(p.pos.z, z0, z1)
        val dx = p.pos.x - qx; val dz = p.pos.z - qz
        val d2 = dx * dx + dz * dz
        if (d2 >= r * r) return
        if (d2 > 1e-8f) {
            val d = sqrt(d2)
            p.pos.x = qx + dx / d * r; p.pos.z = qz + dz / d * r
        } else {
            // centre is inside the box: leave through the nearest face
            val l = p.pos.x - x0; val rgt = x1 - p.pos.x; val dn = p.pos.z - z0; val up = z1 - p.pos.z
            val m = min(min(l, rgt), min(dn, up))
            when (m) { l -> p.pos.x = x0 - r; rgt -> p.pos.x = x1 + r; dn -> p.pos.z = z0 - r; else -> p.pos.z = z1 + r }
        }
    }

    private fun collide() {
        val p = player
        val feet = p.pos.y
        chunks.forEachNodeNear(p.pos.x, p.pos.z, 6f) { n ->
            val t = n.type
            if (t.collide > 0f) {
                if (t.grow == Grow.NONE || env.v.grow(t.grow) > n.grow + 0.12f) {
                    if (!(t == PropType.BOULDER && feet > n.y + 1.1f * n.scale)) pushOut(n.x, n.z, t.collide * n.scale)
                }
            }
        }
        for (c in structures.colliders) {
            if (abs(c.x - p.pos.x) > 14f || abs(c.z - p.pos.z) > 14f) continue
            if (feet < c.top) pushOut(c.x, c.z, c.r)
        }
        for (pl in placed) {
            if (pl.type == BuildType.POD) continue
            if (abs(pl.x - p.pos.x) > 6f || abs(pl.z - p.pos.z) > 6f) continue
            if (feet < pl.y + pl.type.height && feet + 1.8f > pl.y) pushOut(pl.x, pl.z, pl.type.radius * 0.72f)
        }
        // pod walls
        var inside = false
        if (pods.isNotEmpty()) {
            val gx = floor(p.pos.x / POD_CELL).toInt(); val gz = floor(p.pos.z / POD_CELL).toInt()
            for (dz in -1..1) for (dx in -1..1) {
                val pod = pods[cellKey(gx + dx, gz + dz)] ?: continue
                if (feet >= pod.y + POD_HEIGHT - 0.5f || feet + 1.7f < pod.y) continue
                val cx = (pod.gx + 0.5f) * POD_CELL; val cz = (pod.gz + 0.5f) * POD_CELL
                val h = POD_CELL * 0.5f; val t = 0.28f
                for (s in 0 until 4) {
                    val st = sideState(pod, s)
                    val gap = when (st) { 1 -> 0.92f; 2 -> 2.15f; else -> -1f }
                    // wall line runs along the lateral axis at distance h from the centre
                    if (sideDx[s] != 0) {
                        val wx0 = if (sideDx[s] > 0) cx + h - t else cx - h; val wx1 = wx0 + t
                        if (gap < 0f) pushBox(wx0, cz - h, wx1, cz + h)
                        else { pushBox(wx0, cz - h, wx1, cz - gap); pushBox(wx0, cz + gap, wx1, cz + h) }
                    } else {
                        val wz0 = if (sideDz[s] > 0) cz + h - t else cz - h; val wz1 = wz0 + t
                        if (gap < 0f) pushBox(cx - h, wz0, cx + h, wz1)
                        else { pushBox(cx - h, wz0, cx - gap, wz1); pushBox(cx + gap, wz0, cx + h, wz1) }
                    }
                }
                if (dx == 0 && dz == 0 && feet >= pod.y - 0.3f) inside = true
            }
        }
        p.indoors = inside
    }

    // ------------------------------------------------------------------ survival

    private fun hurt(amount: Float) {
        val p = player
        if (p.dead) return
        p.health -= amount
        sound?.play(Sfx.HURT, 0.7f)
        ui?.vibrate(40)
        if (p.health <= 0f) {
            p.health = 0f; p.dead = true
            buildType = null; deconMode = false
            sound?.play(Sfx.DEATH, 0.8f)
            ui?.onPlayerDied()
        }
    }

    private fun survive(dt: Float) {
        val p = player
        val l = WorldGen.lander
        val nearLander = p.pos.distXZ(l.x, l.z) < 8f
        val wasSheltered = p.sheltered
        p.sheltered = p.indoors || nearLander
        val breathable = env.v.breathable && !p.underwater
        if (p.sheltered && !wasSheltered) {
            if (p.o2 < p.o2Max * 0.9f && !breathable) sound?.play(Sfx.O2_REFILL, 0.5f)
            if (p.indoors) {
                sound?.play(Sfx.AIRLOCK, 0.5f)
                if (flags.add("firstPod")) say(Story.firstPod)
            }
        }
        if (p.sheltered || breathable) p.o2 = min(p.o2Max, p.o2 + p.o2Max * 0.28f * dt)
        else p.o2 = max(0f, p.o2 - dt)
        p.water = max(0f, p.water - dt * 100f / (15f * 60f))
        p.food = max(0f, p.food - dt * 100f / (24f * 60f))

        // supplies are used automatically when a need gets low
        if (p.water < 18f && p.inv.remove(Item.WATER)) { p.water = min(100f, p.water + 48f); sound?.play(Sfx.DRINK, 0.5f); note("Drank a water flask") }
        if (p.food < 18f) {
            if (p.inv.remove(Item.GOURD)) { p.food = min(100f, p.food + 70f); p.water = min(100f, p.water + 10f); sound?.play(Sfx.EAT, 0.5f); note("Ate a glowgourd") }
            else if (p.inv.remove(Item.RATION)) { p.food = min(100f, p.food + 46f); sound?.play(Sfx.EAT, 0.5f); note("Ate a ration") }
        }
        if (!p.sheltered && !breathable && p.o2 < p.o2Max * 0.12f && p.inv.remove(Item.O2_CAP)) {
            p.o2 = min(p.o2Max, p.o2 + p.o2Max * 0.6f); sound?.play(Sfx.O2_REFILL, 0.6f); note("Used an oxygen capsule")
        }

        val lowO2 = !p.sheltered && !breathable && p.o2 < max(25f, p.o2Max * 0.15f)
        if (lowO2) {
            alarmTimer -= dt
            if (alarmTimer <= 0f) {
                alarmTimer = if (p.o2 < 10f) 1.1f else 2.6f
                sound?.play(Sfx.ALARM, 0.45f)
                if (flags.add("lowO2:" + (time / 240).toInt())) say(Story.lowO2[((time / 7).toInt()) % Story.lowO2.size])
            }
        } else alarmTimer = 0f
        var dmg = 0f
        if (p.o2 <= 0f) dmg += 9f
        if (p.water <= 0f) dmg += 1.2f
        if (p.food <= 0f) dmg += 0.8f
        if (dmg > 0f) {
            p.health -= dmg * dt
            if (p.health <= 0f) hurt(1f)
        } else if (p.water > 20f && p.food > 20f) p.health = min(100f, p.health + dt * 1.5f)
    }

    fun respawn() {
        val p = player
        var bx = WorldGen.lander.x + sin(WorldGen.lander.yaw) * 6f
        var bz = WorldGen.lander.z + cos(WorldGen.lander.yaw) * 6f
        var by = Float.NaN
        var best = p.pos.distXZ(bx, bz)
        for (pod in pods.values) {
            val cx = (pod.gx + 0.5f) * POD_CELL; val cz = (pod.gz + 0.5f) * POD_CELL
            val d = p.pos.distXZ(cx, cz)
            if (d < best) { best = d; bx = cx; bz = cz; by = pod.y + POD_FLOOR }
        }
        p.pos.set(bx, if (by.isNaN()) WorldGen.height(bx, bz) else by, bz)
        p.vel.set(0f, 0f, 0f)
        p.dead = false
        p.health = 60f; p.o2 = p.o2Max
        p.water = max(p.water, 45f); p.food = max(p.food, 45f)
        say(Story.died[(time.toInt()) % Story.died.size])
    }

    fun useItem(i: Item): Boolean {
        val p = player
        when (i) {
            Item.WATER -> { if (p.water > 95f || !p.inv.remove(i)) return false; p.water = min(100f, p.water + 48f); sound?.play(Sfx.DRINK, 0.5f) }
            Item.RATION -> { if (p.food > 95f || !p.inv.remove(i)) return false; p.food = min(100f, p.food + 46f); sound?.play(Sfx.EAT, 0.5f) }
            Item.GOURD -> { if (p.food > 95f || !p.inv.remove(i)) return false; p.food = min(100f, p.food + 70f); p.water = min(100f, p.water + 10f); sound?.play(Sfx.EAT, 0.5f) }
            Item.O2_CAP -> { if (p.o2 > p.o2Max * 0.95f || !p.inv.remove(i)) return false; p.o2 = min(p.o2Max, p.o2 + p.o2Max * 0.6f); sound?.play(Sfx.O2_REFILL, 0.6f) }
            else -> return false
        }
        return true
    }

    fun craft(r: Recipe): Boolean {
        val p = player
        if (stageReached < r.stage) return false
        if (!r.hand && !nearFabricator()) return false
        if (r.needs != null && !p.has(r.needs)) return false
        if (r.out.isUpgrade && p.has(r.out)) return false
        if (!p.inv.has(r.cost)) return false
        var spent = 0
        for (c in r.cost) spent += c.n
        if (!r.out.isUpgrade && p.inv.free + spent < r.count) { note("Pack is full"); return false }
        p.inv.pay(r.cost)
        if (r.out.isUpgrade) {
            p.upgrades[r.out.ordinal] = true
            p.inv.capacity = p.packSize
            p.o2 = p.o2Max
            sound?.play(Sfx.UNLOCK, 0.7f)
            note("Installed " + r.out.label)
        } else {
            p.inv.add(r.out, r.count)
            sound?.play(Sfx.CRAFT, 0.6f)
        }
        flags.add("crafted:" + r.out.name)
        return true
    }

    // ------------------------------------------------------------------ machines and the planet

    fun recalc() {
        var gen = 0f; var use = 0f
        var h = 0.0; var pr = 0.0; var o = 0.0; var b = 0.0
        for (p in placed) {
            val t = p.type
            if (t.power > 0f) gen += t.power else use -= t.power
            h += t.heat; pr += t.press; o += t.oxy; b += t.bio
        }
        powerGen = gen; powerUse = use
        efficiency = if (use <= 0.001f) 1f else min(1f, gen / use)
        rateHeat = h; ratePress = pr; rateOxy = o; rateBio = b
    }

    private fun produce(p: Placed, dt: Float) {
        val inv = p.inv ?: return
        when (p.type) {
            BuildType.EXTRACTOR -> {
                p.timer += dt
                val period = 75f
                if (p.timer >= period) {
                    val n = (p.timer / period).toInt()
                    p.timer -= n * period
                    inv.add(p.ore ?: Item.FERRITE, n)
                }
            }
            BuildType.COLLECTOR -> {
                p.timer += dt
                if (p.timer >= 100f) { val n = (p.timer / 100f).toInt(); p.timer -= n * 100f; inv.add(Item.WATER, n) }
            }
            BuildType.PLANTER -> {
                p.timer += dt
                if (p.timer >= 300f) {
                    val n = (p.timer / 300f).toInt(); p.timer -= n * 300f
                    for (k in 0 until min(n, 3)) { inv.add(Item.GOURD, 2); inv.add(Item.SPORE, 1) }
                }
            }
            else -> {}
        }
    }

    private fun simulate(dt: Float) {
        val e = efficiency.toDouble() * dt
        heat += rateHeat * e; press += ratePress * e; oxy += rateOxy * e; bio += rateBio * e
        for (p in placed) if (p.inv != null && p.type.power < 0f) produce(p, dt * efficiency) else if (p.type == BuildType.PLANTER) produce(p, dt)
        stage = Planet.stageOf(wi)
        checkStage(true)
    }

    private fun checkStage(announce: Boolean) {
        val now = floor(stage).toInt()
        while (stageReached < now) {
            stageReached++
            if (!announce) continue
            val i = stageReached
            banner = "STAGE " + i + "  ·  " + Planet.stageNames[i].uppercase()
            bannerTime = 8f
            sound?.play(Sfx.STAGE, 0.8f)
            say(Story.stageLines[i])
            val news = BuildType.entries.filter { it.stage == i }
            if (news.isNotEmpty()) note("New blueprints: " + news.joinToString(", ") { it.label })
            if (i == 4) env.nudgeWeather(true)
            if (i == 3) env.nudgeWeather(false)
            ui?.refreshMenus()
            saveRequested = true
        }
    }

    // ------------------------------------------------------------------ building

    private fun aimRay() {
        val p = player
        val cp = cos(p.pitch)
        aim.set(sin(p.yaw) * cp, sin(p.pitch), cos(p.yaw) * cp)
        eye.set(p.pos.x, p.pos.y + p.eyeHeight, p.pos.z)
    }

    private fun updateBuildPreview() {
        val t = buildType ?: return
        aimRay()
        buildValid = false
        buildWhy = ""
        if (!player.inv.has(t.cost)) buildWhy = "Missing materials"
        when {
            t == BuildType.POD -> previewPod(t)
            t.indoor -> previewIndoor(t)
            else -> previewOutdoor(t)
        }
        if (buildWhy.isEmpty()) buildValid = true
    }

    private fun terrainHit(maxDist: Float): Boolean {
        var d = 1.2f
        while (d < maxDist) {
            val x = eye.x + aim.x * d; val y = eye.y + aim.y * d; val z = eye.z + aim.z * d
            if (y <= WorldGen.height(x, z)) {
                buildPos.set(x, WorldGen.height(x, z), z)
                return true
            }
            d += 0.35f
        }
        // looking at the horizon: put it a few metres ahead on the ground
        val hx = sqrt(aim.x * aim.x + aim.z * aim.z)
        if (hx < 0.2f) return false
        val fx = eye.x + aim.x / hx * 5f; val fz = eye.z + aim.z / hx * 5f
        buildPos.set(fx, WorldGen.height(fx, fz), fz)
        return true
    }

    private fun previewOutdoor(t: BuildType) {
        if (!terrainHit(15f)) { buildWhy = "Aim at the ground"; return }
        val x = buildPos.x; val z = buildPos.z
        val r = t.radius
        WorldGen.normal(x, z, nrm, max(1f, r))
        if (nrm[1] < 0.8f) { buildWhy = "Too steep"; return }
        if (WorldGen.poiClearance(x, z) < r + 0.6f) { buildWhy = "Too close to a site"; return }
        for (dx in -1..1) for (dz in -1..1) {
            if (podAt(x + dx * (r + 1.2f), z + dz * (r + 1.2f)) != null) { if (buildWhy.isEmpty()) buildWhy = "Too close to a pod"; return }
        }
        for (pl in placed) {
            if (pl.type == BuildType.POD || pl.type.indoor) continue
            val d = sqrt((pl.x - x) * (pl.x - x) + (pl.z - z) * (pl.z - z))
            if (d < pl.type.radius + r + 0.2f) { if (buildWhy.isEmpty()) buildWhy = "Too close to another machine"; return }
        }
        if (player.pos.distXZ(x, z) < r + 0.5f) { if (buildWhy.isEmpty()) buildWhy = "Step back"; return }
    }

    private fun previewPod(t: BuildType) {
        if (!terrainHit(16f)) { buildWhy = "Aim at the ground"; return }
        val gx = floor(buildPos.x / POD_CELL).toInt(); val gz = floor(buildPos.z / POD_CELL).toInt()
        val cx = (gx + 0.5f) * POD_CELL; val cz = (gz + 0.5f) * POD_CELL
        val h = POD_CELL * 0.5f
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in -1..1) for (j in -1..1) {
            val y = WorldGen.height(cx + i * h, cz + j * h)
            lo = min(lo, y); hi = max(hi, y)
        }
        var y = hi + 0.1f
        var joined = false
        for (s in 0 until 4) {
            val n = pods[cellKey(gx + sideDx[s], gz + sideDz[s])] ?: continue
            if (hi <= n.y + POD_FLOOR + 0.05f && lo > n.y - 3.2f) { y = n.y; joined = true; break }
        }
        buildPos.set(cx, y, cz)
        val pdx = player.pos.x - cx; val pdz = player.pos.z - cz
        buildDoor = if (abs(pdx) > abs(pdz)) (if (pdx > 0) 0 else 2) else (if (pdz > 0) 1 else 3)
        if (pods.containsKey(cellKey(gx, gz))) { buildWhy = "A pod is already here"; return }
        if (!joined && hi - lo > 1.9f) { buildWhy = "Ground is too uneven"; return }
        if (hi < 0.6f) { buildWhy = "Too low, this will flood"; return }
        if (WorldGen.poiClearance(cx, cz) < 5f) { buildWhy = "Too close to a site"; return }
        for (pl in placed) {
            if (pl.type == BuildType.POD || pl.type.indoor) continue
            if (abs(pl.x - cx) < h + pl.type.radius + 0.4f && abs(pl.z - cz) < h + pl.type.radius + 0.4f) { if (buildWhy.isEmpty()) buildWhy = "A machine is in the way"; return }
        }
        if (abs(pdx) < h + 0.4f && abs(pdz) < h + 0.4f) { if (buildWhy.isEmpty()) buildWhy = "Step back"; return }
    }

    private fun previewIndoor(t: BuildType) {
        var d = 0.6f
        var pod: Placed? = null
        while (d < 7f) {
            val x = eye.x + aim.x * d; val y = eye.y + aim.y * d; val z = eye.z + aim.z * d
            val q = podAt(x, z)
            if (q != null && y <= q.y + POD_FLOOR + 0.02f && y > q.y - 1f) { pod = q; buildPos.set(x, q.y + POD_FLOOR, z); break }
            d += 0.15f
        }
        if (pod == null) {
            val q = podAt(player.pos.x, player.pos.z)
            buildPos.set(eye.x + aim.x * 2.5f, (q?.y ?: player.pos.y) + (if (q != null) POD_FLOOR else 0f), eye.z + aim.z * 2.5f)
            buildWhy = "Place this on a pod floor"
            return
        }
        val cx = (pod.gx + 0.5f) * POD_CELL; val cz = (pod.gz + 0.5f) * POD_CELL
        val lim = POD_CELL * 0.5f - 0.3f - t.radius
        // furniture slides along the walls instead of poking through them
        val lx = buildPos.x - cx; val lz = buildPos.z - cz
        fun limit(v: Float, side: Int, side2: Int): Float {
            val hiOpen = sideState(pod, side) == 2; val loOpen = sideState(pod, side2) == 2
            return clamp(v, if (loOpen) -3f else -lim, if (hiOpen) 3f else lim)
        }
        buildPos.x = cx + limit(lx, 0, 2); buildPos.z = cz + limit(lz, 1, 3)
        for (pl in placed) {
            if (!pl.type.indoor) continue
            val dd = sqrt((pl.x - buildPos.x) * (pl.x - buildPos.x) + (pl.z - buildPos.z) * (pl.z - buildPos.z))
            if (dd < pl.type.radius + t.radius - 0.15f && abs(pl.y - buildPos.y) < 1f) { if (buildWhy.isEmpty()) buildWhy = "Something is in the way"; return }
        }
        if (player.pos.distXZ(buildPos.x, buildPos.z) < t.radius + 0.3f) { if (buildWhy.isEmpty()) buildWhy = "Step back" }
    }

    private fun tryPlace() {
        val t = buildType ?: return
        updateBuildPreview()
        if (!buildValid) { sound?.play(Sfx.DENY, 0.5f); if (buildWhy.isNotEmpty()) note(buildWhy); return }
        if (!player.inv.pay(t.cost)) return
        val pl = Placed(nextId++, t, buildPos.x, buildPos.y, buildPos.z, if (t == BuildType.POD) 0f else buildYaw)
        pl.born = time
        if (t == BuildType.POD) {
            pl.gx = floor(buildPos.x / POD_CELL).toInt(); pl.gz = floor(buildPos.z / POD_CELL).toInt()
            pl.door = buildDoor
            pods[cellKey(pl.gx, pl.gz)] = pl
        }
        if (t.indoor) pl.podId = podAt(pl.x, pl.z)?.id ?: -1
        if (t == BuildType.EXTRACTOR) { pl.ore = WorldGen.veinAt(pl.x, pl.z); note("Vein: " + pl.ore!!.label) }
        placed.add(pl)
        recalc()
        sound?.playAt(Sfx.BUILD, pl.x, pl.y + 1f, pl.z, 0.9f)
        toolKick = 1f
        ui?.vibrate(25)
        for (i in 0 until 14) {
            val a = fx.rnd() * TWO_PI; val r = t.radius * (0.4f + fx.rnd() * 0.7f)
            fx.emit(pl.x + cos(a) * r, pl.y + 0.2f + fx.rnd() * t.height * 0.5f, pl.z + sin(a) * r, cos(a) * 1.2f, 0.8f + fx.rnd(), sin(a) * 1.2f, 0.9f, 0.5f, 1.4f, 0.5f, 1f, 0.95f, 0.5f, true)
        }
        flags.add("built:" + t.name)
        if (t.isMachine && flags.add("firstMachine")) say(Story.firstMachine)
        if (t == BuildType.EXTRACTOR && flags.add("firstExtractor")) say(Story.firstExtractor)
        if (powerUse > powerGen + 0.01f && flags.add("powerLow:" + (time / 300).toInt())) say(Story.powerLow)
        saveRequested = true
        if (!player.inv.has(t.cost) || t == BuildType.POD) buildType = null
    }

    private fun tryDeconstruct() {
        val pl = targetPlaced ?: run { sound?.play(Sfx.DENY, 0.4f); return }
        if (pl.type == BuildType.POD && placed.any { it.type.indoor && it.podId == pl.id }) { note("Empty the pod first"); sound?.play(Sfx.DENY, 0.5f); return }
        if ((pl.inv?.total ?: 0) > 0) { note("Empty it first"); sound?.play(Sfx.DENY, 0.5f); return }
        placed.remove(pl)
        if (pl.type == BuildType.POD) pods.remove(cellKey(pl.gx, pl.gz))
        var lost = 0
        for (c in pl.type.cost) lost += c.n - player.inv.add(c.item, c.n)
        if (lost > 0) note("Pack full: $lost parts were lost") else note("Recovered " + pl.type.label)
        recalc()
        sound?.playAt(Sfx.DECON, pl.x, pl.y + 1f, pl.z, 0.8f)
        for (i in 0 until 10) fx.emit(pl.x + fx.rs() * pl.type.radius, pl.y + fx.rnd() * 2f, pl.z + fx.rs() * pl.type.radius, fx.rs(), 1f, fx.rs(), 0.8f, 0.6f, 1.2f, 0.7f, 0.72f, 0.8f, 0.3f, false)
        targetPlaced = null
        saveRequested = true
    }

    // ------------------------------------------------------------------ interaction

    private fun angleTo(x: Float, y: Float, z: Float): Float {
        val dx = x - eye.x; val dy = y - eye.y; val dz = z - eye.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-3f) return 0f
        return acos(clamp((dx * aim.x + dy * aim.y + dz * aim.z) / d, -1f, 1f))
    }

    private fun findTarget() {
        aimRay()
        val p = player
        var best = 9f
        var kind = TargetKind.NONE
        var bn: Node? = null; var bp: Placed? = null; var bpoi: Poi? = null
        if (p.dead || buildType != null) { targetKind = TargetKind.NONE; prompt = ""; return }
        if (!deconMode) {
            chunks.forEachNodeNear(p.pos.x, p.pos.z, 4.4f) { n ->
                if (n.type.isOre) {
                    val d = sqrt((n.x - eye.x) * (n.x - eye.x) + (n.y - eye.y) * (n.y - eye.y) + (n.z - eye.z) * (n.z - eye.z))
                    val a = angleTo(n.x, n.y + 0.15f, n.z)
                    if (d < 4.6f && (a < 0.34f || (d < 2.6f && a < 0.9f))) {
                        val sc = a + d * 0.06f
                        if (sc < best) { best = sc; kind = TargetKind.ORE; bn = n }
                    }
                }
            }
        }
        for (pl in placed) {
            if (abs(pl.x - p.pos.x) > 9f || abs(pl.z - p.pos.z) > 9f) continue
            val usable = deconMode || pl.inv != null || pl.type == BuildType.FABRICATOR
            if (!usable) continue
            val cy = pl.y + min(pl.type.height * 0.5f, 1.4f)
            val d = sqrt((pl.x - eye.x) * (pl.x - eye.x) + (cy - eye.y) * (cy - eye.y) + (pl.z - eye.z) * (pl.z - eye.z))
            val reach = (if (pl.type == BuildType.POD) 7.5f else 4.2f) + pl.type.radius
            if (d > reach) continue
            val a = angleTo(pl.x, cy, pl.z)
            val cone = atan2(pl.type.radius + 0.35f, max(d, 0.5f)) + 0.1f
            if (a < cone) {
                val sc = a + d * 0.05f - (if (deconMode) 0f else 0.05f)
                if (sc < best) { best = sc; kind = TargetKind.PLACED; bp = pl; bn = null }
            }
        }
        if (!deconMode) {
            for (poi in WorldGen.pois) {
                if (poi.kind == PoiKind.WATCHER) continue
                if (abs(poi.x - p.pos.x) > 14f || abs(poi.z - p.pos.z) > 14f) continue
                var tx = poi.x; var tz = poi.z; var ty = poi.y + 1f; var reach = 4.5f; var size = 1.2f
                when (poi.kind) {
                    PoiKind.LANDER -> { ty = poi.y + 2.2f; reach = 7.5f; size = 2.4f }
                    PoiKind.OUTPOST -> {
                        // the terminal stands beside the hut
                        val c = cos(poi.yaw); val s = sin(poi.yaw)
                        tx = poi.x + c * 3.6f + s * 1.3f; tz = poi.z - s * 3.6f + c * 1.3f; ty = poi.y + 1.2f; size = 0.8f
                    }
                    PoiKind.SLATE -> { ty = poi.y + 0.6f; size = 0.7f; reach = 4f }
                    PoiKind.CARGO -> { ty = poi.y + 0.7f; size = 1.1f }
                    PoiKind.WRECK -> { ty = poi.y + 1.4f; size = 2.4f; reach = 6f }
                    PoiKind.VAULT -> { tz = poi.z + 7.2f; ty = poi.y + 1.9f; size = 2.6f; reach = 8f }
                    else -> {}
                }
                val d = sqrt((tx - eye.x) * (tx - eye.x) + (ty - eye.y) * (ty - eye.y) + (tz - eye.z) * (tz - eye.z))
                if (d > reach) continue
                val a = angleTo(tx, ty, tz)
                if (a < atan2(size, max(d, 0.5f)) + 0.12f) {
                    val sc = a + d * 0.05f
                    if (sc < best) { best = sc; kind = TargetKind.POI; bpoi = poi; bp = null; bn = null }
                }
            }
        }
        targetNode = bn; targetPlaced = bp; targetPoi = bpoi
        targetKind = kind
        prompt = when (kind) {
            TargetKind.NONE -> ""
            TargetKind.ORE -> "Pick up " + bn!!.type.item!!.label
            TargetKind.PLACED -> {
                val pl = bp!!
                if (deconMode) "Take apart " + pl.type.label else when (pl.type) {
                    BuildType.FABRICATOR -> "Use Fabricator"
                    BuildType.EXTRACTOR -> "Extractor · " + (pl.ore?.label ?: "") + " (" + pl.inv!!.total + ")"
                    BuildType.COLLECTOR -> "Rain Catcher (" + pl.inv!!.total + ")"
                    BuildType.PLANTER -> if (pl.inv!!.total > 0) "Harvest Planter (" + pl.inv!!.total + ")" else "Planter · growing " + (pl.timer / 3f).toInt() + "%"
                    else -> "Open " + pl.type.label
                }
            }
            TargetKind.POI -> when (bpoi!!.kind) {
                PoiKind.LANDER -> "Open lander hold"
                PoiKind.OUTPOST -> if (logsRead.contains(bpoi!!.logIndex)) "Lantern outpost · search" else "Lantern outpost · read log"
                PoiKind.SLATE -> "Read data slate"
                PoiKind.CARGO -> "Open supply drop"
                PoiKind.WRECK -> "Search the rover"
                PoiKind.VAULT -> if (stageReached >= Planet.LAST) "Enter the vault" else "A stone door"
                else -> ""
            }
        }
    }

    private fun interact() {
        val p = player
        when (targetKind) {
            TargetKind.NONE -> {}
            TargetKind.ORE -> {
                val n = targetNode ?: return
                val item = n.type.item ?: return
                if (!n.alive) return
                if (p.inv.add(item, 1) == 0) {
                    sound?.play(Sfx.DENY, 0.5f); note("Pack is full")
                    if (flags.add("packFull:" + (time / 400).toInt())) say(Story.packFull)
                    return
                }
                n.alive = false
                harvest.harvest(n.key, time / 86400.0, 45f / 1440f)
                chunks.markDirty(n.x, n.z)
                sound?.play(Sfx.PICKUP, 0.6f)
                toolKick = 1f
                note("+1 " + item.label + "  (" + p.inv.count(item) + ")")
                for (i in 0 until 4) fx.emit(n.x, n.y + 0.2f, n.z, fx.rs() * 0.8f, 1f + fx.rnd(), fx.rs() * 0.8f, 0.5f, 0.25f, 0.4f,
                    ((item.color shr 16) and 255) / 255f, ((item.color shr 8) and 255) / 255f, (item.color and 255) / 255f, 0.7f, true)
                flags.add("picked")
            }
            TargetKind.PLACED -> {
                val pl = targetPlaced ?: return
                when (pl.type) {
                    BuildType.FABRICATOR -> ui?.openFabricator()
                    else -> pl.inv?.let { sound?.play(Sfx.CRATE, 0.5f); ui?.openStorage(pl.type.label, it) }
                }
            }
            TargetKind.POI -> {
                val poi = targetPoi ?: return
                when (poi.kind) {
                    PoiKind.LANDER -> { sound?.play(Sfx.CRATE, 0.5f); ui?.openStorage("Lander Hold", landerInv) }
                    PoiKind.OUTPOST, PoiKind.SLATE -> {
                        val first = logsRead.add(poi.logIndex)
                        if (first || poi.kind == PoiKind.SLATE) {
                            val log = Story.logs[poi.logIndex]
                            sound?.play(Sfx.LOG, 0.7f)
                            ui?.showLog(log.title, log.author + "  ·  day " + log.day + "\n\n" + log.body)
                            if (first) { say(Story.logReplies[poi.logIndex]); saveRequested = true }
                        } else if (poi.kind == PoiKind.OUTPOST) {
                            sound?.play(Sfx.CRATE, 0.5f); ui?.openStorage("Outpost Supplies", loot(poi))
                        }
                    }
                    PoiKind.CARGO -> { sound?.play(Sfx.CRATE, 0.5f); ui?.openStorage("Supply Drop", loot(poi)) }
                    PoiKind.WRECK -> { sound?.play(Sfx.CRATE, 0.5f); ui?.openStorage("Rover", loot(poi)) }
                    PoiKind.VAULT -> {
                        if (stageReached >= Planet.LAST) {
                            if (!ended) { ended = true; flags.add("ending"); saveRequested = true }
                            sound?.play(Sfx.ENDING, 0.9f)
                            ui?.onEnding()
                        } else say(Story.vaultClosed)
                    }
                    else -> {}
                }
            }
        }
    }

    /** What a site holds. Generated the first time it is opened, then kept in the save. */
    private fun loot(poi: Poi): Inventory = lootInv.getOrPut(poi.id) {
        val inv = Inventory(60)
        when (poi.kind) {
            PoiKind.OUTPOST -> {
                inv.add(Item.SPORE, 4); inv.add(Item.RATION, 3)
                when (poi.logIndex) {
                    0 -> { inv.add(Item.ALLOY, 4); inv.add(Item.GLASS, 2); inv.add(Item.CIRCUIT, 2); inv.add(Item.WATER, 2) }
                    2 -> { inv.add(Item.CIRCUIT, 3); inv.add(Item.MOTOR, 1); inv.add(Item.ALLOY, 4) }
                    4 -> { inv.add(Item.ALUMINIUM, 6); inv.add(Item.MOTOR, 2); inv.add(Item.TITANIUM, 4) }
                    6 -> { inv.add(Item.SULFUR, 8); inv.add(Item.CELL, 2); inv.add(Item.PYRE, 3) }
                    8 -> { inv.add(Item.LUMEN, 8); inv.add(Item.LENS, 2); inv.add(Item.CIRCUIT, 3) }
                    else -> { inv.add(Item.OSMIUM, 6); inv.add(Item.CORE, 1); inv.add(Item.CELL, 2) }
                }
            }
            PoiKind.WRECK -> { inv.add(Item.MOTOR, 2); inv.add(Item.CELL, 1); inv.add(Item.CIRCUIT, 2); inv.add(Item.TITANIUM, 4) }
            else -> {
                val far = sqrt((poi.x - WorldGen.START_X) * (poi.x - WorldGen.START_X) + (poi.z - WorldGen.START_Z) * (poi.z - WorldGen.START_Z)) > 480f
                inv.add(Item.SPORE, if (far) 3 else 2); inv.add(Item.RATION, 2); inv.add(Item.WATER, 1)
                if (far) {
                    inv.add(Item.ALLOY, 3); inv.add(Item.CIRCUIT, 2)
                    inv.add(when (poi.id % 3) { 0 -> Item.MOTOR; 1 -> Item.CELL; else -> Item.LENS }, 1)
                } else { inv.add(Item.FERRITE, 3); inv.add(Item.COBALT, 3); inv.add(Item.SILICA, 3); inv.add(Item.MAGNESIUM, 2) }
            }
        }
        saveRequested = true
        inv
    }

    // ------------------------------------------------------------------ the Watchers

    private fun updateWatchers(dt: Float) {
        val p = player
        val fx0 = sin(p.yaw); val fz0 = cos(p.yaw)
        var nearest = 999f
        for (i in watcherPois.indices) {
            val w = watcherPois[i]
            val dx = p.pos.x - w.x; val dz = p.pos.z - w.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < nearest) nearest = d
            if (d > 90f) continue
            // in view if the player is roughly facing it
            val facing = (-dx * fx0 - dz * fz0) / max(d, 0.01f)
            val inView = facing > 0.25f
            val want = atan2(dx, dz)
            if (!inView && d > 2.5f) {
                val diff = angleDiff(watcherYaw[i], want)
                val step = 0.7f * dt
                watcherYaw[i] += clamp(diff, -step, step)
            } else {
                val turned = abs(angleDiff(watcherSeen[i], watcherYaw[i]))
                if (turned > 0.5f && d < 45f) {
                    sound?.playAt(Sfx.STONE, w.x, w.y + 3f, w.z, 0.5f)
                    if (flags.contains("watcherSeen") && flags.add("watcherMoved:" + (time / 500).toInt())) {
                        say(Story.watcherMoved[(time / 500).toInt() % Story.watcherMoved.size])
                    }
                }
                watcherSeen[i] = watcherYaw[i]
                if (d < 22f && env.v.frost < 0.9f && flags.add("watcherSeen")) say(Story.watcherFirst)
            }
        }
        nearestWatcher = nearest
    }

    // ------------------------------------------------------------------ voice, goals, markers

    private var speechGap = 0f

    private fun updateSpeech(dt: Float) {
        if (subtitle.isNotEmpty()) {
            subtitleTime -= dt
            if (subtitleTime <= 0f) { subtitle = ""; speechGap = 0.45f }
        } else {
            speechGap -= dt
            if (speechGap <= 0f) {
                val next = speech.removeFirstOrNull()
                if (next != null) {
                    subtitle = next
                    subtitleTime = 2.4f + next.length * 0.055f
                    sound?.speak(next.length)
                }
            }
        }
        idleTimer -= dt
        if (idleTimer <= 0f) {
            idleTimer = 170f + fx.rnd() * 220f
            if (speech.isEmpty() && subtitle.isEmpty() && !player.dead) {
                val late = stageReached >= 6 && fx.rnd() < 0.5f
                val pool = if (late) Story.idleLate else Story.idle
                say(pool[(fx.rnd() * pool.size).toInt().coerceIn(0, pool.size - 1)])
            }
        }
    }

    private fun storyBeats() {
        if (env.nightFactor > 0.8f && flags.add("firstNight")) say(Story.firstNight)
        if (placed.count { it.type.isMachine } >= 4 && time > 600 && flags.add("hum")) say(Story.hum)
    }

    private fun has(flag: String) = flags.contains(flag)

    private fun updateObjective() {
        val p = player
        objectiveX = Float.NaN
        val inv = p.inv
        objective = when {
            !has("built:WIND") && !has("picked") -> "Pick up ore from the ground. Look at a rock and press Use."
            !has("built:WIND") -> "Open Build and place a Wind Spindle  (Ferrite " + inv.count(Item.FERRITE) + "/2, Magnesium " + inv.count(Item.MAGNESIUM) + "/1)"
            !has("built:HEATER_1") -> "Build an Ember Coil to start warming Ysmere"
            !has("built:DRILL_1") -> "Build a Vent Bore to thicken the air"
            stageReached < 1 -> "Build more machines and reach stage 1, the Violet Hour"
            !has("built:POD") -> "Build a Living Pod so you have air away from the lander"
            !has("built:FABRICATOR") -> "Put a Fabricator inside your pod"
            !logsRead.contains(0) -> {
                val o = WorldGen.pois.first { it.kind == PoiKind.OUTPOST && it.logIndex == 0 }
                objectiveX = o.x; objectiveZ = o.z
                "Something is transmitting to the north-east. Follow the gold mark on your compass."
            }
            !has("built:OXY_1") && stageReached >= 1 -> "Build a Spore Vat with the spores Lantern Nine left"
            stageReached < Planet.LAST -> "Reach stage " + (stageReached + 1) + ", " + Planet.stageNames[stageReached + 1] + "  ·  logs " + logsRead.size + "/" + WorldGen.LOG_COUNT
            !ended -> {
                objectiveX = WorldGen.vault.x; objectiveZ = WorldGen.vault.z + 8f
                "Ysmere is awake. Go to the door on the island in the Sink."
            }
            else -> "Ysmere is awake. Build whatever you like."
        }
    }

    private fun updateMarkers() {
        val list = ArrayList<Marker>()
        val l = WorldGen.lander
        list.add(Marker(l.x, l.z, 0xFF59F0E0.toInt(), "Lander"))
        var firstPod = true
        for (pl in placed) {
            if (pl.type == BuildType.BEACON) list.add(Marker(pl.x, pl.z, 0xFFFFFFFF.toInt(), "Beacon"))
            if (pl.type == BuildType.POD && firstPod) { firstPod = false; list.add(Marker(pl.x, pl.z, 0xFF9BE88A.toInt(), "Base")) }
        }
        if (!objectiveX.isNaN()) list.add(Marker(objectiveX, objectiveZ, 0xFFFFD060.toInt(), "Goal"))
        if (logsRead.contains(9) || stageReached >= Planet.LAST) list.add(Marker(WorldGen.vault.x, WorldGen.vault.z, 0xFFFF6A8C.toInt(), "Door"))
        markers = list.toTypedArray()

        if (player.has(Item.SCANNER)) {
            val out = ArrayList<Float>()
            chunks.forEachNodeNear(player.pos.x, player.pos.z, 46f) { n ->
                if (n.type.isOre && out.size < 4 * 48) { out.add(n.x); out.add(n.y + 0.3f); out.add(n.z); out.add(n.type.item!!.ordinal.toFloat()) }
            }
            oreMarks = out.toFloatArray()
        } else if (oreMarks.isNotEmpty()) oreMarks = FloatArray(0)
    }

    // ------------------------------------------------------------------ effects and sound

    private fun updateFx(dt: Float) {
        val p = player
        fx.update(dt, env.wind * 1.2f, env.wind * 0.5f)
        fxTimer -= dt
        if (fxTimer > 0f) return
        fxTimer = 0.12f
        var near = 0f
        val e = efficiency
        for (pl in placed) {
            val dx = pl.x - p.pos.x; val dz = pl.z - p.pos.z
            val d2 = dx * dx + dz * dz
            if (d2 > 70f * 70f) continue
            val t = pl.type
            if (t.isMachine || t.power != 0f) near += (1f - sqrt(d2) / 70f) * 0.3f
            if (fx.rnd() > 0.55f * e) continue
            val top = pl.y + t.height
            when (t.cat) {
                Cat.HEAT -> fx.emit(pl.x + fx.rs() * 0.3f, top * 0.5f + pl.y * 0.5f + fx.rnd() * t.height * 0.5f, pl.z + fx.rs() * 0.3f, fx.rs() * 0.3f, 1.2f + fx.rnd(), fx.rs() * 0.3f, 1.6f, 0.3f + t.radius * 0.2f, 0.5f, 1f, 0.5f, 0.25f, 0.5f, true)
                Cat.PRESSURE -> fx.emit(pl.x, top, pl.z, fx.rs() * 0.5f, 2.2f + fx.rnd(), fx.rs() * 0.5f, 3.2f, 0.8f + t.radius * 0.5f, 1.6f, 0.75f, 0.72f, 0.85f, 0.2f, false, 0.3f)
                Cat.OXYGEN -> fx.emit(pl.x + fx.rs() * 0.4f, top, pl.z + fx.rs() * 0.4f, fx.rs() * 0.3f, 0.9f, fx.rs() * 0.3f, 2.6f, 0.6f, 1.1f, 0.8f, 0.95f, 1f, 0.14f, false, 0.3f)
                Cat.LIFE -> fx.emit(pl.x + fx.rs() * t.radius, top * 0.6f + pl.y * 0.4f, pl.z + fx.rs() * t.radius, fx.rs() * 1.6f, 1.2f + fx.rnd(), fx.rs() * 1.6f, 3f, 0.16f, 0.02f, 1f, 0.35f, 0.5f, 0.9f, true, 0.2f)
                Cat.POWER -> if (t == BuildType.THERMAL) fx.emit(pl.x, top, pl.z, fx.rs() * 0.4f, 1.8f, fx.rs() * 0.4f, 2.8f, 0.9f, 1.5f, 0.9f, 0.9f, 0.95f, 0.18f, false, 0.3f)
                else -> {}
            }
        }
        nearMachines = clamp01(near)
        // sulfur vents breathe steam
        chunks.forEachNodeNear(p.pos.x, p.pos.z, 80f) { n ->
            if (n.type == PropType.VENT && fx.rnd() < 0.3f) {
                fx.emit(n.x, n.y + 2.3f * n.scale, n.z, fx.rs() * 0.4f, 1.6f + fx.rnd(), fx.rs() * 0.4f, 3.6f, 0.9f * n.scale, 1.5f, 0.92f, 0.9f, 0.7f, 0.16f, false, 0.25f)
            }
        }
    }

    private fun updateAmbience(frozen: Boolean) {
        val p = player
        val v = env.v
        val a = amb
        a.paused = frozen && !menuOpen
        a.menu = menuOpen
        a.wind = env.wind
        a.rain = env.rain
        a.atmo = v.atmo
        a.indoors = p.indoors
        a.underwater = p.underwater
        a.night = env.nightFactor
        a.life = clamp01(v.grass * 0.4f + v.trees * 0.3f + v.motes * 0.3f)
        a.machines = nearMachines
        a.water = if (v.waterLevel > -50f) smoothstep(6f, 0.5f, abs(p.pos.y - v.waterLevel)) * smoothstep(-8f, -1f, v.waterLevel) else 0f
        a.watcher = smoothstep(26f, 5f, nearestWatcher) * (0.5f + 0.5f * env.nightFactor)
        a.hum = clamp01(placed.count { it.type.isMachine } / 12f) * (0.35f + 0.65f * clamp01(stage / 8f))
        val lowAir = !p.sheltered && !v.breathable
        a.breath = clamp01((if (p.sprinting && p.bobAmount > 0.5f) 0.45f else 0.1f) + (if (lowAir) smoothstep(0.3f, 0.02f, p.o2 / p.o2Max) else 0f))
        a.helmet = !v.breathable
        a.stage = stage
        sound?.setAmbience(a)
    }
}

object Fmt {
    private val units = arrayOf("", "k", "M", "G", "T")
    fun wi(v: Double): String {
        var x = v; var u = 0
        while (x >= 1000.0 && u < units.size - 1) { x /= 1000.0; u++ }
        val s = if (x >= 100 || (u == 0 && x == floor(x))) String.format(java.util.Locale.US, "%.0f", x)
        else if (x >= 10) String.format(java.util.Locale.US, "%.1f", x) else String.format(java.util.Locale.US, "%.2f", x)
        return s + units[u]
    }

    fun time(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${s}s"
    }
}
