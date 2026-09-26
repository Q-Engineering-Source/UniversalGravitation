package wi.unigrav.core.client.camera;

import java.util.Locale;
import java.util.Objects;

import neofontrender.addons.api.flight.FlightHudCanvas;

/** Pure immediate-mode presentation of the FREE six-DOF control state. */
final class SixDofHudRenderer {

    private static final int PRIMARY = 0xFF70FFD1;
    private static final int SECONDARY = 0xFFC0FFE8;
    private static final int ACCENT = 0xFF70D7FF;
    private static final int PANEL = 0xB8142730;
    private static final int HALO = 0xD8001014;
    private static final float STROKE = 1.0F;

    private SixDofHudRenderer() {
    }

    static void draw(FlightHudCanvas canvas, SixDofHudFrame frame, int screenWidth,
            int screenHeight) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(frame, "frame");
        Layout layout = layout(screenWidth, screenHeight);
        canvas.clip(0.0F, 0.0F, screenWidth, layout.bottomSafeTop(), () -> {
            drawHeading(canvas, frame, layout);
            drawAttitudeReadout(canvas, frame, layout);
            drawPitchLadder(canvas, frame, layout);
            drawRollIndicator(canvas, frame, layout);
            drawInformationPanels(canvas, frame, layout);
        });
    }

    private static void drawAttitudeReadout(FlightHudCanvas canvas, SixDofHudFrame frame,
            Layout layout) {
        String attitude = String.format(Locale.ROOT, "P %+.1f°  R %+.1f°",
                signed180(frame.attitude().pitchDegrees()),
                signed180(frame.attitude().rollDegrees()));
        fittedCenteredText(canvas, attitude, layout.centerX(), layout.headingBottom() + 3.0F,
                responsiveScale(layout, 0.70F), ACCENT,
                HALO, layout.ladderHalfWidth() * 1.8F);
    }

    /** Visible layout seam used by the renderer behavior tests. */
    static Layout layout(int screenWidth, int screenHeight) {
        if (screenWidth < 1 || screenHeight < 1) {
            throw new IllegalArgumentException("screen dimensions must be positive");
        }
        float width = screenWidth;
        float height = screenHeight;
        float margin = Math.min(Math.max(0.5F, Math.min(14.0F, width * 0.018F)),
                width * 0.25F);
        float bottomSafe = Math.min(Math.max(0.5F, Math.min(84.0F, height * 0.18F)),
                height * 0.30F);
        float bottomSafeTop = height - bottomSafe;
        float panelHeight = Math.min(Math.max(1.0F, Math.min(96.0F,
                        Math.max(52.0F, height * 0.14F))),
                Math.max(1.0F, bottomSafeTop - margin));
        float bottomGap = Math.min(2.0F, bottomSafeTop * 0.10F);
        float panelTop = Math.max(0.0F, height - bottomSafe - panelHeight - bottomGap);
        float panelWidth = Math.min(Math.min(176.0F, width * 0.32F),
                Math.max(0.25F, (width - margin * 2.0F) * 0.49F));
        float headingTop = Math.min(margin, Math.max(0.0F, bottomSafeTop - 1.0F));
        float headingHeight = Math.min(Math.max(1.0F, Math.min(64.0F,
                        Math.max(36.0F, height * 0.08F))),
                Math.max(1.0F, bottomSafeTop - headingTop));
        float headingBottom = Math.min(bottomSafeTop, headingTop + headingHeight);
        float centerX = width * 0.5F;
        float centerY = clamp(height * 0.50F, headingBottom + 1.0F,
                Math.max(headingBottom + 1.0F, panelTop - 1.0F));
        float ladderHalfWidth = Math.max(1.0F, Math.min(270.0F, width * 0.16F));
        float ladderHalfHeight = Math.max(1.0F, Math.min(150.0F, height * 0.27F));
        float pitchPixelsPerDegree = Math.max(0.72F, Math.min(3.3F, height / 150.0F));
        float rollRadius = Math.max(1.0F, Math.min(ladderHalfWidth * 0.78F,
                Math.min(height * 0.16F, 72.0F)));
        return new Layout(width, height, centerX, centerY, ladderHalfWidth, ladderHalfHeight,
                pitchPixelsPerDegree, rollRadius, margin, headingTop, headingBottom,
                panelTop, panelHeight, panelWidth, bottomSafeTop);
    }

    private static void drawHeading(FlightHudCanvas canvas, SixDofHudFrame frame,
            Layout layout) {
        float left = layout.margin();
        float right = layout.screenWidth() - layout.margin();
        canvas.fill(left, layout.headingTop(), right, layout.headingBottom(), PANEL);
        canvas.outline(left, layout.headingTop(), right, layout.headingBottom(), ACCENT,
                STROKE);

        // Minecraft yaw 0 faces world south, so its UIE world-compass heading is yaw + 180.
        double heading = wrap360(frame.attitude().yawDegrees() + 180.0);
        String title = String.format(Locale.ROOT, "WORLD HDG %03.0f°", heading);
        fittedCenteredText(canvas, title, layout.centerX(), layout.headingTop() + 3.0F,
                responsiveScale(layout, 0.72F),
                SECONDARY, HALO, right - left - 10.0F);

        float tickY = layout.headingBottom() - 5.0F;
        float rangePixels = (right - left) * 0.42F;
        float pixelsPerDegree = rangePixels / 80.0F;
        float tickTop = layout.headingTop() + Math.min(13.0F,
                Math.max(0.0F, (layout.headingBottom() - layout.headingTop()) * 0.5F));
        float tickBottom = layout.headingBottom() - Math.min(2.0F,
                Math.max(0.0F, (layout.headingBottom() - layout.headingTop()) * 0.1F));
        if (right - left > 4.0F && tickBottom >= tickTop) {
            canvas.clip(left + 1.0F, tickTop, right - 1.0F, tickBottom, () -> {
                    for (int marker = 0; marker < 360; marker += 5) {
                        double delta = wrap180(marker - heading);
                        if (Math.abs(delta) > 84.0) {
                            continue;
                        }
                        float x = layout.centerX() + (float) delta * pixelsPerDegree;
                        boolean major = marker % 15 == 0;
                        float length = major ? 7.0F : 4.0F;
                        canvas.line(x, tickY, x, tickY - length, PRIMARY, STROKE);
                        if (marker % 45 == 0 && layout.screenHeight() >= 420.0F) {
                            fittedCenteredText(canvas, String.format(Locale.ROOT, "%03d", marker),
                                    x, layout.headingBottom() - responsiveScale(layout, 0.65F) * 15.0F,
                                    responsiveScale(layout, 0.65F), PRIMARY, HALO,
                                    27.0F);
                        }
                    }
                });
        }
        canvas.triangle(layout.centerX(), tickY - 8.0F, 0.0F, 1.0F, 3.5F, ACCENT,
                STROKE);
    }

    private static void drawPitchLadder(FlightHudCanvas canvas, SixDofHudFrame frame,
            Layout layout) {
        float left = layout.centerX() - layout.ladderHalfWidth();
        float top = Math.max(layout.centerY() - layout.ladderHalfHeight(),
                layout.headingBottom() + statusClearance(layout));
        float right = layout.centerX() + layout.ladderHalfWidth();
        float bottom = Math.min(layout.centerY() + layout.ladderHalfHeight(),
                layout.panelTop() - 4.0F);
        double pitch = frame.attitude().pitchDegrees();
        double rollRadians = Math.toRadians(wrap180(-frame.attitude().rollDegrees()));
        float cosine = (float) Math.cos(rollRadians);
        float sine = (float) Math.sin(rollRadians);
        float gap = Math.max(23.0F, layout.ladderHalfWidth() * 0.39F);

        canvas.clip(left, top, right, Math.max(top + 1.0F, bottom), () -> {
            for (int marker = -180; marker < 180; marker += 5) {
                // UIE's documented WRAP_360 convention: markerDelta = wrap180(marker + MinecraftPitch).
                double markerDelta = wrap180(marker + pitch);
                float localY = (float) -markerDelta * layout.pitchPixelsPerDegree();
                if (Math.abs(localY) > layout.ladderHalfHeight() + 7.0F) {
                    continue;
                }
                boolean major = marker % 10 == 0;
                float length = major ? layout.ladderHalfWidth() * 0.42F
                        : layout.ladderHalfWidth() * 0.24F;
                boolean zeroReference = marker == 0;
                boolean antipodalReference = marker == -180;
                if (zeroReference) {
                    length = layout.ladderHalfWidth() * 0.62F;
                } else if (antipodalReference) {
                    length = layout.ladderHalfWidth() * 0.52F;
                }
                int color = zeroReference ? PRIMARY : antipodalReference ? ACCENT
                        : major ? PRIMARY : SECONDARY;
                rotatedLine(canvas, layout, -gap - length, localY, -gap, localY,
                        cosine, sine, color, major ? STROKE * 1.15F : STROKE);
                rotatedLine(canvas, layout, gap, localY, gap + length, localY,
                        cosine, sine, color, major ? STROKE * 1.15F : STROKE);
                if (major && marker != 0 && Math.abs(localY) < layout.ladderHalfHeight() - 8.0F) {
                    String label = String.format(Locale.ROOT, "%+d", marker);
                    Point labelPoint = layout.rotate(-gap - length - 9.0F, localY - 3.0F,
                            cosine, sine);
                    fittedCenteredText(canvas, label, labelPoint.x(), labelPoint.y(),
                            responsiveScale(layout, 0.65F), color, HALO, 34.0F);
                }
            }
        });
    }

    private static void drawRollIndicator(FlightHudCanvas canvas, SixDofHudFrame frame,
            Layout layout) {
        float radius = layout.rollRadius();
        canvas.circle(layout.centerX(), layout.centerY(), radius, ACCENT, STROKE, 48);
        for (int degrees = -60; degrees <= 60; degrees += 15) {
            double angle = Math.toRadians(degrees - 90.0);
            float outerX = layout.centerX() + (float) Math.cos(angle) * radius;
            float outerY = layout.centerY() + (float) Math.sin(angle) * radius;
            float innerRadius = radius - (degrees % 30 == 0 ? 6.0F : 3.0F);
            float innerX = layout.centerX() + (float) Math.cos(angle) * innerRadius;
            float innerY = layout.centerY() + (float) Math.sin(angle) * innerRadius;
            canvas.line(innerX, innerY, outerX, outerY, PRIMARY, STROKE);
        }
        canvas.triangle(layout.centerX(), layout.centerY() - radius + 2.0F, 0.0F, 1.0F,
                4.0F, PRIMARY, STROKE);
        Point marker = layout.rollMarker(frame.attitude().rollDegrees());
        canvas.diamond(marker.x(), marker.y(), 3.5F, ACCENT, STROKE * 1.2F);
    }

    private static void drawInformationPanels(FlightHudCanvas canvas, SixDofHudFrame frame,
            Layout layout) {
        float left = layout.margin();
        float right = layout.screenWidth() - layout.margin() - layout.panelWidth();
        drawPanel(canvas, left, layout.panelTop(), layout.panelWidth(), layout.panelHeight());
        drawPanel(canvas, right, layout.panelTop(), layout.panelWidth(), layout.panelHeight());

        float lineOne = layout.panelTop() + 4.0F;
        float leftStep = Math.max(10.0F, layout.panelHeight() * 0.23F);
        float lineTwo = lineOne + leftStep;
        float lineThree = lineOne + leftStep * 2.0F;
        float lineFour = lineOne + leftStep * 3.0F;
        float inner = layout.panelWidth() - 8.0F;
        float scale = responsiveScale(layout, 0.70F);
        fittedText(canvas, "FREE", left + 4.0F, lineOne, scale, PRIMARY, HALO, inner);
        fittedText(canvas, String.format(Locale.ROOT, "SPEED %.1f m/s", frame.speedMps()),
                left + 4.0F, lineTwo, scale, SECONDARY, HALO, inner);
        fittedText(canvas, "DRIFT: "
                        + (frame.driftAuthorized() ? "ENABLED" : "LOCKED"),
                left + 4.0F, lineThree, scale, frame.driftAuthorized() ? PRIMARY : ACCENT,
                HALO, inner);
        fittedText(canvas, alignmentStatus(frame.alignment()),
                left + 4.0F, lineFour, scale,
                frame.alignment().selecting() || frame.alignment().enabled() ? ACCENT : SECONDARY,
                HALO, inner);

        float rightStep = Math.max(9.0F, layout.panelHeight() * 0.16F);
        fittedText(canvas, String.format(Locale.ROOT, "BOOST %d/%d", frame.boost().gear(),
                        frame.boost().gearCount()),
                right + 4.0F, lineOne, scale, PRIMARY, HALO, inner);
        if (frame.boost().restrictedCorridor()) {
            // Corridor restriction limits the effective target, not the selected gear or G state.
            fittedText(canvas, String.format(Locale.ROOT, "SELECT %.1f m/s",
                            frame.boost().selectedSpeedMps()), right + 4.0F, lineOne + rightStep,
                    scale, SECONDARY, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "TARGET %.1f m/s",
                            frame.boost().targetSpeedMps()), right + 4.0F, lineOne + rightStep * 2.0F,
                    scale, PRIMARY, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "CARRY R %.1f m/s",
                            frame.boost().carrierCeilingMps()), right + 4.0F, lineOne + rightStep * 3.0F,
                    scale, ACCENT, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "ROLL %d/5", frame.roll().gear()),
                    right + 4.0F, lineOne + rightStep * 4.0F, scale, PRIMARY, HALO, inner);
        } else {
            fittedText(canvas, String.format(Locale.ROOT, "TARGET %.1f m/s", frame.boost().targetSpeedMps()),
                    right + 4.0F, lineOne + rightStep, scale, SECONDARY, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "ROLL %d/5", frame.roll().gear()),
                    right + 4.0F, lineOne + rightStep * 2.0F, scale, PRIMARY, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "OMEGA %+.1f dps", frame.roll().omegaDps()),
                    right + 4.0F, lineOne + rightStep * 3.0F, scale, ACCENT, HALO, inner);
            fittedText(canvas, String.format(Locale.ROOT, "MAX %.1f dps", frame.roll().maximumDps()),
                    right + 4.0F, lineOne + rightStep * 4.0F, scale, SECONDARY, HALO, inner);
        }

        String thrust = switch (frame.thrustInput()) {
            case NONE -> frame.speedMps() > 0.05 ? "COAST" : "IDLE";
            case DRIFT -> "DRIFT";
            case BOOST -> "BOOST";
            case BOOST_AND_DRIFT -> "BOOST + DRIFT";
        };
        float textLine = responsiveScale(layout, 0.70F) * 14.0F;
        float thrustLabelY = layout.headingBottom() + textLine + 5.0F;
        fittedCenteredText(canvas, "THRUST INPUT", layout.centerX(), thrustLabelY, scale,
                PRIMARY, HALO, layout.ladderHalfWidth() * 1.9F);
        fittedCenteredText(canvas, thrust, layout.centerX(), thrustLabelY + textLine, scale,
                SECONDARY, HALO, layout.ladderHalfWidth() * 1.9F);
    }

    private static void drawPanel(FlightHudCanvas canvas, float left, float top, float width,
            float height) {
        canvas.fill(left, top, left + width, top + height, PANEL);
        canvas.outline(left, top, left + width, top + height, PRIMARY, STROKE);
    }

    private static void rotatedLine(FlightHudCanvas canvas, Layout layout, float x1, float y1,
            float x2, float y2, float cosine, float sine, int color, float width) {
        Point start = layout.rotate(x1, y1, cosine, sine);
        Point end = layout.rotate(x2, y2, cosine, sine);
        canvas.line(start.x(), start.y(), end.x(), end.y(), color, width);
    }

    private static void fittedText(FlightHudCanvas canvas, String value, float x, float y,
            float scale, int color, int halo, float maxWidth) {
        canvas.text(value, x, y, fittedScale(canvas, value, scale, maxWidth), color, halo);
    }

    private static void fittedCenteredText(FlightHudCanvas canvas, String value, float centerX,
            float y, float scale, int color, int halo, float maxWidth) {
        canvas.centeredText(value, centerX, y, fittedScale(canvas, value, scale, maxWidth),
                color, halo);
    }

    private static float fittedScale(FlightHudCanvas canvas, String value, float requested,
            float maxWidth) {
        float measured = canvas.textWidth(value, requested);
        if (measured <= maxWidth || measured <= 0.0F) {
            return requested;
        }
        float minimum = maxWidth >= 75.0F ? 0.65F : 0.31F;
        return Math.max(minimum, requested * maxWidth / measured);
    }

    private static double wrap180(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped == 0.0 ? 0.0 : wrapped;
    }

    private static double wrap360(double degrees) {
        double wrapped = degrees % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }

    private static double signed180(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped > 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped <= -180.0) {
            wrapped += 360.0;
        }
        return wrapped == 0.0 ? 0.0 : wrapped;
    }

    record Layout(float screenWidth, float screenHeight, float centerX, float centerY,
            float ladderHalfWidth, float ladderHalfHeight,
            float pitchPixelsPerDegree, float rollRadius, float margin, float headingTop,
            float headingBottom, float panelTop, float panelHeight, float panelWidth,
            float bottomSafeTop) {
        Point rollMarker(double rollDegrees) {
            // UIE's bank-index marker follows roll - 90; only the conformal ladder rotates -roll.
            double angle = Math.toRadians(wrap180(rollDegrees) - 90.0);
            return new Point(centerX + (float) Math.cos(angle) * rollRadius,
                    centerY + (float) Math.sin(angle) * rollRadius);
        }

        Point rotate(float localX, float localY, float cosine, float sine) {
            return new Point(centerX + localX * cosine - localY * sine,
                    centerY + localX * sine + localY * cosine);
        }
    }

    record Point(float x, float y) {
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float responsiveScale(Layout layout, float minimum) {
        float viewportScale = Math.min(layout.screenWidth() / 854.0F,
                layout.screenHeight() / 480.0F);
        return Math.max(minimum, Math.min(1.45F, viewportScale));
    }

    private static float statusClearance(Layout layout) {
        return responsiveScale(layout, 0.70F) * 42.0F + 4.0F;
    }

    private static String alignmentStatus(SixDofHudFrame.Alignment alignment) {
        if (alignment.selecting()) {
            return "SELECT " + (alignment.previewReference() == null
                    ? "--" : alignment.previewReference().label());
        }
        return "ALIGN " + (alignment.enabled() ? "ON " : "OFF ")
                + alignment.reference().label();
    }
}
