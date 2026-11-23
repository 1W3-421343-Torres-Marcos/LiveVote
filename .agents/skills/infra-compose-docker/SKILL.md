---
name: infra-compose-docker
description: >-
  Guía de Dockerfiles multi-stage Distroless multi-arquitectura y orquestación con Docker Compose en desarrollo y producción.
  Activar esta skill al editar Dockerfiles, docker-compose.yml, docker-compose.override.yml,
  docker-compose.prod.yml, redes internas, healthchecks o builds con docker buildx.
---

# Skill: `infra-compose-docker`

Infraestructura local (`docker compose up` en Windows + Docker Desktop WSL2) y base reproducible para producción sin tags `latest`.

## Reglas para Dockerfiles e imágenes

- **Tags fijos obligatorios**: **NUNCA** usar `latest` en imágenes base ni en imágenes propias. Fijar versiones exactas (ej. `maven:3.9.9-eclipse-temurin-17-alpine`, `node:22.12.0-alpine3.20`, `nginx:1.27.3-alpine`, `postgres:16.6-alpine`, `rabbitmq:3.13.7-management-alpine`).
- **Multi-stage Distroless (Java)**:
  - Build stage: compila con Maven/JDK 17 (aprovechando cache de dependencias).
  - Runtime stage: `gcr.io/distroless/java17-debian12:nonroot` (sin shell, usuario no-root, menor superficie y RAM).
  - Nota sobre healthchecks con Distroless: al no haber `curl`/`wget` en Distroless, resolver healthchecks HTTP usando el endpoint Actuator con un binario mínimo o configuración externa, o probar `CMD` Java / HTTP probe liviano compatible con Distroless (definir e incluir el mecanismo explícitamente al crear los Dockerfiles en F7).
- **Multi-arquitectura**: compilar con `docker buildx build --platform linux/amd64,linux/arm64` ya que el plan gratuito destino puede ser ARM64 (ej. Ampere / Oracle Cloud) o AMD64.

## Estructura de Docker Compose

1. **`docker-compose.yml` (base)**:
   - Red interna dedicada (`livevote-internal`).
   - Dos contenedores PostgreSQL **completamente separados**:
     - `postgres-votes`: volumen `postgres_votes_data`, base `POSTGRES_VOTES_DB`, usuario `POSTGRES_VOTES_USER`.
     - `postgres-results`: volumen `postgres_results_data`, base `POSTGRES_RESULTS_DB`, usuario `POSTGRES_RESULTS_USER`.
   - `rabbitmq`: volumen `rabbitmq_data`, usuario/clave por variables de entorno.
   - Servicios: `votes-service`, `results-service`, `api-gateway`, `frontend` (`nginx`), todos con `healthcheck` y `depends_on` usando `condition: service_healthy`.
2. **`docker-compose.override.yml` (desarrollo local)**:
   - Se carga automáticamente con `docker compose up`.
   - Expone puertos útiles para debug en host (ej. `80:80` o `4200`/`8080`, UI de RabbitMQ `15672`, y puertos de bases en dev si se requieren) e incluye configuración local.
3. **`docker-compose.prod.yml` (producción)**:
   - **Solo `nginx` (o el proxy TLS opcional Caddy)** expone puertos al host (`80`/`443`). Bases, RabbitMQ, Gateway y microservicios permanecen **únicamente en la red interna**.
   - `restart: unless-stopped`, límites `mem_limit` / `deploy.resources.limits.memory`, `JAVA_OPTS` con `-XX:MaxRAMPercentage`, y rotación de logs (`json-file`, `max-size`, `max-file`).

## Notas para Windows + Docker Desktop (WSL2)

- Asegurar fin de línea `LF` (configurado en `.gitattributes`) en scripts `.sh`, `nginx.conf` y archivos de configuración montados en contenedores.
- Verificar la configuración combinada antes de ejecutar:
  `docker compose config`
