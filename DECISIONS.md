# DECISIONS.md

Final design decisions for the seat booking engine. This file records *what* was decided. For the reasoning and discussion behind each decision, see `DESIGN_DISCUSSION.md`.

---

## D1 — Locking strategy

**Decision:** Single engine-wide `synchronized` lock on the `SeatBookingEngine` instance.

Every public method is `synchronized`, so only one operation executes at a time.

**Rationale:** Simplest strategy that provides correct mutual exclusion with no TOCTOU races.  
**Trade-off:** Limits throughput under high concurrency. Acceptable for a correctness-first assignment.

---

## D2 — Expired hold + late confirmation (R3 and R4)

**Decision:** An expired hold may be confirmed if the seat has not been acquired by another user since the hold was placed. The hold's original expiry timestamp is never modified or extended.

When a competing user successfully places a new hold on an expired seat, the original hold is marked `SUPERSEDED` and can no longer be confirmed.

**Rationale:** Satisfies R3 (late confirmation is allowed) without violating R4 (no extension).  
**Trade-off:** The original holder does not get priority after expiry. First-come-first-served under the engine lock.

---

## D3 — Hold record retention

**Decision:** Hold records are kept in memory indefinitely. They are never deleted after expiry, release, or supersession.

**Rationale:** Retention allows the engine to return meaningful error codes (e.g., `HOLD_SUPERSEDED` vs. `HOLD_NOT_FOUND`) and correctly evaluate late-confirmation eligibility.  
**Trade-off:** Memory grows over time. Cleanup is out of scope for this in-memory assignment.

---

## D4 — Expired hold lifecycle

**Decision:** An expired hold record stays in `ACTIVE` status (from the hold record's perspective) until it is confirmed, released, or superseded. The engine determines availability by checking `clock.nowMillis() >= expiresAtMillis`.

Expired holds are never automatically revived if a seat later becomes free again after another user released it.

**Rationale:** Seat ownership is determined by the seat's current hold, not by historical hold records. A released seat is simply available for a new hold by anyone.

---

## D5 — Idempotency (R5)

**Decision:** If a user calls `hold` for a seat they already have an active, non-expired hold on, the original `HoldRecord` is returned unchanged. No new hold is created and the expiry is not extended.

**Rationale:** Prevents accidental double-counting and makes clients safe to retry without side effects.

---

## D6 — Atomic `hold_many` (R6)

**Decision:** `holdMany` validates every seat against every pre-condition (existence, availability, hold limit) before mutating any state. If any seat fails validation, the entire batch is rejected with no state changes.

**Rationale:** Guarantees the all-or-nothing semantic. Validation before mutation is simpler than rollback-on-failure and avoids partial-state cleanup.

---

## D7 — Injectable clock

**Decision:** All time-sensitive logic reads time via a `Clock` interface. The production `SystemClock` wraps `System.currentTimeMillis()`. Tests use `FakeClock`, which only advances when explicitly told to.

**Rationale:** Deterministic, fast tests with no `Thread.sleep()` calls.

---

## D8 — Input validation before mutation

**Decision:** All inputs (null/blank user or seat IDs, non-positive durations, overflow detection) are validated before any state is changed. A validation failure leaves the engine state unchanged.

**Rationale:** Prevents partial state corruption from invalid arguments. Follows fail-fast principles.
