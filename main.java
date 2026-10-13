import java.util.List;

/**
 * Quick demonstration of the SeatBookingEngine.
 * Run from the project root after compiling with:
 *
 *   javac -d out src/*.java && java -cp out Main
 *
 * For the full test suite, run:
 *
 *   javac -cp out -d out test/SeatBookingEngineTest.java && java -cp out SeatBookingEngineTest
 */
class Main {
    public static void main(String[] args) {
        System.out.println("=== Seat Booking Engine Demo ===\n");

        SeatBookingEngine engine = new SeatBookingEngine();
        engine.registerSeats("A1", "A2", "A3", "B1");

        // Alice holds A1
        Result r1 = engine.hold("alice", "A1");
        System.out.println("Alice holds A1:  " + r1.getMessage());
        String aliceHoldId = r1.getHoldRecord().getHoldId();

        // Bob tries to hold A1 — should fail
        Result r2 = engine.hold("bob", "A1");
        System.out.println("Bob holds A1:    " + r2.getCode() + " – " + r2.getMessage());

        // Alice confirms A1
        Result r3 = engine.confirm("alice", aliceHoldId);
        System.out.println("Alice confirms:  " + r3.getMessage());

        // Seat status
        Result status = engine.seatStatus("A1");
        System.out.println("A1 status:       " + status.getSeatInfo().getStatus() +
                           " (sold to: " + status.getSeatInfo().getSoldToUserId() + ")");

        // Bob batch-holds A2 and A3
        Result batch = engine.holdMany("bob", List.of("A2", "A3"));
        System.out.println("\nBob batch holds A2+A3: " + batch.getMessage());
        for (HoldRecord h : batch.getHoldRecords()) {
            System.out.println("  " + h.getSeatId() + " → hold ID " + h.getHoldId());
        }

        System.out.println("\nDone. Run 'java -cp out SeatBookingEngineTest' for all 65 tests.");
    }
}
