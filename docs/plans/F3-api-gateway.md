# Plan de Implementación — Fase F3: `api-gateway` y red interna

- **Fase**: `F3`
- **Versión objetivo**: `v0.3.0`
- **Estado**: `Completado (v0.3.0)`
- **Fecha**: `2026-11-27`

---

## Objetivo

Implementar `services/api-gateway` con **Spring Cloud Gateway** como único punto de entrada HTTP hacia los microservicios internos, aplicando validación HS256 de JWT, saneamiento y reinyección confiable de `X-User-Id`, rate limiting por IP, Circuit Breaker con fallback controlado (**Resilience4j**), preparación de la ruta SSE para F4 y aislamiento de red interna en Docker Compose (sin exponer puertos de `votes-service` ni `results-service` al host).

---

## 1. Proyecto Maven (`services/api-gateway/pom.xml`)

- **Java**: `17` (`--release 17`)
- **Spring Boot**: `3.4.3` | **Spring Cloud BOM**: `2024.0.1` (*Moorgate*) | **Versión**: `0.3.0`
- **Dependencias principales**:
  - `spring-cloud-starter-gateway` (reactivo/WebFlux, sin `starter-web`)
  - `spring-cloud-starter-circuitbreaker-reactor-resilience4j`
  - `spring-boot-starter-actuator`
  - `com.github.ben-manes.caffeine:caffeine` (token bucket acotado en memoria por IP, sin Redis)
  - `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson` (`0.12.6`) para validación HS256 independiente
- **Dependencias de test**:
  - `spring-boot-starter-test`, `io.projectreactor:reactor-test`, `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter` (`1.21.4`)

---

## 2. Estructura Package by Layer (`com.livevote.gateway`)

Sin lógica de negocio en el Gateway. Se crean únicamente los paquetes necesarios (sin `entity`, `repository`, `event`, `dto`, `service` ni `common`; espejada en `src/test/java`):

- `ApiGatewayApplication.java`
- `config/`:
  - `JwtProperties`: valida `JWT_SECRET` (`>= 32` bytes al arrancar).
  - `RateLimitProperties`: capacidad y ventana de buckets (`auth` y `vote`) + límite máximo de IPs rastreadas.
  - `ResilienceConfig`: `Customizer<ReactiveResilience4JCircuitBreakerFactory>` con ventana deslizante, umbral de fallos y `TimeLimiter` explícito (`4 s`) para `votesServiceCircuitBreaker` y `resultsServiceCircuitBreaker`.
- `security/`:
  - `JwtValidator`: verifica firma HS256 y expiración extrayendo `sub` como `UUID` (sin loguear secretos ni tokens).
  - `IpRateLimitFilter` (`GlobalFilter`, orden `-200`): limita `POST /api/auth/register`, `POST /api/auth/login` y `POST /api/polls/{id}/votes` usando IP de `X-Forwarded-For` o `RemoteAddress`, devolviendo `429 Too Many Requests` + `Retry-After`.
  - `IdentityHeaderFilter` (`GlobalFilter`, orden `-100`): elimina cualquier cabecera `X-User-Id` entrante en **todas** las rutas; en rutas protegidas (`POST /api/polls`, `GET /api/polls/mine`, `POST /api/polls/{id}/close`) valida JWT (`401` si falta o es inválido) e inyecta `X-User-Id: <sub UUID>`.
- `controller/`:
  - `FallbackController` (`@RequestMapping` multi-método): `/fallback/votes` y `/fallback/results` devolviendo `503 Service Unavailable` JSON (`ApiErrorResponse`).
- `exception/`:
  - `ApiErrorResponse` (`timestamp`, `status`, `error`, `message`, `path`).
  - `GatewayErrorWriter`: escribe respuestas JSON uniformes (`401`, `429`) desde filtros reactivos.

---

## 3. Enrutamiento y aislamiento de red

- **Puerto**: `8080` | **Timeouts HTTP Netty**: `connect-timeout: 3000 ms`, `response-timeout: 5s`.
- **Rutas**:
  1. `votes-auth`: `/api/auth/**` -> `votes-service` (`CircuitBreaker` con `statusCodes=[500,502,503,504]`, dejando pasar `4xx` del servicio).
  2. `votes-polls`: `/api/polls/**`, `/api/votes/**` -> `votes-service` (`CircuitBreaker` con `statusCodes=[500,502,503,504]`).
  3. `results-stream`: `/api/results/*/stream` -> `results-service` con `metadata.response-timeout: -1` y sin `TimeLimiter` corto (listo para SSE en F4).
  4. `results-api`: `/api/results/**` -> `results-service` (`CircuitBreaker` con `statusCodes=[500,502,503,504]`).
- **Docker Compose**:
  - `votes-service` pasa a `TRUST_GATEWAY_HEADER: "true"` al quedar detrás del Gateway en la red interna `livevote-internal`.
  - `docker-compose.override.yml` expone `8080:8080` en `api-gateway` y deja de publicar `8081` y `8082` en el host para evitar bypass y suplantación de `X-User-Id`.
  - Versiones de `votes-service`, `results-service` y `api-gateway` alineadas a `0.3.0`.

---

## 4. Plan de verificación

1. Unitarios en `services/api-gateway`: `JwtValidatorTest`, `IdentityHeaderFilterTest` e `IpRateLimitFilterTest`.
2. Integración en `ApiGatewayIntegrationTest` con Testcontainers (`wiremock/wiremock:3.9.2-alpine`): verificación real sobre TCP de stripping/reinyección de `X-User-Id` en `/__admin/requests`, `401`, `429` + `Retry-After`, paso limpio de `409`, fallback `503` del Circuit Breaker y `readiness` `200 UP`.
3. Regresión `mvn test` en `votes-service` y `results-service` (`0.3.0`).
4. Smoke test E2E vía `http://localhost:8080` con los 6 contenedores `healthy` en Docker Compose, incluyendo caída simulada de `results-service` (`503` en lectura mientras `votes-service` sigue respondiendo `201`).

---

## 5. Cierre de fase F3 (tras revisión del usuario)

- [x] Marcar ítems de F3 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.3.0)`.
- [x] Actualizar tabla de fases y ejecución local en `README.md`.
- [x] Agregar sección `## [0.3.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.3.0`.

---

## Desvíos y aprendizajes

1. **Tag oficial de WireMock en Testcontainers**: se utilizó `wiremock/wiremock:3.9.2-1-alpine` en `ApiGatewayIntegrationTest` tras verificar la disponibilidad de imagen multi-arquitectura en Docker Hub.
2. **Ruta original en fallback del Circuit Breaker**: Spring Cloud Gateway no siempre puebla `GATEWAY_ORIGINAL_REQUEST_URL_ATTR` cuando no actúan filtros de reescritura de URI, haciendo que inicialmente `FallbackController` informara `path: "/fallback/results"`. Se resolvió capturando `request.getPath().value()` al ingreso en `IdentityHeaderFilter.ATTR_ORIGINAL_PATH` (`"livevote.originalPath"`) y leyéndolo con prioridad en `FallbackController.resolveOriginalPath`.
3. **Aislamiento de puertos en desarrollo**: se retiraron los puertos `8081` y `8082` de `docker-compose.override.yml` y se activó `TRUST_GATEWAY_HEADER: "true"` en `votes-service`, dejando `http://localhost:8080` (`api-gateway`) como único punto de entrada HTTP.
