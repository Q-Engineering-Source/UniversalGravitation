package wi.unigrav.core.player;

/** Positive numeric work requested after a shared decode ledger is exhausted. */
final class NumericWorkExhaustedException extends IllegalArgumentException {
    private final long used;
    private final long requested;
    private final long limit;

    NumericWorkExhaustedException(long used, long requested, long limit) {
        super("numeric decode work exhausted (used=" + used
                + ", requested=" + requested + ", limit=" + limit + ")");
        if (used < 0 || requested <= 0 || limit < 0 || used > limit) {
            throw new IllegalArgumentException("invalid numeric work exhaustion details");
        }
        this.used = used;
        this.requested = requested;
        this.limit = limit;
    }

    long used() { return used; }
    long requested() { return requested; }
    long limit() { return limit; }
}

