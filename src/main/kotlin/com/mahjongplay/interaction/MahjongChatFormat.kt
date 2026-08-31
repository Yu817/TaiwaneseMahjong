package com.mahjongplay.interaction

import com.mahjongplay.game.MahjongGame
import com.mahjongplay.game.MahjongPlayerBase
import com.mahjongplay.model.MahjongTile
import com.mahjongplay.model.ScoreItem
import com.mahjongplay.model.TaiwanSettlement
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * 台灣麻將優雅訊息格式化器：提供統一、精美且現代化的聊天室廣播與結算面板。
 */
object MahjongChatFormat {

    val DIVIDER: Component = Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY)
        .decoration(TextDecoration.STRIKETHROUGH, false)

    val PREFIX: Component = Component.text("🀄 ", NamedTextColor.GOLD)
        .append(Component.text("【麻將】", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
        .append(Component.text(" ", NamedTextColor.WHITE))

    val ECO_PREFIX: Component = Component.text("💰 ", NamedTextColor.GOLD)
        .append(Component.text("【經濟】", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
        .append(Component.text(" ", NamedTextColor.WHITE))

    val ALERT_PREFIX: Component = Component.text("🚨 ", NamedTextColor.RED)
        .append(Component.text("【警報】", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD))
        .append(Component.text(" ", NamedTextColor.WHITE))

    fun gameStart(isRealMoney: Boolean): List<Component> {
        val modeLine = if (isRealMoney) {
            Component.text("  💰 賽制模式：", NamedTextColor.GOLD)
                .append(Component.text("【真金對戰局】（結算將轉移伺服器遊戲幣）", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
        } else {
            Component.text("  🎮 賽制模式：", NamedTextColor.AQUA)
                .append(Component.text("【休閒娛樂局】（純積分對局，不扣除遊戲幣）", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
        }
        return listOf(
            DIVIDER,
            Component.text("  🀄 ", NamedTextColor.GOLD)
                .append(Component.text("【台灣麻將】 牌局正式開打！", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)),
            modeLine,
            Component.text("  💡 操作提示：隨時按下 ", NamedTextColor.GRAY)
                .append(Component.text("【F 鍵】", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" 可開啟／取消 🤖 託管代打", NamedTextColor.GRAY)),
            DIVIDER,
        )
    }

    fun roundStart(roundName: String, honba: Int): Component =
        PREFIX.append(Component.text("開局：", NamedTextColor.GRAY))
            .append(Component.text(roundName, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .let { if (honba > 0) it.append(Component.text("（連莊 $honba）", NamedTextColor.YELLOW)) else it }

    fun flowerDrawn(player: String, flower: MahjongTile): Component =
        Component.text("🌸 ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("【補花】 ", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
            .append(Component.text(player, NamedTextColor.AQUA))
            .append(Component.text(" 摸進了 ", NamedTextColor.WHITE))
            .append(Component.text("[ ${flower.displayName} ]", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text("，立即補牌！", NamedTextColor.GRAY))

    fun chii(player: String, from: String, tile: MahjongTile): Component =
        Component.text("🥢 ", NamedTextColor.GREEN)
            .append(Component.text("【吃牌】 ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
            .append(Component.text(player, NamedTextColor.AQUA))
            .append(Component.text(" 吃了 ", NamedTextColor.WHITE))
            .append(Component.text(from, NamedTextColor.GRAY))
            .append(Component.text(" 的 ", NamedTextColor.WHITE))
            .append(Component.text("[ ${tile.displayName} ]", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))

    fun pon(player: String, from: String, tile: MahjongTile): Component =
        Component.text("💥 ", NamedTextColor.AQUA)
            .append(Component.text("【碰牌】 ", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
            .append(Component.text(player, NamedTextColor.AQUA))
            .append(Component.text(" 碰了 ", NamedTextColor.WHITE))
            .append(Component.text(from, NamedTextColor.GRAY))
            .append(Component.text(" 的 ", NamedTextColor.WHITE))
            .append(Component.text("[ ${tile.displayName} ]", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))

    fun kan(player: String, kanType: String, tile: MahjongTile): Component {
        val label = when (kanType) {
            "ankan" -> "暗槓"
            "kakan" -> "加槓"
            else -> "明槓"
        }
        return Component.text("🀄 ", NamedTextColor.GOLD)
            .append(Component.text("【$label】 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text(player, NamedTextColor.AQUA))
            .append(Component.text(" 槓了 ", NamedTextColor.WHITE))
            .append(Component.text("[ ${tile.displayName} ]", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            .append(Component.text("，至牌尾摸補牌！", NamedTextColor.GRAY))
    }

    fun tsumoCard(player: MahjongPlayerBase, settlement: TaiwanSettlement): List<Component> {
        val taiDetails = if (settlement.taiList.isNotEmpty()) {
            settlement.taiList.joinToString(" • ") { "${it.name}(${it.tai}台)" }
        } else {
            "底台自摸"
        }
        val flowers = if (settlement.flowerCount > 0) " • 花牌(${settlement.flowerCount}張)" else ""

        return listOf(
            DIVIDER,
            Component.text("  🏆 ", NamedTextColor.GOLD)
                .append(Component.text("【自摸胡牌】 ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(player.displayName, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" 喜獲自摸！", NamedTextColor.YELLOW)),
            Component.text("  📜 台數明細：", NamedTextColor.YELLOW)
                .append(Component.text("$taiDetails$flowers", NamedTextColor.WHITE)),
            Component.text("  💰 結算統計：", NamedTextColor.GOLD)
                .append(Component.text("合計 ", NamedTextColor.GRAY))
                .append(Component.text("${settlement.tai} 台", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text("，三家各付 ", NamedTextColor.GRAY))
                .append(Component.text("${settlement.score} 積分", NamedTextColor.GREEN))
                .append(Component.text("（總進帳 +${settlement.score * 3}）", NamedTextColor.YELLOW)),
            DIVIDER,
        )
    }

    fun ronCard(winners: List<MahjongPlayerBase>, loser: MahjongPlayerBase, settlements: List<TaiwanSettlement>): List<Component> {
        val list = mutableListOf<Component>()
        list += DIVIDER
        winners.forEachIndexed { i, winner ->
            val s = settlements.getOrElse(i) { settlements.first() }
            val taiDetails = if (s.taiList.isNotEmpty()) {
                s.taiList.joinToString(" • ") { "${it.name}(${it.tai}台)" }
            } else {
                "平胡"
            }
            val flowers = if (s.flowerCount > 0) " • 花牌(${s.flowerCount}張)" else ""

            list += Component.text("  🎉 ", NamedTextColor.GOLD)
                .append(Component.text("【胡牌結算】 ", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(winner.displayName, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" 榮胡！", NamedTextColor.GOLD))
                .append(Component.text(" (放槍：", NamedTextColor.GRAY))
                .append(Component.text(loser.displayName, NamedTextColor.RED))
                .append(Component.text(")", NamedTextColor.GRAY))

            list += Component.text("  📜 台數明細：", NamedTextColor.YELLOW)
                .append(Component.text("$taiDetails$flowers", NamedTextColor.WHITE))

            list += Component.text("  💰 結算統計：", NamedTextColor.GOLD)
                .append(Component.text("合計 ", NamedTextColor.GRAY))
                .append(Component.text("${s.tai} 台", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text("，贏得 ", NamedTextColor.GRAY))
                .append(Component.text("+${s.score} 積分", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text("（由 ${loser.displayName} 支付）", NamedTextColor.GRAY))
        }
        list += DIVIDER
        return list
    }

    fun drawCard(drawName: String, tenpaiList: List<Pair<String, Boolean>>): List<Component> {
        val tenpaiSummary = tenpaiList.joinToString("  •  ") { (name, isTen) ->
            if (isTen) "§a$name (聽牌 ✓)" else "§7$name (未聽 ✗)"
        }
        return listOf(
            DIVIDER,
            Component.text("  🍂 ", NamedTextColor.GRAY)
                .append(Component.text("【荒牌流局】 ", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .append(Component.text("牌牆活牌耗盡，本局結束！ ($drawName)", NamedTextColor.GRAY)),
            Component.text("  🛡️ 聽牌結算： ", NamedTextColor.GOLD)
                .append(Component.text(tenpaiSummary)),
            DIVIDER,
        )
    }

    fun roundScoreSummary(rankedScores: List<Pair<String, Int>>): List<Component> {
        val list = mutableListOf<Component>()
        list += Component.text("📊【本局得分榜】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        val medals = listOf("🥇", "🥈", "🥉", "💥")
        rankedScores.forEachIndexed { index, (name, delta) ->
            val medal = medals.getOrElse(index) { "•" }
            val scoreColor = when {
                delta > 0 -> NamedTextColor.GREEN
                delta < 0 -> NamedTextColor.RED
                else -> NamedTextColor.WHITE
            }
            val text = if (delta > 0) "+$delta" else "$delta"
            list += Component.text("  $medal ", NamedTextColor.YELLOW)
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text("  $text 積分", scoreColor).decorate(TextDecoration.BOLD))
        }
        return list
    }

    fun gameEndPodium(scoreList: List<ScoreItem>): List<Component> {
        val list = mutableListOf<Component>()
        list += DIVIDER
        list += Component.text("  👑 ", NamedTextColor.GOLD)
            .append(Component.text("【台灣麻將・牌局大結算】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))

        val sorted = scoreList.sortedByDescending { it.scoreOrigin }
        val medals = listOf("🥇 冠軍", "🥈 亞軍", "🥉 季軍", "💥 殿軍")

        sorted.forEachIndexed { index, item ->
            val rankLabel = medals.getOrElse(index) { "第 ${index + 1} 名" }
            val finalScoreText = if (item.scoreOrigin > 0) "+${item.scoreOrigin}" else "${item.scoreOrigin}"
            val color = when {
                item.scoreOrigin > 0 -> NamedTextColor.GREEN
                item.scoreOrigin < 0 -> NamedTextColor.RED
                else -> NamedTextColor.WHITE
            }
            val tag = if (index == 0 && item.scoreOrigin > 0) " 🀄 雀神！" else ""

            list += Component.text("  $rankLabel：", NamedTextColor.YELLOW)
                .append(Component.text(" ${item.displayName}", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text("  $finalScoreText 積分", color).decorate(TextDecoration.BOLD))
                .append(Component.text(tag, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        }
        list += Component.text("  🎉 感謝各位雀友切磋，期待下次再戰！", NamedTextColor.GRAY)
        list += DIVIDER
        return list
    }

    fun bankruptcyAlert(names: String): List<Component> = listOf(
        DIVIDER,
        ALERT_PREFIX.append(Component.text("玩家【$names】餘額不足以支付底台，觸發擊飛（破產）！", NamedTextColor.RED).decorate(TextDecoration.BOLD)),
        Component.text("  🏁 牌局提前結束，立即進行最終大結算！", NamedTextColor.YELLOW),
        DIVIDER,
    )
}
