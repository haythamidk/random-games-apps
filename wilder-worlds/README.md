# Wilder Worlds

A Minecraft Bedrock add-on (Pocket Edition included) that adds 4 new biomes and makes the vanilla biomes look more natural. Built for 26.60+. See [ABOUT.md](ABOUT.md) for the biomes and what changes.

## Install
1. Download [`release/WilderWorlds.mcaddon`](release/WilderWorlds.mcaddon) on your phone.
2. Open it with Minecraft. Both packs import by themselves.
3. Make a **new world**, and turn on **Wilder Worlds** under Behavior Packs. The resource pack comes with it.
4. Optional: turn on **Wilder Worlds Lite Shader** under Resource Packs.

No experimental toggles are needed. Chunks you already explored in an old world stay as they were, so a new world is the clean way to see it.

To find a biome fast, turn on cheats and run `/locate biome ww:autumn_forest` (or `ww:pine_highlands`, `ww:limestone_highlands`, `ww:marshland`).

If `/locate` says the biome is unknown, the behavior pack is not active on that world, or the game rejected a file. Turn on Content Log in Settings > Creator to see which.

## Status
The packs pass the checks in `tools/validate.py` against Mojang's schemas and block list for 1.26.60, and the features follow the vanilla definitions that ship with the dedicated server. They have **not been run in the game yet**.

## Tech
- **Biomes:** `minecraft:replace_biomes` swaps part of some vanilla biomes for each new one. Bedrock picks the terrain shape before that swap, so an add-on can restyle the land but not reshape it.
- **Surface:** noise-driven `surface_material_adjustments` and steep-slope materials give podzol, mud, bare rock and limestone bands.
- **Features:** 109 features and 78 feature rules for trees, bushes, dead wood, boulders, ground patches, plants and ponds. They are all plain JSON. There are no scripts and no entities, so nothing runs while you play.
- **Blocks:** 3 autumn leaf blocks. They render as solid blocks, which is the cheapest kind to draw.
- **Lite shader:** a separate resource pack. Phones cannot load custom shader code in Bedrock, so it changes what a pack is allowed to: fog starts at half the render distance for a soft haze, underwater view reaches 1.6x further, and the sun and moon are round. It does not touch Vibrant Visuals and costs no frame rate.

## Build
```
python3 tools/build.py [bedrock-samples]     # regenerates the packs and the .mcaddon
python3 tools/validate.py <bedrock-samples>  # checks them (clone Mojang/bedrock-samples first)
```
`build.py` needs Pillow. `validate.py` needs jsonschema. Edit the tables in `build.py` rather than the generated JSON. The samples path is only needed by `build.py` to remake the shader's fog files.

Biome size and how common each one is are set by `amount` and `scale` in `build_biomes()`. A lower `scale` gives bigger, rarer areas.
