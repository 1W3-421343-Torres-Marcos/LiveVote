---
name: ci-cd
description: >-
  Guía para pipelines de CI/CD en GitHub Actions por ruta modificada, versionado semántico y publicación multi-arch.
  Activar esta skill al crear o mantener workflows en .github/workflows/ para build, tests,
  Testcontainers y publicación de imágenes Docker amd64/arm64.
---

# Skill: `ci-cd`

Automatización de integración continua y publicación de imágenes de LiveVote en **GitHub Actions** con presupuesto cero (runners gratuitos de GitHub y GitHub Container Registry `ghcr.io`).

## Principios del pipeline

- **Ejecución por ruta modificada (`paths` filters)**: cada proyecto del monorepo tiene su workflow independiente (o jobs condicionales por cambios) para no reconstruir todo en cada commit:
  - `services/votes-service/**`
  - `services/results-service/**`
  - `services/api-gateway/**`
  - `frontend/**`
- **Etapas por servicio backend**:
  1. Checkout + setup JDK 17 con caché de Maven (`~/.m2`).
  2. Build + tests unitarios + tests de integración con **Testcontainers** (`mvn verify` / `mvn test`, aprovechando Docker disponible en `ubuntu-latest`).
  3. Build y push de imagen Docker multi-arquitectura (`linux/amd64,linux/arm64`) con `docker/setup-qemu-action` + `docker/setup-buildx-action` + `docker/build-push-action`.
- **Etapas para `frontend`**:
  1. Checkout + setup Node 22 con caché `npm`.
  2. `npm ci`, lint/tests unitarios (`ChromeHeadless`) y `npm run build`.
  3. Build y push de imagen `nginx` multi-arquitectura (`linux/amd64,linux/arm64`).

## Versionado semántico y tags de imágenes

- **Regla inviolable**: **NUNCA** publicar ni desplegar usando únicamente el tag `latest`.
- En ramas de integración (`main`): etiquetar con SHA corto (ej. `sha-<short>`) para trazabilidad de CI.
- En tags Git semánticos (`vMAJOR.MINOR.PATCH`, ej. `v1.0.0`): publicar tags explícitos `1.0.0` y `1.0` en `ghcr.io/<owner>/livevote-<component>`.

## Verificación

- Validar sintaxis YAML y disparadores por ruta de cada workflow.
- Confirmar en GitHub Actions que modificar un único servicio solo dispara el workflow de ese componente y publica el manifiesto multi-arch (`amd64` + `arm64`).
