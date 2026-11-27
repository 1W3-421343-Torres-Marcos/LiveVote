# Plan de Implementación — Fase F4: Tiempo real (SSE con heartbeat)

- **Fase**: `F4`
- **Versión objetivo**: `v0.4.0`
- **Estado**: `Completado (v0.4.0)`
- **Fecha**: `2026-11-27`

---

## Objetivo

Exponer el stream reactivo `GET /api/results/{id}/stream` (`text/event-stream`) en `results-service` con snapshot inicial inmediato, actualizaciones en tiempo real tras confirmarse votos en `postgres-results`, evento `heartbeat` cada ~20 s y limpieza sin fugas al desconectarse los clientes; además de validar y blindar el proxy de streams SSE prolongados en `api-gateway` sin buffering ni cortes por timeout/Circuit Breaker.

---

## 1. Contrato SSE (`GET /api/results/{id}/stream`)

- **Acceso**: público (sin JWT; `api-gateway` elimina cualquier cabecera `X-User-Id` entrante).
- **Cabeceras de respuesta (`200 OK`)**:
  - `Content-Type: text/event-stream`
  - `Cache-Control: no-cache`
  - `X-Accel-Buffering: no`
- **Eventos emitidos (`ServerSentEvent`)**:
  - `event: results`: snapshot inicial al conectar (`retry: 3000` ms) y cada actualización tras un voto nuevo confirmado en `postgres-results`, transportando el DTO completo `PollResultsResponse`.
  - `event: heartbeat`: emitido cada **20 s** (`app.sse.heartbeat-interval-ms`, default `20000`) con `HeartbeatEvent(Instant timestamp)`.
- **Errores previos al stream**:
  - Encuesta inexistente -> `404 Not Found` (`Content-Type: application/json`, `ApiErrorResponse`).
  - UUID inválido -> `400 Bad Request` (`Content-Type: application/json`, `ApiErrorResponse`).
  - Se fuerza `MediaType.APPLICATION_JSON` en `GlobalExceptionHandler` para evitar `406 Not Acceptable` cuando `EventSource` o `curl` envían `Accept: text/event-stream`.

---

## 2. Diseño en `services/results-service` (*Package by Layer*)

Sin paquete `common` y manteniendo toda lectura JPA/JDBC fuera del event loop de Netty (`Schedulers.boundedElastic()`):

- `config/SseProperties`: `@ConfigurationProperties(prefix = "app.sse")` con `heartbeatIntervalMs` (`20000`) y `clientBufferSize` (`256`).
- `dto/HeartbeatEvent`: record `{ Instant timestamp }`.
- `repository/OptionCountRepository`: `@Modifying(flushAutomatically = true, clearAutomatically = true)` en `incrementVoteCount` para evitar caché L1 obsoleta dentro de la misma transacción.
- `service/ResultsProjectionService`: `handleVoteCast` retorna `Optional<PollResultsResponse>` (`Optional.empty()` si es voto duplicado, `Optional.of(results)` si incrementó tras confirmar el voto).
- `service/ResultsStreamService`:
  - `ConcurrentHashMap<UUID, PollStreamChannel>` con `Sinks.Many<PollResultsResponse>` (`multicast().directBestEffort()`) y `AtomicInteger` de suscriptores activos.
  - Adquisición y liberación atómicas (`compute` / `computeIfPresent`) más `AtomicBoolean` en `doFinally` para limpieza determinística sin fugas ante cancelación o `404`.
  - Buffer unicast acotado por suscriptor (`clientBufferSize`) suscrito antes de consultar PostgreSQL para no perder votos concurrentes durante la carga del snapshot inicial (`totalVotes > snapshot.totalVotes() || status != snapshot.status()`).
  - Publicación no bloqueante (`tryEmitNext` reintentando solo `FAIL_NON_SERIALIZED`; no-op si no hay suscriptores).
- `event/ResultsEventConsumer`: publica al stream únicamente cuando `handleVoteCast` devuelve resultado nuevo tras `@Transactional`.
- `controller/ResultsController`: agrega `@GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` fijando `Cache-Control: no-cache` y `X-Accel-Buffering: no`.
- `exception/GlobalExceptionHandler`: fuerza `MediaType.APPLICATION_JSON` en respuestas de error.

---

## 3. Validación en `services/api-gateway` y versionado `0.4.0`

- En `services/api-gateway/src/main/resources/application.yml`, mantener explícito `connect-timeout: 3000` junto a `response-timeout: -1` en `results-stream` (`/api/results/*/stream`), excluida del `CircuitBreaker` y `TimeLimiter` de 4 s.
- Crear `SseGatewayStreamingTest` con servidor Netty embebido y `response-timeout=400ms` global demostrando streaming continuo chunk-a-chunk, stripping de `X-User-Id` y conservación del fallback `503` en rutas REST normales.
- Alinear versión Maven a `0.4.0` en `services/votes-service`, `services/results-service` y `services/api-gateway`.

---

## 4. Plan de verificación

1. Unitarios e integración en `services/results-service` (`mvn test`): `ResultsStreamServiceTest` (con `VirtualTimeScheduler`), actualización de `ResultsProjectionServiceTest`, `ResultsEventConsumerTest`, `ResultsControllerTest` y `ResultsServiceIntegrationTest` (Testcontainers PostgreSQL + RabbitMQ).
2. Unitarios e integración en `services/api-gateway` (`mvn test`): `SseGatewayStreamingTest` y regresión existente.
3. Regresión `mvn test` en `services/votes-service`.
4. E2E con Docker Compose (`6` contenedores `healthy`) y `curl -N` sobre `http://localhost:8080/api/results/{id}/stream` (> 22 s) verificando snapshot inmediato, voto `201` en vivo, voto duplicado `409` sin evento extra, `event: heartbeat` sin corte de conexión y `404` JSON en encuesta inexistente.

---

## 5. Cierre de fase F4 (tras revisión del usuario)

- [x] Marcar ítems de F4 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.4.0)`.
- [x] Actualizar tabla de fases en `README.md`.
- [x] Agregar sección `## [0.4.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.4.0`.

---

## Desvíos y aprendizajes

1. **Orden de limpieza ante error en Reactor (`onErrorResume` + `doFinally`)**: En `FluxDoFinally`, Reactor entrega `onError` al suscriptor antes de ejecutar `runFinally(SignalType.ON_ERROR)`, provocando que `ResultsStreamServiceTest.shouldCleanUpChannelWhenPollDoesNotExist` observara momentáneamente `activePollChannelCount == 1` justo tras recibir `ResultsNotFoundException`. Se resolvió agregando `.onErrorResume(...)` previo a `.doFinally(...)` en `ResultsStreamService` para desalojar el canal antes de emitir la señal de error aguas abajo.
