# Profile Service

Authenticated public profile canvas for Onix accounts.

The service is intentionally thin: Account owns users, privacy, sessions, and follow relationships. Profile Service renders an automatic Vue Flow canvas and does not persist profile data or user-controlled canvas positions.

## Structure

- `backend/` Kotlin/Ktor API and Account adapter.
- `frontend/` Vue 3, PrimeVue, Vue Flow profile canvas.
- `dev/` Caddy, Docker Compose topology, and generated local secrets.
- `docs/account-integration.md` required Account cookie and redirect integration.

## Local Commands

```sh
cd frontend && npm install
cd frontend && npm run dev
cd frontend && npm run test
cd frontend && npm run build
cd backend && gradle test
cd backend && gradle installDist
make dev
make compose-config
```

Frontend dev server runs on `http://localhost:5175` and proxies `/api` to backend `http://localhost:8090`.
`make dev` copies `dev/.env.example` to `dev/.env` when needed, generates Account JWT keys under `dev/secrets/`, syncs the current Account source into ignored `dev/account-src/`, then starts Account, Profile backend, Profile frontend, and Caddy. Open `http://profile.localhost:8088`.
For local auth, Account is served on the same origin at `http://profile.localhost:8088/account/`; this lets browser cookies work for both Account and Profile without a shared production cookie domain.
Mailhog is exposed on `http://localhost:8027` by default; override `MAILHOG_HTTP_PORT` in `dev/.env` if needed.

## Backend Environment

```env
PROFILE_HTTP_PORT=8090
PROFILE_ACCOUNT_API_URL=http://localhost:8089/api
PROFILE_ACCOUNT_FRONTEND_URL=http://localhost:8089
PROFILE_PUBLIC_URL=http://localhost:5175
PROFILE_ALLOWED_ORIGINS=http://localhost:5175
```

## API

- `GET /api/session/me`
- `GET /api/profiles/{nickname}`
- `POST /api/profiles/{userId}/follow`
- `DELETE /api/profiles/{userId}/follow`
