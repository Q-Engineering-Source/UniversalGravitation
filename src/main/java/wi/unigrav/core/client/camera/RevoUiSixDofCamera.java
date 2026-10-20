package wi.unigrav.core.client.camera;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import dev.crlhitbox.api.geometry.Rotation3d;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.input.Keyboard;

import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.camera.CameraEyeAnchor;
import neofontrender.addons.api.camera.CameraEyeAnchorContext;
import neofontrender.addons.api.camera.CameraEyeAnchorProvider;
import neofontrender.addons.api.camera.CameraRegistration;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import neofontrender.addons.api.flight.FlightCameraTracking;
import neofontrender.addons.api.flight.FlightCapability;
import neofontrender.addons.api.flight.FlightDecision;
import neofontrender.addons.api.flight.FlightEulerAngles;
import neofontrender.addons.api.flight.FlightManeuverInput;
import neofontrender.addons.api.flight.FlightRegistration;
import neofontrender.addons.api.flight.FlightRenderPose;
import neofontrender.addons.api.flight.FlightRenderDiagnostics;
import neofontrender.addons.api.input.CameraMouseInputEvent;
import neofontrender.addons.api.input.InputAction;
import neofontrender.addons.api.input.InputApi;
import neofontrender.addons.api.input.InputContext;
import neofontrender.addons.api.input.InputFrame;
import neofontrender.addons.api.input.InputFrameContext;
import neofontrender.addons.api.input.InputRegistration;

import wi.unigrav.Reference;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.TickDriver;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.client.ClientBoostRouting;
import wi.unigrav.core.client.ClientGIntent;
import wi.unigrav.core.client.BodyAttitudeInputPolicy;
import wi.unigrav.core.client.PlayerRenderPoseMirror;
import neofontrender.addons.api.camera.CameraEntityPickGeometryProvider;
import neofontrender.addons.api.camera.CameraEntityPickGeometry;
import java.util.List;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.PlayerPhysicsInput;
import wi.unigrav.core.client.TangentViewSample;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerMobilityIntent;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;
import wi.unigrav.core.client.ClientGravityTicker;
import wi.unigrav.core.client.ClientKeyBindingState;
import wi.unigrav.core.client.boost.RemoteBoostClient;

/**
 * Direct client adapter from Universal Gravitation's accepted physical and
 * zero-G state to Revo UI Camera API v3+, Flight API v9+ and Input API v1.
 * Accepted model-up body roots require Flight API v11. Revo UI remains the sole owner of Minecraft
 * camera, input, picking, and rendering Mixins; this adapter owns one local
 * orientation and the player-anchored free-drift impulse derived from it.
 */
public final class RevoUiSixDofCamera implements AutoCloseable {

    public static final String UIE_MOD_ID = "neofontrender_ui_enhancements";
    private static final int REQUIRED_CAMERA_API = 4;
    private static final int REQUIRED_FLIGHT_API = 11;
    private static final int REQUIRED_INPUT_API = 1;
    private static final int PRIORITY = 500;
    private static final long CAMERA_PROBE_MIN_INTERVAL_TICKS = 20L;
    private static final long CAMERA_PROBE_SUMMARY_INTERVAL_TICKS = 100L;
    private static final long PHYSICAL_CONTROLS_PROBE_MIN_INTERVAL_TICKS = 20L;
    private static final long PHYSICAL_CONTROLS_PROBE_SUMMARY_INTERVAL_TICKS = 100L;
    private static final ResourceLocation ID = new ResourceLocation(
            Reference.MOD_ID, "six_dof_camera");
    private static final ResourceLocation EYE_ANCHOR_ID = new ResourceLocation(
            Reference.MOD_ID, "accepted_physical_eye");
    private static final InputContext CAMERA_INPUT_CONTEXT = createInputContext(false, false, false);
    private static final InputContext ACCEPTED_LOOK_INPUT_CONTEXT = InputContext.builder(ID, PRIORITY)
            .claim(InputAction.CAMERA_LOOK_X, InputAction.CAMERA_LOOK_Y).build();
    // Cardinal GON routes translation and held player actions through this provider without
    // claiming UIE Flight attitude or camera-roll actions.
    private static final InputContext ACCEPTED_CARDINAL_DRIFT_INPUT_CONTEXT = InputContext.builder(ID, PRIORITY)
            .claim(InputAction.CAMERA_LOOK_X, InputAction.CAMERA_LOOK_Y,
                    InputAction.CAMERA_TRANSLATE_X, InputAction.CAMERA_TRANSLATE_Y,
                    InputAction.CAMERA_TRANSLATE_Z)
            .block(InputAction.PLAYER_MOVE_FORWARD, InputAction.PLAYER_MOVE_STRAFE,
                    InputAction.PLAYER_JUMP, InputAction.PLAYER_SNEAK, InputAction.PLAYER_SPRINT)
            .build();
    private static final InputContext CAMERA_ROLL_GEAR_CONTEXT = createInputContext(false, true, false);
    private static final InputContext CAMERA_BOOST_GEAR_CONTEXT = createInputContext(false, false, true);
    private static final InputContext CAMERA_BOTH_GEARS_CONTEXT = createInputContext(false, true, true);
    private static final InputContext DRIFT_INPUT_CONTEXT = createInputContext(true, false, false);
    private static final InputContext DRIFT_ROLL_GEAR_CONTEXT = createInputContext(true, true, false);
    private static final InputContext DRIFT_BOOST_GEAR_CONTEXT = createInputContext(true, false, true);
    private static final InputContext DRIFT_BOTH_GEARS_CONTEXT = createInputContext(true, true, true);
    private static final KeyBinding ROLL_LEFT_KEY = rollKey(
            "key.unigrav.roll_left", Keyboard.KEY_Z);
    private static final KeyBinding ROLL_RIGHT_KEY = rollKey(
            "key.unigrav.roll_right", Keyboard.KEY_X);
    private static final KeyBinding ROLL_GEAR_UP_KEY = gearKey(
            "key.unigrav.roll_gear_up", Keyboard.KEY_RIGHT);
    private static final KeyBinding ROLL_GEAR_DOWN_KEY = gearKey(
            "key.unigrav.roll_gear_down", Keyboard.KEY_LEFT);
    private static final KeyBinding BOOST_GEAR_UP_KEY = gearKey(
            "key.unigrav.boost_gear_up", Keyboard.KEY_DOWN);
    private static final KeyBinding BOOST_GEAR_DOWN_KEY = gearKey(
            "key.unigrav.boost_gear_down", Keyboard.KEY_UP);
    private static final KeyBinding ALIGNMENT_TOGGLE_KEY = new KeyBinding(
            "key.unigrav.alignment_toggle", KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL, Keyboard.KEY_H, "Universal Gravitation");
    private static final KeyBinding GROUND_FACE_SELECT_KEY = new KeyBinding(
            "key.unigrav.ground_face_select", KeyConflictContext.IN_GAME,
            KeyModifier.ALT, Keyboard.KEY_H, "Universal Gravitation");
    private static RevoUiSixDofCamera installed;
    private static boolean keyBindingsRegistered;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final GravityEntityBinder binder;
    private final SixDofCameraOrientation orientation = new SixDofCameraOrientation();
    private final CardinalCameraTransition cardinalCameraTransition = new CardinalCameraTransition();
    private CardinalPresentationCache cardinalPresentationCache;
    private final SixDofRollDynamics rollDynamics = new SixDofRollDynamics();
    private final SixDofControlGears controlGears = new SixDofControlGears();
    private final SixDofAlignmentInput alignmentInput = new SixDofAlignmentInput();
    private final SixDofMovementFrameLock movementFrameLock = new SixDofMovementFrameLock();
    private final SixDofCameraSession cameraSession = new SixDofCameraSession();
    private SixDofCameraOrientation.HorizonResult alignmentDiagnostics;
    private boolean alignmentPitchLimited;
    private long cameraLookInputSequence;
    private long lastCameraInputProbeTick = Long.MIN_VALUE;
    private long lastCameraTrackingProbeTick = Long.MIN_VALUE;
    private long lastPhysicalControlsProbeTick = Long.MIN_VALUE;
    private String lastCameraInputProbeKey = "";
    private String lastCameraTrackingProbeKey = "";
    private PhysicalControlsProbeKey lastPhysicalControlsProbeKey;
    private long suppressedCameraInputProbeCount;
    private long suppressedCameraTrackingProbeCount;
    private long suppressedPhysicalControlsProbeCount;
    private String suppressedCameraInputProbeOutcome = "none";
    private String suppressedCameraTrackingProbeOutcome = "none";
    private String suppressedPhysicalControlsProbeOutcome = "none";
    private SixDofCameraOrientation.Components lastCameraProbeQuaternion;
    private CameraLookInputProbe lastCameraLookInputProbe;
    private SixDofGroundFacePreview.Target groundFacePreview;
    private PlayerPhysicsClientSession.BodyReferenceView lastBodyReferenceStatus;
    private PlayerBodyReference.Effective lastEffectiveBodyReference;

    private FlightRegistration capabilityRegistration;
    private FlightRegistration maneuverRegistration;
    private FlightRegistration trackingRegistration;
    private FlightRegistration bodyPoseRegistration;
    private CameraRegistration eyeAnchorRegistration;
    private CameraRegistration pickGeometryRegistration;
    private InputRegistration inputRegistration;
    private ClientBoostRouting.Registration boostRegistration;
    private ClientBoostRouting.Registration physicalRegistration;
    private ClientBoostRouting.Registration viewBasisRegistration;
    private boolean closed;

    private RevoUiSixDofCamera(GravityEntityBinder binder) {
        this.binder = binder;
    }

    /** Registers the roll and gear bindings once. */
    public static synchronized void registerKeyBindings() {
        if (keyBindingsRegistered) {
            return;
        }
        ClientRegistry.registerKeyBinding(ROLL_LEFT_KEY);
        ClientRegistry.registerKeyBinding(ROLL_RIGHT_KEY);
        ClientRegistry.registerKeyBinding(ROLL_GEAR_UP_KEY);
        ClientRegistry.registerKeyBinding(ROLL_GEAR_DOWN_KEY);
        ClientRegistry.registerKeyBinding(BOOST_GEAR_UP_KEY);
        ClientRegistry.registerKeyBinding(BOOST_GEAR_DOWN_KEY);
        ClientRegistry.registerKeyBinding(ALIGNMENT_TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(GROUND_FACE_SELECT_KEY);
        keyBindingsRegistered = true;
    }

    /** Installs one process-lifetime UIE integration and returns its idempotent handle. */
    public static synchronized RevoUiSixDofCamera install(GravityEntityBinder binder) {
        if (binder == null) {
            throw new NullPointerException("binder");
        }
        if (installed != null && !installed.closed) {
            return installed;
        }
        int cameraApiVersion = CameraApi.getApiVersion();
        if (cameraApiVersion < REQUIRED_CAMERA_API) {
            throw new IllegalStateException("Revo UI Camera API " + REQUIRED_CAMERA_API
                    + "+ required, found " + cameraApiVersion);
        }
        int apiVersion = FlightApi.getApiVersion();
        if (apiVersion < REQUIRED_FLIGHT_API) {
            throw new IllegalStateException("Revo UI Flight API " + REQUIRED_FLIGHT_API
                    + "+ required, found " + apiVersion);
        }
        int inputApiVersion = InputApi.getApiVersion();
        if (inputApiVersion < REQUIRED_INPUT_API) {
            throw new IllegalStateException("Revo UI Input API " + REQUIRED_INPUT_API
                    + "+ required, found " + inputApiVersion);
        }

        RevoUiSixDofCamera adapter = new RevoUiSixDofCamera(binder);
        try {
            adapter.inputRegistration = InputApi.registerContextProvider(
                    ID, PRIORITY, adapter::inputContext);
            adapter.capabilityRegistration = FlightApi.registerCapabilityProvider(
                    ID, PRIORITY, adapter::capability);
            adapter.maneuverRegistration = FlightApi.registerManeuverHandler(
                    ID, PRIORITY, adapter::maneuver);
            adapter.trackingRegistration = FlightApi.registerCameraTrackingProvider(
                    ID, PRIORITY, adapter::tracking);
            adapter.bodyPoseRegistration = SixDofBodyPresentation.registerModelUpBodyPoseProvider(
                    ID, PRIORITY, adapter::bodyPose);
            adapter.eyeAnchorRegistration = CameraApi.registerEyeAnchorProvider(new CameraEyeAnchorProvider() {
                @Override public ResourceLocation id() { return EYE_ANCHOR_ID; }
                @Override public int priority() { return PRIORITY; }
                @Override public CameraEyeAnchor anchor(CameraEyeAnchorContext context) {
                    return adapter.eyeAnchor(context);
                }
            });
            adapter.boostRegistration = ClientBoostRouting.register(adapter::boostPlan);
            adapter.pickGeometryRegistration = CameraApi.registerEntityPickGeometryProvider(new CameraEntityPickGeometryProvider() {
                @Override public ResourceLocation id() { return ID; }
                @Override public int priority() { return PRIORITY; }
                @Override public List<CameraEntityPickGeometry> sample(World world,
                        Entity excluded, float partialTicks) {
                    return PlayerRenderPoseMirror.pickGeometry(world, excluded, partialTicks);
                }
            });
            adapter.physicalRegistration = ClientBoostRouting.registerSampledPhysical(adapter::physicalControls);
            adapter.viewBasisRegistration = ClientBoostRouting.registerViewBasis(adapter::walkingViewBasis);
            SixDofGroundFacePreview.installReloadListener();
            MinecraftForge.EVENT_BUS.register(adapter);
            installed = adapter;
            return adapter;
        } catch (RuntimeException | Error failure) {
            try {
                adapter.close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (closed) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            applyRollAndDrift();
            reportActivePose();
            reportBodyReferenceStatus();
            return;
        }
        EntityPlayerSP player = minecraft.player;
        if (cameraSession.update(player, minecraft.world)) {
            clearAttitude();
        }
        if (PlayerPhysicsClient.isInstallationFenced(player)) {
            clearCardinalPresentation();
            suspendControls();
            return;
        }
        if (!isActiveFor(player)) {
            if (isAcceptedPhysicalCameraLook(player)) {
                prepareAcceptedCameraTarget(player, 0.0F);
                return;
            }
            deactivateInactiveCamera(player);
            return;
        }
        if (cardinalCameraCandidate(player) == null) {
            clearCardinalPresentation();
        }
        initializeFromPlayer(player);
    }

    /** Maps the physical mouse to FREE local yaw/pitch before UIE's legacy Flight listener. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCameraMouseInput(CameraMouseInputEvent event) {
        EntityPlayerSP player = event.getPlayer();
        if (closed || minecraft.currentScreen != null || minecraft.isGamePaused()
                || !minecraft.inGameHasFocus) {
            return;
        }
        CameraLookSelection selection = cameraLookSelection(player);
        AcceptedCameraLook acceptedLook = selection.accepted();
        CardinalCameraCandidate cardinal = cardinalCandidate(acceptedLook);
        boolean acceptedFreeLook = acceptedLook != null
                && acceptedLook.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule;
        InputFrame frame = InputApi.getFrame(0.0F);
        boolean ownsLookX = owns(frame, InputAction.CAMERA_LOOK_X);
        boolean ownsLookY = owns(frame, InputAction.CAMERA_LOOK_Y);
        boolean driftAuthorized = acceptedLook == null && isDriftAuthorized(player)
                && !PlayerKinematics.requiresNative(player);
        boolean canPublishCameraQ = acceptedLook != null || driftAuthorized;
        if (!canPublishCameraQ || !ownsLookX || !ownsLookY) {
            String reason = !canPublishCameraQ
                    ? selection.reason()
                    : frame == null || frame.getSampleId() == 0L ? "input_frame_unavailable"
                    : !ownsLookX ? "camera_look_x_not_owned" : "camera_look_y_not_owned";
            recordCameraLookInput(player, frame, acceptedLook,
                    event.getOriginalDeltaX(), event.getOriginalDeltaY(), false, false,
                    orientation.isInitialized() ? orientation.components() : null,
                    canPublishCameraQ ? "eligible_but_not_consumed:" + reason : "not_eligible:" + reason);
            return;
        }
        if (cardinal != null && cardinalPresentation(player, event.getPartialTicks(), cardinal) == null) {
            recordCameraLookInput(player, frame, acceptedLook,
                    event.getOriginalDeltaX(), event.getOriginalDeltaY(), false, false,
                    orientation.isInitialized() ? orientation.components() : null,
                    "eligible_but_no_output:cardinal_presentation_preflight_failed");
            return;
        }
        initializeFromPlayer(player);
        if (cardinal != null) {
            prepareAcceptedCameraTarget(player, cardinal, event.getPartialTicks());
            Vec3 up = new Vec3(cardinal.reference().frame().up().x(),
                    cardinal.reference().frame().up().y(), cardinal.reference().frame().up().z());
            alignmentPitchLimited = orientation.applyAlignedMouseDelta(
                    event.getOriginalDeltaX(), event.getOriginalDeltaY(),
                    minecraft.gameSettings.mouseSensitivity, minecraft.gameSettings.invertMouse, up).pitchLimited();
            cardinalPresentationCache = null;
            event.consumeBodyHorizontal();
            event.consumeBodyVertical();
            recordCameraLookInput(player, frame, acceptedLook,
                    event.getOriginalDeltaX(), event.getOriginalDeltaY(), true, alignmentPitchLimited,
                    orientation.components(), "accepted_cardinal_q");
            return;
        }
        sampleAlignmentCommands(false, event.getPartialTicks());
        if (effectiveAlignment()) {
            alignmentPitchLimited = orientation.applyAlignedMouseDelta(
                    event.getOriginalDeltaX(), event.getOriginalDeltaY(),
                    minecraft.gameSettings.mouseSensitivity, minecraft.gameSettings.invertMouse,
                    alignmentInput.reference().normal()).pitchLimited();
        } else {
            orientation.applyMouseDelta(event.getOriginalDeltaX(), event.getOriginalDeltaY(),
                    minecraft.gameSettings.mouseSensitivity, minecraft.gameSettings.invertMouse);
            alignmentPitchLimited = false;
        }
        // The tracking quaternion is authoritative; vanilla and UIE legacy Flight must not
        // apply the same physical mouse delta to the player's Euler angles a second time.
        event.consumeBodyHorizontal();
        event.consumeBodyVertical();
        recordCameraLookInput(player, frame, acceptedLook,
                event.getOriginalDeltaX(), event.getOriginalDeltaY(), true, alignmentPitchLimited,
                orientation.components(), acceptedFreeLook ? "accepted_free_q" : "active_personal_g_q");
    }

    private FlightDecision capability(EntityPlayerSP player, FlightCapability capability,
                                      boolean builtInDefault) {
        if (!canProvideCameraTracking(player)) {
            return FlightDecision.PASS;
        }
        if (capability == FlightCapability.CONTROL) {
            return FlightDecision.ALLOW;
        }
        // This adapter supplies the final tracking quaternion itself. The
        // remaining currently known legacy presentation features stay disabled.
        if (capability == FlightCapability.CAMERA_ROTATION
                || capability == FlightCapability.KEYBOARD_YAW
                || capability == FlightCapability.PLAYER_ROLL_RENDERING
                || capability == FlightCapability.HUD
                || capability == FlightCapability.CROSSHAIR_SUPPRESSION) {
            return FlightDecision.DENY;
        }
        // Future non-breaking UIE capabilities remain opt-in rather than being
        // accidentally claimed by this camera-only adapter.
        return FlightDecision.PASS;
    }

    private boolean maneuver(FlightManeuverInput input) {
        EntityPlayerSP player = input.getPlayer();
        if (!canProvideCameraTracking(player)) {
            return false;
        }
        initializeFromPlayer(player);
        // UIE Flight v9 combines W/S with pitch, A/D with yaw, and physical mouse X with
        // roll. Free drift deliberately sources those controls from Input API v1 instead;
        // returning true here is a compatibility fallback that only suppresses legacy Euler
        // rotation if a frame was sampled before the dynamic InputContext became visible.
        return true;
    }

    private InputContext inputContext(InputFrameContext frame) {
        EntityPlayerSP player = minecraft.player;
        if (closed || frame == null || player == null || player != minecraft.player
                || minecraft.world == null || player.world != minecraft.world || !frame.isGameFocused()
                || minecraft.currentScreen != null || PlayerKinematics.requiresNative(player)
                || PlayerPhysicsClient.isInstallationFenced(player) || higherCameraRigActive()) {
            return null;
        }
        AcceptedCameraLook accepted = acceptedCameraLook(player);
        CardinalCameraCandidate cardinal = cardinalCandidate(accepted);
        if (cardinal != null) {
            if (cardinalPresentation(player, 0.0F, cardinal) == null) {
                recordCameraTracking(player, "eligible_but_no_output:input_context_presentation_preflight_failed", accepted);
                return null;
            }
            return isDriftAuthorized(player)
                    ? ACCEPTED_CARDINAL_DRIFT_INPUT_CONTEXT : ACCEPTED_LOOK_INPUT_CONTEXT;
        }
        boolean acceptedFree = accepted != null
                && accepted.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule;
        if (acceptedFree && !isDriftAuthorized(player)) return ACCEPTED_LOOK_INPUT_CONTEXT;
        if (!isDriftAuthorized(player)) return null;
        SixDofPhysicalControls.State controls = currentControls();
        return selectInputContext(isDriftAuthorized(player),
                true, controls.boostGearUp() || controls.boostGearDown());
    }

    private void applyRollAndDrift() {
        EntityPlayerSP player = minecraft.player;
        if (PlayerPhysicsClient.isInstallationFenced(player)) {
            clearCardinalPresentation();
            suspendControls();
            return;
        }
        if (minecraft.isGamePaused() || minecraft.currentScreen != null
                || !minecraft.inGameHasFocus) {
            suspendControls();
            return;
        }
        if (!isActiveFor(player)) {
            deactivateInactiveCamera(player);
            return;
        }
        if (cardinalCameraCandidate(player) == null) clearCardinalPresentation();
        initializeFromPlayer(player);
        InputFrame frame = InputApi.getFrame(0.0F);
        if (frame.getSampleId() == 0L || !frame.getContext().isGameFocused()
                || !owns(frame, InputAction.CAMERA_LOOK_X)
                || !owns(frame, InputAction.CAMERA_LOOK_Y)) {
            suspendControls();
            return;
        }
        sampleAlignmentCommands(false, 0.0F);
        SixDofPhysicalControls.State controls = currentControls();
        updateControlGears(player, controls);
        double rollDegrees = rollDynamics.advance(
                controls.rollAxis(), TickDriver.TICK_SECONDS,
                controlGears.rollMaximumSpeedDegreesPerSecond(),
                controlGears.rollAccelerationDegreesPerSecondSquared());
        orientation.applyExplicitRollDegrees(rollDegrees);
        alignmentDiagnostics = effectiveAlignment()
                ? orientation.alignHorizon(alignmentInput.reference().normal(),
                        TickDriver.TICK_SECONDS,
                        ClientKeyBindingState.isDown(ROLL_LEFT_KEY)
                                || ClientKeyBindingState.isDown(ROLL_RIGHT_KEY))
                : null;
        if (PlayerPhysicsClient.owns(player)) return;
        if (!isDriftAuthorized(player)
                || !ID.equals(frame.owner(InputAction.PLAYER_MOVE_FORWARD))) {
            return;
        }

        SixDofDriftMotion.Intent intent = new SixDofDriftMotion.Intent(
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Z),
                -ownedAxis(frame, InputAction.CAMERA_TRANSLATE_X),
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Y));
        Vec3 velocity = new Vec3(player.motionX, player.motionY, player.motionZ)
                .scale(GravityEntityBinder.TICKS_PER_SECOND);
        double driftSpeedLimit = UnigravConfig.sixDofDriftSpeedLimit;
        if (!minecraft.isIntegratedServerRunning()) {
            double remoteCeiling = RemoteBoostClient.currentCeilingMps(player);
            if (!Double.isFinite(remoteCeiling) || remoteCeiling <= 0.0) {
                return;
            }
            // Local zero means "no extra local cap", never "ignore server cap".
            driftSpeedLimit = driftSpeedLimit > 0.0
                    ? Math.min(driftSpeedLimit, remoteCeiling) : remoteCeiling;
        }
        Vec3 deltaVelocity = SixDofDriftMotion.deltaVelocity(
                currentMovementFrame(player), intent, velocity,
                UnigravConfig.sixDofDriftAcceleration,
                driftSpeedLimit,
                TickDriver.TICK_SECONDS);
        if (deltaVelocity.normSquared() == 0.0) {
            return;
        }
        BodyPhysics physics = binder.bodyPhysicsOf(player);
        double massKg = physics != null
                ? physics.massKg() : BodyPhysics.estimate(player).massKg();
        binder.addImpulse(player, deltaVelocity.scale(massKg));
    }

    private FlightCameraTracking tracking(EntityPlayerSP player, float partialTicks) {
        String eligibilityFailure = trackingUnavailableReason(player);
        if (eligibilityFailure != null) {
            recordCameraTracking(player, "not_eligible:" + eligibilityFailure, null);
            return null;
        }
        CameraLookSelection selection = cameraLookSelection(player);
        AcceptedCameraLook acceptedLook = selection.accepted();
        if (acceptedLook != null
                && acceptedLook.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            PlayerPhysicalSnapshot physical = acceptedLook.snapshot();
            var view = freePresentationView(player);
            if (closed || PlayerPhysicsClient.currentProvenPresentation(player).orElse(null) != physical
                    || higherCameraRigActive()) {
                recordCameraTracking(player, "eligible_but_no_output:free_sample_became_stale", acceptedLook);
                return null;
            }
            FlightCameraTracking result = SixDofBodyPresentation.sampleFree(physical, view).tracking();
            recordCameraTracking(player, result == null ? "eligible_but_no_output:free_tracking_null"
                    : "tracking_output:accepted_free_q", acceptedLook);
            return result;
        }
        CardinalCameraCandidate cardinal = cardinalCandidate(acceptedLook);
        if (cardinal != null) {
            SixDofBodyPresentation.Sample sample = cardinalPresentation(player, partialTicks, cardinal);
            FlightCameraTracking result = sample == null ? null : sample.tracking();
            recordCameraTracking(player, result == null
                    ? "eligible_but_no_output:accepted_cardinal_presentation_unavailable"
                    : "tracking_output:accepted_cardinal_q", acceptedLook);
            return result;
        }
        if (isDriftAuthorized(player)) {
            initializeFromPlayer(player);
            FlightCameraTracking result = SixDofBodyPresentation.tracking(currentAttitude());
            recordCameraTracking(player, result == null ? "eligible_but_no_output:active_q_tracking_null"
                    : "tracking_output:active_personal_g_q", null);
            return result;
        }
        var accepted = PlayerPhysicsClient.currentProvenPresentation(player);
        var effective = PlayerPhysicsClient.acceptedEffectiveBodyReference(player);
        if (accepted.isEmpty() || effective.isEmpty()
                || effective.orElseThrow().frame() == CardinalFrame.Y_POS) {
            String reason = accepted.isPresent() && effective.isPresent()
                    ? "eligible_y_plus_reference_without_camera_candidate"
                    : "not_eligible:" + selection.reason();
            recordCameraTracking(player, "eligible_but_no_output:" + reason, null);
            return null;
        }
        var look = player.getLook(partialTicks);
        FlightCameraTracking result = SixDofBodyPresentation.sampleCardinal(accepted.orElseThrow(), effective.orElseThrow(),
                new FlightVector(look.x, look.y, look.z), cardinalBodyForward(player, partialTicks), player.prevRenderYawOffset,
                player.renderYawOffset, partialTicks).tracking();
        recordCameraTracking(player, "tracking_output:native_euler_reference_fallback_unconsumed", null);
        return result;
    }

    private String trackingUnavailableReason(EntityPlayerSP player) {
        if (closed) return "camera_closed";
        if (player == null || player != minecraft.player) return "not_local_actor";
        if (minecraft.world == null || player.world != minecraft.world) return "world_mismatch";
        if (!player.isEntityAlive() || player.isDead) return "actor_not_alive";
        if (PlayerKinematics.requiresNative(player)) return "native_entity_pose_required";
        if (PlayerPhysicsClient.isInstallationFenced(player)) return "installation_fenced";
        if (higherCameraRigActive()) return "higher_priority_camera_rig";
        return null;
    }

    private FlightBodyPose bodyPose(AbstractClientPlayer player, float partialTicks) {
        if (closed || player == null || player.world != minecraft.world || !player.isEntityAlive()
                || player.isDead || PlayerKinematics.requiresNative(player)) return null;
        if (!(player instanceof EntityPlayerSP localPlayer) || localPlayer != minecraft.player) {
            return PlayerRenderPoseMirror.sample(player, partialTicks).orElse(null);
        }
        if (PlayerPhysicsClient.isInstallationFenced(localPlayer)) return null;
        var accepted = PlayerPhysicsClient.currentProvenPresentation(localPlayer);
        if (accepted.isEmpty()) {
            if (PlayerPhysicsClient.drivesMotion(localPlayer)) return null;
            if (isDriftAuthorized(localPlayer)) {
                initializeFromPlayer(localPlayer);
                return walkingPresentation(localPlayer, partialTicks).body();
            }
            return null;
        }
        if (accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            if (higherCameraRigActive()) return null;
            var view = freePresentationView(localPlayer);
            if (closed || PlayerPhysicsClient.currentProvenPresentation(localPlayer).orElse(null) != accepted.orElseThrow()
                    || higherCameraRigActive()) return null;
            return SixDofBodyPresentation.sampleFree(accepted.orElseThrow(), view).body();
        }
        CardinalCameraCandidate cardinal = cardinalCameraCandidate(localPlayer);
        if (cardinal != null) {
            SixDofBodyPresentation.Sample sample = cardinalPresentation(localPlayer, partialTicks, cardinal);
            return sample == null ? null : sample.body();
        }
        // A Cardinal body pose and a camera frame are published from the same accepted
        // sample. If qualification failed, leave both to the native presentation path.
        return null;
    }

    /** Reports the already-integrated active camera sample, including first-person frames with no body render. */
    private void reportActivePose() {
        EntityPlayerSP player = minecraft.player;
        if (closed || PlayerPhysicsClient.isInstallationFenced(player)
                || (!isActiveFor(player) && !isAcceptedPhysicalCameraLook(player))) return;
        initializeFromPlayer(player);
        var view = currentAttitude();
        var accepted = PlayerPhysicsClient.currentProvenPresentation(player);
        // Correlate the already-read physical presentation with the actual G acknowledgement.
        // This optional channel never grants a pose, input, movement or render capability.
        FlightRenderDiagnostics.publish(player, player.world, new FlightRenderDiagnostics.Observation(
                accepted.map(value -> value.scope().toString()).orElse("NOT_OBSERVED"),
                accepted.map(PlayerPhysicalSnapshot::revision).orElse(-1L), player.ticksExisted,
                ClientGravityTicker.requestedPersonalZeroG(player),
                ClientGravityTicker.acknowledgedPersonalZeroG(player).orElse(null),
                ClientGravityTicker.isPersonalZeroGActive(player),
                ClientGIntent.acknowledgedObservation(player)
                        .map(Object::toString).orElse("NOT_OBSERVED")));
        SixDofBodyPresentation.FreePoseAttitudes pose;
        if (accepted.isPresent() && accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.Cardinal) {
            CardinalCameraCandidate cardinal = cardinalCameraCandidate(player);
            if (cardinal != null) {
                var sample = cardinalPresentation(player, 0.0F, cardinal);
                if (sample == null) return;
                pose = new SixDofBodyPresentation.FreePoseAttitudes(
                        sample.body().attitude, sample.body().headWorldAttitude);
                PlayerPhysicsClient.runFreePosePublication(player,
                        () -> PlayerRenderPoseMirror.reportFreePose(wireQuaternion(pose.body()),
                                wireQuaternion(pose.head())));
                return;
            }
            var effective = PlayerPhysicsClient.acceptedEffectiveBodyReference(player);
            if (effective.isEmpty()) return;
            var body = SixDofBodyPresentation.sampleCardinal(accepted.orElseThrow(), effective.orElseThrow(),
                    view.forward(), cardinalBodyForward(player, 0),
                    player.prevRenderYawOffset, player.renderYawOffset, 0).body();
            pose = new SixDofBodyPresentation.FreePoseAttitudes(body.attitude, body.headWorldAttitude);
        } else {
            pose = accepted.isPresent() && accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    ? SixDofBodyPresentation.acceptedFreePoseAttitudes(accepted.orElseThrow(), view)
                    : SixDofBodyPresentation.freePoseAttitudes(view, PlayerPhysicsClient.bodyYawOffset(player, 0.0F));
        }
        PlayerPhysicsClient.runFreePosePublication(player,
                () -> PlayerRenderPoseMirror.reportFreePose(wireQuaternion(pose.body()), wireQuaternion(pose.head())));
    }

    private static PlayerRenderPoseProtocol.Quaternion wireQuaternion(FlightAttitude attitude) {
        return new PlayerRenderPoseProtocol.Quaternion(attitude.x, attitude.y, attitude.z, attitude.w);
    }

    private static FlightVector cardinalBodyForward(EntityPlayerSP player, float partialTicks) {
        Vec3 forward = PlayerPhysicsClient.cardinalBodyForward(player, partialTicks);
        return forward == null ? null : new FlightVector(forward.x(), forward.y(), forward.z());
    }

    private CardinalCameraCandidate cardinalCameraCandidate(EntityPlayerSP player) {
        return cardinalCandidate(acceptedCameraLook(player));
    }

    private static CardinalCameraCandidate cardinalCandidate(AcceptedCameraLook accepted) {
        return accepted != null && accepted.reference() != null
                ? new CardinalCameraCandidate(accepted.snapshot(), accepted.reference(), accepted.viewSequence()) : null;
    }

    private boolean isAcceptedFreeCameraLook(EntityPlayerSP player) {
        AcceptedCameraLook accepted = acceptedCameraLook(player);
        return accepted != null
                && accepted.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule;
    }

    private boolean isAcceptedPhysicalCameraLook(EntityPlayerSP player) {
        return acceptedCameraLook(player) != null;
    }

    private boolean canProvideCameraTracking(EntityPlayerSP player) {
        if (closed || player == null || player != minecraft.player || minecraft.world == null
                || player.world != minecraft.world || !player.isEntityAlive() || player.isDead
                || PlayerKinematics.requiresNative(player) || PlayerPhysicsClient.isInstallationFenced(player)
                || higherCameraRigActive()) {
            return false;
        }
        AcceptedCameraLook accepted = acceptedCameraLook(player);
        if (accepted == null) return isDriftAuthorized(player);
        CardinalCameraCandidate cardinal = cardinalCandidate(accepted);
        return cardinal == null || cardinalPresentation(player, 0.0F, cardinal) != null;
    }

    /** Camera look follows proven physical ownership; G remains a separate thrust permit. */
    private AcceptedCameraLook acceptedCameraLook(EntityPlayerSP player) {
        return cameraLookSelection(player).accepted();
    }

    private CameraLookSelection cameraLookSelection(EntityPlayerSP player) {
        if (closed || !UnigravConfig.sixDofCamera || player == null || player != minecraft.player
                || minecraft.world == null || player.world != minecraft.world || !player.isEntityAlive()
                || player.isDead || PlayerKinematics.requiresNative(player)
                || PlayerPhysicsClient.isInstallationFenced(player) || higherCameraRigActive()) {
            return CameraLookSelection.unavailable(cameraLookUnavailableReason(player));
        }
        PlayerPhysicalSnapshot snapshot = PlayerPhysicsClient.currentProvenPresentation(player).orElse(null);
        if (snapshot == null) return CameraLookSelection.unavailable("accepted_physical_snapshot_unavailable");
        PlayerPhysicalSnapshot.Scope scope = snapshot.scope();
        if (!scope.playerId().equals(player.getUniqueID()) || scope.dimension() != player.dimension) {
            return CameraLookSelection.unavailable("accepted_scope_mismatch");
        }
        if (snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            return CameraLookSelection.available(new AcceptedCameraLook(snapshot, null, 0L, null));
        }
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)) {
            return CameraLookSelection.unavailable("accepted_shape_not_cardinal_or_free");
        }
        PlayerBodyReference.Effective reference = PlayerPhysicsClient.acceptedEffectiveBodyReference(player)
                .orElse(null);
        if (reference == null || reference.frame() != cardinal.frame()) {
            return CameraLookSelection.unavailable("accepted_cardinal_reference_unavailable");
        }
        long viewSequence = 0L;
        TangentViewSample tangentSample = null;
        Optional<TangentViewSample> tangent = PlayerPhysicsClient.currentTangentView(player);
        if (tangent.isPresent()) {
            TangentViewSample sample = tangent.orElseThrow();
            Vec3 head = sample.headForward();
            double lengthSquared = head.normSquared();
            if (sample.frame() != reference.frame() || sample.viewSequence() <= 0L
                    || sample.clientTick() < 0L || !Double.isFinite(head.x()) || !Double.isFinite(head.y())
                    || !Double.isFinite(head.z()) || !Double.isFinite(lengthSquared)
                    || lengthSquared <= 1.0e-12) {
                return CameraLookSelection.unavailable("accepted_tangent_view_invalid");
            }
            viewSequence = sample.viewSequence();
            tangentSample = sample;
        }
        return CameraLookSelection.available(new AcceptedCameraLook(snapshot, reference, viewSequence, tangentSample));
    }

    private String cameraLookUnavailableReason(EntityPlayerSP player) {
        if (closed) return "camera_closed";
        if (!UnigravConfig.sixDofCamera) return "camera_config_disabled";
        if (player == null || player != minecraft.player || minecraft.world == null
                || player.world != minecraft.world) return "actor_or_world_unavailable";
        if (!player.isEntityAlive() || player.isDead) return "actor_not_alive";
        if (PlayerKinematics.requiresNative(player)) return "native_entity_pose_required";
        if (PlayerPhysicsClient.isInstallationFenced(player)) return "installation_fenced";
        if (higherCameraRigActive()) return "higher_priority_camera_rig";
        return "accepted_camera_reference_unavailable";
    }

    private void recordCameraLookInput(EntityPlayerSP player, InputFrame frame,
            AcceptedCameraLook accepted, int deltaX, int deltaY, boolean consumed,
            boolean poleClamped, SixDofCameraOrientation.Components quaternion, String outcome) {
        long tick = player == null || player.world == null ? -1L : player.world.getTotalWorldTime();
        long sampleId = frame == null ? 0L : frame.getSampleId();
        boolean focused = frame != null && frame.getContext() != null
                && frame.getContext().isGameFocused();
        String ownerX = frame == null ? "none" : String.valueOf(frame.owner(InputAction.CAMERA_LOOK_X));
        String ownerY = frame == null ? "none" : String.valueOf(frame.owner(InputAction.CAMERA_LOOK_Y));
        String scope = cameraScope(accepted);
        long revision = accepted == null ? -1L : accepted.snapshot().revision();
        long viewSequence = accepted == null ? 0L : accepted.viewSequence();
        String mode = cameraMode(accepted);
        lastCameraLookInputProbe = new CameraLookInputProbe(cameraLookInputSequence++, sampleId,
                tick, scope, revision, viewSequence, outcome);
        String key = outcome + '|' + mode + '|' + focused + '|' + ownerX + '|' + ownerY
                + '|' + consumed + '|' + poleClamped;
        boolean stateChanged = !key.equals(lastCameraInputProbeKey);
        boolean summaryElapsed = lastCameraInputProbeTick == Long.MIN_VALUE
                || tick >= lastCameraInputProbeTick
                        && tick - lastCameraInputProbeTick >= CAMERA_PROBE_SUMMARY_INTERVAL_TICKS;
        boolean rateWindowOpen = lastCameraInputProbeTick == Long.MIN_VALUE
                || tick >= lastCameraInputProbeTick
                        && tick - lastCameraInputProbeTick >= CAMERA_PROBE_MIN_INTERVAL_TICKS;
        lastCameraInputProbeKey = key;
        if (!stateChanged && !summaryElapsed) return;
        if (!rateWindowOpen && !summaryElapsed) {
            suppressedCameraInputProbeCount++;
            suppressedCameraInputProbeOutcome = outcome;
            return;
        }
        lastCameraInputProbeTick = tick;
        boolean nativeRequired = player != null && PlayerKinematics.requiresNative(player);
        boolean fenced = player != null && PlayerPhysicsClient.isInstallationFenced(player);
        boolean higherRig = higherCameraRigActive();
        boolean gRequested = player != null && ClientGravityTicker.requestedPersonalZeroG(player);
        String gAck = player == null ? "unknown"
                : ClientGravityTicker.acknowledgedPersonalZeroG(player)
                        .map(String::valueOf).orElse("unknown");
        boolean gActive = player != null && ClientGravityTicker.isPersonalZeroGActive(player);
        String mobility = PlayerPhysicsClient.mobilityState()
                .map(state -> state.mode() + "/" + state.hover()).orElse("unavailable");
        SixDofCameraOrientation.Snapshot qBasis = orientation.isInitialized() ? orientation.current() : null;
        String cameraBasis = qBasis == null ? "unavailable"
                : "forward=" + vector(qBasis.forward()) + ",right=" + vector(qBasis.right());
        TangentViewSample tangent = accepted == null ? null : accepted.tangentSample();
        String physicalBasis = tangent == null ? "none"
                : "head=" + vector(tangent.headForward()) + ",frame=" + tangent.frame();
        String bodyQPolicy = cardinalCandidate(accepted) != null && mobility.startsWith("WALK")
                ? "head-only-policy" : accepted == null ? "no-accepted-physical-intent" : "mode-dependent";
        String q = changedQuaternion(quaternion);
        long coalesced = suppressedCameraInputProbeCount;
        String coalescedOutcome = suppressedCameraInputProbeOutcome;
        suppressedCameraInputProbeCount = 0L;
        suppressedCameraInputProbeOutcome = "none";
        UnigravMod.LOGGER.info("camera probe stage=mouse seq={} sample={} tick={} scope={} revision={} viewSeq={} mode={} focused={} lookOwnerX={} lookOwnerY={} rawDelta={},{} outcome={} consumed={} poleClamped={} nativeRequired={} fenced={} higherRig={} gRequested={} gAck={} gActive={} mobility={} cameraBasis={} physicalBasis={} bodyQPolicy={} coalesced={} coalescedLastOutcome={} qChanged={}",
                lastCameraLookInputProbe.sequence(), sampleId, tick, scope, revision, viewSequence,
                mode, focused, ownerX, ownerY, deltaX, deltaY, outcome, consumed, poleClamped,
                nativeRequired, fenced, higherRig, gRequested, gAck, gActive, mobility,
                cameraBasis, physicalBasis, bodyQPolicy, coalesced, coalescedOutcome, q);
    }

    private void recordCameraTracking(EntityPlayerSP player, String outcome,
            AcceptedCameraLook accepted) {
        long tick = player == null || player.world == null ? -1L : player.world.getTotalWorldTime();
        String scope = cameraScope(accepted);
        long revision = accepted == null ? -1L : accepted.snapshot().revision();
        long viewSequence = accepted == null ? 0L : accepted.viewSequence();
        String mode = cameraMode(accepted);
        CameraLookInputProbe input = lastCameraLookInputProbe;
        long inputAge = input == null || tick < 0L || input.worldTick() < 0L ? -1L : Math.max(0L, tick - input.worldTick());
        String key = outcome + '|' + mode + '|' + (input == null ? "none" : input.outcome());
        boolean stateChanged = !key.equals(lastCameraTrackingProbeKey);
        boolean summaryElapsed = lastCameraTrackingProbeTick == Long.MIN_VALUE
                || tick >= lastCameraTrackingProbeTick
                        && tick - lastCameraTrackingProbeTick >= CAMERA_PROBE_SUMMARY_INTERVAL_TICKS;
        boolean rateWindowOpen = lastCameraTrackingProbeTick == Long.MIN_VALUE
                || tick >= lastCameraTrackingProbeTick
                        && tick - lastCameraTrackingProbeTick >= CAMERA_PROBE_MIN_INTERVAL_TICKS;
        lastCameraTrackingProbeKey = key;
        if (!stateChanged && !summaryElapsed) return;
        if (!rateWindowOpen && !summaryElapsed) {
            suppressedCameraTrackingProbeCount++;
            suppressedCameraTrackingProbeOutcome = outcome;
            return;
        }
        lastCameraTrackingProbeTick = tick;
        String inputSeq = input == null ? "none" : Long.toString(input.sequence());
        String inputSample = input == null ? "none" : Long.toString(input.sampleId());
        String inputOutcome = input == null ? "none" : input.outcome();
        String inputScope = input == null ? "none" : input.scope();
        long inputRevision = input == null ? -1L : input.revision();
        boolean inputSameScope = input != null && scope.equals(inputScope);
        String q = changedQuaternion(orientation.isInitialized() ? orientation.components() : null);
        long coalesced = suppressedCameraTrackingProbeCount;
        String coalescedOutcome = suppressedCameraTrackingProbeOutcome;
        suppressedCameraTrackingProbeCount = 0L;
        suppressedCameraTrackingProbeOutcome = "none";
        UnigravMod.LOGGER.info("camera probe stage=tracking tick={} scope={} revision={} viewSeq={} mode={} outcome={} inputSeq={} inputSample={} inputScope={} inputRevision={} inputSameScope={} inputAgeTicks={} inputOutcome={} coalesced={} coalescedLastOutcome={} qChanged={}",
                tick, scope, revision, viewSequence, mode, outcome, inputSeq, inputSample,
                inputScope, inputRevision, inputSameScope, inputAge, inputOutcome,
                coalesced, coalescedOutcome, q);
    }

    private String changedQuaternion(SixDofCameraOrientation.Components quaternion) {
        if (quaternion == null || quaternion.equals(lastCameraProbeQuaternion)) return "unchanged";
        lastCameraProbeQuaternion = quaternion;
        return quaternion.toString();
    }

    private static String cameraScope(AcceptedCameraLook accepted) {
        if (accepted == null) return "none";
        PlayerPhysicalSnapshot.Scope scope = accepted.snapshot().scope();
        return scope.playerId() + "/" + scope.sessionId() + "/d" + scope.dimension()
                + "/w" + scope.worldEpoch();
    }

    private static String cameraMode(AcceptedCameraLook accepted) {
        if (accepted == null) return "none";
        if (accepted.reference() != null) {
            return "CARDINAL:" + accepted.reference().frame();
        }
        return accepted.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule
                ? "FREE" : "accepted-other";
    }

    private static String vector(FlightVector value) {
        return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f)", value.x, value.y, value.z);
    }

    private static String vector(Vec3 value) {
        return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f)", value.x(), value.y(), value.z());
    }

    private static String vector(Vec3d value) {
        return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f)", value.x(), value.y(), value.z());
    }

    private static boolean isZeroTranslation(Vec3d value) {
        return value.x() == 0.0 && value.y() == 0.0 && value.z() == 0.0;
    }

    private void prepareAcceptedCameraTarget(EntityPlayerSP player, float partialTicks) {
        CardinalCameraCandidate candidate = cardinalCameraCandidate(player);
        if (candidate == null) {
            clearCardinalPresentation();
            initializeFromPlayer(player);
            return;
        }
        prepareAcceptedCameraTarget(player, candidate, partialTicks);
    }

    private CardinalCameraTransition.RenderSample prepareAcceptedCameraTarget(EntityPlayerSP player,
            CardinalCameraCandidate candidate, float partialTicks) {
        initializeFromPlayer(player);
        PlayerPhysicalSnapshot.Scope scope = candidate.snapshot().scope();
        if (cardinalCameraTransition.needsCardinalSeed(player, minecraft.world, scope)) {
            orientation.reset(SixDofBodyPresentation.seedCardinalCameraView(
                    candidate.reference(), currentAttitude()));
            cardinalPresentationCache = null;
        }
        double renderTick = renderTick(partialTicks);
        return cardinalCameraTransition.sample(player, minecraft.world, scope,
                candidate.reference(), cardinalEyeReferenceOffset(candidate.snapshot()), renderTick, orientation);
    }

    private SixDofBodyPresentation.Sample cardinalPresentation(EntityPlayerSP player,
            float partialTicks, CardinalCameraCandidate candidate) {
        World sampleWorld = minecraft.world;
        if (sampleWorld == null || player.world != sampleWorld) {
            clearCardinalPresentation();
            return null;
        }
        CardinalCameraTransition.RenderSample render = prepareAcceptedCameraTarget(player, candidate, partialTicks);
        if (minecraft.world != sampleWorld || player.world != sampleWorld
                || PlayerPhysicsClient.currentProvenPresentation(player).orElse(null) != candidate.snapshot()
                || !PlayerPhysicsClient.acceptedEffectiveBodyReference(player)
                        .filter(candidate.reference()::equals).isPresent()) {
            cardinalPresentationCache = null;
            return null;
        }
        Vec3d provenEye = PlayerPhysicsClient.currentProvenEyePosition(player, partialTicks).orElse(null);
        if (provenEye == null) {
            cardinalPresentationCache = null;
            return null;
        }
        long worldTick = sampleWorld.getTotalWorldTime();
        double exactRenderTick = (double) worldTick + Math.max(0.0F, Math.min(1.0F, partialTicks));
        SixDofCameraOrientation.Components target = orientation.components();
        if (cardinalPresentationCache != null && cardinalPresentationCache.matches(player, sampleWorld,
                candidate.snapshot(), candidate.reference(), worldTick, partialTicks, exactRenderTick,
                target, provenEye)) {
            return cardinalPresentationCache.sample;
        }
        SixDofBodyPresentation.Sample sample = SixDofBodyPresentation.sampleCardinalView(
                candidate.snapshot(), candidate.reference(), render,
                cardinalRenderedEyeFromTransport(player, partialTicks, provenEye, render.eyeReferenceCorrection()),
                cardinalBodyForward(player, partialTicks), player.prevRenderYawOffset,
                player.renderYawOffset, partialTicks);
        CameraEyeAnchor renderedEye = provenEye == null ? null : eyeAnchor(sampleWorld,
                provenEye, render.eyeReferenceCorrection());
        if (provenEye != null && !cardinalPresentationStillCurrent(player, sampleWorld, candidate, partialTicks,
                worldTick, target, provenEye)) {
            cardinalPresentationCache = null;
            PlayerPhysicalSnapshot current = PlayerPhysicsClient.currentProvenPresentation(player).orElse(null);
            if (closed || player != minecraft.player || minecraft.world != sampleWorld || player.world != sampleWorld
                    || !player.isEntityAlive() || player.isDead || PlayerKinematics.requiresNative(player)
                    || PlayerPhysicsClient.isInstallationFenced(player) || current == null
                    || !current.scope().equals(candidate.snapshot().scope())) {
                clearCardinalPresentation();
            }
            return null;
        }
        cardinalPresentationCache = new CardinalPresentationCache(player, sampleWorld,
                candidate.snapshot(), candidate.reference(), worldTick, partialTicks, exactRenderTick,
                target, provenEye, renderedEye, sample);
        return sample;
    }

    private static FlightVector cardinalRenderedEyeFromTransport(EntityPlayerSP player, float partialTicks,
            Vec3d provenEye, FlightVector referenceCorrection) {
        double alpha = Math.max(0.0F, Math.min(1.0F, partialTicks));
        // UIE adds model rootWorldOffset to the interpolated entity transport.
        // Derive it from the same proven eye as the camera, including movement
        // interpolation, so body and camera do not rotate about different pivots.
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * alpha;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * alpha;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * alpha;
        return new FlightVector(provenEye.x() + referenceCorrection.x - x,
                provenEye.y() + referenceCorrection.y - y, provenEye.z() + referenceCorrection.z - z);
    }

    private boolean cardinalPresentationStillCurrent(EntityPlayerSP player,
            World sampleWorld, CardinalCameraCandidate candidate, float partialTicks, long worldTick,
            SixDofCameraOrientation.Components target, Vec3d provenEye) {
        AcceptedCameraLook accepted = acceptedCameraLook(player);
        if (accepted == null || accepted.snapshot() != candidate.snapshot()
                || accepted.reference() == null || !accepted.reference().equals(candidate.reference())
                || !accepted.snapshot().scope().equals(candidate.snapshot().scope())
                || sampleWorld == null || minecraft.world != sampleWorld || player.world != sampleWorld
                || sampleWorld.getTotalWorldTime() != worldTick
                || !orientation.components().equals(target)) {
            return false;
        }
        Vec3d currentEye = PlayerPhysicsClient.currentProvenEyePosition(player, partialTicks).orElse(null);
        return CardinalPresentationCache.samePosition(provenEye, currentEye);
    }

    private static FlightVector cardinalEyeReferenceOffset(PlayerPhysicalSnapshot snapshot) {
        // The transport point mirrors a legacy bounds anchor and moves when Cardinal up changes.
        // Subtract physical center so only the eye's reference-relative offset is blended.
        Vec3d offset = snapshot.placement().eye().subtract(snapshot.placement().center());
        if (!Double.isFinite(offset.x()) || !Double.isFinite(offset.y()) || !Double.isFinite(offset.z())) {
            throw new IllegalStateException("Accepted Cardinal eye reference offset is non-finite");
        }
        return new FlightVector(offset.x(), offset.y(), offset.z());
    }

    private double renderTick(float partialTicks) {
        if (!Float.isFinite(partialTicks)) {
            throw new IllegalArgumentException("partialTicks must be finite");
        }
        return (double) minecraft.world.getTotalWorldTime()
                + Math.max(0.0F, Math.min(1.0F, partialTicks));
    }

    private SixDofBodyPresentation.Sample walkingPresentation(EntityPlayerSP player, float partialTicks) {
        return SixDofBodyPresentation.sample(currentAttitude(), PlayerPhysicsClient.bodyYawOffset(player, partialTicks));
    }

    private PlayerPhysicsClientPresentation.ViewBasis walkingViewBasis(EntityPlayerSP player) {
        if (PlayerPhysicsClient.isInstallationFenced(player)) return null;
        CardinalCameraCandidate cardinal = cardinalCameraCandidate(player);
        if (cardinal != null) {
            // Movement uses the visible head direction projected into the currently
            // accepted tangent plane. The reference normal and collision geometry stay
            // authoritative; only the presentation correction is shared with the view.
            CardinalCameraTransition.RenderSample rendered = prepareAcceptedCameraTarget(player, cardinal, 0.0F);
            return viewBasis(rendered.attitude());
        }
        if (isAcceptedFreeCameraLook(player)) {
            initializeFromPlayer(player);
            return viewBasis(orientation.current());
        }
        if (!isDriftAuthorized(player)) return null;
        initializeFromPlayer(player);
        return viewBasis(orientation.current());
    }

    private static PlayerPhysicsClientPresentation.ViewBasis viewBasis(
            SixDofCameraOrientation.Snapshot attitude) {
        var forward = attitude.forward();
        var right = attitude.right();
        return new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(forward.x(), forward.y(), forward.z()),
                new Vec3(right.x(), right.y(), right.z()));
    }

    private static PlayerPhysicsClientPresentation.ViewBasis viewBasis(FlightAttitude attitude) {
        var forward = attitude.forward();
        var right = attitude.right();
        return new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(forward.x, forward.y, forward.z),
                new Vec3(right.x, right.y, right.z));
    }

    private ClientBoostRouting.Plan boostPlan(EntityPlayerSP player) {
        if (PlayerPhysicsClient.isInstallationFenced(player) || !isActiveFor(player)) {
            return null;
        }
        initializeFromPlayer(player);
        return new ClientBoostRouting.Plan(
                orientation.forward(), controlGears.boostAccelerationMps2(),
                controlGears.boostSpeedLimitMps(), controlGears.boostGear(),
                alignmentBoostSuppressed());
    }

    private PlayerPhysicsProtocol.Controls physicalControls(EntityPlayerSP player, TangentViewSample tangent) {
        boolean fenced = PlayerPhysicsClient.isInstallationFenced(player);
        boolean driftAuthorized = !fenced && isDriftAuthorized(player);
        if (fenced || !driftAuthorized) {
            recordPhysicalControlsProbe(player, tangent, null, null, null, null, Optional.empty(), null,
                    false, driftAuthorized, fenced, fenced ? "installation-fenced" : "drift-not-authorized");
            return null;
        }
        initializeFromPlayer(player);
        InputFrame frame = InputApi.getFrame(0.0F);
        var attitude = orientation.current();
        SixDofMovementFrame movementFrame = tangent != null ? SixDofMovementFrame.fromSample(tangent)
                : currentMovementFrame(player, attitude);
        boolean focused = frame != null && frame.getContext() != null && frame.getContext().isGameFocused();
        boolean enabled = frame != null && frame.getSampleId() != 0 && focused
                && minecraft.currentScreen == null && owns(frame, InputAction.PLAYER_MOVE_FORWARD);
        Vec3 translation = enabled ? SixDofDriftMotion.worldIntent(movementFrame, new SixDofDriftMotion.Intent(
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Z), -ownedAxis(frame, InputAction.CAMERA_TRANSLATE_X),
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Y))) : Vec3.ZERO;
        var rawControls = PlayerPhysicsInput.world(translation,
                tangent != null ? tangent.headForward() : attitude.forward(),
                enabled && owns(frame, InputAction.PLAYER_JUMP)
                        && ClientKeyBindingState.isDown(minecraft.gameSettings.keyBindJump),
                enabled && owns(frame, InputAction.PLAYER_SNEAK)
                        && ClientKeyBindingState.isDown(minecraft.gameSettings.keyBindSneak),
                enabled && owns(frame, InputAction.PLAYER_SPRINT) && PlayerPhysicsClient.localSprintIntent(player),
                enabled && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY) && !alignmentBoostSuppressed(),
                controlGears.boostGear(), Optional.of(new Rotation3d(
                        attitude.components().x(), attitude.components().y(), attitude.components().z(), attitude.components().w())));
        Optional<PlayerBodyReference.Effective> acceptedReference =
                PlayerPhysicsClient.acceptedEffectiveBodyReference(player);
        PlayerPhysicalSnapshot accepted = PlayerPhysicsClient.currentProvenPresentation(player).orElse(null);
        boolean acceptedCardinal = accepted != null
                && accepted.placement().shape() instanceof PlayerBodyShape.Cardinal;
        PlayerMobilityIntent.State mobility = PlayerPhysicsClient.mobilityState().orElse(null);
        boolean confirmedFlight = mobility != null && mobility.mode() == PlayerMobilityIntent.Mode.FLIGHT;
        boolean cardinalWalkHeadLookOnly = acceptedCardinal && acceptedReference.isPresent() && !confirmedFlight;
        String bodyQPolicy = cardinalWalkHeadLookOnly ? "accepted-cardinal-head-look-only"
                : confirmedFlight ? "confirmed-flight-body-attitude"
                : acceptedReference.isPresent() ? "accepted-reference-policy"
                : "no-accepted-reference-policy";
        var controls = BodyAttitudeInputPolicy.apply(rawControls, enabled, acceptedReference,
                cardinalWalkHeadLookOnly, confirmedFlight);
        String disposition = frame == null || frame.getSampleId() == 0L ? "no-input-frame"
                : !focused ? "game-unfocused"
                : minecraft.currentScreen != null ? "screen-open"
                : !owns(frame, InputAction.PLAYER_MOVE_FORWARD) ? "move-forward-not-owned"
                : isZeroTranslation(controls.translation()) ? "enabled-zero-translation"
                : "enabled-controls-produced";
        recordPhysicalControlsProbe(player, tangent, frame, rawControls, controls, accepted,
                acceptedReference, mobility, enabled, driftAuthorized, false,
                disposition + "/" + bodyQPolicy);
        return controls;
    }

    /** Records the already sampled input-frame decision at the real control producer. */
    private void recordPhysicalControlsProbe(EntityPlayerSP player, TangentViewSample tangent,
            InputFrame frame, PlayerPhysicsProtocol.Controls rawControls,
            PlayerPhysicsProtocol.Controls filteredControls, PlayerPhysicalSnapshot accepted,
            Optional<PlayerBodyReference.Effective> acceptedReference, PlayerMobilityIntent.State mobility,
            boolean enabled, boolean driftAuthorized, boolean fenced, String disposition) {
        if (!UnigravMod.LOGGER.isInfoEnabled()) return;
        long tick = player == null || player.world == null ? -1L : player.world.getTotalWorldTime();
        boolean focused = frame != null && frame.getContext() != null && frame.getContext().isGameFocused();
        long sampleId = frame == null ? 0L : frame.getSampleId();
        Object moveForwardOwner = frame == null ? null : frame.owner(InputAction.PLAYER_MOVE_FORWARD);
        Object moveStrafeOwner = frame == null ? null : frame.owner(InputAction.PLAYER_MOVE_STRAFE);
        Object translateXOwner = frame == null ? null : frame.owner(InputAction.CAMERA_TRANSLATE_X);
        Object translateYOwner = frame == null ? null : frame.owner(InputAction.CAMERA_TRANSLATE_Y);
        Object translateZOwner = frame == null ? null : frame.owner(InputAction.CAMERA_TRANSLATE_Z);
        Vec3d finalTranslation = filteredControls == null ? null : filteredControls.translation();
        boolean rawBodyQ = rawControls != null && rawControls.desiredBodyAttitude().isPresent();
        boolean filteredBodyQ = filteredControls != null
                && filteredControls.desiredBodyAttitude().isPresent();
        PlayerPhysicalSnapshot.Scope scope = accepted == null ? null : accepted.scope();
        long revision = accepted == null ? -1L : accepted.revision();
        long viewSequence = tangent == null ? 0L : tangent.viewSequence();
        String referenceFrame = acceptedReference == null ? "unavailable"
                : acceptedReference.map(value -> value.frame().name()).orElse("none");
        boolean screenOpen = minecraft.currentScreen != null;
        long tx = finalTranslation == null ? 0L : Double.doubleToRawLongBits(finalTranslation.x());
        long ty = finalTranslation == null ? 0L : Double.doubleToRawLongBits(finalTranslation.y());
        long tz = finalTranslation == null ? 0L : Double.doubleToRawLongBits(finalTranslation.z());
        var key = new PhysicalControlsProbeKey(scope, revision, viewSequence, focused, screenOpen,
                enabled, driftAuthorized, fenced, moveForwardOwner, moveStrafeOwner,
                translateXOwner, translateYOwner, translateZOwner, tx, ty, tz,
                rawBodyQ, filteredBodyQ, mobility == null ? null : mobility.mode(), referenceFrame,
                disposition);
        boolean stateChanged = !Objects.equals(lastPhysicalControlsProbeKey, key);
        if (stateChanged) lastPhysicalControlsProbeKey = key;
        boolean summaryElapsed = lastPhysicalControlsProbeTick == Long.MIN_VALUE
                || tick >= lastPhysicalControlsProbeTick
                        && tick - lastPhysicalControlsProbeTick >= PHYSICAL_CONTROLS_PROBE_SUMMARY_INTERVAL_TICKS;
        boolean rateWindowOpen = lastPhysicalControlsProbeTick == Long.MIN_VALUE
                || tick >= lastPhysicalControlsProbeTick
                        && tick - lastPhysicalControlsProbeTick >= PHYSICAL_CONTROLS_PROBE_MIN_INTERVAL_TICKS;
        if (!stateChanged && !summaryElapsed) return;
        if (!rateWindowOpen && !summaryElapsed) {
            if (suppressedPhysicalControlsProbeCount < Long.MAX_VALUE)
                suppressedPhysicalControlsProbeCount++;
            suppressedPhysicalControlsProbeOutcome = disposition;
            return;
        }
        long coalesced = suppressedPhysicalControlsProbeCount;
        String coalescedOutcome = suppressedPhysicalControlsProbeOutcome;
        suppressedPhysicalControlsProbeCount = 0L;
        suppressedPhysicalControlsProbeOutcome = "none";
        lastPhysicalControlsProbeTick = tick;
        String rawTranslation = rawControls == null ? "none" : vector(rawControls.translation());
        String outputTranslation = finalTranslation == null ? "none" : vector(finalTranslation);
        UnigravMod.LOGGER.info("camera probe stage=physical-controls tick={} scope={} revision={} viewSeq={} inputSample={}"
                        + " driftAuthorized={} fenced={} focused={} screenOpen={} enabled={}"
                        + " moveOwnerForward={} moveOwnerStrafe={} translateOwners={},{},{}"
                        + " rawTranslation={} finalTranslation={} bodyQPolicy={} rawBodyQPresent={}"
                        + " filteredBodyQPresent={} mobility={} acceptedShape={} referenceFrame={}"
                        + " disposition={} coalesced={} coalescedLastOutcome={}",
                tick, scope == null ? "none" : scope, revision, viewSequence, sampleId,
                driftAuthorized, fenced, focused, screenOpen, enabled,
                String.valueOf(moveForwardOwner), String.valueOf(moveStrafeOwner),
                String.valueOf(translateXOwner), String.valueOf(translateYOwner), String.valueOf(translateZOwner),
                rawTranslation, outputTranslation,
                disposition.contains("/") ? disposition.substring(disposition.indexOf('/') + 1) : "none",
                rawBodyQ, filteredBodyQ, mobility == null ? "unavailable" : mobility.mode().name(),
                accepted == null ? "unavailable" : accepted.placement().shape().getClass().getSimpleName(),
                referenceFrame, disposition, coalesced, coalescedOutcome);
    }

    /** Pumps deferred GL cleanup even after leaving a world, when RWL no longer fires. */
    @SubscribeEvent
    public void onPreviewRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            SixDofGroundFacePreview.lifecycle(minecraft.world);
        }
    }

    @SubscribeEvent
    public void onPreviewWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            if (cameraSession.unloadWorld(event.getWorld())) {
                clearAttitude();
            }
            SixDofGroundFacePreview.invalidate();
        }
    }

    /** Final picking uses this render's shared camera sample, never a tick's cached frame. */
    @SubscribeEvent
    public void onRenderGroundFace(RenderWorldLastEvent event) {
        if (minecraft.currentScreen != null || minecraft.isGamePaused()
                || !minecraft.inGameHasFocus || !isActiveFor(minecraft.player)) {
            SixDofGroundFacePreview.clear();
            return;
        }
        InputFrame frame = InputApi.getFrame(event.getPartialTicks());
        if (frame.getSampleId() == 0L || !frame.getContext().isGameFocused()
                || !owns(frame, InputAction.CAMERA_LOOK_X)
                || !owns(frame, InputAction.CAMERA_LOOK_Y)) {
            SixDofGroundFacePreview.clear();
            return;
        }
        sampleAlignmentCommands(true, event.getPartialTicks());
        if (alignmentInput.selecting() && groundFacePreview != null
                && !minecraft.gameSettings.hideGUI) {
            SixDofGroundFacePreview.render(groundFacePreview, event.getPartialTicks());
        } else {
            SixDofGroundFacePreview.clear();
        }
    }

    private void sampleAlignmentCommands(boolean finishSelection, float partialTicks) {
        EntityPlayerSP statusPlayer = minecraft.player;
        boolean toggleHeld = ClientKeyBindingState.isDown(ALIGNMENT_TOGGLE_KEY);
        boolean selectHeld = ClientKeyBindingState.isDown(GROUND_FACE_SELECT_KEY);
        SixDofAlignmentInput.Reference candidate = alignmentInput.previewReference();
        if (finishSelection) {
            groundFacePreview = (selectHeld || alignmentInput.selecting()) && !toggleHeld
                    ? SixDofGroundFacePreview.sample(partialTicks) : null;
            candidate = groundFacePreview == null ? null : groundFacePreview.reference();
        }
        SixDofAlignmentInput.Change change = finishSelection
                ? alignmentInput.update(toggleHeld, selectHeld, candidate)
                : alignmentInput.updateBeforePicking(toggleHeld, selectHeld);
        movementFrameLock.apply(change, alignmentInput, orientation.current());
        if (change.committed() && statusPlayer != null) {
            SixDofMovementFrame committed = movementFrameLock.current(true, orientation.current());
            var request = PlayerPhysicsClient.requestBodyReferenceLock(
                    alignmentInput.reference().cardinalFrame(),
                    new dev.crlhitbox.api.geometry.Vec3d(committed.forward().x(),
                            committed.forward().y(), committed.forward().z()));
            statusPlayer.sendStatusMessage(new TextComponentString(request.isPresent()
                    ? "UniGrav body reference lock requested #" + request.getAsLong()
                    : "UniGrav body reference lock not sent: no active physical owner"), true);
        } else if (change.enabledChanged() && !alignmentInput.enabled() && statusPlayer != null) {
            var request = PlayerPhysicsClient.requestBodyReferenceUnlock();
            statusPlayer.sendStatusMessage(new TextComponentString(request.isPresent()
                    ? "UniGrav body reference unlock requested #" + request.getAsLong()
                    : "UniGrav body reference unlock not sent: no active physical owner"), true);
        }
        if (!alignmentInput.selecting()) {
            groundFacePreview = null;
            SixDofGroundFacePreview.clear();
        }
        if (change.enabledChanged() || change.referenceChanged() || change.committed()) {
            alignmentDiagnostics = null;
            alignmentPitchLimited = false;
            if (statusPlayer != null) {
                statusPlayer.sendStatusMessage(new TextComponentString(String.format(Locale.ROOT,
                        "UniGrav alignment: %s UP %s", alignmentInput.enabled() ? "ON" : "OFF",
                        alignmentInput.reference().label())), true);
            }
        }
        if (!effectiveAlignment()) {
            alignmentDiagnostics = null;
            alignmentPitchLimited = false;
        }
    }

    private boolean effectiveAlignment() {
        return alignmentInput.effectiveAlignment(ClientKeyBindingState.isDown(GROUND_FACE_SELECT_KEY));
    }

    private static boolean alignmentBoostSuppressed() {
        return ClientKeyBindingState.isDown(ALIGNMENT_TOGGLE_KEY);
    }

    /** Adds instruments without enabling UIE's optional overlay suppression policies. */
    @SubscribeEvent
    public void onRenderFlightHud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL
                || !UnigravConfig.sixDofHud || minecraft.gameSettings.hideGUI
                || minecraft.gameSettings.showDebugInfo
                || minecraft.currentScreen != null || !minecraft.inGameHasFocus
                || minecraft.isGamePaused() || !isActiveFor(minecraft.player)) {
            return;
        }
        SixDofHudFrame frame = hudFrame(event.getPartialTicks());
        if (frame != null) {
            RevoUiSixDofHud.render(minecraft, frame);
        }
    }

    /**
     * Samples display values only. UIE has already selected this frame's quaternion;
     * no live Euler fallback, input consumption or physics update belongs in the HUD.
     */
    private SixDofHudFrame hudFrame(float partialTicks) {
        EntityPlayerSP player = minecraft.player;
        FlightRenderPose pose = FlightApi.getRenderPose(player, partialTicks);
        if (pose == null) {
            return null;
        }
        FlightEulerAngles angles = pose.getCameraAngles();
        InputFrame input = InputApi.getFrame(partialTicks);
        boolean driftAuthorized = isDriftAuthorized(player);
        boolean driftInput = driftAuthorized && input.getSampleId() != 0L
                && input.getContext().isGameFocused()
                && owns(input, InputAction.PLAYER_MOVE_FORWARD)
                && (ownedAxis(input, InputAction.CAMERA_TRANSLATE_X) != 0.0F
                || ownedAxis(input, InputAction.CAMERA_TRANSLATE_Y) != 0.0F
                || ownedAxis(input, InputAction.CAMERA_TRANSLATE_Z) != 0.0F);
        boolean boostInput = ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY)
                && !alignmentBoostSuppressed();
        SixDofHudFrame.ThrustInput thrust = boostInput
                ? (driftInput ? SixDofHudFrame.ThrustInput.BOOST_AND_DRIFT
                : SixDofHudFrame.ThrustInput.BOOST)
                : (driftInput ? SixDofHudFrame.ThrustInput.DRIFT : SixDofHudFrame.ThrustInput.NONE);
        double speedMps = Math.hypot(Math.hypot(player.motionX, player.motionY), player.motionZ)
                * GravityEntityBinder.TICKS_PER_SECOND;
        double selectedBoostMps = controlGears.boostSpeedLimitMps();
        boolean remote = !minecraft.isIntegratedServerRunning()
                && RemoteBoostClient.hasCustody(player);
        double carrierMps = remote ? RemoteBoostClient.currentCeilingMps(player) : selectedBoostMps;
        boolean restricted = remote && RemoteBoostClient.inRestrictedCorridor(player);
        double targetBoostMps = remote
                ? RemoteBoostClient.propulsionTargetMps(player, selectedBoostMps) : selectedBoostMps;
        return new SixDofHudFrame(
                new SixDofHudFrame.Attitude(angles.pitchDegrees, angles.yawDegrees, angles.rollDegrees),
                speedMps, driftAuthorized, thrust,
                new SixDofHudFrame.Boost(controlGears.boostGear(), controlGears.boostGearCount(),
                        selectedBoostMps, targetBoostMps, carrierMps, restricted),
                new SixDofHudFrame.Roll(controlGears.rollGear(),
                        controlGears.rollMaximumSpeedDegreesPerSecond(),
                        rollDynamics.angularVelocityDegreesPerSecond(), currentControls().rollAxis()),
                new SixDofHudFrame.Alignment(alignmentInput.enabled(), alignmentInput.reference(),
                        alignmentInput.selecting(), alignmentInput.previewReference()));
    }

    /** Adds local full-quaternion diagnostics without querying Flight API providers. */
    @SubscribeEvent
    public void onRenderDebugText(RenderGameOverlayEvent.Text event) {
        EntityPlayerSP player = minecraft.player;
        if (closed || !minecraft.gameSettings.showDebugInfo || !isActiveFor(player)) {
            return;
        }
        SixDofPhysicalControls.State controls = currentControls();
        event.getLeft().addAll(SixDofCameraDebugText.format(
                orientation.current(), minecraft.gameSettings.invertMouse,
                new SixDofCameraDebugText.RollState(
                        true,
                        (float) controls.rollAxis(),
                        rollDynamics.angularVelocityDegreesPerSecond()),
                new SixDofCameraDebugText.GearState(
                        controlGears.rollGear(), controlGears.rollGearCount(),
                        controlGears.rollMaximumSpeedDegreesPerSecond(),
                        controlGears.rollAccelerationDegreesPerSecondSquared(),
                        controlGears.boostGear(), controlGears.boostGearCount(),
                        controlGears.boostSpeedLimitMps(),
                        controlGears.boostAccelerationMps2()),
                new SixDofCameraDebugText.AlignmentState(alignmentInput.enabled(),
                        alignmentInput.selecting(), alignmentInput.reference(),
                        alignmentInput.previewReference(), effectiveAlignment(),
                        alignmentPitchLimited, alignmentDiagnostics)));
        if (!minecraft.isIntegratedServerRunning() && RemoteBoostClient.hasCustody(player)) {
            event.getLeft().add(String.format(Locale.ROOT,
                    "UG remote carrier=%.2fm/s corridor=%s", RemoteBoostClient.currentCeilingMps(player),
                    RemoteBoostClient.inRestrictedCorridor(player) ? "RESTRICTED" : "OPEN"));
        }
        if (alignmentInput.selecting()) {
            event.getLeft().add("UG " + SixDofGroundFacePreview.depthStatus());
        }
    }

    /** Physical Free presentation does not acquire the optional G camera/input authority. */
    private FlightAttitude freePresentationView(EntityPlayerSP player) {
        if (isAcceptedFreeCameraLook(player) || isDriftAuthorized(player)) {
            clearCardinalPresentation();
            initializeFromPlayer(player);
            return currentAttitude();
        }
        clearCardinalPresentation();
        return FlightAttitude.fromMinecraftDegrees(player.rotationPitch, player.rotationYaw, 0);
    }

    private static boolean higherCameraRigActive() {
        return CameraApi.isDroneActive() || CameraApi.isFreeLookActive() || CameraApi.isShoulderActive();
    }

    private FlightAttitude currentAttitude() {
        SixDofCameraOrientation.Components value = orientation.components();
        return new FlightAttitude(value.x(), value.y(), value.z(), value.w());
    }

    private SixDofMovementFrame currentMovementFrame(EntityPlayerSP player) {
        return currentMovementFrame(player, orientation.current());
    }

    private SixDofMovementFrame currentMovementFrame(EntityPlayerSP player, SixDofCameraOrientation.Snapshot sampled) {
        var tangent = PlayerPhysicsClient.currentTangentView(player);
        if (tangent.isPresent()) return SixDofMovementFrame.fromSample(tangent.orElseThrow());
        return SixDofMovementFrame.fromCamera(sampled);
    }

    private CameraEyeAnchor eyeAnchor(CameraEyeAnchorContext context) {
        if (closed || context == null) return null;
        EntityPlayerSP player = context.player();
        if (player == null || minecraft.world == null || player != minecraft.player
                || player.world != minecraft.world || !player.isEntityAlive() || player.isDead
                || PlayerKinematics.requiresNative(player)
                || PlayerPhysicsClient.isInstallationFenced(player)) {
            clearCardinalPresentation();
            return null;
        }
        // Let UIE camera rigs with higher priority own their frame and anchor.
        if (higherCameraRigActive()) return null;
        CardinalCameraCandidate cardinal = cardinalCameraCandidate(player);
        if (cardinal != null) {
            SixDofBodyPresentation.Sample sample = cardinalPresentation(player,
                    context.partialTicks(), cardinal);
            if (sample == null || cardinalPresentationCache == null
                    || cardinalPresentationCache.player != player
                    || cardinalPresentationCache.world != player.world
                    || cardinalPresentationCache.snapshot != cardinal.snapshot()
                    || !cardinalPresentationCache.reference.equals(cardinal.reference())
                    || cardinalPresentationCache.partialBits
                            != Float.floatToIntBits(context.partialTicks())
                    || cardinalPresentationCache.worldTick != player.world.getTotalWorldTime()
                    || cardinalPresentationCache.eyeAnchor == null) return null;
            return cardinalPresentationCache.eyeAnchor;
        }
        clearCardinalPresentation();
        var eye = PlayerPhysicsClient.currentProvenEyePosition(player, context.partialTicks());
        if (eye.isEmpty()) return null;
        var position = eye.orElseThrow();
        return new CameraEyeAnchor(position.x(), position.y(), position.z());
    }

    private static CameraEyeAnchor eyeAnchor(World world, Vec3d provenEye,
            FlightVector referenceCorrection) {
        net.minecraft.util.math.Vec3d resolved = CardinalEyeAnchorPresentation.resolve(
                world, provenEye, referenceCorrection);
        return new CameraEyeAnchor(resolved.x, resolved.y, resolved.z);
    }

    private void reportBodyReferenceStatus() {
        EntityPlayerSP player = minecraft.player;
        if (closed || player == null) return;
        var status = PlayerPhysicsClient.bodyReferenceStatus().orElse(null);
        var effective = PlayerPhysicsClient.acceptedEffectiveBodyReference(player).orElse(null);
        if (java.util.Objects.equals(status, lastBodyReferenceStatus)
                && java.util.Objects.equals(effective, lastEffectiveBodyReference)) return;
        lastBodyReferenceStatus = status;
        lastEffectiveBodyReference = effective;
        if (!java.util.Objects.equals(effective, null)) {
            player.sendStatusMessage(new TextComponentString("UniGrav effective body reference: "
                    + effective.source() + " " + effective.frame()), true);
        } else if (status != null) {
            player.sendStatusMessage(new TextComponentString("UniGrav body reference request: "
                    + status.status() + status.reason().map(reason -> " (" + reason + ")").orElse("")), true);
        }
    }

    private void initializeFromPlayer(EntityPlayerSP player) {
        if (!orientation.isInitialized()) {
            orientation.reset(player.rotationPitch, player.rotationYaw);
        }
    }

    private void clearAttitude() {
        orientation.clear();
        clearCardinalPresentation();
        alignmentInput.disable();
        movementFrameLock.clear();
        suspendControls();
    }

    private void clearCardinalPresentation() {
        cardinalCameraTransition.clear();
        cardinalPresentationCache = null;
    }

    /**
     * A voluntary remote G-off releases every UIE provider/input claim while
     * the protocol's CLOSING session continues its own packet pairing. Keep
     * the local quaternion representation untouched in that narrow handoff;
     * this method never writes player yaw, pitch, roll or motion.
     */
    private void deactivateInactiveCamera(EntityPlayerSP player) {
        if (isAcceptedPhysicalCameraLook(player)) {
            if (cardinalCameraCandidate(player) == null) clearCardinalPresentation();
            suspendControls();
            return;
        }
        if (RemoteBoostClient.hasCustody(player)
                && !ClientGravityTicker.isPersonalZeroGActive(player)) {
            suspendControls();
            return;
        }
        clearAttitude();
    }

    private void suspendControls() {
        rollDynamics.clear();
        controlGears.suspend();
        alignmentInput.suspend();
        alignmentDiagnostics = null;
        alignmentPitchLimited = false;
        groundFacePreview = null;
        SixDofGroundFacePreview.clear();
    }

    private void updateControlGears(EntityPlayerSP player, SixDofPhysicalControls.State controls) {
        SixDofControlGears.Change change = controlGears.update(
                controls.rollGearUp(), controls.rollGearDown(),
                controls.boostGearUp(), controls.boostGearDown());
        if (!change.rollChanged() && !change.boostChanged()) {
            return;
        }
        player.sendStatusMessage(new TextComponentString(String.format(java.util.Locale.ROOT,
                "UniGrav gears: roll %d/%d %.0f°/s | boost %d/%d %.2f m/s (%.2f m/s²)",
                controlGears.rollGear(), controlGears.rollGearCount(),
                controlGears.rollMaximumSpeedDegreesPerSecond(), controlGears.boostGear(),
                controlGears.boostGearCount(), controlGears.boostSpeedLimitMps(),
                controlGears.boostAccelerationMps2())), true);
    }

    private static SixDofPhysicalControls.State currentControls() {
        return SixDofPhysicalControls.resolve(
                ClientKeyBindingState.isDown(ROLL_LEFT_KEY),
                ClientKeyBindingState.isDown(ROLL_RIGHT_KEY),
                ClientKeyBindingState.isDown(ROLL_GEAR_DOWN_KEY),
                ClientKeyBindingState.isDown(ROLL_GEAR_UP_KEY),
                ClientKeyBindingState.isDown(BOOST_GEAR_DOWN_KEY),
                ClientKeyBindingState.isDown(BOOST_GEAR_UP_KEY));
    }

    /** Creative personal zero-G is the only current thrust permit. */
    private boolean isDriftAuthorized(EntityPlayerSP player) {
        return isActiveFor(player) && ClientGravityTicker.isPersonalZeroGActive(player);
    }

    private boolean isActiveFor(EntityPlayerSP player) {
        if (closed || player == null || player != minecraft.player || minecraft.world == null) {
            return false;
        }
        // UIE's built-in rigs have a higher-level camera session and input context. Yielding
        // here avoids accumulating a hidden quaternion that jumps into view when they close.
        if (CameraApi.isDroneActive() || CameraApi.isFreeLookActive()
                || CameraApi.isShoulderActive()) {
            return false;
        }
        boolean personalZeroG = ClientGravityTicker.isPersonalZeroGActive(player);
        boolean remoteCustody = RemoteBoostClient.hasCustody(player) || PlayerPhysicsClient.owns(player);
        Vec3 acceleration = null;
        if (minecraft.isIntegratedServerRunning() && !personalZeroG && !player.capabilities.isFlying) {
            Vec3 position = new Vec3(player.posX, player.posY, player.posZ);
            double time = player.world.getTotalWorldTime() * TickDriver.TICK_SECONDS;
            acceleration = EntityGravitySources.fieldAcceleration(player, position, time);
        }
        return SixDofCameraActivation.shouldActivate(
                UnigravConfig.sixDofCamera,
                minecraft.isIntegratedServerRunning(),
                remoteCustody,
                binder.isBound(player),
                !player.isDead,
                player.isPlayerSleeping(),
                player.isSpectator(),
                player.isCreative(),
                player.capabilities.isFlying,
                minecraft.gameSettings.debugCamEnable,
                personalZeroG,
                UnigravConfig.sixDofCameraGravityThreshold,
                acceleration);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        failure = cleanup(failure, SixDofGroundFacePreview::invalidate);
        failure = cleanup(failure, () -> SixDofGroundFacePreview.lifecycle(null));
        failure = cleanup(failure, () -> MinecraftForge.EVENT_BUS.unregister(this));
        failure = closeFlight(failure, bodyPoseRegistration);
        failure = closeFlight(failure, trackingRegistration);
        failure = closeFlight(failure, maneuverRegistration);
        failure = closeFlight(failure, capabilityRegistration);
        failure = closeCamera(failure, eyeAnchorRegistration);
        failure = closeCamera(failure, pickGeometryRegistration);
        failure = closeInput(failure, inputRegistration);
        failure = closeBoost(failure, boostRegistration);
        failure = closeBoost(failure, physicalRegistration);
        failure = closeBoost(failure, viewBasisRegistration);
        trackingRegistration = null;
        bodyPoseRegistration = null;
        eyeAnchorRegistration = null;
        pickGeometryRegistration = null;
        maneuverRegistration = null;
        capabilityRegistration = null;
        inputRegistration = null;
        boostRegistration = null;
        physicalRegistration = null;
        viewBasisRegistration = null;
        failure = cleanup(failure, this::clearAttitude);
        failure = cleanup(failure, cameraSession::clear);
        failure = cleanup(failure, () -> {
            synchronized (RevoUiSixDofCamera.class) {
                if (installed == this) {
                    installed = null;
                }
            }
        });
        rethrowCleanupFailure(failure);
    }

    private static InputContext createInputContext(boolean drift,
            boolean blockRoll, boolean blockForward) {
        InputContext.Builder builder = InputContext.builder(ID, PRIORITY)
                .claim(InputAction.CAMERA_LOOK_X, InputAction.CAMERA_LOOK_Y,
                        InputAction.FLIGHT_PITCH,
                        InputAction.FLIGHT_YAW, InputAction.FLIGHT_ROLL,
                        InputAction.FLIGHT_RUDDER)
                .block(InputAction.PLAYER_SPRINT);
        if (blockRoll) {
            builder.block(InputAction.CAMERA_ROLL);
        } else {
            builder.claim(InputAction.CAMERA_ROLL);
        }
        if (drift) {
            builder.claim(InputAction.CAMERA_TRANSLATE_X,
                            InputAction.CAMERA_TRANSLATE_Y)
                    .block(InputAction.PLAYER_MOVE_FORWARD,
                            InputAction.PLAYER_MOVE_STRAFE,
                            InputAction.PLAYER_JUMP,
                            InputAction.PLAYER_SNEAK);
            if (blockForward) {
                builder.block(InputAction.CAMERA_TRANSLATE_Z);
            } else {
                builder.claim(InputAction.CAMERA_TRANSLATE_Z);
            }
        } else if (blockForward) {
            builder.block(InputAction.CAMERA_TRANSLATE_Z,
                    InputAction.PLAYER_MOVE_FORWARD);
        }
        return builder.build();
    }

    private static InputContext selectInputContext(boolean drift,
            boolean blockRoll, boolean blockForward) {
        if (drift) {
            if (blockRoll && blockForward) return DRIFT_BOTH_GEARS_CONTEXT;
            if (blockRoll) return DRIFT_ROLL_GEAR_CONTEXT;
            if (blockForward) return DRIFT_BOOST_GEAR_CONTEXT;
            return DRIFT_INPUT_CONTEXT;
        }
        if (blockRoll && blockForward) return CAMERA_BOTH_GEARS_CONTEXT;
        if (blockRoll) return CAMERA_ROLL_GEAR_CONTEXT;
        if (blockForward) return CAMERA_BOOST_GEAR_CONTEXT;
        return CAMERA_INPUT_CONTEXT;
    }

    private static KeyBinding rollKey(String description, int keyCode) {
        return new KeyBinding(description, KeyConflictContext.IN_GAME,
                KeyModifier.NONE, keyCode, "Universal Gravitation");
    }

    private static KeyBinding gearKey(String description, int keyCode) {
        return new KeyBinding(description, KeyConflictContext.IN_GAME,
                KeyModifier.ALT, keyCode, "Universal Gravitation");
    }

    private static boolean owns(InputFrame frame, InputAction action) {
        return frame != null && ID.equals(frame.owner(action));
    }

    private static float ownedAxis(InputFrame frame, InputAction action) {
        return owns(frame, action) ? frame.get(action).getAxis() : 0.0F;
    }

    private static Throwable closeFlight(Throwable failure, FlightRegistration registration) {
        return registration == null ? failure : cleanup(failure, registration::close);
    }

    private static Throwable closeInput(Throwable failure, InputRegistration registration) {
        return registration == null ? failure : cleanup(failure, registration::close);
    }

    private static Throwable closeCamera(Throwable failure, CameraRegistration registration) {
        return registration == null ? failure : cleanup(failure, registration::close);
    }

    private static Throwable closeBoost(Throwable failure, ClientBoostRouting.Registration registration) {
        return registration == null ? failure : cleanup(failure, registration::close);
    }

    private static Throwable cleanup(Throwable failure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error cleanup) {
            if (failure == null) return cleanup;
            if (failure != cleanup) failure.addSuppressed(cleanup);
        }
        return failure;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error errorFailure) throw errorFailure;
    }

    private record CameraLookSelection(AcceptedCameraLook accepted, String reason) {
        private CameraLookSelection {
            java.util.Objects.requireNonNull(reason, "reason");
        }

        private static CameraLookSelection available(AcceptedCameraLook accepted) {
            return new CameraLookSelection(java.util.Objects.requireNonNull(accepted, "accepted"), "accepted");
        }

        private static CameraLookSelection unavailable(String reason) {
            return new CameraLookSelection(null, reason);
        }
    }

    private record AcceptedCameraLook(PlayerPhysicalSnapshot snapshot,
            PlayerBodyReference.Effective reference, long viewSequence,
            TangentViewSample tangentSample) { }

    private record CameraLookInputProbe(long sequence, long sampleId, long worldTick,
            String scope, long revision, long viewSequence, String outcome) { }

    private record PhysicalControlsProbeKey(PlayerPhysicalSnapshot.Scope scope, long revision,
            long viewSequence, boolean focused, boolean screenOpen, boolean enabled,
            boolean driftAuthorized, boolean fenced, Object moveForwardOwner, Object moveStrafeOwner,
            Object translateXOwner, Object translateYOwner, Object translateZOwner,
            long translationXBits, long translationYBits, long translationZBits,
            boolean rawBodyQ, boolean filteredBodyQ, PlayerMobilityIntent.Mode mobilityMode,
            String referenceFrame, String disposition) { }

    private record CardinalCameraCandidate(PlayerPhysicalSnapshot snapshot,
            PlayerBodyReference.Effective reference, long viewSequence) { }

    private static final class CardinalPresentationCache {
        private final EntityPlayerSP player;
        private final World world;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerBodyReference.Effective reference;
        private final long worldTick;
        private final int partialBits;
        private final double renderTick;
        private final SixDofCameraOrientation.Components target;
        private final Vec3d provenEye;
        private final CameraEyeAnchor eyeAnchor;
        private final SixDofBodyPresentation.Sample sample;

        private CardinalPresentationCache(EntityPlayerSP player, World world,
                PlayerPhysicalSnapshot snapshot, PlayerBodyReference.Effective reference,
                long worldTick, float partialTicks, double renderTick,
                SixDofCameraOrientation.Components target, Vec3d provenEye,
                CameraEyeAnchor eyeAnchor, SixDofBodyPresentation.Sample sample) {
            this.player = player;
            this.world = world;
            this.snapshot = snapshot;
            this.reference = reference;
            this.worldTick = worldTick;
            partialBits = Float.floatToIntBits(partialTicks);
            this.renderTick = renderTick;
            this.target = target;
            this.provenEye = provenEye;
            this.eyeAnchor = eyeAnchor;
            this.sample = sample;
        }

        private boolean matches(EntityPlayerSP player, World world, PlayerPhysicalSnapshot snapshot,
                PlayerBodyReference.Effective reference, long worldTick, float partialTicks,
                double renderTick, SixDofCameraOrientation.Components target, Vec3d provenEye) {
            return this.player == player && this.world == world && this.snapshot == snapshot
                    && this.reference.equals(reference) && this.worldTick == worldTick
                    && partialBits == Float.floatToIntBits(partialTicks)
                    && Double.doubleToLongBits(this.renderTick) == Double.doubleToLongBits(renderTick)
                    && this.target.equals(target) && samePosition(this.provenEye, provenEye);
        }

        private static boolean samePosition(Vec3d first, Vec3d second) {
            if (first == null || second == null) return first == second;
            return Double.doubleToLongBits(first.x()) == Double.doubleToLongBits(second.x())
                    && Double.doubleToLongBits(first.y()) == Double.doubleToLongBits(second.y())
                    && Double.doubleToLongBits(first.z()) == Double.doubleToLongBits(second.z());
        }
    }
}
