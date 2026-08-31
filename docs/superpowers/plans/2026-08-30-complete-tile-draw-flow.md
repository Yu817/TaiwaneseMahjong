# 完整抓牌流程實作計畫

**Goal:** 將目前「從牌牆資料直接加進手牌」的流程，改成可見、可取消、不卡規則的台灣麻將抓牌演出。

**Constraints:** 維持既有 16 張台麻規則、144 張牌／鐵八墩／補花／槓後補牌；不得洩漏其他玩家手牌；遊戲規則的正確性不可依賴 Bukkit 動畫是否成功執行；動畫必須在結束、離桌、重讀插件時可清除。

**Assumptions:** 第一版會顯示四邊雙層牌牆、從牌頭摸牌與牌尾補牌、牌移動到手牌的演出。骰子、開門位置與洗牌僅做視覺上的可重現定位，不新增玩家手動選骰或改變既有亂數牌序。動畫預設約 250–350 ms，並提供停用或縮短設定。

**Acceptance:** 一局開始可看見完整背面牌牆：每邊 36 張（18 墩、每墩上下兩張），四邊合計 72 墩、144 張；同邊的 18 墩彼此無可見間距、上下兩層緊密貼合，外觀是一道連續牌牆而非手牌排列；每次配牌、一般摸牌、補花、暗槓／加槓／明槓補牌都會從正確牌牆端點移動一張牌；非擁有者全程只看見牌背；補花公開顯示花牌後才從牌尾補牌；任何結束或重載後不留下牌牆或飛行牌。

### Task 1: 建立抓牌事件契約與回合序號

- Files: `src/main/kotlin/com/mahjongplay/game/MahjongGame.kt`, `src/main/kotlin/com/mahjongplay/game/TileDrawEvent.kt`（新增）, `src/main/kotlin/com/mahjongplay/game/GameEventListener.kt` 或既有介面宣告處。
- Interfaces: 新增 `DrawSource`（`LIVE_HEAD`、`SUPPLEMENT_TAIL`）、`DrawReason`（`INITIAL_DEAL`、`NORMAL_TURN`、`FLOWER_REPLACEMENT`、`KONG_REPLACEMENT`）與不可變 `TileDrawEvent`。事件帶有單局遞增 `sequence`、目標玩家、實際牌、來源、摸牌後活牌／補牌牆數量，以及鐵八墩補入動作。
- Work: 讓 `generateWall`、`drawLiveFor`、`drawSupplementFor` 與 `dealHands` 在變更手牌前後送出初始化、開始摸、花牌公開、摸牌完成與牌牆更新事件。規則核心仍先決定牌與牌牆資料；演出只消費事件，不能反過來選牌或決定胡牌。
- Verify: 新增 `TileDrawEventTest`，驗證一般摸牌、花牌連續補牌與槓後補牌的事件順序、來源與剩餘張數。

### Task 2: 將牌牆資料映射為可重現的四邊雙層位置

- Files: `src/main/kotlin/com/mahjongplay/display/WallLayout.kt`（新增）, `src/main/kotlin/com/mahjongplay/display/BoardRenderer.kt`, `src/test/kotlin/com/mahjongplay/display/WallLayoutTest.kt`（新增）。
- Interfaces: `WallLayout` 將 144 張牌配置成四邊各 18 墩、每墩上下兩張（每邊 36 張，合計 72 墩），並以 `WallSlot` 回傳牌頭、牌尾與補入鐵八墩時應移除／保留的顯示位置。
- Work: 依 `tableScale` 計算牌牆距離、雙層高度與固定每邊 18 墩，保留手牌、鳴牌、花牌區和棄牌區的安全距離。牌牆使用獨立的 `WALL_PIER_GAP = 0` 與最小防閃爍層距，不得沿用 `HAND_GAP` 或 `PADDING`；相鄰墩的中心距離恰為牌的寬度，因此視覺上密合成一整列。以本局 seed 或莊家座位決定純視覺開門點；它不影響 `liveWall`／`supplementWall` 的既有牌序。牌頭每次取最上層，牌尾補牌與「活牌牆尾端移一張入鐵八墩」同步更新位置。
- Verify: `WallLayoutTest` 驗證每邊恰有 18 墩／36 張，四邊合計 72 墩／144 張；同邊相鄰墩中心距離等於牌寬、層距等於牌高（僅容許防閃爍的極小誤差），以及牌頭順序、牌尾順序、補入鐵八墩與不同桌子縮放的邊界。

### Task 3: 實作牌牆與飛行牌渲染器

- Files: `src/main/kotlin/com/mahjongplay/display/WallRenderer.kt`（新增）, `src/main/kotlin/com/mahjongplay/display/BoardRenderer.kt`, `src/main/kotlin/com/mahjongplay/display/MahjongTileDisplay.kt`。
- Interfaces: `WallRenderer` 擁有所有牌牆／飛行 `ItemDisplay`，提供 `initialize(layout)`、`beginDraw(event)`、`completeDraw(event)`、`showFlower(event)`、`cancelAndClear()`；以 round/animation revision 拒絕過期排程。
- Work: 開局建立 144 張背面牌（雙層牌牆），從事件指定的牌頭或牌尾取走最上層牌，使用短距離插值飛到該玩家手牌的抽牌缺口。飛行途中及抵達前，其他玩家只看背面；擁有者到達後才看正面。配牌沿用同一機制但採四張一組的節奏，避免一次更新 64 次手牌造成閃爍。把所有顯示與互動實體納入同一 registry，結束／斷線／重載可完整移除。
- Verify: 將座標與動畫終點計算抽成純函式測試；在測試伺服器驗證 144 個牆牌、一次摸牌後牆牌減一且飛行牌消失、其他座位不會看見牌面。

### Task 4: 串接配牌、一般摸牌、補花與槓後補牌狀態機

- Files: `src/main/kotlin/com/mahjongplay/game/MahjongGame.kt`, `src/main/kotlin/com/mahjongplay/interaction/PaperGameBridge.kt`, `src/main/kotlin/com/mahjongplay/display/BoardRenderer.kt`。
- Interfaces: `PaperGameBridge` 接收抓牌事件並排程 renderer；`MahjongGame` 在每個動畫窗口後才進入該玩家的胡牌／槓／打牌選項，但不等待外部 callback 回傳規則結果。
- Work:
  - 開局依「每家四張 × 四輪，莊家再一張」順序演出。每一輪依既有 `seatOrderFromDealer` 的方向，逐家從牌頭連取四張；同組四張以 55 ms 間隔依序離牆、180 ms 飛到該家的未來手牌位置，組與組之間停 75–120 ms。四輪完成後，莊家單獨摸第 17 張並落在現有的摸牌間隔位置；整段預設約 6–8 秒。
  - 發出的牌在抵達前不直接重繪完整手牌，避免牌從牆上消失又瞬移進手牌；每組四張抵達後才合併到手牌顯示。擁有者於抵達時看正面，其他人只看飛行牌背與站立牌背。
  - 配牌途中若摸到花牌，該張先正面飛入花牌區並顯示補花提示；從牌尾補出的牌飛往原本的手牌空位。若補牌仍是花，完整重複「公開花牌 → 牌尾補牌」，且本組尚未完成前不發下一家的牌。
  - 一般回合從牌頭摸牌；牌抵達後才開啟自摸／槓／打牌互動並保留第 17 張間隔。
  - 明槓、暗槓、加槓全部從牌尾補；若補到花牌，先放花再繼續從牌尾補。
  - 海底、流局、胡牌、離桌、`/plugman reload` 都會使舊的動畫 revision 失效，並清理飛行牌與牌牆。
- Verify: 擴充 `FlowerReplacementTest` 與新增整合測試，覆蓋開局補花、連續補花、三種槓補牌、海底牌後無後續抓牌、結束時不再發送抓牌完成事件。

### Task 5: 加入玩家回饋與可調整動畫設定

- Files: `src/main/resources/config.yml`, `src/main/kotlin/com/mahjongplay/config/MahjongSettings.kt`, `src/main/kotlin/com/mahjongplay/model/MahjongRule.kt`, `src/main/kotlin/com/mahjongplay/interaction/ActionBarHUD.kt`, `src/main/kotlin/com/mahjongplay/interaction/PaperGameBridge.kt`。
- Interfaces: 新增全域顯示設定，例如 `display.draw-animation-ms`、`display.initial-deal-group-pause-ms`、`display.draw-sounds`；設定為 `0` 時保留正確規則流程但跳過等待與飛行動畫。
- Work: 行動列於抓牌時顯示「某家摸牌／補花／槓後補牌」與剩餘活牌數；給摸牌者、花牌公開與槓後補牌不同音效／粒子提示。Bot 的思考計時必須從抓牌動畫完成後才開始，避免動畫侵蝕玩家操作時間。以同一個設定套用於所有新桌，既有桌不因重讀被中途改變。
- Verify: `MahjongSettingsTest` 覆蓋缺省值、邊界值與 0 ms 快速模式；手動確認正常與快速模式都不會提早出現可打牌選項。

### Task 6: 完整驗收、效能與部署

- Files: `src/test/kotlin/com/mahjongplay/game/TileDrawEventTest.kt`（新增）, `src/test/kotlin/com/mahjongplay/display/WallLayoutTest.kt`（新增）, 相關既有測試。
- Work: 以固定牌牆腳本跑一局可重現情境：配牌含花、一般摸牌、吃碰後不摸牌、三種槓補牌、連續補花、最後一張與流局。檢查單桌最多約 144 個牆牌加少量飛行牌，且每次 `clearAllDisplays` 均歸零。確認玩家離桌與 PlugMan 重載期間不會從已取消 coroutine 產生新實體。
- Verify: `mvn clean test`、`mvn clean package`；將 JAR 放入 `plugins/` 後執行 `/plugman reload TaiwaneseMahjong`；在四人桌以資源包已載入的客戶端完成上述手動情境並檢查 `logs/latest.log` 無 TaiwaneseMahjong 例外。

## 明確排除於第一版

- 玩家手動擲骰、選擇開門點或以骰點改變實際亂數牌序。
- 對觀戰者揭露任何仍在手牌或飛行中的牌面。
- 為了動畫而改寫計分、吃碰槓優先序或胡牌判定。
