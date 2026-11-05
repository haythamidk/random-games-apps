package com.rustbloom.game.game

import com.rustbloom.game.engine.DEG2RAD
import com.rustbloom.game.engine.Rng
import com.rustbloom.game.engine.TWO_PI
import com.rustbloom.game.engine.Vec3
import com.rustbloom.game.engine.angleDiff
import com.rustbloom.game.engine.clamp
import com.rustbloom.game.engine.yawTo
import com.rustbloom.game.input.Action
import com.rustbloom.game.input.InputState
import com.rustbloom.game.world.Aabb
import com.rustbloom.game.world.Biome
import com.rustbloom.game.world.ChunkManager
import com.rustbloom.game.world.HarvestRegistry
import com.rustbloom.game.world.InteractKind
import com.rustbloom.game.world.Node
import com.rustbloom.game.world.Poi
import com.rustbloom.game.world.PoiKind
import com.rustbloom.game.world.StructureBuilder
import com.rustbloom.game.world.VegType
import com.rustbloom.game.world.WorldGen
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole simulation. Runs on the GL thread; UI code must use [post] to change anything.
 */
class GameState(val settings: Settings) : CreatureHost {
    val commands = ConcurrentLinkedQueue<() -> Unit>()
    fun post(cmd: () -> Unit) { commands.add(cmd) }

    var ui: GameUi? = null
    var sound: SoundSink? = null

    val harvest = HarvestRegistry()
    val chunks = ChunkManager(harvest)
    val collision = Collision(chunks)
    val structures = StructureBuilder().buildAll()
    val env = Environment()
    val player = Player()
    val creatures = ArrayList<Creature>()
    private var nextCreatureId = 1
    val projectiles = ArrayList<Projectile>()
    val placed = ArrayList<Placed>()
    private var nextPlacedId = 1
    val beams = ArrayList<Beam>()

    private val hudBuffers = arrayOf(HudData(), HudData())
    @Volatile var hud: HudData = hudBuffers[0]
        private set
    private var hudIndex = 0

    var difficulty = Difficulty.NORMAL

    // ---- progression
    var questIndex = 0
    val counters = HashMap<String, Int>()
    val baselines = HashMap<String, Int>()
    val spiresActive = HashSet<String>()
    val logsRead = HashSet<Int>()
    val cachesOpened = HashSet<Int>()
    val wreckCooldown = HashMap<Int, Double>()
    val guardianKilledAt = HashMap<Int, Double>()
    val discovered = HashSet<Int>()
    var bossDefeated = false
    var tinkerUnlocked = false
    val bounties = ArrayList<Bounty>()
    var bountyDay = -1
    var playTime = 0.0

    // ---- transient
    var started = false
    @Volatile var paused = true
    var worldReady = false
    var encounter: Encounter? = null
    var boss: Creature? = null
    private val guardSpawned = HashSet<Int>()
    val dialogQueue = ArrayDeque<String>()
    private var dialogTime = 0f
    var currentDialog = ""
    val notes = ArrayList<Note>()
    var buildType: BuildType? = null
    var buildRot = 0
    val buildPos = Vec3()
    var buildValid = false
    val target = Target()
    private var questCheckTimer = 0f
    private var discoverTimer = 0f
    private var spawnTimer = 1f
    private var autosaveTimer = 120f
    private var respawnTimer = 10f
    private var alertSoundCd = 0f
    private var stepTimer = 0f
    private var lastAttack = false
    private var fpsAcc = 0f
    private var fpsFrames = 0
    var fps = 0
    var levelUpFlash = 0f
    var fade = 0f
    var saveRequested = false
    var hint = ""
    private var hintTime = 0f
    val rng = Rng(System.nanoTime())
    var shake = 0f

    val colliderList = ArrayList<Aabb>()

    init {
        collision.setStatic(structures.colliders)
    }

    val quest: QuestDef? get() = Story.quests.getOrNull(questIndex)

    // ------------------------------------------------------------------ setup

    fun newGame(d: Difficulty) {
        difficulty = d
        resetProgress()
        worldReady = false
        val v = WorldGen.vaultPoi
        player.pos.set(v.x, v.y, v.z + 7f)
        player.yaw = 0f
        player.pitch = -0.05f
        player.spawnX = player.pos.x
        player.spawnZ = player.pos.z
        player.inv.add(Item.BERRIES, 4)
        env.time = 7.5f
        env.day = 1
        env.forceWeather(Weather.CLEAR)
        env.nextWeatherIn = 10f
        startQuest(0)
        hintFor("Look around by dragging on the right side of the screen. Walk with the left stick.", 8f)
        started = true
    }

    fun resetProgress() {
        creatures.clear(); projectiles.clear(); placed.clear(); beams.clear()
        player.inv.clear()
        player.hp = 100f; player.hunger = 100f; player.thirst = 100f; player.stamina = 100f
        player.xp = 0; player.level = 1; player.skillPoints = 0
        player.skills.fill(0)
        player.bonusHp = 0f; player.bonusWarmth = 0f; player.thirstPerk = 1f; player.hungerPerk = 1f
        player.dead = false; player.bedId = -1; player.selected = 0
        player.inv.size = 36
        questIndex = 0; counters.clear(); baselines.clear(); spiresActive.clear(); logsRead.clear(); cachesOpened.clear()
        wreckCooldown.clear(); guardianKilledAt.clear(); discovered.clear(); bossDefeated = false; tinkerUnlocked = false
        bounties.clear(); bountyDay = -1; playTime = 0.0
        harvest.respawnAt.clear()
        encounter = null; boss = null; guardSpawned.clear(); dialogQueue.clear(); currentDialog = ""
        notes.clear(); buildType = null
        chunks.releaseAll()
        rebuildColliders()
        nextCreatureId = 1; nextPlacedId = 1
    }

    fun rebuildColliders() {
        colliderList.clear()
        for (p in placed) p.colliders(colliderList)
        collision.setBuilds(colliderList)
    }

    fun nextPlaced(): Int = nextPlacedId++
    fun bumpPlacedId(id: Int) { if (id >= nextPlacedId) nextPlacedId = id + 1 }

    // ------------------------------------------------------------------ main update

    private val frame = InputState.Frame()

    fun update(dt0: Float, input: InputState) {
        while (true) {
            val c = commands.poll() ?: break
            try { c() } catch (t: Throwable) { android.util.Log.e("Rustbloom", "command failed", t) }
        }
        val dt = min(dt0, 0.05f)
        fpsAcc += dt0; fpsFrames++
        if (fpsAcc >= 0.5f) { fps = (fpsFrames / fpsAcc).roundToInt(); fpsAcc = 0f; fpsFrames = 0 }

        chunks.viewRadius = settings.viewDistance
        chunks.highDetailNear = settings.terrainDetail > 0
        chunks.decorDensity = settings.decorDensity
        chunks.nowDays = env.totalDays
        val missing = chunks.update(player.pos.x, player.pos.z, if (worldReady) 2 else 4)
        if (!worldReady && started && missing == 0 && chunks.ready(player.pos.x, player.pos.z)) {
            worldReady = true
            // drop the player onto the ground once terrain is there
            player.pos.y = collision.groundHeight(player.pos.x, player.pos.z, player.pos.y + 50f, 100f)
            ui?.onWorldReady()
        }

        input.poll(frame)
        if (!started) {
            // title screen backdrop: slow camera drift
            player.yaw += dt * 0.04f
            player.pitch = 0.02f
            player.pos.y = WorldGen.height(player.pos.x, player.pos.z) + 0.5f
            env.time = 17.2f
            env.computeLighting()
            fillHud(); return
        }
        if (!worldReady || paused) { fillHud(); return }

        playTime += dt
        if (player.dead) {
            fade = min(1f, fade + dt)
            fillHud(); return
        }
        fade = max(0f, fade - dt * 0.7f)

        val biome = WorldGen.biomeAt(player.pos.x, player.pos.z)
        env.update(dt, settings.dayLengthMinutes, biome, WorldGen.coldness(player.pos.x, player.pos.z))
        if (env.thunderPending in 0f..0.02f) { sound?.play(Sfx.THUNDER, 0.9f); env.thunderPending = -1f }

        handleActions(dt)
        updateLook(dt)
        updatePlayer(dt, biome)
        updateCombat(dt)
        findTarget()
        if (buildType != null) updateBuildPreview()
        updateCreatures(dt)
        updateProjectiles(dt)
        updatePlaced(dt)
        updateEncounter(dt)

        questCheckTimer -= dt
        if (questCheckTimer <= 0f) { questCheckTimer = 0.5f; checkQuest() }
        discoverTimer -= dt
        if (discoverTimer <= 0f) { discoverTimer = 1f; checkDiscovery(); checkBounties() }
        respawnTimer -= dt
        if (respawnTimer <= 0f) { respawnTimer = 15f; chunks.refreshRespawns() }

        updateDialog(dt)
        for (n in notes) n.age += dt
        notes.removeAll { it.age > 5f }
        for (b in beams) b.ttl -= dt
        beams.removeAll { it.ttl <= 0f }
        if (hintTime > 0f) { hintTime -= dt; if (hintTime <= 0f) hint = "" }
        levelUpFlash = max(0f, levelUpFlash - dt)
        shake = max(0f, shake - dt * 2f)
        if (alertSoundCd > 0) alertSoundCd -= dt

        autosaveTimer -= dt
        if (autosaveTimer <= 0f) { autosaveTimer = 120f; saveRequested = true }

        val fire = nearestFireDist()
        sound?.setAmbience(env.wind, if (env.snowing) 0f else env.rain, clamp(1f - fire / 10f, 0f, 1f), env.nightFactor)
        fillHud()
    }

    private fun handleActions(dt: Float) {
        for (a in frame.actions) {
            when (a) {
                Action.JUMP -> {}
                Action.INTERACT -> if (buildType != null) placeBuild() else interact()
                Action.USE_ITEM -> useSelected()
                Action.HOTBAR_NEXT -> selectSlot((player.selected + 1) % 6)
                Action.HOTBAR_PREV -> selectSlot((player.selected + 5) % 6)
                Action.HOTBAR_1 -> selectSlot(0)
                Action.HOTBAR_2 -> selectSlot(1)
                Action.HOTBAR_3 -> selectSlot(2)
                Action.HOTBAR_4 -> selectSlot(3)
                Action.HOTBAR_5 -> selectSlot(4)
                Action.HOTBAR_6 -> selectSlot(5)
                Action.ROTATE -> if (buildType != null) buildRot = (buildRot + 1) % 4
                Action.CANCEL -> if (buildType != null) { buildType = null; note("Build cancelled", 0xffcccccc.toInt()) }
                Action.CYCLE_AMMO -> cycleAmmo()
                Action.CROUCH_TOGGLE -> player.crouching = !player.crouching
                Action.SPRINT_TOGGLE -> player.sprinting = !player.sprinting
                Action.ATTACK_PRESS -> if (buildType != null) placeBuild()
                Action.SKIP_DIALOG -> dialogTime = 99f
                else -> {}
            }
        }
    }

    private fun selectSlot(i: Int) {
        if (player.selected != i) {
            player.selected = i
            player.drawing = false; player.drawAmount = 0f
            sound?.play(Sfx.UI, 0.4f)
        }
    }

    private fun cycleAmmo() {
        val order = listOf(Item.ARROW, Item.FIRE_ARROW, Item.SHOCK_ARROW)
        val start = order.indexOf(player.ammo)
        for (k in 1..3) {
            val n = order[(start + k) % 3]
            if (player.inv.has(n, 1)) { player.ammo = n; note("Ammo: ${n.displayName}", 0xffffd27a.toInt()); return }
        }
        note("No other arrows", 0xffcccccc.toInt())
    }

    private fun updateLook(dt: Float) {
        val sens = settings.lookSensitivity
        val inv = if (settings.invertY) -1f else 1f
        val zoom = if (player.drawing && frame.aim) 0.55f else 1f
        player.yaw -= frame.lookX * sens * DEG2RAD * zoom
        player.pitch -= frame.lookY * sens * DEG2RAD * inv * zoom
        // gamepad right stick: rate based with a response curve
        val px = frame.padLookX; val py = frame.padLookY
        val rate = 170f * sens * zoom
        player.yaw -= px * abs(px) * rate * DEG2RAD * dt
        player.pitch -= py * abs(py) * rate * 0.7f * DEG2RAD * dt * inv
        player.pitch = clamp(player.pitch, -1.45f, 1.45f)
        while (player.yaw > Math.PI) player.yaw -= TWO_PI
        while (player.yaw < -Math.PI) player.yaw += TWO_PI
    }

    private fun updatePlayer(dt: Float, biome: Biome) {
        val p = player
        if (frame.crouch) p.crouching = true
        val moving = abs(frame.moveX) + abs(frame.moveY) > 0.1f
        val wantSprint = (frame.sprint || p.sprinting || (settings.autoSprint && frame.moveY > 0.92f && settings.controls == ControlScheme.TOUCH)) && moving && frame.moveY > 0.2f
        val canSprint = p.stamina > 1f && !p.crouching && !p.swimming
        val sprintNow = wantSprint && canSprint
        if (!moving) p.sprinting = false
        p.sprinting = if (frame.sprint || settings.controls != ControlScheme.TOUCH) sprintNow else (p.sprinting && canSprint) || sprintNow
        if (p.sprinting && p.crouching) p.crouching = false

        var jump = frame.actions.contains(Action.JUMP)
        if (jump && p.crouching) { p.crouching = false; jump = false }
        if (jump && p.onGround && p.stamina < 8f) jump = false
        if (jump && p.onGround) { p.stamina -= 8f; p.staminaDelay = 0.8f }

        val enc = if (p.inv.armor?.item == Item.SCRAP_PLATE) 0.93f else 1f
        val wasSwim = p.swimming
        val impact = PlayerPhysics.step(p, collision, dt, frame.moveX, frame.moveY, jump, enc * (if (p.drawing) 0.55f else 1f))
        if (p.swimming && !wasSwim) sound?.play(Sfx.SPLASH, 0.6f)
        if (impact > 13f) {
            val dmg = (impact - 13f) * 6f
            damagePlayer(dmg, null)
            note("Fall damage", 0xffff8a7a.toInt())
        }
        // footsteps
        if (p.onGround && moving) {
            stepTimer -= dt * (if (p.sprinting) 1.6f else 1f)
            if (stepTimer <= 0f) { stepTimer = 0.42f; sound?.play(Sfx.STEP, if (p.crouching) 0.15f else 0.35f, 0.9f + rng.nextFloat() * 0.2f) }
        }

        // ---- survival stats
        val diff = difficulty.drain
        val hungerRate = 100f / (45f * 60f) * diff * p.hungerPerk * (1f - 0.12f * p.skill(Skill.IRON_STOMACH)) * (if (p.sprinting) 1.4f else 1f)
        var thirstRate = 100f / (32f * 60f) * diff * p.thirstPerk * (1f - 0.12f * p.skill(Skill.CAMEL)) * (if (p.sprinting) 1.4f else 1f)

        // temperature
        val h = p.pos.y
        var ambient = WorldGen.baseTemperature(p.pos.x, p.pos.z, h) + env.temperatureOffset(biome)
        if (env.snowing && env.rain > 0.2f) ambient -= 6f
        val armor = p.inv.armor?.item
        var warmth = (armor?.warmth ?: 0f) + p.bonusWarmth + p.skill(Skill.COLD_BLOOD) * 6f
        if (p.selectedTool() == ToolKind.TORCH) warmth += Item.TORCH.warmth
        val fireDist = nearestFireDist()
        if (fireDist < 7f) warmth += 22f * (1f - fireDist / 7f)
        val sheltered = collision.covered(p.pos.x, p.pos.y, p.pos.z)
        if (sheltered) warmth += 5f
        if (p.swimming) ambient -= 6f
        // warmth matters less when it is already hot
        val felt = if (ambient < 18f) min(ambient + warmth, 22f + warmth * 0.2f) else ambient + warmth * 0.15f
        p.feltTemp += (felt - p.feltTemp) * min(1f, dt * 0.3f)
        if (p.feltTemp > 30f) thirstRate *= 1f + (p.feltTemp - 30f) * 0.08f
        p.hunger = max(0f, p.hunger - hungerRate * dt * (if (p.feltTemp < 5f) 1.5f else 1f))
        p.thirst = max(0f, p.thirst - thirstRate * dt)

        var dmg = 0f
        if (p.hunger <= 0f) dmg += 0.6f
        if (p.thirst <= 0f) dmg += 0.9f
        if (p.feltTemp < -8f) dmg += 1.0f else if (p.feltTemp < 0f) dmg += 0.35f
        if (p.feltTemp > 42f) dmg += 0.6f
        if (p.underwater && p.stamina <= 0f) dmg += 6f
        if (dmg > 0f) damagePlayer(dmg * dt, null, silent = true)
        else if (p.hunger > 50f && p.thirst > 50f && p.hp < p.maxHp) p.hp = min(p.maxHp, p.hp + dt * (if (p.hunger > 80f && p.thirst > 80f) 0.8f else 0.3f))

        // stamina
        val maxSt = p.maxStamina
        when {
            p.sprinting && moving -> { p.stamina -= 13f * dt; p.staminaDelay = 1f }
            p.swimming -> { p.stamina -= 4f * dt; p.staminaDelay = 0.5f }
            p.drawing -> { p.stamina -= 3f * dt; p.staminaDelay = 0.5f }
            else -> {
                if (p.staminaDelay > 0f) p.staminaDelay -= dt
                else p.stamina += 20f * (1f + p.skill(Skill.ENDURANCE) * 0.2f) * dt * (if (p.feltTemp < 0f) 0.6f else 1f) * (if (p.hunger < 15f) 0.5f else 1f)
            }
        }
        p.stamina = clamp(p.stamina, 0f, maxSt)
        if (p.stamina <= 0f) p.sprinting = false
        p.hurtFlash = max(0f, p.hurtFlash - dt * 1.5f)
        p.healFlash = max(0f, p.healFlash - dt * 1.5f)
    }

    // ------------------------------------------------------------------ combat

    private val tv = Vec3()
    private val tv2 = Vec3()
    private val tp = Vec3()
    private val hudEye = Vec3()
    private val hudDir = Vec3()

    fun eyePos(out: Vec3): Vec3 = out.set(player.pos.x, player.pos.y + player.eyeHeight, player.pos.z)

    fun lookDir(out: Vec3): Vec3 {
        val cp = cos(player.pitch)
        return out.set(sin(player.yaw) * cp, sin(player.pitch), cos(player.yaw) * cp)
    }

    private fun updateCombat(dt: Float) {
        val p = player
        val stack = p.selectedStack()
        val tool = stack?.item?.tool ?: ToolKind.NONE
        val attack = frame.attack && buildType == null
        val pressed = attack && !lastAttack
        val released = !attack && lastAttack
        lastAttack = attack

        if (tool == ToolKind.BOW) {
            val drawTime = if (stack!!.item == Item.ALLOY_BOW) 0.55f else 0.85f
            if (attack && p.swingTime < 0f) {
                if (!p.drawing) {
                    if (!p.inv.has(p.ammo, 1)) {
                        // pick any arrow type we have
                        val alt = listOf(Item.ARROW, Item.FIRE_ARROW, Item.SHOCK_ARROW).firstOrNull { p.inv.has(it, 1) }
                        if (alt == null) { if (pressed) note("No arrows! Craft some (wood, stone, fiber)", 0xffff9a7a.toInt()); return }
                        p.ammo = alt
                    }
                    p.drawing = true
                    p.drawAmount = 0f
                    sound?.play(Sfx.BOW_DRAW, 0.5f)
                }
                p.drawAmount = min(1f, p.drawAmount + dt / drawTime)
            } else if (p.drawing && (released || !attack)) {
                if (p.drawAmount > 0.2f) fireArrow(stack!!)
                p.drawing = false
                p.drawAmount = 0f
            }
            return
        }
        p.drawing = false
        if (p.swingTime >= 0f) {
            p.swingTime += dt
            if (!p.swingHit && p.swingTime >= p.swingDuration * 0.4f) {
                p.swingHit = true
                meleeHit()
            }
            if (p.swingTime >= p.swingDuration) p.swingTime = -1f
        } else if (attack && (pressed || p.stamina > 10f)) {
            if (p.stamina < 5f) return
            p.swingDuration = when (tool) { ToolKind.SPEAR -> 0.5f; ToolKind.NONE -> 0.38f; else -> 0.48f }
            p.swingTime = 0f
            p.swingHit = false
            p.stamina -= if (tool == ToolKind.SPEAR) 9f else 6f
            p.staminaDelay = 0.7f
            sound?.play(Sfx.SWING, 0.45f, 0.9f + rng.nextFloat() * 0.2f)
        }
    }

    private fun weaponDamage(stack: ItemStack?): Float {
        val base = when (stack?.item?.tool) {
            null, ToolKind.NONE, ToolKind.WATERSKIN -> 5f
            else -> stack.item.damage
        }
        return base * (1f + 0.15f * player.skill(Skill.SPEARMASTER)) * difficulty.damageDealt
    }

    private fun meleeHit() {
        val p = player
        val eye = eyePos(tv)
        val dir = lookDir(tv2)
        val reach = if (p.selectedTool() == ToolKind.SPEAR) 3.4f else 2.7f
        // creatures first
        var best: Creature? = null
        var bestD = Float.MAX_VALUE
        for (c in creatures) {
            if (!c.alive) continue
            val cx = c.pos.x - eye.x; val cz = c.pos.z - eye.z
            val cy = c.centerY() - eye.y
            val d = sqrt(cx * cx + cz * cz)
            val r = reach + c.type.radius
            if (d > r || abs(cy) > c.type.height + 1.5f) continue
            val dot = (cx * dir.x + cz * dir.z) / max(0.01f, d * sqrt(dir.x * dir.x + dir.z * dir.z))
            if (dot < 0.55f && d > c.type.radius + 0.4f) continue
            if (d < bestD) { bestD = d; best = c }
        }
        val stack = p.selectedStack()
        if (best != null) {
            val weak = best.weakSpot(Vec3())
            val wd = rayPointDist(eye, dir, weak)
            val isWeak = wd < (if (best.type == CreatureType.COLOSSUS) 1.4f else 0.6f)
            var dmg = weaponDamage(stack)
            if (p.sprinting) dmg *= 1.3f
            damageCreature(best, dmg, isWeak, ProjKind.ARROW, melee = true)
            wearSelected(1)
            shake = 0.15f
            return
        }
        // harvesting
        if (target.kind == TargetKind.NODE) {
            harvestHit(target.node!!)
        }
    }

    private fun rayPointDist(o: Vec3, d: Vec3, p: Vec3): Float {
        val px = p.x - o.x; val py = p.y - o.y; val pz = p.z - o.z
        val t = max(0f, px * d.x + py * d.y + pz * d.z)
        val cx = o.x + d.x * t - p.x; val cy = o.y + d.y * t - p.y; val cz = o.z + d.z * t - p.z
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    private fun fireArrow(bow: ItemStack) {
        val p = player
        if (!p.inv.remove(p.ammo, 1)) return
        val kind = when (p.ammo) { Item.FIRE_ARROW -> ProjKind.FIRE_ARROW; Item.SHOCK_ARROW -> ProjKind.SHOCK_ARROW; else -> ProjKind.ARROW }
        val eye = eyePos(Vec3())
        val dir = lookDir(Vec3())
        val speed = 28f + 34f * p.drawAmount + (if (bow.item == Item.ALLOY_BOW) 10f else 0f)
        val dmg = bow.item.damage * p.ammo.damage * (0.35f + 0.65f * p.drawAmount) * (1f + 0.15f * p.skill(Skill.STEADY_AIM)) * difficulty.damageDealt
        val pos = Vec3(eye.x + dir.x * 0.5f, eye.y + dir.y * 0.5f - 0.05f, eye.z + dir.z * 0.5f)
        projectiles.add(Projectile(kind, pos, Vec3(dir.x * speed, dir.y * speed, dir.z * speed), dmg, true))
        sound?.play(Sfx.BOW_RELEASE, 0.7f)
        wearSelected(1)
        if (!p.inv.has(p.ammo, 1)) note("Out of ${p.ammo.displayName}s", 0xffffb07a.toInt())
    }

    private fun wearSelected(n: Int) {
        val s = player.selectedStack() ?: return
        if (!s.item.hasDurability || s.item.tool == ToolKind.WATERSKIN) return
        s.durability -= n
        if (s.durability <= 0) {
            player.inv.slots[player.selected] = null
            note("${s.item.displayName} broke!", 0xffff7a6a.toInt())
            sound?.play(Sfx.HIT_METAL, 0.6f, 0.6f)
        }
    }

    fun damageCreature(c: Creature, dmg0: Float, weak: Boolean, kind: ProjKind, melee: Boolean = false) {
        if (!c.alive) return
        var dmg = dmg0
        if (weak) dmg *= 2f + 0.25f * player.skill(Skill.WEAK_POINT)
        if (c.type.machine) {
            if (kind == ProjKind.SHOCK_ARROW) { dmg *= 1.5f; c.stun = if (c.type == CreatureType.COLOSSUS) 0.6f else 2.5f }
        }
        if (kind == ProjKind.FIRE_ARROW) c.burn = 5f
        c.hp -= dmg
        c.hitFlash = 1f
        c.aggroTime = max(c.aggroTime, 18f)
        c.lastSeen = 0f
        if (c.type.temperament == Temperament.HOSTILE && (c.state == CState.IDLE || c.state == CState.WANDER)) { c.state = CState.ALERT; c.stateTime = 0.5f }
        if (c.type.temperament == Temperament.PASSIVE) { c.state = CState.FLEE; c.stateTime = 0f }
        if (c.type.temperament == Temperament.SKITTISH && c.state != CState.WINDUP) { c.state = CState.FLEE; c.stateTime = 0f }
        sound?.play(if (c.type.machine) Sfx.HIT_METAL else Sfx.HIT_FLESH, if (weak) 1f else 0.7f, if (weak) 1.3f else 1f)
        if (weak) note("Weak spot!", 0xffffe07a.toInt())
        if (c.hp <= 0f) onCreatureKilled(c)
    }

    private fun onCreatureKilled(c: Creature) {
        CreatureAI.kill(c)
        if (c.type.machine) sound?.play(Sfx.MACHINE_DIE, 0.9f)
        count("kill:${c.type.name}")
        gainXp(c.type.xp)
        if (c.guardPoi >= 0) guardianKilledAt[c.guardPoi] = env.totalDays
        if (c.type == CreatureType.COLOSSUS) {
            bossDefeated = true
            boss = null
            note("The Colossus has fallen!", 0xffff6a8a.toInt())
            sound?.play(Sfx.SPIRE, 1f, 0.7f)
            ui?.onVictory()
            for (o in creatures) if (o !== c && o.type.machine && o.alive) { o.aggroTime = 0f; o.encounter = false; o.state = CState.FLEE }
        }
        for (b in bounties) if (!b.claimed && b.kill == c.type) b.progress++
    }

    fun damagePlayer(dmg0: Float, from: Creature?, silent: Boolean = false, applyArmor: Boolean = from != null) {
        if (player.dead) return
        val armor = player.inv.armor?.item?.armor ?: 0f
        val dmg = if (applyArmor) dmg0 * difficulty.damageTaken * (1f - armor) else dmg0
        player.hp -= dmg
        if (!silent || dmg > 2f) {
            player.hurtFlash = min(1f, player.hurtFlash + 0.25f + dmg / 40f)
            sound?.play(Sfx.HURT, 0.7f)
            if (settings.vibration) ui?.vibrate(min(120L, 25L + dmg.toLong() * 3))
            shake = min(0.6f, shake + dmg / 60f)
        }
        if (from != null && player.drawing) { player.drawing = false; player.drawAmount = 0f }
        if (player.hp <= 0f) {
            player.hp = 0f
            player.dead = true
            buildType = null
            sound?.play(Sfx.DEATH, 0.9f)
            encounter?.let { failEncounter("You fell. The Spire's signal fades.") }
            ui?.onPlayerDied()
        }
    }

    fun respawn() {
        val p = player
        val bed = placed.firstOrNull { it.id == p.bedId }
        if (bed != null) p.pos.set(bed.x + 1.5f, bed.y, bed.z) else p.pos.set(p.spawnX, 0f, p.spawnZ)
        p.pos.y = collision.groundHeight(p.pos.x, p.pos.z, 500f, 1000f)
        p.vel.set(0f, 0f, 0f)
        p.hp = p.maxHp * 0.6f
        p.hunger = max(p.hunger, 45f)
        p.thirst = max(p.thirst, 45f)
        p.stamina = p.maxStamina
        p.feltTemp = 18f
        p.dead = false
        if (difficulty == Difficulty.HARD) {
            // lose backpack resources on hard
            for (i in 6 until p.inv.capacity) { val s = p.inv.slots[i]; if (s != null && s.item.category == Category.RESOURCE) p.inv.slots[i] = null }
            note("Your pack was lost.", 0xffff8a7a.toInt())
        }
        // clear hostile creatures near the respawn point
        for (c in creatures) if (c.hostileNow && c.pos.distXZ(p.pos) < 60f && c.type != CreatureType.COLOSSUS) c.removed = true
        creatures.removeAll { it.removed }
        boss?.let { b -> if (b.alive) { b.hp = b.maxHp; b.summonStage = 0 } }
        fade = 1f
        env.advanceHours(4f)
    }

    // ------------------------------------------------------------------ targets & harvesting

    private fun findTarget() {
        target.clear()
        val eye = eyePos(tv)
        val dir = lookDir(tv2)
        val reach = 3.3f
        var best = Float.MAX_VALUE

        fun consider(dist: Float): Boolean = dist < best

        // corpses
        for (c in creatures) {
            if (c.alive || c.looted || c.deadTime < 0.5f) continue
            val cp = tp.set(c.pos.x, c.pos.y + c.type.height * 0.3f, c.pos.z)
            val along = (cp.x - eye.x) * dir.x + (cp.y - eye.y) * dir.y + (cp.z - eye.z) * dir.z
            if (along < 0f || along > reach + c.type.radius * 2) continue
            val d = rayPointDist(eye, dir, cp)
            if (d < c.type.radius + 0.8f && consider(along)) {
                best = along
                target.kind = TargetKind.CORPSE; target.creature = c
                target.label = if (c.type.machine) "Salvage ${c.type.displayName}" else "Harvest ${c.type.displayName}"
            }
        }
        // POI interactables
        for (it in structures.interactables) {
            val dx = it.x - eye.x; val dy = it.y - eye.y; val dz = it.z - eye.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > (reach + it.radius) * (reach + it.radius)) continue
            val along = dx * dir.x + dy * dir.y + dz * dir.z
            if (along < -0.5f) continue
            val d = rayPointDist(eye, dir, tp.set(it.x, it.y, it.z))
            if (d < it.radius && consider(along)) {
                best = along
                target.kind = TargetKind.INTERACT; target.inter = it
                target.label = interactLabel(it.kind, it.poi)
            }
        }
        // placed objects
        for (pl in placed) {
            if (pl.type == BuildType.FOUNDATION || pl.type == BuildType.WALL || pl.type == BuildType.ROOF || pl.type == BuildType.DOORWAY || pl.type == BuildType.SPIKES || pl.type == BuildType.TORCH_POST || pl.type == BuildType.SHOCK_PYLON) continue
            val cp = tp.set(pl.x, pl.y + pl.type.sy * 0.5f, pl.z)
            val along = (cp.x - eye.x) * dir.x + (cp.y - eye.y) * dir.y + (cp.z - eye.z) * dir.z
            if (along < 0f || along > reach + 1f) continue
            val d = rayPointDist(eye, dir, cp)
            if (d < max(pl.hx, pl.hz) + 0.4f && consider(along)) {
                best = along
                target.kind = TargetKind.PLACED; target.placed = pl
                target.label = when (pl.type) {
                    BuildType.CAMPFIRE -> "Cook at Campfire"
                    BuildType.WORKBENCH -> "Use Workbench"
                    BuildType.TINKER_BENCH -> "Use Tinker Bench"
                    BuildType.BED -> if (env.isNight || env.time > 18f) "Sleep" else "Rest (set respawn)"
                    BuildType.CHEST -> "Open Chest"
                    BuildType.RAIN_CATCHER -> "Drink (${pl.water} water)"
                    BuildType.BOUNTY_BOARD -> "View Bounties"
                    else -> pl.type.displayName
                }
            }
        }
        // nodes
        chunks.forEachNodeNear(eye.x, eye.z, reach + 1.5f) { n ->
            if (n.type.decorative) return@forEachNodeNear
            val hy = clamp(eye.y + dir.y * 1.5f, n.y + 0.2f, n.y + (if (n.type.isTree) 3f else 1.0f) * n.scale)
            val cp = tp.set(n.x, hy, n.z)
            val along = (cp.x - eye.x) * dir.x + (cp.y - eye.y) * dir.y + (cp.z - eye.z) * dir.z
            if (along < 0f || along > reach + 0.6f) return@forEachNodeNear
            val rad = when {
                n.type.isTree -> n.type.collide * n.scale + 0.55f
                n.type == VegType.BOULDER -> 1.5f * n.scale
                n.type == VegType.SCRAP_HEAP || n.type == VegType.CRYSTAL -> 1.2f
                else -> 0.75f * n.scale
            }
            val d = rayPointDist(eye, dir, cp)
            if (d < rad && consider(along + 0.3f)) {
                best = along + 0.3f
                target.kind = TargetKind.NODE; target.node = n
                target.label = nodeLabel(n)
            }
        }
        // water
        if (target.kind == TargetKind.NONE) {
            val p = player
            val feetWater = WorldGen.height(p.pos.x, p.pos.z) < WorldGen.WATER - 0.2f
            var lookWater = false
            if (dir.y < -0.2f) {
                val t = (WorldGen.WATER - eye.y) / dir.y
                if (t in 0f..reach + 1f) {
                    val wx = eye.x + dir.x * t; val wz = eye.z + dir.z * t
                    if (WorldGen.height(wx, wz) < WorldGen.WATER - 0.1f) lookWater = true
                }
            }
            if (feetWater || lookWater) {
                target.kind = TargetKind.WATER
                target.label = if (p.selectedStack()?.item == Item.WATERSKIN) "Fill Waterskin" else "Drink Water"
            }
        }
        target.dist = best
        target.sub = when (target.kind) {
            TargetKind.NODE -> nodeSub(target.node!!)
            else -> ""
        }
    }

    private fun nodeLabel(n: Node): String = when (n.type) {
        VegType.BERRY_BUSH -> "Pick Redberries"
        VegType.FIBER_PLANT -> "Gather Fiber"
        VegType.ROCK_SMALL -> "Pick up Stones"
        VegType.HERB -> "Gather Bloodmoss"
        VegType.MUSHROOM -> "Pick Fen Caps"
        VegType.BOULDER -> "Mine Boulder"
        VegType.SCRAP_HEAP -> "Salvage Scrap"
        VegType.CRYSTAL -> "Mine Cryo Crystal"
        VegType.THORN_BUSH -> "Harvest Rustthorn"
        else -> "Chop ${n.type.displayName}"
    }

    private fun nodeSub(n: Node): String {
        val tool = player.selectedTool()
        return when {
            n.type.pickup -> ""
            n.type.isTree && tool != ToolKind.AXE -> "An axe works much faster"
            (n.type == VegType.BOULDER || n.type == VegType.SCRAP_HEAP) && tool != ToolKind.PICK -> "A pickaxe works much faster"
            n.type == VegType.CRYSTAL && tool != ToolKind.PICK -> "Needs a pickaxe"
            else -> "${n.hitsLeft} hits left"
        }
    }

    private fun interactLabel(k: InteractKind, poi: Poi): String = when (k) {
        InteractKind.LOG_TERMINAL -> if (logsRead.contains(poi.logIndex)) "Replay Echo Log" else "Read Echo Log"
        InteractKind.SPIRE_CONSOLE -> if (spiresActive.contains(poi.kind.name)) "${poi.name} (awake)" else "Insert Signal Key"
        InteractKind.RELAY_CONSOLE -> "Access Relay Console"
        InteractKind.CACHE_BOX -> if (cachesOpened.contains(poi.id)) "Empty Cache" else "Open Supply Cache"
        InteractKind.VAULT_TERMINAL -> "Talk to ECHO"
        InteractKind.CROWN_CORE -> if (bossDefeated) "HALCYON Core (quiet)" else "Place Overload Core"
        InteractKind.WRECK_SALVAGE -> "Salvage Wreck"
    }

    private fun give(item: Item, n: Int, silent: Boolean = false) {
        if (n <= 0) return
        val left = player.inv.add(item, n)
        if (!silent) note("+${n - left} ${item.displayName}", 0xffd8f0c0.toInt())
        if (left > 0) note("Pack full! ${item.displayName} lost", 0xffff8a7a.toInt())
    }

    private fun harvestHit(n: Node) {
        val p = player
        val tool = p.selectedTool()
        val tier = p.selectedStack()?.item?.tier ?: 0
        val lumber = p.skill(Skill.LUMBERJACK)
        if (n.type == VegType.CRYSTAL && tool != ToolKind.PICK) { note("You need a pickaxe", 0xffffb07a.toInt()); return }
        if (n.type.pickup) {
            when (n.type) {
                VegType.BERRY_BUSH -> { give(Item.BERRIES, 3 + rng.nextInt(2)); if (rng.chance(0.4f)) give(Item.FIBER, 1) }
                VegType.FIBER_PLANT -> give(Item.FIBER, 3 + (if (rng.chance(0.3f)) 1 else 0))
                VegType.ROCK_SMALL -> give(Item.STONE, 3)
                VegType.HERB -> give(Item.HERB, 2)
                VegType.MUSHROOM -> give(Item.MUSHROOM, 2)
                else -> {}
            }
            sound?.play(Sfx.PICKUP, 0.6f)
            depleteNode(n)
            return
        }
        val right = (n.type.isTree && tool == ToolKind.AXE) || ((n.type == VegType.BOULDER || n.type == VegType.SCRAP_HEAP || n.type == VegType.CRYSTAL) && tool == ToolKind.PICK) || n.type == VegType.THORN_BUSH
        val power = if (right) 1 + (if (tier >= 2) 1 else 0) + (if (lumber >= 1) 1 else 0) else 1
        val bonus = if (lumber >= 2 && right) 1 else 0
        val bonus2 = if (lumber >= 3 && right) 1 else 0
        // wrong tool: only every other hit yields
        val yields = right || rng.chance(0.5f)
        when (n.type) {
            VegType.OAK, VegType.SWAMP_TREE, VegType.DEAD_TREE -> {
                if (yields) give(Item.WOOD, 1 + (if (tier >= 2 && right) 1 else 0) + bonus + bonus2)
                if (n.type == VegType.SWAMP_TREE && rng.chance(0.3f)) give(Item.FIBER, 1)
                sound?.play(Sfx.CHOP, 0.8f, 0.9f + rng.nextFloat() * 0.2f)
            }
            VegType.PINE -> {
                if (yields) give(Item.WOOD, 1 + (if (tier >= 2 && right) 1 else 0) + bonus + bonus2)
                if (rng.chance(0.35f)) give(Item.RESIN, 1)
                sound?.play(Sfx.CHOP, 0.8f, 0.95f + rng.nextFloat() * 0.2f)
            }
            VegType.BOULDER -> {
                if (yields) give(Item.STONE, 1 + (if (right) 1 else 0) + bonus + bonus2)
                if (right && rng.chance(0.1f)) give(Item.SCRAP, 1)
                sound?.play(Sfx.MINE, 0.8f)
            }
            VegType.SCRAP_HEAP -> {
                if (yields) give(Item.SCRAP, 1 + (if (right && tier >= 2) 1 else 0) + bonus)
                if (rng.chance(if (right) 0.35f else 0.15f)) give(Item.WIRE, 1)
                sound?.play(Sfx.HIT_METAL, 0.7f)
            }
            VegType.CRYSTAL -> {
                give(Item.CRYO_SHARD, 1)
                if (rng.chance(0.3f)) give(Item.STONE, 1)
                sound?.play(Sfx.MINE, 0.8f, 1.4f)
            }
            VegType.THORN_BUSH -> {
                give(Item.FIBER, 1)
                sound?.play(Sfx.CHOP, 0.5f, 1.3f)
            }
            else -> {}
        }
        wearSelected(1)
        n.hitsLeft -= power
        if (n.hitsLeft <= 0) {
            when (n.type) {
                VegType.OAK, VegType.PINE, VegType.SWAMP_TREE -> give(Item.WOOD, 2)
                VegType.THORN_BUSH -> give(Item.THORNFRUIT, 1 + rng.nextInt(2))
                VegType.BOULDER -> if (rng.chance(0.25f)) give(Item.SCRAP, 1)
                else -> {}
            }
            depleteNode(n)
        }
    }

    private fun depleteNode(n: Node) {
        n.alive = false
        harvest.harvest(n.key, env.totalDays, n.type.respawnDays)
        chunks.markDirty(n.x, n.z)
        gainXp(if (n.type.pickup) 1 else 3)
    }

    // ------------------------------------------------------------------ interaction

    private fun interact() {
        val p = player
        when (target.kind) {
            TargetKind.NONE -> {}
            TargetKind.NODE -> {
                // interacting with hard nodes performs a tool swing
                if (target.node!!.type.pickup) harvestHit(target.node!!)
                else if (p.swingTime < 0f && p.selectedTool() != ToolKind.BOW) {
                    p.swingDuration = 0.48f; p.swingTime = 0f; p.swingHit = false
                    sound?.play(Sfx.SWING, 0.4f)
                } else if (p.selectedTool() == ToolKind.BOW) harvestHit(target.node!!)
            }
            TargetKind.CORPSE -> lootCorpse(target.creature!!)
            TargetKind.WATER -> drinkWater()
            TargetKind.PLACED -> usePlaced(target.placed!!)
            TargetKind.INTERACT -> usePoi(target.inter!!.kind, target.inter!!.poi)
        }
    }

    private fun lootCorpse(c: Creature) {
        val bonus = 0.25f * player.skill(Skill.BUTCHER)
        val loot = CreatureAI.loot(c, rng, bonus).toMutableList()
        if (c.type.machine && player.skill(Skill.SALVAGER) > 0) {
            val s = player.skill(Skill.SALVAGER)
            if (rng.chance(0.3f * s)) loot.add(Item.WIRE to 1)
            if (rng.chance(0.2f * s)) loot.add(Item.ALLOY to 1)
            if (s >= 3 && rng.chance(0.1f)) loot.add(Item.LENS to 1)
        }
        for ((it, n) in loot) give(it, n)
        c.looted = true
        c.deadTime = max(c.deadTime, 115f)
        sound?.play(if (c.type.machine) Sfx.HIT_METAL else Sfx.PICKUP, 0.6f)
    }

    private fun drinkWater() {
        val p = player
        val s = p.selectedStack()
        if (s?.item == Item.WATERSKIN) {
            s.durability = Item.WATERSKIN.durability
            note("Waterskin filled", 0xff9fd8ff.toInt())
        } else {
            p.thirst = min(100f, p.thirst + 18f)
            note("Refreshing", 0xff9fd8ff.toInt())
        }
        sound?.play(Sfx.DRINK, 0.7f)
    }

    fun useSelected() {
        val p = player
        val s = p.selectedStack() ?: return
        if (s.item == Item.WATERSKIN) {
            if (s.durability <= 0) { note("Waterskin is empty", 0xffcccccc.toInt()); return }
            s.durability--
            p.thirst = min(100f, p.thirst + 25f)
            sound?.play(Sfx.DRINK, 0.7f)
            return
        }
        if (s.item.isConsumable) consume(p.selected)
        else if (s.item.category == Category.ARMOR) equipArmor(p.selected)
    }

    fun consume(slot: Int) {
        val p = player
        val s = p.inv.slots[slot] ?: return
        val it = s.item
        if (it == Item.WATERSKIN) {
            if (s.durability > 0) { s.durability--; p.thirst = min(100f, p.thirst + 25f); sound?.play(Sfx.DRINK, 0.7f) }
            return
        }
        if (!it.isConsumable) return
        val mend = if (it.category == Category.MEDICAL) 1f + 0.25f * p.skill(Skill.MENDER) else 1f
        p.hunger = min(100f, p.hunger + it.hunger)
        p.thirst = min(100f, p.thirst + it.thirst)
        p.hp = clamp(p.hp + it.heal * mend, 1f, p.maxHp)
        if (it.heal > 0) p.healFlash = 1f
        s.count--
        if (s.count <= 0) p.inv.slots[slot] = null
        sound?.play(if (it.category == Category.MEDICAL) Sfx.CRAFT else Sfx.EAT, 0.7f)
        if (it == Item.RAW_MEAT) note("Ugh. Raw meat. Cook it next time.", 0xffffb07a.toInt())
    }

    fun equipArmor(slot: Int) {
        val inv = player.inv
        val s = inv.slots[slot] ?: return
        if (s.item.category != Category.ARMOR) return
        val old = inv.armor
        inv.armor = s
        inv.slots[slot] = old
        note("Wearing ${s.item.displayName}", 0xffd8f0c0.toInt())
        sound?.play(Sfx.CRAFT, 0.5f)
    }

    fun unequipArmor() {
        val inv = player.inv
        val a = inv.armor ?: return
        if (inv.add(a.item, 1, a.durability) == 0) inv.armor = null
    }

    private fun usePlaced(pl: Placed) {
        when (pl.type) {
            BuildType.CAMPFIRE -> ui?.openCrafting(Station.CAMPFIRE)
            BuildType.WORKBENCH -> ui?.openCrafting(Station.WORKBENCH)
            BuildType.TINKER_BENCH -> ui?.openCrafting(Station.TINKER)
            BuildType.CHEST -> ui?.openChest(pl)
            BuildType.BOUNTY_BOARD -> { ensureBounties(); ui?.openBounties() }
            BuildType.RAIN_CATCHER -> {
                if (pl.water <= 0) { note("The catcher is dry. Wait for rain.", 0xffcccccc.toInt()); return }
                val s = player.selectedStack()
                if (s?.item == Item.WATERSKIN && s.durability < Item.WATERSKIN.durability) {
                    val take = min(pl.water, Item.WATERSKIN.durability - s.durability)
                    s.durability += take; pl.water -= take
                    note("Waterskin filled", 0xff9fd8ff.toInt())
                } else {
                    pl.water--
                    player.thirst = min(100f, player.thirst + 30f)
                }
                sound?.play(Sfx.DRINK, 0.7f)
            }
            BuildType.BED -> sleep(pl)
            else -> {}
        }
    }

    fun sleep(bed: Placed) {
        val p = player
        p.bedId = bed.id
        p.spawnX = bed.x; p.spawnZ = bed.z
        val hostileNear = creatures.any { it.alive && it.hostileNow && it.pos.distXZ(p.pos) < 25f }
        if (hostileNear) { note("You can't sleep with enemies nearby!", 0xffff8a7a.toInt()); return }
        if (env.time > 18f || env.time < 6f) {
            val hours = if (env.time > 18f) 24f - env.time + 6.5f else 6.5f - env.time
            env.advanceHours(hours)
            p.hunger = max(5f, p.hunger - hours * 2.2f)
            p.thirst = max(5f, p.thirst - hours * 2.8f)
            p.hp = min(p.maxHp, p.hp + hours * 6f)
            p.stamina = p.maxStamina
            fade = 1f
            count("sleep")
            note("You slept until morning. Respawn point set.", 0xffd8f0c0.toInt())
            saveRequested = true
            creatures.removeAll { it.hostileNow && it.pos.distXZ(p.pos) < 120f && it.type != CreatureType.COLOSSUS }
        } else {
            note("Respawn point set. You can sleep after 18:00.", 0xffd8f0c0.toInt())
            count("sleep_rest")
        }
    }

    private fun usePoi(k: InteractKind, poi: Poi) {
        discovered.add(poi.id)
        when (k) {
            InteractKind.LOG_TERMINAL -> {
                val log = Story.logs.getOrNull(poi.logIndex) ?: return
                if (logsRead.add(poi.logIndex)) {
                    gainXp(30)
                    note("Echo log recovered (${logsRead.size}/${Story.logs.size})", 0xff9fe8e0.toInt())
                }
                sound?.play(Sfx.UI, 0.8f)
                ui?.showLog(log.first, log.second)
            }
            InteractKind.RELAY_CONSOLE -> {
                count("interact:${k.name}")
                for (p in WorldGen.pois) if (p.kind.name.startsWith("SPIRE") || p.kind == PoiKind.CROWN) discovered.add(p.id)
                sound?.play(Sfx.SPIRE, 0.7f, 1.3f)
                if (quest?.id != "q_relay") ui?.showLog("Whisper Relay", "The relay hums with static. ECHO has marked the four Spires and the Crown on your map.")
            }
            InteractKind.CACHE_BOX -> openCache(poi)
            InteractKind.WRECK_SALVAGE -> {
                val ready = (wreckCooldown[poi.id] ?: 0.0) <= env.totalDays
                if (!ready) { note("Nothing left to salvage. Try again in a few days.", 0xffcccccc.toInt()); return }
                give(Item.SCRAP, 4 + rng.nextInt(3)); give(Item.WIRE, 2 + rng.nextInt(2)); give(Item.ALLOY, 1 + rng.nextInt(2))
                if (rng.chance(0.2f)) give(Item.LENS, 1)
                if (rng.chance(0.12f)) give(Item.CORE, 1)
                wreckCooldown[poi.id] = env.totalDays + 2.0
                sound?.play(Sfx.HIT_METAL, 0.8f)
                gainXp(15)
                if (rng.chance(0.5f)) {
                    for (i in 0 until 2) spawnCreature(CreatureType.SCUTTLER, poi.x + rng.range(-12f, 12f), poi.z + rng.range(-12f, 12f), variantFor(poi.x, poi.z))?.let { it.aggroTime = 20f }
                    note("Scuttlers burst from the wreck!", 0xffff8a7a.toInt())
                }
            }
            InteractKind.SPIRE_CONSOLE -> useSpire(poi)
            InteractKind.VAULT_TERMINAL -> {
                count("interact:${k.name}")
                if (quest?.id != "q_bloom") {
                    val msg = when {
                        bossDefeated -> "ECHO: The world is quieter now. Go and see it, Sleeper."
                        spiresActive.size == 4 -> "ECHO: The Crown waits. Build the Overload Core at a Tinker Bench."
                        else -> "ECHO: Vault power at ${max(3, 12 - env.day)} percent. Spires awake: ${spiresActive.size} of 4. Echo logs: ${logsRead.size} of ${Story.logs.size}."
                    }
                    ui?.showLog("Cradle-7 Terminal", msg)
                }
            }
            InteractKind.CROWN_CORE -> {
                if (bossDefeated) { ui?.showLog("HALCYON Core", "The core pulses slowly, the deep green of the Bloom Signal. It is listening."); return }
                if (boss != null && boss!!.alive) { note("Defeat the Colossus!", 0xffff8a7a.toInt()); return }
                if (spiresActive.size < 4) { note("The core is shielded. Awaken all four Spires first.", 0xffffb07a.toInt()); return }
                if (!player.inv.has(Item.OVERLOAD_CORE, 1)) { note("You need an Overload Core.", 0xffffb07a.toInt()); return }
                player.inv.remove(Item.OVERLOAD_CORE, 1)
                count("interact:${k.name}")
                startBoss(poi)
            }
        }
    }

    private fun openCache(poi: Poi) {
        if (!cachesOpened.add(poi.id)) { note("Already emptied", 0xffcccccc.toInt()); return }
        val r = Rng(poi.id.toLong() * 104729 + 7)
        if (poi.kind == PoiKind.CAMP) {
            give(Item.BANDAGE, 2); give(Item.BERRIES, 5); give(Item.FIBER, 4); give(Item.WOOD, 4)
            ui?.showLog("Abandoned Camp", "A note is pinned under a stone: 'Gone north to the relay. Fire keeps the crabs away. Don't trust the big ones. -T'")
        } else {
            give(Item.WOOD, 3 + r.nextInt(4)); give(Item.FIBER, 2 + r.nextInt(4))
            give(Item.ARROW, 5 + r.nextInt(6))
            if (r.chance(0.6f)) give(Item.SCRAP, 2 + r.nextInt(4))
            if (r.chance(0.5f)) give(Item.WIRE, 1 + r.nextInt(3))
            if (r.chance(0.4f)) give(Item.ALLOY, 1 + r.nextInt(2))
            if (r.chance(0.45f)) give(Item.LENS, 1)
            if (r.chance(0.2f)) give(Item.CORE, 1)
            if (r.chance(0.4f)) give(Item.BANDAGE, 1 + r.nextInt(2))
            if (r.chance(0.25f)) give(Item.SALVE, 1)
        }
        gainXp(20)
        sound?.play(Sfx.PICKUP, 0.8f)
    }

    // ------------------------------------------------------------------ spires & boss

    private fun useSpire(poi: Poi) {
        if (spiresActive.contains(poi.kind.name)) {
            ui?.showLog(poi.name, Story.spireLines[poi.kind] ?: "")
            return
        }
        if (encounter != null) { note("Hold the signal!", 0xffffd27a.toInt()); return }
        if (!player.inv.has(Item.SIGNAL_KEY, 1)) { note("You need a Signal Key (crafted at a Workbench)", 0xffffb07a.toInt()); return }
        player.inv.remove(Item.SIGNAL_KEY, 1)
        encounter = Encounter(poi.id, poi.kind.name, if (poi.kind == PoiKind.SPIRE_GREEN) 60f else 75f)
        sound?.play(Sfx.SPIRE, 1f)
        queueDialog(listOf("ECHO: The key is in. The Spire is waking... HALCYON has noticed. Hold this ground!"))
        // guardians
        val (gType, variant) = when (poi.kind) {
            PoiKind.SPIRE_DUST -> CreatureType.WARDEN to Variant.DUST
            PoiKind.SPIRE_TIDE -> CreatureType.WARDEN to Variant.FEN
            PoiKind.SPIRE_FROST -> CreatureType.PROWLER to Variant.FROST
            else -> null to Variant.NORMAL
        }
        if (gType != null) {
            val a = rng.range(0f, TWO_PI)
            spawnCreature(gType, poi.x + cos(a) * 40f, poi.z + sin(a) * 40f, variant)?.let {
                it.encounter = true; it.aggroTime = 999f
                encounter!!.guardianId = it.id
            }
        }
    }

    private fun updateEncounter(dt: Float) {
        val e = encounter ?: return
        val poi = WorldGen.pois.first { it.id == e.poiId }
        val d = player.pos.distXZ(poi.x, poi.z)
        if (d > 110f) { failEncounter("You left the Spire. The signal collapsed. Your key was returned."); return }
        e.time = max(0f, e.time - dt)
        e.waveTimer -= dt
        if (e.waveTimer <= 0f && e.time > 8f) {
            e.wave++
            e.waveTimer = 18f
            val (type, n) = when (poi.kind) {
                PoiKind.SPIRE_GREEN -> CreatureType.SCUTTLER to (2 + e.wave)
                PoiKind.SPIRE_DUST -> CreatureType.SCUTTLER to (3 + e.wave)
                PoiKind.SPIRE_FROST -> (if (e.wave == 2) CreatureType.PROWLER else CreatureType.SCUTTLER) to (if (e.wave == 2) 1 else 3 + e.wave)
                else -> CreatureType.SCUTTLER to (4 + e.wave)
            }
            val v = variantFor(poi.x, poi.z)
            for (i in 0 until n) {
                val a = rng.range(0f, TWO_PI)
                val r = rng.range(30f, 45f)
                spawnCreature(type, poi.x + cos(a) * r, poi.z + sin(a) * r, v)?.let { it.encounter = true; it.aggroTime = 999f }
            }
            note("Wave ${e.wave}! Machines incoming", 0xffff8a7a.toInt())
        }
        val guardianAlive = e.guardianId >= 0 && creatures.any { it.id == e.guardianId && it.alive }
        if (e.time <= 0f && !guardianAlive) {
            encounter = null
            activateSpire(poi)
        }
    }

    private fun failEncounter(msg: String) {
        encounter = null
        player.inv.add(Item.SIGNAL_KEY, 1)
        for (c in creatures) if (c.encounter) { c.encounter = false; c.aggroTime = 5f }
        note(msg, 0xffff8a7a.toInt())
    }

    private fun activateSpire(poi: Poi) {
        spiresActive.add(poi.kind.name)
        for (c in creatures) if (c.encounter && c.alive && c.type != CreatureType.WARDEN) { c.encounter = false; c.aggroTime = 0f; c.state = CState.FLEE; c.stateTime = 0f }
        when (poi.kind) {
            PoiKind.SPIRE_GREEN -> player.bonusHp += 15f
            PoiKind.SPIRE_DUST -> { player.thirstPerk = 0.8f; tinkerUnlocked = true }
            PoiKind.SPIRE_FROST -> player.bonusWarmth += 8f
            PoiKind.SPIRE_TIDE -> player.hungerPerk = 0.8f
            else -> {}
        }
        player.hp = player.maxHp
        gainXp(250)
        sound?.play(Sfx.SPIRE, 1f, 1.2f)
        note("${poi.name} awakened!", 0xff7affc0.toInt())
        ui?.showLog(poi.name, Story.spireLines[poi.kind] ?: "")
        saveRequested = true
    }

    private fun startBoss(poi: Poi) {
        val b = spawnCreature(CreatureType.COLOSSUS, poi.x, poi.z - 18f, Variant.NORMAL, force = true) ?: return
        b.aggroTime = 9999f
        b.yaw = 0f
        boss = b
        sound?.play(Sfx.STOMP, 1f, 0.6f)
        queueDialog(listOf("ECHO: The core is overloading! Something is rising from the Crown...", "ECHO: The Colossus! Aim for the glowing core on its chest!"))
    }

    // ------------------------------------------------------------------ creatures

    override val playerPos: Vec3 get() = player.pos
    override val playerStealth: Float
        get() {
            var s = 1f
            if (player.crouching) s *= 0.55f
            if (player.sprinting) s *= 1.3f
            s *= 1f - 0.2f * player.skill(Skill.SILENT_STEP)
            if (env.isNight && player.selectedTool() == ToolKind.TORCH) s *= 1.3f
            return s
        }
    override val isNight: Boolean get() = env.isNight

    override fun creatureMelee(c: Creature, damage: Float) {
        damagePlayer(damage, c)
    }

    override fun creatureFireBolt(c: Creature, fromX: Float, fromY: Float, fromZ: Float, dirX: Float, dirY: Float, dirZ: Float, damage: Float) {
        val sp = if (c.type == CreatureType.COLOSSUS) 30f else 36f
        projectiles.add(Projectile(ProjKind.BOLT, Vec3(fromX, fromY, fromZ), Vec3(dirX * sp, dirY * sp, dirZ * sp), damage, false))
        playAt(Sfx.BOLT, c.pos, 0.8f)
    }

    override fun creatureStomp(c: Creature, radius: Float, damage: Float) {
        val d = c.pos.distXZ(player.pos)
        playAt(Sfx.STOMP, c.pos, 1f)
        shake = max(shake, if (d < radius * 2) 0.5f else 0.15f)
        if (d < radius + 1f && player.pos.y - c.pos.y < 3f) damagePlayer(damage * (1f - d / (radius + 1f) * 0.5f), c)
    }

    override fun creatureSummon(c: Creature, count: Int) {
        for (i in 0 until count) {
            val a = rng.range(0f, TWO_PI)
            spawnCreature(CreatureType.SCUTTLER, c.pos.x + cos(a) * 12f, c.pos.z + sin(a) * 12f, Variant.CORRUPT, force = true)?.let { it.aggroTime = 999f; it.encounter = true }
        }
        note("The Colossus calls for aid!", 0xffff6a8a.toInt())
    }

    override fun creatureAlerted(c: Creature) {
        if (c.type.machine && alertSoundCd <= 0f) {
            playAt(Sfx.MACHINE_ALERT, c.pos, 0.8f)
            alertSoundCd = 2.5f
        }
    }

    override fun nearFire(x: Float, z: Float): Boolean = placed.any { it.type == BuildType.CAMPFIRE && abs(it.x - x) < 8f && abs(it.z - z) < 8f }

    private fun nearestFireDist(): Float {
        var best = 999f
        for (p in placed) if (p.type == BuildType.CAMPFIRE || p.type == BuildType.TORCH_POST) {
            val d = player.pos.dist(tp.set(p.x, p.y, p.z))
            val eff = if (p.type == BuildType.TORCH_POST) d * 2f else d
            if (eff < best) best = eff
        }
        return best
    }

    private fun playAt(s: Sfx, at: Vec3, vol: Float) {
        val d = at.dist(player.pos)
        if (d > 70f) return
        sound?.play(s, vol * clamp(1f - d / 70f, 0f, 1f))
    }

    fun variantFor(x: Float, z: Float): Variant {
        if (bossDefeated) return Variant.NORMAL
        return when (WorldGen.biomeAt(x, z)) {
            Biome.SNOW, Biome.PINE -> Variant.FROST
            Biome.DESERT -> Variant.DUST
            Biome.MARSH -> Variant.FEN
            else -> Variant.NORMAL
        }
    }

    fun spawnCreature(t: CreatureType, x: Float, z: Float, v: Variant, force: Boolean = false): Creature? {
        val h = WorldGen.height(x, z)
        if (!force && h < 0.4f) return null
        val c = Creature(nextCreatureId++, t, x, z, v)
        c.yaw = rng.range(0f, TWO_PI)
        creatures.add(c)
        return c
    }

    private fun updateCreatures(dt: Float) {
        // index loop: creatures may spawn others (boss summons) during their update
        var ci = 0
        while (ci < creatures.size) {
            val c = creatures[ci++]
            CreatureAI.update(c, dt, this)
            if (c.alive) {
                c.headYaw = 0f
                // keep creatures out of each other and out of the player
                val dx = c.pos.x - player.pos.x; val dz = c.pos.z - player.pos.z
                val d = sqrt(dx * dx + dz * dz)
                val min = c.type.radius + 0.4f
                if (d < min && d > 0.01f) {
                    player.pos.x -= dx / d * (min - d) * 0.6f
                    player.pos.z -= dz / d * (min - d) * 0.6f
                }
            }
        }
        // despawn
        val it = creatures.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val d = c.pos.distXZ(player.pos)
            val remove = c.removed ||
                (!c.alive && (c.deadTime > 120f || (c.looted && c.deadTime > 118f))) ||
                (d > 240f && c.type != CreatureType.COLOSSUS && !c.encounter)
            if (remove) {
                if (c.guardPoi >= 0) guardSpawned.remove(c.guardPoi)
                if (c === boss) boss = null
                it.remove()
            }
        }
        spawnTimer -= dt
        if (spawnTimer <= 0f) { spawnTimer = 1.5f; spawnAround() }
    }

    private fun spawnAround() {
        val p = player.pos
        var wild = 0; var mach = 0
        for (c in creatures) {
            if (!c.alive || c.encounter || c.guardPoi >= 0) continue
            if (c.type.machine) mach++ else wild++
        }
        val night = env.isNight
        val diffAdd = when (difficulty) { Difficulty.STORY -> -2; Difficulty.HARD -> 2; else -> 0 }
        var maxMach = 5 + diffAdd + (if (night) 3 else 0)
        if (bossDefeated) maxMach = (maxMach * 0.6f).toInt()
        val maxWild = 7
        // guardians near POIs
        for (poi in WorldGen.pois) {
            if (poi.kind != PoiKind.WRECK && !poi.kind.name.startsWith("SPIRE") && poi.kind != PoiKind.CROWN) continue
            if (guardSpawned.contains(poi.id)) continue
            val d = p.distXZ(poi.x, poi.z)
            if (d > 160f || d < 50f) continue
            if (poi.kind.name.startsWith("SPIRE") && spiresActive.contains(poi.kind.name)) continue
            if (poi.kind == PoiKind.CROWN && bossDefeated) continue
            val killed = guardianKilledAt[poi.id]
            if (killed != null && env.totalDays - killed < 2.0) continue
            guardSpawned.add(poi.id)
            val v = variantFor(poi.x, poi.z)
            val list = when (poi.kind) {
                PoiKind.WRECK -> listOf(CreatureType.PROWLER, CreatureType.SCUTTLER, CreatureType.SCUTTLER)
                PoiKind.CROWN -> listOf(CreatureType.PROWLER, CreatureType.PROWLER, CreatureType.WARDEN)
                PoiKind.SPIRE_GREEN -> listOf(CreatureType.SCUTTLER, CreatureType.SCUTTLER)
                else -> listOf(CreatureType.SCUTTLER, CreatureType.SCUTTLER, CreatureType.STRIDER)
            }
            for (t in list) {
                val a = rng.range(0f, TWO_PI)
                val r = rng.range(14f, 28f) + poi.radius
                spawnCreature(t, poi.x + cos(a) * r, poi.z + sin(a) * r, if (poi.kind == PoiKind.CROWN) Variant.CORRUPT else v)?.guardPoi = poi.id
            }
        }
        if (wild >= maxWild && mach >= maxMach) return
        val a = rng.range(0f, TWO_PI)
        val r = rng.range(70f, 150f)
        val x = p.x + cos(a) * r
        val z = p.z + sin(a) * r
        if (!WorldGen.inWorld(x, z)) return
        val h = WorldGen.height(x, z)
        if (h < 0.8f) return
        val biome = WorldGen.biomeAt(x, z, h)
        val nearVault = WorldGen.vaultPoi.let { sqrt((it.x - x) * (it.x - x) + (it.z - z) * (it.z - z)) } < 300f
        val nearBed = placed.any { it.type == BuildType.BED && abs(it.x - x) < 45f && abs(it.z - z) < 45f }
        val roll = rng.nextFloat()
        val v = variantFor(x, z)
        val wantMachine = mach < maxMach && (wild >= maxWild || rng.chance(0.5f))
        if (wantMachine) {
            if (nearBed) return
            val t = when (biome) {
                Biome.MEADOW, Biome.BEACH -> if (roll < 0.6f) CreatureType.STRIDER else CreatureType.SCUTTLER
                Biome.FOREST -> if (roll < 0.3f) CreatureType.STRIDER else if (roll < 0.75f || nearVault) CreatureType.SCUTTLER else CreatureType.PROWLER
                Biome.PINE, Biome.SNOW -> if (roll < 0.35f) CreatureType.STRIDER else if (roll < 0.7f) CreatureType.SCUTTLER else CreatureType.PROWLER
                Biome.DESERT -> if (roll < 0.45f) CreatureType.STRIDER else if (roll < 0.93f) CreatureType.SCUTTLER else CreatureType.WARDEN
                Biome.MARSH -> if (roll < 0.75f) CreatureType.SCUTTLER else CreatureType.PROWLER
                else -> return
            }
            val tt = if (nearVault && (t == CreatureType.PROWLER || t == CreatureType.WARDEN)) CreatureType.STRIDER else t
            val group = when (tt) { CreatureType.STRIDER -> 2 + rng.nextInt(3); CreatureType.SCUTTLER -> if (night) 2 + rng.nextInt(2) else 1 + rng.nextInt(2); else -> 1 }
            for (i in 0 until group) spawnCreature(tt, x + rng.range(-6f, 6f), z + rng.range(-6f, 6f), v)
        } else if (wild < maxWild) {
            val t = when (biome) {
                Biome.MEADOW -> if (roll < 0.6f) CreatureType.GRAZEHORN else CreatureType.TUSKER
                Biome.FOREST, Biome.MARSH -> if (roll < 0.35f) CreatureType.GRAZEHORN else CreatureType.TUSKER
                Biome.PINE, Biome.SNOW -> CreatureType.GRAZEHORN
                Biome.DESERT -> if (roll < 0.5f) CreatureType.TUSKER else return
                Biome.BEACH -> CreatureType.TUSKER
                else -> return
            }
            val group = if (t == CreatureType.GRAZEHORN) 2 + rng.nextInt(3) else 1 + rng.nextInt(2)
            for (i in 0 until group) spawnCreature(t, x + rng.range(-5f, 5f), z + rng.range(-5f, 5f), Variant.NORMAL)
        }
    }

    // ------------------------------------------------------------------ projectiles

    private fun updateProjectiles(dt: Float) {
        val wv = Vec3()
        for (pr in projectiles) {
            if (!pr.alive) continue
            pr.life -= dt
            if (pr.life <= 0f) { pr.alive = false; continue }
            pr.prev.set(pr.pos)
            if (pr.kind != ProjKind.BOLT) pr.vel.y -= 9.8f * dt
            val steps = 3
            for (s in 0 until steps) {
                pr.pos.addScaled(pr.vel, dt / steps)
                val h = WorldGen.height(pr.pos.x, pr.pos.z)
                if (pr.pos.y < h || collision.solidAt(pr.pos.x, pr.pos.y, pr.pos.z)) {
                    pr.alive = false
                    if (pr.fromPlayer && rng.chance(0.4f)) recoverArrow(pr.kind)
                    break
                }
                if (pr.fromPlayer) {
                    var hit = false
                    for (c in creatures) {
                        if (!c.alive) continue
                        val dx = pr.pos.x - c.pos.x; val dz = pr.pos.z - c.pos.z
                        val r = c.type.radius + 0.25f
                        if (dx * dx + dz * dz > r * r * 2.2f) continue
                        if (pr.pos.y < c.pos.y - 0.2f || pr.pos.y > c.pos.y + c.type.height + 0.2f) continue
                        c.weakSpot(wv)
                        val wr = if (c.type == CreatureType.COLOSSUS) 1.4f else 0.55f
                        val weak = pr.pos.dist(wv) < wr
                        damageCreature(c, pr.damage, weak, pr.kind)
                        sound?.play(Sfx.ARROW_HIT, 0.6f)
                        pr.alive = false
                        hit = true
                        break
                    }
                    if (hit) break
                } else {
                    val dx = pr.pos.x - player.pos.x; val dz = pr.pos.z - player.pos.z
                    val dy = pr.pos.y - (player.pos.y + 0.9f)
                    if (dx * dx + dz * dz < 0.5f && abs(dy) < 1.1f) {
                        damagePlayer(pr.damage, null, false, applyArmor = true)
                        pr.alive = false
                        break
                    }
                }
            }
        }
        projectiles.removeAll { !it.alive }
    }

    private fun recoverArrow(k: ProjKind) {
        val item = when (k) { ProjKind.ARROW -> Item.ARROW; else -> return }
        player.inv.add(item, 1)
    }

    // ------------------------------------------------------------------ placed objects

    private fun updatePlaced(dt: Float) {
        for (pl in placed) {
            when (pl.type) {
                BuildType.RAIN_CATCHER -> {
                    if (env.rain > 0.3f && !env.snowing) {
                        pl.timer += dt
                        if (pl.timer > 20f) { pl.timer = 0f; pl.water = min(10, pl.water + 1) }
                    }
                }
                BuildType.SHOCK_PYLON -> {
                    pl.timer -= dt
                    if (pl.timer <= 0f) {
                        var best: Creature? = null; var bd = 13f
                        for (c in creatures) {
                            if (!c.alive || !c.type.machine || !c.hostileNow || c.type == CreatureType.COLOSSUS) continue
                            val d = c.pos.distXZ(pl.x, pl.z)
                            if (d < bd) { bd = d; best = c }
                        }
                        if (best != null) {
                            pl.timer = 2.2f
                            beams.add(Beam(Vec3(pl.x, pl.y + 2.5f, pl.z), Vec3(best.pos.x, best.centerY(), best.pos.z), 0.25f, 0xff6fd8ff.toInt()))
                            best.stun = 1f
                            damageCreature(best, 25f, false, ProjKind.SHOCK_ARROW)
                            playAt(Sfx.ZAP, best.pos, 0.8f)
                        } else pl.timer = 0.5f
                    }
                }
                BuildType.SPIKES -> {
                    for (c in creatures) {
                        if (!c.alive || !c.hostileNow) continue
                        if (abs(c.pos.x - pl.x) < pl.hx + c.type.radius && abs(c.pos.z - pl.z) < pl.hz + c.type.radius) {
                            c.hp -= 12f * dt
                            if (c.hp <= 0f) onCreatureKilled(c)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ building

    fun startBuild(t: BuildType) {
        buildType = t
        buildRot = 0
        hintFor(if (settings.controls == ControlScheme.TOUCH) "Tap PLACE to build, ROTATE to turn, X to cancel." else "Attack / Interact to place, Rotate to turn, Cancel to stop.", 5f)
    }

    private fun canAfford(t: BuildType) = t.cost.all { (i, n) -> player.inv.has(i, n) }

    private fun updateBuildPreview() {
        val t = buildType ?: return
        val eye = eyePos(tv)
        val dir = lookDir(tv2)
        val dist = if (t == BuildType.FOUNDATION || t == BuildType.ROOF) 4.5f else 3.5f
        var x = eye.x + dir.x * dist
        var z = eye.z + dir.z * dist
        var y = collision.groundHeight(x, z, eye.y + 1f)
        val playerRot = (((-player.yaw / 1.5707964f).roundToInt() % 4) + 4) % 4
        var rot = (playerRot + buildRot) % 4
        if (t.grid) {
            val g = 3f
            // snap to existing foundations when possible
            val f = placed.filter { it.type == BuildType.FOUNDATION }.minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
            val fd = if (f != null) sqrt((f.x - x) * (f.x - x) + (f.z - z) * (f.z - z)) else 99f
            when (t) {
                BuildType.FOUNDATION -> {
                    if (f != null && fd < 5f) {
                        val ox = ((x - f.x) / g).roundToInt(); val oz = ((z - f.z) / g).roundToInt()
                        x = f.x + ox * g; z = f.z + oz * g; y = f.y
                    } else {
                        x = floor(x / g) * g + g / 2; z = floor(z / g) * g + g / 2
                        y = maxOf(WorldGen.height(x - 1.4f, z - 1.4f), WorldGen.height(x + 1.4f, z - 1.4f), WorldGen.height(x - 1.4f, z + 1.4f), WorldGen.height(x + 1.4f, z + 1.4f)) - 0.1f
                    }
                    rot = 0
                }
                BuildType.WALL, BuildType.DOORWAY -> {
                    if (f != null && fd < 3.5f) {
                        val dx = x - f.x; val dz = z - f.z
                        if (abs(dx) > abs(dz)) { x = f.x + (if (dx > 0) 1.5f else -1.5f); z = f.z; rot = 1 } else { x = f.x; z = f.z + (if (dz > 0) 1.5f else -1.5f); rot = 0 }
                        y = f.y + BuildType.FOUNDATION.sy
                    } else rot %= 2
                }
                BuildType.ROOF -> {
                    if (f != null && fd < 3.5f) { x = f.x; z = f.z; y = f.y + BuildType.FOUNDATION.sy + 3f } else y += 3f
                    rot = 0
                }
                else -> {}
            }
        }
        buildPos.set(x, y, z)
        rotPreview = rot
        val terrain = WorldGen.height(x, z)
        val overlaps = placed.any { it.type == t && abs(it.x - x) < 0.3f && abs(it.z - z) < 0.3f && abs(it.y - y) < 0.3f }
        val hx = (if (rot % 2 == 0) t.sx else t.sz) / 2 - 0.05f
        val hz = (if (rot % 2 == 0) t.sz else t.sx) / 2 - 0.05f
        val hitsStructure = collision.overlapsStatic(x - hx, y + 0.05f, z - hz, x + hx, y + t.sy, z + hz)
        buildValid = terrain > -0.3f && !hitsStructure && canAfford(t) && !overlaps &&
            player.pos.distXZ(x, z) > 0.8f
    }

    var rotPreview = 0

    private fun placeBuild() {
        val t = buildType ?: return
        if (!canAfford(t)) { note("Not enough materials", 0xffff8a7a.toInt()); return }
        if (!buildValid) { note("Can't build here", 0xffff8a7a.toInt()); return }
        for ((i, n) in t.cost) player.inv.remove(i, n)
        val pl = Placed(nextPlaced(), t, buildPos.x, buildPos.y, buildPos.z, rotPreview)
        placed.add(pl)
        rebuildColliders()
        count("build:${t.name}")
        gainXp(4)
        sound?.play(Sfx.BUILD, 0.8f)
        note("Built ${t.displayName}", 0xffd8f0c0.toInt())
        if (t == BuildType.BED && player.bedId < 0) { player.bedId = pl.id; player.spawnX = pl.x; player.spawnZ = pl.z }
        if (!canAfford(t) || !t.grid) buildType = null
    }

    fun demolish(pl: Placed) {
        placed.remove(pl)
        for ((i, n) in pl.type.cost) player.inv.add(i, (n + 1) / 2)
        pl.storage?.let { s -> for (st in s.slots) if (st != null) player.inv.add(st.item, st.count, st.durability) }
        rebuildColliders()
    }

    // ------------------------------------------------------------------ crafting

    fun stationNear(s: Station): Boolean {
        if (s == Station.HAND) return true
        val type = when (s) { Station.CAMPFIRE -> BuildType.CAMPFIRE; Station.WORKBENCH -> BuildType.WORKBENCH; Station.TINKER -> BuildType.TINKER_BENCH; else -> return true }
        return placed.any { it.type == type && it.pos3dist(player.pos) < 5f }
    }

    private fun Placed.pos3dist(v: Vec3): Float { val dx = x - v.x; val dy = y - v.y; val dz = z - v.z; return sqrt(dx * dx + dy * dy + dz * dz) }

    fun craft(r: Recipe): Boolean {
        if (!stationNear(r.station)) { note("You need to be near a ${r.station.displayName}", 0xffff8a7a.toInt()); return false }
        if (!Recipes.canCraft(r, player.inv)) { note("Missing materials", 0xffff8a7a.toInt()); return false }
        val frugal = player.skill(Skill.FRUGAL) * 0.15f
        val skip = rng.chance(frugal)
        for ((i, n) in r.inputs) {
            if (skip && (i == Item.WOOD || i == Item.FIBER)) continue
            player.inv.remove(i, n)
        }
        val left = player.inv.add(r.output, r.amount)
        if (left > 0) note("Pack full!", 0xffff8a7a.toInt())
        count("craft:${r.output.name}")
        gainXp(if (r.station == Station.HAND) 3 else 8)
        sound?.play(Sfx.CRAFT, 0.8f)
        note("Crafted ${r.output.displayName}${if (r.amount > 1) " x${r.amount}" else ""}", 0xffd8f0c0.toInt())
        if (skip) note("Frugal: saved materials", 0xffffe07a.toInt())
        return true
    }

    fun unlockSkill(s: Skill): Boolean {
        val p = player
        if (p.skillPoints <= 0 || p.skills[s.ordinal] >= s.maxRank) return false
        p.skills[s.ordinal]++
        p.skillPoints--
        if (s == Skill.PACK_MULE) p.inv.size = p.inv.capacity
        sound?.play(Sfx.LEVEL_UP, 0.6f)
        return true
    }

    fun gainXp(n: Int) {
        val lv = player.addXp(n)
        if (lv > 0) {
            levelUpFlash = 3f
            sound?.play(Sfx.LEVEL_UP, 0.8f)
            note("Level ${player.level}! +$lv skill point${if (lv > 1) "s" else ""}", 0xffffe07a.toInt())
        }
    }

    // ------------------------------------------------------------------ bounties

    fun ensureBounties() {
        if (bountyDay == env.day && bounties.isNotEmpty()) return
        bountyDay = env.day
        bounties.clear()
        val r = Rng(env.day.toLong() * 7717 + 3)
        val killTypes = listOf(CreatureType.SCUTTLER, CreatureType.STRIDER, CreatureType.TUSKER, CreatureType.GRAZEHORN, CreatureType.PROWLER, CreatureType.WARDEN)
        val deliver = listOf(Item.WOOD, Item.STONE, Item.FIBER, Item.HIDE, Item.SCRAP, Item.WIRE, Item.BERRIES, Item.RESIN, Item.HERB, Item.CRYO_SHARD)
        for (i in 0 until 3) {
            if (i < 2) {
                val maxIdx = if (spiresActive.size >= 2) killTypes.size else 4
                val t = killTypes[r.nextInt(maxIdx)]
                val n = when (t) { CreatureType.WARDEN -> 1; CreatureType.PROWLER -> 2; else -> 3 + r.nextInt(4) }
                val xp = t.xp * n + 40
                val reward = when (t) {
                    CreatureType.WARDEN, CreatureType.PROWLER -> listOf(Item.CORE to 1, Item.LENS to 1)
                    CreatureType.STRIDER -> listOf(Item.ALLOY to 2, Item.SHOCK_ARROW to 5)
                    else -> listOf(Item.ARROW to 15, Item.BANDAGE to 2)
                }
                bounties.add(Bounty("Hunt ${n}x ${t.displayName}", t, null, n, xp, reward))
            } else {
                val it = deliver[r.nextInt(deliver.size)]
                val n = if (it == Item.CRYO_SHARD || it == Item.HERB || it == Item.RESIN) 5 else 15 + r.nextInt(15)
                bounties.add(Bounty("Deliver ${n}x ${it.displayName}", null, it, n, 60 + n * 2, listOf(if (r.chance(0.5f)) Item.LENS to 1 else Item.ALLOY to 2)))
            }
        }
    }

    fun claimBounty(b: Bounty): Boolean {
        if (b.claimed) return false
        if (b.deliver != null) {
            if (!player.inv.has(b.deliver, b.count)) { note("You need ${b.count} ${b.deliver.displayName}", 0xffff8a7a.toInt()); return false }
            player.inv.remove(b.deliver, b.count)
        } else if (!b.complete) return false
        b.claimed = true
        gainXp(b.xp)
        for ((i, n) in b.reward) give(i, n)
        count("bounty")
        sound?.play(Sfx.QUEST, 0.8f)
        return true
    }

    private fun checkBounties() {}

    // ------------------------------------------------------------------ quests

    fun count(key: String, n: Int = 1) { counters[key] = (counters[key] ?: 0) + n }
    private fun counter(key: String) = counters[key] ?: 0

    fun startQuest(i: Int) {
        questIndex = i
        val q = quest ?: return
        baselines.clear()
        for (o in q.objectives) {
            val k = objectiveKey(o)
            if (k != null) baselines[k] = counter(k)
        }
        queueDialog(q.dialog)
    }

    private fun objectiveKey(o: Objective): String? = when (o.type) {
        ObjType.KILL -> "kill:${o.target}"
        ObjType.SLEEP -> "sleep"
        ObjType.INTERACT -> "interact:${o.target}"
        else -> null
    }

    fun objectiveProgress(o: Objective): Int = when (o.type) {
        ObjType.COLLECT -> min(o.count, player.inv.count(Item.valueOf(o.target)))
        ObjType.CRAFT -> if (counter("craft:${o.target}") > 0) 1 else 0
        ObjType.BUILD -> if (counter("build:${o.target}") > 0 || placed.any { it.type.name == o.target }) 1 else 0
        ObjType.REACH -> {
            val poi = WorldGen.pois.firstOrNull { it.kind.name == o.target }
            if (poi != null && (player.pos.distXZ(poi.x, poi.z) < poi.radius + 25f || counter("reach:${o.target}") > 0)) 1 else 0
        }
        ObjType.ACTIVATE -> if (spiresActive.contains(o.target)) 1 else 0
        ObjType.BOSS -> if (bossDefeated) 1 else 0
        ObjType.READ_LOGS -> min(o.count, logsRead.size)
        ObjType.EQUIP_ARMOR -> if ((player.inv.armor?.item?.warmth ?: 0f) >= 10f) 1 else 0
        ObjType.KILL, ObjType.SLEEP, ObjType.INTERACT -> {
            val k = objectiveKey(o)!!
            min(o.count, counter(k) - (baselines[k] ?: 0))
        }
    }

    private fun checkQuest() {
        val q = quest ?: return
        // REACH objectives latch once reached
        for (o in q.objectives) if (o.type == ObjType.REACH && objectiveProgress(o) >= 1) counters["reach:${o.target}"] = 1
        val done = q.objectives.all { objectiveProgress(it) >= it.count }
        if (!done) return
        gainXp(q.xp)
        for ((i, n) in q.rewards) give(i, n)
        sound?.play(Sfx.QUEST, 0.9f)
        note("Quest complete: ${q.title}", 0xff7affc0.toInt())
        if (q.completeDialog.isNotEmpty()) queueDialog(q.completeDialog)
        saveRequested = true
        if (questIndex + 1 < Story.quests.size) startQuest(questIndex + 1)
        else {
            questIndex = Story.quests.size
            note("Main story complete! Free roam unlocked.", 0xffffe07a.toInt())
        }
    }

    fun queueDialog(lines: List<String>) {
        for (l in lines) dialogQueue.add(l)
        if (currentDialog.isEmpty()) dialogTime = 99f
    }

    private fun updateDialog(dt: Float) {
        dialogTime += dt
        val dur = 2.5f + currentDialog.length * 0.045f
        if (currentDialog.isEmpty() || dialogTime > dur) {
            currentDialog = dialogQueue.pollFirst() ?: ""
            dialogTime = 0f
        }
    }

    private fun checkDiscovery() {
        for (p in WorldGen.pois) {
            if (discovered.contains(p.id)) continue
            if (player.pos.distXZ(p.x, p.z) < 90f + p.radius) {
                discovered.add(p.id)
                note("Discovered: ${p.name}", 0xffbfe4ff.toInt())
                gainXp(10)
            }
        }
    }

    fun note(text: String, color: Int) {
        notes.add(Note(text, color))
        if (notes.size > 5) notes.removeAt(0)
    }

    fun hintFor(text: String, time: Float) { hint = text; hintTime = time }

    // ------------------------------------------------------------------ hud

    private fun fillHud() {
        hudIndex = 1 - hudIndex
        val h = hudBuffers[hudIndex]
        val p = player
        h.loading = !worldReady
        h.hp = p.hp; h.maxHp = p.maxHp
        h.hunger = p.hunger; h.thirst = p.thirst
        h.stamina = p.stamina; h.maxStamina = p.maxStamina
        h.temp = p.feltTemp
        h.tempState = when { p.feltTemp < -8f -> -2; p.feltTemp < 3f -> -1; p.feltTemp > 42f -> 2; p.feltTemp > 32f -> 1; else -> 0 }
        h.time = env.time; h.day = env.day
        h.weather = if (env.snowing && env.weather.rain > 0f) "Snow" else env.weather.label
        h.biome = WorldGen.biomeAt(p.pos.x, p.pos.z).displayName
        h.fps = fps
        val q = quest
        if (q != null) {
            h.questTitle = q.title
            h.objectiveCount = min(4, q.objectives.size)
            for (i in 0 until h.objectiveCount) {
                val o = q.objectives[i]
                val pr = objectiveProgress(o)
                h.objectives[i] = if (o.count > 1) "${o.text} (${min(pr, o.count)}/${o.count})" else o.text
                h.objectiveDone[i] = pr >= o.count
            }
        } else {
            h.questTitle = "Endless Wilds"
            h.objectiveCount = 1
            h.objectives[0] = "Echo logs found: ${logsRead.size}/${Story.logs.size}"
            h.objectiveDone[0] = logsRead.size >= Story.logs.size
        }
        for (i in 0 until 6) {
            val s = p.inv.slots[i]
            h.hotbarName[i] = s?.item?.displayName
            h.hotbarShort[i] = s?.item?.shortName
            h.hotbarCount[i] = s?.count ?: 0
            h.hotbarDur[i] = if (s != null && s.item.hasDurability) s.durability.toFloat() / s.item.durability else -1f
            h.hotbarColor[i] = s?.item?.color ?: 0
        }
        h.selected = p.selected
        h.showAmmo = p.selectedTool() == ToolKind.BOW
        h.ammoName = p.ammo.displayName
        h.ammoCount = p.inv.count(p.ammo)
        h.hasTool = p.selectedStack() != null
        h.prompt = target.label
        h.promptSub = target.sub
        // creature under crosshair
        h.targetName = ""
        val eye = eyePos(hudEye); val dir = lookDir(hudDir)
        var bestT = 60f
        for (c in creatures) {
            if (!c.alive) continue
            val cp = tp.set(c.pos.x, c.centerY(), c.pos.z)
            val t = (cp.x - eye.x) * dir.x + (cp.y - eye.y) * dir.y + (cp.z - eye.z) * dir.z
            if (t < 0 || t > bestT) continue
            if (rayPointDist(eye, dir, cp) < c.type.radius + c.type.height * 0.4f) {
                bestT = t
                h.targetName = (if (c.variant != Variant.NORMAL && c.type.machine) "${c.variant.name.lowercase().replaceFirstChar { it.uppercase() }} " else "") + c.type.displayName
                h.targetHp = c.hp / c.maxHp
                h.targetHostile = c.hostileNow
            }
        }
        h.bowDraw = if (p.drawing) p.drawAmount else 0f
        h.aiming = p.drawing
        h.hurt = p.hurtFlash; h.heal = p.healFlash
        h.compassYaw = p.yaw
        // markers
        var m = 0
        fun marker(x: Float, z: Float, label: String, color: Int) {
            if (m >= 8) return
            val dx = x - p.pos.x; val dz = z - p.pos.z
            h.markerAngle[m] = angleDiff(yawTo(dx, dz), p.yaw)
            h.markerLabel[m] = label
            h.markerColor[m] = color
            h.markerDist[m] = sqrt(dx * dx + dz * dz)
            m++
        }
        val qm = q?.marker ?: (if (q?.id == "q_relay") PoiKind.RELAY else null)
        if (qm != null) WorldGen.pois.firstOrNull { it.kind == qm }?.let { marker(it.x, it.z, it.name, 0xffffd24a.toInt()) }
        placed.firstOrNull { it.id == p.bedId }?.let { marker(it.x, it.z, "Home", 0xff8fe08f.toInt()) }
        for (poi in WorldGen.pois) {
            if (!poi.kind.name.startsWith("SPIRE") || !discovered.contains(poi.id) || poi.kind == qm) continue
            marker(poi.x, poi.z, poi.name, if (spiresActive.contains(poi.kind.name)) 0xff7affc0.toInt() else 0xff9fb8d0.toInt())
        }
        h.markerCount = m
        val b = boss
        if (b != null && b.alive && b.pos.distXZ(p.pos) < 160f) { h.bossName = b.type.displayName; h.bossHp = b.hp / b.maxHp } else h.bossName = ""
        val e = encounter
        h.encounterTime = e?.time ?: -1f
        h.encounterLabel = if (e != null) (if (e.time <= 0f) "Defeat the guardian!" else "Hold the signal") else ""
        h.dead = p.dead
        h.fade = fade
        h.level = p.level
        h.xpFrac = p.xp.toFloat() / p.xpForNext()
        h.skillPoints = p.skillPoints
        h.dialog = currentDialog
        h.noteCount = min(5, notes.size)
        for (i in 0 until h.noteCount) {
            val n = notes[notes.size - h.noteCount + i]
            h.notes[i] = n.text; h.noteAge[i] = n.age; h.noteColor[i] = n.color
        }
        h.buildMode = buildType != null
        h.buildName = buildType?.displayName ?: ""
        h.buildValid = buildValid
        h.underwater = p.underwater
        h.hint = hint
        h.crouching = p.crouching
        h.sprinting = p.sprinting
        h.flashLevelUp = levelUpFlash
        h.stealthy = p.crouching
        hud = h
    }
}
