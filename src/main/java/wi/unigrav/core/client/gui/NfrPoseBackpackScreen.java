package wi.unigrav.core.client.gui;

import com.cleanroommc.modularui.api.layout.ILayoutWidget;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import wi.unigrav.core.client.ClientGravityTicker;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.player.PlayerMobilityIntent;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.client.camera.RevoUiSixDofCamera;
import neofontrender.client.gui.component.base.NfrLayout;
import neofontrender.client.gui.component.base.NfrScrollablePane;
import neofontrender.client.gui.component.base.NfrTextButton;
import neofontrender.client.gui.component.base.NfrTextInfoPanel;
import neofontrender.client.gui.component.base.NfrToggleIndicator;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

/** NFR/ModularUI client-only debug controls; server state remains the authority. */
@SideOnly(Side.CLIENT)
public final class NfrPoseBackpackScreen {
    private NfrPoseBackpackScreen() { }

    public static void open() {
        ModularPanel panel = new ModularPanel("unigrav_pose_backpack").relativeToScreen().full();
        panel.disableThemeBackground(true);
        panel.disableHoverThemeBackground(true);
        panel.child(new ScreenContent().relativeToParent().full());
        ClientGUI.open(new ModularScreen("unigrav", panel)
                .useTheme("neofontrender_modern")
                .pausesGame(false));
    }

    private static final class ScreenContent extends ParentWidget<ScreenContent> implements ILayoutWidget {
        private static final int PAD = 12;
        private final NfrTextInfoPanel info = new NfrTextInfoPanel(
                NfrTextInfoPanel.spaced(() -> tr("gui.unigrav.pose_backpack.title"), 0xFFFFFF),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::eligibilityText, 0xD8D8D8),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::gIntentText, 0xD8D8D8),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::mobilityModeText, 0xD8D8D8),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::hoverText, 0xD8D8D8),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::mobilityReasonText, 0xD8D8D8),
                NfrTextInfoPanel.line(NfrPoseBackpackScreen::mobilityRequestText, 0xBFC7D1),
                NfrTextInfoPanel.line(() -> tr("gui.unigrav.pose_backpack.space_source"), 0xBFC7D1));
        private final ToggleButton gravityButton = new ToggleButton(
                NfrPoseBackpackScreen::gravityButtonText,
                () -> ClientGravityTicker.requestedPersonalZeroG(Minecraft.getMinecraft().player),
                NfrPoseBackpackScreen::canRequestG,
                NfrPoseBackpackScreen::toggleG);
        private final ToggleButton modeButton = new ToggleButton(
                NfrPoseBackpackScreen::modeButtonText,
                NfrPoseBackpackScreen::flightRequested,
                NfrPoseBackpackScreen::canRequestMobility,
                NfrPoseBackpackScreen::toggleMode);
        private final ToggleButton hoverButton = new ToggleButton(
                NfrPoseBackpackScreen::hoverButtonText,
                NfrPoseBackpackScreen::hoverRequested,
                NfrPoseBackpackScreen::canRequestMobility,
                NfrPoseBackpackScreen::toggleHover);
        private final ParentWidget<?> rows = new ParentWidget<>();
        private final ToggleButton alignmentButton = new ToggleButton(
                () -> tr("gui.unigrav.pose_backpack.button.alignment", RevoUiSixDofCamera.alignmentReferenceLabel()),
                RevoUiSixDofCamera::alignmentEnabled, RevoUiSixDofCamera::canConfigureAlignment,
                RevoUiSixDofCamera::toggleAlignment);
        private final List<NfrTextButton> referenceButtons = new ArrayList<>();
        private final NfrScrollablePane scroll;
        private final NfrTextButton closeButton = new NfrTextButton(
                () -> tr("gui.unigrav.pose_backpack.close"), true)
                .onMousePressed(mouseButton -> {
                    ClientGUI.close();
                    return true;
                });

        private ScreenContent() {
            rows.child(info);
            rows.child(gravityButton);
            rows.child(modeButton);
            rows.child(hoverButton);
            rows.child(alignmentButton);
            for (CardinalFrame frame : CardinalFrame.values()) {
                NfrTextButton button = new NfrTextButton(
                        () -> tr("gui.unigrav.pose_backpack.button.reference", frame.name().replace("_POS", "+").replace("_NEG", "-")), false)
                        .onMousePressed(mouseButton -> {
                            RevoUiSixDofCamera.selectAlignmentReference(frame);
                            return true;
                        });
                button.setEnabledIf(widget -> RevoUiSixDofCamera.canConfigureAlignment());
                referenceButtons.add(button);
                rows.child(button);
            }
            scroll = new NfrScrollablePane(rows);
            child(scroll.widget());
            child(closeButton);
        }

        @Override
        public boolean layoutWidgets() {
            int width = Math.max(0, getArea().w());
            int height = Math.max(0, getArea().h());
            int cardWidth = Math.min(620, Math.max(240, width - PAD * 2));
            int cardHeight = Math.min(460, Math.max(180, height - PAD * 2));
            int x = Math.max(0, (width - cardWidth) / 2);
            int y = Math.max(0, (height - cardHeight) / 2);
            int innerWidth = Math.max(0, cardWidth - PAD * 2);
            int closeHeight = 24;
            int contentHeight = Math.max(0, cardHeight - PAD * 3 - closeHeight);
            scroll.layout(x + PAD, y + PAD, innerWidth, contentHeight,
                    innerWidth, rowsHeight(innerWidth));
            NfrLayout.place(closeButton, x + PAD, y + cardHeight - PAD - closeHeight,
                    innerWidth, closeHeight);
            return true;
        }

        private int rowsHeight(int width) {
            int infoHeight = info.preferredHeight();
            int buttonHeight = 28;
            int gap = 7;
            NfrLayout.place(info, 0, 0, width, infoHeight);
            NfrLayout.place(gravityButton, 0, infoHeight + gap, width, buttonHeight);
            NfrLayout.place(modeButton, 0, infoHeight + gap + buttonHeight + gap, width, buttonHeight);
            NfrLayout.place(hoverButton, 0, infoHeight + gap + 2 * (buttonHeight + gap), width, buttonHeight);
            NfrLayout.place(alignmentButton, 0, infoHeight + gap + 3 * (buttonHeight + gap), width, buttonHeight);
            int top = infoHeight + gap + 4 * (buttonHeight + gap);
            for (NfrTextButton button : referenceButtons) {
                NfrLayout.place(button, 0, top, width, buttonHeight);
                top += buttonHeight + gap;
            }
            return top;
        }
    }

    private static final class ToggleButton extends NfrTextButton {
        private final NfrToggleIndicator indicator;

        private ToggleButton(Supplier<String> label, Supplier<Boolean> selected,
                             Supplier<Boolean> enabled, Runnable action) {
            super(label, false);
            indicator = new NfrToggleIndicator(selected);
            size(430, 28);
            setEnabledIf(widget -> enabled.get());
            onMousePressed(mouseButton -> {
                if (enabled.get()) action.run();
                return true;
            });
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
            super.draw(context, widgetTheme);
            indicator.draw(this);
        }
    }

    private static String eligibilityText() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (player == null || minecraft.world == null || player.world != minecraft.world
                || minecraft.getConnection() == null) return tr("gui.unigrav.pose_backpack.reason.disconnected");
        if (!player.isCreative()) return tr("gui.unigrav.pose_backpack.reason.not_creative");
        Optional<Boolean> acknowledgedG = ClientGravityTicker.acknowledgedPersonalZeroG(player);
        if (acknowledgedG.isEmpty())
            return tr("gui.unigrav.pose_backpack.reason.g_unknown");
        if (!acknowledgedG.orElseThrow()) return tr("gui.unigrav.pose_backpack.reason.g_off");
        if (!PlayerPhysicsClient.drivesMotion(player))
            return tr("gui.unigrav.pose_backpack.reason.owner_unavailable");
        return tr("gui.unigrav.pose_backpack.reason.ready");
    }

    private static String gIntentText() {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) return tr("gui.unigrav.pose_backpack.intent.unavailable");
        String requested = ClientGravityTicker.requestedPersonalZeroG(player)
                ? tr("gui.unigrav.pose_backpack.state.on") : tr("gui.unigrav.pose_backpack.state.off");
        Optional<Boolean> acknowledged = ClientGravityTicker.acknowledgedPersonalZeroG(player);
        String confirmed = acknowledged.map(on -> tr(on ? "gui.unigrav.pose_backpack.state.on"
                : "gui.unigrav.pose_backpack.state.off")).orElse(tr("gui.unigrav.pose_backpack.state.unknown"));
        String pending = acknowledged.map(on -> on == ClientGravityTicker.requestedPersonalZeroG(player)
                ? "" : " · " + tr("gui.unigrav.pose_backpack.state.pending")).orElse("");
        return tr("gui.unigrav.pose_backpack.g_status", requested, confirmed, pending);
    }

    private static String mobilityModeText() {
        Optional<PlayerMobilityIntent.State> state = PlayerPhysicsClient.mobilityState();
        if (state.isEmpty()) return tr("gui.unigrav.pose_backpack.mobility.unavailable");
        PlayerMobilityIntent.State current = state.orElseThrow();
        return tr("gui.unigrav.pose_backpack.mobility.mode",
                tr("gui.unigrav.pose_backpack.mode." + current.mode().name().toLowerCase(java.util.Locale.ROOT)),
                tr("gui.unigrav.pose_backpack.mode." + current.effectiveMode().name().toLowerCase(java.util.Locale.ROOT)),
                current.detached() ? tr("gui.unigrav.pose_backpack.state.yes") : tr("gui.unigrav.pose_backpack.state.no"));
    }

    private static String hoverText() {
        Optional<PlayerMobilityIntent.State> state = PlayerPhysicsClient.mobilityState();
        if (state.isEmpty()) return "";
        PlayerMobilityIntent.State current = state.orElseThrow();
        return tr("gui.unigrav.pose_backpack.mobility.hover",
                tr("gui.unigrav.pose_backpack.hover." + current.hover().name().toLowerCase(java.util.Locale.ROOT)),
                tr("gui.unigrav.pose_backpack.hover_status." + current.hoverStatus().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static String mobilityReasonText() {
        Optional<PlayerMobilityIntent.State> state = PlayerPhysicsClient.mobilityState();
        if (state.isEmpty()) return "";
        return tr("gui.unigrav.pose_backpack.reason_line",
                tr("gui.unigrav.pose_backpack.reason." + state.orElseThrow().reason().name()
                        .toLowerCase(java.util.Locale.ROOT)));
    }

    private static String mobilityRequestText() {
        Optional<PlayerMobilityIntent.Request> request = PlayerPhysicsClient.pendingMobilityRequest();
        if (request.isPresent()) return tr("gui.unigrav.pose_backpack.mobility.pending",
                request.orElseThrow().id(), tr("gui.unigrav.pose_backpack.mode."
                        + request.orElseThrow().mode().name().toLowerCase(java.util.Locale.ROOT)));
        return tr("gui.unigrav.pose_backpack.mobility.no_pending");
    }

    private static String gravityButtonText() { return tr("gui.unigrav.pose_backpack.button.gravity"); }
    private static String modeButtonText() {
        return tr("gui.unigrav.pose_backpack.button.mode",
                tr(flightRequested() ? "gui.unigrav.pose_backpack.mode.walk" : "gui.unigrav.pose_backpack.mode.flight"));
    }
    private static String hoverButtonText() {
        return tr("gui.unigrav.pose_backpack.button.hover",
                tr(hoverRequested() ? "gui.unigrav.pose_backpack.hover.off" : "gui.unigrav.pose_backpack.hover.on"));
    }

    private static boolean canRequestG() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        return player != null && player.isCreative() && minecraft.world != null
                && player.world == minecraft.world && minecraft.getConnection() != null;
    }

    private static boolean canRequestMobility() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        return canRequestG() && player != null && ClientGravityTicker.acknowledgedPersonalZeroG(player).orElse(false)
                && ClientGravityTicker.isPersonalZeroGActive(player) && PlayerPhysicsClient.drivesMotion(player)
                && PlayerPhysicsClient.pendingMobilityRequest().isEmpty();
    }

    private static boolean flightRequested() {
        Optional<PlayerMobilityIntent.Request> pending = PlayerPhysicsClient.pendingMobilityRequest();
        if (pending.isPresent()) return pending.orElseThrow().mode() == PlayerMobilityIntent.Mode.FLIGHT;
        return PlayerPhysicsClient.mobilityState().map(state -> state.mode() == PlayerMobilityIntent.Mode.FLIGHT)
                .orElse(false);
    }

    private static boolean hoverRequested() {
        Optional<PlayerMobilityIntent.Request> pending = PlayerPhysicsClient.pendingMobilityRequest();
        if (pending.isPresent()) return pending.orElseThrow().hover() == PlayerMobilityIntent.Hover.ON;
        return PlayerPhysicsClient.mobilityState().map(state -> state.hover() == PlayerMobilityIntent.Hover.ON)
                .orElse(false);
    }

    private static void toggleG() {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (canRequestG() && player != null)
            ClientGravityTicker.requestPersonalZeroG(player, !ClientGravityTicker.requestedPersonalZeroG(player));
    }

    private static void toggleMode() {
        PlayerMobilityIntent.Mode mode = flightRequested()
                ? PlayerMobilityIntent.Mode.WALK : PlayerMobilityIntent.Mode.FLIGHT;
        PlayerMobilityIntent.Hover hover = PlayerPhysicsClient.mobilityState()
                .map(PlayerMobilityIntent.State::hover).orElse(PlayerMobilityIntent.Hover.OFF);
        if (!PlayerPhysicsClient.requestMobility(mode, hover)) rejectedRequestNotice();
    }

    private static void toggleHover() {
        PlayerMobilityIntent.Mode mode = PlayerPhysicsClient.mobilityState()
                .map(PlayerMobilityIntent.State::mode).orElse(PlayerMobilityIntent.Mode.WALK);
        PlayerMobilityIntent.Hover hover = hoverRequested()
                ? PlayerMobilityIntent.Hover.OFF : PlayerMobilityIntent.Hover.ON;
        if (!PlayerPhysicsClient.requestMobility(mode, hover)) rejectedRequestNotice();
    }

    private static void rejectedRequestNotice() {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null) player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(
                "gui.unigrav.pose_backpack.mobility.client_rejected"), true);
    }

    private static String tr(String key, Object... args) { return I18n.format(key, args); }
}
