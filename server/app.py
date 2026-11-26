"""
Gesty – serwer API.

Uruchomienie lokalne:
    pip install -r requirements.txt
    export FIREBASE_CREDENTIALS=/ścieżka/do/service-account.json
    uvicorn app:app --reload

Każde zapytanie (poza /health) wymaga nagłówka:
    Authorization: Bearer <Firebase ID token>
Opcjonalnie telefon wysyła X-Device-Id (UUID), żeby było wiadomo, skąd przyszła zmiana.
"""
from __future__ import annotations

import os
import sqlite3
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Callable, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field, model_validator

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = os.environ.get("GESTY_DB", str(BASE_DIR / "gesty.db"))
CREDENTIALS = os.environ.get("FIREBASE_CREDENTIALS", str(BASE_DIR / "service-account.json"))

# Muszą zgadzać się z enumami w aplikacji (model/GestureType.kt, model/ActionType.kt)
GESTURES = {
    "MOUTH_OPEN_ONCE", "MOUTH_OPEN_TWICE", "MOUTH_OPEN_THRICE", "BROWS_UP", "SMILE",
    "THUMB_UP", "THUMB_DOWN", "VICTORY", "MIDDLE_FINGER", "OPEN_PALM", "CLOSED_FIST",
    "POINTING_UP", "I_LOVE_YOU",
}
ACTIONS = {
    "NONE", "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_PLAY_PAUSE", "VOLUME_UP", "VOLUME_DOWN",
    "SWIPE_UP", "SWIPE_DOWN", "SWIPE_LEFT", "SWIPE_RIGHT", "DOUBLE_TAP", "TAP_CENTER",
    "OPEN_APP", "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "SCREENSHOT", "LOCK_SCREEN",
    "TAKE_SELFIE",
}


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------- Firebase

class FirebaseVerifier:
    """Weryfikuje Firebase ID token. Bez pliku service account serwer działa, ale zwraca 503 na chronionych endpointach."""

    def __init__(self, credentials_path: str):
        self.ready = False
        self.error: Optional[str] = None
        try:
            import firebase_admin
            from firebase_admin import auth, credentials

            if not Path(credentials_path).is_file():
                self.error = f"brak pliku {credentials_path}"
                return
            if not firebase_admin._apps:
                firebase_admin.initialize_app(credentials.Certificate(credentials_path))
            self._auth = auth
            self.ready = True
        except Exception as e:  # zły plik itp.
            self.error = str(e)

    def __call__(self, token: str) -> dict:
        if not self.ready:
            raise HTTPException(503, f"Firebase nie jest skonfigurowany: {self.error}")
        try:
            return self._auth.verify_id_token(token)
        except Exception:
            raise HTTPException(401, "Nieprawidłowy lub wygasły token")

    def delete_user(self, uid: str) -> None:
        if self.ready:
            try:
                self._auth.delete_user(uid)
            except Exception:
                pass


# ---------------------------------------------------------------- Baza

def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db(path: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with connect(path) as conn:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.executescript((BASE_DIR / "schema.sql").read_text(encoding="utf-8"))


def get_db(request: Request):
    conn = connect(request.app.state.db_path)
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------- Modele

class Mapping(BaseModel):
    gesture: str
    action: str
    app_package: Optional[str] = Field(None, max_length=255)
    app_label: Optional[str] = Field(None, max_length=120)
    updated_at: int = Field(ge=0)

    @model_validator(mode="after")
    def check(self):
        if self.gesture not in GESTURES:
            raise ValueError(f"nieznany gest: {self.gesture}")
        if self.action not in ACTIONS:
            raise ValueError(f"nieznana akcja: {self.action}")
        if self.action == "OPEN_APP" and not self.app_package:
            raise ValueError("app_package jest wymagany dla OPEN_APP")
        if self.action != "OPEN_APP":
            self.app_package = None
            self.app_label = None
        return self


class MappingBody(BaseModel):
    action: str
    app_package: Optional[str] = None
    app_label: Optional[str] = None
    updated_at: int = Field(ge=0)


class Settings(BaseModel):
    fps: int = Field(ge=5, le=30)
    pause_on_screen_off: bool
    doom_scroll: bool
    mouth_level: int = Field(ge=0, le=2)
    updated_at: int = Field(ge=0)


class SyncBody(BaseModel):
    mappings: list[Mapping] = Field(default_factory=list, max_length=100)
    settings: Optional[Settings] = None


class DeviceBody(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    app_version: Optional[str] = Field(None, max_length=20)


class User(BaseModel):
    uid: str
    email: Optional[str] = None
    name: Optional[str] = None
    picture: Optional[str] = None


# ---------------------------------------------------------------- Aplikacja

def create_app(db_path: str = DB_PATH, verifier: Optional[Callable[[str], dict]] = None) -> FastAPI:
    verifier = verifier or FirebaseVerifier(CREDENTIALS)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        init_db(db_path)
        yield

    app = FastAPI(title="Gesty API", version="1.0", lifespan=lifespan)
    app.state.db_path = db_path
    app.state.verifier = verifier

    def current_user(
        authorization: Optional[str] = Header(None),
        db: sqlite3.Connection = Depends(get_db),
    ) -> User:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(401, "Brak tokenu", headers={"WWW-Authenticate": "Bearer"})
        claims = verifier(authorization.split(" ", 1)[1].strip())
        user = User(uid=claims["uid"] if "uid" in claims else claims["sub"],
                    email=claims.get("email"), name=claims.get("name"), picture=claims.get("picture"))
        t = now_ms()
        db.execute(
            """INSERT INTO users(uid, email, display_name, photo_url, created_at, last_seen_at)
               VALUES (?,?,?,?,?,?)
               ON CONFLICT(uid) DO UPDATE SET email=excluded.email, display_name=excluded.display_name,
                   photo_url=excluded.photo_url, last_seen_at=excluded.last_seen_at""",
            (user.uid, user.email, user.name, user.picture, t, t),
        )
        return user

    # ---------- pomocnicze

    def upsert_mapping(db, uid: str, m: Mapping, device: Optional[str]) -> None:
        # Ostatni zapis wygrywa: nadpisujemy tylko, jeśli zmiana jest nowsza.
        db.execute(
            """INSERT INTO gesture_mappings(uid, gesture, action, app_package, app_label, updated_at, device_id)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(uid, gesture) DO UPDATE SET action=excluded.action, app_package=excluded.app_package,
                   app_label=excluded.app_label, updated_at=excluded.updated_at, device_id=excluded.device_id
               WHERE excluded.updated_at > gesture_mappings.updated_at""",
            (uid, m.gesture, m.action, m.app_package, m.app_label, m.updated_at, device),
        )

    def upsert_settings(db, uid: str, s: Settings, device: Optional[str]) -> None:
        db.execute(
            """INSERT INTO user_settings(uid, fps, pause_on_screen_off, doom_scroll, mouth_level, updated_at, device_id)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(uid) DO UPDATE SET fps=excluded.fps, pause_on_screen_off=excluded.pause_on_screen_off,
                   doom_scroll=excluded.doom_scroll, mouth_level=excluded.mouth_level,
                   updated_at=excluded.updated_at, device_id=excluded.device_id
               WHERE excluded.updated_at > user_settings.updated_at""",
            (uid, s.fps, int(s.pause_on_screen_off), int(s.doom_scroll), s.mouth_level, s.updated_at, device),
        )

    def touch_device(db, uid: str, device: Optional[str]) -> None:
        if device:
            db.execute("UPDATE devices SET last_seen_at=? WHERE uid=? AND id=?", (now_ms(), uid, device))

    def state(db, uid: str) -> dict:
        rows = db.execute(
            "SELECT gesture, action, app_package, app_label, updated_at FROM gesture_mappings WHERE uid=? ORDER BY gesture",
            (uid,),
        ).fetchall()
        s = db.execute(
            "SELECT fps, pause_on_screen_off, doom_scroll, mouth_level, updated_at FROM user_settings WHERE uid=?",
            (uid,),
        ).fetchone()
        return {
            "mappings": [dict(r) for r in rows],
            "settings": None if s is None else {
                "fps": s["fps"], "pause_on_screen_off": bool(s["pause_on_screen_off"]),
                "doom_scroll": bool(s["doom_scroll"]), "mouth_level": s["mouth_level"],
                "updated_at": s["updated_at"],
            },
            "server_time": now_ms(),
        }

    # ---------- endpointy

    @app.get("/health")
    def health():
        ok_db = True
        try:
            with connect(db_path) as c:
                c.execute("SELECT 1")
        except Exception:
            ok_db = False
        return {"status": "ok" if ok_db else "error", "db": ok_db,
                "firebase": bool(getattr(verifier, "ready", True)), "version": app.version}

    @app.get("/me")
    def me(user: User = Depends(current_user), db=Depends(get_db)):
        row = db.execute("SELECT * FROM users WHERE uid=?", (user.uid,)).fetchone()
        devices = db.execute("SELECT COUNT(*) FROM devices WHERE uid=?", (user.uid,)).fetchone()[0]
        mappings = db.execute("SELECT COUNT(*) FROM gesture_mappings WHERE uid=?", (user.uid,)).fetchone()[0]
        return {"uid": user.uid, "email": user.email, "name": user.name, "picture": user.picture,
                "created_at": row["created_at"] if row else None,
                "devices": devices, "mappings": mappings}

    @app.delete("/me", status_code=204)
    def delete_me(user: User = Depends(current_user), db=Depends(get_db)):
        db.execute("DELETE FROM users WHERE uid=?", (user.uid,))  # CASCADE usuwa resztę
        db.commit()
        if hasattr(verifier, "delete_user"):
            verifier.delete_user(user.uid)

    @app.get("/sync")
    def get_sync(user: User = Depends(current_user), db=Depends(get_db),
                 x_device_id: Optional[str] = Header(None)):
        touch_device(db, user.uid, x_device_id)
        return state(db, user.uid)

    @app.post("/sync")
    def post_sync(body: SyncBody, user: User = Depends(current_user), db=Depends(get_db),
                  x_device_id: Optional[str] = Header(None)):
        """Telefon wysyła cały swój stan, serwer scala (nowsze wygrywa) i zwraca wynik."""
        for m in body.mappings:
            upsert_mapping(db, user.uid, m, x_device_id)
        if body.settings:
            upsert_settings(db, user.uid, body.settings, x_device_id)
        touch_device(db, user.uid, x_device_id)
        return state(db, user.uid)

    @app.put("/mappings/{gesture}")
    def put_mapping(gesture: str, body: MappingBody, user: User = Depends(current_user), db=Depends(get_db),
                    x_device_id: Optional[str] = Header(None)):
        try:
            m = Mapping(gesture=gesture, **body.model_dump())
        except ValueError as e:
            raise HTTPException(422, str(e))
        upsert_mapping(db, user.uid, m, x_device_id)
        row = db.execute(
            "SELECT gesture, action, app_package, app_label, updated_at FROM gesture_mappings WHERE uid=? AND gesture=?",
            (user.uid, gesture),
        ).fetchone()
        return dict(row)

    @app.delete("/mappings", status_code=204)
    def reset_mappings(user: User = Depends(current_user), db=Depends(get_db)):
        """„Przywróć domyślne” – kasuje przypisania z serwera."""
        db.execute("DELETE FROM gesture_mappings WHERE uid=?", (user.uid,))

    @app.get("/settings")
    def get_settings(user: User = Depends(current_user), db=Depends(get_db)):
        s = state(db, user.uid)["settings"]
        if s is None:
            raise HTTPException(404, "Brak zapisanych ustawień")
        return s

    @app.put("/settings")
    def put_settings(body: Settings, user: User = Depends(current_user), db=Depends(get_db),
                     x_device_id: Optional[str] = Header(None)):
        upsert_settings(db, user.uid, body, x_device_id)
        return state(db, user.uid)["settings"]

    @app.get("/devices")
    def list_devices(user: User = Depends(current_user), db=Depends(get_db)):
        rows = db.execute(
            "SELECT id, name, app_version, last_seen_at, created_at FROM devices WHERE uid=? ORDER BY last_seen_at DESC",
            (user.uid,),
        ).fetchall()
        return [dict(r) for r in rows]

    @app.put("/devices/{device_id}")
    def register_device(device_id: str, body: DeviceBody, user: User = Depends(current_user), db=Depends(get_db)):
        if not (8 <= len(device_id) <= 64):
            raise HTTPException(422, "Nieprawidłowe id urządzenia")
        t = now_ms()
        db.execute(
            """INSERT INTO devices(id, uid, name, app_version, last_seen_at, created_at) VALUES (?,?,?,?,?,?)
               ON CONFLICT(uid, id) DO UPDATE SET name=excluded.name, app_version=excluded.app_version,
                   last_seen_at=excluded.last_seen_at""",
            (device_id, user.uid, body.name, body.app_version, t, t),
        )
        return {"id": device_id, "name": body.name, "app_version": body.app_version, "last_seen_at": t}

    @app.delete("/devices/{device_id}", status_code=204)
    def delete_device(device_id: str, user: User = Depends(current_user), db=Depends(get_db)):
        db.execute("DELETE FROM devices WHERE uid=? AND id=?", (user.uid, device_id))

    return app


app = create_app()
