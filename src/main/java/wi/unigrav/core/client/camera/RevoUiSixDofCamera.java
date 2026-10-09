package wi.unigrav.core.client.camera;

import java.util.Locale;

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
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.TickDriver;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.client.ClientBoostRouting;
import wi.unigrav.core.client.PlayerRenderPoseMirror;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.PlayerPhysicsInput;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;
import wi.unigrav.core.client.ClientGravityTicker;
import wi.unigrav.core.client.ClientKeyBindingState;
import wi.unigrav.core.client.boost.RemoteBoostClient;

/**
 * Direct client adapter from Universal Gravitation's zero-G state to Revo UI
 * Flight API v9+ and Input API v1. Model-up body presentation requires Flight API v10.
 * Revo UI remains the sole owner of Minecraft
 * camera, input, picking, and rendering Mixins; this adapter owns one local
 * orientation and the player-anchored free-drift impulse derived from it.
 */
public final class RevoUiSixDofCamera implements AutoCloseable {

    public static final String UIE_MOD_ID = "neofontrender_ui_enhancements";
    private static final int REQUIRED_CAMERA_API = 2;
    private static final int REQUIRED_FLIGHT_API = 9;
    private static final int REQUIRED_INPUT_API = 1;
    private static final int PRIORITY = 500;
    private static final ResourceLocation ID = new ResourceLocation(
            Reference.MOD_ID, "six_dof_camera");
    private static final InputContext CAMERA_INPUT_CONTEXT = createInputContext(false, false, false);
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
    private final SixDofRollDynamics rollDynamics = new SixDofRollDynamics();
    private final SixDofControlGears controlGears = new SixDofControlGears();
    private final SixDofAlignmentInput alignmentInput = new SixDofAlignmentInput();
    private SixDofCameraOrientation.HorizonResult alignmentDiagnostics;
    private boolean alignmentPitchLimited;
    private SixDofGroundFacePreview.Target groundFacePreview;

    private FlightRegistration capabilityRegistration;
    private FlightRegistration maneuverRegistration;
    private FlightRegistration trackingRegistration;
    private FlightRegistration bodyPoseRegistration;
    private InputRegistration inputRegistration;
    private ClientBoostRouting.Registration boostRegistration;
    private ClientBoostRouting.Registration physicalRegistration;
    private ClientBoostRouting.Registration viewBasisRegistration;
    private EntityPlayerSP activePlayer;
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
            adapter.boostRegistration = ClientBoostRouting.register(adapter::boostPlan);
            adapter.physicalRegistration = ClientBoostRouting.registerPhysical(adapter::physicalControls);
            adapter.viewBasisRegistration = ClientBoostRouting.registerViewBasis(adapter::walkingViewBasis);
            SixDofGroundFacePreview.installReloadListener();
            MinecraftForge.EVENT_BUS.register(adapter);
            installed = adapter;
            return adapter;
        } catch (RuntimeException | Error failure) {
            adapter.close();
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
            return;
        }
        EntityPlayerSP player = minecraft.player;
        if (player != activePlayer) {
            clearAttitude();
            activePlayer = player;
        }
        if (!isActiveFor(player)) {
            deactivateInactiveCamera(player);
            return;
        }
        initializeFromPlayer(player);
    }

    /** Maps the physical mouse to FREE local yaw/pitch before UIE's legacy Flight listener. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCameraMouseInput(CameraMouseInputEvent event) {
        EntityPlayerSP player = event.getPlayer();
        if (closed || minecraft.currentScreen != null || minecraft.isGamePaused()
                || !minecraft.inGameHasFocus || !isActiveFor(player)) {
            return;
        }
        InputFrame frame = InputApi.getFrame(0.0F);
        if (!owns(frame, InputAction.CAMERA_LOOK_X)
                || !owns(frame, InputAction.CAMERA_LOOK_Y)) {
            return;
        }
        initializeFromPlayer(player);
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
    }

    private FlightDecision capability(EntityPlayerSP player, FlightCapability capability,
                                      boolean builtInDefault) {
        if (!isActiveFor(player)) {
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
        if (!isActiveFor(player)) {
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
        if (closed || frame == null || !frame.isGameFocused()
                || minecraft.currentScreen != null || !isActiveFor(player)) {
            return null;
        }
        SixDofPhysicalControls.State controls = currentControls();
        return selectInputContext(isDriftAuthorized(player),
                true, controls.boostGearUp() || controls.boostGearDown());
    }

    private void applyRollAndDrift() {
        EntityPlayerSP player = minecraft.player;
        if (minecraft.isGamePaused() || minecraft.currentScreen != null
                || !minecraft.inGameHasFocus) {
            suspendControls();
            return;
        }
        if (!isActiveFor(player)) {
            deactivateInactiveCamera(player);
            return;
        }
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
                orientation.current(), intent, velocity,
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
        if (!isActiveFor(player)) {
            return null;
        }
        initializeFromPlayer(player);
        return SixDofBodyPresentation.tracking(currentAttitude());
    }

    private FlightBodyPose bodyPose(AbstractClientPlayer player, float partialTicks) {
        if (closed || player == null || player.world != minecraft.world || !player.isEntityAlive()
                || player.isDead || PlayerKinematics.requiresNative(player)) return null;
        if (!(player instanceof EntityPlayerSP localPlayer) || localPlayer != minecraft.player) {
            return PlayerRenderPoseMirror.sample(player, partialTicks).orElse(null);
        }
        if (isActiveFor(localPlayer)) {
            initializeFromPlayer(localPlayer);
            return walkingPresentation(localPlayer, partialTicks).body();
        }
        var accepted = PlayerPhysicsClient.acceptedPresentation(localPlayer);
        if (accepted.isEmpty()) return null;
        var look = localPlayer.getLook(partialTicks);
        return SixDofBodyPresentation.sampleCardinal(accepted.orElseThrow(),
                new FlightVector(look.x, look.y, look.z), localPlayer.prevRenderYawOffset,
                localPlayer.renderYawOffset, partialTicks).body();
    }

    /** Reports the already-integrated active camera sample, including first-person frames with no body render. */
    private void reportActivePose() {
        EntityPlayerSP player = minecraft.player;
        if (closed || !isActiveFor(player)) return;
        initializeFromPlayer(player);
        SixDofBodyPresentation.FreePoseAttitudes pose = SixDofBodyPresentation.freePoseAttitudes(
                currentAttitude(), PlayerPhysicsClient.bodyYawOffset(player, 0.0F));
        PlayerRenderPoseMirror.reportFreePose(wireQuaternion(pose.body()), wireQuaternion(pose.head()));
    }

    private static PlayerRenderPoseProtocol.Quaternion wireQuaternion(FlightAttitude attitude) {
        return new PlayerRenderPoseProtocol.Quaternion(attitude.x, attitude.y, attitude.z, attitude.w);
    }

    private SixDofBodyPresentation.Sample walkingPresentation(EntityPlayerSP player, float partialTicks) {
        return SixDofBodyPresentation.sample(currentAttitude(), PlayerPhysicsClient.bodyYawOffset(player, partialTicks));
    }

    private PlayerPhysicsClientPresentation.ViewBasis walkingViewBasis(EntityPlayerSP player) {
        if (!isActiveFor(player)) return null;
        initializeFromPlayer(player);
        var attitude = currentAttitude();
        var forward = attitude.forward();
        var right = attitude.right();
        return new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(forward.x, forward.y, forward.z),
                new Vec3(right.x, right.y, right.z));
    }

    private ClientBoostRouting.Plan boostPlan(EntityPlayerSP player) {
        if (!isActiveFor(player)) {
            return null;
        }
        initializeFromPlayer(player);
        return new ClientBoostRouting.Plan(
                orientation.forward(), controlGears.boostAccelerationMps2(),
                controlGears.boostSpeedLimitMps(), controlGears.boostGear(),
                alignmentBoostSuppressed());
    }

    private PlayerPhysicsProtocol.Controls physicalControls(EntityPlayerSP player) {
        if (!isDriftAuthorized(player)) return null;
        initializeFromPlayer(player);
        InputFrame frame = InputApi.getFrame(0.0F);
        var attitude = orientation.current();
        boolean enabled = frame.getSampleId() != 0 && frame.getContext().isGameFocused()
                && minecraft.currentScreen == null && owns(frame, InputAction.PLAYER_MOVE_FORWARD);
        Vec3 translation = enabled ? SixDofDriftMotion.worldIntent(attitude, new SixDofDriftMotion.Intent(
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Z), -ownedAxis(frame, InputAction.CAMERA_TRANSLATE_X),
                ownedAxis(frame, InputAction.CAMERA_TRANSLATE_Y))) : Vec3.ZERO;
        return PlayerPhysicsInput.world(translation, attitude.forward(),
                enabled && ClientKeyBindingState.isDown(minecraft.gameSettings.keyBindJump),
                enabled && ClientKeyBindingState.isDown(minecraft.gameSettings.keyBindSneak),
                enabled && player.isSprinting(),
                enabled && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY) && !alignmentBoostSuppressed(),
                controlGears.boostGear());
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
        if (!alignmentInput.selecting()) {
            groundFacePreview = null;
            SixDofGroundFacePreview.clear();
        }
        if (change.enabledChanged() || change.referenceChanged() || change.committed()) {
            alignmentDiagnostics = null;
            alignmentPitchLimited = false;
            minecraft.player.sendStatusMessage(new TextComponentString(String.format(Locale.ROOT,
                    "UniGrav alignment: %s UP %s", alignmentInput.enabled() ? "ON" : "OFF",
                    alignmentInput.reference().label())), true);
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

    private FlightAttitude currentAttitude() {
        SixDofCameraOrientation.Components value = orientation.components();
        return new FlightAttitude(value.x(), value.y(), value.z(), value.w());
    }

    private void initializeFromPlayer(EntityPlayerSP player) {
        if (!orientation.isInitialized()) {
            orientation.reset(player.rotationPitch, player.rotationYaw);
        }
    }

    private void clearAttitude() {
        orientation.clear();
        alignmentInput.disable();
        suspendControls();
    }

    /**
     * A voluntary remote G-off releases every UIE provider/input claim while
     * the protocol's CLOSING session continues its own packet pairing. Keep
     * the local quaternion representation untouched in that narrow handoff;
     * this method never writes player yaw, pitch, roll or motion.
     */
    private void deactivateInactiveCamera(EntityPlayerSP player) {
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
        SixDofGroundFacePreview.invalidate();
        SixDofGroundFacePreview.lifecycle(null);
        MinecraftForge.EVENT_BUS.unregister(this);
        closeFlight(bodyPoseRegistration);
        closeFlight(trackingRegistration);
        closeFlight(maneuverRegistration);
        closeFlight(capabilityRegistration);
        closeInput(inputRegistration);
        closeBoost(boostRegistration);
        closeBoost(physicalRegistration);
        closeBoost(viewBasisRegistration);
        trackingRegistration = null;
        bodyPoseRegistration = null;
        maneuverRegistration = null;
        capabilityRegistration = null;
        inputRegistration = null;
        boostRegistration = null;
        physicalRegistration = null;
        viewBasisRegistration = null;
        clearAttitude();
        activePlayer = null;
        synchronized (RevoUiSixDofCamera.class) {
            if (installed == this) {
                installed = null;
            }
        }
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

    private static void closeFlight(FlightRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static void closeInput(InputRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static void closeBoost(ClientBoostRouting.Registration registration) {
        if (registration != null) {
            registration.close();
        }
    }
}
