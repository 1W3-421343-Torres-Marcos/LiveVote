# Plan de Implementación — Fase F2: RabbitMQ y `results-service`

- **Fase**: `F2`
- **Versión objetivo**: `v0.2.0`
- **Estado**: `Completado (v0.2.0)`
- **Fecha**: `2026-11-26`

---

## Objetivo

Conectar `votes-service` y el nuevo microservicio de lectura `results-service` exclusivamente mediante mensajería asíncrona en **RabbitMQ**, manteniendo aislamiento total de bases de datos (`postgres-votes` vs `postgres-results`), contratos JSON versionados sin código compartido, organización estricta **Package by Layer**, idempotencia atómica en `processed_votes` y manejo diferenciado de fallos transitorios vs *poison messages*.

---

## 1. Topología RabbitMQ y política de errores

- **Exchange principal**: `livevote.events` (`topic`, durable).
- **Exchange de reintento**: `livevote.retry` (`topic`, durable).
- **Dead Letter Exchange**: `livevote.dlx` (`direct`, durable).
- **Cola principal**: `results.events.queue` (durable, `x-dead-letter-exchange = livevote.dlx`, `x-dead-letter-routing-key = results.events.dlq`), enlazada a `poll.created` y `vote.cast` (y preparada para `poll.closed` en F9).
- **Cola de reintento diferido**: `results.events.retry.queue` (durable, `x-message-ttl = 5000` configurable, `x-dead-letter-exchange = livevote.events` preservando el routing key original), enlazada a `livevote.retry` con `#`.
- **Dead Letter Queue (DLQ)**: `results.events.dlq` (durable), enlazada a `livevote.dlx` con routing key `results.events.dlq`.
- **Política de errores del consumidor**:
  - **Fallos transitorios** (ej. `postgres-results` caída / error de acceso a datos): 3 reintentos locales con backoff exponencial (`500 ms`, multiplicador `2.0`, máx. `2000 ms`); si persiste el fallo, se republica en `livevote.retry` (`results.events.retry.queue`) sin descartar el mensaje hacia la DLQ.
  - **Eventos fuera de orden** (`VoteCast` que llega antes que `PollCreated`): `PollProjectionPendingException` -> reintento diferido en `livevote.retry` hasta `max-pending-retries = 5` antes de derivar a DLQ si la encuesta no existe.
  - **Poison messages** (JSON malformado, `eventVersion != 1`, campos obligatorios nulos o routing key desconocida): lanza `AmqpRejectAndDontRequeueException` de inmediato hacia `results.events.dlq`.
- **Cero acoplamiento Java**: no se envían ni leen cabeceras `__TypeId__`; cada servicio define sus propios DTOs en `event/` y deserializa por routing key + `ObjectMapper`.

---

## 2. Cambios en `services/votes-service` (`0.2.0`)

- **Maven (`pom.xml`)**: subir versión a `0.2.0`, agregar `spring-boot-starter-amqp` y `org.testcontainers:rabbitmq` (`test`).
- **`config/RabbitMqConfig`**: declara `TopicExchange("livevote.events")` durable y constantes de routing keys.
- **`event/`**:
  - `PollCreatedOptionEvent(optionId, text)`
  - `PollCreatedEvent(eventVersion=1, pollId, title, options, createdAt)` *(sin `ownerId` ni email)*.
  - `VoteCastEvent(eventVersion=1, voteId, pollId, optionId, castAt)`.
  - `VoteEventPublisher`: escucha con `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` para publicar en RabbitMQ únicamente tras confirmar la transacción en `postgres-votes`, capturando errores de broker sin romper respuestas `201 Created`.
- **`service/PollService`**: emite `PollCreatedEvent` en `createPoll` y `VoteCastEvent` en `castVote` vía `ApplicationEventPublisher`.
- **Verificación en `votes-service`**: `PollServiceTest`, `VoteEventPublisherTest` y `VotesServiceIntegrationTest` ampliado con `RabbitMQContainer`.

---

## 3. Nuevo microservicio `services/results-service` (`0.2.0`)

- **Stack**: Java 17 (`--release 17`), Spring Boot `3.4.3`, `spring-boot-starter-webflux` (sin `starter-web`), `spring-boot-starter-data-jpa`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`, Flyway + PostgreSQL, Testcontainers (`1.21.4`).
- **Estructura Package by Layer (`com.livevote.results`)** (sin `common` ni `security`, espejada en `src/test/java`):
  - `config/`: `MessagingProperties`, `RabbitMqConfig`.
  - `controller/`: `ResultsController` (`GET /api/results/{id}` envuelto en `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`).
  - `service/`: `ResultsProjectionService`.
  - `repository/`: `PollProjectionRepository`, `OptionCountRepository`, `ProcessedVoteRepository`.
  - `entity/`: `PollProjectionStatus`, `PollProjectionEntity`, `OptionCountEntity`, `ProcessedVoteEntity`.
  - `dto/`: `PollResultsResponse`, `OptionResultResponse`.
  - `event/`: `PollCreatedOptionEvent`, `PollCreatedEvent`, `VoteCastEvent`, `ResultsEventConsumer`.
  - `exception/`: `ApiErrorResponse`, `ResultsNotFoundException`, `InvalidEventException`, `PollProjectionPendingException`, `GlobalExceptionHandler`.
- **Migración Flyway (`V1__init_results_schema.sql`)**: tablas `poll_projections`, `option_counts` y `processed_votes` (`vote_id` PK).
- **Idempotencia atómica (`@Transactional`)**: `INSERT INTO processed_votes ... ON CONFLICT (vote_id) DO NOTHING`; solo si inserta 1 fila ejecuta `UPDATE option_counts SET vote_count = vote_count + 1 ...`.
- **Docker**: `services/results-service/Dockerfile` multi-stage con tags fijos (`maven:3.9.9-eclipse-temurin-17-alpine` -> `eclipse-temurin:17.0.14_7-jre-alpine`, usuario no-root `app`, puerto `8082`).

---

## 4. Actualización de Docker Compose

- **`docker-compose.yml`**: agrega `postgres-results` (`postgres:16.6-alpine` + volumen `postgres_results_data`), `rabbitmq` (`rabbitmq:3.13.7-alpine` + volumen `rabbitmq_data`), `results-service` (conectado solo a `postgres-results` y `rabbitmq`) y las variables de RabbitMQ en `votes-service`.
- **`docker-compose.override.yml`**: expone `5434:5432` (`postgres-results`), `5672:5672` y `15672:15672` (`rabbitmq:3.13.7-management-alpine`), `8082:8082` (`results-service`) y fallbacks de desarrollo.

---

## 5. Plan de verificación

1. `mvn -B -f "services\votes-service\pom.xml" test` (unitarios + Testcontainers PostgreSQL y RabbitMQ).
2. `mvn -B -f "services\results-service\pom.xml" test` (unitarios + `@WebFluxTest` + `ResultsServiceIntegrationTest` con Testcontainers PostgreSQL y RabbitMQ: flujo normal, idempotencia de duplicados, reintento fuera de orden y poison message en DLQ).
3. `docker compose config` + `docker compose up -d --build` (5 contenedores `healthy`) + smoke test HTTP de punta a punta entre `localhost:8081` y `localhost:8082` + `docker compose down -v`.

---

## 6. Cierre de fase F2 (luego de aprobación del resumen de verificación)

- [x] Marcar ítems de F2 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.2.0)`.
- [x] Actualizar tabla de fases y ejecución local en `README.md`.
- [x] Agregar sección `## [0.2.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.2.0`.

---

## Desvíos y aprendizajes

- **Preservación de routing key en reintentos diferidos**: `results.events.retry.queue` define `x-dead-letter-exchange = livevote.events` sin sobrescribir `x-dead-letter-routing-key`, de modo que RabbitMQ conserva `poll.created` o `vote.cast` al expirar el TTL y reinyectar el mensaje en `results.events.queue`.
- **Colas temporales en tests con `RabbitTemplate.receive`**: en `VotesServiceIntegrationTest`, declarar la cola temporal con `.autoDelete()` provoca que RabbitMQ la elimine tras consumir el primer mensaje al cerrarse el consumidor temporal; se dejó sin `.autoDelete()` limpiándola explícitamente en el bloque `finally`.
- **Polling asíncrono sobre `WebTestClient`**: en `ResultsServiceIntegrationTest`, mientras `PollCreated` aún no se proyecta, `GET /api/results/{id}` responde `404` (`ApiErrorResponse` con `"status": 404`), por lo que el helper lee primero `byte[]` y deserializa `PollResultsResponse` únicamente cuando el código HTTP es `2xx`.
