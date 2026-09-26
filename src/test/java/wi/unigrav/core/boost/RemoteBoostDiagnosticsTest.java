package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the production headless diagnostic seam used by /ugboost selftest. */
class RemoteBoostDiagnosticsTest {

    @Test
    void pureSelfTestExercisesTheProductionCodecAndBudgetSeam() {
        RemoteBoostDiagnostics.Result result = RemoteBoostDiagnostics.runPureSelfTest();

        assertEquals(3, result.checks());
        assertTrue(result.label().contains("pure budget/codec"));
    }
}
