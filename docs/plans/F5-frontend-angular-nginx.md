# Plan de Implementación — Fase F5: Frontend Angular y `nginx`

- **Fase**: `F5`
- **Versión objetivo**: `v0.5.0`
- **Estado**: `Completado (v0.5.0)`
- **Fecha**: `2026-11-27`

---

## Objetivo

Construir la SPA cliente moderna en `frontend/` (**Angular 20 LTS**, 100 % standalone) con las 4 pantallas obligatorias (Auth, Panel del presentador, Votación mobile-first y Resultados en vivo en modo proyector con SSE), servirla mediante `nginx` como reverse proxy bajo el mismo origen `/api` (sin CORS y con streaming SSE sin buffering) e integrarla en Docker Compose.

---

## 1. Proyecto Angular (`frontend/`) y versionado `0.5.0`

- **Versión**: Angular 20 LTS (`@angular/cli@20`, compatible con Node `24.13.1` local y `node:22.12.0-alpine3.20` en Docker), 100 % standalone (sin `NgModules`), control flow `@if`/`@else`/`@for (...; track ...)`, signals, guards funcionales (`CanActivateFn`) e interceptor HTTP funcional (`HttpInterceptorFn`).
- **Tests**: Jasmine + Karma configurados en `package.json` con `ng test --watch=false --browsers=ChromeHeadless`.
- **Estilos**: CSS moderno autocontenido con design tokens propios (sin dependencias ni CDNs externas).
- **Proxy local de desarrollo**: `frontend/proxy.conf.json` reenviando `/api` hacia `http://localhost:8080`.
- **Versionado unificado**: `frontend/package.json` y los tres `pom.xml` (`votes-service`, `results-service`, `api-gateway`) alineados a `0.5.0`.

---

## 2. Arquitectura interna (`frontend/src/app/`)

- `core/models/`: `auth.models.ts`, `poll.models.ts`, `results.models.ts` espejando exactamente los DTOs JSON de `votes-service` y `results-service`.
- `core/services/`:
  - `DeviceIdService`: genera UUID con `crypto.randomUUID()` (fallback `crypto.getRandomValues`) y persiste `livevote_device_id` en `localStorage`.
  - `AuthService`: `/api/auth/register` y `/api/auth/login`, persistencia del JWT (`livevote_jwt_token`) y metadata en `localStorage`, signal `currentUser`, verificación de expiración `exp` y `logout()`.
  - `PollService`: `/api/polls/mine`, `POST /api/polls`, `POST /api/polls/{id}/close`, `GET /api/polls/{id}`, `POST /api/polls/{id}/votes`.
  - `ResultsService`: `GET /api/results/{id}` y `streamResults(pollId)` envolviendo `EventSource` (`/api/results/{id}/stream`) en un `Observable` (emitiendo en `NgZone`, escuchando `results`, `heartbeat` y `onerror`, y ejecutando `eventSource.close()` en teardown; con `EVENT_SOURCE_FACTORY` inyectable para tests).
- `core/guards/auth.guard.ts`: `authGuard` (`CanActivateFn`) y `guestGuard`.
- `core/interceptors/jwt.interceptor.ts`: `jwtInterceptor` (`HttpInterceptorFn`) adjuntando `Authorization: Bearer` a `/api/**` (excepto `/api/auth/*`) y limpiando sesión + redirigiendo a `/login` ante `401`.
- `features/`:
  1. **Auth** (`/login`, `/register`): formularios reactivos y feedback claro (`401`, `409`, `429`, `400`, `503`).
  2. **Panel del presentador** (`/polls`, `/polls/new`): listado de encuestas propias, copiar link `/vote/:id`, enlace a `/results/:id`, cerrar encuesta (`POST /api/polls/{id}/close`) y creación con `FormArray` de 2 a 6 opciones.
  3. **Votación mobile-first** (`/vote/:id`): botones táctiles grandes con `deviceId` y manejo explícito de `201`, `409`, `404`, `422`, `429` y `503`.
  4. **Resultados en vivo — Modo proyector** (`/results/:id`): alto contraste, barras/porcentajes reactivos, estado `EN VIVO`/`CERRADA`, indicador SSE + `heartbeat`, pantalla completa, limpieza con `takeUntilDestroyed` y corte ante `404`.

---

## 3. `nginx`, `Dockerfile` y Docker Compose

- `frontend/nginx.conf` (fin de línea `LF`):
  - `/healthz` (`200 ok`) para healthcheck del contenedor.
  - `location ~ ^/api/results/[^/]+/stream$`: `proxy_pass http://api-gateway:8080;`, `proxy_buffering off;`, `proxy_cache off;`, `proxy_http_version 1.1;`, `proxy_set_header Connection "";`, `proxy_read_timeout 3600s;`, `proxy_send_timeout 3600s;`, `add_header X-Accel-Buffering no;`.
  - `location /api/`: `proxy_pass http://api-gateway:8080;` (mismo origen sin CORS, reenvío de `X-Forwarded-For` / `X-Real-IP`).
  - `location /`: fallback SPA `try_files $uri $uri/ /index.html;`.
- `frontend/Dockerfile` + `.dockerignore`: multi-stage con tags fijos `node:22.12.0-alpine3.20` -> `nginx:1.27.3-alpine`.
- `docker-compose.yml` y `docker-compose.override.yml`: integración del servicio `frontend` dependiente de `api-gateway` (`service_healthy`), expuesto en `${FRONTEND_PORT:-4200}:80` (`http://localhost:4200`) en desarrollo local.

---

## 4. Plan de verificación

1. Frontend (`frontend/`): `npm test` (`ChromeHeadless`) y `npm run build` al 100 %.
2. Backend (`0.5.0`): `mvn test` en `votes-service`, `results-service` y `api-gateway`.
3. Stack completo (`docker compose up -d --build`, 7 contenedores `healthy`):
   - `docker compose config` limpio.
   - `curl -N http://localhost:4200/api/results/{id}/stream` (> 22 s) verificando SSE inmediato sin buffering a través de `nginx -> api-gateway -> results-service`.
   - Flujo E2E completo en navegador real sobre `http://localhost:4200` (registro, creación, voto `201`, duplicado `409`, resultados en vivo proyector y cierre de encuesta).
   - Limpieza con `docker compose down -v`.

---

## 5. Cierre de fase F5 (tras revisión del usuario)

- [x] Marcar ítems de F5 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.5.0)`.
- [x] Actualizar tabla de fases y ejecución local en `README.md`.
- [x] Agregar sección `## [0.5.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.5.0`.

---

## Desvíos y aprendizajes

1. **Compatibilidad Angular 20 LTS con Node 24 y Docker (`node:22.12.0-alpine3.20`)**: `@angular/cli@20` (`ng new`) genera convenciones modernas (`app.ts`/`app.html`/`app.css` y salida `dist/livevote-frontend/browser/`), verificadas tanto en host (`npm test` `14/14` con `ChromeHeadless` + `npm run build` `352.65 kB`) como en el build multi-stage hacia `nginx:1.27.3-alpine`.
2. **Hallazgo en frío tras `docker compose up`**: la primera llamada en frío a `POST /api/auth/register` y la primera llamada a `POST /api/polls` (apertura lazy inicial de `RabbitTemplate` contra `rabbitmq:5672` sumada al warmup de Hibernate/Tomcat) superaron brevemente los `4 s` del `TimeLimiter` de `api-gateway` (`ResilienceConfig`), devolviendo `503` antes de estabilizarse. Se deja registrado para evaluar ajuste de warmup / timeout al abordar resiliencia en F6.
