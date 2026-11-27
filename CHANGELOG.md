# Changelog

Todos los cambios relevantes de **LiveVote** se documentan en este archivo siguiendo el formato [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y [Versionado Semántico](https://semver.org/lang/es/) unificado (`v0.X.0` por fase hasta `v1.0.0` en producción).

## [Unreleased]

## [0.3.0] - 2026-11-27

### Agregado

- Nuevo microservicio `services/api-gateway` (Spring Cloud Gateway WebFlux, Java 17, estructura *Package by Layer*: `config`, `security`, `controller`, `exception`, versión `0.3.0`) con `Dockerfile` multi-stage Distroless multi-arquitectura.
- Enrutamiento unificado en el Gateway para `votes-auth`, `votes-polls`, `results-stream` (`/api/results/*/stream` sin Circuit Breaker ni timeout restrictivo para SSE) y `results-api`.
- Filtro global `IdentityHeaderFilter`: elimina cualquier cabecera `X-User-Id` enviada por el cliente, valida JWT HS256 en rutas protegidas (`POST/PATCH /api/polls/**`, `GET /api/polls/mine`) y reinyecta `X-User-Id` con el claim `sub` del token.
- Filtro `IpRateLimitFilter` con Caffeine en memoria (registro `5 req/min`, login `10 req/min`, voto `30 req/min`) respondiendo `429 Too Many Requests` en formato `ApiErrorResponse` estándar.
- Circuit Breakers con Resilience4j (`votes-cb`, `results-cb`) registrando fallos en HTTP `500/502/503/504` y `FallbackController` (`503 Service Unavailable` preservando el `path` original de la solicitud).
- Suite de tests unitarios e integración con WireMock en `api-gateway` (`9/9` en verde).

### Cambiado

- Alineación de versiones Maven a `0.3.0` en `services/api-gateway`, `services/votes-service` y `services/results-service`.
- Aislamiento de red interna en `docker-compose.yml` y `docker-compose.override.yml`: `api-gateway` expuesto en `http://localhost:8080`, `TRUST_GATEWAY_HEADER: "true"` activo en `votes-service` y retiro de los puertos `8081`/`8082` publicados al host.

## [0.2.0] - 2026-11-26

### Cambiado
- Estandarización obligatoria a **Package by Layer** en todos los microservicios Spring Boot (`config`, `security`, `controller`, `service`, `repository`, `entity`, `dto`, `event`, `exception`) sin paquete genérico `common`.

### Agregado
- **F2 (RabbitMQ + `results-service` + `postgres-results`)**:
  - Topología durable en RabbitMQ (`livevote.events`, `livevote.retry`, `livevote.dlx`, `results.events.queue`, `results.events.retry.queue` y `results.events.dlq`) sin acoplamiento por cabeceras `__TypeId__`.
  - Publicación `AFTER_COMMIT` de `PollCreated` (sin datos del dueño) y `VoteCast` en `services/votes-service` (`0.2.0`).
  - Nuevo proyecto `services/results-service` (`0.2.0`, WebFlux + Spring Data JPA fuera del event loop sobre `Schedulers.boundedElastic()`, Flyway `V1__init_results_schema.sql`, endpoint `GET /api/results/{id}`).
  - Idempotencia atómica en `processed_votes` (`ON CONFLICT DO NOTHING`) y política diferenciada de reintentos (backoff + cola diferida para fallos transitorios/fuera de orden vs `AmqpRejectAndDontRequeueException` -> DLQ para *poison messages*).
  - Integración completa en `docker-compose.yml` y `docker-compose.override.yml`, verificada con 17 tests en `votes-service`, 11 tests en `results-service` (Testcontainers PostgreSQL + RabbitMQ) y smoke test E2E de 5 contenedores.

## [0.1.0] - 2026-11-26

### Agregado
- **F0 (Bootstrap)**: inicialización del monorepo con `.gitignore`, `.gitattributes` (LF), `.env.example`, `.env.prod.example`, `AGENTS.md`, 9 skills en `.agents/skills/`, `docs/PLAN.md` y `README.md`.
- **F1 (`votes-service` + `postgres-votes`)**:
  - Proyecto Maven independiente en `services/votes-service` (Java 17, Spring Boot `3.4.3`, Flyway, Spring Data JPA, Spring Security).
  - Módulo `identity` (`POST /api/auth/register`, `POST /api/auth/login`) con hash BCrypt, emisión/validación de JWT HS256 (2 h) y soporte dual `trust-gateway-header` (`false` por defecto en F1).
  - Módulo `polls` (`POST /api/polls`, `GET /api/polls/mine`, `POST /api/polls/{id}/close`, `GET /api/polls/{id}`, `POST /api/polls/{id}/votes`) con validación de 2–6 opciones, control de dueño y restricción única `(poll_id, device_id)` en `V1__init_votes_schema.sql`.
  - Suite de 14 tests (unitarios, `@WebMvcTest` y `VotesServiceIntegrationTest` con Testcontainers `postgres:16.6-alpine`).
  - `Dockerfile` multi-stage con tags fijos y configuración inicial `docker-compose.yml` + `docker-compose.override.yml`.
