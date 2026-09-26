package wi.unigrav.core.client.boost;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;

/**
 * Client-only, Minecraft-free ownership reducer for one negotiated remote
 * boost connection. It deliberately records no position or claimed velocity:
 * those stay authoritative on the server's movement budget.
 */
final class RemoteBoostSession {

    private static final long NO_NONCE = Long.MIN_VALUE;

    private final Predicate<BoostProtocol.Packet> applyOperation;
    private Object lifetime = new Object();
    private boolean applying;
    private boolean applicationBroken;
    private BoostProtocol.Packet lastAppliedState;
    private BoostProtocol.Packet lastVelocityOperation;

    private long nonce = NO_NONCE;
    private long epoch;
    private long lastSerial = -1L;
    private long nextControlSequence;
    private long nextFrameSequence;
    private int dimension;
    private int gear = 5;
    private double ceilingMps;
    private BoostProtocol.Mode mode = BoostProtocol.Mode.UNAVAILABLE;
    private BoostProtocol.Reason lastReason = BoostProtocol.Reason.NONE;
    /** The G-key choice, deliberately independent from the disposable transport session. */
    private boolean requestedIntent;
    /** A fresh STATE is the bounded retry edge for the outstanding MODE request. */
    private boolean modeRequestPending;
    /** STATE.flag; local feet-Y is combined by the Minecraft-facing owner. */
    private boolean restrictedCorridor;
    private boolean physicsRetired;

    /** The only system seam is the client motion write, not a second state reducer. */
    RemoteBoostSession(Predicate<BoostProtocol.Packet> applyOperation) {
        this.applyOperation = Objects.requireNonNull(applyOperation, "applyOperation");
    }

    boolean acceptOffer(BoostProtocol.Packet packet) {
        if (physicsRetired || packet == null || packet.kind() != BoostProtocol.Kind.OFFER
                || packet.version() != BoostProtocol.VERSION || !packet.flag()
                || !RemoteBoostCap.validRemoteCeiling(packet.ceilingMps())) {
            return false;
        }
        nonce = packet.nonce();
        resetApplicationLifetime();
        epoch = 0L;
        lastSerial = -1L;
        nextControlSequence = 0L;
        nextFrameSequence = 0L;
        dimension = packet.dimension();
        gear = 5;
        ceilingMps = packet.ceilingMps();
        // OFFER only proves per-peer channel presence plus a fresh nonce. The
        // server's exact-version HELLO acknowledgement must arrive as STATE
        // READY before this client may request a mode change.
        mode = BoostProtocol.Mode.UNAVAILABLE;
        lastReason = BoostProtocol.Reason.NONE;
        modeRequestPending = false;
        restrictedCorridor = false;
        return true;
    }

    boolean acceptState(BoostProtocol.Packet packet, int currentDimension) {
        if (applying) throw new IllegalStateException("Reentrant boost STATE application");
        if (!matchesCurrentFreshState(packet, currentDimension) || packet.mode() == null
                || packet.operation() == null || packet.reason() == null
                || !finite(packet.velocity())) {
            return false;
        }
        if ((packet.mode() == BoostProtocol.Mode.ACTIVE
                || packet.mode() == BoostProtocol.Mode.CLOSING)
                && (!RemoteBoostCap.validRemoteCeiling(packet.ceilingMps()) || !validGear(packet.gear()))) {
            return false;
        }
        if (packet.epoch() > epoch) {
            // FRAME sequences are scoped to the server's movement-budget
            // epoch; its first admitted sample for a new epoch is sequence 1.
            nextFrameSequence = 0L;
        }
        epoch = packet.epoch();
        lastSerial = packet.sequence();
        dimension = packet.dimension();
        mode = packet.mode();
        lastReason = packet.reason();
        restrictedCorridor = packet.flag();
        if (RemoteBoostCap.validRemoteCeiling(packet.ceilingMps())) {
            ceilingMps = packet.ceilingMps();
        }
        if (validGear(packet.gear())) {
            gear = packet.gear();
        }
        // A new server STATE is the only retry edge. It never changes the G
        // choice: a normal incident, an error and a transport reset must not
        // silently turn a user's requested mode off.
        modeRequestPending = false;
        Object expectedLifetime = lifetime;
        boolean previouslyBroken = applicationBroken;
        // Consume the serial before the external write: a failed or throwing ADD
        // must not be replayed. Successful reception alone cannot issue a receipt.
        lastAppliedState = null;
        applicationBroken = true;
        applying = true;
        try {
            if (!applyOperation.test(packet) || lifetime != expectedLifetime) return false;
            boolean replacesVelocity = packet.operation() == BoostProtocol.Operation.REPLACE
                    || packet.operation() == BoostProtocol.Operation.STOP;
            applicationBroken = previouslyBroken && !replacesVelocity;
            if (packet.operation() != BoostProtocol.Operation.NONE) lastVelocityOperation = packet;
            if (!applicationBroken) lastAppliedState = packet;
            return true;
        } finally {
            applying = false;
        }
    }

    /** Empty during application or after corruption; NONE cannot repair a missed ADD. */
    Optional<BoostProtocol.StateApplication> applicationReceipt() {
        return applying || applicationBroken || lastAppliedState == null ? Optional.empty()
                : Optional.of(new BoostProtocol.StateApplication(lastAppliedState, lastVelocityOperation));
    }

    void invalidateApplication() {
        lifetime = new Object();
        applicationBroken = true;
        lastAppliedState = null;
    }

    private void resetApplicationLifetime() {
        lifetime = new Object();
        applicationBroken = false;
        lastAppliedState = null;
        lastVelocityOperation = null;
    }

    /** Identity/order gate only; deliberately does not inspect payload content. */
    boolean matchesCurrentFreshState(BoostProtocol.Packet packet, int currentDimension) {
        return !physicsRetired && packet != null && packet.kind() == BoostProtocol.Kind.STATE
                && packet.version() == BoostProtocol.VERSION && packet.nonce() == nonce
                && packet.dimension() == currentDimension && packet.epoch() >= epoch
                && (packet.epoch() != epoch || packet.sequence() > lastSerial);
    }

    /**
     * Records an actual G-key choice and emits at most one immediately eligible
     * MODE request. Environment and packet handling must use
     * {@link #nextEligibleModeRequest(int)} instead.
     */
    BoostProtocol.Packet requestMode(boolean enabled, int currentDimension) {
        requestedIntent = enabled;
        return issueModeRequest(currentDimension, true);
    }

    /** Emits a bounded retry only after an explicit choice or fresh server state. */
    BoostProtocol.Packet nextEligibleModeRequest(int currentDimension) {
        return issueModeRequest(currentDimension, false);
    }

    private BoostProtocol.Packet issueModeRequest(int currentDimension, boolean explicit) {
        if (physicsRetired || nonce == NO_NONCE || currentDimension != dimension || modeRequestPending) {
            return null;
        }
        boolean enable = requestedIntent;
        if (enable ? ((mode != BoostProtocol.Mode.READY || (!explicit && lastReason != BoostProtocol.Reason.NONE))
                && mode != BoostProtocol.Mode.CLOSING)
                : mode != BoostProtocol.Mode.ACTIVE) {
            return null;
        }
        modeRequestPending = true;
        return BoostProtocol.modeRequest(nonce, epoch, ++nextControlSequence,
                currentDimension, enable);
    }

    BoostProtocol.Packet nextFrame(int currentDimension, int selectedGear, boolean settled) {
        return nextFrame(currentDimension, selectedGear, settled, Vec3.ZERO);
    }

    BoostProtocol.Packet nextFrame(int currentDimension, int selectedGear, boolean settled,
            Vec3 velocityHintMps) {
        if (!hasCustody() || currentDimension != dimension || !validGear(selectedGear)) {
            return null;
        }
        if (!finite(velocityHintMps)) {
            velocityHintMps = Vec3.ZERO;
        }
        gear = selectedGear;
        return BoostProtocol.frame(nonce, epoch, ++nextFrameSequence,
                currentDimension, selectedGear, settled, velocityHintMps);
    }

    boolean hasOffer() {
        return nonce != NO_NONCE;
    }

    boolean targetsDimension(int currentDimension) {
        return hasOffer() && dimension == currentDimension;
    }

    boolean hasCustody() {
        return !physicsRetired && (mode == BoostProtocol.Mode.ACTIVE || mode == BoostProtocol.Mode.CLOSING);
    }

    boolean personalZeroG() {
        return !physicsRetired && requestedIntent && mode == BoostProtocol.Mode.ACTIVE;
    }

    boolean canRequestEnable() {
        return mode == BoostProtocol.Mode.READY && !requestedIntent && !modeRequestPending;
    }

    boolean requestedIntent() {
        return requestedIntent;
    }

    boolean restrictedCorridor() {
        return restrictedCorridor;
    }

    int gear() {
        return gear;
    }

    double ceilingMps() {
        return ceilingMps;
    }

    long epoch() {
        return epoch;
    }

    BoostProtocol.Mode mode() {
        return mode;
    }

    void reset() {
        physicsRetired = false;
        resetApplicationLifetime();
        nonce = NO_NONCE;
        epoch = 0L;
        lastSerial = -1L;
        nextControlSequence = 0L;
        nextFrameSequence = 0L;
        dimension = 0;
        gear = 5;
        ceilingMps = 0.0;
        mode = BoostProtocol.Mode.UNAVAILABLE;
        lastReason = BoostProtocol.Reason.NONE;
        modeRequestPending = false;
        restrictedCorridor = false;
    }

    void retireForPhysics() {
        physicsRetired = true;
        invalidateApplication();
        modeRequestPending = false;
    }

    boolean physicsRetired() { return physicsRetired; }

    private static boolean validGear(int value) {
        return value >= 1 && value <= 9;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x())
                && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
