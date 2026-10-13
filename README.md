# Seat Booking Engine

A concurrency-safe, in-memory ticket reservation engine written in Java 21.
**"Nobody Gets the Same Seat Twice."**

---

## Overview

This engine manages seat holds, confirmations, and releases with strict safety guarantees:
- No seat can be double-booked or double-held.
- Holds are time-limited and never silently extended.
- A single, well-reasoned lock prevents all race conditions.
- A fake clock makes tests deterministic without `Thread.sleep()`.

---

## Project structure

```
seat_booking_engine/
├── src/
│   ├── Clock.java              # Injectable clock interface
│   ├── SystemClock.java        # Real system clock
│   ├── FakeClock.java          # Controllable fake clock for tests
│   ├── HoldRecord.java         # Immutable hold record + lifecycle status
│   ├── SeatInfo.java           # Per-seat state (AVAILABLE / HELD / SOLD)
│   ├── Result.java             # Result type returned by all engine methods
│   └── SeatBookingEngine.java  # Main engine (all business logic)
├── test/
│   └── SeatBookingEngineTest.java  # Full test suite (65 tests)
├── README.md
├── DECISION.md
├── DECISIONS.md
├── AI_USAGE.md
├── BUGS_I_FEAR.md
└── DESIGN_DISCUSSION.md
```

---

## API

### Setup

```java
// Create engine with default system clock
SeatBookingEngine engine = new SeatBookingEngine();

// Or inject a clock (recommended for tests)
FakeClock clock = new FakeClock(System.currentTimeMillis());
SeatBookingEngine engine = new SeatBookingEngine(clock);

// Register seats before booking
engine.registerSeats("A1", "A2", "B1", "B2");
```

### `hold(userId, seatId)`
Reserve a seat for a user for the default duration (10 minutes).

```java
Result result = engine.hold("alice", "A1");
if (result.isOk()) {
    HoldRecord hold = result.getHoldRecord();
    System.out.println("Hold ID: " + hold.getHoldId());
}
```

**Idempotent:** Calling `hold` again for the same user and same seat while the hold is still active returns the original hold ID — no new hold is created and the expiry is not extended.

### `hold(userId, seatId, holdDurationMillis)`
Same as above but with a custom hold duration.

### `confirm(userId, holdId)`
Confirm a hold, completing the sale. The hold's expiry does not matter — an expired hold can still be confirmed as long as no one else has taken the seat in the meantime.

```java
Result result = engine.confirm("alice", hold.getHoldId());
```

### `release(userId, holdId)`
Release a hold early, freeing the seat for others.

```java
Result result = engine.release("alice", hold.getHoldId());
```

### `seatStatus(seatId)`
Query the current status of a seat.

```java
Result result = engine.seatStatus("A1");
SeatInfo info = result.getSeatInfo();
System.out.println(info.getStatus()); // AVAILABLE / HELD / SOLD
```

### `holdMany(userId, seatIds)`  
### `holdMany(userId, seatIds, holdDurationMillis)`
Atomically hold multiple seats. Either **all** holds succeed or **none** of the state changes are applied.

```java
Result result = engine.holdMany("alice", List.of("A1", "A2", "B1"));
if (result.isOk()) {
    List<HoldRecord> holds = result.getHoldRecords();
}
```

---

## Business rules enforced

| Rule | Description |
|------|-------------|
| R1 | A seat cannot be held by two users simultaneously or sold twice |
| R2 | Maximum 4 active holds per user at any time |
| R3 | An expired hold may still be confirmed if no one else has taken the seat |
| R4 | Holds are never extended; the original expiry timestamp is final |
| R5 | Re-requesting an active hold for the same seat by the same user is idempotent |
| R6 | `hold_many` is atomic — all or nothing |

---

## Building and running tests

**Prerequisites:** Java 21 or later (`java -version` to check).

```bash
# Compile source
mkdir out
javac -d out src/Clock.java src/SystemClock.java src/FakeClock.java \
      src/HoldRecord.java src/SeatInfo.java src/Result.java src/SeatBookingEngine.java

# Compile tests
javac -cp out -d out test/SeatBookingEngineTest.java

# Run tests
java -cp out SeatBookingEngineTest
```

**Expected output:**
```
=== SeatBookingEngine Tests ===
...
=== Results: 65 passed, 0 failed ===
```

---

## Locking strategy

The engine uses a **single `synchronized` lock on `this`** for all public methods.

**Why this is the right choice here:**
- Correctness is simple to reason about — only one operation proceeds at a time.
- No TOCTOU (time-of-check-to-time-of-use) races are possible.
- The implementation is easy to audit and test.

**The trade-off:**
- Under very high throughput, a single lock creates a bottleneck. A production system handling millions of concurrent requests would likely use per-seat locking, optimistic CAS, or a database with row-level locking. Those approaches are significantly harder to implement correctly and test exhaustively.
- For a correctness-first assignment, the simplicity of a single lock is the right choice.

---

## Design decisions

See [`DECISION.md`](DECISION.md) for final decisions on expired holds, hold retention, and hold lifecycle.

See [`DESIGN_DISCUSSION.md`](DESIGN_DISCUSSION.md) for the full reasoning behind each decision, including edge cases and trade-offs.

---

## Known limitations and risks

See [`BUGS_I_FEAR.md`](BUGS_I_FEAR.md) for a candid list of things that could go wrong in production.
