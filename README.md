# casehub-chat-app

[![Build](https://github.com/casehubio/chat-app/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/chat-app/actions/workflows/publish.yml)

Chat workbench application for the CaseHub platform. Provides a runnable chat UI with:

- Qhorus workbench shell (responsive layout: desktop/tablet/phone)
- SQLite-backed chat persistence with REST and WebSocket endpoints
- JWT-based dev-auth identity system
- Consumes `@casehubio/agency-ui-channel-activity` components

## Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install
```

With UI (Quinoa frontend build):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui
```

Frontend tests:

```bash
cd src/main/webui && npx vitest run
```

## Dependencies

- `casehub-connectors` — chat-spi, chat-ref, connectors-core
- `casehub-pages` — pages-auth, pages-runtime, pages-ui
- `casehub-mosaic` — channel-activity components
