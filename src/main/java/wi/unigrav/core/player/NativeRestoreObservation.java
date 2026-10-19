package wi.unigrav.core.player;

import java.util.Objects;

/** Exact, single-transaction evidence for preparing a Free owner for safe native handoff. */
final class NativeRestoreObservation implements FreeReferenceModePrepared.SourceObservation {
    private final FreeOwnerTransaction transaction;
    private final PlayerPhysicsServer.NativeRestoreAuthority authority;
    private final PlayerBodyShape.Dimensions targetDimensions;

    private NativeRestoreObservation(FreeOwnerTransaction transaction,
            PlayerPhysicsServer.NativeRestoreAuthority authority,
            PlayerBodyShape.Dimensions targetDimensions) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.targetDimensions = Objects.requireNonNull(targetDimensions, "targetDimensions");
    }

    static NativeRestoreObservation capture(FreeOwnerTransaction transaction,
            PlayerPhysicsServer.NativeRestoreAuthority authority) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(authority, "authority");
        if (!authority.matches(transaction))
            throw new IllegalStateException("native restore authority does not match its claimed owner transaction");
        var accepted = transaction.accepted().placement();
        if (!(accepted.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !authority.physicalAnchorMatches(transaction)
                || !authority.targetDimensions().supportsCapsule())
            throw new IllegalStateException("native restore source differs from the exact accepted Free anchor");
        return new NativeRestoreObservation(transaction, authority, authority.targetDimensions());
    }

    PlayerBodyShape.Dimensions targetDimensions(FreeOwnerTransaction exactTransaction) {
        if (!matches(exactTransaction))
            throw new IllegalStateException("native restore profile is no longer current for this owner transaction");
        return targetDimensions;
    }

    boolean flagsCurrentAfterInstall(FreeOwnerTransaction exactTransaction) {
        return exactTransaction == transaction && authority.flagsCurrentAfterInstall(exactTransaction);
    }

    @Override public boolean matches(FreeOwnerTransaction exactTransaction) {
        return exactTransaction == transaction && authority.matches(exactTransaction)
                && authority.targetDimensions().equals(targetDimensions)
                && authority.physicalAnchorMatches(exactTransaction);
    }

    @Override public boolean recaptureMatches(FreeOwnerTransaction exactTransaction) {
        return matches(exactTransaction);
    }
}
