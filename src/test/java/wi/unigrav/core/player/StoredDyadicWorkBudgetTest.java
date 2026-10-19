package wi.unigrav.core.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredDyadicWorkBudgetTest {
    @Test
    void positiveOverCapChargeHasTypedDetailsAndKeepsPriorWork() {
        StoredDyadic.Work work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS - 4L, 31);
        long usedBeforeFailure = work.units();

        NumericWorkExhaustedException failure = assertThrows(NumericWorkExhaustedException.class,
                () -> work.charge(5, 64));

        assertEquals(usedBeforeFailure, failure.used());
        assertEquals(5, failure.requested());
        assertEquals(StoredDyadic.MAX_WORK_UNITS, failure.limit());
        assertEquals(usedBeforeFailure, work.units(),
                "a rejected atomic charge adds nothing and prior partial attempts remain charged");
        assertEquals(31, work.peakBits());
        assertThrows(NumericWorkExhaustedException.class, () -> work.charge(5));
    }

    @Test
    void negativeChargeAndBitWidthErrorsRemainInputValidationFailures() {
        StoredDyadic.Work negative = new StoredDyadic.Work();
        IllegalArgumentException negativeFailure = assertThrows(IllegalArgumentException.class,
                () -> negative.charge(-1));
        assertFalse(negativeFailure instanceof NumericWorkExhaustedException);
        assertEquals(0, negative.units());

        StoredDyadic.Work invalidBits = new StoredDyadic.Work();
        IllegalArgumentException bitFailure = assertThrows(IllegalArgumentException.class,
                () -> invalidBits.charge(1, StoredDyadic.MAX_BITS + 1));
        assertFalse(bitFailure instanceof NumericWorkExhaustedException);
        assertEquals(1, invalidBits.units(),
                "the existing unit charge remains accounted before a later bit-width validation failure");
        assertEquals(0, invalidBits.peakBits());
    }

    @Test
    void zeroChargeDoesNotExhaustButStillValidatesBitWidths() {
        StoredDyadic.Work work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        assertDoesNotThrow(() -> work.charge(0));
        assertThrows(IllegalArgumentException.class,
                () -> work.charge(0, StoredDyadic.MAX_BITS + 1));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }
}
