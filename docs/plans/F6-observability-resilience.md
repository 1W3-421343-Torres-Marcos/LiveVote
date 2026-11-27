# Plan de Implementación — Fase F6: Observabilidad, resiliencia y demos (c) y (d)

- **Fase**: `F6`
- **Versión objetivo**: `v0.6.0`
- **Estado**: `Completado (v0.6.0)`
- **Fecha**: `2026-11-27`

---

## Objetivo

Instrumentar `api-gateway`, `votes-service` y `results-service` con logs JSON estructurados, propagación distribuida de `traceId` (`Gateway -> votes-service -> RabbitMQ -> results-service`), endpoints Actuator (`/actuator/health/liveness`, `/actuator/health/readiness` independientes por base de datos y métricas Golden Signals), endurecimiento de HikariCP + manejo `503` ante caída de base de datos + warmup de arranque (hallazgo F5), y verificar/documentar en `docs/demos.md` las demostraciones obligatorias **Demo (c)** y **Demo (d)**.

---

## 1. Logs JSON estructurados y propagación de `traceId`

- **Formato JSON nativo (Spring Boot 3.4)**: `logging.structured.format.console: logstash` en los tres servicios con `service: ${spring.application.name}`, renombre `@timestamp -> timestamp`, `logger_name -> logger`, exclusión de `@version`/`level_value` y `traceId` automático desde MDC.
- **Seguridad**: prohibido loguear contraseñas, hashes BCrypt, `JWT_SECRET`, cabeceras `Authorization` o tokens JWT.
- **Cadena `traceId` (`X-Trace-Id` / `traceId`)**:
  1. `api-gateway`: `config/TraceIdFilter` (`GlobalFilter`, orden `-300`) valida `X-Trace-Id` (`^[A-Za-z0-9_-]{1,64}$`) o genera 32 hex; propaga en request/response HTTP y loguea con `MDC.putCloseable` (incluidos rechazos `429` y fallbacks `503`).
  2. `votes-service`: `config/TraceIdFilter` (`OncePerRequestFilter`, `HIGHEST_PRECEDENCE`) carga `MDC`, responde `X-Trace-Id` y limpia en `finally`; `VoteEventPublisher` copia `traceId` en cabeceras AMQP `X-Trace-Id` y `traceId` (sin `__TypeId__`).
  3. `results-service`: `config/TraceIdFilter` (`WebFilter`, `HIGHEST_PRECEDENCE`) propaga `X-Trace-Id` en HTTP/SSE; `ResultsEventConsumer` y `RabbitMqConfig` extraen `X-Trace-Id`/`traceId` de los mensajes AMQP dentro de `MDC.putCloseable`.

---

### 2. Actuator, Golden Signals y resiliencia

- **Health checks**:
  - `management.endpoint.health.probes.enabled: true`, `show-components: always`, `show-details: never`.
  - `readiness.include: readinessState,db,rabbit` en `votes-service` y `results-service` (cada servicio refleja exclusivamente su propia BD).
  - `votes-service` `SecurityConfig`: habilita acceso interno a `/actuator/info` y `/actuator/metrics/**`.
- **Golden Signals**: histograma en `http.server.requests`, tag `application`, contadores Micrometer (`livevote.gateway.fallback.total`, `livevote.gateway.ratelimit.rejected.total`, `livevote.events.published.total`, `livevote.events.consumed.total`) y gauges SSE (`livevote.sse.active.channels`, `livevote.sse.active.subscribers`).
- **HikariCP, manejo de BD caída y warmup F5**:
  - HikariCP en ambos servicios JDBC: `connection-timeout: 3000`, `validation-timeout: 2000`, `keepalive-time: 60000`, `idle-timeout: 120000`, `max-lifetime: 300000`, `connectTimeout=3`, `socketTimeout=5`.
  - `votes-service` `GlobalExceptionHandler`: `CannotCreateTransactionException` y `TransientDataAccessException` -> `503 Service Unavailable`.
  - Warmup y timeout: `AmqpAdmin.initialize()` al `ApplicationReadyEvent` en `votes-service`; ajuste de `TimeLimiter` (`4 s -> 5 s`) y `response-timeout` (`5 s -> 6 s`) en `api-gateway`.

---

## 3. Versionado (`0.6.0`), verificación y `docs/demos.md`

- Versiones alineadas a `0.6.0` en `services/votes-service/pom.xml`, `services/results-service/pom.xml`, `services/api-gateway/pom.xml` y `frontend/package.json`.
- Tests unitarios e integración con Testcontainers en los 3 servicios Java + regresión `npm test`/`npm run build` en `frontend/`.
- Verificación en Docker Compose (7 contenedores `healthy`):
  - Correlación de `traceId` en logs JSON parseables de los 3 servicios + métricas Golden Signals.
  - **Demo (c)**: caída y autorrecuperación de `postgres-results` sin pérdida de votos ni envío a DLQ.
  - **Demo (d)**: caída y autorrecuperación de `postgres-votes` manteniendo lectura y SSE en vivo sin caída en cascada.
- Documentar runbooks y evidencia real observada en `docs/demos.md`.

---

## 4. Cierre de fase F6 (tras revisión del usuario)

- [x] Marcar ítems de F6 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.6.0)`.
- [x] Actualizar tabla de fases en `README.md`.
- [x] Agregar sección `## [0.6.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.6.0`.

---

## Desvíos y aprendizajes

- En Spring Boot `3.4.3`, el formato `logstash` exige escapar la clave con corchetes (`"[@timestamp]": timestamp`) en `application.yml` para evitar que el `Binder` elimine el prefijo `@` y lograr el campo `timestamp` esperado.
- El grupo `management.endpoint.health.group.readiness` no hereda `show-components: always` desde la configuración global de `health`, por lo que se declaró explícitamente dentro del grupo `readiness` en `votes-service` y `results-service`.
- En `api-gateway`, `TraceIdFilter` utiliza `exchange.getResponse().beforeCommit(...)` para sobrescribir la cabecera `X-Trace-Id` con un único valor cuando los servicios downstream ya la emiten en su respuesta.
- En `votes-service`, ante caída de `postgres-votes`, `GlobalExceptionHandler` mapea también `DataAccessResourceFailureException` (además de `CannotCreateTransactionException` y `TransientDataAccessException`) a HTTP `503 Service Unavailable`.
- Para eliminar el fallo en frío observado en F5, `votes-service` ejecuta `AmqpAdmin.initialize()` al recibir `ApplicationReadyEvent` (inyectado con `ObjectProvider<AmqpAdmin>` para mantener compatibilidad con `@WebMvcTest`), acompañado por `TimeLimiter` de `5 s` y `response-timeout` de `6 s` en `api-gateway`.
