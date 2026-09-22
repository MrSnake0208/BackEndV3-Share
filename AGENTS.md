=== SCOPE LIMITS (these bound what you PROPOSE, never what you look for) ===
Report anything that is actually wrong here — including a rare-looking case, if
this project actually produces it. Then keep the fix in scope:

1. This is not a security paper. Verification is welcome; over-defense is not.
   Unless this project states otherwise, assume a cooperating operator on their
   own machine; if it has a real adversary, it will say so and that scope wins.
2. Do not add hashes, checksums or fingerprints unless the hash replaces a
   materially more expensive operation AND its result changes what happens next.
3. No defensive scaffolding: no feature flags, migration frameworks, compat
   layers or wrappers for cases that do not occur here.
4. No corner-case obsession: exotic encodings, symlink races, RTL text and
   millisecond races are out of scope unless the case is reachable through this
   project's supported use — its documented inputs, its published interface, its
   real data. Reachable is enough; you do not need a reproduction. Constructible
   in principle is not enough.
5. Where judgement is needed, judge. Do not replace it with a scoring table, a
   checklist, or a re-verification loop over something already settled.
6. None of this overrides security, migration, verification or review that the
   user, this project's own conventions, or a higher-priority rule asked for.
   Those were requested; they are the work, not scope creep.
   Shapes already seen, for calibration. Examples, not a checklist — a real finding
   is not dismissed by resembling one:
   H hashing every row of two spreadsheets to answer what comparing cells answers
   H writing checksum files that nothing ever reads
   E hardening the accounts of an app that has no users and no deployment
   R auditing your own patch all night while the feature stays unwritten
   R a reviewer that returns a failing verdict on everything
   O guards whose justification is the previous guard, not the requirement
   And two that look like the above and are not. Report these:
   ✓ a digest that lets you skip re-reading a large file you already have
   ✓ a rare-looking input this project's own documentation example produces
   Before running any check, answer: what specific failure would this detect, and
   what would I do differently if it occurred? No answer means do not run it.
   Say plainly when something is correct. Do not manufacture findings.

## Inventory domain standards

Tasks involving inventory, reward records, inventory import/export, inventory snapshots, or the inventory exchange protocol must read and follow these repository-local standards before implementation:

- `docs/standards/inventory/backend-design.md`
- `docs/standards/inventory/exchange-protocol-v1.md`

Machine-readable protocol schema:

- `docs/standards/inventory/schema/inventory-exchange-v1.schema.json`

These documents are the authoritative inventory-domain specifications for this repository. Non-inventory tasks do not need to read them.

### Inventory invariants

- Current inventory state and historical reward records are different facts; do not derive ordinary current-stock reads by replaying the full reward history.
- `reward_delta` is an increment event; `stock_snapshot` is an absolute observation. Do not interchange their semantics.
- `record_id` is the idempotency key for exchanged records; repeated imports of the same record must not apply stock changes twice.
- Cross-platform object identity uses the stable `(entity_type, id)` pair. Display names are not database or protocol identity keys.
- Snapshot baseline rules determine whether a delayed reward changes current stock; historical rewards may remain valid for statistics without changing current stock.
- Protocol behavior and field semantics must stay aligned with `exchange-protocol-v1.md` and its JSON Schema.


## Local code-level test completion gate (2026-09-22)

Before implementing a feature or bug fix, read `TESTING.md` at the YuanHub-All root and both `.trellis/spec/*/quality-guidelines.md`. Keep the active task's `test-plan.json` mapping changed sources to changed tests and business invariants. Bug fixes require actual red→green regression logs; do not only check source strings. New Trellis tasks automatically receive these guidelines in implement/check contexts and a test-plan scaffold. The scaffold itself is not coverage.

Run root `./test quick` after edits and `./test smart --task .trellis/tasks/<task>` before completion; shared test infrastructure/release changes also require `./test all`. Mongo transaction/Redis semantics tests must use owned disposable containers, never existing development or production services. No unexplained missing tests, failed/skipped suites or stale results may be declared done. Actual Trellis `finish`/`archive` commands enforce fresh evidence before changing task state. Do not bypass this gate by directly changing task status or manufacturing reports. Preserve unrelated dirty files; explain them by exact path rather than resetting or including them in a commit.
