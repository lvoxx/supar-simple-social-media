# SSSM developer convenience targets.
# Cross-platform note: these assume a POSIX shell (Git Bash / WSL on Windows).

COMPOSE_DIR := docker
COMPOSE := docker compose --env-file $(COMPOSE_DIR)/.dev.env
COMPOSE_FILES := \
	-f $(COMPOSE_DIR)/docker-compose.postgres.yml \
	-f $(COMPOSE_DIR)/docker-compose.redis.yml \
	-f $(COMPOSE_DIR)/docker-compose.kafka.yml \
	-f $(COMPOSE_DIR)/docker-compose.cassandra.yml \
	-f $(COMPOSE_DIR)/docker-compose.elasticsearch.yml \
	-f $(COMPOSE_DIR)/docker-compose.keycloak.yml

.PHONY: up down logs proto build test build-java build-go test-java test-go fmt

## Infra ---------------------------------------------------------------------
up:        ## start local infra
	$(COMPOSE) $(COMPOSE_FILES) up -d

down:      ## stop local infra
	$(COMPOSE) $(COMPOSE_FILES) down

logs:      ## tail infra logs
	$(COMPOSE) $(COMPOSE_FILES) logs -f

## Codegen -------------------------------------------------------------------
proto:     ## regenerate Java + Go code from schemas/
	cd schemas && buf generate

## Build & test --------------------------------------------------------------
## Java is a Maven reactor (apps/java/pom.xml); Go is a single module (apps/go/go.mod).
build: build-java build-go      ## build everything

build-java:
	@cd apps/java && ./mvnw -B -q clean package -DskipTests

build-go:
	@cd apps/go && go build ./...

test: test-java test-go         ## test everything

test-java:
	@cd apps/java && ./mvnw -B -q test

test-go:
	@cd apps/go && go test ./...

fmt:       ## format Go code
	gofmt -w apps/go
