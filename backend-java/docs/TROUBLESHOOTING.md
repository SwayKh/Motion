# Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Container exits with `invalid encryption key` | `MOTION_ENCRYPTION_KEY` is not a valid base64 32-byte value. Regenerate with `openssl rand -base64 32`. |
| Media returns `failed to read file` or streams garbage | The encryption key no longer matches the data (e.g. new key on old volumes). Restore the `.env` from the backup that matches the data, or restore the whole backup set. |
| Login always says “too many requests” | `TRUSTED_PROXIES` is unset, so the proxy's IP is being rate-limited as one client. Set it to the proxy's address/CIDR. |
| Browser fetches blocked by CORS | Add your exact browser origin (scheme + host + port) to `TRUSTED_ORIGINS`. |
| Jobs stuck `queued` | Check `docker compose logs motion-java` for FFmpeg availability (`ffmpeg -version` inside the image); ensure worker count > 0; restart the app — interrupted jobs are requeued automatically. |
| Job fails with `video processing produced no output` / probe error | The source is corrupt or has no decodable streams. Re-upload; check `MAX_DURATION_SECONDS`, `MAX_WIDTH`, `MAX_HEIGHT`. |
| Upload fails with 413 | Multipart ceiling: raise `MOTION_MULTIPART_MAX_FILE_SIZE` / `MOTION_MULTIPART_MAX_REQUEST_SIZE` and `MAX_UPLOAD_BYTES` consistently. |
| Health check never turns healthy | `docker compose logs motion-java`; on very slow disks the 30 s start period can be too short — increase `start_period` in the compose file. |
| Database locked / SQLITE_BUSY | More than one Motion instance is using the same `app.db`. Run exactly one instance per database (`MOTION_DB_POOL_SIZE=1` is not a workaround). |
| Password reset emails not arriving | Check `SMTP_*` settings; Gmail requires an App Password (not the account password). Without `SMTP_HOST`, no mail is sent. |
| Reset/verification link token invalid | Tokens expire (`EMAIL_VERIFICATION_TTL`, reset TTL 15 min); request a new one. Rate limits apply. |
| Disk filling up | Terminal videos older than `RETENTION` are swept hourly; lower `RETENTION` or raise cleanup cadence; check `docker system df` for unrelated growth. |
| Restored backup can't log in | The restored `app.db` and `.env` must come from the same backup set; mixing an old database with a new key breaks encrypted reads. |

Diagnostics:

```bash
docker compose -f docker-compose.java.yml logs -f motion-java
docker exec motion-java curl -fsS http://localhost:8080/health
docker exec motion-java java -jar /app/app.jar backup-db /app/data/check.db   # consistent snapshot
sqlite3 <volume>/app.db "PRAGMA integrity_check;"
```
