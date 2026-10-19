package wi.unigrav.core.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreePostprocessingWorkTest {
    @Test void sharedOwnerTickCounterAccumulatesAndRejectsInsufficientRemainingWork() {
        var work = new FreePostprocessingWork();
        work.charge(FreePostprocessingWork.MAX_UNITS - 2);
        work.charge(1);
        assertEquals(FreePostprocessingWork.MAX_UNITS - 1, work.units());
        assertFalse(work.terminal());

        var exhausted = assertThrows(FreePostprocessingWork.Exhausted.class, () -> work.charge(2));
        assertTrue(exhausted.getMessage().contains("used=" + (FreePostprocessingWork.MAX_UNITS - 1)));
        assertTrue(exhausted.getMessage().contains("requested=2"));
        assertEquals(FreePostprocessingWork.MAX_UNITS - 1, work.units(),
                "a rejected classification must not partially debit or mint remaining units");
        assertTrue(work.terminal());
        assertThrows(FreePostprocessingWork.Exhausted.class, () -> work.charge(1),
                "a terminal postprocessing ledger cannot be reset for another candidate");
    }
}