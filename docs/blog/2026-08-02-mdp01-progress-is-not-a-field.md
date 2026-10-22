---
layout: post
title: "Progress Is Not a Field"
date: 2026-08-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-work]
tags: [architecture, progress, observation, typed-shapes, rollup]
---

Every task management system I've seen treats progress as a property of the task — a `percentComplete` integer, a status note, maybe a progress bar that someone updates manually. CaseHub's WorkItem had exactly this: a flat `Integer percentComplete` and a `String statusNote`, set via a single REST endpoint. Simple. Wrong.

The problem surfaces the moment you try to answer a real question. "How far along is the fleet deployment?" The deployment has four levels — fleet, datacentres, nodes, components. Each node reports its own progress shape: the calibration step is 3 of 7 sensors done, while the software install is at 62%. Some components appeared after the deployment started. The node's progress is a tree, not a number. And the fleet's progress is an aggregate of those trees. A single integer on a WorkItem can't represent any of this.

The deeper issue is orthogonality. A WorkItem can be DELEGATED — from the engine's perspective, it's been handed off — while its internal progress is deeply structured: staged, conditional, partially complete, nested. WorkItem lifecycle and progress state are independent dimensions. Flattening one onto the other loses information that consumers (dashboards, SLA monitors, AI agents, milestone evaluators) actually need.

## What we built instead

ProgressInstance is a separate entity, anchored to any scope via `scopeType`/`scopeId` — a WorkItem, a PlanItem, a fleet node, anything. Same shape at every level. Parent-child hierarchy via `parentProgressId`. Three typed shapes cover the real cases: `percentage` (the simple one), `count` (N of M with a unit), and `step` (a forward-only DAG of named steps with dependencies, optional flags, and JQ conditions).

The step shape is where it gets interesting. A step definition declares the dependency graph at creation time:

```json
[
  {"name": "unpack", "optional": false, "dependsOn": []},
  {"name": "assembly", "optional": false, "dependsOn": ["unpack"]},
  {"name": "wiring", "optional": false, "dependsOn": ["unpack"]},
  {"name": "calibration", "optional": false, "dependsOn": ["assembly", "wiring"]},
  {"name": "testing", "optional": true, "dependsOn": ["calibration"],
   "condition": ".steps.calibration.data.passedChecks > 5"}
]
```

The progress model validates that step transitions are legal — can't activate a step whose dependencies aren't met, can't skip a non-optional step, conditions are gate-checked at activation time. This is data integrity, not control. The orchestrator decides *when* to advance a step; the progress model validates that the advance is legal per the definition the orchestrator itself declared.

## The observation principle

The key design constraint: progress is a choreography observer. It records and reports what happened without controlling execution. The engine decides when to retry a failed step, when to skip ahead, when to fail the whole instance. Progress faithfully represents what those systems are doing — it doesn't drive them.

This shows up concretely in two asymmetries. Derived completion *is* automatic: when all required steps are done, the ProgressInstance transitions to COMPLETED — that's a logical invariant, same as "percentage reaching 100 means done." But derived failure is *not* automatic: when a required step fails, the instance stays ACTIVE. The orchestrator observes the failure via a CDI event and decides what to do — retry, skip, escalate. That judgment belongs with the system that understands the domain context, not with a progress recorder.

## The looping anchor

The reason ProgressInstance is a separate entity — not just richer fields on WorkItem — comes from iteration. When a PlanItem retries in the engine, each iteration needs its own progress. If progress were a field on WorkItem, retrying overwrites the previous iteration's state. With separate instances, each iteration is a child:

```
ProgressInstance (PlanItem repeat root)
  ├── ProgressInstance (iteration 1) — COMPLETED
  ├── ProgressInstance (iteration 2) — ACTIVE, 35%
```

The parent aggregates via a configurable rollup strategy. The rollup cascade is asynchronous — `@ObservesAsync` with OCC retry, same pattern as the existing spawn group coordinator. Eventual consistency with a reconciliation sweep as a safety net. Each level commits independently; no transaction spans multiple tree levels.

## Rollup across heterogeneous shapes

A `count` parent can aggregate `percentage` children. A fleet-level instance uses `weighted-percentage` rollup while individual nodes use `count-completed`. The strategy is per-instance, resolved via the platform's existing `StrategyResolver` — same convention as agent routing and worker selection. Custom strategies are CDI beans; you pay the complexity only at the levels that need it.

## What this opens

The engine integration is the piece that makes this architectural: `ProgressUpdatedEvent` fires → engine writes progress state into case context → existing milestone expression evaluation picks it up. A milestone criterion like `progress.workitem.abc123.state.value >= 80` works with no new engine machinery. Progress and milestones compose via events — progress tracks continuous state, milestones are binary waypoints that fire when a threshold crosses.

The conversation renderer in casehub-agency is the other natural consumer. A case worker viewing a channel sees "Calibration: sensor 3 of 7" or "Review: 63% complete" derived from the actual ProgressInstance state — not parsed from messages, not a number someone typed in manually. The choreography overlay that blocks#62 has been waiting for.

The step DAG model also gives AI agents a structured reporting surface. An agent processing 50 files can report `{current: 23, total: 50, unit: "files"}` at each step, feeding naturally into trust scoring and SLA monitoring. The agent doesn't need to know about the progress model's internals — it's a REST call with a JSON body.
