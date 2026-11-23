---
name: messaging-contracts
description: >-
  Contratos JSON versionados de eventos, topología RabbitMQ y políticas de reintentos/DLQ de LiveVote.
  Activar esta skill al definir o modificar productores, consumidores, colas, exchanges, DLQ
  o esquemas de eventos (PollCreated, VoteCast, PollClosed) entre votes-service y results-service.
---

# Skill: `messaging-contracts`

Define la comunicación exclusivamente asíncrona entre `votes-service` (productor) y `results-service` (consumidor). **Prohibido crear módulos Java compartidos**: cada servicio define sus propios DTOs; el contrato oficial es el JSON versionado.

## Topología RabbitMQ

- **Exchange principal**: `livevote.events` (tipo `topic`, `durable = true`).
- **Dead Letter Exchange**: `livevote.dlx` (tipo `direct` o `topic`, `durable = true`).
- **Cola principal de resultados**: `results.events.queue` (`durable = true`, `x-dead-letter-exchange = livevote.dlx`, `x-dead-letter-routing-key = results.events.dlq`).
- **Dead Letter Queue (DLQ)**: `results.events.dlq` (`durable = true`), enlazada a `livevote.dlx`.
- **Routing keys**:
  - `poll.created` -> `PollCreated`
  - `vote.cast` -> `VoteCast`
  - `poll.closed` -> `PollClosed` (fase tardía F9)

## Contratos JSON v1

Todos los eventos incluyen `"eventVersion": 1`, timestamps en **ISO-8601 UTC** (`Instant`) y propagan `traceId` en las cabeceras AMQP (`X-Trace-Id` / `traceId`).

### `PollCreated` (`poll.created`)
> **Regla de privacidad**: NO incluye `ownerId`, email ni ningún dato del dueño de la encuesta.

```json
{
  "eventVersion": 1,
  "pollId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "¿Qué framework prefieren para el TP?",
  "options": [
    { "optionId": "11111111-1111-1111-1111-111111111111", "text": "Spring Boot" },
    { "optionId": "22222222-2222-2222-2222-222222222222", "text": "Angular" }
  ],
  "createdAt": "2026-11-22T23:00:00Z"
}
```

### `VoteCast` (`vote.cast`)

```json
{
  "eventVersion": 1,
  "voteId": "99999999-9999-9999-9999-999999999999",
  "pollId": "550e8400-e29b-41d4-a716-446655440000",
  "optionId": "11111111-1111-1111-1111-111111111111",
  "castAt": "2026-11-22T23:05:12Z"
}
```

### `PollClosed` (`poll.closed`, F9)

```json
{
  "eventVersion": 1,
  "pollId": "550e8400-e29b-41d4-a716-446655440000",
  "closedAt": "2026-11-22T23:30:00Z"
}
```

## Política de errores en el consumidor

| Tipo de fallo | Ejemplo | Acción esperada |
| :--- | :--- | :--- |
| **Fallo transitorio** | `postgres-results` caída, pool agotado, timeout JDBC | Reintentar con backoff sin descartar el mensaje ni enviarlo a DLQ prematuramente (Demo c). |
| **Poison message** | JSON malformado, `eventVersion` desconocida, campos nulos obligatorios | Lanzar `AmqpRejectAndDontRequeueException` -> enrutar a `results.events.dlq`. |
| **Evento duplicado** | `voteId` ya presente en `processed_votes` | ACK exitoso sin modificar conteos (idempotencia). |

## Verificación

- Tests de serialización/deserialización JSON en ambos servicios verificando nombres de campos exactos y ausencia de datos del dueño en `PollCreated`.
- Test con Testcontainers RabbitMQ comprobando que un payload inválido termina en `results.events.dlq`.
