# Polaris Documentation

Documentation is organized around three questions: **what/why**, **how**, and **how to run**.

```
                          SYSTEM KNOWLEDGE
                               │
              ┌────────────────┼────────────────┐
              │                │                 │
           BUSINESS         TECHNICAL        OPERATIONS
              │                │                 │
           WHAT/WHY           HOW           HOW TO RUN
```

## [Business](business/) — what & why
Domain processes, product requirements, and use cases. Read this to understand the problem being solved and for whom.
- [Business processes](business/README.md) — domain flows (e.g. shopper search & order placement)
- [Product Requirements (PRDs)](business/prds/) — personas, use cases, acceptance criteria

## [Technical](technical/) — how
Architecture, decisions, data, and implementation. Read this to understand how the system is built.
- [Architecture Decision Records (ADRs)](technical/decisions/) — why a given technical approach was chosen
- [Architecture & design docs](technical/architecture/) — sequence diagrams and component design
- [Event Models](technical/event-models/) — Command/Event/Read-Model breakdowns of individual use cases (especially exception paths), including as-designed vs. as-built gaps
- [Work Orders](technical/work-orders/) — vertically-sliced implementation task specs
- [Data](technical/data/) — schema and seed data

## [Operations](operations/) — how to run
Deployment, monitoring, and runbooks. Read this to run, observe, and recover the system in production.
