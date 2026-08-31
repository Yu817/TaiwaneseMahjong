package com.mahjongplay.game

enum class BotDifficulty(val displayName: String, val levelTag: String) {
    LOW("初級", "低"),
    MEDIUM("中級", "中"),
    HIGH("高級", "高");

    companion object {
        fun fromString(str: String?): BotDifficulty {
            return when (str?.lowercase()?.trim()) {
                "low", "easy", "1", "低", "初級", "初" -> LOW
                "high", "hard", "expert", "3", "高", "高級", "高階" -> HIGH
                else -> MEDIUM
            }
        }
    }
}
