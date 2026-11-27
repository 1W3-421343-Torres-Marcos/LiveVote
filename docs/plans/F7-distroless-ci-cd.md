# Plan de Implementación — Fase F7: Imágenes Distroless multi-arquitectura y CI/CD

- **Fase**: `F7`
- **Versión objetivo**: `v0.7.0`
- **Estado**: `Completado (v0.7.0)`
- **Fecha**: `2026-11-27`

---

## 1. Objetivo

Migrar los tres microservicios Spring Boot a imágenes multi-stage basadas en **Distroless** (`gcr.io/distroless/java17-debian12:nonroot`), habilitar builds reproducibles multi-arquitectura (`linux/amd64,linux/arm64`) en Docker Compose y automatizar la integración continua y publicación en GitHub Container Registry (`ghcr.io`) mediante **GitHub Actions** disparados por ruta modificada y versionado semántico (cero `latest`).

---

## 2. Arquitectura de contenedores y salud Distroless

- **Compilación cross-arch rápida (`$BUILDPLATFORM`)**:
  - Backends Java: `FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17-noble AS build` (a diferencia de `-alpine`, `-noble` publica manifiestos nativos `linux/amd64` y `linux/arm64/v8`). Compila el JAR ejecutable y `HealthCheck.class` una sola vez sin emulación QEMU.
  - Runtime Java: `FROM gcr.io/distroless/java17-debian12:nonroot`, `USER nonroot:nonroot` (`65532:65532`), etiquetas OCI y `ENTRYPOINT ["java", "-jar", "/app/app.jar"]` (soporte nativo de `JAVA_TOOL_OPTIONS` sin shell).
  - Frontend Angular + `nginx`: `FROM --platform=$BUILDPLATFORM node:22.12.0-alpine3.20 AS build` → `FROM nginx:1.27.3-alpine` con etiquetas OCI.
- **Healthchecks sin shell ni `curl`/`wget`**:
  - Cada microservicio Java incluye su propio `HealthCheck.java` independiente (`services/<servicio>/HealthCheck.java`, fuera de `src/main/java`), compilado con `--release 17` usando solo `java.base` (`HttpURLConnection` con timeouts explícitos de `3 s` y modo `--print`).
  - `docker-compose.yml` invoca `["CMD", "java", "-Xmx24m", "-XX:TieredStopAtLevel=1", "-cp", "/app", "HealthCheck", "http://127.0.0.1:<puerto>/actuator/health/readiness"]` para evitar heredar `-XX:MaxRAMPercentage` desde `JAVA_TOOL_OPTIONS` en cada probe.
  - `docs/demos.md` reemplaza `sh -c wget` por `HealthCheck --print` en la inspección interna de `votes-service` y `results-service`.

---

## 3. Karma CI y alineación de versión (`0.7.0`)

- `frontend/karma.conf.js` + `karmaConfig` en `frontend/angular.json` + script `npm run test:ci` con `ChromeHeadlessCI` (`--no-sandbox`, `--disable-gpu`, `--disable-dev-shm-usage`) requerido en runners Ubuntu de GitHub Actions.
- Versiones alineadas a `0.7.0` en `services/votes-service/pom.xml`, `services/results-service/pom.xml`, `services/api-gateway/pom.xml`, `frontend/package.json` y `APP_VERSION=0.7.0` en `.env.prod.example` (documentando `JAVA_TOOL_OPTIONS` para Distroless).

---

## 4. Pipelines CI/CD (`.github/workflows/`)

- Cuatro workflows independientes con filtros `paths` en `push`/`pull_request` hacia `main`, `workflow_call` y `workflow_dispatch`:
  - `.github/workflows/votes-service.yml` (`services/votes-service/**`)
  - `.github/workflows/results-service.yml` (`services/results-service/**`)
  - `.github/workflows/api-gateway.yml` (`services/api-gateway/**`)
  - `.github/workflows/frontend.yml` (`frontend/**`)
- Un orquestador `.github/workflows/release.yml` disparado en tags semánticos `v[0-9]+.[0-9]+.[0-9]+` que invoca los cuatro workflows reutilizables en paralelo con concurrencia aislada por componente (`<component>-${{ github.ref }}`).
- Cada workflow ejecuta primero `test` (`mvn -B verify` con Testcontainers en backend o `npm ci` + `npm run test:ci` + `npm run build` en frontend) y luego `docker` (`setup-qemu-action@v3`, `setup-buildx-action@v3`, normalización a minúsculas de `github.repository_owner`, `metadata-action@v5` con `flavor: latest=false` y tags `sha-<short>` / `X.Y.Z` / `X.Y`, y `build-push-action@v6` multi-arch `linux/amd64,linux/arm64` hacia `ghcr.io/<owner>/livevote-<component>`).

---

## 5. Criterios de verificación

1. Validación YAML y reglas (sin `:latest` ni `latest: true`, fin de línea `LF`, permisos mínimos, filtros `paths`).
2. `mvn -B verify` en los 3 servicios + `npm run test:ci`, `npm test` y `npm run build` en `frontend`.
3. Build multi-arch local (`docker buildx build --platform linux/amd64,linux/arm64`) para las 4 imágenes comprobando manifiestos `amd64` y `arm64`.
4. `docker compose config` + `docker compose up -d --build` con los 7 contenedores `healthy`, validación Distroless (`nonroot`, ausencia de `/bin/sh`, `JAVA_TOOL_OPTIONS`, `HealthCheck --print`) y smoke test E2E vía `http://localhost:4200` incluyendo SSE.

---

## 6. Cierre de fase F7 (tras revisión del usuario)

- [x] Marcar ítems de F7 en `docs/PLAN.md` y registrar evidencia en la bitácora.
- [x] Actualizar estado de este plan a `Completado (v0.7.0)`.
- [x] Actualizar tabla de fases en `README.md`.
- [x] Agregar sección `## [0.7.0]` en `CHANGELOG.md`.
- [x] Commit convencional en inglés y tag Git `v0.7.0`.

---

## Desvíos y aprendizajes

- **Imagen builder Maven multi-arch**: `maven:3.9.9-eclipse-temurin-17-alpine` solo publica manifiesto `linux/amd64`, por lo que se utilizó `maven:3.9.9-eclipse-temurin-17-noble` junto a `FROM --platform=$BUILDPLATFORM` para compilar una sola vez de forma nativa y empaquetar ambos targets (`linux/amd64` y `linux/arm64`).
- **Healthchecks Distroless**: al carecer de shell y `wget`, cada microservicio Java incorporó un `HealthCheck.java` independiente (`--print` opcional) invocado en `docker-compose.yml` con `-Xmx24m -XX:TieredStopAtLevel=1`; la inspección interna documentada en `docs/demos.md` también se actualizó a `HealthCheck --print`.
- **Karma en Angular 20 LTS**: `@angular/build:karma` reemplaza `@angular-devkit/build-angular/plugins/karma`; `frontend/karma.conf.js` declara únicamente los plugins instalados (`karma-jasmine`, `karma-chrome-launcher`, `karma-jasmine-html-reporter`, `karma-coverage`) y el launcher `ChromeHeadlessCI` (`--no-sandbox`) para runners Ubuntu.
- **Releases multi-arch en GitHub Actions**: se agregó `.github/workflows/release.yml` invocando los 4 workflows reutilizables por `workflow_call` ante tags `v[0-9]+.[0-9]+.[0-9]+` (evitando mezclar filtros `paths` con `tags`), con grupos de concurrencia separados por componente (`<component>-${{ github.ref }}`).

