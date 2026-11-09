#!/usr/bin/env python3
"""Generates the Wilder Worlds add-on (behavior pack, resource pack, .mcaddon).

Run from anywhere:  python3 wilder-worlds/tools/build.py [bedrock-samples]

The bedrock-samples path (https://github.com/Mojang/bedrock-samples) is only
needed to regenerate the lite shader's fog files from the vanilla ones.

Everything under behavior_pack/ and resource_pack/ is produced by this script,
so edit the tables here rather than the generated JSON.
"""
import json
import random
import shutil
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
BP = ROOT / "behavior_pack"
RP = ROOT / "resource_pack"
SHADER = ROOT / "shader_pack"
RELEASE = ROOT / "release"

NS = "ww"
VERSION = [1, 1, 0]
MIN_ENGINE = [1, 26, 0]
FV_BIOME = "1.26.0"
FV_CLIENT_BIOME = "1.21.120"
FV_FEATURE = "1.26.0"
FV_RULE = "1.13.0"
FV_BLOCK = "1.21.120"

BP_UUID = "6d1f4b1e-58a3-4c0e-9a57-2f0c1b7e4a11"
BP_MODULE_UUID = "b0a7c2d4-93f1-4e86-8c35-7d5e9a1f6b22"
RP_UUID = "3e9c8a70-14b6-4d2f-a6e1-90c4d7b2f533"
RP_MODULE_UUID = "f47b0e15-6c29-4a83-b1d8-25a6e3c9d044"
SHADER_UUID = "9a2d6c41-7be5-4f18-93a0-c85d1e2b7f55"
SHADER_MODULE_UUID = "c1e84f37-2a90-4d6b-8e53-fa07b6d9c366"

HEIGHTMAP = "query.heightmap(variable.worldx, variable.worldz)"


def mc(name):
    return "minecraft:" + name


def ww(name):
    return NS + ":" + name


def rng(lo, hi):
    return {"range_min": lo, "range_max": hi}


# --------------------------------------------------------------------------
# Block lists
# --------------------------------------------------------------------------
SOIL = [mc(b) for b in (
    "grass_block", "dirt", "coarse_dirt", "podzol", "moss_block", "mud",
    "dirt_with_roots", "mycelium")]

GROUND_PLANTS = [mc(b) for b in (
    "short_grass", "fern", "tall_grass", "large_fern", "bush", "firefly_bush",
    "leaf_litter", "wildflowers", "moss_carpet", "short_dry_grass",
    "tall_dry_grass", "snow_layer", "dandelion", "poppy", "cornflower",
    "oxeye_daisy", "azure_bluet", "allium", "blue_orchid",
    "lily_of_the_valley", "brown_mushroom", "red_mushroom")]

AUTUMN_LEAVES = ["orange_leaves", "yellow_leaves", "red_leaves"]

LEAVES = [mc(b) for b in (
    "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
    "acacia_leaves", "dark_oak_leaves", "azalea_leaves",
    "azalea_leaves_flowered", "mangrove_leaves", "cherry_leaves",
    "pale_oak_leaves")] + [ww(b) for b in AUTUMN_LEAVES]

AIRISH = [mc("air")] + GROUND_PLANTS

TREE_REPLACE = AIRISH + LEAVES
TREE_THROUGH = SOIL + GROUND_PLANTS

PATCH_REPLACE = [mc(b) for b in ("grass_block", "dirt", "podzol", "coarse_dirt")]
BANK_REPLACE = PATCH_REPLACE + [mc("sand"), mc("gravel"), mc("clay")]
BOULDER_REPLACE = AIRISH + SOIL + [mc(b) for b in (
    "sand", "red_sand", "gravel", "stone", "snow")]

FEATURES = {}
RULES = {}
BIOMES = {}
CLIENT_BIOMES = {}


# --------------------------------------------------------------------------
# Feature builders
# --------------------------------------------------------------------------
def add_feature(kind, name, body):
    assert name not in FEATURES, name
    data = {"description": {"identifier": ww(name)}}
    data.update(body)
    FEATURES[name] = {"format_version": FV_FEATURE, mc(kind): data}
    return ww(name)


def tree_base(grow_on=None):
    return {
        "base_block": [mc("dirt")],
        "may_grow_on": grow_on or SOIL,
        "may_replace": TREE_REPLACE,
        "may_grow_through": TREE_THROUGH,
    }


def spruce_tree(name, log, leaves, height, lower, upper, radius):
    body = {
        "trunk": {"trunk_height": rng(*height), "trunk_block": log},
        "spruce_canopy": {
            "lower_offset": rng(*lower),
            "upper_offset": rng(*upper),
            "max_radius": rng(*radius),
            "leaf_block": leaves,
        },
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def pine_tree(name, log, leaves, height, canopy_height, base_radius):
    body = {
        "trunk": {"trunk_height": rng(*height), "trunk_block": log},
        "pine_canopy": {
            "canopy_height": rng(*canopy_height),
            "base_radius": base_radius,
            "leaf_block": leaves,
        },
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def round_tree(name, log, leaves, height, offset_min, grow_on=None):
    body = {
        "trunk": {"trunk_height": rng(*height), "trunk_block": log},
        "canopy": {
            "canopy_offset": {"min": offset_min, "max": 0},
            "min_width": 1,
            "canopy_slope": {"rise": 1, "run": 2},
            "variation_chance": {"numerator": 1, "denominator": 2},
            "leaf_block": leaves,
        },
    }
    body.update(tree_base(grow_on))
    return add_feature("tree_feature", name, body)


def fancy_tree(name, log, leaves, base, variance):
    body = {
        "fancy_trunk": {
            "trunk_height": {"base": base, "variance": variance, "scale": 0.618},
            "trunk_width": 1,
            "branches": {"slope": 0.381, "density": 1.0, "min_altitude_factor": 0.2},
            "trunk_block": log,
            "width_scale": 1.0,
            "foliage_altitude_factor": 0.3,
        },
        "fancy_canopy": {"height": 4, "radius": 3, "leaf_block": leaves},
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def fallen_log(name, log, length, grow_on=None):
    body = {"fallen_trunk": {"log_length": rng(*length), "trunk_block": log}}
    body.update(tree_base(grow_on))
    return add_feature("tree_feature", name, body)


def snag(name, log, height):
    return add_feature("multipart_block_column_feature", name, {
        "tip_block": log,
        "frustum_block": log,
        "middle_block": log,
        "base_block": log,
        "height_range": rng(*height),
        "direction": "UP",
        "may_place_on": SOIL,
        "may_replace": AIRISH,
    })


def blob(name, block, count, may_replace):
    return add_feature("ore_feature", name, {
        "count": count,
        "replace_rules": [{"places_block": block, "may_replace": may_replace}],
    })


def boulder(name, main, main_count, accent=None, accent_count=0):
    """A half-buried rock. The accent blob overlaps the main one for a mixed look."""
    extra = [main] if isinstance(main, str) else []
    if accent is None:
        return blob(name, main, main_count, BOULDER_REPLACE)
    a = blob(name + "_core", main, main_count, BOULDER_REPLACE)
    b = blob(name + "_accent", accent, accent_count, BOULDER_REPLACE + extra)
    return add_feature("aggregate_feature", name, {"features": [a, b]})


def single(name, blocks):
    """blocks: a block, or a list of (block, weight)."""
    if isinstance(blocks, list):
        places = [{"block": b, "weight": w} for b, w in blocks]
    else:
        places = blocks
    return add_feature("single_block_feature", name, {
        "places_block": places,
        "enforce_placement_rules": True,
        "enforce_survivability_rules": True,
        "may_replace": [mc("air")],
    })


def cluster(name, feature, iterations, spread):
    return add_feature("scatter_feature", name, {
        "places_feature": feature,
        "project_input_to_floor": True,
        "distribution": {
            "iterations": iterations,
            "coordinate_eval_order": "xzy",
            "x": {"distribution": "gaussian", "extent": [-spread, spread]},
            "y": HEIGHTMAP,
            "z": {"distribution": "gaussian", "extent": [-spread, spread]},
        },
    })


def mix(name, weighted):
    return add_feature("weighted_random_feature", name, {
        "features": [[f, w] for f, w in weighted],
    })


def state(block, **states):
    return {"name": mc(block), "states": states}


# --------------------------------------------------------------------------
# Feature rules
# --------------------------------------------------------------------------
def has(tag):
    return {"test": "has_biome_tag", "operator": "==", "value": tag}


def lacks(tag):
    return {"test": "has_biome_tag", "operator": "!=", "value": tag}


def biome_filter(any_tags, none_tags=()):
    tests = [has("overworld")]
    if len(any_tags) == 1:
        tests.append(has(any_tags[0]))
    else:
        tests.append({"any_of": [has(t) for t in any_tags]})
    tests += [lacks(t) for t in none_tags]
    return tests


def rule(name, feature, any_tags, iterations, chance=None, none_tags=(),
         y=HEIGHTMAP, placement_pass="surface_pass"):
    assert name not in RULES, name
    dist = {"iterations": iterations}
    if chance is not None:
        dist["scatter_chance"] = chance
    dist.update({
        "coordinate_eval_order": "xzy",
        "x": {"distribution": "uniform", "extent": [0, 15]},
        "y": y,
        "z": {"distribution": "uniform", "extent": [0, 15]},
    })
    RULES[name] = {
        "format_version": FV_RULE,
        "minecraft:feature_rules": {
            "description": {"identifier": ww(name), "places_feature": feature},
            "conditions": {
                "placement_pass": placement_pass,
                "minecraft:biome_filter": biome_filter(list(any_tags), none_tags),
            },
            "distribution": dist,
        },
    }


def ground_rule(name, feature, any_tags, iterations, chance=None, none_tags=()):
    """Ground patches swap the surface block, so they run before plants grow."""
    rule(name, feature, any_tags, iterations, chance, none_tags,
         y=HEIGHTMAP + " - 1", placement_pass="before_surface_pass")


def rock_rule(name, feature, any_tags, iterations, chance=None, none_tags=()):
    rule(name, feature, any_tags, iterations, chance, none_tags,
         placement_pass="before_surface_pass")


# --------------------------------------------------------------------------
# Shared features
# --------------------------------------------------------------------------
SPRUCE_LOG, OAK_LOG, BIRCH_LOG = mc("spruce_log"), mc("oak_log"), mc("birch_log")
DARK_LOG = mc("dark_oak_log")
SPRUCE_LEAVES, OAK_LEAVES, BIRCH_LEAVES = (
    mc("spruce_leaves"), mc("oak_leaves"), mc("birch_leaves"))
ORANGE, YELLOW, RED = ww("orange_leaves"), ww("yellow_leaves"), ww("red_leaves")


def build_shared_features():
    f = {}
    # Conifers
    f["young_spruce"] = spruce_tree("young_spruce", SPRUCE_LOG, SPRUCE_LEAVES,
                                    (6, 9), (1, 2), (0, 2), (2, 3))
    f["tall_spruce"] = spruce_tree("tall_spruce", SPRUCE_LOG, SPRUCE_LEAVES,
                                   (11, 17), (2, 4), (0, 2), (3, 4))
    f["giant_spruce"] = spruce_tree("giant_spruce", SPRUCE_LOG, SPRUCE_LEAVES,
                                    (19, 26), (3, 6), (0, 2), (4, 5))
    f["slim_pine"] = pine_tree("slim_pine", SPRUCE_LOG, SPRUCE_LEAVES,
                               (10, 16), (4, 7), 2)
    # Autumn larches (conifer shape, coloured needles)
    for colour, leaves in (("orange", ORANGE), ("yellow", YELLOW), ("red", RED)):
        f["larch_" + colour] = spruce_tree(
            "larch_" + colour, SPRUCE_LOG, leaves, (8, 13), (2, 3), (0, 1), (2, 3))
    # Broadleaf trees
    f["round_orange"] = round_tree("round_orange", OAK_LOG, ORANGE, (5, 8), -3)
    f["round_yellow"] = round_tree("round_yellow", BIRCH_LOG, YELLOW, (6, 9), -3)
    f["round_red"] = round_tree("round_red", DARK_LOG, RED, (5, 7), -3)
    f["green_oak"] = round_tree("green_oak", OAK_LOG, OAK_LEAVES, (4, 6), -3)
    f["big_oak"] = fancy_tree("big_oak", OAK_LOG, OAK_LEAVES, 8, 6)
    f["big_orange_oak"] = fancy_tree("big_orange_oak", OAK_LOG, ORANGE, 8, 6)
    # Bushes: one log wrapped in leaves
    for kind, log, leaves in (
            ("oak", OAK_LOG, OAK_LEAVES), ("spruce", SPRUCE_LOG, SPRUCE_LEAVES),
            ("birch", BIRCH_LOG, BIRCH_LEAVES), ("orange", OAK_LOG, ORANGE),
            ("yellow", BIRCH_LOG, YELLOW), ("red", DARK_LOG, RED)):
        f["bush_" + kind] = round_tree("bush_" + kind, log, leaves, (1, 2), -1)
    # Dead wood
    f["fallen_oak"] = fallen_log("fallen_oak", OAK_LOG, (3, 6))
    f["fallen_spruce"] = fallen_log("fallen_spruce", SPRUCE_LOG, (4, 7))
    f["fallen_birch"] = fallen_log("fallen_birch", BIRCH_LOG, (3, 6))
    f["driftwood"] = fallen_log("driftwood", mc("stripped_spruce_log"), (2, 4),
                                grow_on=SOIL + [mc("sand"), mc("gravel")])
    f["snag"] = snag("snag", SPRUCE_LOG, (3, 7))
    # Rocks
    f["boulder_stone"] = boulder("boulder_stone", mc("stone"), 16, mc("andesite"), 7)
    f["boulder_mossy"] = boulder("boulder_mossy", mc("cobblestone"), 14,
                                 mc("mossy_cobblestone"), 9)
    f["boulder_lime"] = boulder("boulder_lime", mc("calcite"), 18, mc("diorite"), 8)
    f["boulder_granite"] = boulder("boulder_granite", mc("granite"), 14, mc("stone"), 5)
    f["boulder_sandstone"] = boulder("boulder_sandstone", mc("sandstone"), 16)
    f["boulder_big"] = boulder("boulder_big", mc("stone"), 40, mc("tuff"), 14)
    f["rock_small"] = boulder("rock_small", mc("stone"), 4)
    f["rock_small_mossy"] = boulder("rock_small_mossy", mc("mossy_cobblestone"), 4)
    f["rock_small_lime"] = boulder("rock_small_lime", mc("calcite"), 5)
    # Ground patches
    for name, block, count, repl in (
            ("coarse", mc("coarse_dirt"), 30, PATCH_REPLACE),
            ("podzol", mc("podzol"), 33, PATCH_REPLACE),
            ("gravel", mc("gravel"), 24, PATCH_REPLACE),
            ("moss", mc("moss_block"), 20, PATCH_REPLACE),
            ("mud", mc("mud"), 30, PATCH_REPLACE),
            ("rooted", mc("dirt_with_roots"), 14, PATCH_REPLACE),
            ("stone", mc("stone"), 30, PATCH_REPLACE),
            ("andesite", mc("andesite"), 22, PATCH_REPLACE),
            ("calcite", mc("calcite"), 30, PATCH_REPLACE),
            ("diorite", mc("diorite"), 20, PATCH_REPLACE),
            ("bank_gravel", mc("gravel"), 26, BANK_REPLACE),
            ("bank_clay", mc("clay"), 16, BANK_REPLACE),
            ("bank_sand", mc("sand"), 22, BANK_REPLACE)):
        f["patch_" + name] = blob("patch_" + name, block, count, repl)
    # Small plants
    f["grass"] = single("grass", [(mc("short_grass"), 6), (mc("fern"), 1)])
    f["ferns"] = single("ferns", [(mc("fern"), 4), (mc("short_grass"), 2), (mc("bush"), 1)])
    f["shrub"] = single("shrub", mc("bush"))
    f["firefly_bush"] = single("firefly_bush", mc("firefly_bush"))
    f["dry_grass"] = single("dry_grass", [(mc("short_dry_grass"), 3), (mc("tall_dry_grass"), 2)])
    f["reeds"] = single("reeds", mc("reeds"))
    f["mushrooms"] = single("mushrooms", [(mc("brown_mushroom"), 3), (mc("red_mushroom"), 1)])
    f["moss_carpet"] = single("moss_carpet", mc("moss_carpet"))
    f["leaf_litter"] = single("leaf_litter", [
        (state("leaf_litter", growth=g), w) for g, w in ((0, 2), (1, 3), (2, 3), (3, 2))])
    f["meadow_flowers"] = single("meadow_flowers", [
        (mc("oxeye_daisy"), 4), (mc("azure_bluet"), 4), (mc("cornflower"), 2),
        (mc("dandelion"), 3), (mc("allium"), 1)])
    f["marsh_flowers"] = single("marsh_flowers", [
        (mc("blue_orchid"), 5), (mc("cornflower"), 3), (mc("azure_bluet"), 1)])
    f["woodland_flowers"] = single("woodland_flowers", [
        (mc("lily_of_the_valley"), 3), (mc("poppy"), 2), (mc("dandelion"), 2)])
    f["wildflowers"] = single("wildflowers", [
        (state("wildflowers", growth=g), 1) for g in (0, 1, 2, 3)])
    # Clusters
    f["grass_tuft"] = cluster("grass_tuft", f["grass"], 14, 5)
    f["fern_bed"] = cluster("fern_bed", f["ferns"], 12, 4)
    f["meadow_flower_patch"] = cluster("meadow_flower_patch", f["meadow_flowers"], 9, 4)
    f["marsh_flower_patch"] = cluster("marsh_flower_patch", f["marsh_flowers"], 8, 4)
    f["woodland_flower_patch"] = cluster("woodland_flower_patch", f["woodland_flowers"], 6, 3)
    f["wildflower_patch"] = cluster("wildflower_patch", f["wildflowers"], 8, 3)
    f["leaf_litter_patch"] = cluster("leaf_litter_patch", f["leaf_litter"], 10, 4)
    f["reed_bed"] = cluster("reed_bed", f["reeds"], 14, 4)
    f["dry_grass_tuft"] = cluster("dry_grass_tuft", f["dry_grass"], 8, 5)
    f["moss_carpet_patch"] = cluster("moss_carpet_patch", f["moss_carpet"], 8, 3)
    # Marsh pond: a mud-lined hollow filled with water
    f["pond_weed"] = single("pond_weed", mc("seagrass"))
    f["marsh_pond"] = add_feature("vegetation_patch_feature", "marsh_pond", {
        "replaceable_blocks": SOIL + [mc(b) for b in ("sand", "gravel", "clay", "stone")],
        "ground_block": mc("mud"),
        "vegetation_feature": f["pond_weed"],
        "surface": "floor",
        "depth": 2,
        "vertical_range": 3,
        "vegetation_chance": 0.15,
        "horizontal_radius": rng(2, 5),
        "extra_deep_block_chance": 0.6,
        "extra_edge_column_chance": 0.5,
        "waterlogged": True,
    })
    return f


# --------------------------------------------------------------------------
# Custom biomes
# --------------------------------------------------------------------------
def biome(name, climate, surface, targets, amount, scale, tags,
          adjustments=None, steep=None):
    comps = {"minecraft:climate": climate}
    if steep:
        # Same shape as vanilla stony_peaks, with all four slope directions on.
        comps["minecraft:mountain_parameters"] = {
            "steep_material_adjustment": dict(
                material=steep, north_slopes=True, east_slopes=True,
                south_slopes=True, west_slopes=True),
            "top_slide": {"enabled": False},
        }
    comps["minecraft:surface_builder"] = {"builder": surface}
    if adjustments:
        comps["minecraft:surface_material_adjustments"] = {"adjustments": adjustments}
    comps["minecraft:replace_biomes"] = {"replacements": [{
        "dimension": "minecraft:overworld",
        "targets": targets,
        "amount": amount,
        "noise_frequency_scale": scale,
    }]}
    comps["minecraft:tags"] = {"tags": ["overworld", "animal", "monster", "ww_biome", name] + tags}
    BIOMES[name] = {
        "format_version": FV_BIOME,
        "minecraft:biome": {"description": {"identifier": ww(name)}, "components": comps},
    }


def overworld_surface(top=mc("grass_block"), mid=mc("dirt"), floor=mc("gravel")):
    return {
        "type": "minecraft:overworld",
        "sea_floor_depth": 7,
        "sea_floor_material": floor,
        "foundation_material": mc("stone"),
        "mid_material": mid,
        "top_material": top,
        "sea_material": mc("water"),
    }


def adjust(noise_range, scale, **materials):
    return {"materials": materials, "noise_frequency_scale": scale, "noise_range": noise_range}


def client_biome(name, fog, water, grass, foliage, grading="minecraft:default_color_grading",
                 water_opacity=None):
    water_look = {"surface_color": water}
    if water_opacity is not None:
        water_look["surface_opacity"] = water_opacity
    CLIENT_BIOMES[name] = {
        "format_version": FV_CLIENT_BIOME,
        "minecraft:client_biome": {
            "description": {"identifier": ww(name)},
            "components": {
                "minecraft:fog_appearance": {"fog_identifier": fog},
                "minecraft:water_appearance": water_look,
                "minecraft:foliage_appearance": {"color": foliage},
                "minecraft:grass_appearance": {"color": grass},
                "minecraft:atmosphere_identifier": {
                    "atmosphere_identifier": "minecraft:default_atmospherics"},
                "minecraft:color_grading_identifier": {"color_grading_identifier": grading},
                "minecraft:lighting_identifier": {
                    "lighting_identifier": "minecraft:default_lighting"},
                "minecraft:water_identifier": {"water_identifier": "minecraft:default_water"},
                "minecraft:ambient_sounds": {
                    "underwater_addition": {
                        "asset": "ambient.underwater.additions", "chance": 0.01},
                    "underwater_loop": "ambient.underwater.loop",
                },
            },
        },
    }


def build_biomes(f):
    # ---- Autumn Forest -------------------------------------------------
    biome("autumn_forest",
          climate={"downfall": 0.7, "snow_accumulation": [0.0, 0.125], "temperature": 0.6},
          surface=overworld_surface(),
          targets=["forest", "birch_forest", "flower_forest", "roofed_forest"],
          amount=0.6, scale=22,
          tags=["bee_habitat"],
          adjustments=[
              adjust([0.3, 1.0], 0.0625, top_material=mc("podzol")),
              adjust([-1.0, -0.5], 0.0625, top_material=mc("coarse_dirt")),
          ])
    client_biome("autumn_forest", "minecraft:fog_forest", "#3f76a8", "#a8a04a", "#c98b2e")
    t = ["autumn_forest"]
    mix("autumn_tree_mix", [
        (f["larch_orange"], 5), (f["larch_yellow"], 5), (f["larch_red"], 3),
        (f["round_orange"], 3), (f["round_yellow"], 4), (f["round_red"], 2),
        (f["tall_spruce"], 3), (f["young_spruce"], 2), (f["green_oak"], 2),
        (f["big_orange_oak"], 1)])
    mix("autumn_bush_mix", [
        (f["bush_orange"], 3), (f["bush_yellow"], 3), (f["bush_red"], 2), (f["bush_oak"], 2)])
    mix("autumn_deadwood_mix", [(f["fallen_oak"], 3), (f["fallen_birch"], 2), (f["snag"], 1)])
    mix("autumn_rock_mix", [(f["boulder_mossy"], 3), (f["rock_small_mossy"], 3), (f["boulder_stone"], 1)])
    rule("autumn_forest_trees", ww("autumn_tree_mix"), t, 5)
    rule("autumn_forest_groves", ww("autumn_tree_mix"), t,
         "math.clamp(math.floor(query.noise(variable.originx * 0.012, variable.originz * 0.012) * 8), 0, 4)")
    rule("autumn_forest_bushes", ww("autumn_bush_mix"), t, 2)
    rule("autumn_forest_deadwood", ww("autumn_deadwood_mix"), t, 1, 40)
    rock_rule("autumn_forest_rocks", ww("autumn_rock_mix"), t, 1, 25)
    ground_rule("autumn_forest_moss", f["patch_moss"], t, 1, 35)
    rule("autumn_forest_litter", f["leaf_litter_patch"], t, 2)
    rule("autumn_forest_grass", f["grass_tuft"], t, 2)
    rule("autumn_forest_ferns", f["fern_bed"], t, 1, 50)
    rule("autumn_forest_flowers", f["woodland_flower_patch"], t, 1, 30)
    rule("autumn_forest_mushrooms", f["mushrooms"], t, 2, 40)

    # ---- Pine Highlands ------------------------------------------------
    biome("pine_highlands",
          climate={"downfall": 0.8, "snow_accumulation": [0.0, 0.25], "temperature": 0.25},
          surface=overworld_surface(),
          targets=["taiga", "mega_taiga", "redwood_taiga_mutated", "cold_taiga",
                   "extreme_hills_plus_trees", "grove"],
          amount=0.6, scale=22,
          tags=["spawns_cold_variant_farm_animals"],
          steep=mc("stone"),
          adjustments=[
              adjust([0.25, 1.0], 0.0625, top_material=mc("podzol")),
              adjust([-0.2, 0.02], 0.05, top_material=mc("coarse_dirt")),
              adjust([-1.0, -0.62], 0.04, top_material=mc("stone"), mid_material=mc("stone")),
          ])
    client_biome("pine_highlands", "minecraft:fog_taiga", "#2f6f86", "#6f9a5b", "#4f7f4a",
                 grading="minecraft:coolish_color_grading")
    t = ["pine_highlands"]
    mix("pine_tree_mix", [
        (f["tall_spruce"], 6), (f["slim_pine"], 5), (f["young_spruce"], 3),
        (f["giant_spruce"], 1)])
    mix("pine_deadwood_mix", [(f["fallen_spruce"], 3), (f["snag"], 2)])
    mix("pine_rock_mix", [
        (f["boulder_stone"], 4), (f["boulder_mossy"], 3), (f["rock_small"], 4),
        (f["boulder_big"], 1)])
    rule("pine_highlands_trees", ww("pine_tree_mix"), t, 5)
    rule("pine_highlands_groves", ww("pine_tree_mix"), t,
         "math.clamp(math.floor(query.noise(variable.originx * 0.01, variable.originz * 0.01) * 8), 0, 4)")
    rule("pine_highlands_bushes", f["bush_spruce"], t, 1)
    rule("pine_highlands_deadwood", ww("pine_deadwood_mix"), t, 1, 40)
    rock_rule("pine_highlands_rocks", ww("pine_rock_mix"), t, 1, 55)
    ground_rule("pine_highlands_gravel", f["patch_gravel"], t, 1, 40)
    ground_rule("pine_highlands_roots", f["patch_rooted"], t, 1, 30)
    rule("pine_highlands_ferns", f["fern_bed"], t, 2)
    rule("pine_highlands_grass", f["grass_tuft"], t, 1)
    rule("pine_highlands_mushrooms", f["mushrooms"], t, 2, 35)

    # ---- Limestone Highlands -------------------------------------------
    biome("limestone_highlands",
          climate={"downfall": 0.6, "snow_accumulation": [0.0, 0.125], "temperature": 0.5},
          surface=overworld_surface(),
          targets=["meadow", "extreme_hills", "extreme_hills_mutated", "stony_peaks",
                   "savanna_plateau"],
          amount=0.65, scale=22,
          tags=["mountains", "spawns_cold_variant_farm_animals"],
          steep=mc("calcite"),
          adjustments=[
              adjust([0.42, 1.0], 0.07, top_material=mc("calcite"), mid_material=mc("calcite")),
              adjust([-0.08, 0.04], 0.045, top_material=mc("stone"), mid_material=mc("stone")),
              adjust([-1.0, -0.7], 0.06, top_material=mc("diorite"), mid_material=mc("calcite")),
          ])
    client_biome("limestone_highlands", "minecraft:fog_extreme_hills", "#3d8fc4",
                 "#8fc050", "#77ab2f")
    t = ["limestone_highlands"]
    mix("limestone_tree_mix", [(f["tall_spruce"], 3), (f["slim_pine"], 3), (f["young_spruce"], 3)])
    mix("limestone_rock_mix", [
        (f["boulder_lime"], 4), (f["rock_small_lime"], 5), (f["boulder_stone"], 1)])
    mix("limestone_patch_mix", [
        (f["patch_calcite"], 4), (f["patch_stone"], 3), (f["patch_diorite"], 2)])
    rule("limestone_highlands_trees", ww("limestone_tree_mix"), t, 1, 70)
    rule("limestone_highlands_copses", ww("limestone_tree_mix"), t,
         "math.clamp(math.floor(query.noise(variable.originx * 0.015, variable.originz * 0.015) * 10) - 1, 0, 4)")
    rule("limestone_highlands_bushes", f["bush_oak"], t, 1, 60)
    rock_rule("limestone_highlands_rocks", ww("limestone_rock_mix"), t, 1, 65)
    ground_rule("limestone_highlands_outcrops", ww("limestone_patch_mix"), t, 2)
    rule("limestone_highlands_grass", f["grass_tuft"], t, 3)
    rule("limestone_highlands_flowers", f["meadow_flower_patch"], t, 1, 70)
    rule("limestone_highlands_wildflowers", f["wildflower_patch"], t, 1, 30)

    # ---- Marshland -----------------------------------------------------
    marsh_surface = overworld_surface(floor=mc("mud"))
    marsh_surface["type"] = "minecraft:swamp"
    marsh_surface["max_puddle_depth_below_sea_level"] = 1
    biome("marshland",
          climate={"downfall": 0.9, "snow_accumulation": [0.0, 0.125], "temperature": 0.65},
          surface=marsh_surface,
          targets=["plains", "sunflower_plains", "swampland"],
          amount=0.5, scale=22,
          tags=["spawns_slimes_on_surface", "spawns_warm_variant_frogs"],
          adjustments=[
              adjust([0.35, 1.0], 0.08, top_material=mc("mud")),
              adjust([-1.0, -0.55], 0.07, top_material=mc("coarse_dirt")),
              adjust([-0.12, 0.0], 0.06, top_material=mc("moss_block")),
          ])
    client_biome("marshland", "minecraft:fog_swampland", "#3f9a94", "#7fae4a", "#6aa03f",
                 water_opacity=0.7)
    t = ["marshland"]
    mix("marsh_tree_mix", [
        (f["green_oak"], 5), (f["tall_spruce"], 2), (f["young_spruce"], 2),
        (f["big_oak"], 1)])
    mix("marsh_bush_mix", [(f["bush_oak"], 5), (f["bush_birch"], 2), (f["bush_spruce"], 1)])
    mix("marsh_deadwood_mix", [(f["fallen_oak"], 2), (f["fallen_spruce"], 1), (f["snag"], 1)])
    rule("marshland_ponds", f["marsh_pond"], t, 1, 55, placement_pass="before_surface_pass")
    rule("marshland_trees", ww("marsh_tree_mix"), t, 1)
    rule("marshland_bushes", ww("marsh_bush_mix"), t, 3)
    rule("marshland_deadwood", ww("marsh_deadwood_mix"), t, 1, 25)
    rock_rule("marshland_rocks", f["rock_small_mossy"], t, 1, 15)
    ground_rule("marshland_mud", f["patch_mud"], t, 1, 60)
    rule("marshland_reeds", f["reed_bed"], t, 2)
    rule("marshland_grass", f["grass_tuft"], t, 3)
    rule("marshland_ferns", f["fern_bed"], t, 1, 50)
    rule("marshland_flowers", f["marsh_flower_patch"], t, 1, 80)
    rule("marshland_fireflies", f["firefly_bush"], t, 1, 40)
    rule("marshland_moss", f["moss_carpet_patch"], t, 1, 30)


# --------------------------------------------------------------------------
# Realism pass over the vanilla biomes
# --------------------------------------------------------------------------
def build_vanilla_rules(f):
    woods = ["forest"]
    not_woods = ("taiga", "jungle", "ww_biome", "pale_garden", "mangrove_swamp")
    mix("woods_deadwood_mix", [(f["fallen_oak"], 3), (f["fallen_birch"], 2)])
    mix("woods_patch_mix", [(f["patch_coarse"], 3), (f["patch_podzol"], 2), (f["patch_moss"], 2),
                            (f["patch_rooted"], 1)])
    mix("woods_rock_mix", [(f["boulder_mossy"], 2), (f["rock_small_mossy"], 3), (f["rock_small"], 2)])
    rule("woods_deadwood", ww("woods_deadwood_mix"), woods, 1, 25, not_woods)
    rule("woods_bushes", f["bush_oak"], woods, 1, 70, not_woods)
    rule("woods_big_oaks", f["big_oak"], woods, 1, 15, not_woods)
    rock_rule("woods_rocks", ww("woods_rock_mix"), woods, 1, 15, not_woods)
    ground_rule("woods_patches", ww("woods_patch_mix"), woods, 1, 60, not_woods)
    rule("woods_litter", f["leaf_litter_patch"], woods, 1, 50, not_woods)

    taiga = ["taiga"]
    not_taiga = ("ww_biome",)
    mix("taiga_tree_mix", [(f["tall_spruce"], 6), (f["slim_pine"], 3), (f["giant_spruce"], 1)])
    mix("taiga_deadwood_mix", [(f["fallen_spruce"], 3), (f["snag"], 1)])
    mix("taiga_patch_mix", [(f["patch_podzol"], 3), (f["patch_coarse"], 2), (f["patch_gravel"], 1)])
    mix("taiga_rock_mix", [(f["boulder_mossy"], 3), (f["rock_small_mossy"], 3), (f["boulder_stone"], 1)])
    rule("taiga_tall_trees", ww("taiga_tree_mix"), taiga, 1, None, not_taiga)
    rule("taiga_deadwood", ww("taiga_deadwood_mix"), taiga, 1, 30, not_taiga)
    rock_rule("taiga_rocks", ww("taiga_rock_mix"), taiga, 1, 22, not_taiga)
    ground_rule("taiga_patches", ww("taiga_patch_mix"), taiga, 1, 65, not_taiga)
    rule("taiga_ferns", f["fern_bed"], taiga, 1, 45, not_taiga)

    plains = ["plains"]
    mix("plains_patch_mix", [(f["patch_coarse"], 3), (f["patch_gravel"], 1)])
    mix("plains_rock_mix", [(f["rock_small"], 4), (f["boulder_stone"], 1)])
    mix("plains_lone_tree_mix", [(f["green_oak"], 3), (f["big_oak"], 1)])
    rule("plains_bushes", f["bush_oak"], plains, 1, 35)
    rule("plains_shrubs", f["shrub"], plains, 2, 60)
    rule("plains_lone_trees", ww("plains_lone_tree_mix"), plains, 1, 6)
    rock_rule("plains_rocks", ww("plains_rock_mix"), plains, 1, 10)
    ground_rule("plains_patches", ww("plains_patch_mix"), plains, 1, 35)
    rule("plains_wildflowers", f["wildflower_patch"], plains, 1, 12)

    hills = ["extreme_hills", "mountains"]
    not_hills = ("ww_biome", "frozen", "forest")
    mix("hills_patch_mix", [(f["patch_stone"], 4), (f["patch_andesite"], 2), (f["patch_gravel"], 3)])
    mix("hills_rock_mix", [(f["boulder_stone"], 4), (f["rock_small"], 4), (f["boulder_big"], 1)])
    mix("hills_tree_mix", [(f["slim_pine"], 3), (f["young_spruce"], 2)])
    ground_rule("hills_patches", ww("hills_patch_mix"), hills, 2, None, not_hills)
    rock_rule("hills_rocks", ww("hills_rock_mix"), hills, 1, 45, not_hills)
    rule("hills_pines", ww("hills_tree_mix"), ["extreme_hills"], 1, 30, ("ww_biome",))

    savanna = ["savanna"]
    mix("savanna_rock_mix", [(f["boulder_granite"], 2), (f["rock_small"], 3)])
    rule("savanna_dry_grass", f["dry_grass_tuft"], savanna, 1, 70)
    ground_rule("savanna_patches", f["patch_coarse"], savanna, 1, 70)
    rock_rule("savanna_rocks", ww("savanna_rock_mix"), savanna, 1, 15)
    rule("savanna_bushes", f["bush_oak"], savanna, 1, 20)

    desert = ["desert"]
    rule("desert_dry_grass", f["dry_grass_tuft"], desert, 1, 25)
    rock_rule("desert_rocks", f["boulder_sandstone"], desert, 1, 10)

    river = ["river"]
    not_river = ("frozen",)
    mix("river_bank_mix", [(f["patch_bank_gravel"], 4), (f["patch_bank_clay"], 1), (f["patch_bank_sand"], 2)])
    ground_rule("river_banks", ww("river_bank_mix"), river, 2, None, not_river)
    rule("river_reeds", f["reed_bed"], river, 1, 60, not_river)
    rock_rule("river_rocks", f["rock_small"], river, 1, 20, not_river)

    beach = ["beach"]
    not_beach = ("frozen", "cold")
    ground_rule("beach_gravel", f["patch_bank_gravel"], beach, 1, 35, not_beach)
    rock_rule("beach_rocks", f["rock_small"], beach, 1, 10, not_beach)
    rule("beach_driftwood", f["driftwood"], beach, 1, 10, not_beach)

    swamp = ["swamp"]
    not_swamp = ("ww_biome", "mangrove_swamp")
    ground_rule("swamp_mud", f["patch_mud"], swamp, 1, 70, not_swamp)
    rule("swamp_reeds", f["reed_bed"], swamp, 1, 60, not_swamp)
    rule("swamp_deadwood", f["fallen_oak"], swamp, 1, 20, not_swamp)

    jungle = ["jungle"]
    ground_rule("jungle_moss", f["patch_moss"], jungle, 1, 70)
    rock_rule("jungle_rocks", f["boulder_mossy"], jungle, 1, 15)


# --------------------------------------------------------------------------
# Custom blocks (autumn leaves)
# --------------------------------------------------------------------------
LEAF_COLOURS = {
    # name: (display name, light, mid, dark, gap, map colour)
    "orange_leaves": ("Orange Autumn Leaves", (232, 136, 44), (206, 104, 28), (170, 78, 20), (96, 44, 14), "#ce681c"),
    "yellow_leaves": ("Yellow Autumn Leaves", (236, 198, 62), (214, 170, 40), (178, 134, 26), (104, 78, 18), "#d6aa28"),
    "red_leaves": ("Red Autumn Leaves", (206, 74, 44), (176, 52, 34), (140, 38, 28), (76, 24, 18), "#b03422"),
}


def leaf_block(name, map_colour):
    return {
        "format_version": FV_BLOCK,
        "minecraft:block": {
            "description": {
                "identifier": ww(name),
                "menu_category": {"category": "nature", "group": "minecraft:itemGroup.name.leaves"},
            },
            "components": {
                "minecraft:geometry": "minecraft:geometry.full_block",
                "minecraft:material_instances": {
                    "*": {"texture": NS + "_" + name, "render_method": "opaque"},
                },
                "minecraft:destructible_by_mining": {"seconds_to_destroy": 0.3},
                "minecraft:destructible_by_explosion": {"explosion_resistance": 0.2},
                "minecraft:flammable": {"catch_chance_modifier": 30, "destroy_chance_modifier": 60},
                "minecraft:light_dampening": 1,
                "minecraft:map_color": map_colour,
            },
        },
    }


def leaf_texture(path, light, mid, dark, gap, seed):
    """16x16 opaque leaf texture: clumps of three tones with dark gaps."""
    r = random.Random(seed)
    size = 16
    img = Image.new("RGB", (size, size))
    cells = [[r.random() for _ in range(size)] for _ in range(size)]
    for y in range(size):
        for x in range(size):
            # Smooth a little so tones form leaf-sized clumps instead of static.
            n = (cells[y][x] * 2 + cells[y][(x + 1) % size] + cells[(y + 1) % size][x]) / 4
            if n < 0.24:
                colour = gap
            elif n < 0.45:
                colour = dark
            elif n < 0.72:
                colour = mid
            else:
                colour = light
            img.putpixel((x, y), colour)
    img.save(path)


def pack_icon(path):
    """A tiny landscape: sky, grey peaks, pines, autumn trees and a pond."""
    r = random.Random(7)
    s = 32
    img = Image.new("RGB", (s, s), (150, 196, 232))
    px = img.putpixel
    for x in range(s):  # mountains
        peak = int(9 + 5 * abs(((x * 3) % 22) - 11) / 11)
        for y in range(peak, 20):
            px((x, y), (236, 238, 240) if y < peak + 2 else (132, 136, 146))
    for x in range(s):  # hills and meadow
        top = 17 + int(2 * ((x * 5) % 7) / 7)
        for y in range(top, s):
            px((x, y), (104, 160, 62) if y < 26 else (86, 138, 52))
    for x in range(10, 24):  # pond
        for y in range(27, 31):
            if 0 < (x - 9) < 14 - abs(y - 29) * 2:
                px((x, y), (70, 150, 160))
    for tx, base, colour in ((3, 24, (40, 96, 52)), (8, 22, (232, 136, 44)), (14, 23, (40, 96, 52)),
                             (20, 21, (214, 170, 40)), (25, 23, (176, 52, 34)), (29, 24, (40, 96, 52))):
        height = 7 + r.randint(0, 3)
        for i in range(height):
            half = min(2, i // 2)
            for dx in range(-half, half + 1):
                if 0 <= tx + dx < s:
                    px((tx + dx, base - height + i), colour)
        px((tx, base), (72, 50, 30))
    img.resize((128, 128), Image.NEAREST).save(path)


# --------------------------------------------------------------------------
# Lite shader pack
# --------------------------------------------------------------------------
# Bedrock on phones cannot load custom shader code, so this pack gets its look
# from the things the game does let a resource pack change: fog and the sky
# textures. None of it costs frame rate.
HAZE_START = 0.5      # fraction of render distance where the haze begins
WATER_CLARITY = 1.6   # how much further you can see under water
NOT_OVERWORLD = ("hell", "basalt_deltas", "crimson_forest", "warped_forest",
                 "soulsand_valley", "the_end")


def shader_fogs(samples):
    """Rewrites the vanilla fog files with a soft distance haze and clearer water."""
    out = SHADER / "fogs"
    if out.exists():
        shutil.rmtree(out)
    for path in sorted((samples / "resource_pack" / "fogs").glob("*.json")):
        if path.name.startswith(NOT_OVERWORLD):
            continue
        data = json.loads(path.read_text())
        distance = data["minecraft:fog_settings"].get("distance", {})
        changed = False
        air = distance.get("air")
        if air and air.get("render_distance_type") == "render" and air["fog_start"] > HAZE_START:
            air["fog_start"] = HAZE_START
            changed = True
        water = distance.get("water")
        if water and water.get("render_distance_type") == "fixed":
            water["fog_end"] = round(water["fog_end"] * WATER_CLARITY, 1)
            changed = True
        if changed:
            write_json(out / path.name, data)


def disc(img, cx, cy, radius, shade):
    """Anti-aliased filled circle; shade(x, y) returns the colour or None."""
    for y in range(int(cy - radius - 1), int(cy + radius + 2)):
        for x in range(int(cx - radius - 1), int(cx + radius + 2)):
            cover = 0
            for sy in (0.25, 0.75):
                for sx in (0.25, 0.75):
                    if (x + sx - cx) ** 2 + (y + sy - cy) ** 2 <= radius * radius:
                        cover += 0.25
            colour = shade(x, y) if cover else None
            if colour:
                base = img.getpixel((x, y))
                img.putpixel((x, y), tuple(
                    int(base[i] + (colour[i] - base[i]) * cover) for i in range(3)))


def glow(img, cx, cy, reach, colour, strength):
    for y in range(int(cy - reach), int(cy + reach) + 1):
        for x in range(int(cx - reach), int(cx + reach) + 1):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5 / reach
            if d < 1:
                k = (1 - d) ** 2.2 * strength
                base = img.getpixel((x, y))
                img.putpixel((x, y), tuple(min(255, int(base[i] + colour[i] * k)) for i in range(3)))


def shader_sky():
    """Round sun and moon in the vanilla layout (the game draws black as clear)."""
    env = SHADER / "textures" / "environment"
    env.mkdir(parents=True, exist_ok=True)
    cell = 64
    sun = Image.new("RGB", (cell, cell), (0, 0, 0))
    glow(sun, 32, 32, 30, (255, 196, 96), 0.55)
    disc(sun, 32, 32, 9.5, lambda x, y: (255, 226, 150))
    disc(sun, 32, 32, 7.5, lambda x, y: (255, 248, 216))
    sun.save(env / "sun.png")

    r = random.Random(3)
    craters = [(r.uniform(-6, 6), r.uniform(-6, 6), r.uniform(1.2, 2.6)) for _ in range(7)]
    moon = Image.new("RGB", (cell * 4, cell * 2), (0, 0, 0))
    # Vanilla order: full, then waning (lit on the left), new, then waxing.
    for phase in range(8):
        cx, cy = (phase % 4) * cell + 32, (phase // 4) * cell + 32
        lit = {0: 1.0, 1: 0.75, 2: 0.5, 3: 0.25, 4: 0.0, 5: 0.25, 6: 0.5, 7: 0.75}[phase]
        left_lit = phase < 4
        glow(moon, cx, cy, 26, (70, 90, 140), 0.25 + 0.35 * lit)

        def shade(x, y, cx=cx, cy=cy, lit=lit, left_lit=left_lit):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            tone = (214, 222, 236)
            for ox, oy, rad in craters:
                if (dx - ox) ** 2 + (dy - oy) ** 2 < rad * rad:
                    tone = (176, 186, 206)
            # Terminator: an ellipse whose width follows the phase.
            half = max(0.0, 9.0 * 9.0 - dy * dy) ** 0.5
            edge = half * (1 - 2 * lit)            # +half (dark) .. -half (full)
            side = -dx if left_lit else dx
            if side < edge:
                return (16, 20, 34)
            return tone

        disc(moon, cx, cy, 9.0, shade)
    moon.save(env / "moon_phases.png")


def build_shader(samples):
    if samples:
        shader_fogs(samples)
    elif not (SHADER / "fogs").exists():
        raise SystemExit("first build needs the bedrock-samples path for the shader fogs")
    shader_sky()
    write_json(SHADER / "manifest.json", manifest(
        "Wilder Worlds Lite Shader",
        "Soft distance haze, clearer water, round sun and moon. No frame rate cost.",
        SHADER_UUID, SHADER_MODULE_UUID, "resources"))
    shutil.copy(BP / "pack_icon.png", SHADER / "pack_icon.png")


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------
def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def manifest(name, description, uuid, module_uuid, module_type, dependency=None):
    data = {
        "format_version": 2,
        "header": {
            "name": name,
            "description": description,
            "uuid": uuid,
            "version": VERSION,
            "min_engine_version": MIN_ENGINE,
        },
        "modules": [{"type": module_type, "uuid": module_uuid, "version": VERSION}],
    }
    if dependency:
        data["dependencies"] = [{"uuid": dependency, "version": VERSION}]
    return data


def main():
    samples = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    for folder in (BP, RP):
        if folder.exists():
            shutil.rmtree(folder)

    features = build_shared_features()
    build_biomes(features)
    build_vanilla_rules(features)

    # Behavior pack
    write_json(BP / "manifest.json", manifest(
        "Wilder Worlds", "4 new biomes and more natural terrain. Use on a NEW world.",
        BP_UUID, BP_MODULE_UUID, "data", RP_UUID))
    for name, data in BIOMES.items():
        write_json(BP / "biomes" / (name + ".biome.json"), data)
    for name, data in FEATURES.items():
        write_json(BP / "features" / (name + ".json"), data)
    for name, data in RULES.items():
        write_json(BP / "feature_rules" / (name + ".json"), data)
    for name, spec in LEAF_COLOURS.items():
        write_json(BP / "blocks" / (name + ".json"), leaf_block(name, spec[5]))

    # Resource pack
    write_json(RP / "manifest.json", manifest(
        "Wilder Worlds Resources", "Colours and textures for Wilder Worlds.",
        RP_UUID, RP_MODULE_UUID, "resources"))
    for name, data in CLIENT_BIOMES.items():
        write_json(RP / "biomes" / (name + ".client_biome.json"), data)
    textures = {}
    sounds = {"format_version": "1.19.30"}
    lang = []
    (RP / "textures" / "blocks").mkdir(parents=True, exist_ok=True)
    for i, (name, spec) in enumerate(LEAF_COLOURS.items()):
        key = NS + "_" + name
        textures[key] = {"textures": "textures/blocks/" + key}
        leaf_texture(RP / "textures" / "blocks" / (key + ".png"), *spec[1:5], seed=11 + i)
        sounds[ww(name)] = {"sound": "grass"}
        lang.append("tile.%s.name=%s" % (ww(name), spec[0]))
    write_json(RP / "textures" / "terrain_texture.json", {
        "resource_pack_name": "wilder_worlds",
        "texture_name": "atlas.terrain",
        "padding": 8,
        "num_mip_levels": 4,
        "texture_data": textures,
    })
    write_json(RP / "blocks.json", sounds)
    (RP / "texts").mkdir(parents=True, exist_ok=True)
    (RP / "texts" / "en_US.lang").write_text("\n".join(lang) + "\n")
    write_json(RP / "texts" / "languages.json", ["en_US"])
    pack_icon(BP / "pack_icon.png")
    shutil.copy(BP / "pack_icon.png", RP / "pack_icon.png")
    build_shader(samples)

    # .mcaddon = zip holding both packs
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
    print("biomes %d, features %d, rules %d, blocks %d -> %s (%d KB)" % (
        len(BIOMES), len(FEATURES), len(RULES), len(LEAF_COLOURS), out.name,
        out.stat().st_size // 1024))


if __name__ == "__main__":
    main()
