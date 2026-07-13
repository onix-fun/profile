# Onix Local Infra Workspace

This repository is the local infra workspace for Account, Profile, Content, and Design System services.

Account, Profile, Content, and Design System are treated as separate service roots. The root keeps shared local development topology, Caddy routing, Docker Compose, generated dev secrets, and integration docs.

## Structure

- `account/` Account service checkout.
- `profile/backend/` Kotlin/Ktor Profile API over Account and Content APIs.
- `profile/frontend/` Vue Profile/Search frontend.
- `content/backend/` Kotlin/Ktor Content API, persistence, media/search adapters, and gRPC provider.
- `content/frontend/` Vue Feed/Post/Story frontend.
- `design-system/` `@onix/design-system` tokens package.
- `dev/` Caddy, Docker Compose topology, generated local secrets, and Account sync scripts.
- `shared-proto/` checked-in API contract protos used by local backend builds.

## Local Commands

```sh
cd profile/frontend && npm install
cd profile/frontend && npm run test
cd profile/frontend && npm run build
cd content/frontend && npm install
cd content/frontend && npm run test
cd content/frontend && npm run build
cd profile/backend && gradle test
cd profile/backend && gradle installDist
cd content/backend && gradle test
cd content/backend && gradle installDist
make dev
make compose-config
```

`make dev` generates Account JWT keys under `dev/secrets/`, syncs the Account checkout in `account/`, builds Profile and Content backends, and starts the full local topology.

Local Caddy hosts:

- `http://account.onix.localhost:8088`
- `http://profile.onix.localhost:8088`
- `http://content.onix.localhost:8088`

MailHog web UI is exposed at `http://localhost:8028` by default. Override it with `MAILHOG_HTTP_PORT` in `dev/.env` if that port is already in use.

## Service Boundaries

Profile frontend calls only Profile backend. Profile backend exposes REST-shaped facades for profile-owned UI needs over Content APIs, including search, collection state, post reactions, story archive counts, recommendations, and media rendering.

Content frontend calls Content GraphQL and Account browser APIs. Inter-service navigation uses runtime-configured public frontend URLs and absolute links. Auth redirects use `redirect=<currentUrl>` and `X-Onix-Redirect`.

## Backend Environment

```env
PROFILE_HTTP_PORT=8090
PROFILE_ACCOUNT_GRPC_URL=localhost:9097
PROFILE_ACCOUNT_GRPC_TLS=false
PROFILE_ACCOUNT_FRONTEND_URL=http://account.onix.localhost:8088
PROFILE_PUBLIC_URL=http://profile.onix.localhost:8088
PROFILE_ALLOWED_ORIGINS=http://profile.onix.localhost:8088
PROFILE_TRUSTED_REDIRECT_ORIGINS=http://profile.onix.localhost:8088,http://content.onix.localhost:8088
PROFILE_CONTENT_API_URL=http://localhost:8091
PROFILE_CONTENT_GRPC_URL=localhost:9091

CONTENT_HTTP_PORT=8091
CONTENT_GRPC_PORT=9091
CONTENT_ALLOWED_ORIGINS=http://content.onix.localhost:8088,http://profile.onix.localhost:8088
CONTENT_ACCOUNT_GRPC_URL=localhost:9097
CONTENT_ACCOUNT_GRPC_TLS=false
CONTENT_MEDIA_API_URL=http://localhost:8082
CONTENT_SEARCH_API_URL=http://localhost:8083
```
