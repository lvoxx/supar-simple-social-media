# Changelog

## [0.2.0](https://github.com/lvoxx/supar-simple-social-media/compare/v0.1.0...v0.2.0) (2026-06-26)


### Features

* add AllArgsConstructor to OutboxEvent class ([d838848](https://github.com/lvoxx/supar-simple-social-media/commit/d838848744d5f20d04edbbe334e23545989e7834))
* add ArgoCD application manifests for monitoring and logging components including Grafana, Loki, Tempo, and ingress-nginx ([3edf80e](https://github.com/lvoxx/supar-simple-social-media/commit/3edf80e4b43af866283a7404be41ca5dca3e0ae3))
* add CI workflow, contributing guidelines, and project structure ([2a261b8](https://github.com/lvoxx/supar-simple-social-media/commit/2a261b85c969d44004de2210061d8fd5ae74d27c))
* add Glob and Grep permissions to settings.json ([561a264](https://github.com/lvoxx/supar-simple-social-media/commit/561a2640c8a6ab319166a276263b8d2de4f6b985))
* add initial configuration files for media service, including application.yml, test overrides, ArgoCD project, Helm chart, and service definitions ([5be1c35](https://github.com/lvoxx/supar-simple-social-media/commit/5be1c353faa19358e0b356e24c89e20552f827fd))
* add initial configuration for release-please, pre-commit hooks, and user service database migration ([71afe08](https://github.com/lvoxx/supar-simple-social-media/commit/71afe080cc3999c5aa0b9717be26dfd36f0e3f5c))
* add initial Java Spring Boot user service with PostgreSQL support ([7280ef2](https://github.com/lvoxx/supar-simple-social-media/commit/7280ef232583d5b80f390a823b7c7b0445456fa5))
* add initial media service configuration including Dockerfile, Maven wrapper, and project files ([aae6299](https://github.com/lvoxx/supar-simple-social-media/commit/aae6299994743f6da1632c9f855cff436ef17fec))
* add initial Terraform configuration for AWS infrastructure including VPC, EKS, RDS, and Cloudflare integration ([e2f9e29](https://github.com/lvoxx/supar-simple-social-media/commit/e2f9e299cead870a80eee0accf52155b40bfdf31))
* add Lombok annotations for constructors and accessors in domain models ([04fc2fc](https://github.com/lvoxx/supar-simple-social-media/commit/04fc2fc82b5afb9cc72ccd684d862df20214e194))
* add user-service and post-service with Docker support and configuration files ([19428ce](https://github.com/lvoxx/supar-simple-social-media/commit/19428ced3c8299bcb6d49baac0da20e56658099c))
* **engagement-service:** Phase 2 — Redis engagement counters + PG flush ([eedea4b](https://github.com/lvoxx/supar-simple-social-media/commit/eedea4ba5605da987d01f1ba2413af38ade267f6))
* enhance user service with integration tests and PostgreSQL support ([f391900](https://github.com/lvoxx/supar-simple-social-media/commit/f391900b5c1450ceaf6bc19e33288ac25b6ed370))
* implement gateway-sidecar authentication and refactor security configuration ([23986c1](https://github.com/lvoxx/supar-simple-social-media/commit/23986c18d46d493e1cf14436a19b2c0f8271e4f3))
* implement user profile and follow functionality ([f9e6d45](https://github.com/lvoxx/supar-simple-social-media/commit/f9e6d4594b004a98c6a4a891b3dc615fe0c3e804))
* **media-service:** presigned R2 uploads with on-the-fly imgproxy variants ([5831b1f](https://github.com/lvoxx/supar-simple-social-media/commit/5831b1f2cc2e1924cbe4bc73e9246edc2428f50f))
* **notification-service:** Phase 2 Slice 1 — Kafka consumer, persisted inbox, SSE ([8d3c4c3](https://github.com/lvoxx/supar-simple-social-media/commit/8d3c4c31ff4de8b347e5539cfb9766e91709b146))
* **post-service:** add baseline schema and transactional outbox for posts ([19350e1](https://github.com/lvoxx/supar-simple-social-media/commit/19350e1ad27ba2790eca7cc95f11b059474075cd))
* **timeline-service:** implement fan-out-on-read for home feed with Redis caching and cursor pagination ([8e719bf](https://github.com/lvoxx/supar-simple-social-media/commit/8e719bfa328a5968579406703e78a3b4e09e0b2f))
* update GitNexus indexing details in documentation ([ecf7e9d](https://github.com/lvoxx/supar-simple-social-media/commit/ecf7e9df32491c7bbb167c170af8e40031d6ae4a))
* update roadmap to include frontend generation phase ([32259dd](https://github.com/lvoxx/supar-simple-social-media/commit/32259dd0d26bcf57891300b873fc7ee4cbd78eb2))
* update ROADMAP with service registration process and infrastructure details for new services ([1871616](https://github.com/lvoxx/supar-simple-social-media/commit/1871616b5979be6d40e7af814baa0e4c949121cd))
* update version from 0.1.0-SNAPSHOT to 0.0.1 in multiple pom.xml files ([87b05aa](https://github.com/lvoxx/supar-simple-social-media/commit/87b05aa16a58d60cb36268015156081f165690d6))


### Bug Fixes

* format tables in ARCHITECTURE.md and ROADMAP.md for better readability ([910ae07](https://github.com/lvoxx/supar-simple-social-media/commit/910ae07b821984cee60fa75f62150e9fe801028a))
* update file paths in inputFiles.lst to reflect correct drive letter ([c728d3b](https://github.com/lvoxx/supar-simple-social-media/commit/c728d3b75a020c95ad24744a2af73fddea22836b))
* update GitNexus project indexing details for accuracy ([be04429](https://github.com/lvoxx/supar-simple-social-media/commit/be04429c50e0e3bc729ee024aa20541f05ee5e09))
* update GitNexus project indexing details in AGENTS.md and CLAUDE.md ([8abe85b](https://github.com/lvoxx/supar-simple-social-media/commit/8abe85be96f3046c8fbe9ad215f31b83033ef2d9))
* update GitNexus project indexing details in AGENTS.md and CLAUDE.md ([2674db7](https://github.com/lvoxx/supar-simple-social-media/commit/2674db7d9282e157f2b5559d20e9523d6c320330))
* update GitNexus project indexing details in AGENTS.md and CLAUDE.md ([adf98b3](https://github.com/lvoxx/supar-simple-social-media/commit/adf98b332aff0374be981c5e0e2f54cd4a3e679f))
* update GitNexus project indexing details in AGENTS.md and CLAUDE.md for accuracy ([8fb7da7](https://github.com/lvoxx/supar-simple-social-media/commit/8fb7da7256cc8b4114fb00e62c4ba831012e36f8))
* update query parameters in GitNexus skills for consistency ([e540d34](https://github.com/lvoxx/supar-simple-social-media/commit/e540d346494a49c46a61720ff64ce4a7f20276a5))


### Documentation

* enhance CONTRIBUTING.md with detailed service scaffolding guidelines ([b8a0f49](https://github.com/lvoxx/supar-simple-social-media/commit/b8a0f491f97dd249bc6a6c21cd746a780e5eef86))
* update CONTRIBUTING.md with Lombok usage guidelines and guardrails ([312a80b](https://github.com/lvoxx/supar-simple-social-media/commit/312a80b898124deb991110980b73305b95fc3992))
* update README with observability details and clarify service addition steps ([b75221b](https://github.com/lvoxx/supar-simple-social-media/commit/b75221be61db6faf2a1c454c73187f832c375b3c))
