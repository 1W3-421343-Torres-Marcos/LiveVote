# AGENTS.md — LiveVote

Guía de arquitectura, reglas operativas y convenciones para agentes y desarrolladores que trabajan en **LiveVote**.

---

## 1. Propósito y contexto

**LiveVote** es una plataforma de votación en vivo (estilo Mentimeter simple) construida como sistema de microservicios para práctica y portfolio de **Programación IV (UTN FRC — Tecnicatura Universitaria en Programación)**.

- **Entorno local**: Windows con Docker Desktop (WSL2). Debe levantarse con un único comando (`docker compose up`).
- **Presupuesto cero**: No asumir proveedores pagos ni servicios administrados propietarios. El destino de producción se define recién antes de la fase F8.
- **Justificación de microservicios**: Asimetría real de carga (escritura transaccional de votos vs. lectura masiva y conexiones SSE abiertas) y aislamiento de fallos entre bases independientes.

---

## 2. Arquitectura obligatoria

> **Regla crítica**: NO modificar esta arquitectura ni los componentes principales sin documentar y justificar el cambio en este `AGENTS.md`.

```text
Navegador (Angular SPA)
   │  (mismo origen /api -> sin CORS; SSE con proxy_buffering off)
   ▼
nginx (frontend + reverse proxy)
   │
   ▼
api-gateway (Spring Cloud Gateway: JWT, strip/inject X-User-Id, rate limit IP, Circuit Breaker)
   ├── /api/auth/**, /api/polls/**, /api/votes/** ──► votes-service ──► postgres-votes (volumen/base/usuario propios)
   │                                                        │
   │                                           (solo eventos RabbitMQ: topic + DLQ)
   │                                                        ▼
   └── /api/results/** ──────────────────────────────► results-service ──► postgres-results (volumen/base/usuario propios)
```

- **Comunicación entre servicios**: Exclusivamente asíncrona mediante **RabbitMQ** (exchange topic, colas durables y DLQ). Prohibido que `votes-service` y `results-service` se llamen por HTTP o lean la base del otro.
- **Aislamiento de datos**: Dos contenedores PostgreSQL separados (`postgres-votes` y `postgres-results`), cada uno con su volumen persistente, base de datos y usuario exclusivo. Si una base cae, la otra sigue operando.
- **Capas internas por servicio**: `Controller -> Service -> Repository / Client / Publisher`.
- **Proyectos independientes**: `services/api-gateway`, `services/votes-service`, `services/results-service` y `frontend` tienen su propio build, tests y `Dockerfile`. Prohibido compartir código o librerías entre ellos.

---

## 3. Decisiones de arquitectura y trade-offs

1. **`identity` dentro de `votes-service`**: El módulo `identity` (registro, login, BCrypt, emisión de JWT) vive dentro de `votes-service` y no en un tercer microservicio para evitar sumar otro servicio y una tercera base PostgreSQL, manteniendo acotados la complejidad y el consumo de RAM para presupuesto cero.
2. **Sin BFF (Backend for Frontend)**: Existe un único canal cliente (la SPA Angular web/mobile-first). Un BFF añadiría un salto innecesario sin aportar adaptación multi-canal.
3. **WebFlux + JPA fuera del event loop en `results-service`**: `results-service` usa Spring WebFlux para exponer SSE (`Flux` y `Sinks.Many`), manteniendo Spring Data JPA y Flyway sobre `postgres-results` para evitar duplicar drivers (R2DBC + JDBC para migraciones). Todo acceso JPA debe ejecutarse fuera del event loop de Reactor Netty (en hilos del listener AMQP o envuelto en `Schedulers.boundedElastic()`).
4. **Limitación conocida de `results-service` (1 réplica)**: Corre en una sola instancia porque los `Sinks.Many` de SSE residen en memoria. Escalar `results-service` requeriría fan-out entre réplicas (ej. Redis pub/sub o cola exclusiva por instancia), evaluado recién como mejora opcional en F9.
5. **Límites de seguridad aceptados**:
   - El votante anónimo se identifica con un UUID generado en Angular y guardado en `localStorage` (`deviceId`); no representa seguridad fuerte y se acepta como trade-off de usabilidad sin login.
   - Registro abierto sin verificación de email, mitigado con rate limiting por IP en el Gateway.
   - JWT HS256 de vida corta (2 h) guardado en `localStorage` del navegador (trade-off aceptado en lugar de cookies `HttpOnly`).

---

## 4. Reglas inviolables (NUNCA HACER)

- **NUNCA** compartir bases de datos o tablas entre microservicios, ni realizar llamadas HTTP síncronas directas entre `votes-service` y `results-service`.
- **NUNCA** compartir código fuente ni dependencias internas entre `services/*` o `frontend`. El único contrato entre servicios son los esquemas JSON versionados de eventos (`eventVersion`).
- **NUNCA** colocar lógica de negocio en `api-gateway` ni dentro de los controllers.
- **NUNCA** hardcodear credenciales, usar contraseñas por defecto en producción, versionar archivos `.env` / `.env.prod`, exponer puertos de PostgreSQL o RabbitMQ al host en producción, ni loguear contraseñas, secretos o tokens JWT.
- **NUNCA** confiar en la cabecera `X-User-Id` ni en otras cabeceras de identidad provenientes del cliente. El Gateway debe eliminar cualquier `X-User-Id` entrante y reinyectarla solo tras validar el JWT en rutas protegidas.
- **NUNCA** usar el tag Docker `latest`, ni agregar dependencias, infraestructura o servicios fuera del stack definido (`Java 17`, `Spring Boot 3.x`, `Maven`, `PostgreSQL`, `RabbitMQ`, `Angular`, `nginx`) sin justificarlo previamente en este documento.
- **NUNCA** configurar reintentos infinitos en llamadas síncronas ni realizar llamadas de red sin timeouts explícitos.

---

## 5. Convenciones de desarrollo y calidad

- **Idioma**: Identificadores de código, nombres de clases/métodos, endpoints REST, campos JSON, variables de entorno y mensajes de commit en **inglés** (Conventional Commits: `feat:`, `fix:`, `docs:`, `chore:`, `ci:`, `test:`, `refactor:`). Toda la documentación (`README.md`, `AGENTS.md`, `docs/`, skills) en **español**.
- **Estructura de paquetes Java (Package by Layer obligatorio)**: Todos los microservicios Spring Boot (`votes-service`, `results-service` y `api-gateway`) organizan sus clases por capas bajo el paquete raíz del servicio, manteniendo DTOs propios sin compartir código:
  - `config`: configuraciones de Spring y clases `@ConfigurationProperties` (ej. `SecurityConfig`, `SecurityProperties`, `JwtProperties`).
  - `security`: filtros y servicios de autenticación/JWT (ej. `AuthenticationFilter`, `JwtService`).
  - `controller`: controladores REST / SSE.
  - `service`: lógica de negocio.
  - `repository`: interfaces de persistencia (Spring Data JPA).
  - `entity`: entidades JPA.
  - `dto`: objetos de entrada/salida (requests y responses). Si crece, se permiten subpaquetes internos por dominio (ej. `dto.auth`, `dto.poll`).
  - `event`: DTOs de eventos, publicadores y consumidores de RabbitMQ (cuando aplique).
  - `exception`: `GlobalExceptionHandler`, `ApiErrorResponse` y excepciones personalizadas.
  - **Reglas de organización**: prohibido crear paquetes genéricos `common` que mezclen seguridad con manejo de excepciones; crear únicamente los paquetes que el microservicio realmente necesite (ej. `api-gateway` no tendrá `entity` ni `repository`); mantener los tests espejando esta misma estructura bajo `src/test/java`.
- **Migraciones**: Flyway obligatorio en `votes-service` y `results-service` (sin `ddl-auto: update` ni `create`).
- **Testing**: Tests unitarios para la lógica de negocio + al menos un test de integración por servicio usando **Testcontainers** (PostgreSQL y/o RabbitMQ reales).
- **Versionado unificado (SemVer `MAJOR.MINOR.PATCH`)**:
  - Releases y tags Git unificados por hito: `v0.1.0` (F1) … `v0.7.0` (F7), `PATCH` (`v0.1.1`) para correcciones intermedias, y `v1.0.0` al completar el despliegue productivo (F8/F9).
  - Las versiones en `pom.xml` y `package.json` se mantienen alineadas al hito vigente.
  - En F7+, las imágenes Docker llevan tag `sha-<short>` en `main` y `X.Y.Z` en tags SemVer (coincidente con `APP_VERSION`; nunca `latest`).
  - Los contratos de eventos RabbitMQ mantienen versionado independiente vía `"eventVersion"`.
- **Formato de archivos en Windows**: Mantener fin de línea `LF` según `.gitattributes` para evitar errores dentro de contenedores Linux/WSL2.
- **Registro de documentación y planes por fase**:
  - Cada fase de implementación cuenta con un plan detallado en `docs/plans/F<N>-<nombre>.md` redactado y aprobado antes de codificar, y marcado `Completado (v0.X.0)` (anotando desvíos técnicos reales) al cerrarse.
  - Roles únicos sin duplicación: `README.md` (portada del proyecto), `AGENTS.md` (arquitectura y reglas), `docs/PLAN.md` (checklists y bitácora), `CHANGELOG.md` (historial *Keep a Changelog* en español) y `.agents/skills/*` (runbooks por módulo). `docs/demos.md` y `docs/runbook-prod.md` se crean recién al llegar a F6/F8/F9.
- **Seguimiento de progreso y cierre de fase**:
  1. Al iniciar una sesión o fase, leer `docs/PLAN.md` y el plan vigente en `docs/plans/` para retomar desde el estado real registrado.
  2. Marcar ítems/fases como completados (`- [x]`) **únicamente** después de pasar todos sus tests y criterios de verificación.
  3. Al cerrar cada fase: actualizar checkboxes y bitácora en `docs/PLAN.md`, marcar `Completado (v0.X.0)` (con desvíos si hubo) en `docs/plans/F<N>-*.md`, actualizar la tabla en `README.md`, agregar la entrada en `CHANGELOG.md`, crear un commit convencional en inglés y etiquetar el hito con tag semántico `v0.X.0`.

---

## 6. Compuerta obligatoria antes de F8 (Producción)

> **STOP obligatorio**: ANTES de planificar o ejecutar la fase **F8 (Producción)**, el agente debe preguntar al usuario cuál es el destino de despliegue elegido y verificar los límites vigentes de su plan gratuito (RAM, vCPU, disco, ancho de banda y arquitectura `amd64`/`arm64`).

---

## 7. Índice de Skills del workspace

Antes de trabajar en un módulo o etapa específica, activar la skill correspondiente ubicada en `.agents/skills/`:

| Skill | Ruta | Cuándo usarla |
| :--- | :--- | :--- |
| `votes-service` | `.agents/skills/votes-service/SKILL.md` | Identity, encuestas, votos, Flyway y `postgres-votes` |
| `results-service` | `.agents/skills/results-service/SKILL.md` | Consumidor AMQP, idempotencia, conteos, WebFlux y SSE |
| `api-gateway` | `.agents/skills/api-gateway/SKILL.md` | Enrutamiento, JWT, `X-User-Id`, rate limit, Circuit Breaker, escalado |
| `messaging-contracts` | `.agents/skills/messaging-contracts/SKILL.md` | Topología RabbitMQ, esquemas JSON `eventVersion`, DLQ y reintentos |
| `frontend-angular` | `.agents/skills/frontend-angular/SKILL.md` | Angular standalone, `@if`/`@for`, guards, interceptor, SSE, `nginx` |
| `infra-compose-docker` | `.agents/skills/infra-compose-docker/SKILL.md` | Dockerfiles Distroless multi-arch, Compose base/override/prod |
| `observability` | `.agents/skills/observability/SKILL.md` | Logs JSON, `traceId`, Actuator, métricas y demos (c)/(d) |
| `ci-cd` | `.agents/skills/ci-cd/SKILL.md` | GitHub Actions por ruta, build/test, GHCR multi-arch y semver |
| `production-deploy` | `.agents/skills/production-deploy/SKILL.md` | Compuerta F8, `.env.prod`, medición RAM, TLS, backups y smoke test |

---

## 8. Fuera de alcance

- Verificación de email, recuperación de contraseña y login social (OAuth2/OIDC).
- Roles o permisos complejos (solo presentador autenticado dueño de su encuesta y votante anónimo).
- Pagos, facturación o multi-tenancy.
- Kubernetes, Eureka u otro service discovery (se usa DNS interno de Docker Compose).
- BFF (Backend for Frontend).
- Despliegue real en la nube antes de F8.
