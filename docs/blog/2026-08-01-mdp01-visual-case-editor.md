---
title: "A Visual Editor for Case Definitions"
date: 2026-08-01
author: mdp
entry_type: note
subtype: diary
status: draft
tags: [visual-editor, react-flow, lit, graph, stencils, design]
---

# A Visual Editor for Case Definitions

Case definitions are YAML. They work, they're diffable, they version in git. But nobody can look at a YAML file with six workers, twelve bindings, and three milestone conditions and quickly understand the flow. I wanted to know what it would take to build a visual viewer — and eventually an editor — for CaseHub cases.

## Starting point: the SWF editor

The Serverless Workflow team recently built a visual editor. Same ecosystem, same family. The natural question: can we reuse it, or parts of it?

I dug into their implementation. It's React 19 + React Flow + ELK.js for hierarchical layout, tightly coupled to the `@openworkflowspec/sdk` for parsing and graph construction. The rendering layer isn't separable from the domain model — you'd need to rewrite the bridge layer to use it for Cases. But the library choices are solid, and the patterns for wiring ELK containment layout to React Flow are directly useful.

## The tech stack question

Here's where it got interesting. CaseHub's entire frontend — Pages, mosaic, the design token system, the component model — is Lit 3.x Web Components. No React anywhere. So using React Flow means introducing React into a Lit app.

I explored every alternative. Cytoscape.js looked promising until I hit the wall: no public API for custom canvas-drawn nodes. The `cytoscape-layers` plugin offers an overlay workaround, but you're drawing decorations on top of invisible base nodes — interaction and visuals decoupled. For a stencil-driven editor where each work type has its own visual identity, that's a dead end.

GoJS and JointJS+ are built for exactly this use case — stencils, palettes, containment, connection rules. But both require commercial licences. JointJS open-source is MPL 2.0. None qualify.

I looked at the xyflow headless core (`@xyflow/system`) hoping to build a native Lit binding. The numbers killed it: the core is only 25% of the total logic. The React binding is 11,700 lines. The Svelte binding — built by the xyflow team who wrote the internals — is 7,700 lines. A Lit binding is 6-8 person-weeks of framework plumbing, not a thin wrapper.

## The pragmatic answer

React-in-Lit bridging is a solved problem. Skip Shadow DOM on the wrapper component, mount via `ReactDOM.createRoot`, pass Lit properties as React props. Roughly fifty lines of bridge code and 45KB of bundle overhead. CSS isolation via `all: initial` plus cascade `@layer` prevents style leakage. The SWF team and CaseHub end up on the same rendering framework — shared knowledge, same mental model, and when CaseHub drills into an embedded SWF workflow, it's the same library at both levels.

A native `@xyflow/lit` binding remains a future option. The graph model, stencils, and domain adapters don't care what renders them — swapping the renderer is a later decision.

## The domain model surprise

The adversarial design review caught something I'd have discovered painfully during implementation: my mental model was wrong.

I'd been thinking in terms of the engine's runtime planning model — Compound and Primitive PlanItemDefinitions, decomposition strategies, completion semantics. But that's what the engine produces at runtime. It's not what users author. Users write YAML with bindings, workers, milestones, goals. The definition-time view should mirror the YAML vocabulary, not the planning vocabulary.

Bindings are the primary connective element. Workers are containers that own capabilities. Milestones and goals are landmarks. The domain adapter resolves string-based references — a binding's capability name matched to a worker's capability list — into graph edges. Simple, direct, and correct.

## Two tiers of stencils

The structural stencils — Binding, Worker, Milestone, Goal, SubCase — are compile-time. They define the graph grammar: what can contain what, what can connect to what, cardinality rules.

Work stencils are different. They're the leaf vocabulary — what a Primitive actually does. Send an email. Run an AI agent. Submit a human task. These are runtime-discoverable from marketplace YAML at configurable URLs, grouped by category, each bringing its own icon and property schema. The editor doesn't ship with them; it discovers them.

## What's bootstrapped

The foundation is split across two repos. Pages gets the domain-agnostic graph infrastructure: `graph-core` (model, stencil registry, constraint validation, persistence SPI), `graph-renderer` (React Flow bridge, ELK layout), and `graph-work-registry` (marketplace discovery). mosaic gets the CaseHub-specific packages: `graph-stencil-case` (Case domain adapter, structural stencils) and `graph-stencil-swf` (SWF drill-down via `@openworkflowspec/sdk`).

The core TypeScript interfaces are written. `GraphModel`, `StencilDescriptor`, `StencilGrammar`, `PersistenceBackend` with optimistic concurrency, `DomainAdapter` with the `toGraph`/`applyEdit` contract, `GraphEdit` operations. Placeholder adapters implement the contracts. The package structure follows existing Pages and mosaic conventions exactly.

## What's next

Phase 0: verify the CaseDefinition JSON Schema against the current Java model (it may be stale), run the cross-parser compatibility test between `yaml` npm and Jackson, and validate the React Flow + Lit bridge spike against Pages' global CSS resets.

Then the interesting work: making it actually render a case.
