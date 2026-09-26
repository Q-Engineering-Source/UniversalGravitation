package wi.unigrav.core.client.camera;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the FREE full-quaternion F3 attitude readout. */
class SixDofCameraDebugTextTest {

    @Test
    void formatsTheIdentityFreeViewWithNamedLocalInputAxes() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        assertEquals(List.of(
                "[unigrav 6dof] mode=FREE q(wxyz)=(1.000,0.000,0.000,0.000)",
                "[unigrav 6dof] view +Z=(0.000,0.000,1.000) +X=(1.000,0.000,0.000) +Y=(0.000,1.000,0.000)",
                "[unigrav 6dof] mouse +X axis=(0.000,-1.000,0.000) +Y axis=(-1.000,0.000,0.000) roll axis=(0.000,0.000,1.000)",
                "[unigrav 6dof] mouse axes dot=0.000 cross-area=1.000",
                "[unigrav 6dof] roll owner=true input=0.000 omega=0.00°/s state=STOPPED"),
                SixDofCameraDebugText.format(orientation.current(), false,
                        new SixDofCameraDebugText.RollState(true, 0.0, 0.0)));
    }

    @Test
    void usesRootDecimalFormattingForTheAuthoritativeQuaternion() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(30.0, -45.0);
        orientation.applyExplicitRollDegrees(37.25);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            List<String> lines = SixDofCameraDebugText.format(orientation.current(), false);

            assertEquals("[unigrav 6dof] mode=FREE q(wxyz)=(0.877,0.345,0.274,0.191)",
                    lines.getFirst());
            assertEquals("[unigrav 6dof] mouse axes dot=0.000 cross-area=1.000",
                    lines.get(3));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void localRollRotatesEveryVisibleMouseAxisWithoutHiddenControlState() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        orientation.applyExplicitRollDegrees(90.0);

        List<String> lines = SixDofCameraDebugText.format(orientation.current(), false);

        assertEquals("[unigrav 6dof] mode=FREE q(wxyz)=(0.707,0.000,0.000,0.707)",
                lines.getFirst());
        assertEquals("[unigrav 6dof] view +Z=(0.000,0.000,1.000) +X=(0.000,1.000,0.000) +Y=(-1.000,0.000,0.000)",
                lines.get(1));
        assertEquals("[unigrav 6dof] mouse +X axis=(1.000,0.000,0.000) +Y axis=(0.000,-1.000,0.000) roll axis=(0.000,0.000,1.000)",
                lines.get(2));
    }

    @Test
    void upsideDownViewKeepsMouseAxesCameraLocalWithoutReflection() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(180.0, 0.0);

        List<String> lines = SixDofCameraDebugText.format(orientation.current(), false);

        assertEquals("[unigrav 6dof] mode=FREE q(wxyz)=(0.000,1.000,0.000,0.000)",
                lines.getFirst());
        assertEquals("[unigrav 6dof] view +Z=(0.000,0.000,-1.000) +X=(1.000,0.000,0.000) +Y=(0.000,-1.000,0.000)",
                lines.get(1));
        assertEquals("[unigrav 6dof] mouse +X axis=(0.000,1.000,0.000) +Y axis=(-1.000,0.000,0.000) roll axis=(0.000,0.000,-1.000)",
                lines.get(2));
    }

    @Test
    void reportsTheActualRawMouseYAxisForTheInvertSetting() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        String normal = SixDofCameraDebugText.format(orientation.current(), false).get(2);
        String inverted = SixDofCameraDebugText.format(orientation.current(), true).get(2);

        assertEquals("[unigrav 6dof] mouse +X axis=(0.000,-1.000,0.000) +Y axis=(-1.000,0.000,0.000) roll axis=(0.000,0.000,1.000)",
                normal);
        assertEquals("[unigrav 6dof] mouse +X axis=(0.000,-1.000,0.000) +Y axis=(1.000,0.000,0.000) roll axis=(0.000,0.000,1.000)",
                inverted);
    }

    @Test
    void genericLocalRotationContinuesToProduceFreeDiagnostics() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(20.0, -30.0);
        orientation.applyDegrees(7.0, 11.0, 13.0);
        orientation.applyMouseDelta(10, -8, 0.5, false);

        List<String> lines = SixDofCameraDebugText.format(orientation.current(), false);

        assertEquals(5, lines.size());
        assertTrue(lines.getFirst().startsWith("[unigrav 6dof] mode=FREE q(wxyz)="));
        assertFalse(lines.stream().anyMatch(line -> line.contains("gauge")
                || line.contains("residual") || line.contains("recovery")
                || line.contains("explicit-roll") || line.contains("inverted")));
    }

    @Test
    void nullViewHasNoDebugOutput() {
        assertEquals(List.of(), SixDofCameraDebugText.format(null, false));
    }

    @Test
    void formatsDriveCoastStoppedAndUnownedRollStates() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        String drive = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, -1.0, 72.0)).getLast();
        String coast = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 72.0)).getLast();
        String stopped = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.0)).getLast();
        String slowCoast = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.02)).getLast();
        String unowned = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(false, 0.0, 0.0)).getLast();

        assertEquals("[unigrav 6dof] roll owner=true input=-1.000 omega=72.00°/s state=DRIVE",
                drive);
        assertEquals("[unigrav 6dof] roll owner=true input=0.000 omega=72.00°/s state=COAST",
                coast);
        assertEquals("[unigrav 6dof] roll owner=true input=0.000 omega=0.00°/s state=STOPPED",
                stopped);
        assertEquals("[unigrav 6dof] roll owner=true input=0.000 omega=0.02°/s state=COAST",
                slowCoast);
        assertEquals("[unigrav 6dof] roll owner=false input=0.000 omega=0.00°/s state=UNOWNED",
                unowned);
    }

    @Test
    void formatsSelectedRollAndBoostGears() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        String gears = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.0),
                new SixDofCameraDebugText.GearState(
                        3, 5, 180.0, 720.0, 9, 9, 490.0, 980.0)).getLast();

        assertEquals("[unigrav 6dof] gears roll=3/5 max=180°/s accel=720°/s² boost=9/9 target=490.00m/s accel=980.00m/s²",
                gears);
    }

    @Test
    void fourArgumentFormatRemainsBitwiseCompatibleWithoutAlignmentState() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(31.0, -47.0);
        SixDofCameraDebugText.RollState roll = new SixDofCameraDebugText.RollState(true, 0.0, 0.0);
        SixDofCameraDebugText.GearState gears = new SixDofCameraDebugText.GearState(
                3, 5, 180.0, 720.0, 5, 9, 40.0, 80.0);

        assertEquals(SixDofCameraDebugText.format(orientation.current(), false, roll, gears),
                SixDofCameraDebugText.format(orientation.current(), false, roll, gears, null));
    }

    @Test
    void effectiveYAndXReferencesExposeTheActualAlignmentMouseAxes() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        SixDofCameraDebugText.RollState roll = new SixDofCameraDebugText.RollState(true, 0.0, 0.0);

        List<String> yPositive = SixDofCameraDebugText.format(orientation.current(), false, roll,
                null, alignment(true, false, SixDofAlignmentInput.Reference.Y_POS, null,
                        true, false, correction()));
        assertTrue(yPositive.getFirst().startsWith("[unigrav 6dof] mode=ALIGN "));
        assertEquals("[unigrav 6dof] view +Z=(0.000,0.000,1.000) +X=(1.000,0.000,0.000) +Y=(0.000,1.000,0.000)",
                yPositive.get(1));
        assertEquals("[unigrav 6dof] mouse +X axis=(0.000,-1.000,0.000) +Y axis=(-1.000,0.000,0.000) roll axis=(0.000,0.000,1.000)",
                yPositive.get(2));

        List<String> xPositive = SixDofCameraDebugText.format(orientation.current(), false, roll,
                null, alignment(true, false, SixDofAlignmentInput.Reference.X_POS, null,
                        true, false, correction()));
        assertEquals("[unigrav 6dof] view +Z=(0.000,0.000,1.000) +X=(1.000,0.000,0.000) +Y=(0.000,1.000,0.000)",
                xPositive.get(1));
        assertEquals("[unigrav 6dof] mouse +X axis=(-1.000,0.000,0.000) +Y axis=(0.000,1.000,0.000) roll axis=(0.000,0.000,1.000)",
                xPositive.get(2));
        assertEquals("[unigrav 6dof] align autoBank=45.00°/s autoPitch=30.00°/s refPitch=12.50° bankError=-7.25° acquiring=true",
                xPositive.get(6));

        List<String> inverted = SixDofCameraDebugText.format(orientation.current(), true, roll,
                null, alignment(true, false, SixDofAlignmentInput.Reference.X_POS, null,
                        true, false, correction()));
        assertEquals("[unigrav 6dof] mouse +X axis=(-1.000,0.000,0.000) +Y axis=(0.000,-1.000,0.000) roll axis=(0.000,0.000,1.000)",
                inverted.get(2));
    }

    @Test
    void selectingNoHitAndCandidateReportPreviewWithoutApplyingAlignmentOrCorrection() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        List<String> lines = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.0), null,
                alignment(false, true, SixDofAlignmentInput.Reference.Y_NEG, null,
                        false, true, null));

        assertTrue(lines.getFirst().startsWith("[unigrav 6dof] mode=FREE "));
        assertEquals("[unigrav 6dof] align state=SELECT enabled=false selected=Y- preview=NO_HIT effective=false pitchLimited=true",
                lines.get(5));
        assertEquals("[unigrav 6dof] align autoBank=0.00°/s autoPitch=0.00°/s refPitch=0.00° bankError=0.00° acquiring=false",
                lines.get(6));

        List<String> candidate = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.0), null,
                alignment(false, true, SixDofAlignmentInput.Reference.Y_NEG,
                        SixDofAlignmentInput.Reference.X_POS, false, false, null));
        assertEquals("[unigrav 6dof] align state=SELECT enabled=false selected=Y- preview=X+ effective=false pitchLimited=false",
                candidate.get(5));
    }

    @Test
    void offStateClearsAutomaticRatesAndRetainsTheSelectedNormalReference() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        List<String> lines = SixDofCameraDebugText.format(orientation.current(), false,
                new SixDofCameraDebugText.RollState(true, 0.0, 0.0), null,
                alignment(false, false, SixDofAlignmentInput.Reference.Z_NEG, null,
                        false, false, null));

        assertTrue(lines.getFirst().startsWith("[unigrav 6dof] mode=FREE "));
        assertEquals("[unigrav 6dof] align state=HOLD enabled=false selected=Z- preview=NONE effective=false pitchLimited=false",
                lines.get(5));
        assertEquals("[unigrav 6dof] align autoBank=0.00°/s autoPitch=0.00°/s refPitch=0.00° bankError=0.00° acquiring=false",
                lines.get(6));
    }

    private static SixDofCameraDebugText.AlignmentState alignment(boolean enabled,
            boolean selecting, SixDofAlignmentInput.Reference reference,
            SixDofAlignmentInput.Reference previewReference, boolean effective,
            boolean pitchLimited, SixDofCameraOrientation.HorizonResult correction) {
        return new SixDofCameraDebugText.AlignmentState(enabled, selecting, reference,
                previewReference, effective, pitchLimited, correction);
    }

    private static SixDofCameraOrientation.HorizonResult correction() {
        return new SixDofCameraOrientation.HorizonResult(12.5, -7.25, 45.0, 30.0, true);
    }
}
