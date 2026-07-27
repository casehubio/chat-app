# chat-app Workspace
**Name:** casehub-chat-app
**Project repo:** /Users/mdproctor/claude/casehub/chat-app
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/chat-app` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md

## Git Discipline

Two git repositories are active in every session: a **workspace** (methodology artifacts: handover, blog, specs, plans, ADRs) and the **project repo** (source code).

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. cd to the correct repo before staging:
- Source code commits → project repo
- Methodology artifacts → workspace

## Peer Repos — Hard Boundary

These are sibling repos. **Never commit to them from a chat-app session:**
- casehub-parent, casehub-platform, casehub-worker, casehub-ledger, casehub-work
- casehub-qhorus, casehub-connectors, casehub-iot, casehub-ras, casehub-desiredstate
- casehub-blocks, casehub-blocks-ui, casehub-engine, casehub-pages
- claudony, casehub-openclaw, casehub-workers, casehub-ops
- casehub-eidos, casehub-neocortex
- casehub-aml, casehub-clinical, casehub-life, casehub-drafthouse
- casehub-devtown, casehub-soc, casehub-fsitrading, quarkmind

File issues against peer repos instead of committing directly.

## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

# casehub-chat-app — Claude Code Project Guide

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Project Type

type: java

**Stage:** pre-release

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2

---

## What This Project Is

Chat workbench application — a runnable chat UI with qhorus runtime backend (H2 for dev/demo), REST/WebSocket endpoints, and a casehub-pages frontend. Consumes `@casehubio/blocks-ui-channel-activity` for the channel components (feed, nav, member panel, input, reactions, threading) and provides the app shell (workbench layout, WebSocket adapter, swipe gestures, auth layer).

**This is NOT a connector or library.** It is an Integration-tier application that wires together foundation components (qhorus-api, pages-auth, blocks-ui-channel-activity) into a runnable chat experience. The chat-app implements `HumanParticipatingChannelBackend` for outbound WebSocket delivery via the qhorus gateway fan-out pattern.

---

## Build and Test

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install
```

**With UI:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -Pui
```

**Frontend tests (vitest + jsdom):**
```bash
cd src/main/webui && npx vitest run
```

---

## Java on This Machine

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26)    # Java 26, use for dev and tests
```

---

## Ecosystem Conventions

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Add to `pom.xml` `<repositories>`:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```

**Cross-project SNAPSHOT versions:** All casehubio artifacts are `0.2-SNAPSHOT` resolved from GitHub Packages.

## Project Artifacts

- `CLAUDE.md`
- `docs/`

## Frontend Dependencies

This project consumes frontend packages from casehub-pages and blocks-ui via **Maven SNAPSHOT** artifacts (WebJar pattern).
See [casehub-pages ADR-0001](https://github.com/casehubio/casehub-pages/blob/main/docs/adr/0001-cross-repo-frontend-dependency-management.md).

| Source | Mechanism |
|--------|-----------|
| casehub-pages | Maven SNAPSHOT (`META-INF/resources/`) |
| blocks-ui | Maven SNAPSHOT (`META-INF/resources/`) |

**Local development:** after changing pages or blocks-ui, run `yarn build && mvn install` in the source repo to publish the SNAPSHOT to `~/.m2`.

**Do not use npm `file:` references for cross-repo dependencies** — they break in CI. See ADR-0001.

## Work Tracking

Issue tracking: enabled
GitHub repo: casehubio/chat-app

## Development Workflow

Before designing: `superpowers:brainstorming`
Before implementing: `superpowers:test-driven-development`
Before committing: `superpowers:requesting-code-review`

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
