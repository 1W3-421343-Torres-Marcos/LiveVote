---
name: results-service
description: >-
  Guía de implementación para results-service con WebFlux, SSE, JPA fuera del event loop e idempotencia AMQP.
  Activar esta skill al trabajar en el consumidor de eventos, proyección de conteos, endpoints GET /api/results/*,
  streams SSE con heartbeat o manejo de errores y DLQ en services/results-service.
---

# Skill: `results-service`

Servicio de **lectura y tiempo real**, propietario exclusivo de `postgres-results`. Consume eventos desde RabbitMQ, mantiene conteos agregados de forma idempotente y expone resultados por HTTP y Server-Sent Events (SSE).

## Stack y regla no bloqueante (WebFlux + JPA)

- **Stack**: Java 17, Spring Boot 3.x, Maven, Spring WebFlux, Spring Data JPA, Flyway, Spring AMQP, PostgreSQL.
- **Estructura de paquetes (Package by Layer)**: bajo `com.livevote.results`, usar únicamente los paquetes necesarios (`config`, `controller`, `service`, `repository`, `entity`, `dto`, `event`, `exception`), sin paquete genérico `common` y espejando la misma estructura en `src/test/java`.
- **Regla estricta**: NUNCA ejecutar llamadas bloqueantes JPA/JDBC en el event loop de Reactor Netty:
  - En el consumidor AMQP: corre en hilos del contenedor listener de Spring AMQP.
  - En los endpoints WebFlux (`GET /api/results/{id}`, carga inicial de SSE): envolver toda lectura JPA en `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.

## Endpoints y streaming SSE

| Método y ruta | Acceso | Comportamiento |
| :--- | :--- | :--- |
| `GET /api/results/{id}` | Público | Devuelve conteos actuales por opción, título y estado de la encuesta (`200` o `404`). |
| `GET /api/results/{id}/stream` | Público | Stream `text/event-stream` (`Flux<ServerSentEvent<...>>`): emite snapshot inicial al conectarse, cada actualización desde `Sinks.Many` por encuesta y un evento `heartbeat` cada **~20 segundos** para evitar cortes de proxies/navegadores. |

- Limpiar suscripciones al cancelarse el cliente SSE para evitar fugas de memoria.
- **Limitación conocida**: corre en **1 sola instancia** porque los `Sinks.Many` viven en memoria local (el fan-out multi-réplica es una mejora opcional en F9).

## Idempotencia y manejo de errores del consumidor

- **Esquema Flyway**: `poll_projections`, `option_counts`, `processed_votes` (`vote_id` como `PRIMARY KEY`).
- **Idempotencia**: dado que RabbitMQ entrega *at-least-once*, verificar/insertar `vote_id` en `processed_votes` dentro de la **misma transacción** que incrementa `option_counts`. Si el `vote_id` ya existe, ignorar el evento de forma segura sin duplicar conteos ni emitir actualización duplicada.
- **Fallos transitorios vs Poison messages**:
  - Fallo transitorio (ej. `postgres-results` caída o timeout de conexión): configurar reintentos con backoff exponencial acotado (`RetryInterceptorBuilder` o `spring.rabbitmq.listener.simple.retry.*`) sin descartar el mensaje; si persiste al agotarse los intentos locales o se requiere espera mayor, manejar según política definida en `messaging-contracts` para que no se pierdan votos durante Demo (c).
  - Mensaje inválido / corrupto / JSON incompatible: lanzar `AmqpRejectAndDontRequeueException` para enviarlo de inmediato a la DLQ (`results.events.dlq`).

## Verificación

1. Ejecutar tests unitarios e integración (Testcontainers PostgreSQL + RabbitMQ) en `services/results-service`:
   `mvn test`
2. Probar flujo SSE en local verificando snapshot inicial, actualización en vivo tras un voto y `heartbeat` cada ~20 s:
   `curl -N http://localhost:<port>/api/results/<pollId>/stream`
