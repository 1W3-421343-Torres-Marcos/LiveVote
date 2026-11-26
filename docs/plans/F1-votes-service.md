# Plan de Implementación — Fase F1: `votes-service` aislado y `postgres-votes`

- **Fase**: `F1`
- **Versión objetivo**: `v0.1.0`
- **Estado**: `Completado (v0.1.0)`
- **Fecha**: `2026-11-26`

---

## Objetivo

Construir el microservicio de escritura `services/votes-service` de forma autónoma junto a su base de datos exclusiva `postgres-votes`, con migraciones Flyway, autenticación JWT (módulo `identity`), gestión de encuestas y votación anónima única por dispositivo (`poll_id`, `device_id`), pruebas unitarias y de integración con Testcontainers, y levantamiento local mediante Docker Compose.

---

## 1. Proyecto Maven (`services/votes-service/pom.xml`)

- **Java**: `17`
- **Spring Boot**: `3.4.3`
- **Versión del artefacto**: `0.1.0` (alineada al hito `v0.1.0`)
- **Dependencias principales**:
  - `spring-boot-starter-web`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-security`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-actuator`
  - `flyway-core` + `flyway-database-postgresql` + `postgresql`
  - `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson` (`0.12.6`) para JWT HS256
- **Dependencias de test**:
  - `spring-boot-starter-test`
  - `spring-security-test`
  - `spring-boot-testcontainers`
  - `org.testcontainers:junit-jupiter`
  - `org.testcontainers:postgresql`

> *Nota*: `spring-boot-starter-amqp` y la publicación de eventos hacia RabbitMQ se incorporan recién en **F2** para mantener F1 completamente aislado.

---

## 2. Arquitectura interna (`package-by-feature`)

Paquete raíz: `com.livevote.votes` (`Controller -> Service -> Repository`). Sin lógica de negocio en controllers.

### 2.1 Módulo `identity/`
- `UserEntity` (tabla `users`: `id` UUID, `email` único, `passwordHash`, `createdAt`) y `UserRepository`.
- `JwtProperties` (`app.jwt.secret`, `app.jwt.expiration-seconds = 7200`): valida al iniciar que `JWT_SECRET` tenga al menos **32 bytes**.
- `JwtService`: firma y valida tokens HS256 (`sub = userId`, `email`, `iat`, `exp`); nunca loguea secretos ni tokens.
- `IdentityService`: registro con hash BCrypt (`409 Conflict` si el email ya existe) y login (`401 Unauthorized` si credenciales inválidas).
- `AuthController`:
  - `POST /api/auth/register` -> `201 Created` (`AuthResponse`)
  - `POST /api/auth/login` -> `200 OK` (`AuthResponse`)
- DTOs propios: `RegisterRequest`, `LoginRequest`, `AuthResponse`.

### 2.2 Módulo `polls/`
- `PollStatus` (`OPEN`, `CLOSED`).
- `PollEntity` (tabla `polls`: `id` UUID, `ownerId`, `title`, `status`, `createdAt`, `closedAt`).
- `PollOptionEntity` (tabla `poll_options`: `id` UUID, `pollId`, `text`, `displayOrder`).
- `VoteEntity` (tabla `votes`: `id` UUID, `pollId`, `optionId`, `deviceId`, `castAt`).
- Repositorios: `PollRepository`, `PollOptionRepository`, `VoteRepository`.
- `PollService`:
  - `createPoll(ownerId, CreatePollRequest)`: valida título y **2 a 6 opciones** (`201 Created`).
  - `getMyPolls(ownerId)`: devuelve únicamente las encuestas del presentador autenticado (`200 OK`).
  - `closePoll(ownerId, pollId)`: `404` si no existe, `403` si no es el dueño, actualiza estado a `CLOSED` y `closedAt` (`200 OK`).
  - `getPublicPoll(pollId)`: devuelve pregunta, opciones ordenadas y estado (`200 OK` o `404`).
  - `castVote(pollId, CastVoteRequest)`: valida existencia de encuesta (`404`), que esté `OPEN` (`422 Unprocessable Entity` si `CLOSED`), que la opción pertenezca a esa encuesta (`404`) y registra el voto capturando violación de unicidad `(poll_id, device_id)` como `409 Conflict`; éxito devuelve `201 Created`.
- `PollController`:
  - `POST /api/polls` (auth)
  - `GET /api/polls/mine` (auth)
  - `POST /api/polls/{id}/close` (auth, solo dueño)
  - `GET /api/polls/{id}` (público)
  - `POST /api/polls/{id}/votes` (público)
- DTOs propios: `CreatePollRequest`, `PollResponse`, `PollOptionResponse`, `CastVoteRequest`, `VoteResponse`.

### 2.3 Módulo `common/`
- `SecurityProperties`: propiedad `app.security.trust-gateway-header` (default `false` en F1; en F3 pasa a `true` detrás de `api-gateway` en red interna).
- `AuthenticationFilter` (`OncePerRequestFilter`):
  - Si `trust-gateway-header=false`: valida `Authorization: Bearer <jwt>` mediante `JwtService` e **ignora cualquier `X-User-Id`** que envíe el cliente.
  - Si `trust-gateway-header=true`: toma `X-User-Id` inyectada por el Gateway.
- `SecurityConfig`: sesión `STATELESS`, `BCryptPasswordEncoder`, orden estricto de reglas (`GET /api/polls/mine` autenticado **antes** de permitir `GET /api/polls/{id}` público).
- `GlobalExceptionHandler` (`@RestControllerAdvice`) y excepciones de dominio (`ConflictException`, `UnauthorizedException`, `ForbiddenException`, `ResourceNotFoundException`, `UnprocessableEntityException`) con respuesta JSON uniforme (`timestamp`, `status`, `error`, `message`, `path`).

---

## 3. Persistencia, Flyway y resiliencia

- Migración inicial `src/main/resources/db/migration/V1__init_votes_schema.sql`:
  - Tablas `users`, `polls`, `poll_options`, `votes`.
  - Índices por `polls(owner_id)` y `poll_options(poll_id)`.
  - Restricción `CONSTRAINT uk_votes_poll_device UNIQUE (poll_id, device_id)`.
- `application.yml`:
  - `spring.jpa.hibernate.ddl-auto=validate`
  - `spring.flyway.enabled=true`
  - HikariCP con timeouts explícitos (`connection-timeout: 5000`, `validation-timeout: 3000`)
  - Actuator: `/actuator/health/liveness` y `/actuator/health/readiness` (`db`) habilitados.

---

## 4. Docker y Compose inicial

- `services/votes-service/Dockerfile`: multi-stage con tags fijos (`maven:3.9.9-eclipse-temurin-17-alpine` para build y `eclipse-temurin:17.0.14_7-jre-alpine` para runtime; en F7 se migra a Distroless multi-arch).
- `docker-compose.yml` (base):
  - Red interna `livevote-internal`.
  - `postgres-votes` (`postgres:16.6-alpine`), volumen `postgres_votes_data`, base `POSTGRES_VOTES_DB`, usuario `POSTGRES_VOTES_USER`, `healthcheck` con `pg_isready`.
  - `votes-service` conectado a `postgres-votes` con `depends_on: condition: service_healthy`.
- `docker-compose.override.yml` (desarrollo local):
  - Expone `8081:8081` (`votes-service`) y `5433:5432` (`postgres-votes`) para pruebas locales.

---

## 5. Plan de verificación

1. **Tests unitarios**:
   - `JwtServiceTest`: generación, lectura de `sub`, expiración y validación de longitud mínima de secreto.
   - `IdentityServiceTest`: registro, duplicado (`409`), BCrypt y login inválido (`401`).
   - `PollServiceTest`: validación de 2–6 opciones, propiedad al cerrar (`403`), encuesta cerrada (`422`) y opción ajena (`404`).
2. **Test de integración real (`VotesServiceIntegrationTest` con Testcontainers `postgres:16.6-alpine`)**:
   - Flujo completo HTTP: registro -> login -> creación de encuesta -> listado `/mine` -> rechazo de `X-User-Id` falsificado sin JWT (`401`) -> voto (`201`) -> voto duplicado mismo `deviceId` (`409` vía constraint PostgreSQL real) -> cierre por otro usuario (`403`) -> cierre por dueño (`200`) -> voto posterior (`422`).
3. **Verificación local y Compose**:
   - `mvn test` en `services/votes-service` al 100 % en verde.
   - `docker compose config` válido sin errores.
   - `docker compose up -d --build` y comprobación de `GET http://localhost:8081/actuator/health/readiness` en estado `UP`.

---

## 6. Cierre de fase F1

- [x] Marcar todos los ítems de F1 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.1.0)`.
- [x] Actualizar tabla de fases en `README.md`.
- [x] Agregar sección `## [0.1.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.1.0`.

---

## Desvíos y aprendizajes

- **Testcontainers `1.21.4` por Docker Engine `29.9.3`**: Spring Boot `3.4.3` trae por defecto Testcontainers `1.20.5`, cuyo cliente `docker-java` solicita una versión de API antigua rechazada con HTTP `400` por Docker Engine `29.9.3` (mínimo `1.40`). Se fijó `<testcontainers.version>1.21.4</testcontainers.version>` en `services/votes-service/pom.xml`.
- **JDK 21 local compilando hacia Java 17**: el host tiene instalado JDK `21.0.10`, por lo que se configuró `<maven.compiler.release>17</maven.compiler.release>` en `pom.xml`, mientras que el `Dockerfile` compila y corre nativamente sobre imágenes de Java 17.
- **Grupo `readiness` en Actuator**: se configuró explícitamente `management.endpoint.health.group.readiness.include: readinessState,db` para garantizar que `/actuator/health/readiness` refleje el estado real de `postgres-votes`.
- **Variables en `docker-compose.yml` vs `docker-compose.override.yml`**: la definición base no incluye contraseñas por defecto; los fallbacks de desarrollo local (`votes_db`, `votes_dev_password`, etc.) viven únicamente en `docker-compose.override.yml`, permitiendo levantar en local con `docker compose up --build` sin copiar `.env`.
