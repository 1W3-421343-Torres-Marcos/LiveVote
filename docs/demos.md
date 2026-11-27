# Guión y evidencia de demostraciones — LiveVote

Guía reproducible para ejecutar las demostraciones de resiliencia y observabilidad de **LiveVote** sobre Docker Compose.

---

## Prerrequisitos del entorno local

1. Levantar el stack completo desde la raíz del repositorio:

   ```bash
   docker compose up -d --build
   docker compose ps
   ```

   Deben figurar los **7 contenedores** activos (`frontend`, `api-gateway`, `votes-service`, `results-service`, `rabbitmq`, `postgres-votes` y `postgres-results`) en estado `healthy`.

2. Puntos de entrada locales (perfil dev por defecto):

   | Componente | Acceso local | Acceso red interna Docker |
   | :--- | :--- | :--- |
   | SPA + reverse proxy (`frontend`) | `http://localhost:4200` | `http://frontend:80` |
   | `api-gateway` | `http://localhost:8080` | `http://api-gateway:8080` |
   | `votes-service` | — *(solo red interna)* | `http://votes-service:8081` |
   | `results-service` | — *(solo red interna)* | `http://results-service:8082` |
   | RabbitMQ Management | `http://localhost:15672` | `rabbitmq:5672` |
   | `postgres-votes` / `postgres-results` | `localhost:5433` / `localhost:5434` | `postgres-votes:5432` / `postgres-results:5432` |

3. Nota PowerShell (`5.1` en Windows): al usar `curl` para SSE usar siempre `curl.exe` (evita el alias `Invoke-WebRequest`), con `-N` para desactivar buffering.
4. Contenedores Distroless (sin shell ni `wget`): los microservicios Java incluyen el probe `HealthCheck` compilado en `/app`; para consultar endpoints internos de Actuator ejecutar `docker compose exec -T <servicio> java -Xmx24m -XX:TieredStopAtLevel=1 -cp /app HealthCheck --print <url>`.

---

## Demo (a) — Escalado horizontal de `votes-service` (`--scale votes-service=3`)

Demuestra que el balanceo en `api-gateway` (`UpstreamClientConfig` con `roundRobinSelection(true)` + `cacheMaxTimeToLive(5 s)` y `spring.cloud.gateway.httpclient.pool.type: disabled` para evitar pinning TCP por keep-alive) distribuye tráfico real entre múltiples réplicas de `votes-service`.

### Pasos reproducibles

1. **Levantar con 3 réplicas de `votes-service`**:

   ```bash
   docker compose up -d --build --scale votes-service=3
   docker compose ps
   ```

   Deben figurar **9 contenedores** en estado `healthy` (`votes-service-1`, `votes-service-2`, `votes-service-3`, más `results-service`, `api-gateway`, `frontend`, `rabbitmq`, `postgres-votes`, `postgres-results`).

2. **Emitir ráfaga secuencial de consultas y votos inspeccionando `X-Instance-Id`**:

   ```powershell
   1..12 | ForEach-Object {
     (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:4200/api/polls/$pollId").Headers['X-Instance-Id']
   }
   ```

3. **Contrastarlo con logs de cada réplica**: confirmar líneas JSON en `docker logs plataformavotacion-votes-service-{1,2,3}` con `instance=<id>` y coincidencia con los UUID atendidos.

### Evidencia verificada (F9)

- 9/9 contenedores `healthy` bajo `docker compose up -d --build --scale votes-service=3`.
- Sobre 15 solicitudes secuenciales (`12` lecturas `GET /api/polls/{pollId}` + `3` votos `POST .../votes`):
  - **3 réplicas distintas** respondieron con cabecera `X-Instance-Id`:
    - `5407a2cff143` (`votes-service-3`): `9` solicitudes (`matchedRequests=9` en logs del contenedor)
    - `5eab0306f2ab` (`votes-service-2`): `3` solicitudes (`matchedRequests=3` en logs del contenedor)
    - `fbd77f45ba59` (`votes-service-1`): `3` solicitudes (`matchedRequests=3` en logs del contenedor)

---

## Demo (b) — Caída y autorrecuperación de `results-service` + `PollClosed` en vivo por SSE

Demuestra que si cae el servicio consumidor (`results-service`), `votes-service` continúa aceptando votos (`201`) sin perder eventos ni enviar mensajes a DLQ, mientras `api-gateway` responde `503` controlado en lecturas, y al reiniciar `results-service` concilia conteos y además propaga en vivo el evento `PollClosed` por SSE.

### Pasos reproducibles

1. **Estado base**: crear encuesta y emitir 1 voto inicial -> confirmar `GET http://127.0.0.1:4200/api/results/{pollId}` con `totalVotes = 1`.
2. **Detener `results-service`**:

   ```bash
   docker compose stop results-service
   ```

3. **Verificar aislamiento y resiliencia durante la caída**:
   - `GET http://127.0.0.1:4200/api/results/{pollId}` responde `503 Service Unavailable` desde el Circuit Breaker / fallback del Gateway.
   - Emitir 3 votos nuevos por `POST http://127.0.0.1:4200/api/polls/{pollId}/votes` -> todos responden `201 Created`.
   - Verificar colas directamente en RabbitMQ:

     ```bash
     docker compose exec -T rabbitmq rabbitmqctl list_queues -q name messages_ready messages_unacknowledged
     ```

     Esperado: `results.events.queue` acumula `3` mensajes listos (`ready=3`) y `results.events.dlq` permanece en `0`.

4. **Reiniciar `results-service` y confirmar conciliación**:

   ```bash
   docker compose start results-service
   ```

   Esperar a `GET http://127.0.0.1:4200/api/results/{pollId}` con `totalVotes = 4` y verificar `results.events.dlq = 0`.

5. **Verificar `PollClosed` en vivo por SSE** (mejora F9):
   - Abrir stream `curl.exe -N -sS -i http://127.0.0.1:4200/api/results/{pollId}/stream` (recibe snapshot `status: "OPEN"`, `totalVotes: 4`).
   - Cerrar encuesta (`POST http://127.0.0.1:4200/api/polls/{pollId}/close` con JWT del dueño -> `200`).
   - Confirmar recepción inmediata de trama `event:results` en el mismo stream con `"status":"CLOSED"` y `closedAt` no nulo.

### Evidencia verificada (F9)

- Estado base: `totalVotes=1`.
- Con `results-service` detenido:
  - Lectura `GET /api/results/{pollId}`: HTTP `503`.
  - 3 votos nuevos: `201,201,201`.
  - `rabbitmqctl list_queues`: `results.events.queue ready=3 unacked=0`, `results.events.retry.queue ready=0`, `results.events.dlq ready=0`.
- Tras `docker compose start results-service`:
  - Conteo reconciliado automáticamente: `totalVotes=4` (`option1=3`, `option2=1`), `results.events.dlq=0`.
- `PollClosed` en vivo por SSE:
  - `POST .../close` -> HTTP `200`.
  - Trama SSE recibida en el stream abierto: `event:results` con `"status":"CLOSED"`, `"totalVotes":4` y `"closedAt":"2026-11-27T22:47:22.484868011Z"`.

---

## Demo (c) — Caída y autorrecuperación de `postgres-results`

Demuestra que la caída de la base de proyecciones (`postgres-results`) no bloquea ni pierde votos en `votes-service`, mantiene `results.events.dlq` en `0` mediante reintentos en RabbitMQ y concilia automáticamente los conteos al restaurar la base.

### Pasos reproducibles

1. **Estado base**: crear usuario, encuesta (`pollId`, opciones `optionA` y `optionB`) y emitir un primer voto (`POST /api/polls/{pollId}/votes`). Confirmar `GET http://localhost:4200/api/results/{pollId}` con `totalVotes = 1`.
2. **Simular caída de `postgres-results`**:

   ```bash
   docker compose stop postgres-results
   ```

3. **Verificar aislamiento de salud** (probes internos sin shell en Distroless):

   ```bash
   docker compose exec -T results-service java -Xmx24m -XX:TieredStopAtLevel=1 -cp /app HealthCheck --print http://127.0.0.1:8082/actuator/health/readiness
   docker compose exec -T results-service java -Xmx24m -XX:TieredStopAtLevel=1 -cp /app HealthCheck --print http://127.0.0.1:8082/actuator/health/liveness
   docker compose exec -T votes-service java -Xmx24m -XX:TieredStopAtLevel=1 -cp /app HealthCheck --print http://127.0.0.1:8081/actuator/health/readiness
   ```

   Esperado: `results-service` pasa `/actuator/health/readiness` a `503` (`db` en `DOWN`, `rabbit` en `UP`), manteniendo `/actuator/health/liveness` en `200`, mientras `votes-service` sigue `200 UP`.

4. **Seguir votando durante la caída**: emitir dos votos adicionales por `POST http://localhost:4200/api/polls/{pollId}/votes` (con distintos `deviceId`). Ambos deben responder `201 Created`.
5. **Verificar RabbitMQ (`http://localhost:15672`)**: comprobar en `/api/queues/%2F` (o consola web) que `results.events.dlq` permanece en `0` mensajes mientras `results-service` reintenta vía `results.events.retry.queue` *(nota: RabbitMQ Management actualiza métricas cada 5 s y los mensajes en curso pueden mostrarse transitoriamente como `unacked` o en retry)*.
6. **Restaurar `postgres-results`**:

   ```bash
   docker compose start postgres-results
   ```

7. **Confirmar recuperación automática y conciliación**: verificar que `results-service` vuelve solo a `readiness` `200 UP` (sin reiniciar el contenedor), que `GET http://localhost:4200/api/results/{pollId}` alcanza `totalVotes = 3` (`optionA = 2`, `optionB = 1`) y que `results.events.dlq` continúa en `0`.

### Evidencia verificada (F6)

- Estado inicial de los 7 contenedores: todos `healthy` (`COLD_REGISTER_STATUS=201`, `COLD_CREATE_POLL_STATUS=201`).
- Con `postgres-results` detenido:
  - `results-service` `/actuator/health/readiness`: HTTP `503` con `{"status":"DOWN","components":{"db":{"status":"DOWN"},"rabbit":{"status":"UP"},"readinessState":{"status":"UP"}}}`
  - `results-service` `/actuator/health/liveness`: HTTP `200`
  - `votes-service` `/actuator/health/readiness`: HTTP `200`
  - Emisión de 2 votos nuevos: `201,201`
  - Cola DLQ (`results.events.dlq`): `0` mensajes
- Tras `docker compose start postgres-results`:
  - `results-service` `/actuator/health/readiness`: HTTP `200` con `{"status":"UP","components":{"db":{"status":"UP"},"rabbit":{"status":"UP"},"readinessState":{"status":"UP"}}}`
  - Resultado proyectado reconciliado: `totalVotes=3`, `optionA=2`, `optionB=1`
  - `results.events.dlq` tras recuperación: `0` mensajes

---

## Demo (d) — Caída y autorrecuperación de `postgres-votes`

Demuestra que la caída de la base transaccional (`postgres-votes`) degrada de forma controlada las escrituras (`503`) sin afectar las lecturas de `results-service` ni cortar streams SSE abiertos, y que la recuperación posterior vuelve a emitir eventos en vivo por el mismo stream.

### Pasos reproducibles

1. **Abrir stream SSE en vivo** (mantener abierto en una terminal):

   ```bash
   curl.exe -N -sS -i http://localhost:4200/api/results/{pollId}/stream
   ```

   Esperado: evento inicial `event:results` con `retry:3000` y `totalVotes: 3`. En red interna, `http://127.0.0.1:8082/actuator/metrics/livevote.sse.active.channels` informa `1.0`.

2. **Simular caída de `postgres-votes`**:

   ```bash
   docker compose stop postgres-votes
   ```

3. **Verificar aislamiento de salud, lecturas vivas y degradación controlada**:
   - `votes-service` `/actuator/health/readiness` pasa a `503` (`db` en `DOWN`, `rabbit` en `UP`), manteniendo `/actuator/health/liveness` en `200`.
   - `GET http://localhost:4200/api/results/{pollId}` sigue respondiendo `200 OK` y el `curl.exe` SSE no se cierra.
   - Intentar escrituras (`POST http://localhost:4200/api/polls/{pollId}/votes` y `POST http://localhost:4200/api/polls`) devuelve `503 Service Unavailable` controlado.
4. **Restaurar `postgres-votes`**:

   ```bash
   docker compose start postgres-votes
   ```

5. **Confirmar recuperación automática y actualización SSE en vivo**:
   - `votes-service` vuelve automáticamente a `readiness` `200 UP` (esperar ~5-10 s si el Circuit Breaker del Gateway entró en estado abierto/half-open durante las fallas).
   - Emitir un nuevo voto por `POST http://localhost:4200/api/polls/{pollId}/votes` -> devuelve `201 Created`.
   - Verificar en la terminal SSE abierta que llega inmediatamente una nueva trama `event:results` con `totalVotes: 4` sin haber reconectado el cliente.

### Evidencia verificada (F6)

- Snapshot SSE inicial capturado antes de la caída:
  - Cabeceras: `HTTP/1.1 200 OK`, `Content-Type: text/event-stream;charset=UTF-8`, `X-Accel-Buffering: no`, cabecera `X-Trace-Id` única.
  - Métrica SSE: `livevote.sse.active.channels` -> `VALUE = 1.0`.
  - Trama inicial: `event:results`, `retry:3000`, `totalVotes: 3`.
- Con `postgres-votes` detenido:
  - `votes-service` `/actuator/health/readiness`: HTTP `503` con `{"status":"DOWN","components":{"db":{"status":"DOWN"},"rabbit":{"status":"UP"},"readinessState":{"status":"UP"}}}`
  - `votes-service` `/actuator/health/liveness`: HTTP `200`
  - `GET /api/results/{pollId}`: HTTP `200` y stream SSE vivo (`SSE_ALIVE=True`)
  - Escrituras (`POST .../votes` y `POST /api/polls`): `voteStatus=503`, `createPollStatus=503`
- Tras `docker compose start postgres-votes`:
  - `votes-service` `/actuator/health/readiness`: HTTP `200` con `{"status":"UP","components":{"db":{"status":"UP"},"rabbit":{"status":"UP"},"readinessState":{"status":"UP"}}}`
  - Nuevo voto tras recuperación: `201 Created`
  - Trama recibida en caliente en el mismo stream SSE: `event:results` con `"totalVotes":4` (`"votes":3` en la opción votada, `"votes":1` en la segunda).

---

## Apéndice — Evidencia de observabilidad (F6)

### Correlación distribuida de `traceId` en logs JSON

Ejemplo real capturado en Docker Compose enviando `X-Trace-Id: trace-f6-doc-001` sobre `POST /api/polls/{pollId}/votes` (sin credenciales, contraseñas ni tokens JWT en logs):

```json
{"timestamp":"2026-11-27T21:24:55.17530018Z","message":"Gateway request completed method=POST path=\/api\/polls\/e853f325-e150-4b25-9ecc-c7c7fb5c486f\/votes status=201 durationMs=83","logger":"com.livevote.gateway.config.TraceIdFilter","thread_name":"reactor-http-epoll-2","level":"INFO","traceId":"trace-f6-doc-001","service":"api-gateway"}
{"timestamp":"2026-11-27T21:24:55.163629603Z","message":"Published RabbitMQ event routingKey=vote.cast","logger":"com.livevote.votes.event.VoteEventPublisher","thread_name":"http-nio-8081-exec-6","level":"INFO","traceId":"trace-f6-doc-001","service":"votes-service"}
{"timestamp":"2026-11-27T21:24:55.171234885Z","message":"HTTP request completed method=POST path=\/api\/polls\/e853f325-e150-4b25-9ecc-c7c7fb5c486f\/votes status=201 durationMs=74","logger":"com.livevote.votes.config.TraceIdFilter","thread_name":"http-nio-8081-exec-6","level":"INFO","traceId":"trace-f6-doc-001","service":"votes-service"}
{"timestamp":"2026-11-27T21:24:55.282049342Z","message":"Projected VoteCast pollId=e853f325-e150-4b25-9ecc-c7c7fb5c486f voteId=33d99ee5-a7cd-4914-89f1-33839ea0c7c8 totalVotes=1","logger":"com.livevote.results.event.ResultsEventConsumer","thread_name":"org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1","level":"INFO","traceId":"trace-f6-doc-001","service":"results-service"}
```

### Métricas Golden Signals y Actuator verificadas (`200 OK`)

- **Gateway (`http://localhost:8080/actuator/...`)**:
  - `/actuator/health/readiness` y `/actuator/health/liveness`
  - `/actuator/metrics/http.server.requests` (con histogramas habilitados y tag `application=api-gateway`)
  - Contadores registrados al activarse el evento respectivo: `livevote.gateway.ratelimit.rejected.total` y `livevote.gateway.fallback.total`
- **`votes-service` (`http://127.0.0.1:8081/actuator/...` en red interna)**:
  - `/actuator/health/readiness` (`db`, `rabbit`, `readinessState`) y `/actuator/health/liveness`
  - `/actuator/metrics/livevote.events.published.total` (tags `routingKey`, `outcome`)
  - `/actuator/metrics/hikaricp.connections.active`
- **`results-service` (`http://127.0.0.1:8082/actuator/...` en red interna)**:
  - `/actuator/health/readiness` (`db`, `rabbit`, `readinessState`) y `/actuator/health/liveness`
  - `/actuator/metrics/livevote.events.consumed.total` (tags `routingKey`, `outcome`)
  - `/actuator/metrics/livevote.sse.active.channels` y `/actuator/metrics/livevote.sse.active.subscribers`
