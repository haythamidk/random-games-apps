package com.worldwake.game.game

/** One recovered crew log from Lantern Nine, the expedition that came to Ysmere before you. */
class CrewLog(val title: String, val author: String, val day: Int, val body: String)

/**
 * The words of the game: WREN (the suit's assistant), the crew logs and the stage announcements.
 * You are Waker Seven, sent alone by the Meridian Charter to finish a job nine people abandoned.
 */
object Story {
    const val INTRO = "The Meridian Charter does not send crews any more. Crews argue, and crews stop.\n\n" +
        "It sends Wakers. One person, one lander, one contract: take a dead world and make it breathe.\n\n" +
        "Thirty one years ago a crew of nine called Lantern Nine landed on the frozen moon Ysmere. They warmed it for " +
        "a hundred and forty days. Then every machine they had built was switched off, by hand, on the same morning. " +
        "Nobody has heard from them since.\n\n" +
        "You are Waker Seven. Your suit has an assistant called WREN who has not stopped talking since orbit.\n\n" +
        "The frost is waiting."

    val logs = arrayOf(
        CrewLog("Arrival", "Capt. Ilse Varga", 3,
            "Nine of us, and a moon nobody else wanted. The lander is down in one piece and Okafor has already taken something apart.\n\n" +
                "Ysmere is colder than the survey said and prettier than the survey could say. The giant fills a third of the sky. Adeyemi calls it Maren, after a grandmother who also never looked away.\n\n" +
                "First coils go up tomorrow. I give it three years until rain. I told the crew two."),
        CrewLog("A Fault That Isn't", "Rin Okafor, engineer", 19,
            "Twelve coils and four bores running. Output is ahead of plan.\n\n" +
                "There is a resonance in the ground when the coils are on. Low, steady, right at the edge of hearing. I logged it as a mounting fault and re-seated every coil. It did not change.\n\n" +
                "It is not in the coils. The coils start it. Something under the frost finishes it."),
        CrewLog("Not Our Spores", "Tomé Adeyemi, botanist", 41,
            "I filled the first vat with meltwater and our own algae stock. The algae died in a day. Something else in the water did not.\n\n" +
                "They are red. They were in the ice the whole time, asleep, and they are not from any catalogue I own. They grow toward the heaters. Not toward the light. Toward the machines.\n\n" +
                "I have stopped using our stock. Theirs works better. I feel like a guest who brought wine to a vineyard."),
        CrewLog("Paint", "Bashir Lund, geologist", 58,
            "The standing stones are basalt, and basalt does not stand like that. No tool marks. No joints. They look grown.\n\n" +
                "I painted a white stripe on the east face of the one by the rover track so I could find it again.\n\n" +
                "This morning the stripe faced the camp. I checked my compass three times and then I checked whether I was the kind of man who lies in his own notebook."),
        CrewLog("The Vote Nobody Called", "Capt. Ilse Varga", 77,
            "The Charter wants numbers and we have good numbers. That should be the end of it.\n\n" +
                "Half the crew wants to slow down. Nobody will say why in a meeting. Okafor is not sleeping. She says the hum has words in it, then says she is joking, then does not laugh.\n\n" +
                "I ordered everyone to keep building. I have never given an order that felt so much like a question."),
        CrewLog("Translator", "Rin Okafor, engineer", 90,
            "For a joke I fed the hum to the suit translator.\n\n" +
                "It gave me text. Our text. My log from day nineteen, word for word. Then the captain's from yesterday. Then one of mine that I have not written yet.\n\n" +
                "It is not talking to us. It is dreaming, and we have wandered into the dream. I do not think it knows we are real. I am no longer sure how I would prove it."),
        CrewLog("Rested", "Dr. Sunniva Hale, medic", 104,
            "The whole crew now sleeps fourteen hours a night and wakes with the same dream. A red forest. A warm lake. Nine people standing in the shallows.\n\n" +
                "I have run every test I brought. Everyone is healthy. Everyone is healthier than when we landed. Lund's knee has stopped clicking.\n\n" +
                "I should be alarmed. I am writing this down so there is a record that I knew I should be alarmed. I am very rested."),
        CrewLog("Winter Is A Choice", "Tomé Adeyemi, botanist", 121,
            "I understand the frost now. It is not what killed this world. It is where this world put itself to wait.\n\n" +
                "The sun dims in long cycles. When the last one came, everything alive here went down into the ice on purpose, and the stones stayed up to keep watch. They are not statues. They are seed vaults with patience.\n\n" +
                "We are waking it, but slowly and badly, with nine people and a hundred machines. A world woken halfway does not go back to sleep. It rots. Either someone finishes this quickly, or we must stop tonight."),
        CrewLog("Off", "Capt. Ilse Varga", 130,
            "The Charter has recalled us. There is no ship. The next window is thirty years out and they will send a contractor, not a crew.\n\n" +
                "We voted properly this time. We cannot finish with what we have, so we stop. Every coil, every bore, every vat, by hand, at dawn.\n\n" +
                "Tomorrow the hum will go quiet. I am the captain and I voted for it and I already miss it."),
        CrewLog("It Opened For Tomé", "Bashir Lund, geologist", 133,
            "There is a door on the rise in the middle of the Sink. It is not ours. It is the same stone as the Watchers.\n\n" +
                "I tried the drill. I tried the pry bar. Tomé put a hand on it and said please, and it opened.\n\n" +
                "Inside there are beds, if beds are grown rather than built. There are nine. Nobody on this crew says the word coincidence any more."),
        CrewLog("For Whoever Is Next", "Rin Okafor, engineer", 139,
            "We go under tomorrow. All nine of us, in the vault, until someone finishes what we started or the sun does it for them.\n\n" +
                "I have left the outposts stocked and the logs unlocked. If you are reading this you are probably alone, and the Charter has probably told you we failed. We did not fail. We ran out of hands.\n\n" +
                "Do not be afraid of the stones. They only look at you because nobody has, for a very long time."),
        CrewLog("To The Next Waker", "Capt. Ilse Varga", 140,
            "We are under the lake that is not there yet.\n\n" +
                "Warm it. Give it air. Let it rain. When the red comes up through the ground, do not stop, whatever the hum says to you in the night. It is only dreaming out loud.\n\n" +
                "Wake the world all the way and it will wake us. If you stop halfway -\n\n" +
                "Please do not stop halfway."),
    )

    /** WREN's reaction after each log is read. */
    val logReplies = arrayOf(
        "Nine people. I have room in my memory for nine people. I checked.",
        "I am sure it is a mounting fault. I am going to stop listening to the ground now.",
        "For the record, I think the red ones are pretty.",
        "Stones do not turn around, Seven. That is the whole point of stones.",
        "She kept building. So will we. That is fine. That is completely fine.",
        "I ran the hum through my translator too. I am not going to tell you what it said. It was your name.",
        "Seven, how did you sleep? No reason.",
        "A world that put itself to bed. I think I like it here.",
        "They switched it all off by hand. That must have been a very quiet morning.",
        "A door that opens if you ask nicely. I have been rude to so many doors.",
        "We ran out of hands. Well. You have two, and I have opinions. Let us finish it.",
        "Under the lake. Seven, they are under the lake. We have to wake it all the way.",
    )

    val stageLines = arrayOf(
        "",
        "Look up. The sky has a colour now. You did that with a few coils and a lot of stubbornness.",
        "The frost is letting go. Listen. That creaking is a whole moon stretching.",
        "Clouds! Real ones! I am going to name them. That one is Gerald.",
        "It is raining. Seven, it is raining on a moon. Stand in it for a second. I will wait.",
        "The basins are filling. The Sink is going to be a lake by the time we are done.",
        "There is red on the ground. Nobody planted that. It was here all along, waiting for us.",
        "Grass. Flowers that glow. I did not know I had been holding my breath, and I do not have breath.",
        "Trees, Seven. Count them later. Right now just look.",
        "Do you see the little lights? They are alive. They keep landing on your helmet.",
        "My sensors say the air is good. Take the helmet off. Go on. I promise.",
        "It is awake. All of it. Seven, the stones are in bloom. Go to the Sink. Go now.",
    )

    val intro = arrayOf(
        "Good morning, Seven. Well. Morning-ish. Welcome to Ysmere.",
        "I am WREN. I live in your suit and I have read the whole manual so you do not have to.",
        "Air is the bar on the left. When it gets low, come back to the lander and I will top you up.",
        "Your supplies are in the lander hold. After that, look for small rocks on the ground. We need ferrite and magnesium.",
    )

    val idle = arrayOf(
        "I have been counting your footsteps. I lost count. I started again.",
        "The giant has storms bigger than this whole moon. It is very relaxing to watch.",
        "If you get lost, the lander is the yellow mark on your compass.",
        "I like the sound your boots make on the frost. Crunch. Crunch.",
        "Machines keep working while you sleep, you know. Slower. They miss you.",
        "Do you ever think the ground is listening? No. Me neither. Forget I said it.",
        "You are doing well. I have nobody to compare you to, but still.",
        "Empty your extractors now and then. They sulk when they are full.",
        "The Charter said one Waker is cheaper than nine people. They did not say lonelier. I am here, though.",
        "I made a list of things I like. It is short. You are on it.",
    )
    val idleLate = arrayOf(
        "It smells like rain and something sweet. I cannot smell. I am guessing from your heart rate.",
        "The hum is in tune today. I checked. It is a major chord.",
        "I think the trees know where we are. In a nice way.",
        "The little lights follow you around. I am not jealous.",
        "Nine heartbeats, very slow. I can hear them from here if I try. I try a lot.",
    )
    val watcherFirst = "That is one of the standing stones from the survey. Just a rock, Seven. A tall, thin, patient rock."
    val watcherMoved = arrayOf(
        "That one was facing east a minute ago. I logged it. I am looking at the log right now.",
        "It moved. I am almost sure it moved. Do not turn your back on it. Or do. I do not know the rules.",
        "They only turn when you are not looking. I find that very rude and a little flattering.",
    )
    val lowO2 = arrayOf("Seven. Air. Now, please.", "Your tank is nearly empty. Lander or pod. Quickly.", "Breathe slowly and walk fast.")
    val died = arrayOf(
        "There you are. I dragged you back. Please do not make me do that again.",
        "You stopped breathing for a bit. I did not enjoy it.",
    )
    val firstNight = "Night. The giant gives us a little light. Stay near your machines, they are warm."
    val firstPod = "A roof! Come in, the air is free in here."
    val firstMachine = "It is running. Feel that under your boots? No? Good. Neither do I."
    val firstExtractor = "It will dig up whatever is under it. Different ground, different ore."
    val hum = "Do you hear that? Low, under everything. It started when the coils did. It is probably the coils."
    val powerLow = "We are short on power. Machines slow down when they are hungry. Build something that makes power."
    val packFull = "Your pack is full. Put some of it in a crate."
    val vaultClosed = "It is a door, and it is very closed. I think it is waiting for the world to finish waking up."
    val offline = "Welcome back. The machines kept going without you, at a fifth of the pace. They do their best."

    val endingTitle = "Awake"
    const val ENDING = "The door on the island is open before you reach it.\n\n" +
        "Inside it is warm and the walls are the same dark stone as the Watchers, and every Watcher on Ysmere is in bloom. " +
        "There are nine beds, grown rather than built. There are nine people in them, and they are breathing.\n\n" +
        "WREN is quiet for the first time since orbit. Then: \"Seven. One of them just opened her eyes.\"\n\n" +
        "Captain Ilse Varga looks at you for a long moment, the way you look at something from a dream to check that it stays. " +
        "\"You did not stop halfway,\" she says.\n\n" +
        "Outside, the red forest runs down to a warm lake, and the low hum under everything has resolved, finally, into something very like a song.\n\n" +
        "Ysmere is awake. The Charter has two more dead worlds on its list, and they do not know yet what you found under this one.\n\n" +
        "You can keep building here for as long as you like."
}
