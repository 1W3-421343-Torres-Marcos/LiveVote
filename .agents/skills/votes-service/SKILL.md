---
name: votes-service
description: >-
  Guía de implementación, contratos REST, seguridad, persistencia y verificación para votes-service.
  Activar esta skill al crear, modificar o depurar endpoints de autenticación (identity), encuestas (polls),
  votos, migraciones Flyway o publicación de eventos en services/votes-service.
---

# Skill: `votes-service`

Servicio de **escritura** y propietario exclusivo de `postgres-votes`. Contiene los módulos `identity` y `polls` (decisión documentada en `AGENTS.md`: evitar un tercer servicio y una tercera base para mantener bajo el consumo de RAM).

## Stack y estructura de paquetes

- **Stack**: Java 17, Spring Boot 3.x, Maven, Spring Web, Spring Data JPA, Spring Security, Spring AMQP, Flyway, PostgreSQL.
- **Estructura de paquetes (Package by Layer)** bajo `com.livevote.votes` (espejada en `src/test/java`):
  - `config/`: `SecurityConfig`, `SecurityProperties`, `JwtProperties`.
  - `security/`: `AuthenticationFilter`, `JwtService`.
  - `controller/`: `AuthController`, `PollController`.
  - `service/`: `IdentityService`, `PollService`.
  - `repository/`: `UserRepository`, `PollRepository`, `PollOptionRepository`, `VoteRepository`.
  - `entity/`: `UserEntity`, `PollEntity`, `PollOptionEntity`, `VoteEntity`, `PollStatus`.
  - `dto/`: requests/responses de auth, encuestas y votos (o `dto.auth` / `dto.poll` si crece).
  - `event/`: publicadores y DTOs de eventos RabbitMQ (`PollCreated`, `VoteCast`, `PollClosed` desde F2+).
  - `exception/`: `GlobalExceptionHandler`, `ApiErrorResponse` y excepciones de dominio.
  - Sin paquete genérico `common`; crear solo los paquetes necesarios en cada fase.
- **Capas**: `Controller -> Service -> Repository / Publisher`. Sin lógica de negocio en controllers.

## Contratos REST y reglas de negocio

| Método y ruta | Acceso | Reglas y respuestas |
| :--- | :--- | :--- |
| `POST /api/auth/register` | Público | Valida email/password, hashea con BCrypt, emite JWT (`201`). Nunca loguear password ni token. |
| `POST /api/auth/login` | Público | Verifica credenciales BCrypt, emite JWT (`200`) o `401`. |
| `POST /api/polls` | Auth | Crea encuesta con pregunta y **2 a 6 opciones** (`201`). Asocia `ownerId` del usuario autenticado. |
| `GET /api/polls/mine` | Auth | Lista únicamente las encuestas creadas por el usuario autenticado (`200`). |
| `POST /api/polls/{id}/close` | Auth | Solo el dueño puede cerrar (`403` si no es dueño, `404` si no existe, `200`/`204` al cerrar). |
| `GET /api/polls/{id}` | Público | Devuelve pregunta, opciones y estado (`open`/`closed`) para la pantalla de votación (`200` o `404`). |
| `POST /api/polls/{id}/votes` | Público | Body `{optionId, deviceId}`. Responde `201` inmediatamente sin esperar a `results-service`; `409` si `(poll_id, device_id)` ya votó; `404` si encuesta u opción no pertenece/no existe; `422` si la encuesta está cerrada. |

## Resolución de identidad (F1 aislado vs F3+ con Gateway)

- Configurar la propiedad `app.security.trust-gateway-header` (default `false` en desarrollo aislado, `true` cuando corre detrás de `api-gateway` en red interna):
  - Si `true`: extrae el usuario autenticado de la cabecera `X-User-Id` inyectada por el Gateway.
  - Si `false`: valida `Authorization: Bearer <token>` con `JwtService` (HS256, `JWT_SECRET` >= 32 bytes, expiración 2 h = 7200 s).
- Esto permite probar `votes-service` end-to-end en F1 y previene suplantación si el puerto quedara expuesto en desarrollo.

## Persistencia, eventos y resiliencia

- **Flyway**: tablas mínimas `users`, `polls`, `poll_options`, `votes` con `UNIQUE (poll_id, device_id)`.
- **Publicación de eventos**: publicar `PollCreated` y `VoteCast` después de confirmar la transacción en base de datos (`AFTER_COMMIT`), evitando publicar votos que luego hagan rollback.
- **Recuperación tras caída de `postgres-votes` (Demo d)**: configurar HikariCP con timeouts explícitos (`connection-timeout`, `validation-timeout`), habilitar arranque/recuperación resiliente cuando la DB regresa y exponer `/actuator/health/readiness` reflejando `db`.

## Verificación

1. Ejecutar tests unitarios e integración (Testcontainers PostgreSQL + RabbitMQ) dentro de `services/votes-service`:
   `mvn test`
2. Validar respuestas HTTP (`201`, `409`, `404`, `422`, `401`, `403`) y comprobar que `POST /api/polls/{id}/votes` responde `201` incluso con `results-service` detenido.
