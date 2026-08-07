package com.mahjongplay.model

/** 副露。台麻的槓仍算一組面子，但牌面會保留四張。 */
class Fuuro(
    val type: MeldType,
    val tiles: List<MahjongTile>,
    val claimTarget: ClaimTarget,
    val claimTile: MahjongTile,
    val isOpen: Boolean = claimTarget != ClaimTarget.SELF,
    val isAddedKong: Boolean = false
) {
    val isKong: Boolean
        get() = type == MeldType.KONG

    val isSequence: Boolean
        get() = type == MeldType.SEQUENCE

    val isTriplet: Boolean
        get() = type == MeldType.TRIPLET
}

enum class MeldType {
    SEQUENCE,
    TRIPLET,
    KONG
}
