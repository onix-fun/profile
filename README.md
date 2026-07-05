# Profile Service

Authenticated public profile and content canvas for Onix accounts.

Account owns users, privacy, sessions, follow relationships, blocks, and close friends. Profile Service renders deterministic profile canvases over Account plus Content data. Content Service owns posts, stories, comments, reactions, views, media references, and search indexing.

## Structure

- `profile-service/` Kotlin/Ktor Profile API, Account adapter, and Content adapter.
- `content-service/` Kotlin/Ktor Content GraphQL-compatible API, JDBC persistence, and external service adapters.
- `frontend/` Vue 3, PrimeVue, deterministic canvas UI.
- `dev/` Caddy, Docker Compose topology, and generated local secrets.
- `docs/account-integration.md` required Account cookie and redirect integration.

## Local Commands

```sh
cd frontend && npm install
cd frontend && npm run dev
cd frontend && npm run test
cd frontend && npm run build
cd profile-service && gradle test
cd profile-service && gradle installDist
cd content-service && gradle test
cd content-service && gradle installDist
make dev
make compose-config
```

Frontend dev server runs on `http://localhost:5175` and proxies `/api` to Profile `http://localhost:8090` plus `/graphql` to Content `http://localhost:8091`.
`make dev` copies `dev/.env.example` to `dev/.env` when needed, generates Account JWT keys under `dev/secrets/`, syncs the current Account source into ignored `dev/account-src/`, then starts Account, Profile, Content, external content infrastructure, frontend, and Caddy. Open `http://profile.localhost:8088`.
For local auth, Account is served on the same origin at `http://profile.localhost:8088/account/`; this lets browser cookies work for both Account and Profile without a shared production cookie domain.
Mailhog is exposed on `http://localhost:8027` by default; override `MAILHOG_HTTP_PORT` in `dev/.env` if needed.

## Backend Environment

```env
PROFILE_HTTP_PORT=8090
PROFILE_ACCOUNT_API_URL=http://localhost:8089/api
PROFILE_ACCOUNT_FRONTEND_URL=http://localhost:8089
PROFILE_PUBLIC_URL=http://localhost:5175
PROFILE_ALLOWED_ORIGINS=http://localhost:5175
PROFILE_CONTENT_API_URL=http://localhost:8091/internal/profile
PROFILE_CONTENT_GRPC_URL=http://localhost:9091
CONTENT_HTTP_PORT=8091
CONTENT_ALLOWED_ORIGINS=http://localhost:5175
CONTENT_ACCOUNT_API_URL=http://localhost:8089/api
CONTENT_MEDIA_API_URL=http://localhost:8082
CONTENT_SEARCH_API_URL=http://localhost:8083
```

## API

- `GET /api/session/me`
- `GET /api/profiles/{nickname}`
- `POST /api/profiles/{userId}/follow`
- `DELETE /api/profiles/{userId}/follow`
- `POST /graphql` Content operations and multipart uploads
