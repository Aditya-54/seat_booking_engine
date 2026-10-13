# DESIGN_DISCUSSION.md

This document reconstructs the design discussions that shaped this engine's implementation. It is presented as a Q&A — questions represent the kinds of questions I explored, and the explanations record the reasoning arrived at through research and discussion with AI tools. See `AI_USAGE.md` for a transparent account of which parts involved AI assistance.

---

## Topic 1 — Expired holds and late confirmation

**Q: The spec says expired holds may still be confirmed (R3), but also that holds are never extended (R4). Aren't those contradictory?**

No. R3 and R4 address different things. R4 says the expiry *timestamp* is fixed — the engine must never push the deadline further. R3 says that after the deadline has passed, if no one else has taken the seat, the original holder can still complete the purchase.

Think of it like a parking meter that ran out: your car is still there and you still want the space, but the city's right to ticket or tow depends on whether enforcement has acted yet. Here the "enforcement action" is another user acquiring the seat.

The implementation checks whether `seat.getCurrentHold()` still belongs to the confirming user. If it does, confirmation proceeds regardless of expiry. If another user has replaced the hold, the original hold is marked `SUPERSEDED` and confirmation is rejected.

**Q: Does this mean an expired hold has any priority over a new hold request?**

No. Whoever gets the engine lock first wins. An expired hold gives the original holder no special priority — it merely means their window has not been officially closed yet if nobody acted. The race is decided by whichever operation acquires the lock first.

---

## Topic 2 — The Alice and Bob race condition

**Q: What happens if Alice's hold expires and Bob tries to grab the seat at exactly the same time as Alice tries to confirm?**

Because the engine uses a single lock, one operation proceeds and the other waits. If Bob's `hold` executes first, it marks Alice's hold as `SUPERSEDED` and then Alice's `confirm` will fail with `HOLD_SUPERSEDED`. If Alice's `confirm` executes first, it succeeds, the seat is sold, and Bob's `hold` will fail with `SEAT_UNAVAILABLE`.

There is no scenario where both succeed, because only one operation holds the lock at a time. The single lock is what makes the guarantee provable — with a per-seat lock approach the same guarantee is achievable but requires more careful reasoning about the lock ordering.

---

## Topic 3 — Hold record retention

**Q: Why keep hold records after they expire? Why not delete them to save memory?**

Three reasons:

1. **Late confirmation (R3):** The engine needs to look up a hold by ID to evaluate a late confirm. If the record is gone, the engine cannot distinguish "expired but eligible" from "ID never existed."

2. **Error clarity:** Returning `HOLD_SUPERSEDED` or `HOLD_ALREADY_CONFIRMED` is more useful than `HOLD_NOT_FOUND` for debugging. Retaining the record lets the engine give the right answer.

3. **History for diagnostics:** In a real ticketing system, you would want an audit trail. This implementation approximates that simply.

The trade-off is unbounded memory growth. For this assignment — which is entirely in-memory with no long-running production workload — that is acceptable. A production system would need a TTL-based eviction policy or archival to a database.

---

## Topic 4 — No expiry extension

**Q: Why not extend the hold expiry if the user requests the same seat again?**

R4 is explicit: holds are never extended. Beyond the specification requirement, there is a practical reason too: extending a hold keeps the seat unavailable for longer, which harms other customers trying to book limited inventory. The idempotent return of the original hold (R5) is the correct behaviour — the user gets confirmation that their hold still exists without getting extra time.

---

## Topic 5 — Released seat behaviour

**Q: If Alice holds a seat, then releases it, can she get it back with an old hold ID?**

No. Once a hold is released, the hold record is in `RELEASED` status permanently. Calling `confirm` or `release` again on that hold ID will return `HOLD_ALREADY_RELEASED`. The seat becomes `AVAILABLE` and anyone can place a fresh hold on it, including Alice — but she must call `hold` again to get a new hold ID.

This design keeps the seat lifecycle predictable: `AVAILABLE → HELD → SOLD` or `AVAILABLE → HELD → AVAILABLE (via release)`. There is no backwards transition and no revival of old hold records.

---

## Topic 6 — Atomic batch holds (`hold_many`)

**Q: Why does `hold_many` use validate-then-apply rather than apply-with-rollback?**

Validate-then-apply is simpler and safer:

- It never creates partial state that must be cleaned up.
- Under a single lock, the state cannot change between validation and application, so there is no TOCTOU risk.
- There is nothing to "roll back" because nothing was written yet.

An apply-with-rollback approach would require either: (a) careful bookkeeping of every state change made before the failure, or (b) a transactional data structure. Both are harder to implement correctly than the validate-first approach.

The trade-off is that `hold_many` does more reads before any writes. Under high concurrency this is fine because the entire method is serialized by the engine lock anyway.

---

## Topic 7 — Locking strategy and its trade-offs

**Q: Why use a single engine-wide lock instead of per-seat locking?**

A single lock is the simplest correct strategy. It eliminates all race conditions by construction: no two operations can interfere because only one runs at a time.

Per-seat locking would allow concurrent operations on different seats, improving throughput under load. However, it introduces deadlock risk (if a `hold_many` must acquire multiple seat locks), requires careful lock-ordering discipline, and is substantially harder to test for correctness.

For an assignment whose primary goal is correctness and clarity, the single lock is the right choice. The document acknowledges this explicitly so reviewers understand the trade-off was deliberate, not an oversight.

A production-scale alternative might use:
- Per-seat locks with a strict acquisition order (e.g. sorted by seat ID) to prevent deadlocks.
- Optimistic locking with compare-and-swap, retrying on conflict.
- A relational database with row-level locking and serializable transactions.

---

## Topic 8 — Deterministic testing without `Thread.sleep()`

**Q: How do you test time-sensitive logic (expiry, late confirmation) without making tests slow or flaky?**

By injecting a `FakeClock`. The engine receives a `Clock` interface instead of calling `System.currentTimeMillis()` directly. In tests, a `FakeClock` is passed in; its time only advances when `clock.advance(millis)` is called explicitly.

This means tests can:
- Place a hold, advance the clock past expiry, then test behaviour — all in microseconds.
- Reproduce exact boundary conditions (e.g. "exactly 1 ms after expiry") reliably.
- Run completely deterministically without sleeping.

The only place the real clock matters is the production `SystemClock`, which is not used in any test.

---

## Topic 9 — Validation before mutation

**Q: What happens if `hold_many` fails halfway through due to a bad input?**

It cannot. Validation runs completely before any state is changed. If any pre-condition fails — missing seat, unavailable seat, hold limit exceeded, null input — the method returns an error immediately and the engine is in exactly the same state it was before the call.

This "validate all, then apply all" pattern means the engine never has to undo partially applied changes. It is especially important for `hold_many`, where failure partway through would leave some seats held and others not — violating the atomic guarantee.

---

## Topic 10 — Remaining limitations

**Q: What would need to change to use this engine in production?**

Several things are intentionally out of scope for this assignment:

1. **Memory management:** Hold records are retained forever. A production system needs eviction/archival.
2. **Persistence:** Everything is in RAM. A crash loses all state. A real system needs durable storage.
3. **Scalability:** The single lock is a bottleneck above a few thousand ops/second. Horizontal scaling needs distributed locking or per-seat partitioning.
4. **Seat registration:** Seats must be pre-registered. A production system would probably load seat inventory from a database.
5. **Hold duration policy:** The engine accepts arbitrary durations from the caller. A production system would likely enforce a policy (e.g. minimum 1 minute, maximum 30 minutes).
6. **Background expiry:** Expired holds do not free the seat automatically — they must be displaced by a new `hold` call. A background cleanup thread would improve seat utilisation.
7. **Observability:** The engine has no metrics, logging, or event emission. A production system would emit events for every state change.
