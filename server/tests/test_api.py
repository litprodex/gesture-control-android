"""Testy bez prawdziwego Firebase: weryfikator tokenów jest podmieniony."""
import sys
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app as server  # noqa: E402

TOKENS = {"tok-a": {"uid": "user-a", "email": "a@example.com", "name": "Ala"},
          "tok-b": {"uid": "user-b", "email": "b@example.com", "name": "Bartek"}}


def fake_verifier(token):
    if token not in TOKENS:
        raise HTTPException(401, "Nieprawidłowy lub wygasły token")
    return TOKENS[token]


@pytest.fixture
def client(tmp_path):
    app = server.create_app(db_path=str(tmp_path / "t.db"), verifier=fake_verifier)
    with TestClient(app) as c:
        yield c


A = {"Authorization": "Bearer tok-a", "X-Device-Id": "phone-aaaa-1"}
A2 = {"Authorization": "Bearer tok-a", "X-Device-Id": "phone-aaaa-2"}
B = {"Authorization": "Bearer tok-b"}


def test_health_bez_tokenu(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["db"] is True


@pytest.mark.parametrize("path", ["/me", "/sync", "/devices", "/settings"])
def test_401_bez_tokenu(client, path):
    assert client.get(path).status_code == 401


def test_401_zly_token(client):
    assert client.get("/me", headers={"Authorization": "Bearer zly"}).status_code == 401
    assert client.get("/me", headers={"Authorization": "Basic abc"}).status_code == 401


def test_me(client):
    r = client.get("/me", headers=A)
    assert r.status_code == 200
    assert r.json()["email"] == "a@example.com" and r.json()["mappings"] == 0


def test_sync_scalanie_ostatni_wygrywa(client):
    body = {"mappings": [
        {"gesture": "THUMB_UP", "action": "DOUBLE_TAP", "updated_at": 100},
        {"gesture": "VICTORY", "action": "OPEN_APP", "app_package": "com.spotify.music",
         "app_label": "Spotify", "updated_at": 100},
    ], "settings": {"fps": 12, "pause_on_screen_off": True, "doom_scroll": False, "mouth_level": 1, "updated_at": 100}}
    r = client.post("/sync", json=body, headers=A)
    assert r.status_code == 200
    assert len(r.json()["mappings"]) == 2 and r.json()["settings"]["fps"] == 12

    # drugi telefon ze starszymi danymi nie nadpisuje
    old = {"mappings": [{"gesture": "THUMB_UP", "action": "BACK", "updated_at": 50}],
           "settings": {"fps": 30, "pause_on_screen_off": False, "doom_scroll": True, "mouth_level": 2, "updated_at": 50}}
    r = client.post("/sync", json=old, headers=A2).json()
    assert {m["gesture"]: m["action"] for m in r["mappings"]}["THUMB_UP"] == "DOUBLE_TAP"
    assert r["settings"]["fps"] == 12

    # nowsza zmiana wygrywa
    r = client.put("/mappings/THUMB_UP", json={"action": "HOME", "updated_at": 200}, headers=A2)
    assert r.json()["action"] == "HOME"
    assert {m["gesture"]: m["action"] for m in client.get("/sync", headers=A).json()["mappings"]}["THUMB_UP"] == "HOME"


def test_dane_oddzielone_per_uid(client):
    client.put("/mappings/SMILE", json={"action": "SCREENSHOT", "updated_at": 1}, headers=A)
    assert client.get("/sync", headers=B).json()["mappings"] == []


def test_walidacja(client):
    assert client.put("/mappings/NIE_MA", json={"action": "HOME", "updated_at": 1}, headers=A).status_code == 422
    assert client.put("/mappings/SMILE", json={"action": "NIE_MA", "updated_at": 1}, headers=A).status_code == 422
    assert client.put("/mappings/SMILE", json={"action": "OPEN_APP", "updated_at": 1}, headers=A).status_code == 422
    bad = {"fps": 99, "pause_on_screen_off": True, "doom_scroll": False, "mouth_level": 1, "updated_at": 1}
    assert client.put("/settings", json=bad, headers=A).status_code == 422


def test_open_app_czysci_pakiet_przy_zmianie(client):
    client.put("/mappings/SMILE", json={"action": "OPEN_APP", "app_package": "x.y", "app_label": "Y", "updated_at": 1}, headers=A)
    r = client.put("/mappings/SMILE", json={"action": "HOME", "app_package": "x.y", "updated_at": 2}, headers=A)
    assert r.status_code == 200 and r.json()["app_package"] is None


def test_urzadzenia_i_usuniecie_konta(client):
    assert client.put("/devices/phone-aaaa-1", json={"name": "Samsung S23", "app_version": "1.6"}, headers=A).status_code == 200
    assert client.get("/devices", headers=A).json()[0]["name"] == "Samsung S23"
    assert client.get("/devices", headers=B).json() == []
    client.put("/mappings/SMILE", json={"action": "HOME", "updated_at": 1}, headers=A)
    assert client.delete("/me", headers=A).status_code == 204
    assert client.get("/sync", headers=A).json()["mappings"] == []
    assert client.get("/devices", headers=A).json() == []


def test_reset_przypisan(client):
    client.put("/mappings/SMILE", json={"action": "HOME", "updated_at": 1}, headers=A)
    assert client.delete("/mappings", headers=A).status_code == 204
    assert client.get("/sync", headers=A).json()["mappings"] == []


def test_bez_konfiguracji_firebase_503(tmp_path):
    app = server.create_app(db_path=str(tmp_path / "x.db"), verifier=server.FirebaseVerifier(str(tmp_path / "brak.json")))
    with TestClient(app) as c:
        assert c.get("/health").json()["firebase"] is False
        assert c.get("/me").status_code == 401                       # bez tokenu nadal 401
        assert c.get("/me", headers={"Authorization": "Bearer x"}).status_code == 503
