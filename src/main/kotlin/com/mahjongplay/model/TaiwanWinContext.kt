package com.mahjongplay.model

/**
 * 一次台灣麻將和牌發生時的牌局情境。
 *
 * 牌型本身可以只靠手牌判斷，但莊家、連莊、海底、補牌自摸、搶槓、
 * 天胡／地胡／人胡等台數必須由牌局流程提供。
 */
data class TaiwanWinContext(
    val dealerRepeat: Int = 0,
    val isSingleWait: Boolean = false,
    val isLastLiveTile: Boolean = false,
    /** 槓後補牌自摸。 */
    val isKongReplacement: Boolean = false,
    /** 摸花後由牌尾補牌自摸。 */
    val isFlowerReplacement: Boolean = false,
    val isRobbingKong: Boolean = false,
    val isHeavenlyHand: Boolean = false,
    val isEarthlyHand: Boolean = false,
    val isHumanHand: Boolean = false,
)
