---
paths:
  - "**/*.bpmn"
---

# BPMN rules

Diagrams are for the communication between product managers and technical team. Every visible label uses plain business language. Technical detail is kept out of sight, not deleted.

## Scope and intent

A BPMN diagram describes one bounded business process with a clear intent: who participates, what they do, what decisions and events occur, and how participants hand off work. State that intent as a single question the diagram answers, e.g. "How does a shopper search for a product and place an order with the help of an AI Assistant?" Every element should help answer that question; nothing else belongs in the diagram.

- **State the intent.** Before adding or reviewing elements, write the process's one-sentence intent (as a `<bpmn:documentation>` on the `process` element, or the top of the file/PR description if the format has no such element). If you can't state it in one sentence, the scope is too broad — split the diagram.
- **Check every element against the intent.** For each lane, task, event, gateway, and flow, ask "does this help answer the intent question?" If not, cut it or move it to a separate diagram.
- **The five questions a well-scoped diagram answers:**
  1. **Participants** — who is involved? (lanes/pools)
  2. **Actions** — what does each participant do? (tasks)
  3. **Decisions** — where can the process take different paths? (gateways)
  4. **Events** — what starts, changes, or ends the process? (start/intermediate/end events)
  5. **Interactions** — how do participants hand off work or information? (message flows, sequence flows crossing lanes)
- **Resist scope creep.** A request to add a step from an adjacent process (a different trigger, a different end state, a different "why") is a sign to start a new diagram, not extend this one. Flag this to the user instead of silently absorbing it.
- **One start, one or more matching ends.** A single unclear intent often shows up as multiple unrelated start events or end events that don't share a common trigger/outcome. Treat that as a signal to re-check scope.

## Names

- **Lanes**: name each lane after a business role or capability (e.g. `Customer`, `Billing`, `Order Management`), in Title Case with 1–3 words. Every lane must be the same kind of thing. Do not use architecture terms such as Context, Service, Module, API, Backend, Orchestrator or Aggregate.
- **Tasks**: write verb + object in sentence case, 6 words or fewer, describing the business outcome (`Check price is still valid`, not `Re-verify pricing snapshot`).
- **Events**: state a business condition (`Order placed`, `Request expired`).
- **Gateways**: phrase each one as a question (`Customer approves?`). Every decision needs at least two outgoing flows, each labeled with its answer.
- **Keep out of visible names**: status enums, TTL, idempotency, atomic, retry, payload, endpoint and field names, and similar implementation terms. A limit that matters to the business may stay if it is in plain words ("held 15 min").
- **Consistency**: if the project defines a glossary of lane/role names (in `CLAUDE.md` or `docs/`), use those names exactly. Otherwise reuse the names already used in other `.bpmn` files.

## Technical detail

- Move implementation detail into a `<bpmn:documentation>` child of the element it describes.
- Use a visible `textAnnotation` only when a PM needs the detail to follow the flow.
- When simplifying a label, move whatever was removed into documentation in the same edit.

## Diagram layout (avoiding overlap)

`bpmn-to-image` (used by the render hook) draws exactly the DI coordinates in the file — it does not auto-layout or de-conflict anything. When a `name` has no explicit `BPMNLabel`, the renderer auto-places it directly below the shape's center-x, which collides with any outgoing flow that also drops straight down from that same x (typically a gateway's second branch). Symptom: the shape's name, the flow's `Yes`/`No` label, and the flow line all render stacked on top of each other.

- **Give every multi-line gateway/event label an explicit `BPMNLabel`** with `dc:Bounds`, positioned beside the shape in a direction with no incident flow — not above/below by default. Rough sizing for this font: ~9px per character width, ~13–14px height per line (e.g. a two-line label is ≈68×27; three lines ≈60×40). Measure against the rendered PNG rather than guessing further.
- **Assign each side of a gateway one job.** With an incoming flow, two branches, and a label all competing for the same shape, put each on a different side (e.g. incoming=left, branch A=top, branch B=bottom, label=right) so none share an axis.
- **Route unrelated flows around a gateway's column/row, not through it.** Once a gateway's four sides are all in use, a flow between two unrelated tasks that happens to pass near the gateway's x or y will crowd or cross its label — swing it out further before turning.
- **Set `isMarkerVisible="true"`** on every gateway shape so its type icon (e.g. the exclusive gateway's "X") actually renders — otherwise the diamond is ambiguous about which gateway type it is.

## File integrity

- Never rename element `id`s. Change only `name`s.
- Escape special characters in names: `&amp;` for `&`, `&#x27;` for `'`, `&#10;` for a line break.
- Anything added to the process must also be added to the diagram section:
    - every new node gets a `BPMNShape` and belongs to exactly one lane;
    - every new flow gets a `BPMNEdge`;
    - each node's `incoming`/`outgoing` must match the flows' `sourceRef`/`targetRef`.

## Before finishing

1. Run `xmllint --noout <file>` and confirm it passes.
2. Scan visible `name` attributes for technical terms and fix any you find.
3. Re-render (the hook does this automatically after Write/Edit; otherwise run `node scripts/render-bpmn-diagram.mjs <file>`) and view the PNG. `xmllint` only checks XML well-formedness — overlapping labels/lines are a layout defect it cannot detect, so this step isn't optional. Pay special attention to gateways with 2+ branches.
4. Report every renamed label to the user as `old → new`.