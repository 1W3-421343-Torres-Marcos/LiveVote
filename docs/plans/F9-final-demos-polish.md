# Plan de Implementación — Fase F9: Demos finales (a)–(d), documentación y `PollClosed` en vivo

- **Fase**: `F9`
- **Versión objetivo**: `v1.0.0`
- **Estado**: `Completado (v1.0.0)`
- **Fecha**: `2026-11-27`

---

## 1. Objetivo

Completar las demostraciones pendientes de microservicios (**Demo a** de escalado horizontal real de `votes-service` y **Demo b** de caída/recuperación de `results-service`), implementar la mejora opcional de evento **`PollClosed` en vivo** (`poll.closed` → RabbitMQ → SSE), evaluar arquitectónicamente el fan-out horizontal de SSE, pulir la documentación final de portfolio (`README.md`, `docs/demos.md`, `docs/PLAN.md`, `CHANGELOG.md`) y liberar la versión **`v1.0.0`**.

---

## 2. Balanceo de réplicas en `api-gateway` — Demo (a)

- **Problema conocido en Reactor Netty**: con `docker compose up --scale votes-service=3`, el pool keep-alive TCP por defecto reutiliza conexiones persistentes fijadas a una sola IP resuelta al inicio, rompiendo la distribución entre réplicas detrás de DNS round-robin interno de Docker.
- **Solución en `api-gateway` y `votes-service`**:
  1. `services/api-gateway/src/main/resources/application.yml`: configurar `spring.cloud.gateway.httpclient.pool.type: ${GATEWAY_HTTP_POOL_TYPE:disabled}` para evitar pinning TCP por keep-alive hacia upstreams DNS dinámicos.
  2. `services/api-gateway/src/main/java/com/livevote/gateway/config/UpstreamClientConfig.java`: registrar `HttpClientCustomizer` con `resolver(spec -> spec.roundRobinSelection(true).cacheMaxTimeToLive(Duration.ofSeconds(5)))` para rotación DNS round-robin con TTL acotado entre réplicas nuevas o escaladas.
  3. `services/votes-service/src/main/java/com/livevote/votes/config/TraceIdFilter.java`: emitir cabecera `X-Instance-Id` (`HOSTNAME`) en respuestas y `instance={}` en logs JSON para comprobar objetivamente qué réplica atendió cada petición.

---

## 3. Evento `PollClosed` en vivo (`poll.closed`) y evaluación SSE

- **Contrato `PollClosed` (`eventVersion: 1`, `poll.closed`)**:
  - `votes-service`: `PollService.closePoll` publica `PollClosedEvent(1, pollId, closedAt)` tras el commit (`@TransactionalEventListener(phase = AFTER_COMMIT)` en `VoteEventPublisher`).
  - `results-service`: `RabbitMqConfig` enlaza `poll.closed` a `results.events.queue`; `ResultsEventConsumer` consume el evento con idempotencia en `ResultsProjectionService.handlePollClosed` (`CLOSED` + `closedAt`) y emite el snapshot actualizado por `ResultsStreamService.publishUpdate(...)` a los clientes SSE conectados.
- **Evaluación de fan-out SSE para escalar `results-service`**:
  - Actualmente `results-service` corre con 1 réplica porque `Sinks.Many` reside en memoria JVM (`1143.28 MiB` totales en stack productivo).
  - Para escalar `results-service` a N réplicas manteniendo cero dependencias nuevas en presupuesto cero, la opción preferida es crear en cada instancia una **cola temporal/exclusiva vinculada al Topic Exchange `livevote.events`** (en vez de sumar Redis Pub/Sub), o bien Redis Pub/Sub si se requiere desacoplar estado de brokers. Se documenta como evolución futura y se conserva 1 réplica de `results-service` para mantener acotada la RAM (`~2 GB` objetivo).

---

## 4. Criterios de verificación

1. `mvn -B verify` en `votes-service`, `results-service` y `api-gateway`, más `npm --prefix frontend run test:ci` y `npm --prefix frontend run build`.
2. **Demo (a)** en Compose (`--scale votes-service=3`, 9 contenedores `healthy`): verificar distribución de peticiones vía `X-Instance-Id` y logs entre las **3 réplicas**.
3. **Demo (b)** en Compose: detener `results-service`, votar `201` x3, comprobar `GET /api/results/{id}` en `503` (fallback Gateway) y `0` DLQ en RabbitMQ, reiniciar `results-service` y confirmar conciliación a `totalVotes=4`.
4. **SSE `PollClosed` en vivo**: stream `curl.exe -N` recibe snapshot `OPEN` y luego trama en caliente `status=CLOSED` + `closedAt` tras `POST /api/polls/{id}/close`.

---

## 5. Cierre de fase F9 (`v1.0.0`)

- [x] Actualizar `docs/demos.md` con evidencia reproducible de Demos (a)–(d) y `PollClosed`.
- [x] Actualizar `README.md` final de portfolio (justificación, trade-offs, RAM medida, fan-out SSE y fases **F0–F9**).
- [x] Marcar ítems F9 en `docs/PLAN.md`, actualizar bitácora y estado de este plan a `Completado (v1.0.0)`.
- [x] Agregar sección `## [1.0.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés, tag Git `v1.0.0` y push a `origin`.

---

## Desvíos y aprendizajes

- **Pinning HTTP en Gateway resuelto con DNS round-robin**: `UpstreamClientConfig` (`roundRobinSelection(true)` + `cacheMaxTimeToLive(5 s)`) junto a `pool.type: disabled` distribuyó 15 peticiones secuenciales entre las 3 réplicas de `votes-service` (`9`, `3`, `3` hits confirmados por `X-Instance-Id` y logs de cada contenedor).
- **Escapado JSON de rutas y consulta directa RabbitMQ en verificación**: en logs `logstash`, `/` se serializa como `\/`, y el endpoint `/api/queues/%2F` de RabbitMQ Management sufre retraso de agregación y normalización URI en `.NET`; `rabbitmqctl list_queues` demostró en tiempo real los 3 mensajes retenidos en `results.events.queue` y `0` en DLQ durante la caída de `results-service`.
- **Actualización de `VotesServiceIntegrationTest`**: al emitir `poll.closed` tras `POST /api/polls/{id}/close`, el test de integración previo esperaba solo 2 eventos; se actualizó para verificar explícitamente el evento `poll.closed` (`eventVersion: 1`, `pollId`, `closedAt`) y confirmar que votos rechazados (`422`) no emiten eventos.
- **Evaluación de fan-out SSE horizontal**: para escalar `results-service` a N réplicas manteniendo presupuesto cero, se documentó en `README.md` el uso de colas temporales exclusivas por réplica vinculadas al Topic Exchange `livevote.events` en lugar de añadir Redis Pub/Sub.

