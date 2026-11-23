---
name: frontend-angular
description: >-
  Guía de desarrollo del frontend Angular moderno y configuración de nginx como reverse proxy y servidor SPA.
  Activar esta skill al trabajar en componentes standalone, guards, interceptor JWT, EventSource SSE,
  pantallas mobile-first/proyector o nginx.conf dentro de frontend/.
---

# Skill: `frontend-angular`

SPA cliente de LiveVote servida por `nginx`, actuando también como proxy reverso hacia `api-gateway` bajo el mismo origen (`/api` -> sin CORS).

## Stack y convenciones Angular

- **Angular moderno**: 100% **standalone components** (sin `NgModules`).
- **Control flow nativo**: usar `@if`, `@else`, `@for (...; track ...)` en todos los templates.
- **Reactividad y routing**: RxJS, guards funcionales (`CanActivateFn`) para rutas protegidas del presentador y `HttpInterceptorFn` funcional para adjuntar `Authorization: Bearer <token>`.
- **Identidad y estado en navegador**:
  - `deviceId`: UUID generado con `crypto.randomUUID()` y persistido en `localStorage` (identifica votos anónimos; trade-off aceptado).
  - JWT: guardado en `localStorage` bajo clave explícita y limpiado en logout o `401` (trade-off aceptado, documentado en `AGENTS.md` y `README.md`).

## Pantallas obligatorias

1. **Auth (`/login`, `/register`)**: registro e inicio de sesión para presentadores, validación de formularios y feedback claro de errores (`401`, `409`, `429`).
2. **Panel del presentador (`/polls`, `/polls/new`)**: listado de encuestas propias, creación de encuesta (pregunta + 2 a 6 opciones dinámicas), botón para copiar enlace compartible de votación y enlace a resultados, y botón para cerrar encuesta (`POST /api/polls/{id}/close`).
3. **Votación (`/vote/:id`) — Mobile-first**: carga pregunta y opciones (`GET /api/polls/{id}`), envía voto con `deviceId`, muestra estados claros para `201` (voto registrado + acceso a resultados), `409` (ya votaste desde este dispositivo), `404` (encuesta inexistente) y `422` (encuesta cerrada).
4. **Resultados en vivo (`/results/:id`) — Modo proyector**: tipografía grande, alto contraste, barras/porcentajes actualizados en tiempo real.
   - **SSE en RxJS**: envolver `new EventSource('/api/results/' + pollId + '/stream')` dentro de un `Observable`, escuchar eventos de resultados y `heartbeat`, y ejecutar `eventSource.close()` en el teardown del `Observable` y/o `ngOnDestroy` (`takeUntilDestroyed` / `DestroyRef`).

## Reglas críticas de `nginx.conf`

- Mismo origen: `location /api/ { proxy_pass http://api-gateway:8080; ... }` evitando problemas de CORS.
- Fallback SPA: `location / { try_files $uri $uri/ /index.html; }`.
- **Bloque específico para SSE** (`location ~ ^/api/results/[^/]+/stream$`):
  - `proxy_buffering off;`
  - `proxy_cache off;`
  - `proxy_http_version 1.1;`
  - `proxy_set_header Connection "";`
  - `proxy_read_timeout 3600s;` (largo para soportar stream continuo con heartbeat cada ~20 s)
  - `add_header X-Accel-Buffering no;`
- Reenviar IP real (`X-Forwarded-For`, `X-Real-IP`) para que el rate limiting del Gateway funcione por cliente.

## Verificación

1. `npm test` (tests unitarios con `--watch=false --browsers=ChromeHeadless` o runner configurado) y `npm run build` dentro de `frontend/`.
2. Verificar con el stack levantado que `/api/results/{id}/stream` recibe eventos inmediatos sin buffering en `nginx`.
