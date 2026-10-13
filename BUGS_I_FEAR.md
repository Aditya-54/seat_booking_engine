# BUGS_I_FEAR.md

A candid list of bugs, edge cases, and failure modes that could cause problems — either in this implementation or in a future production version.

---

## Bugs in this implementation I am watchful about

### 1. Expired-hold `ACTIVE` status confusion

Hold records remain in `ACTIVE` status even after their wall-clock expiry. The engine determines availability by checking `clock.nowMillis() >= expiresAtMillis` on the hold record, not by the `ACTIVE` status alone.

**The risk:** Code that checks `hold.getStatus() == ACTIVE` and assumes the hold is still valid (not expired) would be wrong. Every check for "is this hold valid?" must also check `!hold.isExpiredAt(now)`.

**Mitigation:** The engine's `isSeatAvailableForHold` and `countActiveHolds` helpers both do this combined check.

---

### 2. Hold limit count after expiry

`countActiveHolds` correctly counts holds that are both `ACTIVE` status and not expired. If the clock injection is wrong (e.g. a test that starts a `FakeClock` at `0` and runs holds that expire at timestamps near `Long.MAX_VALUE`), the count could behave unexpectedly.

**The risk:** A user might be blocked from holding seats because expired holds are being counted incorrectly.

**Mitigation:** `FakeClock` starts at a large value (1,000,000 ms) in tests, and expiry arithmetic uses an overflow guard.

---

### 3. `holdMany` idempotency interacts with hold limit

`holdMany` allows an idempotent repeat for seats the user already holds. This path does not count as a new hold toward the limit. However, the pre-check `currentActive + seatIds.size() > MAX_HOLDS_PER_USER` does not subtract idempotent entries before comparing, so it may incorrectly reject a batch where some entries are idempotent repeats.

**Current behaviour:** The limit check is conservative (slightly more restrictive than strictly necessary for idempotent-heavy batches).

---

### 4. Memory growth from held records

Every hold ever created stays in memory. A long-running engine with millions of short-lived holds will eventually run out of heap space.

**The risk:** OOM in production.

**Mitigation:** Out of scope for this assignment. A production system needs an eviction/archival policy.

---

### 5. No automatic expiry sweep

When a hold expires, the seat remains in `HELD` status until another user calls `hold` on it (which then displaces the expired hold). The seat is therefore not immediately available for queries — `seatStatus` will show `HELD` for an expired hold until someone displaces it.

**The risk:** Seat utilisation is lower than it could be. A user who queries `seatStatus` and sees `HELD` might not try to hold it, even though the current hold is actually expired and the seat could be taken.

**Mitigation:** A background thread sweeping expired holds and calling `seat.applyRelease()` would fix this. Not implemented here.

---

## Bugs I would fear in a production version

### 6. Distributed lock stale reads

If this engine were extended to a distributed system (e.g. multiple JVM instances), the in-process lock would not protect shared state. A Redis distributed lock or database serializable transactions would be needed. Getting distributed locking right under network partitions (split-brain, lock expiry, re-entrant behaviour) is extremely hard.

### 7. Clock skew in distributed deployments

A `SystemClock` in two different JVM instances may not agree on the current time. An expired hold on instance A might still appear active on instance B. This could allow double-booking across instances.

### 8. Long GC pauses invalidating timing guarantees

Under a single-JVM deployment, a GC pause can suspend a thread between the time it reads `clock.nowMillis()` and the time it completes the hold. If the pause is longer than the hold duration, the expiry could be missed. This is unlikely in practice but theoretically possible under stop-the-world GC.

### 9. Integer arithmetic in hold duration

The engine guard against `now + holdDurationMillis` overflowing `Long.MAX_VALUE`. However, if `holdDurationMillis` itself is computed from user input (e.g., minutes converted to milliseconds), there could be an overflow before the call. Callers should validate their inputs before passing them to the engine.

### 10. Unbounded `holdsByUser` list per user

Each user's hold list in `holdsByUser` grows without bound. Scanning it is O(n) in the number of holds ever created by that user. For a user who holds and releases thousands of seats over time, this scan becomes slow.

**The fix:** Track only currently-active holds in a separate counter or set, archiving completed holds separately.
