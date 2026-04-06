
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

A RAG (Retrieval-Augmented Generation) campus counseling chatbot for BUAA (Beihang University). It combines a Spring Boot backend with a React frontend to answer student queries using knowledge from ingested documents.

## Commands

### Backend (Maven, Java 17)
```bash
mvn clean package          # compile and package JAR
mvn spring-boot:run        # run locally (port 8001)
mvn test                   # run tests
mvn -DskipTests package    # skip tests for fast iteration
```

### Frontend (in `frontend/`)
```bash
npm install        # install dependencies
npm run dev        # dev server
npm run build      # production build
npm run build:static  # build + publish static assets
```

## Infrastructure Dependencies

All must be running locally before starting the backend:
- **MySQL** on `localhost:3306`, database `ai_school_conselor`
- **Redis** on `localhost:6379`
- **Milvus** on `localhost:19530` (vector store)
- **Elasticsearch** on `localhost:9200` (keyword search)
- **RustFS/S3** on `localhost:9000` (file storage, bucket: `uploads`)

## Architecture

### Request Flow (Online RAG Pipeline)
1. `ChatController` receives streaming chat request via WebFlux
2. `OnlineChatOrchestrator` coordinates the pipeline:
   - Query rewrite / HyDE expansion
   - `MultiChannelRetrievalEngine`: parallel retrieval from Milvus (semantic) + Elasticsearch (keyword)
   - Reranking + CRAG (Corrective RAG) filtering
   - `SemanticCacheService`: checks/stores responses by semantic similarity
3. Streamed answer returned to frontend via SSE

### Offline Ingestion Pipeline
- Documents uploaded via API → S3 storage
- Parsed by Apache Tika / Markdown parser
- Structure-aware chunking (chunk size: 512, overlap: 128)
- Chunks indexed into both Milvus (embeddings via `text-embedding-v4`) and Elasticsearch

### Key Package Structure
```
src/main/java/org/buaa/rag/
  controller/       # HTTP entry points (ChatController, etc.)
  service/          # service interfaces
  service/impl/     # service implementations
  core/
    online/         # RAG query pipeline (orchestrator, retrieval, reranking)
    offline/        # document ingestion pipeline
  dao/              # MyBatis-Plus entities (*DO) and mappers
```

### Frontend
```
frontend/src/
  main.tsx          # entry point
  App.tsx           # root component
```

### Configuration
- All RAG parameters (retrieval thresholds, prompt templates for HyDE/rerank/CRAG) live in `src/main/resources/application.yml`
- Intent resolution configs: `src/main/resources/` (JSON files)
- Bootstrap data: `datasource/database.sql`, `datasource/knowledge.json`

## Coding Conventions

- Java 17, 4-space indentation, UTF-8
- Packages: lowercase under `org.buaa.rag`
- Suffixes: `*Controller`, `*Service`, `*ServiceImpl`, `*Mapper`, `*DO`, `*DTO`
- REST APIs under `/api/...`, return unified `Result`/`Results` wrapper
- Commit messages: `type: short summary` (`feat`, `fix`, `refactor`, `docs`, `test`, `chore`)
- Do not commit to `target/`

## LLM Integration

- Provider: Alibaba Dashscope (`dashscope` Spring AI adapter)
- Chat model: `qwen-plus` (temperature 0.3, top-p 0.9, max-token 2000)
- Embedding model: `text-embedding-v4`
- API key configured in `application.yml` under `spring.ai.dashscope.api-key`
