package com.mahjongplay.display

import com.mahjongplay.MahjongPlayPlugin
import com.mahjongplay.game.*
import com.mahjongplay.model.MahjongTile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SeatWindDrawRenderer(
    private val center: Location,
    private val surfaceY: () -> Double,
    private val tableScale: Float,
    private val showToAllViewers: (MahjongTileDisplay) -> Unit,
    private val ownershipTag: String? = null,
) {
    private val world: World = center.world
    private val tileDisplays = mutableListOf<MahjongTileDisplay>()
    private val interactionToTileIndex = ConcurrentHashMap<UUID, Int>()
    private var bannerDisplay: TextDisplay? = null
    private val pickedLabelDisplays = mutableListOf<TextDisplay>()
    var currentPickerUUID: String? = null
    var availableIndices = mutableSetOf(0, 1, 2, 3)

    companion object {
        const val WIND_TILE_SCALE = 0.44f
    }

    private fun getTileLocation(index: Int, sy: Double): Location {
        // 避開桌子正中央模型，進一步大幅拉開四張風牌橫向間距（橫向間距達 0.70 格以上），徹底杜絕浮字體左右碰撞
        val (offsetX, offsetZ) = when (index) {
            0 -> -1.05 to 0.22
            1 -> -0.35 to 0.60
            2 -> 0.35 to 0.60
            else -> 1.05 to 0.22
        }
        // 浮空高度 sy + 0.08，徹底高於桌布與邊框模型，絕不被遮擋
        return Location(world, center.x + offsetX, sy + 0.08, center.z + offsetZ)
    }

    fun begin(event: SeatWindDrawStartEvent, game: MahjongGame) {
        cancelAndClear()
        availableIndices = (0 until 4).toMutableSet()

        val sy = surfaceY()
        val starter = game.seat.getOrNull(event.starterSeatIndex)

        // 1. 生成中央標題橫幅（置於高處 sy + 1.25，避免與風牌及名字標籤高度重疊）
        val bannerLoc = Location(world, center.x, sy + 1.25, center.z + 0.15)
        val banner = world.spawnEntity(bannerLoc, EntityType.TEXT_DISPLAY) as TextDisplay
        banner.isPersistent = false
        ownershipTag?.let(banner::addScoreboardTag)
        banner.billboard = Display.Billboard.CENTER
        banner.backgroundColor = Color.fromARGB(215, 15, 20, 30)
        banner.brightness = Display.Brightness(15, 15)
        banner.isShadowed = true
        banner.alignment = TextDisplay.TextAlignment.CENTER
        banner.text(
            Component.text("【開局抓位・抓風】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("🎲 擲出 ${event.dice.total} 點！由 ", NamedTextColor.YELLOW))
                .append(Component.text(starter?.displayName ?: "玩家", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" 率先起抓", NamedTextColor.YELLOW))
        )
        bannerDisplay = banner

        // 2. 排列 4 張放大版蓋著的風牌（scale = 0.44f，大張醒目）
        for (i in 0 until 4) {
            val loc = getTileLocation(i, sy)
            val display = MahjongTileDisplay(
                location = loc,
                tile = MahjongTile.UNKNOWN,
                face = TileFace.FACE_DOWN,
                yaw = 0f,
                interactive = true,
                scale = WIND_TILE_SCALE,
                ownershipTag = ownershipTag,
            )
            display.spawn()
            display.entity?.isVisibleByDefault = true
            showToAllViewers(display)

            display.interactionEntity?.let { interaction ->
                interaction.interactionWidth = 0.45f
                interaction.interactionHeight = 0.36f
                interactionToTileIndex[interaction.uniqueId] = i
            }
            tileDisplays += display
        }

        world.playSound(center, Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.BLOCKS, 1.0f, 1.2f)
        broadcastToPlayers(game, Component.text("🎲 擲出 ${event.dice.total} 點！由【${starter?.displayName}】率先起抓風牌", NamedTextColor.GOLD))
    }

    fun promptPicker(event: SeatWindTurnPromptEvent, game: MahjongGame) {
        val picker = event.picker
        currentPickerUUID = picker.uuid
        availableIndices = event.availableIndices.toMutableSet()

        val bukkitPlayer = runCatching { Bukkit.getPlayer(UUID.fromString(picker.uuid)) }.getOrNull()
        if (bukkitPlayer != null) {
            bukkitPlayer.sendActionBar(
                Component.text("👉 輪到您抓風！請用準心點選桌面上的一張蓋牌", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
            )
            bukkitPlayer.playSound(bukkitPlayer.location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.9f, 1.4f)
        }

        bannerDisplay?.text(
            Component.text("【開局抓位・抓風】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("👉 輪到 ", NamedTextColor.WHITE))
                .append(Component.text(picker.displayName, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" 抽取風牌！", NamedTextColor.WHITE))
        )
    }

    fun onTilePicked(event: SeatWindTilePickedEvent, game: MahjongGame) {
        val index = event.tileIndex
        availableIndices.remove(index)

        val display = tileDisplays.getOrNull(index)
        if (display != null) {
            display.updateTile(event.windTile)
            // 翻開後牌身立起並微幅抬升，且讓牌身 Billboard CENTER 面向所有人
            val loc = display.location.clone().apply { y += 0.05 }
            display.updatePosition(loc, 0f, TileFace.STANDING)
            display.entity?.billboard = Display.Billboard.CENTER
            display.entity?.isVisibleByDefault = true
            showToAllViewers(display)

            // 在翻開的大牌上方生成抽牌者與風位標籤
            val labelLoc = Location(world, loc.x, surfaceY() + 0.50, loc.z)
            val label = world.spawnEntity(labelLoc, EntityType.TEXT_DISPLAY) as TextDisplay
            label.isPersistent = false
            ownershipTag?.let(label::addScoreboardTag)
            label.billboard = Display.Billboard.CENTER
            label.backgroundColor = Color.fromARGB(210, 10, 16, 26)
            label.brightness = Display.Brightness(15, 15)
            label.isShadowed = true
            label.alignment = TextDisplay.TextAlignment.CENTER
            val scaleMatrix = org.joml.Matrix4f().scale(0.65f)
            label.setTransformationMatrix(scaleMatrix)
            label.text(
                Component.text(event.picker.displayName, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
                    .append(Component.newline())
                    .append(Component.text("【${event.windTile.displayName}】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
            )
            pickedLabelDisplays += label
        }

        world.playSound(center, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.9f, 1.3f)
        world.playSound(center, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.7f, 1.2f)

        broadcastToPlayers(
            game,
            Component.text("🀄 【${event.picker.displayName}】 抽到了 ", NamedTextColor.WHITE)
                .append(Component.text("【${event.windTile.displayName}】", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        )
    }

    fun onCompleted(event: SeatWindDrawCompleteEvent, game: MahjongGame) {
        val east = event.newSeatOrder.getOrNull(0)?.displayName ?: ""
        val south = event.newSeatOrder.getOrNull(1)?.displayName ?: ""
        val west = event.newSeatOrder.getOrNull(2)?.displayName ?: ""
        val north = event.newSeatOrder.getOrNull(3)?.displayName ?: ""

        bannerDisplay?.text(
            Component.text("=== 抓風就位完成 ===", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("【東家・起莊】 ", NamedTextColor.YELLOW).append(Component.text(east, NamedTextColor.WHITE)))
                .append(Component.newline())
                .append(Component.text("【南家】 ", NamedTextColor.GREEN).append(Component.text(south, NamedTextColor.WHITE)))
                .append(Component.newline())
                .append(Component.text("【西家】 ", NamedTextColor.AQUA).append(Component.text(west, NamedTextColor.WHITE)))
                .append(Component.newline())
                .append(Component.text("【北家】 ", NamedTextColor.LIGHT_PURPLE).append(Component.text(north, NamedTextColor.WHITE)))
        )

        world.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 1.0f, 1.0f)
        broadcastToPlayers(game, Component.text("🎉 抓風完成！各家就位，即將開局！", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
    }

    fun handleInteractionClick(player: Player, entityUUID: UUID): Boolean {
        val tileIndex = interactionToTileIndex[entityUUID] ?: return false
        if (tileIndex !in availableIndices) return false

        val uuidStr = player.uniqueId.toString()
        if (currentPickerUUID != uuidStr) {
            player.sendActionBar(Component.text("⏳ 還沒輪到您抓風，請稍候！", NamedTextColor.RED))
            return true
        }

        val mjPlayer = MahjongPlayPlugin.instance.tableManager.getGameForPlayer(uuidStr)
            ?.realPlayers?.find { it.uuid == uuidStr } as? MahjongPlayer ?: return false

        val resolved = mjPlayer.resolveSeatWindPick(tileIndex)
        if (resolved) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.6f, 1.5f)
        }
        return true
    }

    fun isWindTileInteraction(entityUUID: UUID): Boolean = interactionToTileIndex.containsKey(entityUUID)

    fun cancelAndClear() {
        tileDisplays.forEach { it.remove() }
        tileDisplays.clear()
        interactionToTileIndex.clear()
        bannerDisplay?.remove()
        bannerDisplay = null
        pickedLabelDisplays.forEach { it.remove() }
        pickedLabelDisplays.clear()
        currentPickerUUID = null
        availableIndices.clear()
    }

    private fun broadcastToPlayers(game: MahjongGame, message: Component) {
        game.realPlayers.forEach { mjPlayer ->
            runCatching { Bukkit.getPlayer(UUID.fromString(mjPlayer.uuid)) }.getOrNull()?.sendMessage(
                Component.text("[麻將] ", NamedTextColor.GOLD).append(message)
            )
        }
    }
}
