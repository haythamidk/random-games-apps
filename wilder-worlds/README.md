# Wilder Worlds

A Minecraft Bedrock add-on (Pocket Edition included) that adds 4 new biomes and makes the vanilla biomes look more natural. Built for 26.60+. See [ABOUT.md](ABOUT.md) for the biomes and what changes.

## Install
1. Download [`release/WilderWorlds.mcaddon`](release/WilderWorlds.mcaddon) on your phone.
2. Open it with Minecraft. Both packs import by themselves.
3. Make a **new world**, and turn on **Wilder Worlds** under Behavior Packs. The resource pack comes with it.

No experimental toggles are needed. Chunks you already explored in an old world stay as they were, so a new world is the clean way to see it.

To find a biome fast, turn on cheats and run `/locate biome ww:autumn_forest` (or `ww:pine_highlands`, `ww:limestone_highlands`, `ww:marshland`).

## Status
The packs pass the checks in `tools/validate.py` against Mojang's schemas and block list for 1.26.60, but they have **not been run in the game yet**. If something looks wrong, turn on Content Log in Settings > Creator and send what it prints.

## Tech
- **Biomes:** `minecraft:replace_biomes` swaps part of some vanilla biomes for each new one. Bedrock picks the terrain shape before that swap, so an add-on can restyle the land but not reshape it.
- **Surface:** noise-driven `surface_material_adjustments` and steep-slope materials give podzol, mud, bare rock and limestone bands.
- **Features:** 109 features and 78 feature rules for trees, bushes, dead wood, boulders, ground patches, plants and ponds. They are all plain JSON. There are no scripts and no entities, so nothing runs while you play.
- **Blocks:** 3 autumn leaf blocks. They render as solid blocks, which is the cheapest kind to draw.

## Build
```
python3 tools/build.py                       # regenerates both packs and the .mcaddon
python3 tools/validate.py <bedrock-samples>  # checks them (clone Mojang/bedrock-samples first)
```
`build.py` needs Pillow. `validate.py` needs jsonschema. Edit the tables in `build.py` rather than the generated JSON.

Biome size and how common each one is are set by `amount` and `scale` in `build_biomes()`. A lower `scale` gives bigger, rarer areas.
