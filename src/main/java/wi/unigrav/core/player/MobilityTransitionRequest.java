package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A mode request uses the accepted Q and the exact owner invocation, never an invented mouse request. */
record MobilityTransitionRequest(FreeOwnerTransaction transaction, PlayerPhysicsStep.TravelState travel,
        PlayerPhysicalSnapshot source, long generation, long inputSequence,
        Rotation3d rawDesiredQ, boolean explicitFlight, BooleanSupplier qualification) implements ModeTransitionPrepared.Request {
    MobilityTransitionRequest {
        Objects.requireNonNull(transaction); Objects.requireNonNull(travel);
        Objects.requireNonNull(source); Objects.requireNonNull(rawDesiredQ); Objects.requireNonNull(qualification);
        if (source != transaction.accepted() || !rawDesiredQ.equals(source.placement().attitude()))
            throw new IllegalArgumentException("Mobility transition must preserve its accepted attitude");
    }
    static MobilityTransitionRequest capture(FreeOwnerTransaction transaction, PlayerPhysicsStep.TravelState travel) {
        transaction.requireCurrent();
        var intent = travel.mobility();
        if (!intent.blocksCapture()) throw new IllegalStateException("Mobility transition has no accepted flight intent");
        return new MobilityTransitionRequest(transaction, travel, transaction.accepted(), intent.generation(),
                intent.inputSequence(), transaction.accepted().placement().attitude(), true, () -> true);
    }
    static MobilityTransitionRequest zeroReference(FreeOwnerTransaction transaction,
            PlayerPhysicsStep.TravelState travel, BooleanSupplier qualification) {
        transaction.requireCurrent();
        var intent = travel.mobility();
        if (intent.mode() != PlayerMobilityIntent.Mode.WALK || !qualification.getAsBoolean())
            throw new IllegalStateException("Automatic Free transition lacks a complete zero-reference observation");
        transaction.requireCurrent();
        return new MobilityTransitionRequest(transaction, travel, transaction.accepted(), intent.generation(),
                intent.inputSequence(), transaction.accepted().placement().attitude(), false, qualification);
    }
    boolean current() {
        transaction.requireCurrent();
        var intent = travel.mobility();
        boolean qualified = qualification.getAsBoolean();
        transaction.requireCurrent();
        return source == transaction.accepted() && intent.generation() == generation
                && intent.inputSequence() == inputSequence && qualified
                && (explicitFlight ? intent.blocksCapture() : intent.mode() == PlayerMobilityIntent.Mode.WALK);
    }
}
