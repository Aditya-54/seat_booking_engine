# Design Decisions

## 1. Expired Holds and Late Confirmation

### Context

The specification creates an ambiguity between R3 and R4.

- **R3:** An expired hold may still be confirmed if nobody else has taken the seat in the meantime.
- **R4:** Holds are final and are never extended.

In a real ticketing system, holding a seat for an additional grace period can prevent other customers from booking limited inventory, even when the original customer never completes payment.

### Decision

We will not extend a hold's original expiration timestamp or introduce an additional grace period.

An expired hold may still be confirmed if its original hold record remains eligible and the seat has not been acquired by another user. If another user successfully acquires the seat first, the original holder's confirmation must fail.

The engine will serialize competing operations using a lock that protects validation and state changes together. This ensures that two users cannot successfully acquire or sell the same seat.

### Reasoning

The goal is to respect the explicit late-confirmation exception in R3 without weakening R4 or inventing an additional business rule.

A separate grace period could keep scarce seats unavailable longer than necessary. Allowing late confirmation without extending the original expiry timestamp avoids that additional delay while preserving the specified exception.

When late confirmation competes with a new hold, the operation that successfully processes first under the engine's synchronization policy determines the outcome.

### Trade-off

This approach does not guarantee that the original holder always gets priority after expiry. A new customer may acquire the seat first, in which case the original holder cannot confirm it.

The exact boundary between expiration and a competing acquisition will be enforced consistently and covered by tests.



## 2. Hold Record Retention

### Context

An expired hold may still be eligible for confirmation if another user has not acquired the seat. If a different user acquires the seat, the original hold must no longer be able to claim it.

Deleting expired hold records immediately would make it difficult to distinguish an expired hold from a hold ID that never existed.

### Decision

Hold records will be retained in memory after expiration and after the seat is acquired by another user.

Each hold record will preserve its hold ID, user ID, seat ID, creation time, expiration time and lifecycle state. The seat record will separately track its current state and current hold ID, where applicable.

A confirmation request will validate both the referenced hold and the seat's current ownership. An old hold record will never grant ownership merely because its ID is valid.

### Reasoning

Keeping the record allows the engine to return a meaningful failure for an expired or superseded hold instead of treating every old ID as unknown. It also preserves the information needed to evaluate late-confirmation requests correctly.

This is an in-memory assignment, so indefinite retention is acceptable for the initial implementation. A production system with a long-running workload would need a deliberate retention and cleanup policy.

### Trade-off

Retaining every hold increases memory usage over time. Cleanup is intentionally deferred because deleting records too early could complicate late-confirmation handling and diagnostic behaviour.


## 3. Expired Hold Lifecycle

### Decision

Expired hold records remain available for lookup, but they are never automatically revived when a seat becomes free again.

If another user acquires and later releases a seat, the seat becomes available for a new hold. The previous holder must create a new hold rather than regain ownership through an old hold ID.

### Reasoning

A seat's current ownership must be independent of its historical hold records. Automatically reviving an old hold could create unexpected ownership changes and make concurrent operations harder to reason about.

This approach keeps the lifecycle predictable and avoids adding a separate grace-period or revival mechanism that the specification does not require.