# Changelog

Todos los cambios relevantes de **LiveVote** se documentan en este archivo siguiendo el formato [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y [Versionado Semántico](https://semver.org/lang/es/) unificado (`v0.X.0` por fase hasta `v1.0.0` en producción).

## [Unreleased]

## [0.1.0] - 2026-11-26

### Agregado
- **F0 (Bootstrap)**: inicialización del monorepo con `.gitignore`, `.gitattributes` (LF), `.env.example`, `.env.prod.example`, `AGENTS.md`, 9 skills en `.agents/skills/`, `docs/PLAN.md` y `README.md`.
- **F1 (`votes-service` + `postgres-votes`)**:
  - Proyecto Maven independiente en `services/votes-service` (Java 17, Spring Boot `3.4.3`, Flyway, Spring Data JPA, Spring Security).
  - Módulo `identity` (`POST /api/auth/register`, `POST /api/auth/login`) con hash BCrypt, emisión/validación de JWT HS256 (2 h) y soporte dual `trust-gateway-header` (`false` por defecto en F1).
  - Módulo `polls` (`POST /api/polls`, `GET /api/polls/mine`, `POST /api/polls/{id}/close`, `GET /api/polls/{id}`, `POST /api/polls/{id}/votes`) con validación de 2–6 opciones, control de dueño y restricción única `(poll_id, device_id)` en `V1__init_votes_schema.sql`.
  - Suite de 14 tests (unitarios, `@WebMvcTest` y `VotesServiceIntegrationTest` con Testcontainers `postgres:16.6-alpine`).
  - `Dockerfile` multi-stage con tags fijos y configuración inicial `docker-compose.yml` + `docker-compose.override.yml`.
