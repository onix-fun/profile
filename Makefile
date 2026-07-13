COMPOSE ?= docker compose
GRADLE ?= $(shell zsh -lc 'command -v gradle' 2>/dev/null || command -v gradle 2>/dev/null || echo gradle)
DEV_ENV ?= dev/.env
SHELL := /bin/zsh
.SHELLFLAGS := -lc

.PHONY: dev dev-account-source dev-env dev-keys profile-frontend-install profile-frontend-test profile-frontend-build content-frontend-install content-frontend-test content-frontend-build design-system-build profile-test profile-build content-test content-build account-build frontend-test frontend-build backend-test backend-build compose-config compose-down

profile-frontend-install:
	cd profile/frontend && npm install

profile-frontend-test:
	cd profile/frontend && npm run test

profile-frontend-build:
	cd profile/frontend && npm run build

content-frontend-install:
	cd content/frontend && npm install

content-frontend-test:
	cd content/frontend && npm run test

content-frontend-build:
	cd content/frontend && npm run build

design-system-build:
	cd design-system && npm run build

frontend-test: profile-frontend-test content-frontend-test

frontend-build: profile-frontend-build content-frontend-build design-system-build

profile-test:
	cd profile/backend && $(GRADLE) test

profile-build:
	cd profile/backend && $(GRADLE) installDist

content-test:
	cd content/backend && $(GRADLE) test

content-build:
	cd content/backend && $(GRADLE) installDist

account-build:
	cd account/backend && ./gradlew build -x test --no-daemon

backend-test: profile-test content-test

backend-build: profile-build content-build

dev: dev-env dev-keys dev-account-source
	$(MAKE) account-build profile-build content-build
	$(COMPOSE) --env-file $(DEV_ENV) -f dev/docker-compose.yml up --build

dev-account-source:
	@dev/scripts/sync-account-source.sh

dev-env:
	@if [ ! -f "$(DEV_ENV)" ]; then cp dev/.env.example "$(DEV_ENV)"; fi

dev-keys:
	@dev/scripts/generate-dev-keys.sh

compose-config: dev-env dev-account-source
	$(COMPOSE) --env-file $(DEV_ENV) -f dev/docker-compose.yml config

compose-down: dev-env
	$(COMPOSE) --env-file $(DEV_ENV) -f dev/docker-compose.yml down
