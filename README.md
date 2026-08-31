# 🀄 TaiwaneseMahjong — 台灣十六張麻將

> 適用於 **Paper / Purpur 1.20.x - 1.21.x** 的頂級原生 3D 台灣十六張麻將伺服器插件。  
> 玩家直接透過右鍵與實體模型進行 3D 互動，**純伺服器端運作，客戶端完全無需安裝任何 Forge / Fabric Mod！**

---

## 🌟 核心特色

### 🀄 1. 正宗台灣十六張麻將規則
* **完整 144 張牌山**：包含萬、筒、條、東南西北中發白，以及春夏秋冬、梅蘭竹菊八張花牌。
* **嚴謹牌局流程**：摸牌、補花、吃、碰、明槓、暗槓、加槓、聽牌與榮和/自摸。
* **標準台灣底台制**：每筆結算嚴格遵循 `底金 + 台數 × 每台積分/金額`。
* **豐富台數番種**：
  * 莊家、連莊（連 $n$ 拉 $n$ 計 $2n+1$ 台）、門清、自摸、門清自摸（3台）。
  * 平胡、碰碰胡、混一色、清一色、字一色、三暗刻、四暗刻、五暗刻。
  * 大三元、小三元、大四喜、小四喜、八仙過海、七搶一、天胡、地胡、海底撈月、槓上開花等。

### 💰 2. Vault 經濟系統整合（真金對戰 vs 休閒娛樂）
* **真金對戰模式**：無縫串接 Vault 經濟插件，自動進行進場門檻檢查、每局結算即時扣款與派彩。
* **防惡意逃跑與擊飛機制**：支援擊飛（破產提前結束），玩家斷線自動進入安全代打結算。
* **休閒娛樂模式**：提供獨立虛擬積分局，適合練牌與娛樂交流。

### 🏆 3. 天梯雀力 RP 評級與戰績系統
* **九段天梯雀力體系**：初段、二段 ➜ 九段 ➜ 雀聖 ➜ 雀神。
* **獨立戰績 GUI（`/mahjong stats`）**：圖形化展示總局數、胡牌率、自摸率、放銃率、最大台數、天梯 RP 與當前段位。

### 🤖 4. 智慧 AI 電腦補位與代打託管（Bot Takeover）
* **多階難度 Bot**：支援休閒、中等、高手等多種演算法風格。
* **無縫代打託管**：玩家超距、切換世界或斷線時，系統自動無縫切換為 🤖 代打模式，保障同桌玩家遊戲體驗。
* **手動代打開關**：玩家可隨時按 `F` 鍵或輸入 `/mahjong auto` 切換代打託管。

### 🎨 5. 沉浸式 3D 牌桌與立體浮字體
* **黑曜石深色透光毛玻璃浮字**：頂部俐落桌號徽章與賽制資訊行（對戰模式、圈數制、底台金額）。
* **3D 膠囊立體按鈕**：翡翠綠【✔ 準備】、琥珀金【▶ 開始】、青金石藍【⚙ 設定】。
* **即時摸牌與出牌**：右鍵手牌浮起選牌，再次右鍵打出；中央牌池與碰槓副露精準排布。

### 👁 6. 旁觀模式與即時防作弊控制
* 支援其他玩家在旁觀戰。
* 管理員或桌主可自由切換【允許看牌（明牌旁觀）】或【隱藏手牌（防作弊）】。
* **0 毫秒即時刷新**：遊戲進行中切換會即時重繪全場旁觀視野，無需重開局。

### 🛠 7. 全功能管理員控制台 GUI
* **Shift + 右鍵牌桌** 或輸入 `/mahjong admin gui [桌號]` 即可開啟管理面板：
  * 👤 檢視 4 個座位的玩家狀態（真人/Bot、在線/離線、準備、積分）。
  * 🥾 強制踢出指定玩家或機器人。
  * 🤖 手動為指定玩家切換／解除代打模式。
  * 👁 強制切換旁觀看牌模式（遊戲中即時生效）。
  * 🚀 強制開始對局、🛑 強制終止牌局、💥 強制銷毀牌桌。

### 🔄 8. 空桌自動恢復預設設定
* 當牌桌內所有真人玩家皆離開後，系統自動清除殘留 Bot 與房主，並將規則全面恢復為伺服器預設值（底 300 / 台 100 等）。

---

## 📜 指令一覽

### 🎮 玩家常用指令
| 指令 | 說明 | 權限節點 |
| :--- | :--- | :--- |
| `/mahjong join [桌號]` | 加入指定牌桌（或直接右鍵牌桌入座） | `mahjongplay.command.join` |
| `/mahjong leave` | 離開目前所在的牌桌 | `mahjongplay.command.leave` |
| `/mahjong ready` | 切換準備 / 取消準備狀態 | `mahjongplay.command.ready` |
| `/mahjong start` | 房主開始對局（可自動補足 Bot） | `mahjongplay.command.start` |
| `/mahjong settings` | 開啟牌桌規則設定 GUI（僅房主可修改） | `mahjongplay.command.settings` |
| `/mahjong stats [玩家]` | 開啟歷史戰績與雀力段位面板 | `mahjongplay.command.stats` |
| `/mahjong auto` | 切換自己為 🤖 代打託管模式 | `mahjongplay.command.auto` |
| `/mahjong list` | 查看全伺服器目前運作中的麻將桌 | `mahjongplay.command.list` |

### 🛠 管理員指令
| 指令 | 說明 | 權限節點 |
| :--- | :--- | :--- |
| `/mahjong table` | 在管理員面前建立全新麻將桌 | `mahjongplay.command.create` |
| `/mahjong admin gui [桌號]` | 開啟牌桌管理員控制台 GUI | `mahjongplay.admin` |
| `/mahjong admin resetall` | 將全服所有牌桌恢復預設設定 | `mahjongplay.admin` |
| `/mahjong admin stop [桌號]` | 強制終止進行中的牌局 | `mahjongplay.admin` |
| `/mahjong admin destroy [桌號]` | 銷毀牌桌實體與存檔 | `mahjongplay.admin` |
| `/mahjong admin kick <玩家>` | 強制踢出牌桌上的玩家 | `mahjongplay.admin` |
| `/mahjong admin reload` | 重新載入 `config.yml` 設定檔 | `mahjongplay.admin` |
| `/mahjong admin resetstats <玩家>` | 重置指定玩家的麻將歷史戰績 | `mahjongplay.admin` |

---

## ⚙ 設定檔說明 (`config.yml`)

```yaml
defaults:
  # 預設圈數（一將為 4 圈 16 局；快速局可設 1 局或 1/4 圈）
  game-length: TWO_WIND
  rounds: 16

  # 台灣底台制預設值
  base-points: 300       # 預設底金
  points-per-tai: 100    # 預設每台積分/金額
  minimum-tai: NONE      # 最低起胡台數限制
  thinking-time: NORMAL  # 出牌限時（NORMAL = 9秒）
  flowers: true          # 是否啟用花牌補牌
  bot-response-ms: 3000  # Bot 作答思考時間

economy:
  enabled: true          # 是否啟用 Vault 經濟系統
  min-balance: 0.0       # 最低進場餘額（0 = 自動依底台動態計算）
  bankruptcy-check: true # 破產擊飛檢查

display:
  table-scale: 1.8       # 牌桌 3D 模型縮放比例
  hand-distance: 1.30    # 手牌距離牌桌中心距離
  draw-animation-ms: 240 # 摸牌動畫時間
  opening-dice-animation-ms: 2200 # 開局擲骰動畫時間
```

---

## 🎨 資源包與 ItemsAdder 設定

本插件使用 Minecraft 1.20+ 原生 `ItemDisplay` 與 Custom Model Data（CMD）：

* **麻將牌模型**：Custom Model Data `900001` ～ `900043`
* **麻將桌模型**：Custom Model Data `900044`
* **座椅模型**：Custom Model Data `900045`

### ItemsAdder 安裝方式：
1. 將專案中的 `itemsadder-content/mahjongcraft` 複製至伺服器 `plugins/ItemsAdder/contents/`。
2. 在伺服器輸入 `/iazip` 重新生成材質包。
3. 玩家載入資源包後即可享受完整的 3D 麻將體驗！

---

## 🔨 建置與開發

### 環境需求
* **Java 21+**
* **Maven 3.9+**

### 編譯指令
```bash
# 執行單元測試
mvn clean test

# 編譯並打包出 Shaded JAR
mvn clean package
```
編譯產物將生成於 `target/TaiwaneseMahjong-2.0.0.jar`。

---

## 📄 開源授權

本專案採用 **MIT 授權條款** 開源。