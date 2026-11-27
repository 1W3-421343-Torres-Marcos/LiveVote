# Runbook de Producción — LiveVote

Guía operativa para desplegar, monitorear, respaldar y verificar **LiveVote** en modo producción usando [docker-compose.yml](../docker-compose.yml) + [docker-compose.prod.yml](../docker-compose.prod.yml) (sin `docker-compose.override.yml`).

---

## 1. Preparación de variables y secretos (`.env.prod`)

1. Copiar la plantilla y **nunca versionar** `.env.prod` (ignorado en `.gitignore`):

   ```bash
   cp .env.prod.example .env.prod
   ```

2. Generar un secreto JWT aleatorio de 48 bytes y contraseñas fuertes distintas para `postgres-votes`, `postgres-results` y `rabbitmq`:

   ```bash
   openssl rand -base64 48
   ```

3. Configurar `APP_VERSION` con el tag semántico publicado en GHCR (ej. `0.7.1` o `0.8.0`, nunca `latest`).
   - Todas las variables críticas usan validación `${VAR:?...}` en `docker-compose.prod.yml`: si falta alguna, Compose aborta antes de levantar contenedores.

---

## 2. Medición real de RAM y sizing por entorno

Medición real obtenida con `docker stats --no-stream` sobre las imágenes multi-arch de GHCR (`0.7.1`, Distroless en Java 17 + `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0`) bajo tráfico de smoke test y stream SSE abierto (`OOMKilled=false` en los 7 contenedores):

| Contenedor | `MEM USAGE` medido | `mem_limit` asignado | Uso respecto del límite | Estado |
| :--- | :---: | :---: | :---: | :---: |
| `votes-service` | `329.7 MiB` | `512 MiB` | `64.39 %` | `healthy` |
| `results-service` | `358.8 MiB` | `512 MiB` | `70.08 %` | `healthy` |
| `api-gateway` | `261.4 MiB` | `384 MiB` | `68.08 %` | `healthy` |
| `rabbitmq` (`alpine`) | `101.8 MiB` | `224 MiB` | `45.45 %` | `healthy` |
| `postgres-results` | `46.41 MiB` | `128 MiB` | `36.25 %` | `healthy` |
| `postgres-votes` | `36.08 MiB` | `128 MiB` | `28.19 %` | `healthy` |
| `frontend` (`nginx`) | `9.09 MiB` | `64 MiB` | `14.20 %` | `healthy` |
| **Total stack base (7 servicios)** | **`1143.28 MiB`** | **`1952 MiB`** | **`58.57 %`** | **`7/7 healthy`** |

> Con `--profile tls` (`caddy:2.8.4-alpine`, `mem_limit: 64m`), la suma de límites totales es `2016 MiB` (`<= 2048 MiB`).

### Guía rápida de dimensionamiento (VPS / nube gratuita)

- **1 GB RAM (VPS micro)**: insuficiente para los 7 contenedores completos sin `swap` ni degradar límites por debajo de lo recomendado (`1143.28 MiB` activos); no recomendado para esta arquitectura sin swap de respaldo.
- **2 GB RAM (`~2048 MiB`)**: **perfil objetivo calibrado por defecto** en `docker-compose.prod.yml` (`1952 MiB` / `2016 MiB` con Caddy, picos por contenedor `<= 70.08 %`).
- **4 GB+ RAM (ej. Oracle Cloud Always Free ARM64 o servidor propio)**: permite subir `mem_limit` (`768m`–`1024m` por JVM) y escalar horizontalmente réplicas de `votes-service` en F9 sin presión de memoria.

---

## 3. Despliegue (HTTP o HTTPS con Caddy)

### Modo HTTP directo (validación local o detrás de balanceador externo con TLS)

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod ps
```

- Solo `frontend` publica `${PROD_HTTP_BIND:-0.0.0.0:80}:80`. `postgres-votes`, `postgres-results`, `rabbitmq`, `votes-service`, `results-service` y `api-gateway` quedan aislados en `livevote-internal`.

### Modo HTTPS automático con perfil `tls` (dominio propio apuntando al servidor)

1. En `.env.prod`, definir `PUBLIC_DOMAIN=tu-dominio.example` y cambiar `PROD_HTTP_BIND=127.0.0.1:8088` para dejar libres los puertos `80`/`443` del host a Caddy (`deploy/Caddyfile` mantiene `flush_interval -1` para SSE).
2. Levantar con el perfil `tls`:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod --profile tls pull
   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod --profile tls up -d --no-build
   ```

---

## 4. Verificación post-despliegue (Smoke Test)

Ejecutar los 9 checks E2E de producción (`/healthz`, rechazo `401` ante `X-User-Id` falsificado, registro, login, creación de encuesta, voto, actualización SSE en vivo, duplicado `409`, cierre y rechazo `422`):

```bash
bash scripts/smoke-test.sh http://127.0.0.1:8088
```

En Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://127.0.0.1:8088
```

---

## 5. Backups (`pg_dump`) y restauración (`pg_restore`)

### Backup manual y cron diario

`scripts/backup-postgres.sh` respalda por separado `postgres-votes` y `postgres-results` en formato custom (`-Fc`), verifica la integridad del archivo con `pg_restore --list` y elimina dumps más antiguos que `RETENTION_DAYS` (por defecto `7`):

```bash
bash scripts/backup-postgres.sh .env.prod
```

Ejemplo de tarea `crontab -e` en servidor Linux (03:00 UTC todos los días):

```cron
0 3 * * * cd /opt/livevote && /bin/bash scripts/backup-postgres.sh /opt/livevote/.env.prod >> /var/log/livevote-backup.log 2>&1
```

### Restauración de una base

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod exec -T postgres-votes \
  pg_restore --clean --if-exists -U "$POSTGRES_VOTES_USER" -d "$POSTGRES_VOTES_DB" < backups/votes_YYYYMMDDTHHMMSSZ.dump

docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod exec -T postgres-results \
  pg_restore --clean --if-exists -U "$POSTGRES_RESULTS_USER" -d "$POSTGRES_RESULTS_DB" < backups/results_YYYYMMDDTHHMMSSZ.dump
```

---

## 6. Actualización de versión (rollback / upgrade)

1. Cambiar `APP_VERSION=<nueva_version>` en `.env.prod` (ej. `0.8.0`).
2. Descargar y recrear solo servicios actualizados:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod pull
   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
   ```

3. Ejecutar nuevamente `scripts/smoke-test.sh`.
