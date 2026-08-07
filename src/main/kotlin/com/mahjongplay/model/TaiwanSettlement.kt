package com.mahjongplay.model

/** 一個台麻計分項目。 */
data class TaiItem(
    val name: String,
    val tai: Int
)

/** 台灣十六張麻將的和牌結算資料。 */
data class TaiwanSettlement(
    val displayName: String,
    val uuid: String,
    val isRealPlayer: Boolean,
    val botCode: Int = MahjongTile.UNKNOWN.code,
    val taiList: List<TaiItem>,
    val flowerCount: Int = 0,
    val winningTile: MahjongTile,
    val hands: List<MahjongTile>,
    val fuuroList: List<Pair<Boolean, List<MahjongTile>>>,
    val tai: Int,
    val score: Int,
    val isTsumo: Boolean
) {
    val isWin: Boolean
        get() = taiList.isNotEmpty() || tai > 0

    companion object {
        val NO_TAI = TaiwanSettlement(
            displayName = "",
            uuid = "",
            isRealPlayer = false,
            taiList = emptyList(),
            winningTile = MahjongTile.UNKNOWN,
            hands = emptyList(),
            fuuroList = emptyList(),
            tai = 0,
            score = 0,
            isTsumo = false
        )
    }
}
