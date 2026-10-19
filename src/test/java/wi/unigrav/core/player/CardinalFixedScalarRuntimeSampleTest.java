package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalFixedScalarRuntimeSampleTest {
    @Test void observesSourceAndTargetOfFixedScalarRecipe() {
        var dimensions = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
        var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1),
                new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833));
        var sourcePlacement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(40.5, 88.59999996423721, 1.8999999761581416), CardinalFrame.Z_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, dimensions));
        var source = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 195),
                new UUID(0, 196), 0, 2), 7960, sourcePlacement, new Vec3d(0, 0, 0));
        var work = new StoredDyadic.Work();
        var prepared = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(source, dimensions, work));
        var stored = prepared.placement().storedCapsuleGeometry();
        double h = Math.abs(stored.storedHalfLine().z());
        var center = sourcePlacement.center();
        var firstHalf = CardinalFrame.Z_POS.attitude().basisY().multiply(h);
        var firstNegative = center.subtract(firstHalf);
        var firstPositive = center.add(firstHalf);
        System.out.println("fixedScalar=" + h + "; sourceHalf=" + firstHalf + "; negative="
                + firstNegative + "; positive=" + firstPositive);
        assertTrue(StoredRealizationLimits.sameRaw(firstNegative, stored.negativeEndpoint()));
        assertTrue(StoredRealizationLimits.sameRaw(firstPositive, stored.positiveEndpoint()));
        var targetQ = CardinalFrame.Y_POS.attitude();
        var targetHalf = targetQ.basisY().multiply(h);
        var targetNegative = center.subtract(targetHalf);
        var targetPositive = center.add(targetHalf);
        var limits = StoredRealizationLimits.derive(center, targetQ, dimensions, work);
        for (int axis = 0; axis < 3; axis++) {
            var delta = StoredDyadic.of(StoredRealizationLimits.component(targetHalf, axis), work)
                    .subtract(StoredDyadic.of(StoredRealizationLimits.component(limits.nominalHalfLine(), axis), work), work).abs(work);
            System.out.println("target axis=" + axis + "; delta=" + delta.roundUp(work)
                    + "; domain=" + limits.halfLineBound(axis).roundUp(work));
            assertTrue(delta.compare(limits.halfLineBound(axis), work) <= 0);
        }
        double[] low = new double[3], high = new double[3];
        var radius = StoredDyadic.of(dimensions.radius(), work);
        for (int axis = 0; axis < 3; axis++) {
            double a = StoredRealizationLimits.component(targetNegative, axis);
            double b = StoredRealizationLimits.component(targetPositive, axis);
            low[axis] = StoredDyadic.of(Math.min(a, b), work).subtract(radius, work).roundDown(work);
            high[axis] = StoredDyadic.of(Math.max(a, b), work).add(radius, work).roundUp(work);
        }
        var outward = new Aabb(new Vec3d(low[0], low[1], low[2]), new Vec3d(high[0], high[1], high[2]));
        var target = new PlayerBodyPlacement(center, targetQ,
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        System.out.println("targetHalf=" + targetHalf + "; negative=" + targetNegative + "; positive="
                + targetPositive + "; outward=" + outward + "; targetCardinal=" + target.bounds());
        assertTrue(target.bounds().contains(outward.min()) && target.bounds().contains(outward.max()));
        double deltaH = dimensions.centerlineHalfLength() - h;
        // For |basis component| <= 2, two RN multiplies differ by at most
        // 2*deltaH + 2*u*(2*hNominal) + lambda. This is a numeric domain bound only.
        var global = StoredDyadic.of(deltaH, work).scalePow2(1, work)
                .add(StoredDyadic.of(dimensions.centerlineHalfLength(), work).scalePow2(-51, work), work)
                .add(StoredDyadic.lambdaHalf(work).scalePow2(1, work), work);
        System.out.println("allIntervalMultiplyDifferenceUpper=" + global.roundUp(work));
        for (int axis = 0; axis < 3; axis++) {
            var coordinateFloor = StoredDyadic.gap64(StoredRealizationLimits.component(center, axis), work).scalePow2(1, work);
            System.out.println("allInterval axis=" + axis + "; domainFloor=" + coordinateFloor.roundUp(work));
            assertTrue(global.compare(coordinateFloor, work) <= 0);
        }
    }
}
