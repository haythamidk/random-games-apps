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
        "may_grow_through", "trunk", "fallen_trunk", "fancy_trunk", "spruce_canopy",
        "pine_canopy", "canopy", "fancy_canopy"},
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
    "minecraft:multipart_block_column_feature": {
        "description", "tip_block", "frustum_block", "middle_block", "base_block",
        "height_range", "weighted_heights", "direction", "may_place_on", "may_replace"},
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
            if not ({"trunk", "fallen_trunk", "fancy_trunk"} & set(body)):
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
                if target not in vanilla_biomes:
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

    client_validator = schema_validator(
        samples, "client/biome/1.21.130/Client Biome Document.json")
    for path in sorted((RP / "biomes").glob("*.json")):
        check_schema(client_validator, path)
        fog = load(path)["minecraft:client_biome"]["components"]["minecraft:fog_appearance"]
        if fog["fog_identifier"] not in fogs:
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

    for path in sorted((SHADER / "fogs").glob("*.json")):
        original = samples / "resource_pack/fogs" / path.name
        if not original.exists():
            err(path.name, "not a vanilla fog file")
        elif shape(load(path)) != shape(load(original)):
            err(path.name, "structure differs from the vanilla fog")
        elif load(path)["minecraft:fog_settings"]["description"] != \
                load(original)["minecraft:fog_settings"]["description"]:
            err(path.name, "fog identifier changed")
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
    print("ok: %d biomes, %d features, %d rules, %d blocks, add-on in sync" % (
        len(list((BP / "biomes").glob("*.json"))), len(features),
        len(list((BP / "feature_rules").glob("*.json"))), len(custom_blocks)))


if __name__ == "__main__":
    main()
