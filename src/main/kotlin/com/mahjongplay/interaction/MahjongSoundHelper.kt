package com.mahjongplay.interaction

import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

/**
 * 台灣麻將音效管理器：為摸牌、出牌、鳴牌、結算與倒數提供逼真生動的原生音效反饋。
 */
object MahjongSoundHelper {

    /** 摸牌入手的清脆滑動聲 */
    fun playTileDraw(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON, SoundCategory.PLAYERS, 0.65f, 1.6f)
        }
    }

    /** 捨牌／打牌敲桌聲（厚實骨牌叩桌聲） */
    fun playTileDiscard(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_BONE_BLOCK_PLACE, SoundCategory.PLAYERS, 0.85f, 1.3f)
            p.playSound(location, Sound.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 0.65f, 1.45f)
        }
    }

    /** 點選手牌預選聲（手牌立起） */
    fun playTileSelect(player: Player) {
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.45f, 1.8f)
    }

    /** 補花好彩頭鈴聲 */
    fun playFlower(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.85f, 1.4f)
            p.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.55f, 1.6f)
        }
    }

    /** 「碰！」乾脆利落的金屬碰撞截牌聲 */
    fun playPon(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.35f, 1.95f)
            p.playSound(location, Sound.BLOCK_WOOD_BREAK, SoundCategory.PLAYERS, 0.75f, 1.25f)
            p.playSound(location, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.55f, 1.2f)
        }
    }

    /** 「吃！」順暢俐落的吃牌音 */
    fun playChii(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.65f, 1.4f)
            p.playSound(location, Sound.BLOCK_BAMBOO_WOOD_PLACE, SoundCategory.PLAYERS, 0.75f, 1.5f)
        }
    }

    /** 「槓！」四張骨牌推倒合併的強烈震撼聲 */
    fun playKan(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_BONE_BLOCK_BREAK, SoundCategory.PLAYERS, 0.9f, 1.15f)
            p.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.55f, 1.4f)
            p.playSound(location, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.45f, 1.8f)
        }
    }

    /** 「跳過」按鍵聲 */
    fun playSkip(player: Player) {
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.5f, 0.95f)
    }

    /** 「自摸！」獲勝爆發震撼音效（煙火爆裂+升級） */
    fun playTsumo(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.0f, 1.1f)
            p.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.9f, 1.2f)
            p.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.85f, 1.5f)
        }
    }

    /** 「胡牌！」抓銃警鐘長鳴+雷鳴 */
    fun playRon(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.0f)
            p.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.55f, 1.9f)
            p.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.75f, 1.2f)
        }
    }

    /** 籌碼結算金幣落袋叮噹聲 */
    fun playScoreSettlement(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.ITEM_ARMOR_EQUIP_GOLD, SoundCategory.PLAYERS, 0.8f, 1.25f)
            p.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.5f)
        }
    }

    /** 「流局／荒莊」落空音 */
    fun playDraw(location: Location, players: Collection<Player>) {
        players.forEach { p ->
            p.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.75f, 1.15f)
            p.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.85f, 0.7f)
        }
    }

    /** 輪到你出牌提醒音（清脆雙音和弦） */
    fun playTurnPrompt(location: Location, player: Player) {
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.65f, 1.6f)
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.55f, 1.5f)
    }

    /** 最後 3 秒倒數計時急促滴答聲 */
    fun playCountdownTick(player: Player, remainingSeconds: Int) {
        val pitch = when (remainingSeconds) {
            1 -> 1.8f
            2 -> 1.5f
            else -> 1.2f
        }
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.65f, pitch)
    }
}
