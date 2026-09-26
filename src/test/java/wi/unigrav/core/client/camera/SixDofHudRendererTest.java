package wi.unigrav.core.client.camera;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import neofontrender.addons.api.flight.FlightHudCanvas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the renderer through the public UIE canvas seam without a Minecraft or GL context. */
class SixDofHudRendererTest {

    @Test
    void wrap360PitchLadderRepeatsForWholeRevolutions() {
        RecordingCanvas baseline = draw(frame(13.0, 27.0, 0.0), 854, 480);
        RecordingCanvas plusTurn = draw(frame(373.0, 27.0, 0.0), 854, 480);
        RecordingCanvas minusTurn = draw(frame(-347.0, 27.0, 0.0), 854, 480);

        assertEquals(baseline.events(), plusTurn.events());
        assertEquals(baseline.events(), minusTurn.events());
        assertTrue(baseline.events().stream().anyMatch(event -> event.startsWith("line")));
    }

    @Test
    void ladderRemainsPopulatedAtBothPolesAndInvertedAttitudes() {
        for (double pitch : List.of(-90.0, 90.0, 180.0, -180.0, 270.0)) {
            RecordingCanvas canvas = draw(frame(pitch, 0.0, 180.0), 640, 360);
            assertTrue(canvas.lineCount() > 12, "pitch=" + pitch);
            assertTrue(canvas.textValues().stream().anyMatch(value -> value.startsWith("P ")),
                    "pitch=" + pitch);
            assertTrue(canvas.allCoordinatesFinite());
        }
    }

    @Test
    void fullCircleRollMarkerUsesUieBankIndexSign() {
        SixDofHudRenderer.Layout layout = SixDofHudRenderer.layout(800, 450);
        SixDofHudRenderer.Point zero = layout.rollMarker(0.0);
        SixDofHudRenderer.Point quarter = layout.rollMarker(90.0);
        SixDofHudRenderer.Point inverted = layout.rollMarker(180.0);
        SixDofHudRenderer.Point threeQuarter = layout.rollMarker(270.0);

        assertEquals(layout.centerX(), zero.x(), 0.001F);
        assertTrue(zero.y() < layout.centerY());
        assertTrue(quarter.x() > layout.centerX());
        assertEquals(layout.centerY(), quarter.y(), 0.001F);
        assertTrue(inverted.y() > layout.centerY());
        assertTrue(threeQuarter.x() < layout.centerX());

        RecordingCanvas canvas = draw(frame(0.0, 0.0, 90.0), 800, 450);
        assertTrue(canvas.events().stream().anyMatch(event -> event.startsWith("circle")));
        assertTrue(canvas.events().stream().anyMatch(event -> event.startsWith("diamond")));
    }

    @Test
    void worldHeadingHandlesTheNorthZeroSeamWithoutGravityClaims() {
        RecordingCanvas beforeSeam = draw(frame(0.0, -181.0, 0.0), 854, 480);
        RecordingCanvas afterSeam = draw(frame(0.0, 179.0, 0.0), 854, 480);

        assertEquals(beforeSeam.events(), afterSeam.events());
        assertTrue(beforeSeam.textValues().contains("WORLD HDG 359°"));
        assertFalse(beforeSeam.textValues().stream().anyMatch(value -> value.contains("HORIZON")
                || value.contains("GRAVITY") || value.contains("LEVEL")));
    }

    @Test
    void signedLadderMarkersFollowKnownWrap360PitchPositions() {
        SixDofHudRenderer.Layout layout = SixDofHudRenderer.layout(640, 360);
        RecordingCanvas level = draw(frame(0.0, 0.0, 0.0), 640, 360);
        assertTrue(level.hasHorizontalLineAt(layout.centerY() - 10.0F * layout.pitchPixelsPerDegree()));
        assertTrue(level.hasHorizontalLineAt(layout.centerY() + 10.0F * layout.pitchPixelsPerDegree()));
        assertTrue(level.textValues().contains("+10"));
        assertTrue(level.textValues().contains("-10"));

        RecordingCanvas pitched = draw(frame(10.0, 0.0, 0.0), 640, 360);
        assertTrue(pitched.hasHorizontalLineAt(layout.centerY()));
        assertTrue(pitched.textValues().contains("-10"));
        RecordingCanvas inverted = draw(frame(180.0, 0.0, 0.0), 640, 360);
        assertTrue(inverted.textValues().contains("P +180.0°  R +0.0°"));
        assertTrue(inverted.textValues().contains("-180"));
    }

    @Test
    void allGearsAndUnitsUseRootLocaleAndExposeOnlyRequestedState() {
        Locale oldLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            for (int gear = 1; gear <= 9; gear++) {
                int rollGear = Math.min(gear, 5);
                RecordingCanvas canvas = draw(new SixDofHudFrame(
                        new SixDofHudFrame.Attitude(0.0, 0.0, 0.0), 12.5, true,
                        SixDofHudFrame.ThrustInput.BOOST_AND_DRIFT,
                        new SixDofHudFrame.Boost(gear, 9, 11.5 + gear),
                        new SixDofHudFrame.Roll(rollGear, 45.0 * rollGear, -12.5, 0.0),
                        alignment(false, SixDofAlignmentInput.Reference.Y_POS)),
                        640, 360);
                assertTrue(canvas.textValues().contains("BOOST " + gear + "/9"));
                assertTrue(canvas.textValues().contains("TARGET " + (11.5 + gear) + " m/s"));
                assertTrue(canvas.textValues().contains("ROLL " + rollGear + "/5"));
                assertTrue(canvas.textValues().contains("OMEGA -12.5 dps"));
                assertTrue(canvas.textValues().contains("MAX " + (45.0 * rollGear) + " dps"));
                assertTrue(canvas.textValues().contains("SPEED 12.5 m/s"));
                assertTrue(canvas.textValues().stream().anyMatch(value -> value.contains("dps")));
                assertTrue(canvas.textValues().contains("THRUST INPUT"));
                assertTrue(canvas.textValues().contains("BOOST + DRIFT"));
                assertFalse(canvas.textValues().stream().anyMatch(value -> value.contains(",")));
            }
        } finally {
            Locale.setDefault(oldLocale);
        }
    }

    @Test
    void thrustStatusDistinguishesIdleCoastAndEveryRequestedInput() {
        assertTrue(draw(frame(0.0, 0.0, 0.0), 640, 360).textValues()
                .contains("COAST"));
        SixDofHudFrame idle = new SixDofHudFrame(new SixDofHudFrame.Attitude(0.0, 0.0, 0.0),
                0.0, false, SixDofHudFrame.ThrustInput.NONE,
                new SixDofHudFrame.Boost(1, 9, 0.0), new SixDofHudFrame.Roll(1, 0.0, 0.0, 0.0),
                alignment(false, SixDofAlignmentInput.Reference.Y_POS));
        assertTrue(draw(idle, 640, 360).textValues().contains("IDLE"));
        assertTrue(draw(withInput(SixDofHudFrame.ThrustInput.DRIFT), 640, 360).textValues()
                .contains("DRIFT"));
        assertTrue(draw(withInput(SixDofHudFrame.ThrustInput.BOOST), 640, 360).textValues()
                .contains("BOOST"));
        assertTrue(draw(withInput(SixDofHudFrame.ThrustInput.BOOST_AND_DRIFT), 640, 360)
                .textValues().contains("BOOST + DRIFT"));
        assertTrue(draw(idle, 640, 360).textValues().contains("DRIFT: LOCKED"));
    }

    @Test
    void restrictedCarrierShowsSelectedGearAndEffectiveTargetWithoutClaimingGIsOff() {
        SixDofHudFrame frame = new SixDofHudFrame(
                new SixDofHudFrame.Attitude(0.0, 0.0, 0.0), 448.0, true,
                SixDofHudFrame.ThrustInput.BOOST,
                new SixDofHudFrame.Boost(9, 9, 490.0, 70.0, 448.0, true),
                new SixDofHudFrame.Roll(5, 360.0, 0.0, 0.0),
                alignment(false, SixDofAlignmentInput.Reference.Y_POS));
        RecordingCanvas canvas = draw(frame, 854, 480);
        assertTrue(canvas.textValues().contains("BOOST 9/9"));
        assertTrue(canvas.textValues().contains("SELECT 490.0 m/s"));
        assertTrue(canvas.textValues().contains("TARGET 70.0 m/s"));
        assertTrue(canvas.textValues().contains("CARRY R 448.0 m/s"));
        assertTrue(canvas.textValues().contains("DRIFT: ENABLED"));
    }

    @Test
    void frameIsImmutableAndRepeatedDrawIsDeterministic() {
        SixDofHudFrame frame = frame(41.0, 122.0, -33.0);
        RecordingCanvas first = draw(frame, 1024, 576);
        RecordingCanvas second = draw(frame, 1024, 576);

        assertEquals(first.events(), second.events());
        assertEquals(41.0, frame.attitude().pitchDegrees());
        assertEquals(122.0, frame.attitude().yawDegrees());
        assertEquals(-33.0, frame.attitude().rollDegrees());
    }

    @Test
    void invalidProgrammerInputsFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Attitude(Double.NaN, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Boost(0, 9, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Boost(1, 0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Boost(10, 9, 490.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Roll(1, -1.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame.Roll(1, 1.0, 0.0, 1.01));
        assertThrows(IllegalArgumentException.class,
                () -> new SixDofHudFrame(frame(0.0, 0.0, 0.0).attitude(), -0.01, false,
                        SixDofHudFrame.ThrustInput.NONE, new SixDofHudFrame.Boost(1, 9, 0.0),
                        new SixDofHudFrame.Roll(1, 0.0, 0.0, 0.0),
                        alignment(false, SixDofAlignmentInput.Reference.Y_POS)));
        assertThrows(NullPointerException.class,
                () -> new SixDofHudFrame.Alignment(true, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new SixDofHudFrame.Alignment(true,
                SixDofAlignmentInput.Reference.Y_POS, false, SixDofAlignmentInput.Reference.X_POS));
        assertThrows(IllegalArgumentException.class, () -> SixDofHudRenderer.layout(0, 240));
    }

    @Test
    void responsiveViewportsKeepTheCenterAndHotbarSafetyAreaClear() {
        for (int[] viewport : List.of(new int[] {320, 240}, new int[] {854, 480},
                new int[] {3440, 1440})) {
            int width = viewport[0];
            int height = viewport[1];
            RecordingCanvas canvas = draw(frame(22.0, 87.0, 19.0), width, height);
            SixDofHudRenderer.Layout layout = SixDofHudRenderer.layout(width, height);
            assertTrue(canvas.allCoordinatesFinite());
            assertTrue(canvas.maxDrawY() < layout.bottomSafeTop());
            assertFalse(canvas.drawsInside(layout.centerX() - 14.0F, layout.centerY() - 14.0F,
                    layout.centerX() + 14.0F, layout.centerY() + 14.0F));
        }
    }

    @Test
    void panelTextFitsAtSmallNormalAndUltrawideGuiSizes() {
        SixDofHudFrame data = new SixDofHudFrame(new SixDofHudFrame.Attitude(0.0, 0.0, 0.0),
                500.0, true, SixDofHudFrame.ThrustInput.BOOST_AND_DRIFT,
                new SixDofHudFrame.Boost(5, 9, 40.0), new SixDofHudFrame.Roll(5, 360.0, -360.0, 1.0),
                alignment(true, SixDofAlignmentInput.Reference.Z_NEG));
        for (int[] size : List.of(new int[] {320, 240}, new int[] {854, 480},
                new int[] {1720, 720})) {
            SixDofHudRenderer.Layout layout = SixDofHudRenderer.layout(size[0], size[1]);
            RecordingCanvas canvas = draw(data, size[0], size[1]);
            int panelLabels = 0;
            for (RecordingCanvas.TextBox text : canvas.textBoxes) {
                if (text.y() < layout.panelTop()
                        || text.clip() != null && text.clip().bottom() <= layout.panelTop()) continue;
                panelLabels++;
                float panelLeft = text.x() < layout.centerX() ? layout.margin()
                        : size[0] - layout.margin() - layout.panelWidth();
                assertTrue(text.x() >= panelLeft + 2.0F, text.value());
                assertTrue(text.x() + text.width() <= panelLeft + layout.panelWidth() - 2.0F,
                        text.value() + " exceeds panel at " + size[0]);
                assertTrue(text.y() + text.height() <= layout.panelTop() + layout.panelHeight(),
                        text.value() + " exceeds panel height at " + size[0]);
            }
            assertEquals(9, panelLabels, "all side-panel readouts must be checked");
        }
    }

    @Test
    void alignmentStatusRendersEveryWorldReferenceOnAndOffAcrossGuiSizes() {
        for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
            for (boolean enabled : List.of(false, true)) {
                SixDofHudFrame data = withAlignment(frame(17.0, -43.0, 28.0), enabled, reference);
                String expected = "ALIGN " + (enabled ? "ON " : "OFF ") + reference.label();
                for (int[] size : List.of(new int[] {320, 240}, new int[] {854, 480},
                        new int[] {1720, 720})) {
                    RecordingCanvas canvas = draw(data, size[0], size[1]);
                    assertTrue(canvas.textValues().contains(expected), expected + " at " + size[0]);
                }
            }
        }
    }

    @Test
    void selectingStatusRendersNoHitAndCandidateWithoutChangingCommittedReference() {
        SixDofHudFrame base = frame(17.0, -43.0, 28.0);
        SixDofHudFrame noHit = withAlignment(base, false, SixDofAlignmentInput.Reference.Y_NEG,
                true, null);
        SixDofHudFrame candidate = withAlignment(base, true, SixDofAlignmentInput.Reference.Y_NEG,
                true, SixDofAlignmentInput.Reference.Z_POS);

        for (int[] size : List.of(new int[] {320, 240}, new int[] {854, 480},
                new int[] {1720, 720})) {
            assertTrue(draw(noHit, size[0], size[1]).textValues().contains("SELECT --"));
            assertTrue(draw(candidate, size[0], size[1]).textValues().contains("SELECT Z+"));
        }
        assertEquals(SixDofAlignmentInput.Reference.Y_NEG, candidate.alignment().reference());
    }

    @Test
    void clipCallbacksExecuteAndTheirBoundsAreIntersected() {
        RecordingCanvas canvas = draw(frame(0.0, 0.0, 0.0), 320, 240);

        assertTrue(canvas.clipCalls() >= 3);
        assertTrue(canvas.clips().stream().allMatch(RecordingCanvas.Clip::isIntersection));
        assertTrue(canvas.events().stream().anyMatch(event -> event.startsWith("line")));
        assertNotEquals(0, canvas.textWidthCalls());
    }

    @Test
    void writesInspectableHeadlessJava2dPreviewsFromTheProductionRenderer() throws IOException {
        Path reportDirectory = Path.of("build", "reports", "hud");
        Files.createDirectories(reportDirectory);
        writePreview(reportDirectory.resolve("six-dof-hud-320x240.png"),
                withAlignment(frame(72.0, 359.0, 90.0), true, SixDofAlignmentInput.Reference.Y_POS),
                320, 240);
        writePreview(reportDirectory.resolve("six-dof-hud-854x480.png"),
                withAlignment(frame(33.0, -45.0, 61.0), false, SixDofAlignmentInput.Reference.X_NEG),
                854, 480);
        writePreview(reportDirectory.resolve("six-dof-hud-ultrawide.png"),
                withAlignment(frame(-135.0, 181.0, -145.0), true,
                        SixDofAlignmentInput.Reference.Z_NEG), 1720, 720);

        assertTrue(Files.size(reportDirectory.resolve("six-dof-hud-320x240.png")) > 500L);
        assertTrue(Files.size(reportDirectory.resolve("six-dof-hud-854x480.png")) > 500L);
        assertTrue(Files.size(reportDirectory.resolve("six-dof-hud-ultrawide.png")) > 500L);
    }

    private static void writePreview(Path target, SixDofHudFrame frame, int width, int height)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0xFF071116, true));
            graphics.fillRect(0, 0, width, height);
            SixDofHudRenderer.draw(new Java2dCanvas(graphics), frame, width, height);
        } finally {
            graphics.dispose();
        }
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }

    private static RecordingCanvas draw(SixDofHudFrame frame, int width, int height) {
        RecordingCanvas canvas = new RecordingCanvas();
        assertDoesNotThrow(() -> SixDofHudRenderer.draw(canvas, frame, width, height));
        return canvas;
    }

    private static SixDofHudFrame frame(double pitch, double yaw, double roll) {
        return new SixDofHudFrame(new SixDofHudFrame.Attitude(pitch, yaw, roll), 12.5, true,
                SixDofHudFrame.ThrustInput.NONE, new SixDofHudFrame.Boost(3, 9, 24.0),
                new SixDofHudFrame.Roll(2, 180.0, 37.5, 0.25),
                alignment(false, SixDofAlignmentInput.Reference.Y_POS));
    }

    private static SixDofHudFrame withInput(SixDofHudFrame.ThrustInput input) {
        SixDofHudFrame base = frame(0.0, 0.0, 0.0);
        return new SixDofHudFrame(base.attitude(), base.speedMps(), base.driftAuthorized(), input,
                base.boost(), base.roll(), base.alignment());
    }

    private static SixDofHudFrame withAlignment(SixDofHudFrame base, boolean enabled,
            SixDofAlignmentInput.Reference reference) {
        return withAlignment(base, enabled, reference, false, null);
    }

    private static SixDofHudFrame withAlignment(SixDofHudFrame base, boolean enabled,
            SixDofAlignmentInput.Reference reference, boolean selecting,
            SixDofAlignmentInput.Reference previewReference) {
        return new SixDofHudFrame(base.attitude(), base.speedMps(), base.driftAuthorized(),
                base.thrustInput(), base.boost(), base.roll(),
                alignment(enabled, reference, selecting, previewReference));
    }

    private static SixDofHudFrame.Alignment alignment(boolean enabled,
            SixDofAlignmentInput.Reference reference) {
        return alignment(enabled, reference, false, null);
    }

    private static SixDofHudFrame.Alignment alignment(boolean enabled,
            SixDofAlignmentInput.Reference reference, boolean selecting,
            SixDofAlignmentInput.Reference previewReference) {
        return new SixDofHudFrame.Alignment(enabled, reference, selecting, previewReference);
    }

    private static class RecordingCanvas implements FlightHudCanvas {
        private final List<String> events = new ArrayList<>();
        private final List<Clip> clips = new ArrayList<>();
        private final List<TextBox> textBoxes = new ArrayList<>();
        private final Deque<Rect> clipStack = new ArrayDeque<>();
        private float maxDrawY = Float.NEGATIVE_INFINITY;
        private int textWidthCalls;

        List<String> events() {
            return List.copyOf(events);
        }

        List<String> textValues() {
            return events.stream().filter(event -> event.startsWith("text:"))
                    .map(event -> event.substring(5, event.indexOf('|'))).toList();
        }

        List<Clip> clips() {
            return List.copyOf(clips);
        }

        int clipCalls() {
            return clips.size();
        }

        int textWidthCalls() {
            return textWidthCalls;
        }

        int lineCount() {
            return (int) events.stream().filter(event -> event.startsWith("line")).count();
        }

        float maxDrawY() {
            return maxDrawY;
        }

        boolean allCoordinatesFinite() {
            return events.stream().noneMatch(event -> event.contains("NaN") || event.contains("Infinity"));
        }

        boolean drawsInside(float left, float top, float right, float bottom) {
            return events.stream().filter(event -> event.contains("|")
                    && !event.startsWith("circle") && !event.startsWith("arc")).anyMatch(event -> {
                String[] values = event.substring(event.indexOf('|') + 1).split(",");
                for (int index = 0; index + 1 < values.length; index += 2) {
                    float x = Float.parseFloat(values[index]);
                    float y = Float.parseFloat(values[index + 1]);
                    if (x >= left && x <= right && y >= top && y <= bottom) {
                        return true;
                    }
                }
                return false;
            });
        }

        boolean hasHorizontalLineAt(float y) {
            return events.stream().filter(event -> event.startsWith("line|")).anyMatch(event -> {
                String[] values = event.substring(5).split(",");
                return values.length == 4 && Math.abs(Float.parseFloat(values[1]) - y) < 0.01F
                        && Math.abs(Float.parseFloat(values[3]) - y) < 0.01F;
            });
        }

        @Override
        public void line(float x1, float y1, float x2, float y2, int color, float width) {
            add("line", x1, y1, x2, y2);
        }

        @Override
        public void polyline(float[] points, int color, float width) {
            add("polyline", points);
        }

        @Override
        public void outline(float left, float top, float right, float bottom, int color,
                float width) {
            add("outline", left, top, right, bottom);
        }

        @Override
        public void fill(float left, float top, float right, float bottom, int color) {
            add("fill", left, top, right, bottom);
        }

        @Override
        public void triangle(float tipX, float tipY, float directionX, float directionY,
                float size, int color, float width) {
            add("triangle", tipX, tipY);
        }

        @Override
        public void diamond(float x, float y, float size, int color, float width) {
            add("diamond", x, y);
        }

        @Override
        public void circle(float x, float y, float radius, int color, float width,
                int segments) {
            add("circle", x, y);
        }

        @Override
        public void arc(float x, float y, float radius, double startDegrees, double endDegrees,
                int color, float width, int segments) {
            add("arc", x, y);
        }

        @Override
        public void text(String value, float x, float y, float scale, int color, int haloColor) {
            textBoxes.add(new TextBox(value, x, y, textWidth(value, scale), 9.0F * scale,
                    clipStack.peek()));
            addText(value, x, y);
        }

        @Override
        public void centeredText(String value, float centerX, float y, float scale, int color,
                int haloColor) {
            float width = textWidth(value, scale);
            textBoxes.add(new TextBox(value, centerX - width * 0.5F, y, width, 9.0F * scale,
                    clipStack.peek()));
            addText(value, centerX, y);
        }

        @Override
        public float textWidth(String value, float scale) {
            textWidthCalls++;
            return value.length() * 7.0F * scale;
        }

        @Override
        public void clip(float left, float top, float right, float bottom, Runnable draw) {
            Rect requested = new Rect(left, top, right, bottom);
            Rect parent = clipStack.peek();
            Rect intersection = parent == null ? requested : parent.intersection(requested);
            clips.add(new Clip(requested, intersection));
            clipStack.push(intersection);
            try {
                draw.run();
            } finally {
                clipStack.pop();
            }
        }

        private void addText(String value, float x, float y) {
            events.add("text:" + value + "|" + x + ',' + y);
            register(y);
        }

        private void add(String name, float... coordinates) {
            StringBuilder event = new StringBuilder(name).append('|');
            for (int index = 0; index < coordinates.length; index++) {
                if (index > 0) {
                    event.append(',');
                }
                event.append(coordinates[index]);
                if (index % 2 == 1) {
                    register(coordinates[index]);
                }
            }
            events.add(event.toString());
        }

        private void register(float y) {
            maxDrawY = Math.max(maxDrawY, y);
        }

        record TextBox(String value, float x, float y, float width, float height, Rect clip) {
        }

        record Rect(float left, float top, float right, float bottom) {
            Rect intersection(Rect other) {
                return new Rect(Math.max(left, other.left), Math.max(top, other.top),
                        Math.min(right, other.right), Math.min(bottom, other.bottom));
            }
        }

        record Clip(Rect requested, Rect intersection) {
            boolean isIntersection() {
                return intersection.left() >= requested.left()
                        && intersection.top() >= requested.top()
                        && intersection.right() <= requested.right()
                        && intersection.bottom() <= requested.bottom();
            }
        }
    }

    private static final class Java2dCanvas implements FlightHudCanvas {
        private final Graphics2D graphics;

        private Java2dCanvas(Graphics2D graphics) {
            this.graphics = graphics;
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
        }

        @Override
        public void line(float x1, float y1, float x2, float y2, int color, float width) {
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
        }

        @Override
        public void polyline(float[] points, int color, float width) {
            for (int index = 2; index < points.length; index += 2) {
                line(points[index - 2], points[index - 1], points[index], points[index + 1],
                        color, width);
            }
        }

        @Override
        public void outline(float left, float top, float right, float bottom, int color,
                float width) {
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.drawRect(Math.round(left), Math.round(top), Math.round(right - left),
                    Math.round(bottom - top));
        }

        @Override
        public void fill(float left, float top, float right, float bottom, int color) {
            graphics.setColor(new Color(color, true));
            graphics.fillRect(Math.round(left), Math.round(top), Math.round(right - left),
                    Math.round(bottom - top));
        }

        @Override
        public void triangle(float tipX, float tipY, float directionX, float directionY,
                float size, int color, float width) {
            float perpendicularX = -directionY * size * 0.6F;
            float perpendicularY = directionX * size * 0.6F;
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.drawPolygon(new int[] {Math.round(tipX), Math.round(tipX - directionX * size
                    + perpendicularX), Math.round(tipX - directionX * size - perpendicularX)},
                    new int[] {Math.round(tipY), Math.round(tipY - directionY * size + perpendicularY),
                            Math.round(tipY - directionY * size - perpendicularY)}, 3);
        }

        @Override
        public void diamond(float x, float y, float size, int color, float width) {
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.drawPolygon(new int[] {Math.round(x), Math.round(x + size), Math.round(x),
                    Math.round(x - size)}, new int[] {Math.round(y - size), Math.round(y),
                            Math.round(y + size), Math.round(y)}, 4);
        }

        @Override
        public void circle(float x, float y, float radius, int color, float width, int segments) {
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.drawOval(Math.round(x - radius), Math.round(y - radius),
                    Math.round(radius * 2.0F), Math.round(radius * 2.0F));
        }

        @Override
        public void arc(float x, float y, float radius, double startDegrees, double endDegrees,
                int color, float width, int segments) {
            graphics.setColor(new Color(color, true));
            graphics.setStroke(new BasicStroke(width));
            graphics.draw(new Arc2D.Float(x - radius, y - radius, radius * 2.0F, radius * 2.0F,
                    (float) startDegrees, (float) (endDegrees - startDegrees), Arc2D.OPEN));
        }

        @Override
        public void text(String value, float x, float y, float scale, int color, int haloColor) {
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 12.0F * scale));
            graphics.setColor(new Color(color, true));
            graphics.drawString(value, x, y + graphics.getFontMetrics().getAscent());
        }

        @Override
        public void centeredText(String value, float centerX, float y, float scale, int color,
                int haloColor) {
            text(value, centerX - textWidth(value, scale) * 0.5F, y, scale, color, haloColor);
        }

        @Override
        public float textWidth(String value, float scale) {
            graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN, 12.0F * scale));
            return graphics.getFontMetrics().stringWidth(value);
        }

        @Override
        public void clip(float left, float top, float right, float bottom, Runnable draw) {
            Shape oldClip = graphics.getClip();
            graphics.clipRect(Math.round(left), Math.round(top), Math.round(right - left),
                    Math.round(bottom - top));
            try {
                draw.run();
            } finally {
                graphics.setClip(oldClip);
            }
        }
    }
}
