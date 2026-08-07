# Third-Party Notices

## Riichi Mahjong table model and texture

The Mahjong table model and texture included in this repository are derived from:

- Project: `caxapexac/MinecraftRiichiMahjong`
- Source commit: `a64ee35bebe64981f7982d38032b527681ec37e0`
- Source model: `common/src/main/resources/assets/riichi_mahjong/models/block/mahjong_table.json`
- Source texture: `common/src/main/resources/assets/riichi_mahjong/textures/block/mahjong_table.png`
- Source Blockbench project: `blockbench/mahjong-table.bbmodel`
- Author: `caxapexac`
- License: MIT

The source project declares `MIT` in both:

- `fabric/src/main/resources/fabric.mod.json`
- `neoforge/src/main/resources/META-INF/neoforge.mods.toml`

Changes made for TaiwaneseMahjong:

- Changed the texture namespace from `riichi_mahjong` to `mahjongcraft`.
- Reused the block geometry as an ItemsAdder item model.
- Registered the table as `PAPER` Custom Model Data `900044`.
- Displayed the model through a Paper `ItemDisplay`.
- Adjusted runtime scale and vertical origin for the existing 3×3 table footprint.

Files included here:

- `itemsadder-content/mahjongcraft/resourcepack/assets/mahjongcraft/models/item/mahjong_table.json`
- `itemsadder-content/mahjongcraft/resourcepack/assets/mahjongcraft/textures/item/mahjong_table.png`

### MIT License

Copyright (c) caxapexac

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Japan Street v3 small chair model and texture

The chair used for Mahjong table seats was imported from the server's existing
ItemsAdder content pack:

- Source item: `elitecreatures:japan_street_v3_small_chair`
- Source model: `plugins/ItemsAdder/contents/elitecreatures_japan_streets/resourcepack/assets/elitecreatures/models/japan_street_v3/small_chair.json`
- Source texture: `plugins/ItemsAdder/contents/elitecreatures_japan_streets/resourcepack/assets/elitecreatures/textures/japan_street_v3/smallchairtexture.png`

The project copies the geometry and texture into its own `mahjongcraft`
namespace, registers `mahjongcraft:mahjong_chair` as `PAPER` Custom Model Data
`900045`, and removes the source namespace dependency from the model texture
reference. The imported source folder does not include author or license
metadata; redistribution rights for this asset should be confirmed separately.

Project files:

- `itemsadder-content/mahjongcraft/configs/furniture.yml`
- `itemsadder-content/mahjongcraft/resourcepack/assets/mahjongcraft/models/item/mahjong_chair.json`
- `itemsadder-content/mahjongcraft/resourcepack/assets/mahjongcraft/textures/item/mahjong_chair.png`
