#!/usr/bin/env python3
"""Validate the bundled ItemsAdder content pack and plugin model-data mapping."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
PACK = PROJECT / "itemsadder-content" / "mahjongcraft"
CONFIG = PACK / "configs" / "items.yml"
RESOURCEPACK = PACK / "resourcepack"
MODEL_ROOT = RESOURCEPACK / "assets" / "mahjongcraft" / "models" / "item"
TEXTURE_ROOT = RESOURCEPACK / "assets" / "mahjongcraft" / "textures"
DISPLAY_SOURCE = (
    PROJECT
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "mahjongplay"
    / "display"
    / "MahjongTileDisplay.kt"
)

EXPECTED_ITEMS = [
    *(f"m{number}" for number in range(1, 10)),
    *(f"p{number}" for number in range(1, 10)),
    *(f"s{number}" for number in range(1, 10)),
    "east",
    "south",
    "west",
    "north",
    "white_dragon",
    "green_dragon",
    "red_dragon",
    *(f"flower_{number}" for number in range(1, 9)),
    "unknown",
]
MODEL_DATA_BASE = 900001


def fail(message: str) -> None:
    raise ValueError(message)


def parse_items_config() -> dict[str, dict[str, str | int]]:
    text = CONFIG.read_text(encoding="utf-8")
    if not re.search(r"(?m)^info:\s*\n  namespace: mahjongcraft\s*$", text):
        fail("items.yml must declare the mahjongcraft namespace")

    items: dict[str, dict[str, str | int]] = {}
    current: str | None = None
    in_items = False
    for line in text.splitlines():
        if line == "items:":
            in_items = True
            continue
        if not in_items:
            continue

        item_match = re.fullmatch(r"  ([a-z0-9_]+):", line)
        if item_match:
            current = item_match.group(1)
            items[current] = {}
            continue
        if current is None:
            continue

        material_match = re.fullmatch(r"      material: ([A-Z_]+)", line)
        model_id_match = re.fullmatch(r"      model_id: (\d+)", line)
        model_path_match = re.fullmatch(r"      model_path: ([a-z0-9_./-]+)", line)
        if material_match:
            items[current]["material"] = material_match.group(1)
        elif model_id_match:
            items[current]["model_id"] = int(model_id_match.group(1))
        elif model_path_match:
            items[current]["model_path"] = model_path_match.group(1)
    return items


def validate_items(items: dict[str, dict[str, str | int]]) -> None:
    if list(items) != EXPECTED_ITEMS:
        fail("ItemsAdder item order does not match MahjongTile enum order")

    for offset, item_name in enumerate(EXPECTED_ITEMS):
        item = items[item_name]
        expected_id = MODEL_DATA_BASE + offset
        expected_model = f"item/mahjong_tile/mahjong_tile_{item_name}"
        if item.get("material") != "PAPER":
            fail(f"{item_name}: material must be PAPER")
        if item.get("model_id") != expected_id:
            fail(f"{item_name}: expected model_id {expected_id}")
        if item.get("model_path") != expected_model:
            fail(f"{item_name}: expected model_path {expected_model}")

        model_file = MODEL_ROOT / f"mahjong_tile/mahjong_tile_{item_name}.json"
        if not model_file.is_file():
            fail(f"{item_name}: missing model {model_file.relative_to(PROJECT)}")


def texture_file(texture_reference: str) -> Path:
    namespace, path = texture_reference.split(":", 1)
    return RESOURCEPACK / "assets" / namespace / "textures" / f"{path}.png"


def validate_models() -> None:
    base_model = json.loads((MODEL_ROOT / "mahjong_tile_base.json").read_text(encoding="utf-8"))
    if "textures" in base_model:
        fail("mahjong_tile_base.json must not declare textures; this can mix atlases")

    model_dir = MODEL_ROOT / "mahjong_tile"
    model_files = sorted(model_dir.glob("*.json"))
    if len(model_files) != 46:
        fail(f"expected 46 child models, found {len(model_files)}")

    for model_file in model_files:
        model = json.loads(model_file.read_text(encoding="utf-8"))
        textures = model.get("textures", {})
        if set(textures) != {"0", "1", "particle"}:
            fail(f"{model_file.name}: textures must contain 0, 1 and particle")
        if textures["1"] != "mahjongcraft:item/mahjong_tile/mahjong_tile_cover":
            fail(f"{model_file.name}: invalid cover texture")
        if textures["particle"] != "#1":
            fail(f"{model_file.name}: particle must reference #1")
        for key in ("0", "1"):
            path = texture_file(textures[key])
            if not path.is_file():
                fail(f"{model_file.name}: missing texture {path.relative_to(PROJECT)}")


def validate_layout_and_plugin() -> None:
    forbidden = [
        PACK / "pack.mcmeta",
        RESOURCEPACK / "pack.mcmeta",
        RESOURCEPACK / "assets" / "minecraft" / "items" / "paper.json",
        RESOURCEPACK / "assets" / "minecraft" / "models" / "item" / "paper.json",
    ]
    for path in forbidden:
        if path.exists():
            fail(f"forbidden global resource-pack file: {path.relative_to(PROJECT)}")

    source = DISPLAY_SOURCE.read_text(encoding="utf-8")
    match = re.search(r"MODEL_DATA_BASE\s*=\s*(\d+)", source)
    if not match or int(match.group(1)) != MODEL_DATA_BASE:
        fail(f"MahjongTileDisplay MODEL_DATA_BASE must be {MODEL_DATA_BASE}")


def main() -> int:
    try:
        items = parse_items_config()
        validate_items(items)
        validate_models()
        validate_layout_and_plugin()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ItemsAdder validation failed: {error}", file=sys.stderr)
        return 1

    print(
        "ItemsAdder content valid: "
        f"{len(items)} items, IDs {MODEL_DATA_BASE}-{MODEL_DATA_BASE + len(items) - 1}, "
        "46 single-atlas child models"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
