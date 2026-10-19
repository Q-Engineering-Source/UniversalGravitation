package wi.unigrav.core.player;

/** Numeric qualification only; each caller separately binds its proven literal quaternion chain. */
final class CardinalLiteralWallDomain {
    private CardinalLiteralWallDomain() { }

    static boolean matches(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
            boolean requireJointForH1, StoredDyadic.Work same) {
        // Preserve the existing H1 branches, including the original Z_POS mapping's
        // full-path proof without a JOINT_RN restriction. This is never an H0 branch.
        if (Double.doubleToRawLongBits(recipe.halfLength()) == 0x3fe333331ffffffcL
                && (!requireJointForH1 || path.fixedDomainProof() == CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN))
            return true;
        same.charge(4);
        var dimensions = recipe.start().placement().shape().dimensions();
        // NOMINAL_IDENTITY proves RN(up_i*h) is the identical stored expression
        // throughout the path. The constructor theorem is independent of h/C/r;
        // dynamic supports and both actual endpoint overrides remain with the caller.
        return path.fixedDomainProof() == CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY
                && StoredRealizationLimits.sameRaw(dimensions.width(), (double) .6F)
                && StoredRealizationLimits.sameRaw(dimensions.height(), (double) 1.8F)
                && StoredRealizationLimits.sameRaw(dimensions.eyeHeight(), (double) 1.62F)
                && StoredRealizationLimits.sameRaw(recipe.halfLength(), dimensions.centerlineHalfLength());
    }
}
