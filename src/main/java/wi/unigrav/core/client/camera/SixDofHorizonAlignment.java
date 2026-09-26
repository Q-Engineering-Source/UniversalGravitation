package wi.unigrav.core.client.camera;

import java.util.Objects;

import wi.unigrav.api.math.Vec3;

/** Pure reference-frame geometry and bounded correction flow for horizon alignment. */
final class SixDofHorizonAlignment {

    private static final double POLAR_EPSILON = 1.0e-10;
    private static final double BANK_TIE_EPSILON = 1.0e-12;

    private SixDofHorizonAlignment() {
    }

    static Vec3 unitNormal(Vec3 normal) {
        return normalized(normal, "normal");
    }

    static Frame frame(Vec3 normal, Vec3 forward, Vec3 currentUp) {
        Vec3 unitNormal = unitNormal(normal);
        Vec3 unitForward = normalized(forward, "forward");
        Vec3 unitUp = normalized(currentUp, "currentUp");
        double sine = unitNormal.dot(unitForward);
        Vec3 horizontal = unitForward.sub(unitNormal.scale(sine));
        double cosine = horizontal.norm();
        Vec3 heading;
        if (cosine > POLAR_EPSILON) {
            heading = horizontal.scale(1.0 / cosine);
        } else {
            Vec3 projectedUp = unitUp.sub(unitNormal.scale(unitNormal.dot(unitUp)));
            heading = normalized(projectedUp, "currentUp projection").scale(sine >= 0.0 ? -1.0 : 1.0);
            cosine = 0.0;
        }
        Vec3 left = unitNormal.cross(heading);
        Vec3 targetUp = unitNormal.scale(cosine).sub(heading.scale(sine));
        return new Frame(unitNormal, Math.atan2(sine, cosine), left, targetUp);
    }

    static double boundedStepToZero(double value, double deltaSeconds,
            double halfLifeSeconds, double maximumRateRadiansPerSecond) {
        if (value == 0.0 || deltaSeconds <= 0.0 || maximumRateRadiansPerSecond == 0.0) {
            return 0.0;
        }
        double magnitude = Math.abs(value);
        double rate = Math.log(2.0) / halfLifeSeconds;
        double transitionMagnitude = maximumRateRadiansPerSecond / rate;
        double correction;
        if (magnitude <= transitionMagnitude) {
            correction = magnitude * -Math.expm1(-rate * deltaSeconds);
        } else {
            double timeToExponential = (magnitude - transitionMagnitude) / maximumRateRadiansPerSecond;
            if (deltaSeconds <= timeToExponential) {
                correction = maximumRateRadiansPerSecond * deltaSeconds;
            } else {
                correction = magnitude - transitionMagnitude
                        + transitionMagnitude * -Math.expm1(-rate * (deltaSeconds - timeToExponential));
            }
        }
        return Math.copySign(correction, value);
    }

    /**
     * Returns the exact elapsed time for a bounded decay to reach a smaller
     * positive magnitude. Both the capped and exponential portions use the
     * same flow as {@link #boundedStepToZero(double, double, double, double)}.
     */
    static double boundedDecayTime(double initialMagnitude, double targetMagnitude,
            double halfLifeSeconds, double maximumRateRadiansPerSecond) {
        if (initialMagnitude < targetMagnitude || targetMagnitude <= 0.0
                || !Double.isFinite(initialMagnitude) || !Double.isFinite(targetMagnitude)) {
            throw new IllegalArgumentException("invalid bounded decay interval");
        }
        if (initialMagnitude == targetMagnitude) {
            return 0.0;
        }
        double rate = Math.log(2.0) / halfLifeSeconds;
        double transitionMagnitude = maximumRateRadiansPerSecond / rate;
        if (targetMagnitude >= transitionMagnitude) {
            return (initialMagnitude - targetMagnitude) / maximumRateRadiansPerSecond;
        }
        if (initialMagnitude > transitionMagnitude) {
            return (initialMagnitude - transitionMagnitude) / maximumRateRadiansPerSecond
                    + Math.log(transitionMagnitude / targetMagnitude) / rate;
        }
        return Math.log(initialMagnitude / targetMagnitude) / rate;
    }

    /**
     * Advances an overflow outside the soft pitch target toward that target.
     * Values inside the soft-to-hard buffer use the gentle profile. Values
     * inherited beyond hard first use the emergency profile exactly until the
     * hard boundary, then spend only their remaining elapsed time gently.
     */
    static double boundedSoftPitchStepToZero(double signedOverflowRadians,
            double softToHardBufferRadians, double deltaSeconds,
            double softHalfLifeSeconds, double maximumSoftRateRadiansPerSecond,
            double emergencyHalfLifeSeconds, double maximumEmergencyRateRadiansPerSecond) {
        if (signedOverflowRadians == 0.0 || deltaSeconds <= 0.0) {
            return 0.0;
        }
        double magnitude = Math.abs(signedOverflowRadians);
        double correction;
        if (magnitude <= softToHardBufferRadians) {
            correction = Math.abs(boundedStepToZero(magnitude, deltaSeconds,
                    softHalfLifeSeconds, maximumSoftRateRadiansPerSecond));
        } else {
            double timeToHard = boundedDecayTime(magnitude, softToHardBufferRadians,
                    emergencyHalfLifeSeconds, maximumEmergencyRateRadiansPerSecond);
            if (deltaSeconds <= timeToHard) {
                correction = Math.abs(boundedStepToZero(magnitude, deltaSeconds,
                        emergencyHalfLifeSeconds, maximumEmergencyRateRadiansPerSecond));
            } else {
                correction = magnitude - softToHardBufferRadians
                        + Math.abs(boundedStepToZero(softToHardBufferRadians,
                                deltaSeconds - timeToHard, softHalfLifeSeconds,
                                maximumSoftRateRadiansPerSecond));
            }
        }
        return Math.copySign(correction, signedOverflowRadians);
    }

    static double protectedPitchTarget(double referencePitchRadians,
            double mousePitchRadians, double limitRadians) {
        double rawTarget = referencePitchRadians - mousePitchRadians;
        if (referencePitchRadians > limitRadians) {
            return Math.max(-limitRadians, Math.min(referencePitchRadians, rawTarget));
        }
        if (referencePitchRadians < -limitRadians) {
            return Math.min(limitRadians, Math.max(referencePitchRadians, rawTarget));
        }
        return Math.max(-limitRadians, Math.min(limitRadians, rawTarget));
    }

    static double signedBankErrorRadians(Vec3 forward, Vec3 currentUp, Vec3 targetUp) {
        double sine = forward.dot(currentUp.cross(targetUp));
        double cosine = currentUp.dot(targetUp);
        if (Math.abs(sine) <= BANK_TIE_EPSILON && cosine < 0.0) {
            return Math.PI;
        }
        return Math.atan2(sine, cosine);
    }

    private static Vec3 normalized(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double length = value.norm();
        if (!Double.isFinite(length) || length <= 0.0) {
            throw new IllegalArgumentException(name + " must be non-zero");
        }
        return value.scale(1.0 / length);
    }

    record Frame(Vec3 normal, double referencePitchRadians, Vec3 left, Vec3 targetUp) {
    }

    record Tuning(double softPitchDegrees, double hardPitchDegrees,
            double bankHalfLifeSeconds, double maximumAutoBankDegreesPerSecond,
            double softPitchHalfLifeSeconds, double maximumSoftPitchDegreesPerSecond,
            double emergencyPitchHalfLifeSeconds, double maximumEmergencyPitchDegreesPerSecond,
            double maximumDeltaSeconds) {

        static final Tuning DEFAULT = new Tuning(86.0, 89.0,
                0.20, 180.0, 0.25, 30.0, 0.12, 90.0, 0.10);

        /**
         * Source-compatible package-private bridge for the former one-profile
         * acquisition tuning. Its profile applies to both portions; callers
         * still receive the new explicit 3 degree soft-to-hard buffer.
         */
        Tuning(double poleMarginDegrees, double bankHalfLifeSeconds,
                double maximumAutoBankDegreesPerSecond, double acquisitionHalfLifeSeconds,
                double maximumAcquireDegreesPerSecond, double maximumDeltaSeconds) {
            this(legacySoftPitchDegrees(poleMarginDegrees), 90.0 - poleMarginDegrees,
                    bankHalfLifeSeconds,
                    maximumAutoBankDegreesPerSecond, acquisitionHalfLifeSeconds,
                    maximumAcquireDegreesPerSecond, acquisitionHalfLifeSeconds,
                    maximumAcquireDegreesPerSecond, maximumDeltaSeconds);
        }

        private static double legacySoftPitchDegrees(double poleMarginDegrees) {
            double hardPitchDegrees = 90.0 - poleMarginDegrees;
            return hardPitchDegrees - Math.min(3.0, hardPitchDegrees * 0.5);
        }

        Tuning {
            if (!Double.isFinite(softPitchDegrees)
                    || !Double.isFinite(hardPitchDegrees)
                    || !Double.isFinite(bankHalfLifeSeconds)
                    || !Double.isFinite(maximumAutoBankDegreesPerSecond)
                    || !Double.isFinite(softPitchHalfLifeSeconds)
                    || !Double.isFinite(maximumSoftPitchDegreesPerSecond)
                    || !Double.isFinite(emergencyPitchHalfLifeSeconds)
                    || !Double.isFinite(maximumEmergencyPitchDegreesPerSecond)
                    || !Double.isFinite(maximumDeltaSeconds)
                    || softPitchDegrees <= 0.0 || softPitchDegrees >= hardPitchDegrees
                    || hardPitchDegrees >= 90.0
                    || bankHalfLifeSeconds <= 0.0 || maximumAutoBankDegreesPerSecond < 0.0
                    || softPitchHalfLifeSeconds <= 0.0 || maximumSoftPitchDegreesPerSecond <= 0.0
                    || emergencyPitchHalfLifeSeconds <= 0.0
                    || maximumEmergencyPitchDegreesPerSecond <= 0.0
                    || maximumDeltaSeconds <= 0.0) {
                throw new IllegalArgumentException("invalid horizon alignment tuning");
            }
        }
    }
}
