# cloud-itonami-isic-5629

**Other food service activities** — ISIC Rev.4 class 5629.

A coordination-only actor for institutional / contract food-service sites — hospital cafeterias, school and workplace canteens, food-court vendor bays operating under institutional contracts, and other food service not classified elsewhere (residual category: distinct from stand-alone restaurants and mobile food service, ISIC 5610, and from stand-alone event catering, ISIC 5621) — behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: `log-service-record`, `schedule-service-operation`, `coordinate-supply-order`, `flag-food-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Facility unverified** — the target facility's service-contract record must exist AND be independently registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing a food-safety-clearance decision, overriding an allergen-exclusion requirement, direct kitchen-equipment actuation, and food-safety-authority enforcement (health-department clearance, inspection sign-off, license/permit actions) are permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-food-safety-concern` — ALWAYS escalates, regardless of confidence or phase. A "flag a concern" op is never auto-commit-eligible and never finalizes a food-safety-authority decision itself — it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold — a large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: service-record logging only (approval-gated)
  - Phase 2: + service-operation scheduling, supply-order proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals (food-safety concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Out of scope (structural, not a rollout milestone)

This actor is **operations coordination only**. It never performs or authorizes:

- Finalizing a food-safety-clearance decision.
- Overriding an allergen-exclusion requirement.
- Direct kitchen-equipment actuation or control (ovens, fryers, walk-in coolers, etc.).
- Food-safety-authority enforcement (health-department clearance, inspection sign-off, license suspension, compliance enforcement).

The governor's `scope-exclusion-violations` check re-scans every proposal for this failure mode independently of the advisor's own framing, and treats it as a HARD, permanent block regardless of confidence or how clean everything else is.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/foodserviceops/governor_test.cljk` — unit tests of governor hard checks and scope exclusion
- `test/foodserviceops/advisor_test.cljk` — advisor proposal shape and consistency
- `test/foodserviceops/phase_test.cljk` — rollout phase logic
- `test/foodserviceops/governor_contract_test.cljk` — full graph integration, audit trail
- `test/foodserviceops/store_contract_test.cljk` — Store protocol and MemStore implementation

## Modules

- `foodserviceops.store` — SSoT (MemStore, String-keyed facility directory, append-only ledger)
- `foodserviceops.advisor` — contained intelligence node (mock + real-LLM seam)
- `foodserviceops.governor` — independent compliance layer
- `foodserviceops.phase` — staged rollout (0→3)
- `foodserviceops.operation` — langgraph-clj StateGraph
- `foodserviceops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and ADR-2616562900 (`cloud-itonami-isic-5629-other-food-service-coverage`) for design decisions.
