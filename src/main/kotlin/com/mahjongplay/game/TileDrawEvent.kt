package com.mahjongplay.game

import com.mahjongplay.model.MahjongTile

enum class DrawSource { LIVE_HEAD, SUPPLEMENT_TAIL }

enum class DrawReason {
    INITIAL_DEAL,
    NORMAL_TURN,
    FLOWER_REPLACEMENT,
    KONG_REPLACEMENT,
}

/**
 * A rule-authoritative draw.  The renderer may animate this event, but never
 * decides the tile, source, or any wall mutation itself.
 */
data class TileDrawEvent(
    val sequence: Long,
    val playerUUID: String,
    val tile: MahjongTile,
    val source: DrawSource,
    val reason: DrawReason,
    val handSizeBeforeDraw: Int,
    val liveWallSize: Int,
    val supplementWallSize: Int,
    val supplementRefilledFromLiveWall: Boolean,
    val animationMillis: Long,
)

data class WallInitializedEvent(
    val tileCount: Int,
    val liveWallSize: Int,
    val supplementWallSize: Int,
    val openingDice: OpeningDiceEvent? = null,
)
