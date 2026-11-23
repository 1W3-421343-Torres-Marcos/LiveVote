---
name: observability
description: >-
  Guía de logs JSON estructurados, propagación de traceId, métricas Golden Signals, health checks Actuator y demos de resiliencia.
  Activar esta skill al configurar logging, MDC/traceId entre HTTP y RabbitMQ, endpoints Actuator liveness/readiness
  o al ejecutar las pruebas de aislamiento ante caídas de base de datos (demos c y d).
---

# Skill: `observability`

Instrumentación mínima pero completa para diagnosticar flujos distribuidos (`Gateway -> votes-service -> RabbitMQ -> results-service`) y demostrar el aislamiento de fallos.

## Logging estructurado y propagación de `traceId`

- **Formato JSON**: configurar salida de logs en JSON en los tres servicios Spring Boot (incluyendo `timestamp`, `level`, `service`, `traceId`, `logger`, `message`).
- **Seguridad en logs**: **NUNCA** loguear contraseñas, hashes, `JWT_SECRET` ni tokens `Authorization: Bearer ...`.
- **Flujo de `traceId`**:
  1. `api-gateway`: si no llega `X-Trace-Id` válido (o para iniciar una traza limpia), genera un `traceId` (UUID o 16/32 hex) y lo propaga en la cabecera HTTP `X-Trace-Id` y en el MDC de logs.
  2. `votes-service`: un filtro servlet lee `X-Trace-Id` (o genera uno si corre aislado), lo carga en MDC y lo copia a las cabeceras AMQP del mensaje publicado en RabbitMQ.
  3. `results-service`: el consumidor AMQP extrae `X-Trace-Id` del `MessageProperties` antes de procesar el evento y lo coloca en MDC (y lo limpia en `finally`), permitiendo seguir un voto de punta a punta por `traceId`.

## Actuator: Health Checks y Golden Signals

Habilitar en los servicios Spring Boot:
- **Liveness**: `/actuator/health/liveness` (`management.endpoint.health.probes.enabled=true`).
- **Readiness**: `/actuator/health/readiness`, incluyendo el indicador de su propia base de datos (`db`) en `votes-service` y `results-service` (y estado del broker donde corresponda), sin acoplar un servicio con la base del otro.
- **Métricas (Golden Signals)**: exponer `/actuator/metrics` (y Prometheus si se habilita) cubriendo:
  - *Latencia*: `http.server.requests` (percentiles/histogramas).
  - *Tráfico*: tasa de requests HTTP y mensajes RabbitMQ consumidos/publicados.
  - *Errores*: respuestas `4xx`/`5xx`, fallbacks de Circuit Breaker y mensajes enviados a DLQ.
  - *Saturación*: `hikaricp.connections.active` / `pending`, hilos activos / `boundedElastic`, memoria JVM.

## Runbook de verificación — Demos (c) y (d)

1. **Demo (c) — Caída de `postgres-results`**:
   - Detener `postgres-results` (`docker compose stop postgres-results`).
   - Verificar que `results-service` pasa `readiness` a `DOWN` mientras `votes-service` sigue aceptando `POST /api/polls/{id}/votes` con `201`.
   - Comprobar en RabbitMQ que los eventos permanecen en cola/reintento sin perderse.
   - Levantar `postgres-results` (`docker compose start postgres-results`) y verificar recuperación automática del pool HikariCP y actualización de conteos en `results-service`.
2. **Demo (d) — Caída de `postgres-votes`**:
   - Detener `postgres-votes` (`docker compose stop postgres-votes`).
   - Verificar que `GET /api/results/{id}` y el stream SSE en `results-service` siguen operando normalmente.
   - Verificar que votar/crear encuestas devuelve error controlado (`503` vía Circuit Breaker o respuesta controlada), sin caída en cascada, y se recupera solo al volver `postgres-votes`.
