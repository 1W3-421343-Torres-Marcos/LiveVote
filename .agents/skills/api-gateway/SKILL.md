---
name: api-gateway
description: >-
  Guía de configuración y seguridad para api-gateway con Spring Cloud Gateway, JWT, rate limiting y Resilience4j.
  Activar esta skill al definir rutas, filtros de autenticación y X-User-Id, rate limit por IP,
  Circuit Breakers, timeouts SSE o investigar el balanceo de réplicas en services/api-gateway.
---

# Skill: `api-gateway`

Único punto de entrada backend detrás de `nginx`. **Prohibido colocar lógica de negocio en el Gateway.**

## Stack y tabla de enrutamiento

- **Stack**: Java 17, Spring Boot 3.x, Maven, Spring Cloud Gateway (reactivo/WebFlux), Resilience4j Circuit Breaker, `jjwt` (validación HS256).
- **Rutas**:
  - `/api/auth/**`, `/api/polls/**`, `/api/votes/**` -> `http://votes-service:8081` (o URL por variable de entorno).
  - `/api/results/**` -> `http://results-service:8082`.

## Seguridad: JWT y saneamiento de `X-User-Id`

1. **Sanitización obligatoria en TODAS las rutas**: un filtro global de alta prioridad debe **eliminar cualquier cabecera `X-User-Id`** enviada por el cliente antes de enrutar. Nunca confiar en cabeceras de identidad externas.
2. **Validación JWT en rutas protegidas**:
   - Rutas protegidas: `POST /api/polls`, `GET /api/polls/mine`, `POST /api/polls/{id}/close`.
   - Rutas públicas: `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/polls/{id}` (cuidando no confundir `{id}` con `mine`), `POST /api/polls/{id}/votes`, `GET /api/results/**`.
   - Validar firma HS256 con el secreto compartido (`JWT_SECRET`, >= 32 bytes) y expiración; si falta o es inválido, responder `401 Unauthorized`.
   - Si es válido, inyectar `X-User-Id: <subject>` hacia `votes-service`.

## Rate limiting por IP y resiliencia

- **Rate limiting por IP**: aplicar sobre `POST /api/auth/register`, `POST /api/auth/login` y `POST /api/polls/{id}/votes` usando token bucket en memoria acotado (por ejemplo Caffeine/ConcurrentHashMap con TTL, sin agregar Redis para preservar simplicidad y presupuesto cero). Responder `429 Too Many Requests` al exceder el umbral. Obtener la IP real desde `X-Forwarded-For` confiable de `nginx` o `RemoteAddress`.
- **Circuit Breaker y timeouts**:
  - Configurar Circuit Breaker por ruta con Resilience4j y respuestas fallback JSON controladas (`503 Service Unavailable`) cuando `votes-service` o `results-service` fallen.
  - **Cuidado con SSE**: excluir `/api/results/*/stream` del `TimeLimiter` corto o configurar `response-timeout` amplio para esa ruta, de lo contrario Resilience4j o Netty cortarán la conexión SSE en vivo.

## Trampa conocida al escalar `votes-service` — Demo (a)

Al ejecutar `docker compose up --scale votes-service=3`, el pool HTTP keep-alive de Reactor Netty en `api-gateway` puede mantener conexiones persistentes hacia **una sola réplica** tras resolver inicialmente el DNS de Docker (round-robin DNS).

- **Cómo investigar y resolver**: probar primero el comportamiento real; configurar correctamente el `HttpClient` (ej. resolución DNS dinámica / `maxIdleTime` o cierre/renovación de pool por conexión, o estrategia documentada) y **demostrar con logs y una cabecera/identificador de instancia** en las respuestas o logs que el tráfico efectivamente se distribuye entre las réplicas. Nunca asumir que funciona sin probarlo.

## Verificación

1. `mvn test` en `services/api-gateway` (incluyendo tests de filtros JWT, stripping de `X-User-Id`, rate limit `429` y fallback Circuit Breaker).
2. Probar que enviar `X-User-Id: fake-id` sin JWT válido a `GET /api/polls/mine` retorna `401` y nunca alcanza `votes-service` con esa cabecera.
