# TaiwaneseMahjong

Paper 1.21.4 插件，將 Minecraft 內的麻將桌改為台灣十六張麻將。
玩家直接右鍵操作桌上的 3D 麻將牌，不需要客戶端 Mod。

## 目前規則

- 固定四人、十六張手牌。
- 牌山共 144 張：34 種基本牌各四張，另有春夏秋冬、梅蘭竹菊八張花牌。
- 摸到花牌會立即補牌；可吃、碰、明槓、暗槓與加槓。
- 和牌形狀為五組牌加一對將；支援榮和與自摸。
- 預設起始分數 16,000、最低一台起胡、每台 1,000 分。
- 常見台數包含門清、自摸、花牌、門花、圈花、清一色、混一色、字一色、碰碰胡、平胡、三元牌、風牌、四暗刻、一氣通貫與三色同順。
- 不使用立直、寶牌、紅寶牌、拔北、三人麻將或日麻的番符計算。

不同台灣牌桌對台數與支付方式可能有差異；可在 `MahjongRule` 調整最低台數、每台分數、局數與莊家自摸倍率。

## 指令

```text
/mahjong create [one|east|twowind]  建立牌桌
/mahjong join [id]                  加入牌桌
/mahjong ready                      準備
/mahjong start                      開始遊戲
/mahjong bot                        加入機器人
/mahjong leave                      離開牌桌
/mahjong info                       查看規則
/mahjong list                       查看牌桌
/mahjong destroy                    銷毀牌桌
```

遊戲內可右鍵牌面選牌，再次右鍵確認出牌；吃、碰、槓、榮和與自摸會以互動按鈕顯示。

## 建置

需要 JDK 21 與 Maven：

```bash
mvn clean test
mvn clean package
```

完成後的插件位於 `target/TaiwaneseMahjong-2.0.0.jar`。將 JAR 放入 Paper 的 `plugins` 資料夾，並將 `resource-pack` 內容部署到伺服器使用的資源包；首次更新資源包後請重新連線確認 3D 牌面與花牌模型。

## Project overview

TaiwaneseMahjong is a server-side Paper plugin for four-player Taiwanese 16-tile Mahjong. It includes a 144-tile wall with flower replacement, open melds, kongs, Taiwanese tai scoring, interactive 3D tile displays, and a Maven build.

The game logic is kept separate from Bukkit where possible, and the rule core is covered by Kotlin tests under `src/test/kotlin`.

## 自動發布 Release

GitHub Actions 會在推送符合 `v*` 格式的版本標籤時，自動執行 Maven 測試與打包，並將 JAR 附加到 GitHub Release。

```bash
git tag v2.0.0
git push origin v2.0.0
```

也可以在 GitHub 的 `Actions > Release > Run workflow` 手動輸入版本標籤觸發。
