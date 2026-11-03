-- Gesty – baza SQLite (tworzona automatycznie przy starcie serwera)
-- Konto = użytkownik Firebase (uid). Logowanie wyłącznie przez Google.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    uid           TEXT PRIMARY KEY,                -- Firebase uid
    email         TEXT,
    display_name  TEXT,
    photo_url     TEXT,
    created_at    INTEGER NOT NULL,                -- ms od epoki (UTC)
    last_seen_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id            TEXT NOT NULL,                   -- UUID generowany przez telefon
    uid           TEXT NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    name          TEXT NOT NULL,                   -- np. "samsung SM-S911B"
    app_version   TEXT,
    last_seen_at  INTEGER NOT NULL,
    created_at    INTEGER NOT NULL,
    PRIMARY KEY (uid, id)
);

CREATE TABLE IF NOT EXISTS gesture_mappings (
    uid           TEXT NOT NULL REFERENCES users(uid) ON DELETE CASCADE,
    gesture       TEXT NOT NULL,                   -- = GestureType.name
    action        TEXT NOT NULL,                   -- = ActionType.name
    app_package   TEXT,                            -- tylko dla OPEN_APP
    app_label     TEXT,
    updated_at    INTEGER NOT NULL,                -- znacznik czasu z telefonu (ostatni zapis wygrywa)
    device_id     TEXT,
    PRIMARY KEY (uid, gesture),
    CHECK ((action = 'OPEN_APP') = (app_package IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS user_settings (
    uid                  TEXT PRIMARY KEY REFERENCES users(uid) ON DELETE CASCADE,
    fps                  INTEGER NOT NULL CHECK (fps BETWEEN 5 AND 30),
    pause_on_screen_off  INTEGER NOT NULL CHECK (pause_on_screen_off IN (0, 1)),
    doom_scroll          INTEGER NOT NULL CHECK (doom_scroll IN (0, 1)),
    mouth_level          INTEGER NOT NULL CHECK (mouth_level BETWEEN 0 AND 2),
    updated_at           INTEGER NOT NULL,
    device_id            TEXT
);
