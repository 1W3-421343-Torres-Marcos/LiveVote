# Changelog

Todos los cambios relevantes de **LiveVote** se documentan en este archivo siguiendo el formato [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y [Versionado Semántico](https://semver.org/lang/es/) unificado (`v0.X.0` por fase hasta `v1.0.0` en producción).

## [Unreleased]

## [0.8.0] - 2026-11-27

### Agregado

- Overlay productivo [docker-compose.prod.yml](./docker-compose.prod.yml) con consumo exclusivo de imágenes fijas en GHCR (`APP_VERSION`), secretos obligatorios (`${VAR:?...}`), rotación de logs `json-file`, `restart: unless-stopped`, `mem_limit` calibrados (`1952 MiB` en 7 servicios base), perfil opcional `tls` (`caddy:2.8.4-alpine` + [deploy/Caddyfile](./deploy/Caddyfile) con `flush_interval -1`) y exposición de puertos limitada a `frontend`/Caddy.
- Script de respaldo separado [scripts/backup-postgres.sh](./scripts/backup-postgres.sh) (`pg_dump -Fc` + validación con `pg_restore --list` y retención por `RETENTION_DAYS`) y smoke tests post-despliegue [scripts/smoke-test.sh](./scripts/smoke-test.sh) y [scripts/smoke-test.ps1](./scripts/smoke-test.ps1) (`9` aserciones incluyendo rechazo `401` ante `X-User-Id` falsificado y stream SSE).
- Runbook operativo [docs/runbook-prod.md](./docs/runbook-prod.md) con tabla real de RAM (`1143.28 MiB` medidos sobre `docker stats --no-stream`) y guía por tamaños de instancia (`1 GB` / `2 GB` / `4 GB+`), más plan de cierre [docs/plans/F8-production-deploy.md](./docs/plans/F8-production-deploy.md).

### Cambiado

- Ajuste de [.env.prod.example](./.env.prod.example) con `APP_VERSION=0.8.0`, `PROD_HTTP_BIND`, `RETENTION_DAYS` y `*_JAVA_OPTS=-XX:MaxRAMPercentage=60.0` calibrados para Distroless en ~2 GB RAM.
- Alineación de versiones Maven y npm a `0.8.0` en `services/votes-service/pom.xml`, `services/results-service/pom.xml`, `services/api-gateway/pom.xml` y `frontend/package.json`.

## [0.7.1] - 2026-11-27

### Corregido

- Corrección del filtro de tags en `.github/workflows/release.yml` a sintaxis glob SemVer (`v[0-9]*.[0-9]*.[0-9]*`) para disparar publicaciones multi-arch al pushear tags de versión.
- Actualización de `actions/setup-java@v4` a `@v5` y reintentos HTTP Maven Central (`-Daether.transport.http.retryHandler.count=5`) en los workflows de `votes-service`, `results-service` y `api-gateway` ante respuestas transitorias `429`.

## [0.7.0] - 2026-11-27

### Agregado

- Probe HTTP mínimo e independiente `HealthCheck.java` (solo `java.base`, `HttpURLConnection`, timeouts de `3 s` y flag `--print`) en `services/votes-service`, `services/results-service` y `services/api-gateway` para healthchecks e inspección sin shell dentro de Distroless.
- Configuración Karma CI en `frontend/karma.conf.js` (`ChromeHeadlessCI` con `--no-sandbox`, `--disable-gpu`, `--disable-dev-shm-usage`) y script `npm run test:ci` en `frontend/package.json`.
- Cinco workflows en `.github/workflows/` (`votes-service.yml`, `results-service.yml`, `api-gateway.yml`, `frontend.yml` filtrados por `paths` + `release.yml` en tags semánticos `v*.*.*`) con permisos mínimos, ejecución de `mvn -B verify` / `npm run test:ci` + `ng build`, y publicación multi-arquitectura (`linux/amd64,linux/arm64`) en `ghcr.io/<owner>/livevote-<component>` sin tag `latest` (`sha-<short>`, `X.Y.Z`, `X.Y`).
- Documentación de diseño y aprendizajes en [docs/plans/F7-distroless-ci-cd.md](./docs/plans/F7-distroless-ci-cd.md).

### Cambiado

- Migración de `services/votes-service/Dockerfile`, `services/results-service/Dockerfile` y `services/api-gateway/Dockerfile` a multi-stage `FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17-noble` → `FROM gcr.io/distroless/java17-debian12:nonroot` (`USER nonroot:nonroot`, etiquetas OCI y `ENTRYPOINT ["java", "-jar", "/app/app.jar"]` con soporte nativo de `JAVA_TOOL_OPTIONS`).
- Actualización de `frontend/Dockerfile` a `--platform=$BUILDPLATFORM node:22.12.0-alpine3.20` → `nginx:1.27.3-alpine` con etiquetas OCI.
- Healthchecks Java en `docker-compose.yml` y comandos internos de [docs/demos.md](./docs/demos.md) actualizados al probe `HealthCheck` sin shell/`wget`.
- Alineación de versiones a `0.7.0` en `services/votes-service/pom.xml`, `services/results-service/pom.xml`, `services/api-gateway/pom.xml`, `frontend/package.json` y `.env.prod.example` (`APP_VERSION=0.7.0` y nota `JAVA_TOOL_OPTIONS`).

## [0.6.0] - 2026-11-27

### Agregado

- Logging JSON estructurado nativo (`logstash` en Spring Boot `3.4.3`) en `api-gateway`, `votes-service` y `results-service` con campos `timestamp`, `level`, `service`, `traceId`, `logger` y `message` (sin loguear contraseñas, hashes, `JWT_SECRET` ni tokens JWT).
- Propagación distribuida de `traceId` de extremo a extremo (`Gateway -> votes-service -> RabbitMQ -> results-service`) mediante `TraceIdFilter` en los tres servicios (validación `^[A-Za-z0-9_-]{1,64}$` o generación 32 hex, deduplicación `beforeCommit` de `X-Trace-Id` en `api-gateway` y cabeceras AMQP `X-Trace-Id`/`traceId` sin `__TypeId__`).
- Probes Actuator (`/actuator/health/liveness`, `/actuator/health/readiness` con `db`, `rabbit` y `readinessState` por servicio) y métricas Golden Signals (`http.server.requests` con histogramas, `livevote.gateway.fallback.total`, `livevote.gateway.ratelimit.rejected.total`, `livevote.events.published.total`, `livevote.events.consumed.total`, `livevote.sse.active.channels`, `livevote.sse.active.subscribers` y `hikaricp.connections.active`).
- Runbook reproducible y evidencia verificada de observabilidad, **Demo (c)** y **Demo (d)** en [docs/demos.md](./docs/demos.md) + plan de cierre [docs/plans/F6-observability-resilience.md](./docs/plans/F6-observability-resilience.md).
- Tests unitarios y de integración actualizados (`13/13` en `api-gateway`, `19/19` en `votes-service`, `19/19` en `results-service` y regresión `14/14` + `ng build` en `frontend`).

### Cambiado

- Endurecimiento de HikariCP (`connection-timeout: 3000`, `validation-timeout: 2000`, `keepalive-time: 60000`, `idle-timeout: 120000`, `max-lifetime: 300000`, `connectTimeout=3`, `socketTimeout=5`) en `votes-service` y `results-service`, más manejo HTTP `503 Service Unavailable` ante caída de base de datos en `GlobalExceptionHandler` de `votes-service`.
- Resolución del fallo en frío detectado en F5 mediante inicialización AMQP en `ApplicationReadyEvent` (`ObjectProvider<AmqpAdmin>`) en `votes-service` y ampliación de `TimeLimiter` (`5 s`) / `response-timeout` (`6 s`) en `api-gateway`.
- Alineación de versiones Maven y npm a `0.6.0` en `services/api-gateway/pom.xml`, `services/votes-service/pom.xml`, `services/results-service/pom.xml` y `frontend/package.json`.

## [0.5.0] - 2026-11-27

### Agregado

- Nueva aplicación frontend `frontend/` en **Angular 20 LTS** (100 % standalone, sin `NgModules`, control flow `@if`/`@else`/`@for (...; track ...)`, reactive forms, signals y proxy de dev `proxy.conf.json`).
- Capa `core/` con modelos DTO tipados, `DeviceIdService` (`crypto.randomUUID()` en `localStorage`), `AuthService` (JWT + `expiresAt`), `PollService`, `ResultsService` (`EventSource` envuelto en `Observable` dentro de `NgZone`, `EVENT_SOURCE_FACTORY` y cierre limpio en teardown), `authGuard`/`guestGuard` y `jwtInterceptor` (inyección `Bearer` y logout automático ante `401`).
- 4 pantallas obligatorias + shell en `features/`: Auth (`/login`, `/register`), Panel del presentador (`/polls`, `/polls/new` con `FormArray` de 2–6 opciones, copia de link y cierre), Votación mobile-first (`/vote/:id` con manejo de `201`/`409`/`404`/`422`/`429`/`503`) y Resultados en vivo en modo proyector (`/results/:id` con tema oscuro, barras en tiempo real, latido `heartbeat`, pantalla completa, `takeUntilDestroyed` y corte ante `404`).
- `frontend/nginx.conf` con `/healthz`, proxy mismo origen `/api/` hacia `api-gateway`, fallback SPA (`try_files`) y bloque SSE dedicado (`proxy_buffering off`, `proxy_cache off`, `proxy_http_version 1.1`, `proxy_read_timeout 3600s`, `X-Accel-Buffering no`).
- `frontend/Dockerfile` multi-stage con tags fijos (`node:22.12.0-alpine3.20` → `nginx:1.27.3-alpine`) e integración de `frontend` en `docker-compose.yml` y `docker-compose.override.yml` (`${FRONTEND_PORT:-4200}:80`).
- Suite de tests unitarios Jasmine/Karma en `frontend/` (`14/14 SUCCESS` en `ChromeHeadless`) y verificación E2E completa (SSE vía `nginx` + flujo real en navegador).

### Cambiado

- Alineación de versiones Maven y npm a `0.5.0` en `frontend/package.json`, `services/votes-service/pom.xml`, `services/results-service/pom.xml` y `services/api-gateway/pom.xml`.

## [0.4.0] - 2026-11-27

### Agregado

- Endpoint SSE `GET /api/results/{id}/stream` (`text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`) en `services/results-service` mediante `ResultsStreamService` (`Sinks.Many` por encuesta, `Schedulers.boundedElastic()` para snapshot inicial con `retry: 3000`, actualizaciones en vivo tras commit y evento `heartbeat` cada ~20 s vía `SseProperties` y `HeartbeatEvent`).
- Buffer acotado previo a la consulta JPA en `ResultsStreamService` para retener votos concurrentes durante la carga del snapshot inicial sin emitir duplicados ni fugas de memoria (`onErrorResume` + `doFinally`).
- Test de integración `SseGatewayStreamingTest` en `services/api-gateway` con servidor Netty embebido demostrando entrega SSE continua más allá del timeout HTTP global (`400 ms`), stripping de `X-User-Id` y preservación del fallback `503` en rutas REST.
- Pruebas unitarias (`ResultsStreamServiceTest`) e integración Testcontainers en `ResultsServiceIntegrationTest` (`19/19` en `results-service`, `11/11` en `api-gateway`, `17/17` en `votes-service`).

### Cambiado

- `OptionCountRepository.incrementVoteCount` actualizado con `@Modifying(flushAutomatically = true, clearAutomatically = true)` y `ResultsProjectionService.handleVoteCast` pasando a retornar `Optional<PollResultsResponse>` (vacío ante votos duplicados) para emitir al stream únicamente votos nuevos confirmados desde `ResultsEventConsumer`.
- `GlobalExceptionHandler` en `results-service` configurando `MediaType.APPLICATION_JSON` explícito en errores (`404`/`400`) incluso ante peticiones con `Accept: text/event-stream`.
- Ajuste explícito de `connect-timeout: 3000` junto a `response-timeout: -1` en la ruta `results-stream` de `services/api-gateway`, y alineación de versiones Maven a `0.4.0` en los 3 servicios.

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
