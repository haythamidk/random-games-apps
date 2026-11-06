package com.hadal.game.game

import com.hadal.game.world.PoiType
import com.hadal.game.world.SeaGen
import kotlin.math.sqrt

/** One step of a chapter: text with live progress and a completion check. */
class Step(val text: (GameState) -> String, val done: (GameState) -> Boolean, val onStart: ((GameState) -> Unit)? = null)

class Chapter(val title: String, val intro: List<Message>, val steps: List<Step>, val outro: List<Message>)

/** Crew logs recovered from data cores. */
object Logs {
    val entries: List<Pair<String, String>> = listOf(
        "Lantern, Day 1" to "Station AI ARIA online. Crew of four. Mission: map the shelf and the trench to the north. The surface ship drops supplies every two weeks. Coffee is good. The view is better.",
        "Meridian, Captain Osei" to "Our sonar picked up a sound from the trench. Not whales. A chord. Three notes, very low, repeating every eleven minutes. Ilse says it is geological. Ilse is lying, she is as excited as I am.",
        "Meridian, Engineer Varga" to "Something big bumped the hull at 300 meters. The lights were on. I think the lights brought it. I am writing that down so someone listens: if you hear clicking, kill the lamps.",
        "Meridian, Captain Osei" to "We are taking on water. The crew is moving to Lantern by sub. I am staying with the ship until the barge arrives. If you read this, the barge did not arrive.",
        "Supply Barge, Cargo Manifest" to "Six crates of sealant. Two crates of tea. One record player, marked FRAGILE, marked again in red FRAGILE, marked a third time I MEAN IT.",
        "Lost Trawler, Unknown" to "The nets came up full of white metal. Light as foam. It sang when we hit it with a hammer. We threw it back. We threw it back. We should not have thrown it back.",
        "Cinder Outpost, Dr. Ilse Brandt" to "The vents are warm enough to live by. Crystals here hold heat like a battery holds charge. With enough of them a hull could survive the trench. I have started building one.",
        "Cinder Outpost, Dr. Ilse Brandt" to "The Pale is not hunting us. It is herding us. Away from the trench every time. I think it is guarding something. Or someone. Tomorrow I go down anyway.",
        "Descent Beacon 1" to "Beacon log: diver Kaito Mori passed at 1140 m. Message attached: tell ARIA I am sorry about the coffee machine.",
        "Descent Beacon 2" to "Beacon log: diver Ilse Brandt passed at 1290 m. Message attached: the chord is getting louder. It is not a sound. It is a door.",
        "Descent Beacon 3" to "Beacon log: two subs passed. Then something very large. Then nothing for one hundred and twelve days. Then you.",
        "The Choir" to "They are not dead. They are listening. Everyone who came down here is part of the chord now. It is warm. It is not lonely. If you want to stay, you can. If you want to go home, the Pale will let you. It always did.",
    )

    val echoes: List<String> = listOf(
        "...before the sea had a floor, there was the song...", "...we sang the vents awake...", "...the cold came down from the light...",
        "...we went deeper to stay warm...", "...the long one keeps the door...", "...small lights came, and went, and came...",
        "...one listened...", "...she built a shell of warm stones...", "...the door is a chord of three...",
        "...silence is how we hide...", "...the long one is not cruel, only tired...", "...we remember the surface as a rumour...",
        "...the whales carry our words up...", "...every bone on the plain was a guest...", "...the black stones are our counting...",
        "...the pearls are the eyes of the clams who dreamed...", "...the station sings too, did you know...", "...four lights, now one...",
        "...the trench is not a wound, it is a mouth that hums...", "...you are almost in tune...", "...stay or go, both are kind...",
        "...we will keep a note for you...", "...listen at the edge when the lamps are dark...", "...thank you for coming to visit...",
    )
}

class Story {
    var chapter = 0
    var step = 0
    var started = false
    var forcePale = false
    var finished = false
    var choice = 0 // 0 undecided, 1 stayed, 2 went home

    // persistent flags and counters
    var relayFixed = false
    val logsFound = HashSet<Int>()      // indexes into Logs.entries
    val logsRead = HashSet<Int>()
    val beacons = HashSet<Int>()
    val echoes = HashSet<Int>()
    val collected = IntArray(Item.entries.size)
    var undocked = false
    var deepest = 0f

    private fun poiDist(g: GameState, t: PoiType): Float {
        val p = SeaGen.poi(t)
        val dx = g.sub.pos.x - p.x; val dz = g.sub.pos.z - p.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun countStep(item: Item, n: Int, verb: String) = Step(
        { g -> "$verb ${item.title} (${minOf(n, g.story.collected[item.ordinal] - g.story.baseline(item))}/$n)" },
        { g -> g.story.collected[item.ordinal] - g.story.baseline(item) >= n },
        { g -> g.story.setBaseline(item) },
    )

    private val baselines = HashMap<Item, Int>()
    fun baseline(i: Item) = baselines[i] ?: 0
    fun setBaseline(i: Item) { baselines[i] = collected[i.ordinal] }
    fun restoreBaseline(i: Item, v: Int) { baselines[i] = v }

    private fun upgradeStep(u: Upgrade, lvl: Int, text: String) = Step({ text }, { g -> g.upgrades[u.ordinal] >= lvl })

    val chapters: List<Chapter> = listOf(
        Chapter(
            "I. Lantern",
            listOf(
                Message("ARIA", "Good morning. You have been asleep for nine days. The rest of the crew is gone, and so is the surface ship.", 6f),
                Message("ARIA", "The sub Wren is docked below us. Its hull is rated for 160 meters. Do not go deeper than that until we fix it.", 6f),
                Message("ARIA", "Hold the left stick to move, drag the right side of the screen to look, and use the arrow buttons to rise and sink.", 6f),
            ),
            listOf(
                Step({ "Undock the Wren (press Undock)" }, { g -> g.story.undocked }),
                countStep(Item.SCRAP, 6, "Grab"),
                countStep(Item.COPPER, 2, "Mine"),
                Step({ "Return to Lantern Station and dock" }, { g -> g.docked }),
                upgradeStep(Upgrade.HULL, 1, "Fabricate Hull Plating Mk1 (rated 420 m)"),
            ),
            listOf(Message("ARIA", "Hull reinforced. We can go deeper now. And I can stop pretending not to worry.", 5f)),
        ),
        Chapter(
            "II. The Relay",
            listOf(Message("ARIA", "Our long range sonar runs through the old relay on the shelf edge, to the south west. It went dark the night the crew left. Can you look at it?", 6f)),
            listOf(
                Step({ g -> "Reach the Shelf Relay (${poiDist(g, PoiType.RELAY).toInt()} m)" }, { g -> poiDist(g, PoiType.RELAY) < 22f }),
                Step({ g -> "Repair the relay (needs 4 Copper Ore, have ${g.count(Item.COPPER)})" }, { g -> g.story.relayFixed }),
                Step({ "Return to Lantern Station" }, { g -> g.docked }),
            ),
            listOf(
                Message("ARIA", "Relay online. I can see the whole shelf again. And something else. A wreck to the south east, about 250 meters down. It is the Meridian.", 6f),
                Message("ARIA", "The map now shows everything the relay can hear. Your own sonar ping still finds what it cannot.", 5f),
            ),
        ),
        Chapter(
            "III. Meridian",
            listOf(Message("ARIA", "The Meridian was our support ship. If the crew left logs, they will be on its data cores. Grab them with the arm.", 5f)),
            listOf(
                Step({ g -> "Find the wreck of the Meridian (${poiDist(g, PoiType.MERIDIAN).toInt()} m)" }, { g -> poiDist(g, PoiType.MERIDIAN) < 70f }),
                Step({ g -> "Recover the Meridian data cores (${(1..3).count { g.story.logsFound.contains(it) }}/3)${g.coreHint(1..3)}" }, { g -> (1..3).all { g.story.logsFound.contains(it) } }),
                Step({ "Dock at the station to read the cores" }, { g -> g.docked && (1..3).all { g.story.logsRead.contains(it) } }),
            ),
            listOf(Message("ARIA", "They followed a sound into the trench. I am going to need you to go a lot deeper.", 5f)),
        ),
        Chapter(
            "IV. Midnight",
            listOf(Message("ARIA", "Below 420 meters the sunlight is gone for good. Everything that lives there makes its own light. Scan what you find. I want to know who our neighbours are.", 6f)),
            listOf(
                upgradeStep(Upgrade.HULL, 2, "Fabricate Hull Plating Mk2 (rated 700 m)"),
                Step({ g -> "Scan different species (${minOf(4, g.biodex.size)}/4)" }, { g -> g.biodex.size >= 4 }),
                countStep(Item.NODULE, 8, "Collect"),
                Step({ g -> "Descend below 500 m (deepest ${g.story.deepest.toInt()} m)" }, { g -> g.story.deepest >= 500f }, { g -> g.story.forcePale = true }),
                Step({ "Return to Lantern Station alive" }, { g -> g.docked }),
            ),
            listOf(
                Message("ARIA", "That thing. Varga called it the Pale. It reacts to light and noise. When it is close, kill the lamp and drift.", 6f),
                Message("ARIA", "I have plans for a flare launcher and a shock pulse in the fabricator. Please build them.", 5f),
            ),
        ),
        Chapter(
            "V. Cinder",
            listOf(Message("ARIA", "Dr. Brandt kept a small outpost in the vent field to the west, beyond the plains. It is deep. Bring the hull up first.", 5f)),
            listOf(
                upgradeStep(Upgrade.HULL, 3, "Fabricate Hull Plating Mk3 (rated 1000 m)"),
                Step({ g -> "Find Cinder Outpost in the vent field (${poiDist(g, PoiType.CINDER).toInt()} m)" }, { g -> poiDist(g, PoiType.CINDER) < 50f }),
                Step({ g -> "Recover Dr. Brandt's data cores (${(6..7).count { g.story.logsFound.contains(it) }}/2)${g.coreHint(6..7)}" }, { g -> (6..7).all { g.story.logsFound.contains(it) } }),
                countStep(Item.CRYSTAL, 6, "Collect"),
                Step({ "Dock at the station to read the cores" }, { g -> g.docked && (6..7).all { g.story.logsRead.contains(it) } }),
            ),
            listOf(Message("ARIA", "She went down. They all went down. The beacons on the trench rim will tell us where. If you want to follow them, I will help you. I would rather you did not go alone, but here we are.", 7f)),
        ),
        Chapter(
            "VI. The Edge",
            listOf(Message("ARIA", "The trench lies to the north, past the plains. The crew left three descent beacons on its wall. Wake them up.", 5f)),
            listOf(
                upgradeStep(Upgrade.HULL, 4, "Fabricate Hull Plating Mk4 (rated 1300 m)"),
                Step({ g -> "Reactivate the descent beacons (${g.story.beacons.size}/3)" }, { g -> g.story.beacons.size >= 3 }),
                countStep(Item.ALLOY, 6, "Collect"),
            ),
            listOf(Message("ARIA", "The pale alloy is the only thing that could hold at the bottom. Build the last hull. Then go and find them.", 5f)),
        ),
        Chapter(
            "VII. Hadal",
            listOf(Message("ARIA", "Final hull plans ready. Rated 1800 meters. The bottom of the trench is about 1600. I measured three times.", 5f)),
            listOf(
                upgradeStep(Upgrade.HULL, 5, "Fabricate Hull Plating Mk5 (rated 1800 m)"),
                Step({ g -> "Descend to the Choir at the bottom of the trench (${poiDist(g, PoiType.CHOIR).toInt()} m)" }, { g -> poiDist(g, PoiType.CHOIR) < 90f }),
                Step({ "Touch the Choir" }, { g -> g.story.choice != 0 }),
            ),
            listOf(Message("ARIA", "You came back. I kept the kettle warm. The sea is still out there, and there are still things in it to find. Echo stones, ARIA notes. Twenty four of them. Shall we?", 8f)),
        ),
    )

    val currentChapter: Chapter? get() = chapters.getOrNull(chapter)
    val currentStep: Step? get() = currentChapter?.steps?.getOrNull(step)

    fun objectiveText(g: GameState): String {
        if (finished) return "Free dive: Echo Stones ${echoes.size}/${Logs.echoes.size}  Biodex ${g.biodex.size}/${Species.entries.size}"
        return currentStep?.text?.invoke(g) ?: ""
    }

    /** Advances story steps; returns true if something changed. */
    fun update(g: GameState): Boolean {
        val ch = currentChapter ?: return false
        if (!started) {
            started = true
            for (m in ch.intro) g.say(m)
            ch.steps.getOrNull(step)?.onStart?.invoke(g)
        }
        val st = ch.steps.getOrNull(step) ?: return false
        if (!st.done(g)) return false
        step++
        g.sound.play(Sfx.QUEST, 0.8f)
        if (step >= ch.steps.size) {
            for (m in ch.outro) g.say(m)
            chapter++
            step = 0
            started = false
            if (chapter >= chapters.size) { finished = true; chapter = chapters.size }
            g.requestSave()
        } else {
            ch.steps[step].onStart?.invoke(g)
        }
        return true
    }
}
