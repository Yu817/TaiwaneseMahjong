package com.mahjongplay.game

import com.mahjongplay.model.MahjongTile

data class SeatWindDrawStartEvent(
    val dice: OpeningDice,
    val starterSeatIndex: Int,
    val windTiles: List<MahjongTile>,
)

data class SeatWindTurnPromptEvent(
    val picker: MahjongPlayerBase,
    val availableIndices: List<Int>,
)

data class SeatWindTilePickedEvent(
    val picker: MahjongPlayerBase,
    val tileIndex: Int,
    val windTile: MahjongTile,
)

data class SeatWindDrawCompleteEvent(
    val assignments: Map<MahjongTile, MahjongPlayerBase>,
    val newSeatOrder: List<MahjongPlayerBase>,
)
