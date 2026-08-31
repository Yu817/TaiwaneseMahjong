package com.mahjongplay.table

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.BotDifficulty
import com.mahjongplay.game.GameStatus
import com.mahjongplay.interaction.MahjongChatFormat
import com.mahjongplay.model.MahjongRule
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

object MahjongSettingsGUI {

    const val TITLE = "⚙ 麻將牌桌設定"

    private val CIRCLE_PRESETS = listOf(1, 2, 4, 8, 12, 16)
    val BOT_PRESETS = listOf(3000L, 6000L, 9000L, 12000L, 15000L)

    private fun Component.noItalic(): Component =
        this.decoration(TextDecoration.ITALIC, false)

    fun open(player: Player, session: MahjongTableSession, manager: MahjongTableManager) {
        if (session.game.status != GameStatus.WAITING) {
            player.sendMessage(MahjongChatFormat.warn("遊戲進行中，無法修改牌桌規則。"))
            return
        }

        val rule = session.game.rule
        val owner = manager.isTableOwner(session, player.uniqueId.toString())
        val ownerName = session.game.players
            .firstOrNull { it.uuid == session.ownerUUID }
            ?.displayName
            ?: "尚未加入"

        val inv: Inventory = Bukkit.createInventory(
            null,
            45,
            Component.text(TITLE, NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD).noItalic(),
        )

        // 1. 邊框與背景填色
        val grayBorder = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.GRAY))
        val cyanBorder = createItem(Material.CYAN_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.AQUA))
        for (i in 0 until 45) {
            inv.setItem(i, grayBorder)
        }
        val cornerSlots = listOf(0, 8, 36, 44)
        cornerSlots.forEach { inv.setItem(it, cyanBorder) }

        // 2. Slot 4: 規則總覽看板
        val botSec = (rule.botResponseDelayMs / 1000L).coerceIn(3L, 15L)
        val modeDisplay = if (rule.moneyMatch && !rule.botsEnabled) {
            Component.text("💰 金幣真錢局", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        } else if (rule.botsEnabled) {
            Component.text("🎮 娛樂局 (含機器人)", NamedTextColor.AQUA)
        } else {
            Component.text("🎮 休閒娛樂局", NamedTextColor.AQUA)
        }

        val overviewLore = listOf(
            Component.text("👑 桌主：", NamedTextColor.GOLD).append(Component.text(ownerName, NamedTextColor.YELLOW)),
            Component.text("⚔ 模式：", NamedTextColor.GOLD).append(modeDisplay),
            Component.text("🀄 圈數：", NamedTextColor.AQUA).append(Component.text(rule.displayCircleText, NamedTextColor.WHITE)),
            Component.text("💰 底／台：", NamedTextColor.GOLD).append(Component.text("${rule.basePoints} 底 / ${rule.pointsPerTai} 台", NamedTextColor.GREEN)),
            Component.text("⏳ 玩家限時：", NamedTextColor.YELLOW).append(Component.text(rule.thinkingTime.displayName, NamedTextColor.WHITE)),
            Component.text("🤖 機器人：", NamedTextColor.BLUE).append(
                if (rule.botsEnabled) Component.text("開啟 (${rule.defaultBotDifficulty.displayName}・${botSec}秒)", NamedTextColor.GREEN)
                else Component.text("關閉", NamedTextColor.RED),
            ),
            Component.text("🧭 開局抓風：", NamedTextColor.GOLD).append(Component.text(if (rule.seatWindDrawEnabled) "開啟 ✓" else "關閉 ✗", if (rule.seatWindDrawEnabled) NamedTextColor.GREEN else NamedTextColor.RED)),
            Component.text("🌸 花牌規則：", NamedTextColor.LIGHT_PURPLE).append(Component.text(if (rule.flowersEnabled) "開啟 ✓" else "關閉 ✗", if (rule.flowersEnabled) NamedTextColor.GREEN else NamedTextColor.RED)),
            Component.text("👁 旁觀看牌：", NamedTextColor.BLUE).append(Component.text(if (rule.spectatorSeeHands) "允許看牌 ✓" else "隱藏手牌 ✗", if (rule.spectatorSeeHands) NamedTextColor.GREEN else NamedTextColor.RED)),
            Component.text(" "),
            if (owner) {
                Component.text("💡 提示：點擊下方圖示可修改或切換設定！", NamedTextColor.YELLOW)
            } else {
                Component.text("🔒 目前為檢視模式（僅桌主可修改設定）", NamedTextColor.GRAY)
            },
        )
        inv.setItem(4, createItem(Material.ENCHANTED_BOOK, Component.text("🀄【目前牌桌規則總覽】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), overviewLore))

        // Row 2: 模式、圈數、底分、台分 (Slots 10, 12, 14, 16)
        val modeMat = if (rule.moneyMatch) Material.EMERALD else Material.SUNFLOWER
        val modeLore = mutableListOf<Component>()
        modeLore += Component.text("對戰模式選擇：", NamedTextColor.GRAY)

        if (rule.moneyMatch) {
            modeLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("💰 真金對戰（金幣局）", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
            modeLore += Component.text("    🎮 休閒娛樂（娛樂局）", NamedTextColor.DARK_GRAY)
        } else {
            modeLore += Component.text("    💰 真金對戰（金幣局）", NamedTextColor.DARK_GRAY)
            modeLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("🎮 休閒娛樂（娛樂局）", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
        }

        modeLore += Component.text(" ")
        modeLore += Component.text("▶ 點擊切換對戰模式", NamedTextColor.YELLOW)
        if (rule.botsEnabled) {
            modeLore += Component.text("⚠ 提醒：開啟機器人補位時將強制以娛樂局進行", NamedTextColor.RED)
        } else {
            modeLore += Component.text("• 金幣局：遊戲結束依積分轉移伺服器經濟遊戲幣", NamedTextColor.GRAY)
            modeLore += Component.text("• 娛樂局：純積分對戰，不扣除任何遊戲幣", NamedTextColor.GRAY)
        }

        inv.setItem(10, createItem(modeMat, Component.text("⚔ 對戰模式", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), modeLore))

        // Slot 12: 牌局圈數
        val circleLore = mutableListOf<Component>()
        circleLore += Component.text("牌局圈數選擇：", NamedTextColor.GRAY)
        listOf(
            1 to "1/4 圈（1 局）",
            2 to "1/2 圈（2 局）",
            4 to "1 圈（4 局）",
            8 to "2 圈（8 局）",
            12 to "3 圈（12 局）",
            16 to "4 圈（16 局）",
        ).forEach { (cnt, label) ->
            if (rule.roundsToPlay == cnt) {
                circleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    .append(Component.text(label, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                    .append(Component.text(" ✔", NamedTextColor.GREEN))
            } else {
                circleLore += Component.text("    $label", NamedTextColor.DARK_GRAY)
            }
        }
        circleLore += Component.text(" ")
        circleLore += Component.text("▶ 點擊循環切換圈數", NamedTextColor.YELLOW)

        inv.setItem(12, createItem(Material.CLOCK, Component.text("🀄 牌局圈數", NamedTextColor.AQUA).decorate(TextDecoration.BOLD), circleLore))

        inv.setItem(
            14,
            createItem(
                Material.GOLD_INGOT,
                Component.text("💰 底金積分 (Base Points)", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                listOf(
                    Component.text("目前底金：", NamedTextColor.GRAY).append(Component.text("${rule.basePoints} 分", NamedTextColor.GREEN)),
                    Component.text(" "),
                    Component.text("▶ 點擊開啟輸入視窗修改底金", NamedTextColor.YELLOW),
                ),
            ),
        )

        inv.setItem(
            16,
            createItem(
                Material.GOLD_NUGGET,
                Component.text("✨ 每台積分 (Points Per Tai)", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                listOf(
                    Component.text("目前每台：", NamedTextColor.GRAY).append(Component.text("${rule.pointsPerTai} 分", NamedTextColor.GREEN)),
                    Component.text(" "),
                    Component.text("▶ 點擊開啟輸入視窗修改每台", NamedTextColor.YELLOW),
                ),
            ),
        )

        // Row 3: 玩家思考限時、Bots補位開關、難度、思考速度 (Slots 19, 21, 23, 25)
        val playerTimeLore = mutableListOf<Component>()
        playerTimeLore += Component.text("玩家出牌限時選擇：", NamedTextColor.GRAY)
        listOf(
            MahjongRule.ThinkingTime.SEC_3,
            MahjongRule.ThinkingTime.SEC_6,
            MahjongRule.ThinkingTime.SEC_9,
            MahjongRule.ThinkingTime.SEC_12,
            MahjongRule.ThinkingTime.SEC_15,
        ).forEach { t ->
            if (rule.thinkingTime == t) {
                playerTimeLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    .append(Component.text(t.displayName, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                    .append(Component.text(" ✔", NamedTextColor.GREEN))
            } else {
                playerTimeLore += Component.text("    ${t.displayName}", NamedTextColor.DARK_GRAY)
            }
        }
        playerTimeLore += Component.text(" ")
        playerTimeLore += Component.text("▶ 點擊循環切換限時", NamedTextColor.YELLOW)

        inv.setItem(19, createItem(Material.REPEATER, Component.text("⏳ 玩家出牌思考時間", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), playerTimeLore))

        val botToggleLore = mutableListOf<Component>()
        botToggleLore += Component.text("機器人補位設定：", NamedTextColor.GRAY)
        if (rule.botsEnabled) {
            botToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("開啟補位（自動補足4人）", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
            botToggleLore += Component.text("    關閉補位（需4位真人）", NamedTextColor.DARK_GRAY)
        } else {
            botToggleLore += Component.text("    開啟補位（自動補足4人）", NamedTextColor.DARK_GRAY)
            botToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("關閉補位（需4位真人）", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
        }
        botToggleLore += Component.text(" ")
        botToggleLore += Component.text("▶ 點擊切換補位開關", NamedTextColor.YELLOW)
        botToggleLore += Component.text("⚠ 提醒：開啟機器人時將強制以娛樂局進行", NamedTextColor.RED)

        inv.setItem(21, createItem(Material.IRON_BLOCK, Component.text("🤖 機器人補位 (Bots)", NamedTextColor.BLUE).decorate(TextDecoration.BOLD), botToggleLore))

        // 僅在開 Bot 時才顯示 Bot 的難度與速度設定
        if (rule.botsEnabled) {
            val diffLore = mutableListOf<Component>()
            diffLore += Component.text("機器人難度選擇：", NamedTextColor.GRAY)
            BotDifficulty.entries.forEach { d ->
                if (rule.defaultBotDifficulty == d) {
                    diffLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                        .append(Component.text(d.displayName, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                        .append(Component.text(" ✔", NamedTextColor.GREEN))
                } else {
                    diffLore += Component.text("    ${d.displayName}", NamedTextColor.DARK_GRAY)
                }
            }
            diffLore += Component.text(" ")
            diffLore += Component.text("▶ 點擊循環切換難度", NamedTextColor.YELLOW)

            inv.setItem(23, createItem(Material.TARGET, Component.text("🎯 機器人難度", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), diffLore))

            val botSpeedLore = mutableListOf<Component>()
            botSpeedLore += Component.text("機器人出牌速度選擇：", NamedTextColor.GRAY)
            BOT_PRESETS.forEach { ms ->
                val s = ms / 1000L
                if (rule.botResponseDelayMs == ms) {
                    botSpeedLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                        .append(Component.text("$s 秒", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                        .append(Component.text(" ✔", NamedTextColor.GREEN))
                } else {
                    botSpeedLore += Component.text("    $s 秒", NamedTextColor.DARK_GRAY)
                }
            }
            botSpeedLore += Component.text(" ")
            botSpeedLore += Component.text("▶ 點擊循環切換思考速度", NamedTextColor.YELLOW)

            inv.setItem(25, createItem(Material.SUGAR, Component.text("⚡ 機器人思考速度", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), botSpeedLore))
        } else {
            inv.setItem(23, grayBorder)
            inv.setItem(25, grayBorder)
        }

        // Row 4: 抓風、花牌、重設 (Slots 28, 30, 34)
        val windToggleLore = mutableListOf<Component>()
        windToggleLore += Component.text("開局抓風選位設定：", NamedTextColor.GRAY)
        if (rule.seatWindDrawEnabled) {
            windToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("開啟（開局擲骰摸風排座）", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
            windToggleLore += Component.text("    關閉（依加入順序排座）", NamedTextColor.DARK_GRAY)
        } else {
            windToggleLore += Component.text("    開啟（開局擲骰摸風排座）", NamedTextColor.DARK_GRAY)
            windToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("關閉（依加入順序排座）", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
        }
        windToggleLore += Component.text(" ")
        windToggleLore += Component.text("▶ 點擊切換抓風流程", NamedTextColor.YELLOW)

        inv.setItem(28, createItem(Material.COMPASS, Component.text("🧭 開局抓風選位", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), windToggleLore))

        val flowerToggleLore = mutableListOf<Component>()
        flowerToggleLore += Component.text("花牌規則設定：", NamedTextColor.GRAY)
        if (rule.flowersEnabled) {
            flowerToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("開啟（包含春夏秋冬梅蘭竹菊）", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
            flowerToggleLore += Component.text("    關閉（無花牌純字與數牌）", NamedTextColor.DARK_GRAY)
        } else {
            flowerToggleLore += Component.text("    開啟（包含春夏秋冬梅蘭竹菊）", NamedTextColor.DARK_GRAY)
            flowerToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("關閉（無花牌純字與數牌）", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
        }
        flowerToggleLore += Component.text(" ")
        flowerToggleLore += Component.text("▶ 點擊切換花牌規則", NamedTextColor.YELLOW)

        inv.setItem(30, createItem(Material.POPPY, Component.text("🌸 8張花牌規則", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD), flowerToggleLore))

        val spectatorToggleLore = mutableListOf<Component>()
        spectatorToggleLore += Component.text("旁觀看牌設定：", NamedTextColor.GRAY)
        if (rule.spectatorSeeHands) {
            spectatorToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("允許看牌（非本桌玩家可看手牌）", NamedTextColor.BLUE).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
            spectatorToggleLore += Component.text("    隱藏手牌（旁觀者只看見牌背）", NamedTextColor.DARK_GRAY)
        } else {
            spectatorToggleLore += Component.text("    允許看牌（非本桌玩家可看手牌）", NamedTextColor.DARK_GRAY)
            spectatorToggleLore += Component.text(" ➤ ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text("隱藏手牌（旁觀者只看見牌背）", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✔", NamedTextColor.GREEN))
        }
        spectatorToggleLore += Component.text(" ")
        spectatorToggleLore += Component.text("▶ 點擊切換旁觀看牌權限", NamedTextColor.YELLOW)

        inv.setItem(32, createItem(Material.ENDER_EYE, Component.text("👁 旁觀看牌", NamedTextColor.BLUE).decorate(TextDecoration.BOLD), spectatorToggleLore))

        inv.setItem(
            34,
            createItem(
                Material.REDSTONE,
                Component.text("🔄 還原預設設定", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                listOf(
                    Component.text("點擊將所有規則還原為伺服器預設值", NamedTextColor.GRAY),
                    Component.text(" "),
                    Component.text("▶ 點擊還原預設", NamedTextColor.RED),
                ),
            ),
        )

        // Row 5: 關閉視窗 (Slot 40)
        inv.setItem(
            40,
            createItem(
                Material.BARRIER,
                Component.text("✕ 關閉設定視窗", NamedTextColor.GRAY).decorate(TextDecoration.BOLD),
                listOf(Component.text("點擊關閉此介面", NamedTextColor.DARK_GRAY)),
            ),
        )

        player.openInventory(inv)
    }

    fun promptBasePointsDialog(player: Player, session: MahjongTableSession, manager: MahjongTableManager) {
        player.closeInventory()
        val current = session.game.rule.basePoints
        val base = DialogBase.builder(Component.text("💰 設定底金積分", NamedTextColor.GOLD))
            .externalTitle(Component.text("麻將底金積分設定", NamedTextColor.GOLD))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(listOf(DialogBody.plainMessage(Component.text("請在下方欄位輸入新的底金積分（0～1,000,000）：", NamedTextColor.YELLOW), 320)))
            .inputs(listOf(
                DialogInput.text("value", Component.text("底金積分", NamedTextColor.YELLOW))
                    .width(220)
                    .labelVisible(true)
                    .initial(current.toString())
                    .maxLength(7)
                    .build(),
            ))
            .build()

        val submitButton = ActionButton.builder(Component.text("✔ 確認儲存", NamedTextColor.GREEN))
            .tooltip(Component.text("點擊儲存設定並返回牌桌設定選單", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                DialogActionCallback { response, audience ->
                    if (audience is Player) {
                        val raw = response.getText("value")?.trim()?.replace(",", "")?.toIntOrNull()
                        if (raw == null || raw !in 0..MahjongRule.MAX_POINTS) {
                            audience.sendMessage(MahjongChatFormat.error("底金積分必須是 0～${MahjongRule.MAX_POINTS} 的整數。"))
                        } else {
                            val updated = session.game.rule.copy(basePoints = raw)
                            session.game.changeRules(updated)
                            manager.updateTableDisplay(session)
                            manager.autoSave()
                            audience.sendMessage(MahjongChatFormat.success("底金積分已成功設定為：${raw} 分！"))
                        }
                        Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                            open(audience, session, manager)
                        }, 1L)
                    }
                },
                ClickCallback.Options.builder().uses(1).build(),
            ))
            .build()

        val cancelButton = ActionButton.builder(Component.text("✕ 取消返回", NamedTextColor.GRAY))
            .tooltip(Component.text("返回牌桌設定選單", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                DialogActionCallback { _, audience ->
                    if (audience is Player) {
                        Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                            open(audience, session, manager)
                        }, 1L)
                    }
                },
                ClickCallback.Options.builder().uses(1).build(),
            ))
            .build()

        val type = DialogType.multiAction(listOf(submitButton), cancelButton, 1)
        val dialog = Dialog.create { factory -> factory.empty().base(base).type(type) }
        player.showDialog(dialog)
    }

    fun promptPointsPerTaiDialog(player: Player, session: MahjongTableSession, manager: MahjongTableManager) {
        player.closeInventory()
        val current = session.game.rule.pointsPerTai
        val base = DialogBase.builder(Component.text("✨ 設定每台積分", NamedTextColor.GOLD))
            .externalTitle(Component.text("麻將每台積分設定", NamedTextColor.GOLD))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(listOf(DialogBody.plainMessage(Component.text("請在下方欄位輸入新的每台積分（0～1,000,000）：", NamedTextColor.YELLOW), 320)))
            .inputs(listOf(
                DialogInput.text("value", Component.text("每台積分", NamedTextColor.GOLD))
                    .width(220)
                    .labelVisible(true)
                    .initial(current.toString())
                    .maxLength(7)
                    .build(),
            ))
            .build()

        val submitButton = ActionButton.builder(Component.text("✔ 確認儲存", NamedTextColor.GREEN))
            .tooltip(Component.text("點擊儲存設定並返回牌桌設定選單", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                DialogActionCallback { response, audience ->
                    if (audience is Player) {
                        val raw = response.getText("value")?.trim()?.replace(",", "")?.toIntOrNull()
                        if (raw == null || raw !in 0..MahjongRule.MAX_POINTS) {
                            audience.sendMessage(MahjongChatFormat.error("每台積分必須是 0～${MahjongRule.MAX_POINTS} 的整數。"))
                        } else {
                            val updated = session.game.rule.copy(pointsPerTai = raw)
                            session.game.changeRules(updated)
                            manager.updateTableDisplay(session)
                            manager.autoSave()
                            audience.sendMessage(MahjongChatFormat.success("每台積分已成功設定為：${raw} 分！"))
                        }
                        Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                            open(audience, session, manager)
                        }, 1L)
                    }
                },
                ClickCallback.Options.builder().uses(1).build(),
            ))
            .build()

        val cancelButton = ActionButton.builder(Component.text("✕ 取消返回", NamedTextColor.GRAY))
            .tooltip(Component.text("返回牌桌設定選單", NamedTextColor.GRAY))
            .action(DialogAction.customClick(
                DialogActionCallback { _, audience ->
                    if (audience is Player) {
                        Bukkit.getScheduler().runTaskLater(MahjongPlayPlugin.instance, Runnable {
                            open(audience, session, manager)
                        }, 1L)
                    }
                },
                ClickCallback.Options.builder().uses(1).build(),
            ))
            .build()

        val type = DialogType.multiAction(listOf(submitButton), cancelButton, 1)
        val dialog = Dialog.create { factory -> factory.empty().base(base).type(type) }
        player.showDialog(dialog)
    }

    private fun createItem(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.noItalic())
        if (lore.isNotEmpty()) {
            meta.lore(lore.map { it.noItalic() })
        }
        item.itemMeta = meta
        return item
    }
}

class MahjongSettingsGUIListener(private val manager: MahjongTableManager) : Listener {

    private val CIRCLE_PRESETS = listOf(1, 2, 4, 8, 12, 16)
    private val BOT_PRESETS = listOf(3000L, 6000L, 9000L, 12000L, 15000L)

    private fun <T> nextPreset(current: T, presets: List<T>): T {
        val index = presets.indexOf(current)
        return if (index == -1 || index >= presets.size - 1) presets.first() else presets[index + 1]
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(view.title())

        if (titleText.contains(MahjongSettingsGUI.TITLE)) {
            event.isCancelled = true
            val rawSlot = event.rawSlot
            if (rawSlot !in 0 until 45) return

            val session = manager.getSessionForPlayer(player.uniqueId.toString())
                ?: manager.getAllSessions().firstOrNull { s ->
                    s.center.world == player.world && s.center.distance(player.location) <= 6.0
                }
                ?: return

            if (session.game.status != GameStatus.WAITING) {
                player.sendMessage(MahjongChatFormat.warn("遊戲進行中，無法修改牌桌規則。"))
                player.closeInventory()
                return
            }

            if (rawSlot == 40) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.0f)
                player.closeInventory()
                return
            }

            if (!manager.isTableOwner(session, player.uniqueId.toString())) {
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                player.sendMessage(MahjongChatFormat.error("只有桌主可以修改牌桌規則設定。"))
                return
            }

            val updated = session.game.rule.copy()
            when (rawSlot) {
                10 -> { // 對戰模式 (金幣局 / 娛樂局)
                    updated.moneyMatch = !updated.moneyMatch
                    if (updated.moneyMatch && updated.basePoints == 0) {
                        updated.basePoints = 300
                        updated.pointsPerTai = 100
                    }
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                12 -> { // 圈數
                    updated.roundsToPlay = nextPreset(updated.roundsToPlay, CIRCLE_PRESETS)
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                14 -> { // 底金
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                    MahjongSettingsGUI.promptBasePointsDialog(player, session, manager)
                    return
                }
                16 -> { // 每台
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                    MahjongSettingsGUI.promptPointsPerTaiDialog(player, session, manager)
                    return
                }
                19 -> { // 玩家出牌時間
                    val curSeconds = updated.thinkingTime.totalSeconds
                    val next = when (curSeconds) {
                        3 -> MahjongRule.ThinkingTime.SEC_6
                        6 -> MahjongRule.ThinkingTime.SEC_9
                        9 -> MahjongRule.ThinkingTime.SEC_12
                        12 -> MahjongRule.ThinkingTime.SEC_15
                        15 -> MahjongRule.ThinkingTime.SEC_3
                        else -> MahjongRule.ThinkingTime.SEC_9
                    }
                    updated.thinkingTime = next
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                21 -> { // Bots 開關
                    updated.botsEnabled = !updated.botsEnabled
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                23 -> { // Bot 難度 (僅在 botsEnabled 時有效)
                    if (!session.game.rule.botsEnabled) return
                    val allDiffs = BotDifficulty.entries
                    val nextIdx = (allDiffs.indexOf(updated.defaultBotDifficulty) + 1) % allDiffs.size
                    updated.defaultBotDifficulty = allDiffs[nextIdx]
                    session.game.players.filterIsInstance<com.mahjongplay.game.MahjongBot>().forEach { it.difficulty = updated.defaultBotDifficulty }
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                25 -> { // Bot 速度 (僅在 botsEnabled 時有效)
                    if (!session.game.rule.botsEnabled) return
                    updated.botResponseDelayMs = nextPreset(updated.botResponseDelayMs, BOT_PRESETS)
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                28 -> { // 抓風
                    updated.seatWindDrawEnabled = !updated.seatWindDrawEnabled
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                30 -> { // 花牌
                    updated.flowersEnabled = !updated.flowersEnabled
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                32 -> { // 旁觀看牌
                    updated.spectatorSeeHands = !updated.spectatorSeeHands
                    session.game.players.forEach { session.renderer.updateVisibility(it) }
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                }
                34 -> { // 還原預設
                    val defaultRule = manager.settings.createRule()
                    updated.moneyMatch = defaultRule.moneyMatch
                    updated.roundsToPlay = defaultRule.roundsToPlay
                    updated.thinkingTime = defaultRule.thinkingTime
                    updated.botsEnabled = defaultRule.botsEnabled
                    updated.defaultBotDifficulty = defaultRule.defaultBotDifficulty
                    updated.botResponseDelayMs = defaultRule.botResponseDelayMs
                    updated.flowersEnabled = defaultRule.flowersEnabled
                    updated.chairsEnabled = false
                    updated.seatWindDrawEnabled = defaultRule.seatWindDrawEnabled
                    updated.spectatorSeeHands = defaultRule.spectatorSeeHands
                    updated.basePoints = defaultRule.basePoints
                    updated.pointsPerTai = defaultRule.pointsPerTai
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
                }
                else -> return
            }

            session.game.changeRules(updated)
            manager.updateTableDisplay(session)
            manager.autoSave()
            MahjongSettingsGUI.open(player, session, manager)
        }
    }
}
