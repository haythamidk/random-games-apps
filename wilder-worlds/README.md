# Wilder Worlds

A Minecraft Bedrock add-on (Pocket Edition included) with 8 new biomes, 7 wood sets, a new ore with its own tools and armour, and a light realism pass over the vanilla biomes. Built for 26.60+. See [ABOUT.md](ABOUT.md) for everything it adds.

## Install
1. Download [`release/WilderWorlds.mcaddon`](release/WilderWorlds.mcaddon) on your phone.
2. Open it with Minecraft. The packs import by themselves.
3. Make a **new world**, and turn on **Wilder Worlds** under Behavior Packs. The resource pack comes with it.
4. Optional: turn on **Wilder Worlds Lite Shader** under Resource Packs.

No experimental toggles are needed. To find a biome fast, turn on cheats and run `/locate biome ww:redwood_valley` (the others are listed in [ABOUT.md](ABOUT.md)).

If something is missing or looks wrong, turn on Content Log in Settings > Creator, open the world and read what it prints.

## Status
Version 1.2.0 was run on 26.62 and loaded. **Version 2.1.0 has not been run in the game yet.** What has been checked:

- Every biome, block, item and client biome validates against Mojang's JSON schemas for 1.26.60.
- Tree shapes copy the vanilla definitions that ship with the dedicated server (oak, jungle tree, jungle bush, spruce, pine, giant spruce, dark oak, acacia), with only the blocks swapped. Version 1 guessed the canopy numbers and produced 15-wide trees on 2-block trunks.
- `tools/validate.py` also checks every block, item, texture, recipe ingredient, loot entry and feature reference exists.

Not checkable without the game, so the most likely places for a surprise: how the armour sits on the player model, tool speed on vanilla blocks, and the cross-shaped plants.

## Tech
- **Biomes:** `minecraft:replace_biomes` swaps part of some vanilla biomes for each new one. Bedrock picks the terrain shape before that swap, so an add-on can restyle the land but not reshape it.
- **Features:** 114 features and 84 feature rules. Plain JSON, no scripts, no entities.
- **Blocks:** 32 custom blocks. Leaves and plants use cutout textures like the game's fancy leaves.
- **Items:** 13 custom items. Armour uses the game's own armour model with new 64x32 sheets.
- **Art:** `tools/art.py` draws every texture from code: bark, rings, planks, foliage, stone, ores, item icons and armour sheets.
- **Lite shader:** a separate resource pack. Phones cannot load custom shader code in Bedrock, so it only changes fog, sky colour, water clarity and the sun and moon textures.

## Build
```
python3 tools/build.py [bedrock-samples]     # regenerates the packs and the .mcaddon
python3 tools/validate.py <bedrock-samples>  # checks them (clone Mojang/bedrock-samples first)
python3 tools/art.py sheet.png               # contact sheet of all the textures
```
`build.py` needs Pillow. `validate.py` needs jsonschema. Edit `build.py`, `worldgen.py` and `art.py` rather than the generated files. The samples path is only needed by `build.py` to remake the shader's fog files.

How common each biome is, and how big, is set by `amount` and `scale` in `build_biomes()`. A lower `scale` gives bigger, rarer areas.
