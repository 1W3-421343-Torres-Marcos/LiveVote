# LiveVote

Plataforma de votación en vivo (estilo Mentimeter simple) construida como sistema de **microservicios orientados a eventos** en Java 17 / Spring Boot 3.4, RabbitMQ, PostgreSQL, Angular 20 LTS y nginx. Proyecto de práctica y portfolio para **Programación IV (UTN FRC — Tecnicatura Universitaria en Programación)**.

---

## Arquitectura

```text
Navegador (Angular 20 SPA)
   │  (mismo origen /api -> sin CORS; SSE sin buffering)
   ▼
nginx (Frontend estático + Reverse Proxy)  [opcional Caddy TLS en prod]
   │
   ▼
api-gateway (Spring Cloud Gateway: JWT, X-User-Id, rate limit IP, Circuit Breaker, DNS round-robin)
   ├── /api/auth/**, /api/polls/**, /api/votes/** ──► votes-service ──► postgres-votes (base/volumen propios)
   │                                                        │
   │                                           (solo RabbitMQ: PollCreated, VoteCast, PollClosed + DLQ)
   │                                                        ▼
   └── /api/results/** ──────────────────────────────► results-service ──► postgres-results (base/volumen propios)
```

### ¿Por qué microservicios en LiveVote?

1. **Asimetría real de carga**:
   - **Escritura (`votes-service`)**: picos cortos y transaccionales al emitir votos, con validación estricta de unicidad (`poll_id`, `device_id`) e identidad del presentador; escalable horizontalmente (`--scale votes-service=3`) mediante DNS round-robin sin keep-alive pinning en `api-gateway`.
   - **Lectura y tiempo real (`results-service`)**: gran cantidad de clientes y pantallas de proyector consumiendo resultados y streams **SSE** de larga duración con latido (`heartbeat ~20 s`) y actualizaciones en vivo ante `VoteCast` y `PollClosed`.
2. **Aislamiento de fallos y bases independientes**:
   - `postgres-votes` y `postgres-results` corren en contenedores separados con volúmenes, bases y usuarios exclusivos.
   - Si cae `results-service` o `postgres-results`, se siguen aceptando votos (`201`) mientras los eventos esperan en RabbitMQ (`0` en DLQ). Si cae `postgres-votes`, `results-service` sigue atendiendo lecturas y SSE mientras las escrituras degradan de forma controlada a `503`.
3. **Modelos de datos distintos**:
   - `votes-service` persiste usuarios, encuestas, opciones y votos individuales (normalizado); `results-service` mantiene proyecciones desnormalizadas optimizadas para lectura rápida e idempotencia atómica (`ON CONFLICT DO NOTHING`).
4. **Trade-offs asumidos y evaluación de evolución**:
   - **Módulo `identity` dentro de `votes-service`**: evita sumar un tercer microservicio Java y una tercera base PostgreSQL, manteniendo el consumo total medido en **`1143.28 MiB`** (~1.12 GiB sobre `1952 MiB` de límite en producción para los 7 servicios).
   - **WebFlux + JPA fuera del event loop en `results-service`**: permite exponer `Flux`/SSE sin duplicar drivers (`R2DBC` + `JDBC` para Flyway), delegando JPA a hilos del consumidor AMQP o `Schedulers.boundedElastic()`.
   - **Escalado de `results-service` (evaluación fan-out SSE)**: actualmente opera con 1 réplica porque los `Sinks.Many` residen en memoria JVM. Para escalarlo horizontalmente manteniendo el enfoque de presupuesto cero sin sumar infraestructura externa, la alternativa recomendada es suscribir cada réplica con una **cola temporal exclusiva en RabbitMQ** enlazada al Topic Exchange `livevote.events` (evitando así desplegar Redis Pub/Sub adicional).
   - **Límites de seguridad aceptados**: votante anónimo identificado por `deviceId` en `localStorage`, registro abierto mitigado con rate limiting por IP (Caffeine) y JWT HS256 de vida corta (2 h).

---

## Estructura del monorepo

```text
.
├── .agents/skills/             # Skills específicas del proyecto para agentes
├── .github/workflows/          # Pipelines CI/CD por ruta modificada + release multi-arch (F7)
├── deploy/                     # Caddyfile para terminación TLS opcional (F8)
├── docs/
│   ├── PLAN.md                 # Plan maestro, bitácora y checklists F0–F9
│   ├── demos.md                # Runbook reproducible y evidencia de Demos (a)–(d)
│   ├── runbook-prod.md         # Runbook de producción, RAM medida, TLS y backups (F8)
│   └── plans/                  # Planes detallados de implementación por fase (F1–F9)
├── frontend/                   # SPA Angular 20 standalone + nginx + Karma CI (F5/F7)
├── scripts/                    # Scripts operativos: backup-postgres.sh y smoke-test (F8)
├── services/
│   ├── api-gateway/            # Spring Cloud Gateway + Resilience4j + DNS round-robin
│   ├── votes-service/          # Escritura: identity + polls + votes (Flyway)
│   └── results-service/        # Lectura + SSE: WebFlux + JPA + AMQP (Flyway)
├── docker-compose.yml          # Orquestación base Distroless de los 7 contenedores
├── docker-compose.override.yml # Puertos y credenciales locales de desarrollo
├── docker-compose.prod.yml     # Overlay de producción: GHCR, mem_limit y perfil TLS
├── .env.example                # Variables de entorno para desarrollo local
├── .env.prod.example           # Plantilla sin credenciales para producción
├── AGENTS.md                   # Reglas de arquitectura y convenciones
└── CHANGELOG.md                # Historial de versiones (Keep a Changelog)
```

Cada componente bajo `services/*` y `frontend/` es un proyecto independiente con su propio build, suite de tests y `Dockerfile` multi-arch (`linux/amd64`, `linux/arm64`), sin compartir código fuente entre sí.

---

## Ejecución rápida

### Entorno local de desarrollo (`docker compose up`)

Con Docker Desktop en ejecución (sin crear `.env`: `docker-compose.override.yml` aporta valores por defecto de desarrollo):

```bash
docker compose up -d --build
```

- **Aplicación web (`frontend` + `nginx`)**: `http://localhost:4200` (proxy mismo origen `/api` hacia `api-gateway`, SSE sin buffering).
- **`api-gateway`**: `http://localhost:8080` (`/actuator/health/readiness`).
- **`votes-service` y `results-service`**: aislados en red interna `livevote-internal` (imágenes `gcr.io/distroless/java17-debian12:nonroot`, `nonroot`, healthchecks nativos vía `HealthCheck.java`).
- **PostgreSQL / RabbitMQ Management (dev)**: `postgres-votes` en `localhost:5433`, `postgres-results` en `localhost:5434`, RabbitMQ UI en `http://localhost:15672` (`livevote` / `livevote_dev_password`).

### Modo producción (imágenes GHCR multi-arch, ~2 GB RAM)

Consultar [docs/runbook-prod.md](./docs/runbook-prod.md):

```bash
cp .env.prod.example .env.prod   # completar secretos con openssl rand -base64 48
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
```

- Imágenes multi-arch publicadas en `ghcr.io/1w3-421343-torres-marcos/livevote-<component>:<version>`.
- Backups separados `pg_dump -Fc`: `bash scripts/backup-postgres.sh .env.prod`.
- Smoke test post-despliegue (9 checks incluyendo `401` ante `X-User-Id` falsificado y SSE): `bash scripts/smoke-test.sh http://127.0.0.1:80`.

---

## Demostraciones verificadas (a)–(d) y observabilidad

Todas las demostraciones obligatorias cuentan con pasos reproducibles y evidencia verificada en [docs/demos.md](./docs/demos.md):

- **Demo (a) — Escalado horizontal**: `docker compose up -d --build --scale votes-service=3` distribuyendo peticiones reales entre las 3 réplicas (`X-Instance-Id` + logs JSON).
- **Demo (b) — Caída y recuperación de `results-service`**: lecturas en `503` controlado, votos en `201` retenidos en `results.events.queue` (`0` DLQ), reconciliación automática al reiniciar y propagación en vivo de **`PollClosed` (`poll.closed`)** por SSE.
- **Demo (c) — Caída y recuperación de `postgres-results`**: `/actuator/health/readiness` en `503 DOWN` independiente de `votes-service`, votos `201`, `0` DLQ y conciliación automática.
- **Demo (d) — Caída y recuperación de `postgres-votes`**: escrituras degradadas a `503`, lecturas y SSE en vivo sin interrupción y emisión inmediata tras recuperar la base.

---

## Convenciones y estado de fases

- **Código, APIs, JSON y commits**: en **inglés** (Conventional Commits).
- **Documentación**: en **español**.
- **Reglas de arquitectura y bitácora completa**: [AGENTS.md](./AGENTS.md), [docs/PLAN.md](./docs/PLAN.md) y [CHANGELOG.md](./CHANGELOG.md).

| Fase | Alcance | Estado |
| :--- | :--- | :--- |
| **F0** | Bootstrap del monorepo, `AGENTS.md`, 9 skills y plan maestro | ✅ Completada |
| **F1** | `votes-service` aislado + `postgres-votes` + Flyway + Compose inicial | ✅ Completada (`v0.1.0`) |
| **F2** | RabbitMQ + `results-service` + idempotencia + DLQ | ✅ Completada (`v0.2.0`) |
| **F3** | `api-gateway` (JWT, `X-User-Id`, rate limit, Circuit Breaker) | ✅ Completada (`v0.3.0`) |
| **F4** | Tiempo real SSE con heartbeat (~20 s) end-to-end | ✅ Completada (`v0.4.0`) |
| **F5** | Frontend Angular standalone + `nginx` | ✅ Completada (`v0.5.0`) |
| **F6** | Observabilidad (`traceId`, JSON logs, Actuator) + demos (c) y (d) | ✅ Completada (`v0.6.0`) |
| **F7** | Imágenes Distroless multi-arch (`amd64`/`arm64`) + CI/CD | ✅ Completada (`v0.7.0` / `v0.7.1`) |
| **F8** | Producción (`docker-compose.prod.yml`, RAM, TLS, backups, smoke test) | ✅ Completada (`v0.8.0`) |
| **F9** | Demos (a)–(d) documentadas, `PollClosed` en vivo y documentación final | ✅ Completada (`v1.0.0`) |
