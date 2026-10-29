package com.gesturecontrol.app.model

enum class ActionGroup(val title: String) {
    NONE(""),
    MEDIA("Muzyka i dźwięk"),
    TOUCH("Przewijanie i dotyk"),
    APPS("Aplikacje"),
    SYSTEM("System"),
}

/** Akcje, które można przypisać do gestu. */
enum class ActionType(
    val emoji: String,
    val label: String,
    val group: ActionGroup,
    val needsAccessibility: Boolean,
) {
    NONE("⛔", "Brak akcji", ActionGroup.NONE, false),

    MEDIA_NEXT("⏭", "Następna piosenka", ActionGroup.MEDIA, false),
    MEDIA_PREVIOUS("⏮", "Poprzednia piosenka", ActionGroup.MEDIA, false),
    MEDIA_PLAY_PAUSE("⏯", "Play / pauza", ActionGroup.MEDIA, false),
    VOLUME_UP("🔊", "Głośniej", ActionGroup.MEDIA, false),
    VOLUME_DOWN("🔉", "Ciszej", ActionGroup.MEDIA, false),

    SWIPE_UP("⬆️", "Następny reels / TikTok / Short", ActionGroup.TOUCH, true),
    SWIPE_DOWN("⬇️", "Poprzedni reels", ActionGroup.TOUCH, true),
    SWIPE_LEFT("⬅️", "Przesuń w lewo (następne story)", ActionGroup.TOUCH, true),
    SWIPE_RIGHT("➡️", "Przesuń w prawo", ActionGroup.TOUCH, true),
    DOUBLE_TAP("❤️", "Podwójne tapnięcie (polub)", ActionGroup.TOUCH, true),
    TAP_CENTER("👆", "Tapnięcie w środek ekranu", ActionGroup.TOUCH, true),

    OPEN_APP("📱", "Otwórz aplikację…", ActionGroup.APPS, true),

    BACK("↩️", "Wstecz", ActionGroup.SYSTEM, true),
    HOME("🏠", "Ekran główny", ActionGroup.SYSTEM, true),
    RECENTS("🗂", "Ostatnie aplikacje", ActionGroup.SYSTEM, true),
    NOTIFICATIONS("🔔", "Powiadomienia", ActionGroup.SYSTEM, true),
    SCREENSHOT("📸", "Zrzut ekranu", ActionGroup.SYSTEM, true),
    LOCK_SCREEN("🔒", "Zablokuj ekran", ActionGroup.SYSTEM, true);

    val display: String get() = "$emoji $label"
}
