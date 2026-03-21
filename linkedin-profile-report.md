# GitHub Portfolio Report — dbbaskette
**Generated:** March 21, 2026

## Profile Summary

**80+ public repositories** across Java, Python, TypeScript, Go, Shell, and Svelte. Primary focus areas: **AI/ML agent systems**, **Model Context Protocol (MCP) infrastructure**, **Spring Boot/Spring AI ecosystem**, and **VMware Tanzu platform tooling**. Most active in Java 21 + Spring Boot 4.x + Spring AI stack.

---

## Headline Projects

### Herald — AI Agent Framework
`Java · Spring Boot 4.0 · Spring AI 2.0 · Active (Mar 2026)`

Dual-mode AI agent framework: runs as an always-on Telegram-based personal assistant with persistent memory, calendar/email integration, and scheduled briefings — or as a single-shot task agent driven by declarative `agents.md` files. One JAR, two personalities. Supports subagent delegation and MCP tool integration.

### Nexus — MCP Gateway *(Private)*
`Java · Go · Vue 3.5 · Spring Boot 3.5 · Active (Mar 2026) · ⭐ 2`

MCP (Model Context Protocol) gateway that aggregates multiple MCP servers behind a single secure endpoint with OAuth 2.1 authentication, intelligent tool routing, and audit logging. Includes a Go-based `nexus-local` companion for proxying local stdio-based MCP servers to remote endpoints. Designed for Claude Code, Cursor, and other MCP client ecosystems.

### Worldmind — Agentic Code Assistant
`Java · Spring Boot 3.4 · Spring AI 1.1 · LangGraph4j 1.8 · Active (Feb 2026)`

Autonomous code assistant that accepts natural language development requests and orchestrates the full plan → implement → test → review pipeline. Hybrid architecture pairing LangGraph4j deterministic orchestration with Goose worker agents running in isolated Docker containers. Supports wave-based parallel task execution, crash-resilient PostgreSQL checkpointing, multi-provider LLM backends (Claude, OpenAI, Gemini), and a React dashboard with SSE live updates.

### Titan Manufacturing — Multi-Agent AI Demo
`Java · Active (Mar 2026)`

Multi-agent AI system demonstrating Tanzu Data Intelligence, Tanzu Platform, and OpenMetadata through a manufacturing digital transformation scenario. Showcases agent collaboration patterns on enterprise infrastructure.

### Insurance MegaCorp (IMC) — Microservices Platform
`Java · TypeScript · Spring Boot · Spring Cloud Data Flow · 10+ repos · Active (Jan–Feb 2026)`

End-to-end insurance telematics and claims processing platform spanning 10+ microservices:
- **imc-crash** — Claims Response Agent System Hive (multi-agent claims processing) ⭐ 3
- **imc-vehicle-events** — Real-time telemetry streaming pipeline (SCDF processors, HDFS Parquet sink, JDBC consumer)
- **imc-policy-mcp-server** — RAG-powered policy document retrieval via MCP with PGVector, query rewriting, and customer-scoped search
- **imc-manager** — Unified RAG pipeline monitoring & telemetry dashboard (Spring Boot + React)
- **imc-db-server, imc-telematics-gen, imc-chatbot, imc-web, imc-schema, imc-ragmon, imc-smartdriver-ui, imc-accident-mcp-server**

---

## MCP Ecosystem Projects

| Project | Description | Tech |
|---------|-------------|------|
| **Nexus** | MCP Gateway with OAuth 2.1, tool routing, audit | Spring Boot, Go, Vue |
| **nexus-config-repo** | Spring Cloud Config Server for Nexus | YAML |
| **gp-mcp-server** | Production MCP server for Greenplum/PostgreSQL — AES-256-GCM auth, policy enforcement, HikariCP, OpenTelemetry, Prometheus | Spring Boot 3.5, Spring AI 1.1 |
| **mcp-server** | Spring AI MCP Server foundation — STDIO + SSE dual transport, @Tool auto-registration | Spring AI, Spring Boot |
| **mcp-client** | Interactive MCP test client — STDIO/SSE/Streamable HTTP, JWT auth, no LLM required | Spring Boot, Spring AI, WebFlux |
| **mcp-service-broker** | Open Service Broker for MCP Servers | Spring Cloud OSB |
| **imc-policy-mcp-server** | RAG-powered insurance policy retrieval via MCP | Spring AI, PGVector, Tika |
| **imc-accident-mcp-server** | Accident claims MCP server | Spring AI |
| **gdrive-mcp-server** | Google Drive MCP server | JavaScript |
| **github-mcp-server** | GitHub's official MCP Server (fork) | Go |
| **mcp-mermaid** | AI-powered Mermaid diagram generation via MCP | — |
| **audiodb-mcp** | Audio database MCP server | Java |
| **generic-mcp-server / generic-mcp-client** | Reusable MCP server & client templates | Java, Shell |
| **ordersmcp** | Orders domain MCP server | Java |

---

## AI / Agent Projects

| Project | Description | Tech |
|---------|-------------|------|
| **Herald** | Dual-mode AI agent (Telegram assistant + task agent) | Spring Boot 4, Spring AI 2.0 |
| **agent-00code** | Autonomous AI agent with MCP tool integration | Spring Boot, Spring AI |
| **Worldmind** | Agentic code assistant — plan/implement/test/review | Spring AI, LangGraph4j, Docker |
| **imc-crash** | Multi-agent claims processing system | Java, Spring AI |
| **ragui** | RAG UI with Ollama and pgvector | Spring AI, Ollama |
| **TeleClawd** | Telegram + Claude integration | Python |
| **DuoDebate** | Dual-AI debate system | Java |
| **gp-assistant** | Greenplum AI assistant | Java |
| **news-factory** | AI-generated newsletters | Spring AI |
| **Artificially-Persuasive** | AI marketing strategy guide | — |
| **SCDF-RAG** | Spring Cloud Data Flow RAG pipeline | Shell |
| **td2md** | Documentation → Markdown converter for RAG systems | Python |

---

## Tanzu / Cloud Foundry Projects

| Project | Description | Tech |
|---------|-------------|------|
| **TanzuGEO** *(Private)* | LLM brand-mention benchmarking for Tanzu/Spring GEO strategy | Python |
| **Tanzu-Card-Tracker** *(Private)* | Spring community trading card tracker with AI chat | Spring Boot 4, Vue 3, Spring AI |
| **titan-manufacturing** | Multi-agent AI on Tanzu Platform + OpenMetadata | Java |
| **Tanzu-GenAI-Platform-installer** | Tanzu GenAI Platform installer | PowerShell |
| **tanzu-cf-marketplace** | Tanzu Cloud Foundry marketplace | TypeScript |
| **capitok** | Cluster API → K8s → TAS on EC2 end-to-end | Shell ⭐ 8 |
| **cf-auth-scanner** | Scan CF routes for open auth gaps | Python |
| **VibeCF / cf-local** | Local Cloud Foundry tooling | Shell |
| **acme-fitness-store** | ACME Fitness multi-environment deployment | CSS |

---

## Developer Tools & Utilities

| Project | Description | Stars |
|---------|-------------|-------|
| **SpecForge** | Spring Boot → OpenAPI 3.1 spec generator with React UI and Monaco editor | — |
| **IssueBot** | GitHub issue automation | ⭐ 3 |
| **diagram-designer** | Diagram design tool | ⭐ 7 |
| **TabSaver / nTabs** | Browser tab management extensions | ⭐ 1 / ⭐ 2 |
| **release** | Maven project release automation (versioning, tagging, GitHub releases) | ⭐ 1 |
| **SpringKickstart** | Spring Boot project scaffolding | ⭐ 1 |
| **greenplum-sne** | Greenplum single-node environment | ⭐ 4 |
| **dbbuild** | Build tooling | ⭐ 2 |

---

## Technology Stack Summary

| Category | Technologies |
|----------|-------------|
| **Languages** | Java 21 (primary), Python, TypeScript, Go, Shell, Svelte, JavaScript |
| **Frameworks** | Spring Boot 3.4–4.0, Spring AI 1.1–2.0, Spring Cloud Data Flow, LangGraph4j, Vue 3, React |
| **AI/ML** | MCP (Model Context Protocol), RAG, pgvector, Ollama, Claude, OpenAI, Gemini |
| **Data** | PostgreSQL, Greenplum, HDFS/Parquet, RabbitMQ, HikariCP, Caffeine |
| **Infrastructure** | Cloud Foundry, Kubernetes, Docker, Cluster API, Tanzu Platform |
| **Observability** | OpenTelemetry, Prometheus, structured JSON logging |
| **Security** | OAuth 2.1, JWT, AES-256-GCM, API key auth, policy enforcement |

---

## Key Themes for LinkedIn

1. **MCP Pioneer** — Built 14+ MCP servers/clients/gateways, including a full gateway (Nexus) with OAuth 2.1 and tool routing
2. **AI Agent Architecture** — Designed multi-agent systems (Herald, Worldmind, IMC-CRASH) with orchestration, sandboxed execution, and crash-resilient state
3. **Spring AI Early Adopter** — Building production systems on Spring AI from 1.0 through 2.0-SNAPSHOT
4. **Enterprise Demo Builder** — Created end-to-end demo platforms (Insurance MegaCorp, Titan Manufacturing) showcasing Tanzu + AI integration
5. **Full-Stack AI** — From RAG pipelines and vector databases to React dashboards and Telegram bots
6. **GEO Strategy** — Built tooling (TanzuGEO) to measure and optimize brand visibility in AI-generated responses
