# Repository Guidelines

## Project Structure & Module Organization

This repository contains Profile and Content services over Account. Keep frontend, backend services, dev topology, and documentation separate:

- `profile-service/` for Kotlin/Ktor Profile API code, Account adapter, Content adapter, and Profile tests.
- `content-service/` for Kotlin/Ktor Content API code, persistence, Account/Media/Search adapters, and Content tests.
- `frontend/` for Vue 3, PrimeVue, deterministic canvas UI, and frontend tests.
- `dev/` for local Caddy, Docker Compose topology, and generated dev secrets.
- `docs/` for integration notes and operational decisions.

Avoid committing generated build outputs, local caches, secrets, or editor-specific files.

## Build, Test, and Development Commands

- `cd frontend && npm install`: install frontend dependencies.
- `cd frontend && npm run test`: run Vitest unit tests.
- `cd frontend && npm run build`: typecheck and build the Vue app.
- `cd profile-service && JAVA_HOME=<jdk21> gradle --no-daemon test`: compile and test Profile backend.
- `cd content-service && JAVA_HOME=<jdk21> gradle --no-daemon test`: compile and test Content backend.
- `make dev`: generate local Account JWT keys and start the full Caddy/Account/Profile stack.
- `make compose-config`: validate the local Docker Compose topology.

## Coding Style & Naming Conventions

Use Kotlin packages `com.onix.profile` and `com.onix.content`, and Vue aliases via `@/`. Keep UI layout deterministic: users must not edit or persist canvas node positions. Environment variables use `PROFILE_*` for Profile and `CONTENT_*` for Content.

## Testing Guidelines

Add tests alongside behavior changes. Backend tests use Kotlin test/JUnit; frontend tests use Vitest. Cover Account adapter boundaries, canvas mapping, privacy-filtered nodes, follow states, and auth redirect behavior. Use behavior-oriented test names.

## Commit & Pull Request Guidelines

This directory is not currently a Git repository, so no local commit convention is available. Use short imperative commit subjects, for example `Add automatic profile canvas`. Pull requests should include purpose, validation performed, linked issue when available, and screenshots for user-visible UI changes.

## Security & Configuration Tips

Never commit secrets, private keys, tokens, or production credentials. Local dev keys are generated under ignored `dev/secrets/`. Treat authentication, authorization, audit logging, and data retention behavior as part of the service contract and cover changes with tests.
