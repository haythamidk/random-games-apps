#!/usr/bin/env python3
"""World generation helpers for the Wilder Worlds add-on (see build.py).

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
VERSION = [2, 1, 0]
MIN_ENGINE = [1, 26, 30]
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

CUSTOM_LEAVES = []   # build.py adds the pack's own leaves once the blocks exist

LEAVES = [mc(b) for b in (
    "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
    "acacia_leaves", "dark_oak_leaves", "azalea_leaves",
    "azalea_leaves_flowered", "mangrove_leaves", "cherry_leaves",
    "pale_oak_leaves")] + [ww(b) for b in CUSTOM_LEAVES]

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


HALF = {"numerator": 1, "denominator": 2}
ALWAYS = {"numerator": 1, "denominator": 1}


def round_tree(name, log, leaves, height, grow_on=None, hanging=False):
    """Same canopy as the vanilla oak and birch: four layers, 5 wide then 3 wide.

    hanging adds strands of the leaf block below the canopy, the way the
    vanilla jungle tree hangs vines.
    """
    body = {
        "trunk": {"trunk_height": rng(*height), "trunk_block": log},
        "canopy": {
            "canopy_offset": {"min": -3, "max": 0},
            "variation_chance": [HALF, HALF, HALF, ALWAYS],
            "leaf_block": leaves,
        },
    }
    if hanging:
        body["canopy"]["canopy_decoration"] = {
            "decoration_block": leaves,
            "decoration_chance": {"numerator": 1, "denominator": 3},
            "num_steps": 4,
            "step_direction": "down",
        }
    body.update(tree_base(grow_on))
    return add_feature("tree_feature", name, body)


def bush(name, log, leaves):
    """Same shape as the vanilla jungle bush: one log under a low 5-wide dome."""
    body = {
        "trunk": {"trunk_height": 1, "trunk_block": log},
        "canopy": {
            "canopy_offset": {"min": -1, "max": 1},
            "min_width": 0,
            "canopy_slope": {"rise": 1, "run": 1},
            "variation_chance": HALF,
            "leaf_block": leaves,
        },
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def roofed_tree(name, log, leaves):
    """Vanilla dark oak layout: a 2x2 trunk under one broad, flat-topped crown."""
    body = {
        "acacia_trunk": {
            "trunk_width": 2,
            "trunk_height": {"base": 6, "intervals": [3, 2]},
            "trunk_block": log,
            "trunk_lean": {
                "allow_diagonal_growth": False,
                "lean_height": rng(0, 4),
                "lean_steps": rng(0, 3),
            },
            "branches": {
                "branch_chance": {"numerator": 1, "denominator": 3},
                "branch_length": rng(2, 5),
                "branch_position": 1,
                "branch_canopy": {"acacia_canopy": {
                    "canopy_size": 2, "leaf_block": leaves, "simplify_canopy": True}},
            },
        },
        "roofed_canopy": {
            "canopy_height": 4, "core_width": 2, "outer_radius": 2, "inner_radius": 3,
            "leaf_block": leaves,
        },
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def acacia_tree(name, log, leaves):
    """Vanilla savanna tree layout: a leaning trunk, side branch and flat umbrella crowns."""
    body = {
        "acacia_trunk": {
            "trunk_width": 1,
            "trunk_height": {"base": 5, "intervals": [3, 3]},
            "trunk_block": log,
            "trunk_lean": {
                "allow_diagonal_growth": True,
                "lean_height": rng(1, 5),
                "lean_steps": rng(1, 4),
            },
            "branches": {
                "branch_chance": 100.0,
                "branch_length": rng(1, 4),
                "branch_position": rng(1, 3),
                "branch_canopy": {"acacia_canopy": {
                    "canopy_size": 2, "leaf_block": leaves, "simplify_canopy": True}},
            },
        },
        "acacia_canopy": {"canopy_size": 3, "leaf_block": leaves},
    }
    body.update(tree_base())
    return add_feature("tree_feature", name, body)


def mega_tree(name, log, leaves, base, intervals, canopy_height):
    """Vanilla giant spruce layout (2x2 trunk) with our own blocks."""
    body = {
        "mega_trunk": {
            "trunk_width": 2,
            "trunk_height": {"base": base, "intervals": intervals},
            "trunk_block": log,
        },
        "mega_pine_canopy": {
            "canopy_height": rng(*canopy_height),
            "base_radius": 0,
            "radius_step_modifier": 3.5,
            "core_width": 2,
            "leaf_block": leaves,
        },
        "base_block": mc("podzol"),
        "base_cluster": {
            "may_replace": [mc("grass_block"), mc("dirt"), mc("coarse_dirt")],
            "num_clusters": 5,
            "cluster_radius": 2,
        },
        "may_grow_on": SOIL,
        "may_replace": TREE_REPLACE,
        "may_grow_through": TREE_THROUGH,
    }
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
        # The game only finds vanilla biomes by their full id; bare names are
        # rejected with "No biome found", whatever the reference docs say.
        "targets": [mc(t) for t in targets],
        "amount": amount,
        "noise_frequency_scale": scale,
    }]}
    comps["minecraft:tags"] = {"tags": ["overworld", "monster", "ww_biome", name] + tags}
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


# --------------------------------------------------------------------------
# Lite shader pack
# --------------------------------------------------------------------------
# Bedrock on phones cannot load custom shader code, so this pack gets its look
# from the things the game does let a resource pack change: fog and the sky
# textures. None of it costs frame rate.
HAZE_START = 0.5      # fraction of render distance where the haze begins
WATER_CLARITY = 1.6   # how much further you can see under water
SKY = "#4f97ff"       # a deeper blue than the default sky
WATER_OPACITY = 0.45  # water you can see the bottom through
NOT_OVERWORLD = ("hell", "basalt_deltas", "crimson_forest", "warped_forest",
                 "soulsand_valley", "the_end")


def shader_fogs(samples):
    """Soft distance haze and clearer water for every overworld biome.

    A pack may not redefine a minecraft: fog, so each vanilla fog is copied
    under our namespace and the vanilla client biomes are pointed at the copy.
    """
    for folder in (SHADER / "fogs", SHADER / "biomes"):
        if folder.exists():
            shutil.rmtree(folder)
    vanilla = {}
    for path in (samples / "resource_pack" / "fogs").glob("*.json"):
        data = json.loads(path.read_text())
        vanilla[data["minecraft:fog_settings"]["description"]["identifier"]] = data
    default_air = vanilla["minecraft:fog_default"]["minecraft:fog_settings"]["distance"]["air"]

    def haze(fog_id):
        new_id = fog_id.replace("minecraft:", NS + ":")
        path = SHADER / "fogs" / (new_id.split(":")[1] + ".json")
        if not path.exists():
            data = json.loads(json.dumps(vanilla[fog_id]))
            settings = data["minecraft:fog_settings"]
            settings["description"]["identifier"] = new_id
            distance = settings.setdefault("distance", {})
            # Biome fogs without their own air settings fall back to the
            # default fog, which we cannot touch, so give each one its own.
            air = distance.setdefault("air", dict(default_air))
            if air.get("render_distance_type") == "render" and air["fog_start"] > HAZE_START:
                air["fog_start"] = HAZE_START
            water = distance.get("water")
            if water and water.get("render_distance_type") == "fixed":
                water["fog_end"] = round(water["fog_end"] * WATER_CLARITY, 1)
            write_json(path, data)
        return new_id

    sources = sorted((samples / "resource_pack" / "biomes").glob("*.json"))
    sources += sorted((RP / "biomes").glob("*.json"))
    for path in sources:
        if path.name.startswith(NOT_OVERWORLD):
            continue
        data = json.loads(path.read_text())
        fog = data["minecraft:client_biome"]["components"].get("minecraft:fog_appearance")
        if not fog or fog["fog_identifier"] not in vanilla:
            continue
        fog["fog_identifier"] = haze(fog["fog_identifier"])
        comps = data["minecraft:client_biome"]["components"]
        comps.setdefault("minecraft:sky_color", {"sky_color": SKY})
        water = comps.get("minecraft:water_appearance")
        if water is not None and "surface_opacity" not in water:
            water["surface_opacity"] = WATER_OPACITY
        write_json(SHADER / "biomes" / path.name, data)


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


