---
name: production-deploy
description: >-
  Runbook y compuerta obligatoria para la fase F8 de producción: elección de destino gratuito, límites de RAM, secretos, TLS, backups y smoke test.
  Activar esta skill antes de planificar o ejecutar F8, al ajustar docker-compose.prod.yml,
  medir el consumo real de RAM del stack o documentar backups pg_dump y smoke tests.
---

# Skill: `production-deploy`

Prepara y ejecuta el despliegue a producción de **LiveVote** con presupuesto cero y sin servicios administrados propietarios.

## Compuerta obligatoria antes de planificar o tocar F8

> **DETENERSE Y PREGUNTAR PRIMERO**: Antes de planificar o ejecutar la fase **F8**, el agente **DEBE**:
> 1. Preguntar al usuario cuál es el destino de despliegue elegido (por ejemplo VPS gratuito ARM/AMD64, Oracle Cloud Always Free, máquina propia, etc.).
> 2. Verificar los límites vigentes del plan gratuito elegido (RAM total, vCPU, arquitectura `arm64`/`amd64`, almacenamiento, puertos públicos y ancho de banda).
> 3. Comparar esos límites contra la medición real de RAM del stack obtenida previamente.

## Reglas de configuración 12-factor y seguridad

- **Secretos fuera del repositorio**: usar `.env.prod` en el servidor (nunca versionado). Ninguna contraseña por defecto; validar que `JWT_SECRET` tenga >= 32 bytes generados aleatoriamente (`openssl rand -base64 48`).
- **Superficie mínima expuesta**: en `docker-compose.prod.yml`, **solo `nginx` (o el proxy TLS)** publica puertos al exterior (`80`/`443`). `postgres-votes`, `postgres-results`, `rabbitmq`, `api-gateway`, `votes-service` y `results-service` solo escuchan en la red Docker interna.
- **TLS**: terminado fuera del stack de la app (balanceador del proveedor o servicio opcional `caddy` mediante perfil `tls` en `docker-compose.prod.yml`). La aplicación interna escucha HTTP detrás del terminador TLS.

## Medición real de RAM y sizing JVM

Antes de desplegar, levantar el stack en modo producción local y medir consumo real bajo carga básica:

```bash
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"
```

- Documentar el total de MB consumidos por los 7 contenedores (`postgres-votes`, `postgres-results`, `rabbitmq`, `votes-service`, `results-service`, `api-gateway`, `frontend`).
- Ajustar `mem_limit` en `docker-compose.prod.yml` y `-XX:MaxRAMPercentage=75.0` en cada servicio Java para evitar OOMKills en instancias con RAM acotada (ej. 1 GB vs 4+ GB).

## Backups (`pg_dump`) y smoke test post-despliegue

- **Backups básicos**: documentar script y cron de respaldo programado para **ambas bases** por separado (`pg_dump -U ... -Fc` contra `postgres-votes` y `postgres-results`) con retención local rotativa.
- **Smoke test post-despliegue** (script reproducible contra la URL pública):
  1. Registrar usuario de prueba o verificar salud vía Gateway/nginx.
  2. Login -> crear encuesta (`POST /api/polls`).
  3. Abrir stream SSE (`GET /api/results/{id}/stream`) o consultar resultados.
  4. Emitir voto (`POST /api/polls/{id}/votes`) y confirmar `201` + incremento en resultados + `409` en segundo intento con mismo `deviceId`.
  5. Verificar `401` si se envía `X-User-Id` falsificada sin JWT válido.
