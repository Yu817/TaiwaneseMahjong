#!/usr/bin/env python3
"""Validate the bundled ItemsAdder content pack and plugin model-data mapping."""

from __future__ import annotations

import json
import re
import struct
import sys
import zlib
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
PACK = PROJECT / "itemsadder-content" / "mahjongcraft"
TILE_CONFIG = PACK / "configs" / "items.yml"
TABLE_CONFIG = PACK / "configs" / "table.yml"
RESOURCEPACK = PACK / "resourcepack"
MODEL_ROOT = RESOURCEPACK / "assets" / "mahjongcraft" / "models" / "item"
TEXTURE_ROOT = RESOURCEPACK / "assets" / "mahjongcraft" / "textures"
MODEL_DATA_SOURCE = PROJECT / "src" / "main" / "kotlin" / "com" / "mahjongplay" / "display" / "MahjongModelData.kt"
TILE_DISPLAY_SOURCE = PROJECT / "src" / "main" / "kotlin" / "com" / "mahjongplay" / "display" / "MahjongTileDisplay.kt"

EXPECTED_TILE_ITEMS = [
    *(f"m{number}" for number in range(1, 10)),
    *(f"p{number}" for number in range(1, 10)),
    *(f"s{number}" for number in range(1, 10)),
    "east", "south", "west", "north",
    "white_dragon", "green_dragon", "red_dragon",
    *(f"flower_{number}" for number in range(1, 9)),
    "unknown",
]
TILE_MODEL_DATA_BASE = 900001
TABLE_MODEL_DATA = 900044


def fail(message: str) -> None:
    raise ValueError(message)


def parse_items_config(path: Path) -> dict[str, dict[str, str | int]]:
    text = path.read_text(encoding="utf-8")
    if not re.search(r"(?m)^info:\s*\n  namespace: mahjongcraft\s*$", text):
        fail(f"{path.relative_to(PROJECT)} must declare the mahjongcraft namespace")

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


def validate_tile_items(items: dict[str, dict[str, str | int]]) -> None:
    if list(items) != EXPECTED_TILE_ITEMS:
        fail("ItemsAdder tile item order does not match MahjongTile enum order")

    for offset, item_name in enumerate(EXPECTED_TILE_ITEMS):
        item = items[item_name]
        expected_id = TILE_MODEL_DATA_BASE + offset
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


def validate_table_item(items: dict[str, dict[str, str | int]]) -> None:
    if list(items) != ["mahjong_table"]:
        fail("table.yml must contain only the mahjong_table item")

    item = items["mahjong_table"]
    if item.get("material") != "PAPER":
        fail("mahjong_table: material must be PAPER")
    if item.get("model_id") != TABLE_MODEL_DATA:
        fail(f"mahjong_table: expected model_id {TABLE_MODEL_DATA}")
    if item.get("model_path") != "item/mahjong_table":
        fail("mahjong_table: model_path must be item/mahjong_table")


def texture_file(texture_reference: str) -> Path:
    namespace, path = texture_reference.split(":", 1)
    return RESOURCEPACK / "assets" / namespace / "textures" / f"{path}.png"


def validate_tile_models() -> None:
    base_model = json.loads((MODEL_ROOT / "mahjong_tile_base.json").read_text(encoding="utf-8"))
    if "textures" in base_model:
        fail("mahjong_tile_base.json must not declare textures; this can mix atlases")

    model_dir = MODEL_ROOT / "mahjong_tile"
    model_files = sorted(model_dir.glob("*.json"))
    if len(model_files) != 46:
        fail(f"expected 46 tile child models, found {len(model_files)}")

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


def validate_png(path: Path) -> None:
    data = path.read_bytes()
    signature = b"\x89PNG\r\n\x1a\n"
    if not data.startswith(signature):
        fail(f"{path.relative_to(PROJECT)} is not a PNG file")

    offset = len(signature)
    saw_iend = False
    while offset < len(data):
        if offset + 12 > len(data):
            fail(f"{path.relative_to(PROJECT)} has a truncated PNG chunk header")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        chunk_type = data[offset + 4:offset + 8]
        chunk_end = offset + 12 + length
        if chunk_end > len(data):
            fail(f"{path.relative_to(PROJECT)} has a truncated PNG chunk")
        chunk_data = data[offset + 8:offset + 8 + length]
        stored_crc = struct.unpack(">I", data[offset + 8 + length:chunk_end])[0]
        calculated_crc = zlib.crc32(chunk_type)
        calculated_crc = zlib.crc32(chunk_data, calculated_crc) & 0xFFFFFFFF
        if stored_crc != calculated_crc:
            name = chunk_type.decode("ascii", errors="replace")
            fail(f"{path.relative_to(PROJECT)} has an invalid {name} chunk CRC")
        offset = chunk_end
        if chunk_type == b"IEND":
            saw_iend = True
            break

    if not saw_iend or offset != len(data):
        fail(f"{path.relative_to(PROJECT)} has an invalid PNG ending")


def validate_table_model() -> None:
    model_file = MODEL_ROOT / "mahjong_table.json"
    texture_path = TEXTURE_ROOT / "item" / "mahjong_table.png"
    if not model_file.is_file():
        fail(f"missing table model {model_file.relative_to(PROJECT)}")
    if not texture_path.is_file():
        fail(f"missing table texture {texture_path.relative_to(PROJECT)}")
    validate_png(texture_path)

    raw = model_file.read_text(encoding="utf-8")
    if "riichi_mahjong:" in raw or "riichi_mahjong_forge:" in raw:
        fail("mahjong_table.json contains a source-mod namespace")

    model = json.loads(raw)
    textures = model.get("textures", {})
    expected_texture = "mahjongcraft:item/mahjong_table"
    if textures.get("0") != expected_texture:
        fail("mahjong_table.json texture 0 must use mahjongcraft:item/mahjong_table")
    if textures.get("particle") != expected_texture:
        fail("mahjong_table.json particle texture must use mahjongcraft:item/mahjong_table")
    if len(model.get("elements", [])) != 7:
        fail("mahjong_table.json must retain the seven source model elements")


def validate_model_data_mapping(tile_items: dict[str, dict[str, str | int]], table_items: dict[str, dict[str, str | int]]) -> None:
    ids = [
        *(int(item["model_id"]) for item in tile_items.values()),
        *(int(item["model_id"]) for item in table_items.values()),
    ]
    if len(ids) != len(set(ids)):
        fail("ItemsAdder model IDs must be unique")

    source = MODEL_DATA_SOURCE.read_text(encoding="utf-8")
    tile_match = re.search(r"const val TILE_BASE\s*=\s*(\d+)", source)
    table_match = re.search(r"const val TABLE\s*=\s*(\d+)", source)
    if not tile_match or int(tile_match.group(1)) != TILE_MODEL_DATA_BASE:
        fail(f"MahjongModelData.TILE_BASE must be {TILE_MODEL_DATA_BASE}")
    if not table_match or int(table_match.group(1)) != TABLE_MODEL_DATA:
        fail(f"MahjongModelData.TABLE must be {TABLE_MODEL_DATA}")

    tile_display = TILE_DISPLAY_SOURCE.read_text(encoding="utf-8")
    if "MahjongModelData.TILE_BASE + tile.code" not in tile_display:
        fail("MahjongTileDisplay must derive tile IDs from MahjongModelData.TILE_BASE")


def validate_layout() -> None:
    forbidden = [
        PACK / "pack.mcmeta",
        RESOURCEPACK / "pack.mcmeta",
        RESOURCEPACK / "assets" / "minecraft" / "items" / "paper.json",
        RESOURCEPACK / "assets" / "minecraft" / "models" / "item" / "paper.json",
    ]
    for path in forbidden:
        if path.exists():
            fail(f"forbidden global resource-pack file: {path.relative_to(PROJECT)}")


def main() -> int:
    try:
        tile_items = parse_items_config(TILE_CONFIG)
        table_items = parse_items_config(TABLE_CONFIG)
        validate_tile_items(tile_items)
        validate_table_item(table_items)
        validate_tile_models()
        validate_table_model()
        validate_model_data_mapping(tile_items, table_items)
        validate_layout()
    except (OSError, KeyError, ValueError, json.JSONDecodeError) as error:
        print(f"ItemsAdder validation failed: {error}", file=sys.stderr)
        return 1

    print(
        "ItemsAdder content valid: "
        f"{len(tile_items)} tiles using IDs {TILE_MODEL_DATA_BASE}-{TILE_MODEL_DATA_BASE + len(tile_items) - 1}, "
        f"1 table using ID {TABLE_MODEL_DATA}, "
        "46 tile child models and 1 table model"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
