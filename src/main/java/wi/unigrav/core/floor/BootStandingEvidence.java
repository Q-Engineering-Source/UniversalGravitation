package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Objects;

import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.Availability;
import wi.unigrav.core.player.StandingConstraints.BootCandidate;
import wi.unigrav.core.player.StandingConstraints.Evidence;
import wi.unigrav.core.player.StandingConstraints.Surface;

/**
 * Joins a same-read finite boot query to the standing resolver's non-boot evidence.
 * The caller owns actual equipment/magnetic authorization and must have supplied the complete
 * active tier domain to the query. The non-boot baseline (including ordinary nonmagnetic
 * support) must be authoritative, current, and owned by the same player/world observation.
 * Its bare force and cell-face values carry no provenance for this adapter to revalidate;
 * that responsibility remains with the baseline sampler. A qualified support identity is
 * additionally checked against the accepted scope, and an unqualified support value never
 * equals a qualified boot target. This adapter never samples equipment, creates support,
 * chooses a physical shape, modifies an entity, or applies a force.
 */
public final class BootStandingEvidence {
    private BootStandingEvidence() {
    }

    /**
     * Binds to the owner's current accepted snapshot and observed world total tick, not to
     * a requested view/pose or a packet-defined tick. Both must still be current when consumed.
     * An unavailable baseline or unknown/stale boot query invalidates the whole evidence;
     * it cannot become an empty boot list. This does not certify the baseline caller's provenance.
     */
    public static Evidence withBootQuery(Evidence nonBootEvidence, BootSurfaceQuery.Result query,
                                         PlayerPhysicalSnapshot accepted, long worldTick) {
        Objects.requireNonNull(nonBootEvidence, "nonBootEvidence");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(accepted, "accepted");
        if (worldTick < 0) throw new IllegalArgumentException("worldTick must be nonnegative");
        if (!nonBootEvidence.bootCandidates().isEmpty()) {
            throw new IllegalArgumentException("boot query requires non-boot baseline evidence, not an accumulator");
        }
        if (nonBootEvidence.availability() == Availability.UNAVAILABLE
                || query instanceof BootSurfaceQuery.Unavailable) {
            return Evidence.unavailable();
        }
        var measurements = (BootSurfaceQuery.Measurements) query;
        var capture = measurements.qualifiedCapture().orElseThrow(
                () -> new IllegalArgumentException("standing boot evidence requires same-read qualified capture"));
        if (!measurements.source().physical().equals(accepted) || capture.snapshot().captureTick() != worldTick
                || nonBootEvidence.actualSupport().flatMap(Surface::identity)
                .filter(support -> !support.scope().equals(accepted.scope())).isPresent()) {
            return Evidence.unavailable();
        }
        var candidates = new ArrayList<BootCandidate>(measurements.candidates().size());
        for (var measured : measurements.candidates()) {
            var proximity = measured.proximity();
            var candidate = new BootCandidate(Surface.fromBootIdentity(BootSurfaceIdentity.from(measurements, measured)),
                    proximity.gap(), proximity.range(), proximity.pullDirection(),
                    measured.fragment().qualification().effectiveTier());
            if (candidate.hasUnresolvedStrength()) return Evidence.unavailable();
            candidates.add(candidate);
        }
        return new Evidence(nonBootEvidence.availability(), nonBootEvidence.gravityAcceleration(),
                nonBootEvidence.magneticAcceleration(), nonBootEvidence.faceEvidence(), candidates,
                nonBootEvidence.actualSupport());
    }
}
