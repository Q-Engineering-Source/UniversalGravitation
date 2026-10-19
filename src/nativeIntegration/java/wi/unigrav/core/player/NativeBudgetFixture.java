package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only same-package access to the actual owner frame ledger; no synthetic terminal setter. */
public final class NativeBudgetFixture {
    private NativeBudgetFixture() { }
    public static void exhaust(FreeTickResources resources) {
        resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
    }
    public static void assertOriginalExhaustedCause(Throwable cause) {
        assertInstanceOf(FreePostprocessingWork.Exhausted.class,cause);
    }
    public static void assertSpentWithoutRefund(FreeTickResources resources) {
        assertEquals(FreePostprocessingWork.MAX_UNITS,resources.postprocessing().units());
        assertTrue(resources.postprocessing().terminal());
    }
}