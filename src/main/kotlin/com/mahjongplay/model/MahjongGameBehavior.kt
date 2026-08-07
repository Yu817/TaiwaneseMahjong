package com.mahjongplay.model

import com.mahjongplay.util.TextFormatting
import net.kyori.adventure.text.Component

/** 台灣十六張麻將的遊戲行為。 */
enum class MahjongGameBehavior(
    private val displayName: String
) : TextFormatting {
    CHII("吃"),
    PON_OR_CHII("碰／吃"),
    PON("碰"),
    KAN("槓"),
    MINKAN("明槓"),
    ANKAN("暗槓"),
    ANKAN_OR_KAKAN("暗槓／加槓"),
    KAKAN("加槓"),
    RON("榮和"),
    TSUMO("自摸"),
    EXHAUSTIVE_DRAW("流局"),
    DISCARD("出牌"),
    SKIP("跳過"),
    GAME_START("開始對局"),
    GAME_OVER("對局結束"),
    SCORE_SETTLEMENT("分數結算"),
    TAI_SETTLEMENT("台數結算"),
    COUNTDOWN_TIME("倒數"),
    AUTO_ARRANGE("整理手牌"),
    MACHI("聽牌");

    override fun toText(): Component = Component.text(displayName)
}
