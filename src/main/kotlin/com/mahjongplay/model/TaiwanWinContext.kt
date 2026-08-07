package com.mahjongplay.model

/**
 * 一次台灣麻將和牌發生時的牌局情境。
 *
 * 牌型本身可以只靠手牌判斷，但莊家、連莊、海底、槓上開花、搶槓、
 * 天胡／地胡／人胡等台數必須由牌局流程提供，因此集中在這個 context。
 */
data class TaiwanWinContext(
    /** 莊家目前已連莊幾次；僅在和牌者為莊家時由計分器採用。 */
    val dealerRepeat: Int = 0,
    /** 和牌前實際只聽一種牌。 */
    val isSingleWait: Boolean = false,
    /** 活牌牆最後一張造成的和牌。 */
    val isLastLiveTile: Boolean = false,
    /** 槓後補牌自摸。 */
    val isKongReplacement: Boolean = false,
    /** 榮和他人的加槓牌。 */
    val isRobbingKong: Boolean = false,
    /** 莊家起手即和，且牌局尚未被任何副露／槓打斷。 */
    val isHeavenlyHand: Boolean = false,
    /** 閒家第一次摸牌即自摸，且此前沒有副露／槓。 */
    val isEarthlyHand: Boolean = false,
    /** 閒家尚未打過牌前，於第一巡榮和，且此前沒有副露／槓。 */
    val isHumanHand: Boolean = false,
)
