package wi.unigrav.core.boost;

import wi.unigrav.api.math.Vec3;

/**
 * Pure codec/budget probes shared by JUnit and the dedicated-only native
 * selftest. This class intentionally has no Minecraft runtime linkage.
 */
public final class RemoteBoostDiagnostics {

    private RemoteBoostDiagnostics() {
    }

    public static Result runPureSelfTest() {
        int checks = 0;

        BoostProtocol.Packet frame = BoostProtocol.frame(17L, 3L, 1L, 0, 5, true);
        require(frame.equals(BoostProtocol.decode(BoostProtocol.encode(frame))), "fixed codec round-trip");
        checks++;

        Vec3 origin = Vec3.ZERO;
        Vec3 proposed = new Vec3(24.5D, 0.0D, 0.0D);
        BoostMovementBudget budget = new BoostMovementBudget(490.0D, 0L, origin);
        BoostMovementBudget.Admission admission = budget.prepare(1L, 0L, proposed);
        require(admission.accepted(), "24.5m proposal admission");
        checks++;

        require(!budget.commit(admission, new Vec3(4.7D, 0.0D, 0.0D)),
                "native wall mismatch rejects budget commit");
        checks++;

        return new Result(checks, "pure budget/codec");
    }

    static void require(boolean condition, String check) {
        if (!condition) {
            throw new IllegalStateException("Remote boost selftest failed: " + check);
        }
    }

    public record Result(int checks, String label) {
        public Result {
            if (checks <= 0 || label == null || label.isEmpty()) {
                throw new IllegalArgumentException("Invalid diagnostic result");
            }
        }
    }
}
