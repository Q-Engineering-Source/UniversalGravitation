package wi.unigrav.core.client;

import net.minecraft.entity.player.EntityPlayer;
import java.util.Objects;
import java.util.List;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Uses the production client commit boundary with real Session, Mirror, and Presentation instances in floor tests. */
public final class PlayerPhysicsClientInstallationTestSupport {
    private final PlayerPhysicsClientSession session;
    private final EntityPlayer actor;
    private final Object ownerContext = new Object();
    private final PlayerPhysicsClientPresentation presentation = new PlayerPhysicsClientPresentation();
    private PlayerPhysicsClient.SuccessfulInstallationStamp installed;
    private EntityPlayer presentationActor;
    private PlayerPhysicalSnapshot presentationSnapshot;

    public PlayerPhysicsClientInstallationTestSupport(PlayerPhysicsClientSession session, EntityPlayer actor) {
        this.session = Objects.requireNonNull(session, "session");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    /** Two real END samples delivered before one server tick; no synthetic jump bit. */
    public static List<PlayerPhysicsProtocol.Controls> firstAndHeldMobilitySamples(PlayerPhysicsProtocol.Controls controls) {
        var sampler = new PlayerPhysicsClientSession(snapshot -> true, () -> {}, () -> {});
        return List.of(sampler.sampleMobility(controls, true, true, true),
                sampler.sampleMobility(controls, true, true, true));
    }

    /** Uses this installed Session's real END sampler, including its pending mobility request. */
    public PlayerPhysicsProtocol.Controls sampleMobility(PlayerPhysicsProtocol.Controls controls,
            boolean held, boolean focused) {
        return session.sampleMobility(controls, held, focused, true);
    }

    /** Installs a new STATE or proves and publishes its exact-geometry retransmission. */
    public boolean install(PlayerPhysicsProtocol.Packet packet) {
        var preparation = session.prepareInstallation(packet);
        if (preparation.disposition() == PlayerPhysicsClientSession.InstallationDisposition.REJECTED) return false;
        var prepared = preparation.prepared().orElseThrow();
        if (preparation.disposition() == PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION) {
            if (installed == null || !installed.matchesRetransmission(ownerContext, session, prepared, packet,
                    actor, actor.world, presentation, presentationActor, presentationSnapshot)) return false;
            var next = installed.publishRetransmission(ownerContext, session, prepared, packet, actor, actor.world,
                    presentation, presentationActor, presentationSnapshot, () -> { });
            if (next == null) return false;
            installed = next;
            return true;
        }

        if (!session.installationCurrent(prepared) || !session.beginPreparedInstallation(prepared)) return false;
        var snapshot = prepared.snapshot();
        var mirrorPreparation = PlayerPhysicsClientMirror.prepare(actor, snapshot);
        if (mirrorPreparation.prepared().isEmpty()) {
            session.revokePreparedInstallation(prepared);
            return false;
        }
        var preparedPresentation = presentation.prepare(actor, snapshot);
        var preparedMirror = mirrorPreparation.prepared().orElseThrow();
        if (!preparedMirror.isBoundTo(actor, snapshot)
                || !preparedPresentation.isBoundTo(presentation, actor, snapshot)) {
            session.revokePreparedInstallation(prepared);
            return false;
        }
        var boundsApplied = PlayerPhysicsClientMirror.commitPrepared(preparedMirror);
        if (!session.installationCurrent(prepared)) {
            session.revokePreparedInstallation(prepared);
            return false;
        }
        PlayerPhysicsClientMirror.commitRemaining(boundsApplied);
        var mirrorProof = PlayerPhysicsClientMirror.proveInstalled(boundsApplied).orElse(null);
        if (mirrorProof == null || !session.installationCurrent(prepared)) {
            session.revokePreparedInstallation(prepared);
            return false;
        }
        var completed = PlayerPhysicsClient.completeInstalledState(ownerContext, session, prepared, packet,
                actor, actor.world, mirrorProof, presentation, preparedPresentation, () -> { });
        if (completed == null) return false;
        installed = completed;
        presentationActor = actor;
        presentationSnapshot = snapshot;
        return true;
    }

    /** Completes the production client session's send bookkeeping after the test submits the exact packet. */
    public void inputSendSucceeded(PlayerPhysicsProtocol.Packet input) {
        session.inputSendSucceeded(input);
    }
}
