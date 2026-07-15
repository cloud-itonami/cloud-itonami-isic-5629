# Contributing to cloud-itonami-isic-5629

Contributions should preserve the actor's scope: institutional/contract
food-service back-office coordination only, with CRITICAL exclusions of
food-safety-clearance finalization, allergen-exclusion overrides, and direct
kitchen-equipment actuation (see README.md).

- All code must be `.cljc` (portable Clojure, no JVM-only constructs).
- Tests must pass: `clojure -M:test`
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize a food-safety-clearance decision or otherwise stand in for a
  food-safety authority.
- Override an allergen-exclusion requirement.
- Directly actuate or control kitchen equipment (ovens, fryers, walk-in
  coolers, etc.).
- Perform food-safety-authority enforcement (health-department clearance,
  inspection sign-off, license suspension, compliance enforcement).

Contributions that cross these boundaries will be rejected.
