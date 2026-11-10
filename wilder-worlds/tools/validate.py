#!/usr/bin/env python3
"""Checks the generated packs against Mojang's published schemas and block list.

Usage:  python3 wilder-worlds/tools/validate.py /path/to/bedrock-samples

bedrock-samples is https://github.com/Mojang/bedrock-samples (it ships the JSON
schemas, the vanilla block list and the vanilla biomes for the matching game
version). The game itself is the final judge; this catches typos before that.
"""
import json
import re
import sys
import zipfile
from pathlib import Path
from urllib.parse import quote

from jsonschema import Draft7Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent.parent
BP = ROOT / "behavior_pack"
RP = ROOT / "resource_pack"
SHADER = ROOT / "shader_pack"

# Keys the feature documentation lists for each feature type we use.
FEATURE_KEYS = {
    "minecraft:tree_feature": {
        "description", "base_block", "base_cluster", "may_grow_on", "may_replace",
        "may_grow_through", "trunk", "fallen_trunk", "fancy_trunk", "mega_trunk", "acacia_trunk",
        "acacia_canopy", "roofed_canopy",
        "spruce_canopy", "pine_canopy", "canopy", "fancy_canopy", "mega_pine_canopy"},
    "minecraft:ore_feature": {
        "description", "count", "discard_chance_on_air_exposure", "replace_rules"},
    "minecraft:aggregate_feature": {"description", "features", "early_out"},
    "minecraft:weighted_random_feature": {"description", "features"},
    "minecraft:scatter_feature": {
        "description", "places_feature", "project_input_to_floor", "distribution"},
    "minecraft:single_block_feature": {
        "description", "places_block", "enforce_placement_rules",
        "enforce_survivability_rules", "randomize_rotation", "may_attach_to",
        "may_not_attach_to", "may_replace"},
    "minecraft:vegetation_patch_feature": {
        "description", "replaceable_blocks", "ground_block", "vegetation_feature",
        "surface", "depth", "extra_deep_block_chance", "vertical_range",
        "vegetation_chance", "horizontal_radius", "extra_edge_column_chance",
        "waterlogged"},
}
TREE_PART_KEYS = {
    "trunk": {"trunk_height", "height_modifier", "can_be_submerged", "trunk_block",
              "trunk_decoration"},
    "fallen_trunk": {"log_length", "stump_height", "height_modifier", "trunk_block",
                     "log_decoration_feature", "trunk_decoration"},
    "fancy_trunk": {"trunk_height", "trunk_width", "branches", "trunk_block",
                    "width_scale", "foliage_altitude_factor"},
    "spruce_canopy": {"lower_offset", "upper_offset", "max_radius", "leaf_block"},
    "pine_canopy": {"canopy_height", "base_radius", "leaf_block"},
    "canopy": {"canopy_offset", "min_width", "canopy_slope", "variation_chance",
               "leaf_block", "canopy_decoration"},
    "fancy_canopy": {"height", "radius", "leaf_block"},
    "acacia_trunk": {"trunk_width", "trunk_height", "trunk_block", "trunk_lean", "branches"},
    "acacia_canopy": {"canopy_size", "leaf_block", "simplify_canopy"},
    "roofed_canopy": {"canopy_height", "core_width", "outer_radius", "inner_radius", "leaf_block"},
    "mega_trunk": {"trunk_width", "trunk_height", "trunk_block", "trunk_decoration", "branches"},
    "mega_pine_canopy": {"canopy_height", "base_radius", "radius_step_modifier", "core_width",
                         "leaf_block"},
}
PASSES = {
    "first_pass", "before_underground_pass", "underground_pass", "after_underground_pass",
    "before_surface_pass", "surface_pass", "after_surface_pass", "before_sky_pass",
    "sky_pass", "after_sky_pass", "final_pass"}

errors = []


def err(where, message):
    errors.append("%s: %s" % (where, message))


def load(path):
    return json.loads(path.read_text())


def strip_comments(text):
    return re.sub(r"^\s*//.*$", "", text, flags=re.M)


def schema_validator(samples, relative):
    base = samples / "metadata" / "json_schemas"
    resources = []
    for path in base.rglob("*.json"):
        schema = load(path)
        uri = "/" + quote(path.relative_to(base).as_posix())
        resources.append((schema.get("$id", uri), Resource.from_contents(schema)))
    registry = Registry().with_resources(resources)
    return Draft7Validator(load(base / relative), registry=registry)


def check_schema(validator, path, key=None):
    data = load(path)
    for e in validator.iter_errors(data[key] if key else data):
        # The published schemas tag their oneOf branches with a const next to a
        # $ref, which draft-07 ignores, so vanilla files trip this too.
        if e.validator == "oneOf" and "is valid under each of" in e.message:
            continue
        err(path.relative_to(ROOT), "schema: %s at /%s" % (
            e.message[:160], "/".join(str(p) for p in e.absolute_path)))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    samples = Path(sys.argv[1])

    vanilla = load(samples / "metadata/vanilladata_modules/mojang-blocks.json")
    block_states = {b["name"]: {p["name"] for p in b["properties"]} for b in vanilla["data_items"]}
    state_values = {p["name"]: {v["value"] for v in p["values"]} for p in vanilla["block_properties"]}
    vanilla_biomes = set()
    vanilla_tags = set()
    for path in (samples / "behavior_pack/biomes").glob("*.json"):
        data = json.loads(strip_comments(path.read_text()))["minecraft:biome"]
        vanilla_biomes.add(data["description"]["identifier"].split(":")[1])
        vanilla_tags |= set(data["components"].get("minecraft:tags", {}).get("tags", []))
    fogs = {load(p)["minecraft:fog_settings"]["description"]["identifier"]
            for p in (samples / "resource_pack/fogs").glob("*.json")}

    # Every file must be plain JSON.
    for path in list(BP.rglob("*.json")) + list(RP.rglob("*.json")) + list(SHADER.rglob("*.json")):
        try:
            load(path)
        except ValueError as e:
            err(path.relative_to(ROOT), "not valid JSON: %s" % e)

    # Custom blocks
    block_validator = schema_validator(samples, "server/block/1.26.60/Blocks.json")
    custom_blocks = set()
    textures = load(RP / "textures/terrain_texture.json")["texture_data"]
    lang = (RP / "texts/en_US.lang").read_text()
    for path in sorted((BP / "blocks").glob("*.json")):
        check_schema(block_validator, path, "minecraft:block")
        block = load(path)["minecraft:block"]
        name = block["description"]["identifier"]
        custom_blocks.add(name)
        for inst in block["components"]["minecraft:material_instances"].values():
            entry = textures.get(inst["texture"])
            if not entry:
                err(path.name, "texture %s missing from terrain_texture.json" % inst["texture"])
            elif not (RP / (entry["textures"] + ".png")).exists():
                err(path.name, "missing image %s.png" % entry["textures"])
        if "tile.%s.name=" % name not in lang:
            err(path.name, "no display name in en_US.lang")
        loot = block["components"].get("minecraft:loot")
        if loot and not (BP / loot).exists():
            err(path.name, "loot table %s is missing" % loot)
        for perm in block.get("permutations", []):
            for state in re.findall(r"q\.block_state\('([^']+)'\)", perm["condition"]):
                traits = block["description"].get("traits", {})
                enabled = [s for t in traits.values() for s in t.get("enabled_states", [])]
                if state not in enabled:
                    err(path.name, "permutation reads state %s that no trait enables" % state)

    # Items, their icons, armour models, recipes and loot
    item_validator = schema_validator(samples, "server/item/1.26.50/ItemDocument.json")
    icons = load(RP / "textures/item_texture.json")["texture_data"]
    custom_items = set()
    for path in sorted((BP / "items").glob("*.json")):
        check_schema(item_validator, path, "minecraft:item")
        data = load(path)["minecraft:item"]
        name = data["description"]["identifier"]
        custom_items.add(name)
        if name.split(":")[1] != path.stem:
            err(path.name, "identifier does not match the file name")
        icon = data["components"]["minecraft:icon"]["textures"]["default"]
        if icon not in icons or not (RP / (icons[icon]["textures"] + ".png")).exists():
            err(path.name, "icon %s has no texture" % icon)
        if "item.%s=" % name not in lang:
            err(path.name, "no display name in en_US.lang")
        wearable = "minecraft:wearable" in data["components"]
        if wearable != (RP / "attachables" / path.name).exists():
            err(path.name, "armour and attachables do not line up")
    for path in sorted((RP / "attachables").glob("*.json")):
        desc = load(path)["minecraft:attachable"]["description"]
        if desc["identifier"] not in custom_items:
            err(path.name, "attachable for an unknown item")
        if not (RP / (desc["textures"]["default"] + ".png")).exists():
            err(path.name, "armour texture is missing")
    vanilla_items = {i["name"] for i in load(
        samples / "metadata/vanilladata_modules/mojang-items.json")["data_items"]}
    known_items = vanilla_items | set(block_states) | custom_items | custom_blocks

    def check_item(where, name):
        if name not in known_items:
            err(where, "unknown item %r" % (name,))

    recipe_ids = set()
    for path in sorted((BP / "recipes").glob("*.json")):
        data = load(path)
        kind = [k for k in data if k != "format_version"][0]
        body = data[kind]
        if body["description"]["identifier"] in recipe_ids:
            err(path.name, "duplicate recipe identifier")
        recipe_ids.add(body["description"]["identifier"])
        if kind == "minecraft:recipe_shaped":
            used = set("".join(body["pattern"])) - {" "}
            if used != set(body["key"]):
                err(path.name, "pattern letters and key do not match")
            if len({len(row) for row in body["pattern"]}) != 1:
                err(path.name, "pattern rows differ in length")
            for entry in body["key"].values():
                check_item(path.name, entry["item"])
            check_item(path.name, body["result"]["item"])
        elif kind == "minecraft:recipe_shapeless":
            for entry in body["ingredients"]:
                check_item(path.name, entry["item"])
            check_item(path.name, body["result"]["item"])
        elif kind == "minecraft:recipe_furnace":
            check_item(path.name, body["input"])
            check_item(path.name, body["output"])
        else:
            err(path.name, "unexpected recipe type %s" % kind)
        for entry in body["unlock"]:
            check_item(path.name, entry["item"])
    for path in sorted((BP / "loot_tables").rglob("*.json")):
        for pool in load(path)["pools"]:
            for entry in pool["entries"]:
                check_item(path.name, entry["name"])

    def check_block(where, spec):
        name = spec if isinstance(spec, str) else spec.get("name")
        states = {} if isinstance(spec, str) else spec.get("states", {})
        if name in custom_blocks:
            return
        if name not in block_states:
            err(where, "unknown block %r" % (name,))
            return
        for key, value in states.items():
            if key not in block_states[name]:
                err(where, "block %s has no state %r" % (name, key))
            elif value not in state_values.get(key, ()):
                err(where, "state %s=%r is out of range" % (key, value))

    def walk_blocks(where, node, key=None):
        """Every string in a block position must be a real block."""
        if isinstance(node, dict):
            if "name" in node and set(node) <= {"name", "states"}:
                check_block(where, node)
                return
            for k, v in node.items():
                if k in ("description", "distribution", "features", "places_feature",
                         "vegetation_feature", "direction", "surface", "type",
                         "identifier", "conditions"):
                    continue
                walk_blocks(where, v, k)
        elif isinstance(node, list):
            for v in node:
                walk_blocks(where, v, key)
        elif isinstance(node, str) and ":" in node:
            check_block(where, node)

    # Features
    features = {}
    for path in sorted((BP / "features").glob("*.json")):
        data = load(path)
        kinds = [k for k in data if k != "format_version"]
        if len(kinds) != 1 or kinds[0] not in FEATURE_KEYS:
            err(path.name, "unexpected feature type %s" % kinds)
            continue
        body = data[kinds[0]]
        ident = body["description"]["identifier"]
        if ident.split(":")[1] != path.stem:
            err(path.name, "identifier %s does not match the file name" % ident)
        unknown = set(body) - FEATURE_KEYS[kinds[0]]
        if unknown:
            err(path.name, "undocumented keys %s" % sorted(unknown))
        if kinds[0] == "minecraft:tree_feature":
            for part, allowed in TREE_PART_KEYS.items():
                if part in body and set(body[part]) - allowed:
                    err(path.name, "%s has undocumented keys %s" % (
                        part, sorted(set(body[part]) - allowed)))
            if not ({"trunk", "fallen_trunk", "fancy_trunk", "mega_trunk", "acacia_trunk"} & set(body)):
                err(path.name, "tree has no trunk")
        features[ident] = (path.name, kinds[0], body)
        walk_blocks(path.name, body)

    def refs(kind, body):
        if kind in ("minecraft:aggregate_feature",):
            return list(body["features"])
        if kind == "minecraft:weighted_random_feature":
            return [f for f, _ in body["features"]]
        if kind == "minecraft:scatter_feature":
            return [body["places_feature"]]
        if kind == "minecraft:vegetation_patch_feature":
            return [body["vegetation_feature"]]
        return []

    used = set()
    for ident, (name, kind, body) in features.items():
        for ref in refs(kind, body):
            used.add(ref)
            if ref not in features:
                err(name, "references missing feature %s" % ref)

    # Biomes
    biome_validator = schema_validator(samples, "server/biome/1.26.30/Biome Document.json")
    my_tags = set()
    for path in sorted((BP / "biomes").glob("*.json")):
        check_schema(biome_validator, path)
        data = load(path)["minecraft:biome"]
        comps = data["components"]
        my_tags |= set(comps["minecraft:tags"]["tags"])
        for rep in comps["minecraft:replace_biomes"]["replacements"]:
            for target in rep["targets"]:
                if not target.startswith("minecraft:") or target[10:] not in vanilla_biomes:
                    err(path.name, "replace target %r is not a vanilla biome" % target)
            if not 0 < rep["amount"] <= 1 or not 0 < rep["noise_frequency_scale"] <= 100:
                err(path.name, "replacement amount or frequency is out of range")
        walk_blocks(path.name, comps.get("minecraft:surface_builder", {}))
        walk_blocks(path.name, comps.get("minecraft:surface_material_adjustments", {}))
        walk_blocks(path.name, comps.get("minecraft:mountain_parameters", {}))
        client = RP / "biomes" / path.name.replace(".biome.json", ".client_biome.json")
        if not client.exists():
            err(path.name, "no matching client biome")
        elif load(client)["minecraft:client_biome"]["description"]["identifier"] != \
                data["description"]["identifier"]:
            err(path.name, "client biome identifier differs")

    pack_fogs = {load(p)["minecraft:fog_settings"]["description"]["identifier"]
                 for p in (RP / "fogs").glob("*.json")}
    if any(f.startswith("minecraft:") for f in pack_fogs):
        err("fogs", "packs may not define fogs in the minecraft namespace")
    client_validator = schema_validator(
        samples, "client/biome/1.21.130/Client Biome Document.json")
    for path in sorted((RP / "biomes").glob("*.json")):
        check_schema(client_validator, path)
        fog = load(path)["minecraft:client_biome"]["components"]["minecraft:fog_appearance"]
        if fog["fog_identifier"] not in fogs | pack_fogs:
            err(path.name, "unknown fog %s" % fog["fog_identifier"])

    # Feature rules
    def tags_in(node):
        if isinstance(node, dict):
            if "test" in node:
                yield node["value"]
            for v in node.values():
                yield from tags_in(v)
        elif isinstance(node, list):
            for v in node:
                yield from tags_in(v)

    for path in sorted((BP / "feature_rules").glob("*.json")):
        body = load(path)["minecraft:feature_rules"]
        desc = body["description"]
        if desc["identifier"].split(":")[1] != path.stem:
            err(path.name, "identifier does not match the file name")
        used.add(desc["places_feature"])
        if desc["places_feature"] not in features:
            err(path.name, "places missing feature %s" % desc["places_feature"])
        if body["conditions"]["placement_pass"] not in PASSES:
            err(path.name, "unknown placement pass")
        for tag in tags_in(body["conditions"]["minecraft:biome_filter"]):
            if tag not in vanilla_tags and tag not in my_tags:
                err(path.name, "biome tag %r is used by no biome" % tag)
        if not {"iterations", "x", "y", "z"} <= set(body["distribution"]):
            err(path.name, "distribution is incomplete")

    for ident, (name, _, _) in features.items():
        if ident not in used:
            err(name, "feature is never placed")

    # Lite shader: every fog must be a vanilla one, with nothing but numbers changed
    def shape(node):
        if isinstance(node, dict):
            return {k: shape(v) for k, v in node.items()}
        return type(node).__name__ if not isinstance(node, (int, float)) else "number"

    vanilla_fogs = {}
    for path in (samples / "resource_pack/fogs").glob("*.json"):
        data = load(path)
        vanilla_fogs[data["minecraft:fog_settings"]["description"]["identifier"]] = data
    shader_fogs = set()
    for path in sorted((SHADER / "fogs").glob("*.json")):
        data = load(path)
        ident = data["minecraft:fog_settings"]["description"]["identifier"]
        shader_fogs.add(ident)
        original = vanilla_fogs.get(ident.replace("ww:", "minecraft:"))
        if not ident.startswith("ww:"):
            err(path.name, "packs may not define fogs in the minecraft namespace")
        elif original is None:
            err(path.name, "no vanilla fog to compare with")
        else:
            ours, theirs = shape(data), shape(original)
            ours["minecraft:fog_settings"]["distance"].pop("air", None)
            theirs["minecraft:fog_settings"].setdefault("distance", {}).pop("air", None)
            if ours != theirs:
                err(path.name, "structure differs from the vanilla fog")
        if "air" not in data["minecraft:fog_settings"]["distance"]:
            err(path.name, "no air fog")
    for path in sorted((SHADER / "biomes").glob("*.json")):
        check_schema(client_validator, path)
        data = load(path)
        original = samples / "resource_pack/biomes" / path.name
        if not original.exists():
            original = RP / "biomes" / path.name
        theirs = load(original)
        fog = data["minecraft:client_biome"]["components"]["minecraft:fog_appearance"]
        if fog["fog_identifier"] not in shader_fogs:
            err(path.name, "points at a fog the shader does not define")
        fog["fog_identifier"] = fog["fog_identifier"].replace("ww:", "minecraft:")
        for side in (data, theirs):   # the shader may also set the sky and water opacity
            comps = side["minecraft:client_biome"]["components"]
            comps.pop("minecraft:sky_color", None)
            comps.get("minecraft:water_appearance", {}).pop("surface_opacity", None)
        if data != theirs:
            err(path.name, "differs from the original by more than its fog")
    for name, size in (("sun.png", (64, 64)), ("moon_phases.png", (256, 128))):
        from PIL import Image
        if Image.open(SHADER / "textures/environment" / name).size != size:
            err(name, "unexpected size")
    uuids = [load(f / "manifest.json")[k]["uuid"] if k == "header" else
             load(f / "manifest.json")["modules"][0]["uuid"]
             for f in (BP, RP, SHADER) for k in ("header", "modules")]
    if len(set(uuids)) != len(uuids):
        err("manifest", "duplicate uuids")

    # Manifests and the packaged add-on
    bp_manifest, rp_manifest = load(BP / "manifest.json"), load(RP / "manifest.json")
    if bp_manifest["dependencies"][0]["uuid"] != rp_manifest["header"]["uuid"]:
        err("manifest", "behavior pack does not depend on the resource pack")
    addon = ROOT / "release" / "WilderWorlds.mcaddon"
    with zipfile.ZipFile(addon) as z:
        packed = {n: z.read(n) for n in z.namelist()}
    for folder, prefix in ((BP, "WilderWorlds_BP/"), (RP, "WilderWorlds_RP/"),
                           (SHADER, "WilderWorlds_Shader/")):
        for path in folder.rglob("*"):
            if path.is_file():
                key = prefix + path.relative_to(folder).as_posix()
                if packed.pop(key, None) != path.read_bytes():
                    err("mcaddon", "%s is missing or stale" % key)
    for extra in packed:
        err("mcaddon", "unexpected file %s" % extra)

    if errors:
        print("\n".join(errors))
        sys.exit("%d problem(s)" % len(errors))
    print("ok: %d biomes, %d features, %d rules, %d blocks, %d items, %d recipes, add-on in sync" % (
        len(list((BP / "biomes").glob("*.json"))), len(features),
        len(list((BP / "feature_rules").glob("*.json"))), len(custom_blocks),
        len(custom_items), len(recipe_ids)))


if __name__ == "__main__":
    main()
