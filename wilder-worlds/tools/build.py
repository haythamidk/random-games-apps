#!/usr/bin/env python3
"""Generates the Wilder Worlds add-on (behavior pack, resource pack, .mcaddon).

Run from anywhere:  python3 wilder-worlds/tools/build.py [bedrock-samples]

The bedrock-samples path (https://github.com/Mojang/bedrock-samples) is only
needed to regenerate the lite shader's fog files from the vanilla ones.

Everything under behavior_pack/, resource_pack/ and shader_pack/ is produced by
this script, so edit the tables here rather than the generated JSON.
  worldgen.py  feature, rule, biome and shader helpers
  art.py       every texture
"""
import shutil
import sys
import zipfile
from pathlib import Path

import art
from worldgen import *  # noqa: F401,F403  (helpers and shared block lists)

FV_ITEM = "1.26.30"
FV_RECIPE = "1.20.10"
FV_ATTACHABLE = "1.21.70"

BLOCKS = {}
ITEMS = {}
RECIPES = {}
LOOT = {}
ATTACHABLES = {}
FOGS = {}
LANG = []
SOUNDS = {}

# --------------------------------------------------------------------------
# Blocks
# --------------------------------------------------------------------------
PICKAXE, AXE, SHOVEL = "minecraft:is_pickaxe", "minecraft:is_axe", "minecraft:is_shovel"


def mining(seconds, tool=None, speed=6):
    data = {"seconds_to_destroy": seconds}
    if tool:
        data["item_specific_speeds"] = [
            {"item": {"tags": "q.any_tag('%s')" % tool}, "destroy_speed": speed}]
    return data


def block(name, display, sound, group, mine, textures=None, method="opaque",
          map_colour="#808080", extra=None, traits=None, permutations=None,
          category="construction"):
    instances = textures or {"*": name}
    desc = {"identifier": ww(name), "menu_category": {"category": category}}
    if group:
        desc["menu_category"]["group"] = group
    if traits:
        desc["traits"] = traits
    comps = {
        "minecraft:geometry": "minecraft:geometry.full_block",
        "minecraft:material_instances": {
            face: {"texture": NS + "_" + tex, "render_method": method}
            for face, tex in instances.items()},
        "minecraft:destructible_by_mining": mine,
        "minecraft:map_color": map_colour,
    }
    comps.update(extra or {})
    data = {"description": desc, "components": comps}
    if permutations:
        data["permutations"] = permutations
    BLOCKS[name] = {"format_version": FV_BLOCK, "minecraft:block": data}
    SOUNDS[ww(name)] = {"sound": sound}
    LANG.append("tile.%s.name=%s" % (ww(name), display))
    return ww(name)


def log(name, display, map_colour):
    """A pillar: bark on the sides, rings on the ends, lies down when placed sideways."""
    face = "q.block_state('minecraft:block_face')"
    return block(
        name, display, "wood", "minecraft:itemGroup.name.log", mining(2.0, AXE),
        textures={"*": name, "up": name + "_top", "down": name + "_top"},
        map_colour=map_colour, category="nature",
        extra={"minecraft:flammable": {"catch_chance_modifier": 5, "destroy_chance_modifier": 5}},
        traits={"minecraft:placement_position": {"enabled_states": ["minecraft:block_face"]}},
        permutations=[
            {"condition": "%s == 'east' || %s == 'west'" % (face, face),
             "components": {"minecraft:transformation": {"rotation": [0, 0, 90]}}},
            {"condition": "%s == 'north' || %s == 'south'" % (face, face),
             "components": {"minecraft:transformation": {"rotation": [90, 0, 0]}}},
        ])


def leaf(name, display, map_colour):
    """Cutout foliage like the game's fancy leaves: you can see through the gaps."""
    return block(
        name, display, "grass", "minecraft:itemGroup.name.leaves", mining(0.2),
        method="alpha_test", map_colour=map_colour, category="nature",
        extra={
            "minecraft:light_dampening": 1,
            "minecraft:destructible_by_explosion": {"explosion_resistance": 0.2},
            "minecraft:flammable": {"catch_chance_modifier": 30, "destroy_chance_modifier": 60},
        })


def plant(name, display, grows_on, map_colour, light=0):
    """A flat cross of two planes, walk-through, that needs the right ground."""
    extra = {
        "minecraft:geometry": "minecraft:geometry.cross",
        "minecraft:collision_box": False,
        "minecraft:selection_box": {"origin": [-5, 0, -5], "size": [10, 13, 10]},
        "minecraft:light_dampening": 0,
        "minecraft:placement_filter": {
            "conditions": [{"allowed_faces": ["up"], "block_filter": grows_on}]},
        "minecraft:destructible_by_explosion": {"explosion_resistance": 0},
    }
    if light:
        extra["minecraft:light_emission"] = light
    return block(name, display, "grass", "minecraft:itemGroup.name.flower", mining(0),
                 method="alpha_test", map_colour=map_colour, extra=extra, category="nature")


def loot(path, item, low, high):
    LOOT[path] = {"pools": [{"rolls": 1, "entries": [{
        "type": "item", "name": item, "weight": 1,
        "functions": [{"function": "set_count", "count": {"min": low, "max": high}}],
    }]}]}
    return "loot_tables/" + path + ".json"


def build_blocks():
    b = {}
    b["redwood_log"] = log("redwood_log", "Redwood Log", "#7a3c2c")
    b["cypress_log"] = log("cypress_log", "Cypress Log", "#6c5e50")
    for wood, colour in (("redwood", "#b06448"), ("cypress", "#ceb880")):
        b[wood + "_planks"] = block(
            wood + "_planks", wood.title() + " Planks", "wood",
            "minecraft:itemGroup.name.planks", mining(2.0, AXE), map_colour=colour,
            extra={"minecraft:flammable": {"catch_chance_modifier": 5, "destroy_chance_modifier": 20}})
    b["redwood_needles"] = leaf("redwood_needles", "Redwood Needles", "#2e5c3c")
    b["cypress_leaves"] = leaf("cypress_leaves", "Cypress Leaves", "#346a50")

    b["volcanic_ash"] = block("volcanic_ash", "Volcanic Ash", "sand", None,
                              mining(0.5, SHOVEL), map_colour="#3e3c40", category="nature")
    b["pumice"] = block("pumice", "Pumice", "stone", None, mining(1.2, PICKAXE),
                        map_colour="#a8a49e", category="nature")
    b["pumice_bricks"] = block("pumice_bricks", "Pumice Bricks", "stone", None,
                               mining(1.5, PICKAXE), map_colour="#a8a49e")
    b["salt_block"] = block("salt_block", "Salt Block", "stone", None, mining(1.0, PICKAXE),
                            map_colour="#eef2f6", category="nature")
    b["salt_bricks"] = block("salt_bricks", "Salt Bricks", "stone", None, mining(1.5, PICKAXE),
                             map_colour="#e2e8ee")
    b["rose_salt_ore"] = block(
        "rose_salt_ore", "Rose Salt Deposit", "stone", "minecraft:itemGroup.name.ore",
        mining(1.5, PICKAXE), map_colour="#e8a8bc", category="nature",
        extra={"minecraft:loot": loot("blocks/rose_salt_ore", ww("rose_salt"), 2, 4)})
    for name, display, colour in (("emberite_ore", "Emberite Ore", "#8a6a5a"),
                                  ("deepslate_emberite_ore", "Deepslate Emberite Ore", "#5a4444")):
        b[name] = block(
            name, display, "stone", "minecraft:itemGroup.name.ore",
            mining(3.0 if name == "emberite_ore" else 4.5, PICKAXE),
            map_colour=colour, category="nature",
            extra={"minecraft:light_emission": 3,
                   "minecraft:destructible_by_explosion": {"explosion_resistance": 3},
                   "minecraft:loot": loot("blocks/" + name, ww("raw_emberite"), 1, 2)})
    b["emberite_block"] = block(
        "emberite_block", "Block of Emberite", "metal", None, mining(5.0, PICKAXE),
        map_colour="#3a3238",
        extra={"minecraft:light_emission": 4,
               "minecraft:destructible_by_explosion": {"explosion_resistance": 6}})
    b["ember_lamp"] = block("ember_lamp", "Ember Lamp", "glass", None, mining(0.5),
                            map_colour="#f08a2c", extra={"minecraft:light_emission": 15})

    b["lavender"] = plant("lavender", "Lavender", SOIL, "#9068c0")
    b["ember_shrub"] = plant(
        "ember_shrub", "Ember Shrub",
        [b["volcanic_ash"], b["pumice"], mc("smooth_basalt"), mc("blackstone"), mc("coarse_dirt")],
        "#c05a20", light=6)
    return b


# --------------------------------------------------------------------------
# Items
# --------------------------------------------------------------------------
def item(name, display, category, group, comps):
    desc = {"identifier": ww(name), "menu_category": {"category": category}}
    if group:
        desc["menu_category"]["group"] = group
    data = {"minecraft:icon": {"textures": {"default": NS + "_" + name}}}
    data.update(comps)
    ITEMS[name] = {"format_version": FV_ITEM,
                   "minecraft:item": {"description": desc, "components": data}}
    LANG.append("item.%s=%s" % (ww(name), display))
    return ww(name)


def repair(name):
    return {"repair_items": [
        {"items": [ww(name)], "repair_amount": "context.other->query.remaining_durability"},
        {"items": [ww("emberite_ingot")], "repair_amount": "query.max_durability * 0.25"},
    ]}


# Emberite sits between iron and diamond, but it is forged from a volcano:
# everything made of it survives fire and lava, and it enchants well.
TOOL_DURABILITY = 780
TOOL_SPEED = 7
ENCHANTABILITY = 18


def tool(name, display, group, slot, damage, tags, digs):
    comps = {
        "minecraft:max_stack_size": 1,
        "minecraft:hand_equipped": True,
        "minecraft:durability": {"max_durability": TOOL_DURABILITY},
        "minecraft:repairable": repair(name),
        "minecraft:damage": damage,
        "minecraft:enchantable": {"slot": slot, "value": ENCHANTABILITY},
        "minecraft:fire_resistant": {},
        "minecraft:tags": {"tags": tags},
        "minecraft:digger": {"use_efficiency": True, "destroy_speeds": digs},
    }
    return item(name, display, "equipment", group, comps)


def dig_tag(*tags, speed=TOOL_SPEED):
    """Fast on every block carrying one of these tags (new-style tag first, then the old ones)."""
    query = ", ".join("'%s'" % t for t in tags)
    return {"block": {"tags": "q.any_tag(%s)" % query}, "speed": speed}


def armour(name, display, group, slot, enchant_slot, protection, durability):
    return item(name, display, "equipment", group, {
        "minecraft:max_stack_size": 1,
        "minecraft:durability": {"max_durability": durability},
        "minecraft:repairable": repair(name),
        "minecraft:enchantable": {"slot": enchant_slot, "value": ENCHANTABILITY},
        "minecraft:fire_resistant": {},
        "minecraft:wearable": {"slot": slot, "protection": protection},
    })


def attachable(name, geometry, layer, hide):
    ATTACHABLES[name] = {
        "format_version": FV_ATTACHABLE,
        "minecraft:attachable": {"description": {
            "identifier": ww(name),
            "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {
                "default": "textures/models/armor/%s_emberite_%d" % (NS, layer),
                "enchanted": "textures/misc/enchanted_actor_glint",
            },
            "geometry": {"default": geometry},
            "scripts": {"parent_setup": "variable.%s_layer_visible = 0.0;" % hide},
            "render_controllers": ["controller.render.armor"],
        }},
    }


def build_items():
    item("raw_emberite", "Raw Emberite", "items", None, {})
    item("emberite_ingot", "Emberite Ingot", "items", None, {"minecraft:fire_resistant": {}})
    item("rose_salt", "Rose Salt", "items", None, {})
    item("cured_meat", "Cured Meat", "items", None, {
        # As filling as steak, keeps you full for longer, and eaten in half the time.
        "minecraft:food": {"nutrition": 8, "saturation_modifier": 1.0},
        "minecraft:use_animation": "eat",
        "minecraft:use_modifiers": {"use_duration": 0.8, "movement_modifier": 0.33},
        "minecraft:tags": {"tags": ["minecraft:is_food", "minecraft:is_meat"]},
    })

    tool("emberite_sword", "Emberite Falchion", "minecraft:itemGroup.name.sword", "sword", 7,
         ["minecraft:is_sword"],
         [{"block": mc("web"), "speed": 15}, {"block": mc("bamboo"), "speed": 10}])
    tool("emberite_pickaxe", "Emberite Pickaxe", "minecraft:itemGroup.name.pickaxe", "pickaxe", 4,
         ["minecraft:is_pickaxe", "minecraft:is_tool", "minecraft:digger", "minecraft:iron_tier"],
         [dig_tag("minecraft:is_pickaxe_item_destructible", "stone", "metal", "diamond_pick_diggable")])
    tool("emberite_axe", "Emberite Bearded Axe", "minecraft:itemGroup.name.axe", "axe", 6,
         ["minecraft:is_axe", "minecraft:is_tool", "minecraft:digger", "minecraft:iron_tier"],
         [dig_tag("minecraft:is_axe_item_destructible", "wood", "pumpkin", "plant")])
    tool("emberite_shovel", "Emberite Spade", "minecraft:itemGroup.name.shovel", "shovel", 3,
         ["minecraft:is_shovel", "minecraft:is_tool", "minecraft:digger", "minecraft:iron_tier"],
         [dig_tag("minecraft:is_shovel_item_destructible", "dirt", "sand", "gravel", "grass", "snow")])
    tool("emberite_hoe", "Emberite Sickle", "minecraft:itemGroup.name.hoe", "hoe", 3,
         ["minecraft:is_hoe", "minecraft:is_tool", "minecraft:digger", "minecraft:iron_tier"],
         [dig_tag("minecraft:is_hoe_item_destructible")])

    armour("emberite_helmet", "Emberite Helm", "minecraft:itemGroup.name.helmet",
           "slot.armor.head", "armor_head", 3, 280)
    armour("emberite_chestplate", "Emberite Cuirass", "minecraft:itemGroup.name.chestplate",
           "slot.armor.chest", "armor_torso", 7, 400)
    armour("emberite_leggings", "Emberite Greaves", "minecraft:itemGroup.name.leggings",
           "slot.armor.legs", "armor_legs", 5, 380)
    armour("emberite_boots", "Emberite Boots", "minecraft:itemGroup.name.boots",
           "slot.armor.feet", "armor_feet", 2, 330)
    attachable("emberite_helmet", "geometry.player.armor.helmet", 1, "helmet")
    attachable("emberite_chestplate", "geometry.player.armor.chestplate", 1, "chest")
    attachable("emberite_leggings", "geometry.humanoid.armor.leggings", 2, "leg")
    attachable("emberite_boots", "geometry.player.armor.boots", 1, "boot")


# --------------------------------------------------------------------------
# Recipes
# --------------------------------------------------------------------------
def shaped(name, pattern, key, result, count=1):
    keys = {k: {"item": v} for k, v in key.items()}
    out = {"item": result}
    if count > 1:
        out["count"] = count
    RECIPES[name] = {"format_version": FV_RECIPE, "minecraft:recipe_shaped": {
        "description": {"identifier": ww(name)},
        "tags": ["crafting_table"],
        "pattern": pattern,
        "key": keys,
        "unlock": [{"item": v} for v in dict.fromkeys(key.values())],
        "result": out,
    }}


def shapeless(name, ingredients, result, count=1):
    out = {"item": result}
    if count > 1:
        out["count"] = count
    RECIPES[name] = {"format_version": FV_RECIPE, "minecraft:recipe_shapeless": {
        "description": {"identifier": ww(name)},
        "tags": ["crafting_table"],
        "ingredients": [{"item": i} for i in ingredients],
        "unlock": [{"item": ingredients[0]}],
        "result": out,
    }}


def smelt(name, source, result):
    RECIPES[name] = {"format_version": FV_RECIPE, "minecraft:recipe_furnace": {
        "description": {"identifier": ww(name)},
        "unlock": [{"item": source}],
        "tags": ["furnace", "blast_furnace"],
        "input": source,
        "output": result,
    }}


def build_recipes():
    ingot, stick = ww("emberite_ingot"), mc("stick")
    for wood in ("redwood", "cypress"):
        planks = ww(wood + "_planks")
        shaped(wood + "_planks", ["#"], {"#": ww(wood + "_log")}, planks, 4)
        shaped("stick_from_" + wood, ["#", "#"], {"#": planks}, stick, 4)
        shaped("crafting_table_from_" + wood, ["##", "##"], {"#": planks}, mc("crafting_table"))
        shaped("chest_from_" + wood, ["###", "# #", "###"], {"#": planks}, mc("chest"))
    shaped("pumice_bricks", ["##", "##"], {"#": ww("pumice")}, ww("pumice_bricks"), 4)
    shaped("salt_bricks", ["##", "##"], {"#": ww("salt_block")}, ww("salt_bricks"), 4)
    shaped("salt_block_from_rose_salt", ["##", "##"], {"#": ww("rose_salt")}, ww("salt_block"))
    shaped("emberite_block", ["###", "###", "###"], {"#": ingot}, ww("emberite_block"))
    shaped("emberite_ingot_from_block", ["#"], {"#": ww("emberite_block")}, ingot, 9)
    shaped("ember_lamp", [" G ", "GEG", " G "], {"G": mc("glass"), "E": ingot}, ww("ember_lamp"), 2)
    smelt("emberite_ingot_from_raw", ww("raw_emberite"), ingot)
    smelt("emberite_ingot_from_ore", ww("emberite_ore"), ingot)
    smelt("emberite_ingot_from_deepslate_ore", ww("deepslate_emberite_ore"), ingot)

    key = {"X": ingot, "#": stick}
    shaped("emberite_sword", ["X", "X", "#"], key, ww("emberite_sword"))
    shaped("emberite_pickaxe", ["XXX", " # ", " # "], key, ww("emberite_pickaxe"))
    shaped("emberite_axe", ["XX", "X#", " #"], key, ww("emberite_axe"))
    shaped("emberite_shovel", ["X", "#", "#"], key, ww("emberite_shovel"))
    shaped("emberite_hoe", ["XX", " #", " #"], key, ww("emberite_hoe"))
    plate = {"X": ingot}
    shaped("emberite_helmet", ["XXX", "X X"], plate, ww("emberite_helmet"))
    shaped("emberite_chestplate", ["X X", "XXX", "XXX"], plate, ww("emberite_chestplate"))
    shaped("emberite_leggings", ["XXX", "X X", "X X"], plate, ww("emberite_leggings"))
    shaped("emberite_boots", ["X X", "X X"], plate, ww("emberite_boots"))

    for meat in ("cooked_beef", "cooked_porkchop", "cooked_mutton"):
        shapeless("cured_meat_from_" + meat, [mc(meat), ww("rose_salt")], ww("cured_meat"))
    shapeless("purple_dye_from_lavender", [ww("lavender")], mc("purple_dye"))


# --------------------------------------------------------------------------
# World generation
# --------------------------------------------------------------------------
OAK_LOG, SPRUCE_LOG, BIRCH_LOG = mc("oak_log"), mc("spruce_log"), mc("birch_log")
OAK_LEAVES, SPRUCE_LEAVES = mc("oak_leaves"), mc("spruce_leaves")


def fog(name, air, water, start, end):
    FOGS[name] = {"format_version": "1.16.100", "minecraft:fog_settings": {
        "description": {"identifier": ww(name)},
        "distance": {
            "air": {"fog_start": start, "fog_end": end, "fog_color": air,
                    "render_distance_type": "render"},
            "water": {"fog_start": 0, "fog_end": 40.0, "fog_color": water,
                      "render_distance_type": "fixed"},
        },
    }}
    return ww(name)


def build_shared_features(b):
    f = {}
    # Vanilla-wood trees for the pass over the game's own biomes
    f["young_spruce"] = spruce_tree("young_spruce", SPRUCE_LOG, SPRUCE_LEAVES,
                                    (6, 9), (1, 2), (0, 2), (2, 3))
    f["tall_spruce"] = spruce_tree("tall_spruce", SPRUCE_LOG, SPRUCE_LEAVES,
                                   (11, 17), (2, 4), (0, 2), (3, 4))
    f["slim_pine"] = pine_tree("slim_pine", SPRUCE_LOG, SPRUCE_LEAVES, (9, 14), (3, 5), 1)
    f["green_oak"] = round_tree("green_oak", OAK_LOG, OAK_LEAVES, (4, 7))
    f["big_oak"] = fancy_tree("big_oak", OAK_LOG, OAK_LEAVES, 5, 12)
    f["bush_oak"] = bush("bush_oak", OAK_LOG, OAK_LEAVES)
    f["fallen_oak"] = fallen_log("fallen_oak", OAK_LOG, (3, 6))
    f["fallen_spruce"] = fallen_log("fallen_spruce", SPRUCE_LOG, (4, 7))
    f["fallen_birch"] = fallen_log("fallen_birch", BIRCH_LOG, (3, 6))
    f["driftwood"] = fallen_log("driftwood", mc("stripped_spruce_log"), (2, 4),
                                grow_on=SOIL + [mc("sand"), mc("gravel")])
    # Rocks
    f["boulder_stone"] = boulder("boulder_stone", mc("stone"), 16, mc("andesite"), 7)
    f["boulder_mossy"] = boulder("boulder_mossy", mc("cobblestone"), 14,
                                 mc("mossy_cobblestone"), 9)
    f["boulder_lime"] = boulder("boulder_lime", mc("calcite"), 16, mc("diorite"), 7)
    f["boulder_granite"] = boulder("boulder_granite", mc("granite"), 14, mc("stone"), 5)
    f["boulder_sandstone"] = boulder("boulder_sandstone", mc("sandstone"), 16)
    f["boulder_pumice"] = boulder("boulder_pumice", b["pumice"], 18, mc("smooth_basalt"), 8)
    f["boulder_basalt"] = boulder("boulder_basalt", mc("smooth_basalt"), 34, mc("blackstone"), 12)
    f["boulder_salt"] = boulder("boulder_salt", b["salt_block"], 16, b["rose_salt_ore"], 5)
    f["rock_small"] = boulder("rock_small", mc("stone"), 4)
    f["rock_small_mossy"] = boulder("rock_small_mossy", mc("mossy_cobblestone"), 4)
    f["rock_small_pumice"] = boulder("rock_small_pumice", b["pumice"], 5)
    f["rock_small_salt"] = boulder("rock_small_salt", b["salt_block"], 5)
    # Ground patches
    for name, material, count, repl in (
            ("coarse", mc("coarse_dirt"), 30, PATCH_REPLACE),
            ("podzol", mc("podzol"), 33, PATCH_REPLACE),
            ("gravel", mc("gravel"), 24, PATCH_REPLACE),
            ("moss", mc("moss_block"), 20, PATCH_REPLACE),
            ("mud", mc("mud"), 30, PATCH_REPLACE),
            ("rooted", mc("dirt_with_roots"), 14, PATCH_REPLACE),
            ("stone", mc("stone"), 30, PATCH_REPLACE),
            ("andesite", mc("andesite"), 22, PATCH_REPLACE),
            ("bank_gravel", mc("gravel"), 26, BANK_REPLACE),
            ("bank_clay", mc("clay"), 16, BANK_REPLACE),
            ("bank_sand", mc("sand"), 22, BANK_REPLACE)):
        f["patch_" + name] = blob("patch_" + name, material, count, repl)
    # Small plants
    f["grass"] = single("grass", [(mc("short_grass"), 6), (mc("fern"), 1)])
    f["ferns"] = single("ferns", [(mc("fern"), 4), (mc("short_grass"), 2), (mc("bush"), 1)])
    f["shrub"] = single("shrub", mc("bush"))
    f["dry_grass"] = single("dry_grass", [(mc("short_dry_grass"), 3), (mc("tall_dry_grass"), 2)])
    f["reeds"] = single("reeds", mc("reeds"))
    f["mushrooms"] = single("mushrooms", [(mc("brown_mushroom"), 3), (mc("red_mushroom"), 1)])
    f["leaf_litter"] = single("leaf_litter", [
        (state("leaf_litter", growth=g), w) for g, w in ((0, 2), (1, 3), (2, 3), (3, 2))])
    f["wildflowers"] = single("wildflowers", [
        (state("wildflowers", growth=g), 1) for g in (0, 1, 2, 3)])
    f["meadow_flowers"] = single("meadow_flowers", [
        (mc("oxeye_daisy"), 4), (mc("azure_bluet"), 3), (mc("cornflower"), 2), (mc("poppy"), 2)])
    f["grass_tuft"] = cluster("grass_tuft", f["grass"], 14, 5)
    f["fern_bed"] = cluster("fern_bed", f["ferns"], 12, 4)
    f["wildflower_patch"] = cluster("wildflower_patch", f["wildflowers"], 8, 3)
    f["meadow_flower_patch"] = cluster("meadow_flower_patch", f["meadow_flowers"], 7, 4)
    f["leaf_litter_patch"] = cluster("leaf_litter_patch", f["leaf_litter"], 10, 4)
    f["reed_bed"] = cluster("reed_bed", f["reeds"], 14, 4)
    f["dry_grass_tuft"] = cluster("dry_grass_tuft", f["dry_grass"], 8, 5)
    return f


def build_biomes(b, f):
    # ---- Redwood Valley: towering red-barked trees over a fern floor ----
    log, needles = b["redwood_log"], b["redwood_needles"]
    f["giant_redwood"] = mega_tree("giant_redwood", log, needles, 17, [4, 10], (10, 15))
    f["redwood"] = spruce_tree("redwood", log, needles, (14, 20), (6, 9), (0, 2), (2, 3))
    f["young_redwood"] = spruce_tree("young_redwood", log, needles, (7, 10), (2, 3), (0, 1), (2, 2))
    biome("redwood_valley",
          climate={"downfall": 0.9, "snow_accumulation": [0.0, 0.125], "temperature": 0.45},
          surface=overworld_surface(),
          targets=["taiga", "mega_taiga", "redwood_taiga_mutated", "extreme_hills_plus_trees",
                   "birch_forest"],
          amount=0.6, scale=22,
          tags=["animal", "spawns_cold_variant_farm_animals"],
          adjustments=[
              adjust([0.1, 1.0], 0.0625, top_material=mc("podzol")),
              adjust([-1.0, -0.55], 0.0625, top_material=mc("coarse_dirt")),
          ])
    client_biome("redwood_valley", "minecraft:fog_taiga", "#2f6f86", "#5f9a55", "#4d8a4a",
                 grading="minecraft:coolish_color_grading")
    t = ["redwood_valley"]
    mix("redwood_tree_mix", [(f["redwood"], 6), (f["young_redwood"], 3), (f["giant_redwood"], 2)])
    mix("redwood_rock_mix", [(f["boulder_mossy"], 3), (f["rock_small_mossy"], 4)])
    rule("redwood_valley_giants", f["giant_redwood"], t, 1)
    rule("redwood_valley_trees", ww("redwood_tree_mix"), t, 4)
    rule("redwood_valley_groves", ww("redwood_tree_mix"), t,
         "math.clamp(math.floor(query.noise(variable.originx * 0.01, variable.originz * 0.01) * 8), 0, 3)")
    rock_rule("redwood_valley_rocks", ww("redwood_rock_mix"), t, 1, 35)
    ground_rule("redwood_valley_moss", f["patch_moss"], t, 1, 50)
    rule("redwood_valley_ferns", f["fern_bed"], t, 3)
    rule("redwood_valley_grass", f["grass_tuft"], t, 1)
    rule("redwood_valley_mushrooms", f["mushrooms"], t, 2, 45)
    rule("redwood_valley_litter", f["leaf_litter_patch"], t, 1, 50)

    # ---- Lavender Hills: purple fields and dark cypress columns ---------
    f["cypress"] = spruce_tree("cypress", b["cypress_log"], b["cypress_leaves"],
                               (8, 13), (1, 2), (0, 1), (1, 2))
    f["young_cypress"] = spruce_tree("young_cypress", b["cypress_log"], b["cypress_leaves"],
                                     (5, 7), (1, 1), (0, 1), (1, 1))
    f["lavender"] = single("lavender", b["lavender"])
    f["lavender_field"] = cluster("lavender_field", f["lavender"], 30, 6)
    biome("lavender_hills",
          climate={"downfall": 0.4, "snow_accumulation": [0.0, 0.125], "temperature": 0.8},
          surface=overworld_surface(),
          targets=["plains", "sunflower_plains", "meadow"],
          amount=0.55, scale=22,
          tags=["animal", "bee_habitat", "spawns_warm_variant_farm_animals"],
          adjustments=[adjust([0.55, 1.0], 0.08, top_material=mc("coarse_dirt"))])
    client_biome("lavender_hills", "minecraft:fog_plains", "#4aa3e0", "#9cbf5a", "#86b04a")
    t = ["lavender_hills"]
    mix("cypress_mix", [(f["cypress"], 3), (f["young_cypress"], 1)])
    rule("lavender_hills_fields", f["lavender_field"], t, 3)
    rule("lavender_hills_swathes", f["lavender_field"], t,
         "math.clamp(math.floor(query.noise(variable.originx * 0.02, variable.originz * 0.02) * 12), 0, 5)")
    rule("lavender_hills_cypress", ww("cypress_mix"), t, 1, 55)
    rule("lavender_hills_cypress_rows", ww("cypress_mix"), t,
         "math.clamp(math.floor(query.noise(variable.originz * 0.015, variable.originx * 0.015) * 10) - 1, 0, 3)")
    rock_rule("lavender_hills_rocks", f["boulder_lime"], t, 1, 18)
    rule("lavender_hills_grass", f["grass_tuft"], t, 3)
    rule("lavender_hills_flowers", f["meadow_flower_patch"], t, 1, 40)

    # ---- Ashlands: a dead volcanic plain that still glows ---------------
    ash, pumice = b["volcanic_ash"], b["pumice"]
    ground = [ash, pumice, mc("smooth_basalt"), mc("blackstone"), mc("stone")]
    f["ember_outcrop"] = blob("ember_outcrop", b["emberite_ore"], 7, ground)
    f["magma_vent"] = blob("magma_vent", mc("magma"), 7, [ash, pumice])
    f["ember_shrub"] = single("ember_shrub", b["ember_shrub"])
    f["ember_shrub_patch"] = cluster("ember_shrub_patch", f["ember_shrub"], 9, 5)
    surface = overworld_surface(top=ash, mid=ash, floor=mc("gravel"))
    surface["foundation_material"] = mc("stone")
    biome("ashlands",
          climate={"downfall": 0.0, "temperature": 1.5},
          surface=surface,
          targets=["savanna", "savanna_plateau", "extreme_hills", "stony_peaks"],
          amount=0.55, scale=22,
          tags=["spawns_warm_variant_farm_animals"],
          steep=mc("smooth_basalt"),
          adjustments=[
              adjust([0.35, 1.0], 0.06, top_material=mc("smooth_basalt"), mid_material=mc("smooth_basalt")),
              adjust([-1.0, -0.5], 0.07, top_material=pumice, mid_material=pumice),
              adjust([-0.06, 0.03], 0.05, top_material=mc("blackstone")),
          ])
    client_biome("ashlands", fog("fog_ashlands", "#8c8480", "#3c444c", 0.2, 0.9),
                 "#4a5560", "#7a7a5a", "#6e6a50")
    t = ["ashlands"]
    mix("ash_rock_mix", [(f["boulder_pumice"], 4), (f["rock_small_pumice"], 5), (f["boulder_basalt"], 2)])
    rock_rule("ashlands_rocks", ww("ash_rock_mix"), t, 1, 70)
    ground_rule("ashlands_ember", f["ember_outcrop"], t, 1, 45)
    ground_rule("ashlands_vents", f["magma_vent"], t, 1, 10)
    rule("ashlands_shrubs", f["ember_shrub_patch"], t, 2)

    # ---- Salt Flats: a blinding white pan with rose crystal outcrops ----
    salt_block = b["salt_block"]
    f["rose_salt_seam"] = blob("rose_salt_seam", b["rose_salt_ore"], 9, [salt_block])
    surface = overworld_surface(top=salt_block, mid=salt_block, floor=mc("sand"))
    biome("salt_flats",
          climate={"downfall": 0.0, "temperature": 2.0},
          surface=surface,
          targets=["desert"],
          amount=0.5, scale=22,
          tags=["spawns_warm_variant_farm_animals"],
          adjustments=[
              adjust([0.5, 1.0], 0.05, top_material=mc("sand"), mid_material=mc("sandstone")),
              adjust([-1.0, -0.72], 0.09, top_material=mc("white_terracotta")),
          ])
    client_biome("salt_flats", fog("fog_salt_flats", "#e6eef4", "#7cc4cc", 0.45, 1.0),
                 "#8fd0d8", "#b8c080", "#a8b070", water_opacity=0.5)
    t = ["salt_flats"]
    mix("salt_rock_mix", [(f["boulder_salt"], 2), (f["rock_small_salt"], 5)])
    ground_rule("salt_flats_seams", f["rose_salt_seam"], t, 1, 50)
    rock_rule("salt_flats_rocks", ww("salt_rock_mix"), t, 1, 30)

    # ---- Emberite ore, everywhere underground ---------------------------
    add_feature("ore_feature", "emberite_ore_vein", {
        "count": 5,
        "replace_rules": [
            {"places_block": b["emberite_ore"], "may_replace": [mc("stone")]},
            {"places_block": b["deepslate_emberite_ore"], "may_replace": [mc("deepslate")]},
        ],
    })
    RULES["underground_emberite_ore"] = {
        "format_version": FV_RULE,
        "minecraft:feature_rules": {
            "description": {"identifier": ww("underground_emberite_ore"),
                            "places_feature": ww("emberite_ore_vein")},
            "conditions": {"placement_pass": "underground_pass",
                           "minecraft:biome_filter": [has("overworld")]},
            "distribution": {
                "iterations": 4,
                "coordinate_eval_order": "zyx",
                "x": {"distribution": "uniform", "extent": [0, 16]},
                "y": {"distribution": "uniform", "extent": [-56, 20]},
                "z": {"distribution": "uniform", "extent": [0, 16]},
            },
        },
    }


def build_vanilla_rules(f):
    """A light pass that makes the game's own biomes less bare."""
    woods, not_woods = ["forest"], ("taiga", "jungle", "ww_biome", "pale_garden", "mangrove_swamp")
    mix("woods_deadwood_mix", [(f["fallen_oak"], 3), (f["fallen_birch"], 2)])
    mix("woods_patch_mix", [(f["patch_coarse"], 3), (f["patch_podzol"], 2), (f["patch_moss"], 2),
                            (f["patch_rooted"], 1)])
    mix("woods_rock_mix", [(f["boulder_mossy"], 2), (f["rock_small_mossy"], 3), (f["rock_small"], 2)])
    rule("woods_deadwood", ww("woods_deadwood_mix"), woods, 1, 25, not_woods)
    rule("woods_bushes", f["bush_oak"], woods, 1, 50, not_woods)
    rule("woods_big_oaks", f["big_oak"], woods, 1, 12, not_woods)
    rock_rule("woods_rocks", ww("woods_rock_mix"), woods, 1, 15, not_woods)
    ground_rule("woods_patches", ww("woods_patch_mix"), woods, 1, 60, not_woods)
    rule("woods_litter", f["leaf_litter_patch"], woods, 1, 50, not_woods)

    taiga, not_taiga = ["taiga"], ("ww_biome",)
    mix("taiga_tree_mix", [(f["tall_spruce"], 6), (f["slim_pine"], 3)])
    mix("taiga_patch_mix", [(f["patch_podzol"], 3), (f["patch_coarse"], 2), (f["patch_gravel"], 1)])
    mix("taiga_rock_mix", [(f["boulder_mossy"], 3), (f["rock_small_mossy"], 3), (f["boulder_stone"], 1)])
    rule("taiga_tall_trees", ww("taiga_tree_mix"), taiga, 1, None, not_taiga)
    rule("taiga_deadwood", f["fallen_spruce"], taiga, 1, 30, not_taiga)
    rock_rule("taiga_rocks", ww("taiga_rock_mix"), taiga, 1, 22, not_taiga)
    ground_rule("taiga_patches", ww("taiga_patch_mix"), taiga, 1, 65, not_taiga)
    rule("taiga_ferns", f["fern_bed"], taiga, 1, 45, not_taiga)

    plains = ["plains"]
    mix("plains_patch_mix", [(f["patch_coarse"], 3), (f["patch_gravel"], 1)])
    mix("plains_rock_mix", [(f["rock_small"], 4), (f["boulder_stone"], 1)])
    rule("plains_bushes", f["bush_oak"], plains, 1, 25)
    rule("plains_shrubs", f["shrub"], plains, 2, 60)
    rule("plains_lone_trees", f["green_oak"], plains, 1, 6)
    rock_rule("plains_rocks", ww("plains_rock_mix"), plains, 1, 10)
    ground_rule("plains_patches", ww("plains_patch_mix"), plains, 1, 35)
    rule("plains_wildflowers", f["wildflower_patch"], plains, 1, 12)

    hills, not_hills = ["extreme_hills", "mountains"], ("ww_biome", "frozen", "forest")
    mix("hills_patch_mix", [(f["patch_stone"], 4), (f["patch_andesite"], 2), (f["patch_gravel"], 3)])
    mix("hills_rock_mix", [(f["boulder_stone"], 4), (f["rock_small"], 4)])
    mix("hills_tree_mix", [(f["slim_pine"], 3), (f["young_spruce"], 2)])
    ground_rule("hills_patches", ww("hills_patch_mix"), hills, 2, None, not_hills)
    rock_rule("hills_rocks", ww("hills_rock_mix"), hills, 1, 45, not_hills)
    rule("hills_pines", ww("hills_tree_mix"), ["extreme_hills"], 1, 30, ("ww_biome",))

    savanna = ["savanna"]
    mix("savanna_rock_mix", [(f["boulder_granite"], 2), (f["rock_small"], 3)])
    rule("savanna_dry_grass", f["dry_grass_tuft"], savanna, 1, 70)
    ground_rule("savanna_patches", f["patch_coarse"], savanna, 1, 70)
    rock_rule("savanna_rocks", ww("savanna_rock_mix"), savanna, 1, 15)

    desert = ["desert"]
    rule("desert_dry_grass", f["dry_grass_tuft"], desert, 1, 25)
    rock_rule("desert_rocks", f["boulder_sandstone"], desert, 1, 10)

    river, not_river = ["river"], ("frozen",)
    mix("river_bank_mix", [(f["patch_bank_gravel"], 4), (f["patch_bank_clay"], 1), (f["patch_bank_sand"], 2)])
    ground_rule("river_banks", ww("river_bank_mix"), river, 2, None, not_river)
    rule("river_reeds", f["reed_bed"], river, 1, 60, not_river)
    rock_rule("river_rocks", f["rock_small"], river, 1, 20, not_river)

    beach, not_beach = ["beach"], ("frozen", "cold")
    ground_rule("beach_gravel", f["patch_bank_gravel"], beach, 1, 35, not_beach)
    rock_rule("beach_rocks", f["rock_small"], beach, 1, 10, not_beach)
    rule("beach_driftwood", f["driftwood"], beach, 1, 10, not_beach)

    swamp, not_swamp = ["swamp"], ("ww_biome", "mangrove_swamp")
    ground_rule("swamp_mud", f["patch_mud"], swamp, 1, 70, not_swamp)
    rule("swamp_reeds", f["reed_bed"], swamp, 1, 60, not_swamp)
    rule("swamp_deadwood", f["fallen_oak"], swamp, 1, 20, not_swamp)

    jungle = ["jungle"]
    ground_rule("jungle_moss", f["patch_moss"], jungle, 1, 70)
    rock_rule("jungle_rocks", f["boulder_mossy"], jungle, 1, 15)


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------
def main():
    samples = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    for folder in (BP, RP):
        if folder.exists():
            shutil.rmtree(folder)

    blocks = build_blocks()
    # Rocks and trees must be allowed to sit in our own ground and foliage.
    BOULDER_REPLACE.extend([blocks["volcanic_ash"], blocks["pumice"], blocks["salt_block"],
                            mc("smooth_basalt"), blocks["lavender"], blocks["ember_shrub"]])
    TREE_REPLACE.extend([blocks["lavender"]])
    TREE_THROUGH.extend([blocks["lavender"]])
    build_items()
    build_recipes()
    features = build_shared_features(blocks)
    build_biomes(blocks, features)
    build_vanilla_rules(features)

    # Behavior pack
    write_json(BP / "manifest.json", manifest(
        "Wilder Worlds",
        "4 new biomes, 2 wood sets, emberite tools and armour, rose salt. Use on a NEW world.",
        BP_UUID, BP_MODULE_UUID, "data", RP_UUID))
    for folder, table, suffix in (
            ("biomes", BIOMES, ".biome.json"), ("features", FEATURES, ".json"),
            ("feature_rules", RULES, ".json"), ("blocks", BLOCKS, ".json"),
            ("items", ITEMS, ".json"), ("recipes", RECIPES, ".json"),
            ("loot_tables", LOOT, ".json")):
        for name, data in table.items():
            write_json(BP / folder / (name + suffix), data)

    # Resource pack
    write_json(RP / "manifest.json", manifest(
        "Wilder Worlds Resources", "Textures and biome colours for Wilder Worlds.",
        RP_UUID, RP_MODULE_UUID, "resources"))
    for name, data in CLIENT_BIOMES.items():
        write_json(RP / "biomes" / (name + ".client_biome.json"), data)
    for name, data in FOGS.items():
        write_json(RP / "fogs" / (name + ".json"), data)
    for name, data in ATTACHABLES.items():
        write_json(RP / "attachables" / (name + ".json"), data)

    def save(folder, textures):
        out = RP / "textures" / folder
        out.mkdir(parents=True, exist_ok=True)
        atlas = {}
        for name, image in textures.items():
            key = NS + "_" + name
            image.save(out / (key + ".png"))
            atlas[key] = {"textures": "textures/%s/%s" % (folder, key)}
        return atlas

    write_json(RP / "textures" / "terrain_texture.json", {
        "resource_pack_name": "wilder_worlds", "texture_name": "atlas.terrain",
        "padding": 8, "num_mip_levels": 4, "texture_data": save("blocks", art.block_textures())})
    write_json(RP / "textures" / "item_texture.json", {
        "resource_pack_name": "wilder_worlds", "texture_name": "atlas.items",
        "texture_data": save("items", art.item_textures())})
    armour_dir = RP / "textures" / "models" / "armor"
    armour_dir.mkdir(parents=True, exist_ok=True)
    art.armour_layer_1().save(armour_dir / (NS + "_emberite_1.png"))
    art.armour_layer_2().save(armour_dir / (NS + "_emberite_2.png"))

    sounds = {"format_version": "1.19.30"}
    sounds.update(SOUNDS)
    write_json(RP / "blocks.json", sounds)
    (RP / "texts").mkdir(parents=True, exist_ok=True)
    (RP / "texts" / "en_US.lang").write_text("\n".join(LANG) + "\n")
    write_json(RP / "texts" / "languages.json", ["en_US"])
    art.pack_icon().save(BP / "pack_icon.png")
    shutil.copy(BP / "pack_icon.png", RP / "pack_icon.png")
    build_shader(samples)

    # .mcaddon = zip holding the packs
    RELEASE.mkdir(exist_ok=True)
    out = RELEASE / "WilderWorlds.mcaddon"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for folder, prefix in ((BP, "WilderWorlds_BP"), (RP, "WilderWorlds_RP"),
                               (SHADER, "WilderWorlds_Shader")):
            for path in sorted(folder.rglob("*")):
                if path.is_file():
                    info = zipfile.ZipInfo(prefix + "/" + path.relative_to(folder).as_posix(),
                                           date_time=(2026, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    z.writestr(info, path.read_bytes())
    print("biomes %d, features %d, rules %d, blocks %d, items %d, recipes %d -> %s (%d KB)" % (
        len(BIOMES), len(FEATURES), len(RULES), len(BLOCKS), len(ITEMS), len(RECIPES),
        out.name, out.stat().st_size // 1024))


if __name__ == "__main__":
    main()
