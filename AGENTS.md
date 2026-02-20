# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/org/buaa/rag`: Spring Boot backend source code.
- `src/main/java/org/buaa/rag/controller`: HTTP entry points (for example `ChatController`).
- `src/main/java/org/buaa/rag/service` and `src/main/java/org/buaa/rag/service/impl`: service contracts and implementations.
- `src/main/java/org/buaa/rag/dao`: MyBatis-Plus entities and mappers.
- `src/main/resources`: runtime config (`application.yml`), intent JSON files, and static web assets (`static/`).
- `datasource/`: local bootstrap data (`database.sql`, `knowledge.json`).
- `target/`: Maven build output; do not commit generated artifacts.

## Build, Test, and Development Commands
- `mvn clean package`: compile and package the application JAR into `target/`.
- `mvn spring-boot:run`: run the app locally (default port: `8000`).
- `mvn test`: execute automated tests (add tests under `src/test/java` as features grow).
- `mvn -DskipTests package`: package quickly when tests are not needed for local iteration.

## Coding Style & Naming Conventions
- Target Java `17`; use 4-space indentation and UTF-8 source files.
- Keep packages lowercase under `org.buaa.rag`.
- Follow existing suffix conventions: `*Controller`, `*Service`, `*ServiceImpl`, `*Mapper`, `*DO`, `*DTO`.
- Keep REST APIs under `/api/...` and return the project’s unified result wrapper (`Result`/`Results`) where applicable.
- Prefer explicit imports and clear method names (`handleChatRequest`, `recordFeedback`) over abbreviations.

## Testing Guidelines
- Place tests in `src/test/java` mirroring production package paths.
- Name unit tests `ClassNameTest`; focus first on service logic, controller request/response behavior, and mapper boundaries.
- For bug fixes, include at least one regression test covering the failure path.
- Run `mvn test` before opening a PR; there is currently no enforced coverage gate, so changed critical paths should be tested explicitly.

## Commit & Pull Request Guidelines
- Recent history follows prefixed messages (for example `feat: ...`); keep using `type: short summary`.
- Recommended types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.
- PRs should include: purpose, key changes, verification steps/commands, and sample API request/response when endpoints change.
- Reference related issues/tasks and call out schema or config edits (for example `datasource/database.sql`, `src/main/resources/application.yml`).

## Security & Configuration Tips
- Never commit real credentials or API keys.
- Move secrets from `application.yml` to environment variables or ignored local profile files before sharing/deploying.
- When changing data stores (MySQL/Redis/Elasticsearch/MinIO), document required local service versions in the PR.
