# Wdrożenie na VPS

Na serwerze działają dwie usługi systemd:

| Usługa | Plik | Rola |
|---|---|---|
| `gesty` | `gesty.service` | uvicorn (2 procesy) na `127.0.0.1:8000`, użytkownik `gesty` |
| `gesty-tunnel` | `gesty-tunnel.service` | `cloudflared` wystawia API jako HTTPS pod `https://….trycloudflare.com` |

Nie ma Caddy ani nginx przed API. Caddy na tej maszynie obsługuje inną aplikację i Gesty go nie dotyczą.

```text
/opt/gesty/server   kod (ten katalog), .venv, service-account.json
/opt/gesty/data     gesty.db (SQLite, WAL)
/opt/gesty/backup   kopie bazy i poprzednich wersji app.py
```

## Najważniejsze: nie restartuj tunelu

Szybki tunel dostaje nowy losowy adres przy każdym starcie. Nowy adres oznacza podmianę `API_BASE_URL` w aplikacji i nowe APK.

`gesty-tunnel` ma `Requires=gesty.service`, więc tunel restartują **oba** te polecenia:

```bash
systemctl restart gesty
systemctl restart gesty-tunnel
```

## Aktualizacja kodu bez zmiany adresu

Uvicorn po `SIGHUP` wymienia procesy robocze po kolei, a nowe procesy wczytują świeży `app.py`. Usługa systemd się nie restartuje, więc tunel zostaje bez zmian.

```bash
# z komputera, w katalogu server/
scp app.py root@157.90.165.142:/tmp/gesty-app.py.new

# na serwerze
D=$(date +%F-%H%M%S)
sqlite3 /opt/gesty/data/gesty.db ".backup /opt/gesty/backup/gesty-$D.db"
cp -p /opt/gesty/server/app.py /opt/gesty/backup/app.py-$D
sed -i 's/\r$//' /tmp/gesty-app.py.new
install -o gesty -g gesty -m 644 /tmp/gesty-app.py.new /opt/gesty/server/app.py
kill -HUP "$(systemctl show gesty -p MainPID --value)"

# sprawdzenie
sleep 5
curl -s 127.0.0.1:8000/health
systemctl show gesty-tunnel -p ActiveEnterTimestamp   # data ma się NIE zmienić
journalctl -u gesty -n 20 --no-pager
```

Gdy zmienia się `schema.sql`, każdy nowy proces sam wykona `CREATE TABLE IF NOT EXISTS`. Zmiany istniejących tabel (np. nowa kolumna) trzeba dopisać jako migrację, bo `IF NOT EXISTS` ich nie zrobi.

Zmiana pakietów w `requirements.txt` też przechodzi przez `SIGHUP`: najpierw `/opt/gesty/server/.venv/bin/pip install -r requirements.txt`, potem sygnał.

## Nowa akcja albo gest w aplikacji

Serwer odrzuca nieznane nazwy z `ActionType.kt` i `GestureType.kt`, a jedna nieznana akcja psuje cały `POST /sync` (błąd 422). Przy dodaniu akcji w aplikacji dopisz ją do `ACTIONS` w `app.py` i wdroż serwer **przed** rozdaniem nowego APK. Test `test_akcje_zgodne_z_aplikacja` wyłapuje taki rozjazd.

## Adres tunelu

```bash
journalctl -u gesty-tunnel --no-pager | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | tail -1
```

## Kopie bazy

`backup.sh` robi kopię do `/opt/gesty/backup` i kasuje starsze niż 14 dni. Nie jest jeszcze w cronie. Aby go włączyć (`crontab -e` jako root):

```text
15 3 * * * /opt/gesty/server/deploy/backup.sh
```
