COMPOSE ?= docker compose
GRADLE ?= $(shell zsh -lc 'command -v gradle' 2>/dev/null || command -v gradle 2>/dev/null || echo gradle)
DEV_ENV ?= dev/.env
SHELL := /bin/zsh
.SHELLFLAGS := -lc

.PHONY: dev dev-account-source dev-env dev-keys frontend-install frontend-test frontend-build profile-test profile-build content-test content-build account-build backend-test backend-build compose-config compose-down

frontend-install:
	cd frontend && npm install

frontend-test:
	cd frontend && npm run test

frontend-build:
	cd frontend && npm run build

profile-test:
	cd profile-service && $(GRADLE) test

profile-build:
	cd profile-service && $(GRADLE) installDist

content-test:
	cd content-service && $(GRADLE) test

content-build:
	cd content-service && $(GRADLE) installDist

account-build:
	cd dev/account-src/backend && ./gradlew build -x test --no-daemon

backend-test: profile-test

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
