package wi.unigrav.core.boost;

import java.math.BigDecimal;
import java.util.Objects;

import wi.unigrav.api.math.Vec3;

/**
 * Server-time distance allowance, not a claim about propulsion or velocity provenance.
 *
 * <p>The bucket stores at most two server ticks of distance. Packet count never refills it.
 * The strict {@link #prepare(long, long, Vec3)} / {@link #commit(Admission, Vec3)} pair is
 * retained for callers which must reject an over-budget endpoint. {@link #prepareClipped(long,
 * long, Vec3)} instead reserves a bounded candidate before native movement is entered; its
 * matching {@link #commitNative(Admission, Vec3)} deliberately trusts the MC owner to have
 * validated the one-pass native endpoint.
 */
public final class BoostMovementBudget {
    public static final double NATIVE_POSITION_TOLERANCE = 1.0e-5;

    public enum Failure { NONE, INVALID_INPUT, SEQUENCE, TIME, PENDING, STEP, CREDIT }
    public enum DiagnosticFailure { NONE, PENDING, INVALID_INPUT, TIME, SEQUENCE, STEP, DISTANCE_CREDIT }

    public record Admission(long sequence, Vec3 start, Vec3 destination,
            double distance, Failure failure) {
        public boolean accepted() { return failure == Failure.NONE; }
    }

    /** Ledger reservation only; the caller still proves owner/revision, geometry and every path segment. */
    public record CenterPathAdmission(long sequence, long serverTick, Vec3 transportOrigin,
            Vec3 centerOrigin, double grant, Failure failure) {
        public boolean accepted() { return failure == Failure.NONE; }
    }

    /**
     * Reserves cumulative center travel, not net transport displacement. The owner supplies an
     * actual origin and a verified path upper bound; this ledger does not grant collision authority.
     * A first center origin, or one after native rebase, is confirmed only by successful commit.
     */
    public CenterPathAdmission reserveCenterPath(long sequence, long serverTick,
            Vec3 expectedTransportOrigin, Vec3 centerOrigin, double requestedPathUpper) {
        if (hasPending()) {
            lastDiagnosticFailure = DiagnosticFailure.PENDING;
            return deniedCenter(sequence, serverTick, expectedTransportOrigin, centerOrigin, Failure.PENDING);
        }
        if (!BoostProtocol.finite(expectedTransportOrigin) || !BoostProtocol.finite(centerOrigin)
                || !finitePath(requestedPathUpper) || !position.equals(expectedTransportOrigin)
                || confirmedCenter != null && !confirmedCenter.equals(centerOrigin)) {
            lastDiagnosticFailure = DiagnosticFailure.INVALID_INPUT;
            return deniedCenter(sequence, serverTick, expectedTransportOrigin, centerOrigin, Failure.INVALID_INPUT);
        }
        if (!precheck(sequence, serverTick, expectedTransportOrigin)) {
            return deniedCenter(sequence, serverTick, expectedTransportOrigin, centerOrigin,
                    lastDenied(sequence, expectedTransportOrigin).failure());
        }
        double grant = Math.min(requestedPathUpper, Math.min(proposalPerTickDistance(), distanceCredit));
        pendingCenter = new CenterPathAdmission(sequence, serverTick, position, centerOrigin, grant, Failure.NONE);
        debit(grant);
        lastDiagnosticFailure = DiagnosticFailure.NONE;
        return pendingCenter;
    }

    /**
     * Commits one already-spent path reservation. The supplied upper bound must include all
     * actual segments; checking its net-distance lower bound cannot reconstruct a folded path.
     * Transport may change independently of center travel (for example a proxy after rotation).
     */
    public boolean commitCenterPath(CenterPathAdmission ticket, Vec3 finalCenter,
            Vec3 actualTransport, double verifiedPathUpper) {
        if (ticket == null || pendingCenter != ticket || !ticket.accepted() || ticket.serverTick() != lastTick
                || !BoostProtocol.finite(finalCenter) || !BoostProtocol.finite(actualTransport)
                || !finitePath(verifiedPathUpper) || verifiedPathUpper > ticket.grant()
                || !coversNetCenterDistance(ticket.centerOrigin(), finalCenter, verifiedPathUpper)) return false;
        position = actualTransport;
        confirmedCenter = finalCenter;
        clearPending();
        return true;
    }

    /** Settles the exact pending reservation without refunding its already-spent grant. */
    public void abortCenterPath(CenterPathAdmission ticket) {
        if (ticket != null && pendingCenter == ticket) clearPending();
    }

    private static boolean finitePath(double path) { return Double.isFinite(path) && path >= 0; }

    /** Exact binary64 squares avoid an epsilon or a rounded sqrt becoming a movement permit. */
    private static boolean coversNetCenterDistance(Vec3 from, Vec3 to, double path) {
        BigDecimal x = new BigDecimal(to.x()).subtract(new BigDecimal(from.x()));
        BigDecimal y = new BigDecimal(to.y()).subtract(new BigDecimal(from.y()));
        BigDecimal z = new BigDecimal(to.z()).subtract(new BigDecimal(from.z()));
        BigDecimal distanceSquared = x.multiply(x).add(y.multiply(y)).add(z.multiply(z));
        BigDecimal upper = new BigDecimal(path);
        return upper.multiply(upper).compareTo(distanceSquared) >= 0;
    }

    private static CenterPathAdmission deniedCenter(long sequence, long tick, Vec3 transport,
            Vec3 center, Failure failure) {
        return new CenterPathAdmission(sequence, tick, transport, center, 0, failure);
    }
    private double ceilingMps;
    private double distanceCredit;
    // A single finite allowance for cumulative native/coordinate rounding; it never refills.
    private double roundingCredit = 1.0e-4;
    private long lastTick;
    // The immediately preceding carrier. It is a one-actual-server-tick cap grace for clipping.
    private double proposalCeilingMps;
    private boolean decayActive;
    private double decayFloorMps;
    private double decayDecelerationMps2;
    private long seenSequence;
    private Vec3 position;
    private Admission pending;
    private CenterPathAdmission pendingCenter;
    private Vec3 confirmedCenter;
    private boolean pendingReserved;
    private DiagnosticFailure lastDiagnosticFailure = DiagnosticFailure.NONE;

    public BoostMovementBudget(double ceilingMps, long serverTick, Vec3 acceptedPosition) {
        validateCeiling(ceilingMps);
        if (serverTick < 0 || !BoostProtocol.finite(acceptedPosition)) {
            throw new IllegalArgumentException("Invalid budget origin");
        }
        this.ceilingMps = ceilingMps;
        this.lastTick = serverTick;
        this.position = acceptedPosition;
        this.distanceCredit = perTickDistance();
        this.proposalCeilingMps = ceilingMps;
    }

    /**
     * Prepares an exact endpoint. It rejects a candidate which exceeds either the single-step
     * cap or the currently accumulated distance allowance; no distance is reserved until commit.
     */
    public Admission prepare(long sequence, long serverTick, Vec3 destination) {
        if (!precheck(sequence, serverTick, destination)) {
            return lastDenied(sequence, destination);
        }
        double distance = distanceFromPosition(destination);
        if (!Double.isFinite(distance)) {
            return denied(sequence, destination, Failure.INVALID_INPUT, DiagnosticFailure.INVALID_INPUT);
        }
        if (distance > perTickDistance() + NATIVE_POSITION_TOLERANCE) {
            return denied(sequence, destination, Failure.STEP, DiagnosticFailure.STEP);
        }
        if (distance > distanceCredit + roundingCredit) {
            return denied(sequence, destination, Failure.CREDIT, DiagnosticFailure.DISTANCE_CREDIT);
        }
        pending = new Admission(sequence, position, destination, distance, Failure.NONE);
        pendingReserved = false;
        lastDiagnosticFailure = DiagnosticFailure.NONE;
        return pending;
    }

    /**
     * Prepares a bounded endpoint for a normal, finite proposal. The candidate is capped by one
     * tick's distance, then by the credit already accrued. The one-time rounding reserve is a
     * strict-endpoint tolerance only and never creates clipped displacement; an empty distance
     * bucket produces a stationary candidate.
     * Accepted clipped tickets debit their full candidate distance immediately and are never
     * refunded by {@link #abort(Admission)} or {@link #commitNative(Admission, Vec3)}.
     */
    public Admission prepareClipped(long sequence, long serverTick, Vec3 rawDestination) {
        if (!precheck(sequence, serverTick, rawDestination)) {
            return lastDenied(sequence, rawDestination);
        }
        Vec3 delta = rawDestination.sub(position);
        double requestedDistance = length(delta);
        if (!Double.isFinite(requestedDistance)) {
            return denied(sequence, rawDestination, Failure.INVALID_INPUT, DiagnosticFailure.INVALID_INPUT);
        }
        double candidateDistance = 0.0D;
        Vec3 candidate = position;
        if (requestedDistance > 0.0D && distanceCredit > 0.0D) {
            candidateDistance = Math.min(requestedDistance,
                    Math.min(proposalPerTickDistance(), distanceCredit));
            candidate = position.add(delta.scale(candidateDistance / requestedDistance));
            if (!BoostProtocol.finite(candidate)) {
                return denied(sequence, rawDestination, Failure.INVALID_INPUT,
                        DiagnosticFailure.INVALID_INPUT);
            }
        }
        pending = new Admission(sequence, position, candidate, candidateDistance, Failure.NONE);
        debit(candidateDistance);
        pendingReserved = true;
        lastDiagnosticFailure = DiagnosticFailure.NONE;
        return pending;
    }

    /**
     * Commits an exact-endpoint ticket. This remains strict so existing callers cannot turn a
     * collision-shortened result into an implicit clamp.
     */
    public boolean commit(Admission admission, Vec3 actualPosition) {
        if (admission == null || pending != admission || pendingReserved || !admission.accepted()
                || !BoostProtocol.finite(actualPosition)) return false;
        double error = length(actualPosition.sub(admission.destination()));
        double distance = distanceFromPosition(actualPosition);
        if (!Double.isFinite(error) || error > NATIVE_POSITION_TOLERANCE
                || !Double.isFinite(distance) || distance > distanceCredit + roundingCredit
                || distance > perTickDistance() + NATIVE_POSITION_TOLERANCE) return false;
        debit(distance);
        position = actualPosition;
        confirmedCenter = null;
        clearPending();
        return true;
    }

    /**
     * Commits a previously reserved clipped ticket after the MC owner has validated lifecycle,
     * finiteness, bounds and its sole native-movement result. A native collision may end shorter
     * than the candidate; that does not refund the already reserved distance.
     */
    public boolean commitNative(Admission admission, Vec3 nativePosition) {
        if (admission == null || pending != admission || !pendingReserved || !admission.accepted()
                || !BoostProtocol.finite(nativePosition)) return false;
        position = nativePosition;
        confirmedCenter = null;
        clearPending();
        return true;
    }

    /** Releases a pending ticket. A clipped ticket was already debited and is not refunded. */
    public void abort(Admission admission) {
        if (admission != null && pending == admission) clearPending();
    }

    /**
     * Changes the ceiling at a known server tick. Elapsed credit is first refilled at the old
     * ceiling, then clamped to the new two-tick capacity. Raising a ceiling never mints credit.
     */
    public void changeCeiling(long serverTick, double nextCeilingMps) {
        validateCeiling(nextCeilingMps);
        if (serverTick < lastTick) {
            throw new IllegalArgumentException("Ceiling change cannot move budget time backwards");
        }
        advance(serverTick);
        ceilingMps = nextCeilingMps;
        distanceCredit = Math.min(distanceCredit, storageCapacity());
        proposalCeilingMps = ceilingMps;
        decayActive = false;
    }

    /**
     * Compatibility form for callers without a server tick. It performs no elapsed-time refill.
     * New production callers should use {@link #changeCeiling(long, double)}.
     */
    public void setCeiling(double value) {
        validateCeiling(value);
        ceilingMps = value;
        distanceCredit = Math.min(distanceCredit, storageCapacity());
        proposalCeilingMps = ceilingMps;
        decayActive = false;
    }

    /**
     * Starts a linear carrier decay after advancing all prior elapsed time. Beginning decay does
     * not instantly lower the carrier or credit capacity; the first change occurs on a later
     * actual server tick. A floor above the current carrier is reduced to the current carrier,
     * so this API can never raise speed or mint a higher ceiling.
     */
    public void beginDecay(long serverTick, double floorMps, double decelerationMps2) {
        validateCeiling(floorMps);
        validateDeceleration(decelerationMps2);
        if (serverTick < lastTick) {
            throw new IllegalArgumentException("Decay cannot move budget time backwards");
        }
        advance(serverTick);
        decayFloorMps = Math.min(floorMps, ceilingMps);
        decayDecelerationMps2 = decelerationMps2;
        decayActive = ceilingMps > decayFloorMps;
        proposalCeilingMps = ceilingMps;
    }

    /**
     * Advances the budget to {@code serverTick}. Normal budgets refill at their fixed carrier.
     * A decaying budget evaluates the carrier over every elapsed tick, but stores credit only for
     * the most recent two-tick segment of that same envelope. Thus a long idle gap reaches its
     * real decay end without minting a historical high-speed allowance.
     */
    public void advance(long serverTick) {
        if (serverTick < lastTick) {
            throw new IllegalArgumentException("Budget advance cannot move time backwards");
        }
        long elapsed = serverTick - lastTick;
        if (elapsed == 0L) {
            return;
        }
        if (!decayActive) {
            distanceCredit = Math.min(storageCapacity(),
                    distanceCredit + Math.min(2L, elapsed) * perTickDistance());
            lastTick = serverTick;
            proposalCeilingMps = ceilingMps;
            return;
        }

        double startCeiling = ceilingMps;
        double elapsedSeconds = elapsed / 20.0D;
        double endCeiling = decay(startCeiling, elapsedSeconds).endCeilingMps();
        long creditedTicks = Math.min(2L, elapsed);
        double tailSeconds = creditedTicks / 20.0D;
        double tailStartSeconds = (elapsed - creditedTicks) / 20.0D;
        double tailStartCeiling = decay(startCeiling, tailStartSeconds).endCeilingMps();
        double mintedDistance = decay(tailStartCeiling, tailSeconds).distanceMeters();
        double immediatelyPreviousCeiling = decay(startCeiling, (elapsed - 1L) / 20.0D)
                .endCeilingMps();
        ceilingMps = endCeiling;
        distanceCredit = Math.min(storageCapacity(), distanceCredit + mintedDistance);
        proposalCeilingMps = immediatelyPreviousCeiling;
        lastTick = serverTick;
        // Keep the immediately preceding carrier for this arrival tick, then let the next
        // actual tick return to normal 70 m/s proposal caps.
        decayActive = ceilingMps > decayFloorMps;
    }

    /**
     * Rebases the accepted native origin without granting a fresh tick of distance. Any pending
     * clipped reservation stays spent; only elapsed server time may refill the ordinary bucket.
     */
    public void rebase(Vec3 acceptedPosition, long serverTick) {
        if (serverTick < lastTick || !BoostProtocol.finite(acceptedPosition)) {
            throw new IllegalArgumentException("Invalid budget rebase");
        }
        clearPending();
        advance(serverTick);
        position = acceptedPosition;
        confirmedCenter = null;
    }

    public Vec3 position() { return position; }
    public long lastSequence() { return seenSequence; }
    public double ceilingMps() { return ceilingMps; }
    /** One actual server tick of the preceding decay carrier for clipped proposal caps. */
    public double proposalCeilingMps() { return proposalCeilingMps; }

    /** Read-only diagnostic state; it does not alter admission, refill, or rounding semantics. */
    public Snapshot snapshot() {
        return new Snapshot(ceilingMps, proposalCeilingMps, distanceCredit, roundingCredit,
                lastTick, seenSequence, hasPending(), decayActive, lastDiagnosticFailure);
    }

    public record Snapshot(double ceilingMps, double proposalCeilingMps, double distanceCredit,
            double roundingCredit, long lastTick, long seenSequence, boolean pending, boolean decayActive,
            DiagnosticFailure lastDiagnosticFailure) { }

    /** Conservative finite allowance relative to vanilla's own tick baseline, never the candidate. */
    public double vanillaAllowanceSquared(Vec3 firstGood) {
        Objects.requireNonNull(firstGood, "firstGood");
        if (!BoostProtocol.finite(firstGood)) return Double.NaN;
        double bound = length(position.sub(firstGood))
                + (pendingReserved ? proposalPerTickDistance() : perTickDistance())
                + NATIVE_POSITION_TOLERANCE;
        return bound * bound;
    }

    private boolean precheck(long sequence, long serverTick, Vec3 destination) {
        if (hasPending()) {
            lastDiagnosticFailure = DiagnosticFailure.PENDING;
            return false;
        }
        if (!BoostProtocol.finite(destination)) {
            lastDiagnosticFailure = DiagnosticFailure.INVALID_INPUT;
            return false;
        }
        if (serverTick < lastTick) {
            lastDiagnosticFailure = DiagnosticFailure.TIME;
            return false;
        }
        advance(serverTick);
        if (seenSequence == Long.MAX_VALUE || sequence != seenSequence + 1) {
            lastDiagnosticFailure = DiagnosticFailure.SEQUENCE;
            return false;
        }
        seenSequence = sequence;
        return true;
    }

    private Admission lastDenied(long sequence, Vec3 destination) {
        return switch (lastDiagnosticFailure) {
            case PENDING -> new Admission(sequence, position, destination, 0.0D, Failure.PENDING);
            case INVALID_INPUT -> new Admission(sequence, position, destination, 0.0D, Failure.INVALID_INPUT);
            case TIME -> new Admission(sequence, position, destination, 0.0D, Failure.TIME);
            case SEQUENCE -> new Admission(sequence, position, destination, 0.0D, Failure.SEQUENCE);
            default -> throw new IllegalStateException("Admission precheck has no failure");
        };
    }

    private void debit(double distance) {
        double roundingUsed = Math.max(0.0D, distance - distanceCredit);
        roundingCredit = Math.max(0.0D, roundingCredit - roundingUsed);
        distanceCredit = Math.max(0.0D, distanceCredit - distance);
    }

    private boolean hasPending() { return pending != null || pendingCenter != null; }

    private void clearPending() {
        pending = null;
        pendingCenter = null;
        pendingReserved = false;
    }

    private double distanceFromPosition(Vec3 destination) {
        return length(destination.sub(position));
    }

    private double perTickDistance() { return ceilingMps / 20.0D; }
    private double proposalPerTickDistance() { return proposalCeilingMps / 20.0D; }
    private double storageCapacity() { return 2.0D * perTickDistance(); }

    private RemoteBoostCorridor.Decay decay(double startCeiling, double seconds) {
        return RemoteBoostCorridor.decay(startCeiling, decayFloorMps, decayDecelerationMps2,
                seconds);
    }

    private Admission denied(long sequence, Vec3 destination, Failure failure,
            DiagnosticFailure diagnosticFailure) {
        lastDiagnosticFailure = diagnosticFailure;
        return new Admission(sequence, position, destination, 0.0D, failure);
    }

    private static double length(Vec3 v) { return Math.hypot(Math.hypot(v.x(), v.y()), v.z()); }

    private static void validateCeiling(double value) {
        if (!Double.isFinite(value) || value <= 0.0D || value > 500.0D) {
            throw new IllegalArgumentException("Remote movement ceiling must be in (0, 500] m/s");
        }
    }

    private static void validateDeceleration(double value) {
        if (!Double.isFinite(value) || value <= 0.0D || value > 10_000.0D) {
            throw new IllegalArgumentException("Decay deceleration must be finite and in (0, 10000] m/s^2");
        }
    }
}
