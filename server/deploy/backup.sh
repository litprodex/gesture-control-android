#!/bin/sh
# Codzienna kopia bazy (crontab -e:  15 3 * * * /opt/gesty/server/deploy/backup.sh)
mkdir -p /opt/gesty/backup
sqlite3 /opt/gesty/data/gesty.db ".backup '/opt/gesty/backup/gesty-$(date +%F).db'"
find /opt/gesty/backup -name 'gesty-*.db' -mtime +14 -delete
