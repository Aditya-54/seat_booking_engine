# AI_USAGE.md

This document gives an honest account of how AI tools were used during this assignment.

---

## What AI was used for

### Research and understanding requirements

I used ChatGPT to help understand the assignment requirements and clarify ambiguities in the business rules — particularly the tension between R3 (late confirmation is allowed) and R4 (holds are never extended). AI helped me reason about what each rule meant independently and why they do not actually contradict each other.

### Discussing design alternatives and edge cases

I discussed several design alternatives with ChatGPT before deciding on the implementation approach:

- **Locking strategy:** Compared single engine-wide lock vs. per-seat locking. AI explained the deadlock risk of per-seat locking with batch holds and helped me articulate why the single lock is the right choice for a correctness-first assignment.
- **Expired hold behaviour:** Discussed what "expired" should mean at the hold-record level vs. the seat level, and how to handle the Alice/Bob race condition.
- **Atomic `hold_many`:** Discussed validate-then-apply vs. apply-with-rollback, and why validate-first is simpler and safer under a single lock.
- **Hold record retention:** Discussed the trade-off between memory growth and the ability to give meaningful error messages for late confirms and superseded holds.

### Assistance writing documentation

AI assisted with drafting and structuring `README.md`, `DECISIONS.md`, `BUGS_I_FEAR.md`, and `DESIGN_DISCUSSION.md`. The ideas, decisions, and trade-offs described in those documents reflect the actual design reasoning from my discussions — AI helped express them clearly and in an organised format.

### Code assistance

The full implementation — `SeatBookingEngine.java`, `HoldRecord.java`, `SeatInfo.java`, `Result.java`, `Clock.java`, `FakeClock.java`, `SystemClock.java`, and `SeatBookingEngineTest.java` — was written with AI assistance (Antigravity / Claude). The logic was guided by the design decisions reached through the discussions described above, and the code was verified by running the tests and observing actual results. The test output in this repository represents actual test runs, not fabricated results.

---

## What AI was NOT used for

- **Fabricating test results:** All 65 test results in the test output are from actual compilation and execution of the code.
- **Inventing design decisions:** The trade-offs described in `DECISION.md` and `DESIGN_DISCUSSION.md` reflect genuine reasoning, not hallucinated explanations.
- **Hiding AI involvement:** This document exists specifically to be transparent about the scope and nature of AI usage.

---

## Summary

| Activity | AI involved? |
|----------|-------------|
| Understanding business rules | Yes (ChatGPT) |
| Design decisions and trade-offs | Yes (ChatGPT discussion) |
| Java implementation | Yes (Antigravity / Claude) |
| Test suite | Yes (Antigravity / Claude) |
| README and documentation | Yes (Antigravity / Claude, guided by my decisions) |
| Running tests and verifying results | Executed and verified manually |
| Final review of all content | My own |
