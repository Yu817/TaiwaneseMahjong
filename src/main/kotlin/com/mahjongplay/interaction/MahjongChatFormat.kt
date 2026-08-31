package com.mahjongplay.interaction

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.ScoreItem
import com.mahjongplay.model.TaiwanSettlement
import com.mahjongplay.table.ChairInteractionResult
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * 台灣麻將優雅訊息格式化器：提供全服統一、精美且現代化的聊天室廣播、提示前綴與結算戰報。
 */
object MahjongChatFormat {

    val DIVIDER: Component = Component.text("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━§r")

    val PREFIX: Component = Component.text("§8[§6🀄 §e§l台灣麻將§8] §f")
    val SUCCESS_PREFIX: Component = Component.text("§8[§a✓ §2§l麻將系統§8] §a")
    val WARN_PREFIX: Component = Component.text("§8[§e⚠ §6§l麻將提示§8] §e")
    val ERROR_PREFIX: Component = Component.text("§8[§c✕ §4§l麻將提示§8] §c")
    val BOT_PREFIX: Component = Component.text("§8[§b🤖 §3§l代打模式§8] §b")
    val ECO_PREFIX: Component = Component.text("§8[§e💰 §6§l麻將金流§8] §f")
    val ALERT_PREFIX: Component = Component.text("§8[§c🚨 §4§l雀壇快訊§8] §f")

    // ==========================================
    // 快速提示組件
    // ==========================================

    fun info(text: String): Component =
        PREFIX.append(Component.text(text, NamedTextColor.WHITE))

    fun success(text: String): Component =
        SUCCESS_PREFIX.append(Component.text(text, NamedTextColor.GREEN))

    fun warn(text: String): Component =
        WARN_PREFIX.append(Component.text(text, NamedTextColor.YELLOW))

    fun error(text: String): Component =
        ERROR_PREFIX.append(Component.text(text, NamedTextColor.RED))

    fun eco(text: String): Component =
        ECO_PREFIX.append(Component.text(text, NamedTextColor.GOLD))

    fun bot(enabled: Boolean): Component =
        if (enabled) {
            Component.text("§8[§b🤖 §3§l代打模式§8] §e已開啟代打模式！ §7(再按一次 §b§l【F 鍵】§7 可解除代打)")
        } else {
            Component.text("§8[§6🀄 §b§l台灣麻將§8] §a已解除代打，恢復手動操作！ §7(按 §b§l【F 鍵】§7 可隨時重新代打)")
        }

    fun ready(isReady: Boolean): Component =
        if (isReady) {
            Component.text("§8[§a✓ §2§l準備就緒§8] §a你已準備！等待其他玩家中…… §7(再次點擊可取消)")
        } else {
            Component.text("§8[§e⚠ §6§l取消準備§8] §e你已取消準備狀態。 §7(點擊準備可重新就緒)")
        }

    fun chairResult(result: ChairInteractionResult): Component {
        val (icon, title, msg, color) = when (result) {
            ChairInteractionResult.SEATED -> Quad("§a✓", "§2§l入座成功", "已坐上麻將椅 §7(按 §e§lShift§7 可隨時起身走動)", "§a")
            ChairInteractionResult.OCCUPIED -> Quad("§c✕", "§4§l入座失敗", "這張椅子已經有雀友入座了。", "§c")
            ChairInteractionResult.OTHER_TABLE -> Quad("§c✕", "§4§l入座失敗", "你目前已在其他麻將桌對局中。", "§c")
            ChairInteractionResult.TABLE_FULL -> Quad("§c✕", "§4§l入座失敗", "這張麻將桌人數已額滿。", "§c")
            ChairInteractionResult.GAME_IN_PROGRESS -> Quad("§e⚠", "§6§l牌局進行中", "本桌牌局已開打，非參賽雀友無法中途加入。", "§e")
        }
        return Component.text("§8[$icon $title§8] $color$msg")
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    // ==========================================
    // 對局廣播卡片
    // ==========================================

    fun gameStart(isRealMoney: Boolean): List<Component> {
        val modeLine = if (isRealMoney) {
            Component.text("  💰 §6賽制模式：§e§l【真金對戰局】 §7（結算轉移伺服器遊戲幣）")
        } else {
            Component.text("  🎮 §b賽制模式：§a§l【休閒娛樂局】 §7（純積分對局，不扣除遊戲幣）")
        }
        return listOf(
            Component.text("§8§m━━━━━━━━━━§r §6🀄 §e§l牌 局 開 打 §6🀄 §8§m━━━━━━━━━━"),
            Component.text("  🀄 §6§l【台灣十六張麻將】 §e牌局正式開始！"),
            modeLine,
            Component.text("  💡 §7操作提示：隨時按下 §b§l【F 鍵】§7 可切換 §e🤖 代打模式"),
            DIVIDER,
        )
    }

    fun roundStart(roundName: String, honba: Int): Component {
        val honbaText = if (honba > 0) " §8(§e連莊 $honba§8)" else ""
        return Component.text("§8[§6🀄 §b§l台灣麻將§8] §f局數開打 ➜ §6§l$roundName$honbaText")
    }

    fun flowerDrawn(player: String, flower: MahjongTile): Component =
        Component.text("§8[§d🌸 補花§8] §b§l$player §f摸進花牌 §d§l【 ${flower.displayName} 】 §7➜ 牌尾即時補牌")

    fun chii(player: String, from: String, tile: MahjongTile): Component =
        Component.text("§8[§a🥢 吃牌§8] §b§l$player §f吃了 §7$from §f打出的 §a§l【 ${tile.displayName} 】")

    fun pon(player: String, from: String, tile: MahjongTile): Component =
        Component.text("§8[§e💥 碰牌§8] §b§l$player §f碰了 §7$from §f打出的 §e§l【 ${tile.displayName} 】")

    fun kan(player: String, kanType: String, tile: MahjongTile): Component {
        val (icon, label, color) = when (kanType) {
            "ankan" -> Triple("🔒", "暗槓", "§d")
            "kakan" -> Triple("➕", "加槓", "§b")
            else -> Triple("🀄", "明槓", "§6")
        }
        return Component.text("§8[$color$icon $label§8] §b§l$player §f槓牌 $color§l【 ${tile.displayName} 】 §7➜ 至牌尾摸補牌")
    }

    fun ankanHidden(player: String): Component =
        Component.text("§8[§d🔒 暗槓§8] §b§l$player §f宣告了 §d§l暗槓 §7➜ 至牌尾摸補牌")

    fun tsumoCard(player: MahjongPlayerBase, settlement: TaiwanSettlement): List<Component> {
        val taiDetails = if (settlement.taiList.isNotEmpty()) {
            settlement.taiList.joinToString("  §7• §f") { "§f${it.name} §8(§e${it.tai}台§8)" }
        } else {
            "§f底台自摸"
        }
        val flowers = if (settlement.flowerCount > 0) "  §7• §d花牌 §8(§d${settlement.flowerCount}張§8)" else ""

        return listOf(
            Component.text("§8§m━━━━━━━━━━§r §6🀄 §e§l自 摸 喜 報 §6🀄 §8§m━━━━━━━━━━"),
            Component.text("  🏆 §6§l【自摸和牌】 §b§l${player.displayName} §e喜獲自摸！ §7(摸進：§6§l【 ${settlement.winningTile.displayName} 】§7)"),
            Component.text("  📜 §e役種明細： §7• $taiDetails$flowers"),
            Component.text("  💰 §6結算統計： §7合計 §a§l${settlement.tai} 台 §7︳三家各付 §a${settlement.score} §7︳總計收穫 §e§l+${settlement.score * 3} 積分"),
            DIVIDER,
        )
    }

    fun ronCard(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, settlements: List<TaiwanSettlement>): List<Component> {
        val list = mutableListOf<Component>()
        list += Component.text("§8§m━━━━━━━━━━§r §c🀄 §e§l榮 和 捷 報 §c🀄 §8§m━━━━━━━━━━")
        winners.forEachIndexed { i, winner ->
            val s = settlements.getOrElse(i) { settlements.first() }
            val taiDetails = if (s.taiList.isNotEmpty()) {
                s.taiList.joinToString("  §7• §f") { "§f${it.name} §8(§e${it.tai}台§8)" }
            } else {
                "§f平胡"
            }
            val flowers = if (s.flowerCount > 0) "  §7• §d花牌 §8(§d${s.flowerCount}張§8)" else ""

            list += Component.text("  🎉 §c§l【胡牌得勝】 §b§l${winner.displayName} §a榮和得勝！ §7(🎯 放槍：§c§l${loser.displayName}§7)")
            list += Component.text("  📜 §e役種明細： §7• $taiDetails$flowers")
            list += Component.text("  💰 §6結算統計： §7合計 §a§l${s.tai} 台 §7︳由 §c${loser.displayName} §7全額支付 §a§l+${s.score} 積分")
        }
        list += DIVIDER
        return list
    }

    fun drawCard(drawName: String, tenpaiList: List<Pair<String, Boolean>>): List<Component> {
        val tenpaiSummary = tenpaiList.joinToString("  §7•  ") { (name, isTen) ->
            if (isTen) "§b$name §a§l(聽牌 ✓)" else "§7$name §8(未聽 ✗)"
        }
        return listOf(
            Component.text("§8§m━━━━━━━━━━§r §7🍂 §e§l荒 牌 流 局 §7🍂 §8§m━━━━━━━━━━"),
            Component.text("  ⏳ §7牌牆活牌已全數摸盡，本局流局！ §8(局況：$drawName)"),
            Component.text("  🛡️ §6聽牌結算： §f$tenpaiSummary"),
            DIVIDER,
        )
    }

    fun roundScoreSummary(rankedScores: List<Pair<String, Int>>): List<Component> {
        val list = mutableListOf<Component>()
        list += Component.text("  📊 §6§l【本局結算戰報】")
        val medals = listOf("🥇", "🥈", "🥉", "💥")
        rankedScores.forEachIndexed { index, (name, delta) ->
            val medal = medals.getOrElse(index) { "•" }
            val scoreText = if (delta > 0) "§a§l+$delta" else if (delta < 0) "§c§l$delta" else "§7$delta"
            val paddedName = name.padEnd(12)
            list += Component.text("    $medal §b$paddedName §8➜ $scoreText §7積分")
        }
        return list
    }

    fun gameEndPodium(scoreList: List<ScoreItem>): List<Component> {
        val list = mutableListOf<Component>()
        list += Component.text("§8§m━━━━━━━━━━§r §6👑 §e§l全 場 雀 神 總 結 算 §6👑 §8§m━━━━━━━━━━")

        val sorted = scoreList.sortedByDescending { it.scoreOrigin }
        val medals = listOf("🥇 §e冠軍", "🥈 §f亞軍", "🥉 §6季軍", "💥 §c殿軍")

        sorted.forEachIndexed { index, item ->
            val rankLabel = medals.getOrElse(index) { "第 ${index + 1} 名" }
            val finalScoreText = if (item.scoreOrigin > 0) "§a§l+${item.scoreOrigin}" else if (item.scoreOrigin < 0) "§c§l${item.scoreOrigin}" else "§7${item.scoreOrigin}"
            val tag = if (index == 0 && item.scoreOrigin > 0) " §6✨ §e§l雀神得主！" else ""

            list += Component.text("  $rankLabel§7： §b§l${item.displayName} §8➜ $finalScoreText §7積分$tag")
        }
        list += Component.text(" ")
        list += Component.text("  🎉 §7感謝各位雀友同桌切磋，期待下次再戰！")
        list += DIVIDER
        return list
    }

    fun bankruptcyAlert(names: String): List<Component> = listOf(
        DIVIDER,
        ALERT_PREFIX.append(Component.text("玩家【$names】餘額不足以支付底台，觸發擊飛（破產）！", NamedTextColor.RED).decorate(TextDecoration.BOLD)),
        Component.text("  🏁 §e牌局提前結束，立即進行最終大結算！"),
        DIVIDER,
    )
}
