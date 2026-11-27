# LiveVote

Plataforma de votación en vivo (tipo Mentimeter simple) construida como sistema de **microservicios** orientados a eventos. Proyecto de práctica y portfolio para **Programación IV (UTN FRC — Tecnicatura Universitaria en Programación)**.

---

## Arquitectura

```text
Navegador (Angular SPA)
   │  (mismo origen /api -> sin CORS; SSE sin buffering)
   ▼
nginx (Frontend estático + Reverse Proxy)
   │
   ▼
api-gateway (Spring Cloud Gateway: JWT, X-User-Id, rate limit IP, Circuit Breaker)
   ├── /api/auth/**, /api/polls/**, /api/votes/** ──► votes-service ──► postgres-votes
   │                                                        │
   │                                          (RabbitMQ topic + DLQ)
   │                                                        ▼
   └── /api/results/** ──────────────────────────────► results-service ──► postgres-results
```

### ¿Por qué microservicios en LiveVote?

1. **Asimetría real de carga**:
   - **Escritura (`votes-service`)**: picos cortos y transaccionales al emitir votos, con validación estricta de unicidad (`poll_id`, `device_id`) e identidad del presentador.
   - **Lectura y tiempo real (`results-service`)**: gran cantidad de clientes y pantallas abiertas simultáneamente consumiendo resultados y conexiones **SSE** de larga duración.
2. **Aislamiento de fallos y bases de datos independientes**:
   - `postgres-votes` y `postgres-results` corren en contenedores separados con volúmenes, bases y usuarios propios.
   - Si cae `results-service` o `postgres-results`, se siguen aceptando votos (`201`) mientras los eventos esperan en RabbitMQ. Si cae `postgres-votes`, los resultados ya emitidos siguen visibles.
3. **Modelos de datos distintos**:
   - `votes-service` almacena entidades normalizadas y votos individuales; `results-service` mantiene proyecciones desnormalizadas optimizadas para lectura rápida y streaming.
4. **Trade-offs asumidos**:
   - Mayor complejidad operativa (broker AMQP, consistencia eventual, trazabilidad distribuida) frente a un monolito modular; en **F8/F9** se documentará el consumo real de RAM medido y la evidencia de las 4 demos de escalado y tolerancia a fallos.
   - El módulo `identity` vive dentro de `votes-service` para no sumar un tercer servicio ni otra base de datos, manteniendo el stack viable con presupuesto cero.
   - `results-service` opera en una única instancia porque los streams SSE en memoria no realizan fan-out entre réplicas (mejora opcional en F9).

---

## Estructura del monorepo

```text
.
├── .agents/skills/          # Skills específicas del proyecto para agentes
├── .github/workflows/       # Pipelines CI/CD por ruta modificada (F7)
├── docs/
│   ├── PLAN.md              # Plan maestro, bitácora y checklists F0–F9
│   └── plans/               # Planes detallados de implementación por fase
├── frontend/                # SPA Angular standalone + nginx (F5)
├── services/
│   ├── api-gateway/         # Spring Cloud Gateway + Resilience4j (F3)
│   ├── votes-service/       # Escritura: identity + polls + votes (F1)
│   └── results-service/     # Lectura + SSE: WebFlux + JPA + AMQP (F2/F4)
├── .env.example             # Variables de entorno para desarrollo local
├── .env.prod.example        # Plantilla sin credenciales para producción
├── AGENTS.md                # Reglas de arquitectura y convenciones
└── CHANGELOG.md             # Historial de versiones (Keep a Changelog)
```

Cada componente bajo `services/*` y `frontend/` es un proyecto independiente con su propio build, suite de tests y `Dockerfile`, sin compartir código entre sí.

---

## Convenciones principales

- **Código, APIs, JSON y commits**: en **inglés** (Conventional Commits).
- **Documentación**: en **español**.
- **Versionado**: SemVer unificado (`v0.X.0` por fase hasta `v1.0.0` en producción), registrado en [CHANGELOG.md](./CHANGELOG.md); contratos de eventos versionados vía `eventVersion`.
- **Stack**: Java 17, Spring Boot 3.x, Maven, PostgreSQL, RabbitMQ, Angular moderno (standalone, `@if`/`@for`), nginx, Docker / Docker Compose (imágenes Distroless multi-arch con tags fijos, nunca `latest`).
- **Reglas detalladas**: consultar [AGENTS.md](./AGENTS.md) y el plan completo en [docs/PLAN.md](./docs/PLAN.md).

---

## Ejecución local

Con Docker Desktop en ejecución (sin necesidad de crear `.env` para desarrollo local, ya que `docker-compose.override.yml` aporta valores por defecto de dev):

```bash
docker compose up --build
```

- `api-gateway` escucha en `http://localhost:8080` (`GET /actuator/health/readiness`) como único punto de entrada HTTP (`/api/auth/**`, `/api/polls/**`, `/api/votes/**`, `/api/results/**`).
- `votes-service` y `results-service` permanecen aislados en la red interna `livevote-internal` (sin puertos publicados al host).
- `postgres-votes` en `localhost:5433` y `postgres-results` en `localhost:5434`.
- RabbitMQ AMQP en `localhost:5672` y Management UI en `http://localhost:15672` (`livevote` / `livevote_dev_password` en dev).

---

## Estado de las fases

| Fase | Alcance | Estado |
| :--- | :--- | :--- |
| **F0** | Bootstrap del monorepo, `AGENTS.md`, 9 skills y plan maestro | ✅ Completada |
| **F1** | `votes-service` aislado + `postgres-votes` + Flyway + Compose inicial | ✅ Completada (`v0.1.0`) |
| **F2** | RabbitMQ + `results-service` + idempotencia + DLQ | ✅ Completada (`v0.2.0`) |
| **F3** | `api-gateway` (JWT, `X-User-Id`, rate limit, Circuit Breaker) | ✅ Completada (`v0.3.0`) |
| **F4** | Tiempo real SSE con heartbeat (~20 s) end-to-end | ⏳ Siguiente |
| **F5** | Frontend Angular standalone + `nginx` | ⬜ Pendiente |
| **F6** | Observabilidad (`traceId`, JSON logs, Actuator) + demos (c) y (d) | ⬜ Pendiente |
| **F7** | Imágenes Distroless multi-arch (`amd64`/`arm64`) + CI/CD | ⬜ Pendiente |
| **F8** | Producción (`docker-compose.prod.yml`, RAM, TLS, backups, smoke test) | ⬜ Pendiente |
| **F9** | Demos (a)–(d) documentadas, README final y mejoras opcionales | ⬜ Pendiente |
