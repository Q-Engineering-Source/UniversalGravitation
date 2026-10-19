package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

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
        return withBootQueryAndSupport(nonBootEvidence, query, nonBootEvidence.actualSupport(), accepted, worldTick, true);
    }

    /**
     * Adds one same-read qualified query and the support independently observed from its raw
     * collision capture. Active pull candidates are admitted only when the owner qualified the
     * actual FEET stack; an ineligible actor can still stand on the same collider as ordinary
     * unqualified support. Source-separated pure-floor score carriers are retained verbatim.
     */
    public static Evidence withBootQueryAndSupport(Evidence nonBootEvidence, BootSurfaceQuery.Result query,
                                                    Optional<Surface> actualSupport,
                                                    PlayerPhysicalSnapshot accepted, long worldTick,
                                                    boolean activeBootQualified) {
        return withBootQueryAndSupport(nonBootEvidence, query, actualSupport, accepted, worldTick,
                activeBootQualified, ignored -> { });
    }

    /** Same adapter with shared owner-tick postprocessing charges for every candidate and identity scan. */
    public static Evidence withBootQueryAndSupport(Evidence nonBootEvidence, BootSurfaceQuery.Result query,
                                                    Optional<Surface> actualSupport,
                                                    PlayerPhysicalSnapshot accepted, long worldTick,
                                                    boolean activeBootQualified, LongConsumer postprocessingWork) {
        return join(nonBootEvidence, query, actualSupport, accepted, worldTick, activeBootQualified,
                postprocessingWork, false);
    }

    /** Only a privately certified endpoint source can satisfy this distinct provenance path. */
    public static Evidence withEndpointBootQueryAndSupport(Evidence nonBootEvidence, BootSurfaceQuery.Result query,
                                                    Optional<Surface> actualSupport,
                                                    PlayerPhysicalSnapshot accepted, long worldTick,
                                                    boolean activeBootQualified, LongConsumer postprocessingWork) {
        return join(nonBootEvidence, query, actualSupport, accepted, worldTick, activeBootQualified,
                postprocessingWork, true);
    }

    private static Evidence join(Evidence nonBootEvidence, BootSurfaceQuery.Result query,
                                 Optional<Surface> actualSupport, PlayerPhysicalSnapshot accepted, long worldTick,
                                 boolean activeBootQualified, LongConsumer postprocessingWork, boolean endpoint) {
        Objects.requireNonNull(nonBootEvidence, "nonBootEvidence");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(actualSupport, "actualSupport");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(postprocessingWork, "postprocessingWork");
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
        if (!(endpoint ? measurements.source().endpointCurrentFor(accepted)
                : measurements.source().isAcceptedSensor() && measurements.source().physical().equals(accepted))
                || capture.snapshot().captureTick() != worldTick
                || actualSupport.flatMap(Surface::identity)
                .filter(support -> !support.scope().equals(accepted.scope())).isPresent()) {
            return Evidence.unavailable();
        }
        var candidates = new ArrayList<BootCandidate>(activeBootQualified ? measurements.candidates().size() : 0);
        if (activeBootQualified) {
            for (var measured : measurements.candidates()) {
                postprocessingWork.accept(1);
                var proximity = measured.proximity();
                var candidate = new BootCandidate(Surface.fromBootIdentity(
                        BootSurfaceIdentity.from(measurements, measured, postprocessingWork)),
                        proximity.gap(), proximity.range(), proximity.pullDirection(),
                        measured.fragment().qualification().effectiveTier());
                if (candidate.hasUnresolvedStrength()) return Evidence.unavailable();
                candidates.add(candidate);
            }
        }
        if (endpoint && !measurements.source().endpointCurrentFor(accepted)) return Evidence.unavailable();
        return new Evidence(nonBootEvidence.availability(), nonBootEvidence.gravityAcceleration(),
                nonBootEvidence.magneticAcceleration(), nonBootEvidence.faceEvidence(), candidates,
                actualSupport, nonBootEvidence.floorGravityStrengths(), nonBootEvidence.floorMagneticStrengths());
    }
    /**
     * Retains only actual native support for an actor that did not qualify active boots. This
     * path cannot add candidates or magnetic identity and preserves the P1 source-separated
     * floor carriers exactly as supplied by the same environment observation.
     */
    public static Evidence withNativeSupport(Evidence nonBootEvidence, Optional<Surface> actualSupport) {
        Objects.requireNonNull(nonBootEvidence, "nonBootEvidence");
        Objects.requireNonNull(actualSupport, "actualSupport");
        if (!nonBootEvidence.bootCandidates().isEmpty()) {
            throw new IllegalArgumentException("native support requires non-boot baseline evidence");
        }
        if (nonBootEvidence.availability() == Availability.UNAVAILABLE
                || actualSupport.flatMap(Surface::identity).isPresent()) {
            return Evidence.unavailable();
        }
        return new Evidence(nonBootEvidence.availability(), nonBootEvidence.gravityAcceleration(),
                nonBootEvidence.magneticAcceleration(), nonBootEvidence.faceEvidence(), java.util.List.of(),
                actualSupport, nonBootEvidence.floorGravityStrengths(), nonBootEvidence.floorMagneticStrengths());
    }
}
