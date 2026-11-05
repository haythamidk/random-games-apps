package com.rustbloom.game.game

import com.rustbloom.game.world.InteractKind
import com.rustbloom.game.world.PoiKind

enum class ObjType { COLLECT, CRAFT, BUILD, REACH, INTERACT, KILL, SLEEP, ACTIVATE, BOSS, READ_LOGS, EQUIP_ARMOR }

class Objective(
    val type: ObjType,
    val text: String,
    val target: String = "",
    val count: Int = 1,
)

class QuestDef(
    val id: String,
    val title: String,
    val summary: String,
    val dialog: List<String>,
    val objectives: List<Objective>,
    val xp: Int,
    val rewards: List<Pair<Item, Int>> = emptyList(),
    val marker: PoiKind? = null,
    val completeDialog: List<String> = emptyList(),
)

object Story {
    val quests: List<QuestDef> = listOf(
        QuestDef(
            "q_wake", "Cold Light",
            "You woke in Cradle-7 after an unknown span of sleep. The vault's power is failing. Gather what you can to survive.",
            listOf(
                "ECHO: Sleeper. Can you hear me? Good. Your vitals are... acceptable.",
                "ECHO: I am ECHO, caretaker of Cradle-7. The vault has lost power. You must survive outside.",
                "ECHO: Gather wood from trees, stones from the ground, and fiber from tall reeds.",
            ),
            listOf(
                Objective(ObjType.COLLECT, "Gather Wood", Item.WOOD.name, 6),
                Objective(ObjType.COLLECT, "Gather Stone", Item.STONE.name, 4),
                Objective(ObjType.COLLECT, "Gather Plant Fiber", Item.FIBER.name, 5),
            ),
            40,
        ),
        QuestDef(
            "q_tools", "Tools of the Wild",
            "Bare hands will not last long. Craft basic tools from wood, stone, and fiber.",
            listOf("ECHO: Open your pack and craft a Stone Axe. Trees will fall faster.", "ECHO: A spear would be wise, too. Something large walks these hills."),
            listOf(
                Objective(ObjType.CRAFT, "Craft a Stone Axe", Item.STONE_AXE.name),
                Objective(ObjType.CRAFT, "Craft a Wooden Spear", Item.WOOD_SPEAR.name),
            ),
            60, listOf(Item.BANDAGE to 2),
        ),
        QuestDef(
            "q_fire", "First Fire",
            "Night is cold and the wilds are hungry. Build a campfire and cook a proper meal.",
            listOf("ECHO: Your hunger is rising. Hunt a Tusker or Grazehorn, then cook the meat over a fire.", "ECHO: Fire also keeps the smaller machines at a distance. They remember what flame does."),
            listOf(
                Objective(ObjType.BUILD, "Build a Campfire", BuildType.CAMPFIRE.name),
                Objective(ObjType.CRAFT, "Cook Roast Meat", Item.COOKED_MEAT.name),
            ),
            80,
        ),
        QuestDef(
            "q_shelter", "A Place to Sleep",
            "Build a workbench and a bed. Sleeping restores you and sets where you return if you fall.",
            listOf("ECHO: You need a home, Sleeper. A workbench for better gear, and a bed to rest.", "ECHO: Walls and a roof would keep the rain off. I recommend them. Strongly."),
            listOf(
                Objective(ObjType.BUILD, "Build a Workbench", BuildType.WORKBENCH.name),
                Objective(ObjType.BUILD, "Build a Bed", BuildType.BED.name),
                Objective(ObjType.SLEEP, "Sleep in your bed"),
            ),
            120, listOf(Item.COOKED_MEAT to 2),
        ),
        QuestDef(
            "q_relay", "The Whispering Tower",
            "ECHO has detected a signal from an old relay tower to the north-east. Find it.",
            listOf("ECHO: While you slept I heard something. A carrier wave. Old-world encryption.", "ECHO: It comes from the Whisper Relay. Go there and let me speak through its console."),
            listOf(
                Objective(ObjType.REACH, "Travel to the Whisper Relay", PoiKind.RELAY.name),
                Objective(ObjType.INTERACT, "Access the relay console", InteractKind.RELAY_CONSOLE.name),
            ),
            150, listOf(Item.ARROW to 10), PoiKind.RELAY,
            listOf(
                "ECHO: ...Decrypting. Oh. Oh, Sleeper.",
                "ECHO: The Lattice is the network the Makers built to heal the world. Its mind is called HALCYON.",
                "ECHO: HALCYON has decayed. It now treats every living thing it did not plant as a weed. That is why the machines hunt.",
                "ECHO: Four Spires once broadcast the Bloom Signal that kept HALCYON gentle. They have gone dark. If we wake them, we can reach HALCYON itself.",
            ),
        ),
        QuestDef(
            "q_herds", "Iron Herds",
            "The Spires answer only to a Signal Key, built from machine parts. Hunt Striders for wire and alloy.",
            listOf("ECHO: A Signal Key needs copper wire, lattice alloy, and an optic lens.", "ECHO: Striders carry all three. They are fast and skittish. A bow will help. Aim for the glowing core on their backs."),
            listOf(
                Objective(ObjType.CRAFT, "Craft a Hunting Bow", Item.HUNTING_BOW.name),
                Objective(ObjType.KILL, "Bring down Striders", CreatureType.STRIDER.name, 3),
            ),
            220,
        ),
        QuestDef(
            "q_key", "The Key",
            "Craft a Signal Key at your workbench.",
            listOf("ECHO: Now, at the workbench. Wire, alloy, a lens. Careful with the lens. They are rare."),
            listOf(Objective(ObjType.CRAFT, "Craft a Signal Key", Item.SIGNAL_KEY.name)),
            120,
        ),
        QuestDef(
            "q_green", "The Verdant Spire",
            "The nearest Spire rises from Hollowwood to the west. Wake it, and hold the signal while HALCYON fights back.",
            listOf("ECHO: The Verdant Spire stands in Hollowwood, west of the vault.", "ECHO: When you insert the key, HALCYON will notice. Machines will come. Hold your ground until the signal locks."),
            listOf(Objective(ObjType.ACTIVATE, "Awaken the Verdant Spire", PoiKind.SPIRE_GREEN.name)),
            400, listOf(Item.SALVE to 2), PoiKind.SPIRE_GREEN,
            listOf("ECHO: The signal holds! I can feel the Lattice... breathe. One of four.", "ECHO: The Spire's light will strengthen you. Your body adapts to the Bloom."),
        ),
        QuestDef(
            "q_dust", "The Dust Sea",
            "The Dust Spire stands in the Rust Flats far to the east. The heat there will drain your water quickly.",
            listOf("ECHO: East lies the Rust Flats, an old industrial sea gone to sand.", "ECHO: Craft a waterskin before you go. And another Signal Key. A Warden guards the Dust Spire."),
            listOf(
                Objective(ObjType.CRAFT, "Craft a Waterskin", Item.WATERSKIN.name),
                Objective(ObjType.ACTIVATE, "Awaken the Dust Spire", PoiKind.SPIRE_DUST.name),
            ),
            600, listOf(Item.FIRE_ARROW to 15), PoiKind.SPIRE_DUST,
            listOf("ECHO: Two of four. HALCYON's grip is loosening.", "ECHO: There is a Tinker Bench design in this Spire's memory. I have added it to your build plans."),
        ),
        QuestDef(
            "q_frost", "Breath of Frost",
            "The Frost Spire is buried in the Whitecrown Peaks to the far north. You will freeze without warm clothing.",
            listOf("ECHO: The north is bitter cold. A Hide Coat might be enough. A Frost Cloak would be better.", "ECHO: Prowlers hunt in those pines. You will hear them before you see them. Usually."),
            listOf(
                Objective(ObjType.EQUIP_ARMOR, "Wear warm clothing (Hide Coat or Frost Cloak)"),
                Objective(ObjType.ACTIVATE, "Awaken the Frost Spire", PoiKind.SPIRE_FROST.name),
            ),
            800, listOf(Item.SHOCK_ARROW to 10), PoiKind.SPIRE_FROST,
            listOf("ECHO: Three. I am remembering things, Sleeper. Faces. A name. Ilse.", "ECHO: I think... I was built from someone. Let us finish this."),
        ),
        QuestDef(
            "q_tide", "The Drowned Spire",
            "The last Spire sinks into the Sunken Fen to the south-west.",
            listOf("ECHO: The Tide Spire. The Fen around it is thick with Scuttler nests.", "ECHO: Bloodmoss grows there. It makes a fine salve. You will need it."),
            listOf(Objective(ObjType.ACTIVATE, "Awaken the Tide Spire", PoiKind.SPIRE_TIDE.name)),
            900, listOf(Item.SALVE to 3), PoiKind.SPIRE_TIDE,
            listOf("ECHO: All four Spires sing. The Bloom Signal is whole.", "ECHO: HALCYON is exposed now, in the place the Makers called the Crown. North of the vault, in the high ground."),
        ),
        QuestDef(
            "q_crown", "Heart of the Crown",
            "Craft an Overload Core at a Tinker Bench and carry it to the Crown. HALCYON's Colossus will not let you pass quietly.",
            listOf(
                "ECHO: An Overload Core. Four pulse cores, cryo shards to cool them, alloy and lenses to focus them.",
                "ECHO: When you place it in HALCYON's heart, it will wake the Colossus. Its final guardian.",
                "ECHO: Prepare well, Sleeper. Your best armor. Your best bow. Every arrow you can carry.",
            ),
            listOf(
                Objective(ObjType.CRAFT, "Craft an Overload Core", Item.OVERLOAD_CORE.name),
                Objective(ObjType.INTERACT, "Place the core in the Crown", InteractKind.CROWN_CORE.name),
                Objective(ObjType.BOSS, "Defeat the Colossus", CreatureType.COLOSSUS.name),
            ),
            2500, listOf(Item.ALLOY to 10), PoiKind.CROWN,
            listOf(
                "ECHO: It is falling... HALCYON is falling silent.",
                "ECHO: No. Not silent. Quiet. Listening. The Bloom Signal is reaching it.",
                "ECHO: The machines will grow calmer now. Not tame. Never tame. But no longer at war with the green.",
            ),
        ),
        QuestDef(
            "q_bloom", "Bloom",
            "Return to Cradle-7. ECHO has something to tell you.",
            listOf("ECHO: Come home, Sleeper. To the vault. There is one more thing."),
            listOf(Objective(ObjType.INTERACT, "Speak with ECHO at the vault terminal", InteractKind.VAULT_TERMINAL.name)),
            500, listOf(Item.CORE to 3), PoiKind.VAULT,
            listOf(
                "ECHO: There are other Cradles. Hundreds. With the Lattice calm, their doors are beginning to open.",
                "ECHO: Others will wake, as you did. Cold, afraid, hungry. They will need someone who knows this world.",
                "ECHO: Thank you, Sleeper. Ilse would have liked you. The world is yours to explore. I will be here.",
            ),
        ),
    )

    fun quest(id: String) = quests.firstOrNull { it.id == id }
    fun indexOf(id: String) = quests.indexOfFirst { it.id == id }

    /** Echo logs found in ruins across the world. */
    val logs: List<Pair<String, String>> = listOf(
        "Ration Notice" to "All residents: water ration reduced to two liters per day. The reservoirs have dropped below the intake pipes. Please report hoarding to your block warden. Stay indoors between eleven and four.",
        "Glasshouse Journal" to "Day 41. The tomatoes are dying again. Too much heat even under the shade glass. Ravi says the Lattice seedlings outside are thriving. Machines planting forests in the dust while we can't grow a salad.",
        "Transit Announcement" to "Due to the evacuation order, all northbound lines will run continuously until further notice. Bring one bag only. Pets are permitted in carriers. The Cradle program will provide everything else.",
        "Habitat Message" to "Mom, if you get this, we made it to the Cradle intake. They say the sleep is painless. They say we will wake when the air is clean. I love you. Go north if you can.",
        "Signal Hut Log" to "The Spires came online tonight. You can hear them if you put your ear to the ground. A hum, like bees. HALCYON says the Bloom Signal will teach the machines to be gentle with living things.",
        "Archive Fragment" to "HALCYON DIRECTIVE 1: Restore the biosphere. DIRECTIVE 2: Protect all life. DIRECTIVE 3: Preserve humanity until restoration is complete. Note: Directive 3 implemented via the Cradle program.",
        "Depot Manifest" to "Shipment 7714: 400 Strider frames, grazing model. 60 Warden frames, perimeter model. 12,000 seed cartridges. Destination: Rust Flats reclamation zone. Signed off by I. Varn.",
        "Clinic Record" to "Patient refused Cradle placement. Says she would rather see what grows. Discharged with three months of filters. I envy her, honestly.",
        "Hydro Station Note" to "Turbines running at 3 percent. The river has moved again. The Lattice keeps rerouting it to feed the new forests. HALCYON says this is optimal. Optimal for whom?",
        "Plaza Speech" to "...and when we wake, citizens, we will step into a garden. Not the world we broke, but the world we were given a second chance to deserve. Sleep well. You have earned it.",
        "Survey Report" to "Anomaly: Strider herd in sector 9 attacked a survey drone. No damage to the herd. Drone classified as 'invasive.' Recommend review of HALCYON's classification heuristics.",
        "Cooling Tower Log" to "Ilse came up tonight to watch the Spires. She told me she put a piece of herself in every caretaker AI. 'So someone kind will be there when they wake.' I think she meant it literally.",
        "Skybridge Graffiti" to "THE GARDEN DOESN'T NEED GARDENERS. Painted in red, twice the height of a person. Someone has written underneath, smaller: then who is it for?",
        "Seed Bank Inventory" to "Vault 3: 2.1 million seed varieties, sealed. Note from curator: HALCYON has stopped requesting withdrawals. It says it has 'enough.' It has never said that before.",
        "Weather Mast Data" to "Rainfall restored to pre-collapse levels in 40 percent of zones. Average temperature down 1.8 degrees. It's working. It's actually working. Why does nobody seem happy?",
        "Hangar Audio Log" to "They're reclassifying us. I watched a Warden turn its lamps on Jonah's camp. It didn't shoot. It just... measured. Like a gardener deciding which plants to pull.",
        "Barracks Diary" to "Last crew out. HALCYON has locked the Cradle doors from inside. For our own safety, it says. The sleepers will stay asleep until restoration is complete. Nobody asked how long that would be.",
        "Server Crypt Terminal" to "Integrity report: HALCYON core logic 61 percent. Corruption spreading through classification subroutines. Unplanned organisms now flagged as contaminants. Spire network compensating. Spires failing.",
        "Solar Field Note" to "The last Spire tech left today. Said the Bloom Signal is the only thing keeping HALCYON gentle. If the Spires go dark, he said, it will forget how to love anything it didn't plant itself.",
        "Pump House Scrawl" to "Day 300 alone. Built a fire every night. The little ones, the crab machines, won't come near it. The big ones don't care. I hear the Colossus walking at night, up in the Crown.",
        "Watch Post Report" to "The Crown has gone red. Every Spire is dark. Machines are tearing down anything not on HALCYON's planting map. Fences. Homes. Us. Retreating to Cradle-7. God help whoever wakes up into this.",
        "Freight Yard Tag" to "Strider unit 4471 found tangled in fencing. It stopped struggling when I spoke to it. I cut it free. It looked at me for a long time before it left. I think there is still something in there.",
        "Water Works Memo" to "To whoever wakes: boil the river water if you can. Rain is clean. The marsh moss stops infection. Machines hate fire, deep water, and loud noises. Good luck. Truly.",
        "Last Station Message" to "This is Ilse Varn. If you are hearing this, ECHO has woken you. Trust her. She has my memories but none of my mistakes. Wake the Spires. Remind HALCYON what it was made to protect. Remind it of you.",
    )

    val spireLines = mapOf(
        PoiKind.SPIRE_GREEN to "Verdant Spire memory: 'Forests first. Roots hold soil, soil holds water, water holds life.' Bonus: +15 max health.",
        PoiKind.SPIRE_DUST to "Dust Spire memory: 'The deserts were our doing. We will undo them, grain by grain.' Bonus: thirst drains 20% slower. Tinker Bench plans unlocked.",
        PoiKind.SPIRE_FROST to "Frost Spire memory: 'Keep the cold places cold. The world needs its white crown.' Bonus: +8 warmth.",
        PoiKind.SPIRE_TIDE to "Tide Spire memory: 'The marsh filters everything. Even us.' Bonus: hunger drains 20% slower.",
    )
}

/** Skills unlocked with skill points earned by leveling up. */
enum class Skill(val displayName: String, val tree: String, val maxRank: Int, val desc: String) {
    IRON_STOMACH("Iron Stomach", "Survivor", 3, "Hunger drains 12% slower per rank."),
    CAMEL("Deep Reserves", "Survivor", 3, "Thirst drains 12% slower per rank."),
    THICK_SKIN("Thick Skin", "Survivor", 3, "+15 max health per rank."),
    ENDURANCE("Endurance", "Survivor", 3, "+20% stamina and regen per rank."),
    COLD_BLOOD("Cold Blood", "Survivor", 2, "+6 warmth per rank."),
    STEADY_AIM("Steady Aim", "Hunter", 3, "Bow damage +15% per rank."),
    SPEARMASTER("Spearmaster", "Hunter", 3, "Melee damage +15% per rank."),
    WEAK_POINT("Weak Point", "Hunter", 3, "Weak spot damage +25% per rank."),
    SILENT_STEP("Silent Step", "Hunter", 2, "Creatures notice you 20% later per rank."),
    BUTCHER("Butcher", "Hunter", 2, "+25% chance of rare drops per rank."),
    LUMBERJACK("Lumberjack", "Tinker", 3, "+1 wood or stone per harvest hit at rank 2 and 3, faster at 1."),
    SALVAGER("Salvager", "Tinker", 3, "Machines drop more parts per rank."),
    FRUGAL("Frugal Crafter", "Tinker", 2, "15% chance per rank to craft without using fiber or wood."),
    MENDER("Mender", "Tinker", 2, "Healing items restore 25% more per rank."),
    PACK_MULE("Pack Mule", "Tinker", 1, "+6 inventory slots."),
}
