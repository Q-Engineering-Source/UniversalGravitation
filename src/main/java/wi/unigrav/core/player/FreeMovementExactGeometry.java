package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact rational geometry built from stored binary64 inputs, charged to the owner tick ledger. */
final class FreeMovementExactGeometry {
    private FreeMovementExactGeometry() { }

    static final class Arithmetic {
        private static final long MAX_FINITE_BITS = 0x7fefffffffffffffL;
        private final BootQueryArithmetic dyadic;
        private final StoredDyadic.Work work;
        private final Runnable current;
        private final BootQueryArithmetic.Number one;
        private final Rational zero;

        Arithmetic(StoredDyadic.Work work, Runnable current) {
            this.work = Objects.requireNonNull(work);
            this.current = Objects.requireNonNull(current);
            dyadic = BootQueryArithmetic.fromSameWork(work, this.current);
            one = dyadic.exact(1);
            zero = new Rational(this, dyadic.fraction(dyadic.exact(0), one));
        }

        Rational exact(double value) { return new Rational(this, dyadic.fraction(dyadic.exact(value), one)); }
        StoredDyadic.Work workIdentity() { return work; }
        Rational ratio(double numerator, double denominator) {
            return new Rational(this, dyadic.fraction(dyadic.exact(numerator), dyadic.exact(denominator)));
        }
        Rational fromNumbers(BootQueryArithmetic.Number numerator, BootQueryArithmetic.Number denominator) {
            return new Rational(this, dyadic.fractionReduced(numerator, denominator));
        }
        Rational add(Rational a, Rational b) {
            require(a); require(b);
            var numerator = dyadic.add(dyadic.multiply(a.value.numerator(), b.value.denominator()),
                    dyadic.multiply(b.value.numerator(), a.value.denominator()));
            return fromNumbers(numerator, dyadic.multiply(a.value.denominator(), b.value.denominator()));
        }
        Rational subtract(Rational a, Rational b) { return add(a, negate(b)); }
        Rational negate(Rational a) { require(a); return fromNumbers(dyadic.negate(a.value.numerator()), a.value.denominator()); }
        Rational multiply(Rational a, Rational b) {
            require(a); require(b);
            return fromNumbers(dyadic.multiply(a.value.numerator(), b.value.numerator()),
                    dyadic.multiply(a.value.denominator(), b.value.denominator()));
        }
        Rational divide(Rational a, Rational b) {
            require(a); require(b);
            if (sign(b) == 0) throw new IllegalArgumentException("exact rational division by zero");
            return fromNumbers(dyadic.multiply(a.value.numerator(), b.value.denominator()),
                    dyadic.multiply(a.value.denominator(), b.value.numerator()));
        }
        Rational square(Rational value) { return multiply(value, value); }
        int sign(Rational value) { require(value); return dyadic.sign(value.value.numerator()); }
        int compare(Rational a, Rational b) {
            require(a); require(b);
            return dyadic.compareFractions(a.value, b.value);
        }
        Rational min(Rational a, Rational b) { return compare(a, b) <= 0 ? a : b; }
        Rational max(Rational a, Rational b) { return compare(a, b) >= 0 ? a : b; }
        Rational clamp(Rational value, Rational low, Rational high) {
            if (compare(value, low) < 0) return low;
            return compare(value, high) > 0 ? high : value;
        }
        double nearest(Rational value) { require(value); return dyadic.roundNearest(value.value); }
        double floor(Rational value) {
            require(value);
            double nearest = dyadic.roundNearest(value.value);
            int side = compare(value, exact(nearest));
            if (side == 0 || side > 0) return nearest;
            double floor = Math.nextDown(nearest);
            if (!Double.isFinite(floor) || compare(value, exact(floor)) < 0)
                throw new IllegalStateException("exact rational floor is not enclosed by the adjacent binary64 value");
            return floor;
        }
        double ceil(Rational value) {
            require(value);
            double nearest = dyadic.roundNearest(value.value);
            int side = compare(value, exact(nearest));
            if (side == 0 || side < 0) return nearest;
            double ceil = Math.nextUp(nearest);
            if (!Double.isFinite(ceil) || compare(value, exact(ceil)) > 0)
                throw new IllegalStateException("exact rational ceiling is not enclosed by the adjacent binary64 value");
            return ceil;
        }
        double sqrtCeil(Rational squaredMagnitude) {
            require(squaredMagnitude);
            if (sign(squaredMagnitude) < 0) throw new IllegalArgumentException("negative exact squared length");
            if (sign(squaredMagnitude) == 0) return 0;
            long low = 0, high = MAX_FINITE_BITS;
            while (low < high) {
                long middle = low + (high - low) / 2 + 1;
                Rational candidate = exact(Double.longBitsToDouble(middle));
                if (compare(square(candidate), squaredMagnitude) <= 0) low = middle;
                else high = middle - 1;
            }
            double floor = Double.longBitsToDouble(low);
            if (compare(square(exact(floor)), squaredMagnitude) == 0) return floor;
            double ceil = Math.nextUp(floor);
            if (!Double.isFinite(ceil)) throw new IllegalArgumentException("exact square root exceeds finite binary64");
            return ceil;
        }
        Rational sqrtCeilValue(Rational squaredMagnitude) {
            return exact(sqrtCeil(squaredMagnitude));
        }
        void charge(long amount) { current.run(); work.charge(amount); }

        /** Rebinds exact normals from another local capability over this identical shared Work only. */
        Vector rebindSameWork(Vector vector) {
            Objects.requireNonNull(vector, "vector");
            return new Vector(rebindSameWork(vector.x()), rebindSameWork(vector.y()), rebindSameWork(vector.z()));
        }

        private Rational rebindSameWork(Rational value) {
            Objects.requireNonNull(value, "rational");
            if (value.owner == this) return value;
            if (value.owner.work != work)
                throw new IllegalArgumentException("foreign exact rational work cannot enter this geometry capability");
            return new Rational(this, dyadic.rebindSameWork(value.value));
        }

        private void require(Rational value) {
            if (value == null || value.owner != this) throw new IllegalArgumentException("foreign exact rational");
        }
    }

    static final class Rational {
        private final Arithmetic owner;
        private final BootQueryArithmetic.Fraction value;
        private Rational(Arithmetic owner, BootQueryArithmetic.Fraction value) {
            this.owner = Objects.requireNonNull(owner);
            this.value = Objects.requireNonNull(value);
        }
    }

    record Vector(Rational x, Rational y, Rational z) { }
    record Closest(Rational parameter, Vector segmentPoint, Vector boxPoint, Vector normal, Rational distanceSquared) { }
    record Facet(int ordinal, Aabb obstacle, Vector normal) {
        Facet {
            Objects.requireNonNull(obstacle);
            Objects.requireNonNull(normal);
        }
    }
    record Projection(Vector direction, Rational squaredCorrection) { }

    static final class Support {
        private final Facet facet;
        private final Closest closest;
        private final Rational supportGap;
        private final Rational normalSquared;
        private final Rational radius;
        private Support(Facet facet, Closest closest, Rational supportGap, Rational normalSquared, Rational radius) {
            this.facet = facet; this.closest = closest; this.supportGap = supportGap;
            this.normalSquared = normalSquared; this.radius = radius;
        }
        Facet facet() { return facet; }
        Closest closest() { return closest; }
        Rational supportGap() { return supportGap; }
        Rational normalSquared() { return normalSquared; }
        Rational radius() { return radius; }
        boolean near(Arithmetic arithmetic, Rational addedRadius) {
            Rational allowed = arithmetic.add(radius, addedRadius);
            return supportValid(arithmetic)
                    && arithmetic.compare(arithmetic.square(supportGap),
                            arithmetic.multiply(arithmetic.square(radius), normalSquared)) >= 0
                    && arithmetic.compare(closest.distanceSquared(), arithmetic.square(allowed)) <= 0;
        }
        boolean supportValid(Arithmetic arithmetic) {
            return arithmetic.compare(closest.distanceSquared(), arithmetic.square(radius)) >= 0
                    && arithmetic.compare(supportGap, arithmetic.exact(0)) >= 0
                    && arithmetic.compare(arithmetic.square(supportGap),
                            arithmetic.multiply(arithmetic.square(radius), normalSquared)) >= 0;
        }
        boolean exactlyTouching(Arithmetic arithmetic) {
            return arithmetic.compare(closest.distanceSquared(), arithmetic.square(radius)) == 0
                    && arithmetic.compare(supportGap, arithmetic.exact(0)) >= 0
                    && arithmetic.compare(arithmetic.square(supportGap),
                            arithmetic.multiply(arithmetic.square(radius), normalSquared)) >= 0;
        }
    }

    /** Exact 3D cone projection. Every supplied facet is checked, including facets not in the KKT basis. */
    static Optional<Projection> projectToCone(Arithmetic arithmetic, Vector requested, List<Facet> allFacets) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(requested); Objects.requireNonNull(allFacets);
        var normals = new ArrayList<Vector>(allFacets.size());
        for (Facet facet : allFacets) normals.add(facet.normal());
        return projectToConeNormals(arithmetic, requested, normals);
    }

    /** Projects a stored velocity into the exact cone, then selects a finite feasible binary64 vector. */
    static Vec3d projectToConeStored(Arithmetic arithmetic, Vec3d requested, List<Vector> constraints) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(requested); Objects.requireNonNull(constraints);
        var ownedConstraints = new ArrayList<Vector>(constraints.size());
        for (Vector constraint : constraints) ownedConstraints.add(arithmetic.rebindSameWork(constraint));
        Vector exactRequested = vector(arithmetic, requested);
        Rational requestedSquared = dot(arithmetic, exactRequested, exactRequested);
        boolean alreadyFeasible = true;
        for (Vector normal : ownedConstraints) {
            if (arithmetic.sign(dot(arithmetic, normal, normal)) <= 0)
                throw new IllegalArgumentException("response constraint has a zero normal");
            if (arithmetic.sign(dot(arithmetic, normal, exactRequested)) < 0) alreadyFeasible = false;
        }
        if (alreadyFeasible) return requested;
        Projection projection = projectToConeNormals(arithmetic, exactRequested, ownedConstraints)
                .orElseThrow(() -> new IllegalStateException("response cone has no certified projection"));
        Vector target = projection.direction();
        if (arithmetic.sign(target.x()) == 0 && arithmetic.sign(target.y()) == 0
                && arithmetic.sign(target.z()) == 0) return new Vec3d(0, 0, 0);

        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            Rational value = component(target, axis);
            lower[axis] = arithmetic.floor(value);
            upper[axis] = arithmetic.ceil(value);
        }
        var candidates = new ArrayList<Vec3d>(14);
        var finiteBases = new ArrayList<Vec3d>(8);
        for (int mask = 0; mask < 8; mask++) {
            Vec3d base = new Vec3d((mask & 1) == 0 ? lower[0] : upper[0],
                    (mask & 2) == 0 ? lower[1] : upper[1],
                    (mask & 4) == 0 ? lower[2] : upper[2]);
            addDistinct(finiteBases, base);
            addFeasibleResponse(arithmetic, target, ownedConstraints, base, requestedSquared, candidates);
        }
        // Derive the bounded one-ULP search from every raw floor/ceil combination, even if
        // the base itself misses a half-space or energy boundary.
        for (Vec3d base : finiteBases) {
            for (int axis = 0; axis < 3; axis++) {
                int sign = arithmetic.sign(component(target, axis));
                double value = axis == 0 ? base.x() : axis == 1 ? base.y() : base.z();
                if (sign > 0) addAdjacentResponse(arithmetic, target, ownedConstraints, base, axis,
                        Math.nextUp(value), requestedSquared, candidates);
                else if (sign < 0) addAdjacentResponse(arithmetic, target, ownedConstraints, base, axis,
                        Math.nextDown(value), requestedSquared, candidates);
                else {
                    addAdjacentResponse(arithmetic, target, ownedConstraints, base, axis,
                            Math.nextUp(value), requestedSquared, candidates);
                    addAdjacentResponse(arithmetic, target, ownedConstraints, base, axis,
                            Math.nextDown(value), requestedSquared, candidates);
                }
            }
        }
        if (candidates.isEmpty())
            throw new IllegalStateException("nonzero exact response projection has no adjacent finite stored vector satisfying its cone and energy bounds");
        candidates.sort((first, second) -> {
            int error = arithmetic.compare(squaredDistance(arithmetic, vector(arithmetic, first), target),
                    squaredDistance(arithmetic, vector(arithmetic, second), target));
            if (error != 0) return error;
            int x = Double.compare(first.x(), second.x());
            if (x != 0) return x;
            int y = Double.compare(first.y(), second.y());
            return y != 0 ? y : Double.compare(first.z(), second.z());
        });
        return candidates.getFirst();
    }

    private static void addAdjacentResponse(Arithmetic arithmetic, Vector target, List<Vector> constraints,
            Vec3d base, int axis, double adjacent, Rational requestedSquared, List<Vec3d> candidates) {
        if (!Double.isFinite(adjacent)) return;
        Vec3d candidate = new Vec3d(axis == 0 ? adjacent : base.x(), axis == 1 ? adjacent : base.y(),
                axis == 2 ? adjacent : base.z());
        addFeasibleResponse(arithmetic, target, constraints, candidate, requestedSquared, candidates);
    }

    private static void addFeasibleResponse(Arithmetic arithmetic, Vector target, List<Vector> constraints,
            Vec3d candidate, Rational requestedSquared, List<Vec3d> candidates) {
        int before = candidates.size();
        for (Vec3d existing : candidates)
            if (Double.doubleToRawLongBits(existing.x()) == Double.doubleToRawLongBits(candidate.x())
                    && Double.doubleToRawLongBits(existing.y()) == Double.doubleToRawLongBits(candidate.y())
                    && Double.doubleToRawLongBits(existing.z()) == Double.doubleToRawLongBits(candidate.z())) return;
        Vector exact = vector(arithmetic, candidate);
        if (arithmetic.sign(exact.x()) == 0 && arithmetic.sign(exact.y()) == 0
                && arithmetic.sign(exact.z()) == 0) return;
        if (arithmetic.compare(dot(arithmetic, exact, exact), requestedSquared) > 0) return;
        for (Vector normal : constraints)
            if (arithmetic.sign(dot(arithmetic, normal, exact)) < 0) return;
        candidates.add(candidate);
    }

    private static void addDistinct(List<Vec3d> values, Vec3d candidate) {
        for (Vec3d existing : values)
            if (Double.doubleToRawLongBits(existing.x()) == Double.doubleToRawLongBits(candidate.x())
                    && Double.doubleToRawLongBits(existing.y()) == Double.doubleToRawLongBits(candidate.y())
                    && Double.doubleToRawLongBits(existing.z()) == Double.doubleToRawLongBits(candidate.z())) return;
        values.add(candidate);
    }

    private static Optional<Projection> projectToConeNormals(Arithmetic arithmetic, Vector requested,
            List<Vector> allNormals) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(requested); Objects.requireNonNull(allNormals);
        var facets = new ArrayList<Vector>();
        for (Vector normal : allNormals) {
            Rational length = dot(arithmetic, normal, normal);
            if (arithmetic.sign(length) <= 0) throw new IllegalArgumentException("support facet normal is zero");
            boolean duplicate = false;
            for (Vector existing : facets) {
                if (samePositiveDirection(arithmetic, normal, existing)) { duplicate = true; break; }
            }
            if (!duplicate) facets.add(normal);
        }
        boolean alreadyFeasible = true;
        for (Vector normal : facets)
            if (arithmetic.sign(dot(arithmetic, normal, requested)) < 0) { alreadyFeasible = false; break; }
        if (alreadyFeasible) return Optional.of(new Projection(requested, arithmetic.exact(0)));

        ProjectionSearch search = new ProjectionSearch(arithmetic, requested, facets);
        for (int activeCount = 1; activeCount <= Math.min(3, facets.size()); activeCount++)
            search.enumerate(0, activeCount, new int[activeCount], 0);
        return Optional.ofNullable(search.best);
    }

    private static final class ProjectionSearch {
        private final Arithmetic arithmetic;
        private final Vector requested;
        private final List<Vector> facets;
        private Projection best;
        private ProjectionSearch(Arithmetic arithmetic, Vector requested, List<Vector> facets) {
            this.arithmetic = arithmetic; this.requested = requested; this.facets = facets;
        }
        private void enumerate(int start, int remaining, int[] active, int used) {
            arithmetic.charge(1);
            if (remaining == 0) {
                consider(active);
                return;
            }
            for (int index = start; index <= facets.size() - remaining; index++) {
                active[used] = index;
                enumerate(index + 1, remaining - 1, active, used + 1);
            }
        }
        private void consider(int[] active) {
            int count = active.length;
            Rational[][] matrix = new Rational[count][count];
            Rational[] right = new Rational[count];
            for (int row = 0; row < count; row++) {
                Vector normal = facets.get(active[row]);
                right[row] = arithmetic.negate(dot(arithmetic, normal, requested));
                for (int column = 0; column < count; column++)
                    matrix[row][column] = dot(arithmetic, normal, facets.get(active[column]));
            }
            Rational[] multipliers = solve(matrix, right);
            if (multipliers == null) return;
            Vector projected = requested;
            for (int index = 0; index < count; index++) {
                if (arithmetic.sign(multipliers[index]) < 0) return;
                projected = add(arithmetic, projected, multiply(arithmetic, facets.get(active[index]), multipliers[index]));
            }
            for (Vector normal : facets)
                if (arithmetic.sign(dot(arithmetic, normal, projected)) < 0) return;
            for (int index : active)
                if (arithmetic.sign(dot(arithmetic, facets.get(index), projected)) != 0) return;
            Vector correction = subtract(arithmetic, projected, requested);
            Rational loss = dot(arithmetic, correction, correction);
            if (best == null || arithmetic.compare(loss, best.squaredCorrection()) < 0)
                best = new Projection(projected, loss);
        }
        private Rational[] solve(Rational[][] source, Rational[] right) {
            int size = right.length;
            Rational determinant;
            Rational[] numerators = new Rational[size];
            if (size == 1) {
                determinant = source[0][0];
                numerators[0] = right[0];
            } else if (size == 2) {
                determinant = arithmetic.subtract(arithmetic.multiply(source[0][0], source[1][1]),
                        arithmetic.multiply(source[0][1], source[1][0]));
                numerators[0] = arithmetic.subtract(arithmetic.multiply(right[0], source[1][1]),
                        arithmetic.multiply(source[0][1], right[1]));
                numerators[1] = arithmetic.subtract(arithmetic.multiply(source[0][0], right[1]),
                        arithmetic.multiply(right[0], source[1][0]));
            } else if (size == 3) {
                determinant = determinant3(source);
                for (int column = 0; column < 3; column++) {
                    Rational[][] replaced = new Rational[3][3];
                    for (int row = 0; row < 3; row++) {
                        for (int index = 0; index < 3; index++)
                            replaced[row][index] = index == column ? right[row] : source[row][index];
                    }
                    numerators[column] = determinant3(replaced);
                }
            } else throw new IllegalArgumentException("cone active set exceeds three dimensions");
            if (arithmetic.sign(determinant) == 0) return null;
            Rational[] solution = new Rational[size];
            for (int index = 0; index < size; index++)
                solution[index] = arithmetic.divide(numerators[index], determinant);
            return solution;
        }
        private Rational determinant3(Rational[][] m) {
            Rational positive = arithmetic.add(arithmetic.add(
                    arithmetic.multiply(arithmetic.multiply(m[0][0], m[1][1]), m[2][2]),
                    arithmetic.multiply(arithmetic.multiply(m[0][1], m[1][2]), m[2][0])),
                    arithmetic.multiply(arithmetic.multiply(m[0][2], m[1][0]), m[2][1]));
            Rational negative = arithmetic.add(arithmetic.add(
                    arithmetic.multiply(arithmetic.multiply(m[0][2], m[1][1]), m[2][0]),
                    arithmetic.multiply(arithmetic.multiply(m[0][1], m[1][0]), m[2][2])),
                    arithmetic.multiply(arithmetic.multiply(m[0][0], m[1][2]), m[2][1]));
            return arithmetic.subtract(positive, negative);
        }
    }

    private static boolean samePositiveDirection(Arithmetic a, Vector first, Vector second) {
        Vector cross = new Vector(
                a.subtract(a.multiply(first.y(), second.z()), a.multiply(first.z(), second.y())),
                a.subtract(a.multiply(first.z(), second.x()), a.multiply(first.x(), second.z())),
                a.subtract(a.multiply(first.x(), second.y()), a.multiply(first.y(), second.x())));
        return a.sign(dot(a, cross, cross)) == 0 && a.sign(dot(a, first, second)) > 0;
    }

    static Closest closestSegmentBox(Arithmetic arithmetic, Segment3d segment, Aabb box) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(segment); Objects.requireNonNull(box);
        Vector start = vector(arithmetic, segment.start());
        Vector end = vector(arithmetic, segment.end());
        Vector delta = subtract(arithmetic, end, start);
        Vector low = vector(arithmetic, box.min()), high = vector(arithmetic, box.max());
        var cuts = new ArrayList<Rational>();
        cuts.add(arithmetic.exact(0)); cuts.add(arithmetic.exact(1));
        for (int axis = 0; axis < 3; axis++) {
            Rational d = component(delta, axis);
            if (arithmetic.sign(d) == 0) continue;
            for (Rational face : List.of(component(low, axis), component(high, axis))) {
                Rational t = arithmetic.divide(arithmetic.subtract(face, component(start, axis)), d);
                if (arithmetic.compare(t, arithmetic.exact(0)) > 0
                        && arithmetic.compare(t, arithmetic.exact(1)) < 0) cuts.add(t);
            }
        }
        cuts.sort(arithmetic::compare);
        var unique = new ArrayList<Rational>(cuts.size());
        for (Rational cut : cuts)
            if (unique.isEmpty() || arithmetic.compare(unique.getLast(), cut) != 0) unique.add(cut);
        Closest best = null;
        for (Rational cut : unique) best = better(arithmetic, best, at(arithmetic, start, delta, low, high, cut));
        for (int interval = 0; interval + 1 < unique.size(); interval++) {
            Rational left = unique.get(interval), right = unique.get(interval + 1);
            Rational midpoint = arithmetic.divide(arithmetic.add(left, right), arithmetic.exact(2));
            Rational coefficient = arithmetic.exact(0), linear = arithmetic.exact(0);
            for (int axis = 0; axis < 3; axis++) {
                Rational point = arithmetic.add(component(start, axis), arithmetic.multiply(component(delta, axis), midpoint));
                Rational bound = null;
                if (arithmetic.compare(point, component(low, axis)) < 0) bound = component(low, axis);
                else if (arithmetic.compare(point, component(high, axis)) > 0) bound = component(high, axis);
                if (bound == null) continue;
                Rational d = component(delta, axis);
                coefficient = arithmetic.add(coefficient, arithmetic.square(d));
                linear = arithmetic.add(linear, arithmetic.multiply(d,
                        arithmetic.subtract(component(start, axis), bound)));
            }
            if (arithmetic.sign(coefficient) != 0) {
                Rational optimum = arithmetic.divide(arithmetic.negate(linear), coefficient);
                best = better(arithmetic, best, at(arithmetic, start, delta, low, high,
                        arithmetic.clamp(optimum, left, right)));
            }
        }
        if (best == null) throw new IllegalStateException("closest segment-box witness has no candidate");
        return best;
    }

    static Optional<Support> support(Arithmetic arithmetic, int ordinal, Segment3d segment,
            Aabb box, double radius) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(segment); Objects.requireNonNull(box);
        Rational exactRadius = arithmetic.exact(radius);
        if (arithmetic.sign(exactRadius) < 0) throw new IllegalArgumentException("negative capsule radius");
        Closest closest = closestSegmentBox(arithmetic, segment, box);
        Vector normal = closest.normal();
        Rational normalSquared = dot(arithmetic, normal, normal);
        if (arithmetic.sign(normalSquared) <= 0) return Optional.empty();
        Vector low = vector(arithmetic, box.min()), high = vector(arithmetic, box.max());
        for (int axis = 0; axis < 3; axis++) {
            Rational n = component(normal, axis), q = component(closest.boxPoint(), axis);
            int sign = arithmetic.sign(n);
            if (sign > 0 && arithmetic.compare(q, component(high, axis)) != 0
                    || sign < 0 && arithmetic.compare(q, component(low, axis)) != 0
                    || sign == 0 && (arithmetic.compare(q, component(low, axis)) < 0
                        || arithmetic.compare(q, component(high, axis)) > 0)) return Optional.empty();
        }
        Vector endpoints = vector(arithmetic, segment.start()), second = vector(arithmetic, segment.end());
        Rational pProjection = dot(arithmetic, normal, closest.segmentPoint());
        if (arithmetic.compare(pProjection, arithmetic.min(dot(arithmetic, normal, endpoints),
                dot(arithmetic, normal, second))) != 0) return Optional.empty();
        Vector boxSupport = new Vector(
                arithmetic.sign(normal.x()) > 0 ? high.x() : low.x(),
                arithmetic.sign(normal.y()) > 0 ? high.y() : low.y(),
                arithmetic.sign(normal.z()) > 0 ? high.z() : low.z());
        Rational gap = arithmetic.subtract(pProjection, dot(arithmetic, normal, boxSupport));
        var facet = new Facet(ordinal, box, normal);
        Support support = new Support(facet, closest, gap, normalSquared, exactRadius);
        return support.supportValid(arithmetic) ? Optional.of(support) : Optional.empty();
    }

    /** Reuses one exact support direction for a declared ideal translation. */
    static boolean supportsTranslation(Arithmetic arithmetic, Vector normal, Segment3d start,
            Vector delta, Aabb box, double radius) {
        Objects.requireNonNull(arithmetic); Objects.requireNonNull(normal); Objects.requireNonNull(start);
        Objects.requireNonNull(delta); Objects.requireNonNull(box);
        if (!supports(arithmetic, normal, start, box, radius)) return false;
        Vector first = vector(arithmetic, start.start()), second = vector(arithmetic, start.end());
        Vector firstEnd = add(arithmetic, first, delta), secondEnd = add(arithmetic, second, delta);
        return supportsPoints(arithmetic, normal, firstEnd, secondEnd, box, radius);
    }

    static boolean supports(Arithmetic arithmetic, Vector normal, Segment3d segment, Aabb box, double radius) {
        Objects.requireNonNull(segment);
        return supportsPoints(arithmetic, normal, vector(arithmetic, segment.start()),
                vector(arithmetic, segment.end()), box, radius);
    }

    private static boolean supportsPoints(Arithmetic arithmetic, Vector normal, Vector first, Vector second,
            Aabb box, double radius) {
        Rational normalSquared = dot(arithmetic, normal, normal);
        if (arithmetic.sign(normalSquared) <= 0) return false;
        Vector low = vector(arithmetic, box.min()), high = vector(arithmetic, box.max());
        Vector support = new Vector(
                arithmetic.sign(normal.x()) > 0 ? high.x() : low.x(),
                arithmetic.sign(normal.y()) > 0 ? high.y() : low.y(),
                arithmetic.sign(normal.z()) > 0 ? high.z() : low.z());
        Rational exactRadius = arithmetic.exact(radius);
        Rational required = arithmetic.multiply(arithmetic.square(exactRadius), normalSquared);
        Rational firstGap = arithmetic.subtract(dot(arithmetic, normal, first), dot(arithmetic, normal, support));
        Rational secondGap = arithmetic.subtract(dot(arithmetic, normal, second), dot(arithmetic, normal, support));
        Rational startGap = arithmetic.min(firstGap, secondGap);
        if (arithmetic.sign(startGap) < 0 || arithmetic.compare(arithmetic.square(startGap), required) < 0)
            return false;
        return true;
    }

    static Rational squaredDistance(Arithmetic arithmetic, Vector first, Vector second) {
        return dot(arithmetic, subtract(arithmetic, first, second), subtract(arithmetic, first, second));
    }

    static Rational cutDeviationUpper(Arithmetic arithmetic, Segment3d originalStart,
            Segment3d actualCut, Vec3d originalDelta, double sourceFraction) {
        return cutDeviationUpper(arithmetic, originalStart, actualCut, originalDelta, sourceFraction, 1);
    }

    static Rational cutDeviationUpper(Arithmetic arithmetic, Segment3d originalStart,
            Segment3d actualCut, Vec3d originalDelta, double localFraction, double sourceScale) {
        Rational f = arithmetic.multiply(arithmetic.exact(localFraction), arithmetic.exact(sourceScale));
        Vector delta = vector(arithmetic, originalDelta);
        Vector startA = vector(arithmetic, originalStart.start()), startB = vector(arithmetic, originalStart.end());
        Vector idealA = add(arithmetic, startA, multiply(arithmetic, delta, f));
        Vector idealB = add(arithmetic, startB, multiply(arithmetic, delta, f));
        Vector actualA = vector(arithmetic, actualCut.start()), actualB = vector(arithmetic, actualCut.end());
        Rational deviationSquared = arithmetic.max(squaredDistance(arithmetic, idealA, actualA),
                squaredDistance(arithmetic, idealB, actualB));
        return arithmetic.sqrtCeilValue(deviationSquared);
    }

    static Rational bracketTravelUpper(Arithmetic arithmetic, Vec3d delta, double low, double high) {
        Rational width = arithmetic.subtract(arithmetic.exact(high), arithmetic.exact(low));
        if (arithmetic.sign(width) < 0) throw new IllegalArgumentException("reversed obstruction bracket");
        return bracketTravelUpper(arithmetic, delta, width);
    }

    static Rational bracketTravelUpper(Arithmetic arithmetic, Vec3d delta, double low,
            double high, double sourceScale) {
        Rational width = arithmetic.multiply(arithmetic.subtract(arithmetic.exact(high), arithmetic.exact(low)),
                arithmetic.exact(sourceScale));
        if (arithmetic.sign(width) < 0) throw new IllegalArgumentException("reversed obstruction bracket");
        return bracketTravelUpper(arithmetic, delta, width);
    }

    /** Independently checks CHB's requested path-width contract using the exact stored inputs. */
    static boolean bracketTravelWithinAccuracy(Arithmetic arithmetic, Vec3d delta,
            double low, double high, double sourceScale, double maximumWidth) {
        Rational width = arithmetic.multiply(arithmetic.subtract(arithmetic.exact(high), arithmetic.exact(low)),
                arithmetic.exact(sourceScale));
        Rational maximum = arithmetic.exact(maximumWidth);
        if (arithmetic.sign(width) < 0 || arithmetic.sign(maximum) <= 0) return false;
        Vector exactDelta = vector(arithmetic, delta);
        Rational distanceSquared = dot(arithmetic, exactDelta, exactDelta);
        return arithmetic.compare(arithmetic.multiply(distanceSquared, arithmetic.square(width)),
                arithmetic.square(maximum)) <= 0;
    }

    static Rational radiusIncrementUpper(Arithmetic arithmetic, double actualRadius, double queryRadius) {
        Rational increment = arithmetic.subtract(arithmetic.exact(queryRadius), arithmetic.exact(actualRadius));
        if (arithmetic.sign(increment) < 0) throw new IllegalArgumentException("query radius is below actual radius");
        return arithmetic.exact(arithmetic.ceil(increment));
    }

    static Rational add(Arithmetic arithmetic, Rational first, Rational second) {
        return arithmetic.add(first, second);
    }

    private static Rational bracketTravelUpper(Arithmetic arithmetic, Vec3d delta, Rational width) {
        Vector exactDelta = vector(arithmetic, delta);
        Rational normSquared = dot(arithmetic, exactDelta, exactDelta);
        Rational normUpper = arithmetic.sqrtCeilValue(normSquared);
        return arithmetic.exact(arithmetic.ceil(arithmetic.multiply(normUpper, width)));
    }

    static Rational dot(Arithmetic arithmetic, Vector a, Vector b) {
        return arithmetic.add(arithmetic.add(arithmetic.multiply(a.x(), b.x()),
                arithmetic.multiply(a.y(), b.y())), arithmetic.multiply(a.z(), b.z()));
    }
    static Vector vector(Arithmetic arithmetic, Vec3d value) {
        return new Vector(arithmetic.exact(value.x()), arithmetic.exact(value.y()), arithmetic.exact(value.z()));
    }
    static Vector add(Arithmetic arithmetic, Vector a, Vector b) {
        return new Vector(arithmetic.add(a.x(), b.x()), arithmetic.add(a.y(), b.y()), arithmetic.add(a.z(), b.z()));
    }
    static Vector subtract(Arithmetic arithmetic, Vector a, Vector b) {
        return new Vector(arithmetic.subtract(a.x(), b.x()), arithmetic.subtract(a.y(), b.y()), arithmetic.subtract(a.z(), b.z()));
    }
    static Vector multiply(Arithmetic arithmetic, Vector a, Rational scalar) {
        return new Vector(arithmetic.multiply(a.x(), scalar), arithmetic.multiply(a.y(), scalar), arithmetic.multiply(a.z(), scalar));
    }
    static Rational component(Vector value, int axis) { return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z(); }

    private static Closest at(Arithmetic a, Vector start, Vector delta, Vector low, Vector high, Rational parameter) {
        Vector p = add(a, start, multiply(a, delta, parameter));
        Vector q = new Vector(a.clamp(p.x(), low.x(), high.x()), a.clamp(p.y(), low.y(), high.y()),
                a.clamp(p.z(), low.z(), high.z()));
        Vector normal = subtract(a, p, q);
        Rational distance = dot(a, normal, normal);
        return new Closest(parameter, p, q, normal, distance);
    }
    private static Closest better(Arithmetic a, Closest old, Closest candidate) {
        if (old == null || a.compare(candidate.distanceSquared(), old.distanceSquared()) < 0
                || a.compare(candidate.distanceSquared(), old.distanceSquared()) == 0
                    && a.compare(candidate.parameter(), old.parameter()) < 0) return candidate;
        return old;
    }
}
