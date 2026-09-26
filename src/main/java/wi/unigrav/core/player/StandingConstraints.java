package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.BootSurfaceIdentity;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;
import wi.unigrav.core.floor.FloorFieldProfile;

/**
 * Pure, server-tick driven selection of a cardinal standing reference.
 *
 * <p>This is deliberately neither a field sampler nor a player-motion owner.
 * The sampler supplies the unprojected, already-summed force and the distinct
 * pre-sum face scores that make a cardinal reference authoritative. In
 * particular, {@code FloorFieldIndex.Sample} is not a valid substitute: it
 * does not contain those scores. {@link Evidence#withFloorSample} consumes the
 * richer, single-traversal {@link StandingSample}. A caller must separately perform geometry
 * admission before changing a boot phase to {@link BootPhase#ATTACHED}; this
 * resolver never turns an acquisition into contact or a capsule permission.
 */
public final class StandingConstraints {
    /** The user-confirmed default capture reach in metres; tiers remain caller-supplied. */
    public static final double DEFAULT_BOOT_CAPTURE_REACH_METRES = 1.5;

    private static final long NO_PROTECTION_DEADLINE = -1L;

    private StandingConstraints() {
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum ConstraintPresence {
        PRESENT,
        ABSENT,
        UNAVAILABLE
    }

    /**
     * A boot phase is descriptive state, not a collision, fit, or on-ground proof.
     * Jump protection prevents reattachment, not the current in-range attraction.
     */
    public enum BootPhase {
        NONE,
        IN_RANGE_UNATTACHED,
        ACQUIRING,
        ATTACHED,
        JUMP_RELEASE_PROTECTED,
        OUT_OF_RANGE
    }

    /** Provenance is explicit so background/natural evidence uses the same face-candidate contract. */
    public enum ConstraintSource {
        FLOOR,
        MAGNETIC,
        NATURAL_BACKGROUND,
        MIXED
    }

    /** A surface value, not support authority. Qualified boot targets retain their full identity. */
    public record Surface(int x, int y, int z, Face outwardFace, Optional<BootSurfaceIdentity> identity) {
        public Surface {
            Objects.requireNonNull(outwardFace, "outwardFace");
            Objects.requireNonNull(identity, "identity");
            identity.ifPresent(target -> {
                var cell = target.primaryContributorCell();
                if (cell.getX() != x || cell.getY() != y || cell.getZ() != z
                        || target.face().outward() != CardinalFrame.fromFloorFace(outwardFace)) {
                    throw new IllegalArgumentException("surface anchor/frame must match its complete boot identity");
                }
            });
        }

        /** Unqualified/manual cell-face value; it never equals a qualified boot target. */
        public Surface(int x, int y, int z, Face outwardFace) {
            this(x, y, z, outwardFace, Optional.empty());
        }

        public static Surface fromBootIdentity(BootSurfaceIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            var cell = identity.primaryContributorCell();
            Face face = switch (identity.face().outward()) {
                case Y_POS -> Face.UP;
                case Y_NEG -> Face.DOWN;
                case X_POS -> Face.EAST;
                case X_NEG -> Face.WEST;
                case Z_POS -> Face.SOUTH;
                case Z_NEG -> Face.NORTH;
            };
            return new Surface(cell.getX(), cell.getY(), cell.getZ(), face, Optional.of(identity));
        }

        public CardinalFrame frame() {
            return CardinalFrame.fromFloorFace(outwardFace);
        }
    }

    /** One source-qualified cardinal candidate and its pre-vector-sum reference score. */
    public record FaceEvidence(double preSumReferenceScore, Set<ConstraintSource> sources) {
        public FaceEvidence {
            requireFiniteNonnegative(preSumReferenceScore, "preSumReferenceScore");
            Objects.requireNonNull(sources, "sources");
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("sources must not be empty");
            }
            EnumSet<ConstraintSource> copy = EnumSet.noneOf(ConstraintSource.class);
            for (ConstraintSource source : sources) {
                copy.add(Objects.requireNonNull(source, "source"));
            }
            sources = Collections.unmodifiableSet(copy);
        }

        public boolean isEffective() {
            return preSumReferenceScore > 0.0;
        }
    }

    /** Tier parameters are explicit engineering/configuration inputs, not inferred product defaults. */
    public record BootTier(FloorFieldProfile profile, double captureReachMetres, long jumpProtectionTicks) {
        public BootTier {
            Objects.requireNonNull(profile, "profile");
            if (!Double.isFinite(captureReachMetres) || captureReachMetres <= 0.0) {
                throw new IllegalArgumentException("captureReachMetres must be finite and positive");
            }
            if (jumpProtectionTicks <= 0L) {
                throw new IllegalArgumentException("jumpProtectionTicks must be positive");
            }
        }

        public static BootTier withDefaultCaptureReach(FloorFieldProfile profile, long jumpProtectionTicks) {
            return new BootTier(profile, DEFAULT_BOOT_CAPTURE_REACH_METRES, jumpProtectionTicks);
        }
    }

    /**
     * Sampler input retaining finite-gap bounds and the independently resolved capture range.
     * Direction is the measured closest-point direction, not necessarily the face normal.
     * These values do not establish actor/equipment authority or physical support.
     */
    public record BootCandidate(Surface surface, BootSurfaceGeometry.Gap gap,
                                BootSurfaceGeometry.Range captureRange, Vec3d pullDirection, BootTier tier) {
        public BootCandidate {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(gap, "gap");
            Objects.requireNonNull(captureRange, "captureRange");
            Objects.requireNonNull(pullDirection, "pullDirection");
            Objects.requireNonNull(tier, "tier");
            double norm = Math.hypot(Math.hypot(pullDirection.x(), pullDirection.y()), pullDirection.z());
            if (!Double.isFinite(norm) || Math.abs(norm - 1.0) > 1.0e-12) {
                throw new IllegalArgumentException("pullDirection must be a unit-vector approximation");
            }
            if (captureRange == BootSurfaceGeometry.Range.IN_RANGE && gap.lowerMetres() > tier.captureReachMetres()
                    || captureRange == BootSurfaceGeometry.Range.OUT_OF_RANGE && gap.upperMetres() <= tier.captureReachMetres()) {
                throw new IllegalArgumentException("capture range contradicts the enclosing gap interval");
            }
            surface.identity().ifPresent(identity -> {
                if (!identity.qualification().effectiveTier().equals(tier)) {
                    throw new IllegalArgumentException("candidate tier must match its complete surface identity");
                }
            });
        }

        /** Exact scalar observation retained for manual/unqualified callers, with inward normal pull. */
        public BootCandidate(Surface surface, double actualGapMetres, BootTier tier) {
            this(surface, new BootSurfaceGeometry.Gap(actualGapMetres, actualGapMetres),
                    actualGapMetres <= tier.captureReachMetres() ? BootSurfaceGeometry.Range.IN_RANGE
                            : BootSurfaceGeometry.Range.OUT_OF_RANGE,
                    surface.frame().up().multiply(-1), tier);
            if (surface.identity().isPresent()) {
                throw new IllegalArgumentException("qualified boot targets require their finite proximity observation");
            }
        }

        /** A gap straddling the exclusive falloff cutoff cannot prove presence or absence of attraction. */
        public boolean hasUnresolvedStrength() {
            return captureRange == BootSurfaceGeometry.Range.IN_RANGE && tier.profile().strength() > 0.0
                    && gap.lowerMetres() < tier.profile().range() && gap.upperMetres() >= tier.profile().range();
        }

        /**
         * Proven nonzero attraction only. A false result is not absence when
         * {@link #hasUnresolvedStrength()} is true; that case invalidates the observation.
         */
        public boolean isEffective() {
            return captureRange == BootSurfaceGeometry.Range.IN_RANGE && !hasUnresolvedStrength()
                    && tier.profile().strengthAt(gap.upperMetres()) > 0.0;
        }

        /** Monotone falloff evaluated at the upper gap bound; the original interval is not discarded. */
        public double pullMagnitude() {
            if (hasUnresolvedStrength()) throw new IllegalStateException("boot falloff cutoff is unresolved");
            return isEffective() ? tier.profile().strengthAt(gap.upperMetres()) : 0.0;
        }
    }

    /** Last selected real surface and its tier parameters, retained while an out-of-range boot becomes stale. */
    public record BootMemory(Surface surface, BootTier tier) {
        public BootMemory {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(tier, "tier");
            surface.identity().ifPresent(identity -> {
                if (!identity.qualification().effectiveTier().equals(tier)) {
                    throw new IllegalArgumentException("remembered tier must match its complete surface identity");
                }
            });
        }
    }

    /**
     * Input from the authoritative sampler. Gravity and magnetic values are
     * field-only complete world-space accelerations; boot adhesion is derived
     * independently below and none of these vectors is projected onto a frame.
     */
    public record Evidence(Availability availability, Vec3 gravityAcceleration, Vec3 magneticAcceleration,
                           Map<CardinalFrame, FaceEvidence> faceEvidence,
                           List<BootCandidate> bootCandidates, Optional<Surface> actualSupport) {
        public Evidence {
            Objects.requireNonNull(availability, "availability");
            requireFiniteVector(gravityAcceleration, "gravityAcceleration");
            requireFiniteVector(magneticAcceleration, "magneticAcceleration");
            Objects.requireNonNull(faceEvidence, "faceEvidence");
            Objects.requireNonNull(bootCandidates, "bootCandidates");
            Objects.requireNonNull(actualSupport, "actualSupport");

            EnumMap<CardinalFrame, FaceEvidence> faces = new EnumMap<>(CardinalFrame.class);
            for (Map.Entry<CardinalFrame, FaceEvidence> entry : faceEvidence.entrySet()) {
                faces.put(Objects.requireNonNull(entry.getKey(), "faceEvidence key"),
                        Objects.requireNonNull(entry.getValue(), "faceEvidence value"));
            }
            List<BootCandidate> candidates = new ArrayList<>(bootCandidates.size());
            Set<Surface> distinctSurfaces = new HashSet<>();
            for (BootCandidate candidate : bootCandidates) {
                BootCandidate nonNullCandidate = Objects.requireNonNull(candidate, "bootCandidate");
                if (!distinctSurfaces.add(nonNullCandidate.surface())) {
                    throw new IllegalArgumentException("bootCandidates must not duplicate a surface");
                }
                candidates.add(nonNullCandidate);
            }
            if (availability == Availability.UNAVAILABLE
                    && (!gravityAcceleration.equals(Vec3.ZERO) || !magneticAcceleration.equals(Vec3.ZERO)
                    || !faces.isEmpty() || !candidates.isEmpty() || actualSupport.isPresent())) {
                throw new IllegalArgumentException("unavailable evidence must not carry authoritative observations");
            }
            faceEvidence = Collections.unmodifiableMap(faces);
            bootCandidates = List.copyOf(candidates);
        }

        public static Evidence unavailable() {
            return new Evidence(Availability.UNAVAILABLE, Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(), Optional.empty());
        }

        /**
         * Adds one floor observation to explicitly supplied non-floor evidence.
         * The caller supplies already-qualified background, boots and support,
         * and decides whether floor gravity applies. Magnetic response is already
         * applied by the index and must not be applied again here. Call once per
         * non-floor baseline; this is not an incremental accumulator or an actor
         * authorization decision. Existing FLOOR/MAGNETIC field provenance is
         * rejected; boot candidates are separate and may remain in the baseline.
         * Missing data invalidates the whole observation.
         */
        public Evidence withFloorSample(Optional<StandingSample> floor, boolean gravityApplies) {
            Objects.requireNonNull(floor, "floor");
            if (availability == Availability.UNAVAILABLE || floor.isEmpty()) {
                return unavailable();
            }
            for (FaceEvidence face : faceEvidence.values()) {
                if (face.sources().contains(ConstraintSource.FLOOR) || face.sources().contains(ConstraintSource.MAGNETIC)) {
                    throw new IllegalArgumentException("floor sampling requires non-floor baseline evidence");
                }
            }
            StandingSample sample = floor.orElseThrow();
            EnumMap<CardinalFrame, FaceEvidence> faces = new EnumMap<>(CardinalFrame.class);
            faces.putAll(faceEvidence);
            if (gravityApplies) {
                mergeFloorStrengths(faces, sample.gravityStrengths(), ConstraintSource.FLOOR);
            }
            mergeFloorStrengths(faces, sample.magneticStrengths(), ConstraintSource.MAGNETIC);
            return new Evidence(Availability.AVAILABLE,
                    gravityApplies ? gravityAcceleration.add(sample.forces().gravityAccel()) : gravityAcceleration,
                    magneticAcceleration.add(sample.forces().magneticAccel()), faces, bootCandidates, actualSupport);
        }

        private static void mergeFloorStrengths(EnumMap<CardinalFrame, FaceEvidence> faces,
                                                Map<Face, Double> strengths, ConstraintSource source) {
            for (Map.Entry<Face, Double> entry : strengths.entrySet()) {
                CardinalFrame frame = CardinalFrame.fromFloorFace(entry.getKey());
                FaceEvidence existing = faces.get(frame);
                double score = entry.getValue();
                EnumSet<ConstraintSource> sources = EnumSet.of(source);
                if (existing != null) {
                    score += existing.preSumReferenceScore();
                    sources.addAll(existing.sources());
                }
                // FaceEvidence rejects non-finite sums; no silent saturation.
                faces.put(frame, new FaceEvidence(score, sources));
            }
        }
    }

    /**
     * Resolver memory. {@code actualSupport} is a separately sampled fact; it
     * is not inferred from a target frame, range, phase, or historical boot.
     */
    public record State(BootPhase bootPhase, Optional<BootMemory> bootMemory,
                        Optional<CardinalFrame> acceptedReference, Optional<Surface> actualSupport,
                        long protectionUntilServerTick) {
        public State {
            Objects.requireNonNull(bootPhase, "bootPhase");
            Objects.requireNonNull(bootMemory, "bootMemory");
            Objects.requireNonNull(acceptedReference, "acceptedReference");
            Objects.requireNonNull(actualSupport, "actualSupport");
            if (protectionUntilServerTick < NO_PROTECTION_DEADLINE) {
                throw new IllegalArgumentException("protectionUntilServerTick must be at least -1");
            }
            if ((bootPhase == BootPhase.ATTACHED || bootPhase == BootPhase.JUMP_RELEASE_PROTECTED
                    || bootPhase == BootPhase.IN_RANGE_UNATTACHED || bootPhase == BootPhase.ACQUIRING)
                    && bootMemory.isEmpty()) {
                throw new IllegalArgumentException("active boot phase requires bootMemory");
            }
            if (bootPhase == BootPhase.JUMP_RELEASE_PROTECTED && protectionUntilServerTick < 0L) {
                throw new IllegalArgumentException("protected boot phase requires a deadline");
            }
            if (bootPhase != BootPhase.JUMP_RELEASE_PROTECTED && protectionUntilServerTick != NO_PROTECTION_DEADLINE) {
                throw new IllegalArgumentException("only protected boot phase may retain a deadline");
            }
        }

        public static State initial() {
            return new State(BootPhase.NONE, Optional.empty(), Optional.empty(), Optional.empty(), NO_PROTECTION_DEADLINE);
        }

        /** Use only after the movement/fit owner has independently accepted an attached cardinal placement. */
        public static State attached(BootMemory memory, CardinalFrame acceptedReference, Surface actualSupport) {
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(acceptedReference, "acceptedReference");
            Objects.requireNonNull(actualSupport, "actualSupport");
            if (!memory.surface().equals(actualSupport)) {
                throw new IllegalArgumentException("attached support must match the selected boot surface");
            }
            if (memory.surface().frame() != acceptedReference) {
                throw new IllegalArgumentException("attached reference must match the selected boot surface frame");
            }
            return new State(BootPhase.ATTACHED, Optional.of(memory), Optional.of(acceptedReference),
                    Optional.of(actualSupport), NO_PROTECTION_DEADLINE);
        }
    }

    /** Server-tick input; no packet-defined time or wall clock is accepted. */
    public record Request(long serverTick, boolean jumpEdge, Evidence evidence) {
        public Request {
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must be nonnegative");
            }
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * Optional authoritative force observation. Gravity and magnetic values are
     * supplied field-only sampler sums; boot adhesion is separate so it cannot
     * be mistaken for magnetic-floor sampling or projected onto a target frame.
     */
    public record ForceSample(Vec3 gravityAcceleration, Vec3 magneticAcceleration, Vec3 bootAcceleration) {
        public ForceSample {
            requireFiniteVector(gravityAcceleration, "gravityAcceleration");
            requireFiniteVector(magneticAcceleration, "magneticAcceleration");
            requireFiniteVector(bootAcceleration, "bootAcceleration");
        }

        public Vec3 combinedAcceleration() {
            Vec3 combined = gravityAcceleration.add(magneticAcceleration).add(bootAcceleration);
            requireFiniteVector(combined, "combinedAcceleration");
            return combined;
        }
    }

    /**
     * The resolver result requests a cardinal reference, or none. It does not
     * authorize a capsule, assert a safe shape transition, or claim contact.
     */
    public record Resolution(ConstraintPresence presence, Optional<CardinalFrame> targetReference,
                             Optional<BootMemory> bootMemory, Optional<Surface> actualSupport,
                             State nextState, Optional<ForceSample> authoritativeForce) {
        public Resolution {
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(targetReference, "targetReference");
            Objects.requireNonNull(bootMemory, "bootMemory");
            Objects.requireNonNull(actualSupport, "actualSupport");
            Objects.requireNonNull(nextState, "nextState");
            Objects.requireNonNull(authoritativeForce, "authoritativeForce");
            if (presence == ConstraintPresence.PRESENT && targetReference.isEmpty()) {
                throw new IllegalArgumentException("present constraints require a target reference");
            }
            if (presence != ConstraintPresence.PRESENT && targetReference.isPresent()) {
                throw new IllegalArgumentException("only present constraints may request a target reference");
            }
            if (!bootMemory.equals(nextState.bootMemory()) || !actualSupport.equals(nextState.actualSupport())) {
                throw new IllegalArgumentException("result memory and support must mirror nextState");
            }
        }
    }

    /**
     * Resolves only standing-reference/boot intent from a server-tick request.
     * It cannot accept a shape, prove fit/contact, mutate an entity, or sample a world.
     */
    public static Resolution resolve(State previous, Request request) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(request, "request");
        Evidence evidence = request.evidence();
        if (evidence.availability() == Availability.UNAVAILABLE
                || evidence.bootCandidates().stream().anyMatch(BootCandidate::hasUnresolvedStrength)) {
            State stale = new State(previous.bootPhase(), previous.bootMemory(), previous.acceptedReference(),
                    Optional.empty(), previous.protectionUntilServerTick());
            return new Resolution(ConstraintPresence.UNAVAILABLE, Optional.empty(), stale.bootMemory(),
                    stale.actualSupport(), stale, Optional.empty());
        }

        List<BootCandidate> effectiveBoots = evidence.bootCandidates().stream().filter(BootCandidate::isEffective).toList();
        Optional<BootCandidate> rememberedCandidate = previous.bootMemory()
                .flatMap(memory -> findBySurface(effectiveBoots, memory.surface()));
        boolean protectionActive = previous.bootPhase() == BootPhase.JUMP_RELEASE_PROTECTED
                && request.serverTick() < previous.protectionUntilServerTick();
        boolean supportConfirmsAttachedBoot = previous.bootPhase() == BootPhase.ATTACHED && rememberedCandidate.isPresent()
                && previous.actualSupport().equals(previous.bootMemory().map(BootMemory::surface))
                && previous.acceptedReference().equals(previous.bootMemory().map(memory -> memory.surface().frame()))
                && evidence.actualSupport().equals(previous.bootMemory().map(BootMemory::surface));
        Selection selection = select(evidence, effectiveBoots, rememberedCandidate, previous.acceptedReference());
        // Reattachment protection must not remove the field during a jump. Only the
        // current effective candidate determines pull, never a stored gap or phase.
        Vec3 bootAcceleration = selection.pullBoot().map(StandingConstraints::bootAcceleration).orElse(Vec3.ZERO);

        if (protectionActive) {
            State next = new State(BootPhase.JUMP_RELEASE_PROTECTED, previous.bootMemory(), previous.acceptedReference(),
                    evidence.actualSupport(), previous.protectionUntilServerTick());
            return resolution(selection.target(), next, evidence, bootAcceleration);
        }

        Optional<BootCandidate> selectedBoot = selection.referenceBoot();
        Optional<CardinalFrame> target = selection.target();

        Optional<BootMemory> memory = selectedBoot.map(candidate -> new BootMemory(candidate.surface(), candidate.tier()))
                .or(() -> previous.bootMemory());
        BootPhase phase;
        long deadline = NO_PROTECTION_DEADLINE;
        if (supportConfirmsAttachedBoot && request.jumpEdge()) {
            BootCandidate attached = rememberedCandidate.orElseThrow();
            phase = BootPhase.JUMP_RELEASE_PROTECTED;
            deadline = protectionDeadline(request.serverTick(), attached.tier().jumpProtectionTicks());
            target = Optional.of(attached.surface().frame());
            memory = Optional.of(new BootMemory(attached.surface(), attached.tier()));
        } else if (supportConfirmsAttachedBoot) {
            phase = BootPhase.ATTACHED;
            memory = Optional.of(new BootMemory(rememberedCandidate.orElseThrow().surface(), rememberedCandidate.orElseThrow().tier()));
            target = Optional.of(rememberedCandidate.orElseThrow().surface().frame());
        } else if (selectedBoot.isPresent()) {
            phase = target.equals(previous.acceptedReference())
                    ? BootPhase.IN_RANGE_UNATTACHED : BootPhase.ACQUIRING;
        } else if (previous.bootMemory().isPresent()) {
            phase = BootPhase.OUT_OF_RANGE;
        } else {
            phase = BootPhase.NONE;
        }

        State next = new State(phase, memory, previous.acceptedReference(), evidence.actualSupport(), deadline);
        return resolution(target, next, evidence, bootAcceleration);
    }

    /** Reference selection and boot-force selection are intentionally independent. */
    private record Selection(Optional<BootCandidate> referenceBoot, Optional<BootCandidate> pullBoot,
                             Optional<CardinalFrame> target) {
    }

    private static Selection select(Evidence evidence, List<BootCandidate> effectiveBoots,
                                    Optional<BootCandidate> rememberedCandidate,
                                    Optional<CardinalFrame> acceptedReference) {
        Optional<BootCandidate> selectedBoot = rememberedCandidate;
        Optional<CardinalFrame> target = selectedBoot.map(candidate -> candidate.surface().frame());
        if (target.isEmpty()) {
            target = acceptedReference.filter(frame -> isEligibleFrame(evidence, effectiveBoots, frame));
        }
        if (target.isEmpty()) {
            target = evidence.actualSupport().map(Surface::frame).filter(frame -> isEligibleFrame(evidence, effectiveBoots, frame));
        }
        if (target.isEmpty()) {
            target = highestScoringFrame(evidence);
        }
        if (target.isEmpty()) {
            selectedBoot = preferredBoot(effectiveBoots, evidence.actualSupport());
            target = selectedBoot.map(candidate -> candidate.surface().frame());
        } else if (selectedBoot.isEmpty()) {
            selectedBoot = preferredBootFor(target.orElseThrow(), effectiveBoots, evidence.actualSupport());
        }
        Optional<BootCandidate> pullBoot = rememberedCandidate
                .or(() -> preferredBoot(effectiveBoots, evidence.actualSupport()));
        return new Selection(selectedBoot, pullBoot, target);
    }

    private static Resolution resolution(Optional<CardinalFrame> target, State next, Evidence evidence,
                                         Vec3 bootAcceleration) {
        ConstraintPresence presence = target.isPresent() ? ConstraintPresence.PRESENT : ConstraintPresence.ABSENT;
        return new Resolution(presence, target, next.bootMemory(), next.actualSupport(), next,
                Optional.of(new ForceSample(evidence.gravityAcceleration(), evidence.magneticAcceleration(), bootAcceleration)));
    }

    private static Vec3 bootAcceleration(BootCandidate candidate) {
        Vec3d direction = candidate.pullDirection();
        double magnitude = candidate.pullMagnitude();
        return new Vec3(direction.x() * magnitude, direction.y() * magnitude, direction.z() * magnitude);
    }

    private static Optional<BootCandidate> findBySurface(List<BootCandidate> candidates, Surface surface) {
        return candidates.stream().filter(candidate -> candidate.surface().equals(surface)).findFirst();
    }

    private static boolean hasEffectiveFace(Evidence evidence, CardinalFrame frame) {
        FaceEvidence face = evidence.faceEvidence().get(frame);
        return face != null && face.isEffective();
    }

    private static boolean isEligibleFrame(Evidence evidence, List<BootCandidate> boots, CardinalFrame frame) {
        return hasEffectiveFace(evidence, frame)
                || boots.stream().anyMatch(candidate -> candidate.surface().frame() == frame);
    }

    private static Optional<CardinalFrame> highestScoringFrame(Evidence evidence) {
        CardinalFrame winner = null;
        double score = 0.0;
        for (CardinalFrame frame : CardinalFrame.values()) {
            FaceEvidence candidate = evidence.faceEvidence().get(frame);
            if (candidate != null && candidate.isEffective() && (winner == null || candidate.preSumReferenceScore() > score)) {
                winner = frame;
                score = candidate.preSumReferenceScore();
            }
        }
        return Optional.ofNullable(winner);
    }

    private static Optional<BootCandidate> preferredBootFor(CardinalFrame frame, List<BootCandidate> boots,
                                                              Optional<Surface> actualSupport) {
        return boots.stream().filter(candidate -> candidate.surface().frame() == frame)
                .sorted((left, right) -> compareBoots(left, right, actualSupport)).findFirst();
    }

    private static Optional<BootCandidate> preferredBoot(List<BootCandidate> boots, Optional<Surface> actualSupport) {
        return boots.stream().sorted((left, right) -> compareBoots(left, right, actualSupport)).findFirst();
    }

    private static int compareBoots(BootCandidate left, BootCandidate right, Optional<Surface> actualSupport) {
        boolean leftSupported = actualSupport.filter(left.surface()::equals).isPresent();
        boolean rightSupported = actualSupport.filter(right.surface()::equals).isPresent();
        if (leftSupported != rightSupported) {
            return leftSupported ? -1 : 1;
        }
        int pull = Double.compare(right.pullMagnitude(), left.pullMagnitude());
        return pull != 0 ? pull : compareSurfaces(left.surface(), right.surface());
    }

    private static int compareSurfaces(Surface left, Surface right) {
        int x = Integer.compare(left.x(), right.x());
        if (x != 0) return x;
        int y = Integer.compare(left.y(), right.y());
        if (y != 0) return y;
        int z = Integer.compare(left.z(), right.z());
        if (z != 0) return z;
        int face = left.outwardFace().compareTo(right.outwardFace());
        if (face != 0) return face;
        if (left.identity().isEmpty()) return right.identity().isEmpty() ? 0 : -1;
        if (right.identity().isEmpty()) return 1;
        return left.identity().orElseThrow().compareTo(right.identity().orElseThrow());
    }

    private static long protectionDeadline(long serverTick, long duration) {
        return duration > Long.MAX_VALUE - serverTick ? Long.MAX_VALUE : serverTick + duration;
    }

    private static void requireFiniteVector(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteNonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }
}
