# Plan de Implementación — Fase F8: Producción, sizing de RAM, backups y smoke test

- **Fase**: `F8`
- **Versión objetivo**: `v0.8.0`
- **Estado**: `Completado (v0.8.0)`
- **Fecha**: `2026-11-27`

---

## 1. Compuerta previa — Decisiones confirmadas con el usuario

1. **Destino de despliegue**: Validación productiva local en **Docker Desktop (WSL2)** dejando toda la configuración lista para desplegar posteriormente en cualquier VPS compatible (`linux/amd64` o `linux/arm64`).
2. **Dominio y TLS**: Exposición HTTP directa para validación local (`PROD_HTTP_BIND`) más perfil opcional `--profile tls` con Caddy (`deploy/Caddyfile`, `80`/`443`) para entornos con dominio propio.
3. **Origen de imágenes**: Consumo real desde **GitHub Container Registry (`ghcr.io`)** por tag semántico `APP_VERSION` (sin `latest`), previa verificación de `v0.7.1`.
4. **Presupuesto de RAM objetivo**: Entorno acotado de **~2 GB RAM (`2048 MiB`)**, calibrando `mem_limit` y `JAVA_TOOL_OPTIONS` a partir de la medición real con `docker stats --no-stream`.

---

## 2. Arquitectura productiva y seguridad (`docker-compose.prod.yml`)

- Archivo overlay combinable **exclusivamente** con la base:
  `docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d` (sin `docker-compose.override.yml`).
- **Superficie mínima expuesta**:
  - Por defecto, **únicamente `frontend` (`nginx`)** publica puertos (`${PROD_HTTP_BIND:-0.0.0.0:80}:80`).
  - Con `--profile tls`, `caddy:2.8.4-alpine` expone `80`/`443` detrás de `PUBLIC_DOMAIN` y `frontend` permanece sin exposición adicional innecesaria (o accesible vía red interna `livevote-internal`).
  - `postgres-votes`, `postgres-results`, `rabbitmq`, `votes-service`, `results-service` y `api-gateway` operan exclusivamente en `livevote-internal`.
- **Configuración 12-factor y Distroless**:
  - Todas las credenciales y secretos exigidos con `${VAR:?...}` (sin valores por defecto en producción); `SPRING_PROFILES_ACTIVE` por defecto `prod`.
  - Opciones JVM vía `JAVA_TOOL_OPTIONS` nativo en Distroless (`VOTES_JAVA_OPTS`, `RESULTS_JAVA_OPTS`, `GATEWAY_JAVA_OPTS`).
  - `restart: unless-stopped`, rotación de logs (`json-file`, `max-size: 10m`, `max-file: 3`) y `mem_limit` calibrados tras medición real.

---

## 3. Operación: backups, smoke test y `docs/runbook-prod.md`

- `scripts/backup-postgres.sh`: respaldo separado `pg_dump -Fc` contra `postgres-votes` y `postgres-results`, verificación automática con `pg_restore --list` y retención rotativa (`RETENTION_DAYS`).
- `scripts/smoke-test.sh` y `scripts/smoke-test.ps1`: prueba automatizada post-despliegue con 9 verificaciones (`/healthz`, rechazo `401` de `X-User-Id` falsificado, registro `201`, login `200`, creación `201`, voto `201`, incremento + SSE `event:results`, duplicado `409`, cierre `200` y rechazo `422` tras cierre).
- `docs/runbook-prod.md`: runbook en español con `.env.prod`, despliegue con/sin `--profile tls`, tabla real de RAM medida y guía para 1 GB / 2 GB / 4 GB+, backups y actualización por `APP_VERSION`.

---

## 4. Criterios de verificación

1. `docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod config` válido (con y sin `--profile tls`) y fallo inmediato ante secreto faltante.
2. Arranque productivo usando imágenes reales `ghcr.io/1w3-421343-torres-marcos/livevote-*:0.7.1` con los 7 contenedores `healthy`, cero puertos internos expuestos al host y cero `OOMKilled`.
3. `smoke-test` (`9/9` checks OK), medición real `docker stats --no-stream` bajo carga + SSE dentro de presupuesto (`<= 2048 MiB`), y ejecución + validación `pg_restore --list` de `scripts/backup-postgres.sh`.
4. Limpieza de archivos temporales (`.env.prod`, `backups/`, `down -v`), alineación a `0.8.0` y cierre/push de F8 (`v0.8.0`).

---

## 5. Cierre de fase F8

- [x] Marcar ítems de F8 en `docs/PLAN.md` y registrar medición de RAM en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.8.0)`.
- [x] Actualizar estructura y tabla de fases en `README.md`.
- [x] Agregar sección `## [0.8.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés, tag Git `v0.8.0` y push a `origin`.

---

## Desvíos y aprendizajes

- **Ajuste de `mem_limit` tras medición real**: la corrida inicial con `384m` en `votes-service`/`results-service` y `-XX:MaxRAMPercentage=70.0` alcanzó `85.57 %` y `86.01 %` respectivamente (`1095.5 MiB` totales). Se recalibró a `512m` en ambos, `384m` en `api-gateway`, `128m` por PostgreSQL y `224m` en RabbitMQ con `-XX:MaxRAMPercentage=60.0`, reduciendo la utilización pico a `70.08 %` (`1143.28 MiB` reales totales sobre `1952 MiB` límite, `OOMKilled=false` en los 7 servicios).
- **Anulación de `build:` en overlay Compose**: `docker-compose.prod.yml` usa `build: !reset null` junto a `pull_policy: always` e imagen fija de GHCR (`build=False` confirmado en `docker compose config --format json`), evitando reconstrucciones accidentales en servidor.
- **Portabilidad Windows/Git Bash en scripts**: `scripts/smoke-test.sh` evita subshell/`shift` cuando falta `body`, y `scripts/backup-postgres.sh` normaliza CR al leer `.env.prod` y aplica `MSYS_NO_PATHCONV=1` en `docker compose exec -T` para compatibilidad entre Linux y Git Bash.

