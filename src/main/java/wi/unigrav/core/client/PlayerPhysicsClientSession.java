package wi.unigrav.core.client;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.entity.player.EntityPlayer;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Client-thread installation state; application success, not reception, establishes ownership. */
public final class PlayerPhysicsClientSession {
    /** Diagnostic observation only; never consulted to authorize a packet or movement. */
    public enum StateDisposition {
        NOT_STATE, CONTEXT_MISMATCH, AWAITING_OFFER, APPLIED, RETRANSMISSION, PROPOSAL_MISMATCH, STALE_SEQUENCE,
        NATIVE_DELEGATED, TELEPORT_MISMATCH, REVISION_CONFLICT, ATTEMPT_FENCED,
        INSTALL_REJECTED, LIFETIME_CHANGED, APPLICATION_THREW
    }
    private StateDisposition lastStateDisposition = StateDisposition.NOT_STATE;
    public StateDisposition lastStateDisposition() { return lastStateDisposition; }
    private boolean observed(StateDisposition disposition, boolean accepted) {
        lastStateDisposition = disposition;
        return accepted;
    }
    /** Local native writers are retired only after actual application, and resume during native delegation. */
    public void runLegacyMotion(EntityPlayer actor, Runnable original) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(original, "original");
        if (drivesMotion() && actor.getUniqueID().equals(snapshot.scope().playerId())
                && actor.dimension == snapshot.scope().dimension() && !PlayerKinematics.requiresNative(actor)) return;
        original.run();
    }
    private final Predicate<PlayerPhysicalSnapshot> install;
    private final Runnable retireLegacy;
    private final Runnable enterNative;
    private PlayerPhysicsClientOffer offer = new PlayerPhysicsClientOffer();
    private PlayerPhysicsProtocol.Packet submitted;
    private PlayerPhysicsProtocol.Packet offered;
    private PlayerPhysicsProtocol.Packet attempted;
    private PlayerPhysicalSnapshot snapshot;
    private Object lifetime = new Object();
    private long incomingSequence = -1;
    private long outgoingSequence;
    private boolean applying;
    private boolean nativeDelegated;
    private boolean retired;
    private PlayerPhysicalSnapshot.Scope custodyScope;
    private long delegationProposal;
    private OptionalInt nativeTeleportId = OptionalInt.empty();

    public PlayerPhysicsClientSession(Predicate<PlayerPhysicalSnapshot> install, Runnable retireLegacy, Runnable enterNative) {
        this.install = Objects.requireNonNull(install, "install");
        this.retireLegacy = Objects.requireNonNull(retireLegacy, "retireLegacy");
        this.enterNative = Objects.requireNonNull(enterNative, "enterNative");
    }

    public boolean receive(PlayerPhysicsProtocol.Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (applying) throw new IllegalStateException("Reentrant physical state installation");
        lastStateDisposition = StateDisposition.NOT_STATE;
        if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE) return receiveNative(packet);
        if (packet.kind() == PlayerPhysicsProtocol.Kind.OFFER) {
            if (drivesMotion() || !offer.accept(packet)) return false;
            if (offered == null || !offered.scope().equals(packet.scope())
                    || offered.offerSequence() != packet.offerSequence()) {
                submitted = null;
                attempted = null;
                nativeTeleportId = OptionalInt.empty();
            }
            offered = packet;
            if (snapshot != null && !snapshot.scope().equals(packet.scope())) snapshot = null;
            return true;
        }
        if (packet.kind() != PlayerPhysicsProtocol.Kind.OWNED && packet.kind() != PlayerPhysicsProtocol.Kind.STATE)
            return false;
        if (submitted == null || !submitted.scope().equals(packet.scope())
                || submitted.offerSequence() != packet.offerSequence()) return observed(StateDisposition.PROPOSAL_MISMATCH, false);
        if (packet.sequence() <= incomingSequence) return observed(StateDisposition.STALE_SEQUENCE, false);
        if (nativeDelegated && submitted.scope().equals(custodyScope)
                && submitted.offerSequence() <= delegationProposal) return observed(StateDisposition.NATIVE_DELEGATED, false);
        if (!nativeTeleportId.equals(packet.nativeTeleportId())) return observed(StateDisposition.TELEPORT_MISMATCH, false);
        PlayerPhysicalSnapshot next = packet.snapshot().orElseThrow();
        if (snapshot != null && next.revision() <= snapshot.revision()) {
            if (!snapshot.equals(next)) return observed(StateDisposition.REVISION_CONFLICT, false);
            incomingSequence = packet.sequence();
            return observed(StateDisposition.RETRANSMISSION, true); // No absolute write or retirement.
        }
        if (attempted != null && (packet.sequence() < attempted.sequence()
                || packet.sequence() == attempted.sequence() && !packet.equals(attempted))) return observed(StateDisposition.ATTEMPT_FENCED, false);
        // Absolute writes may retry exactly after failure; an older state may not win that race.
        attempted = packet;
        Object expectedLifetime = lifetime;
        applying = true;
        lastStateDisposition = StateDisposition.APPLICATION_THREW;
        try {
            if (!install.test(next)) return observed(StateDisposition.INSTALL_REJECTED, false);
            if (lifetime != expectedLifetime) return observed(StateDisposition.LIFETIME_CHANGED, false);
            if (!retired) {
                retireLegacy.run();
                if (lifetime == expectedLifetime) retired = true;
            }
            if (lifetime != expectedLifetime) return observed(StateDisposition.LIFETIME_CHANGED, false);
            snapshot = next;
            custodyScope = next.scope();
            nativeDelegated = false;
            incomingSequence = packet.sequence();
            return observed(StateDisposition.APPLIED, true);
        } finally {
            applying = false;
        }
    }

    private boolean receiveNative(PlayerPhysicsProtocol.Packet packet) {
        if (packet.sequence() <= incomingSequence
                || attempted != null && (packet.sequence() < attempted.sequence()
                || packet.sequence() == attempted.sequence() && !packet.equals(attempted))) return false;
        boolean matching = owns() ? packet.scope().equals(custodyScope)
                : submitted != null && packet.scope().equals(submitted.scope()) && packet.offerSequence() == submitted.offerSequence();
        if (!matching) return false;
        attempted = packet;
        Object expectedLifetime = lifetime;
        applying = true;
        try {
            if (!retired) {
                retireLegacy.run();
                if (lifetime == expectedLifetime) retired = true;
            }
            if (lifetime != expectedLifetime) return false;
            if (!nativeDelegated) enterNative.run();
            if (lifetime != expectedLifetime) return false;
            nativeDelegated = true;
            custodyScope = packet.scope();
            delegationProposal = packet.offerSequence();
            attempted = null;
            incomingSequence = packet.sequence();
            return true;
        } finally {
            applying = false;
        }
    }

    /** Protocol custody survives native delegation so retired UG writers cannot restart. */
    public boolean owns() { return snapshot != null || nativeDelegated; }
    public boolean drivesMotion() { return snapshot != null && !nativeDelegated; }
    /** Keep custody while vanilla applies its correction; only states for that exact native ID may follow. */
    public void nativeCorrection(int teleportId) {
        if (teleportId < 0) throw new IllegalArgumentException("Negative native teleport ID");
        nativeTeleportId = OptionalInt.of(teleportId);
    }
    public Optional<PlayerPhysicalSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    public void reset() {
        lifetime = new Object();
        offer = new PlayerPhysicsClientOffer();
        submitted = null;
        offered = null;
        attempted = null;
        snapshot = null;
        custodyScope = null;
        delegationProposal = 0;
        nativeDelegated = false;
        retired = false;
        incomingSequence = -1;
        outgoingSequence = 0;
        nativeTeleportId = OptionalInt.empty();
    }
    public CPacketPlayer prepare(CPacketPlayer packet, Vec3d transport, float yaw, float pitch,
                                boolean remoteCustody, Optional<BoostProtocol.StateApplication> application,
                                Consumer<PlayerPhysicsProtocol.Packet> outbound) {
        if (drivesMotion()) return packet;
        Object expectedLifetime = lifetime;
        return offer.prepare(packet, transport, yaw, pitch, remoteCustody, application, apply -> {
            var sequenced = new PlayerPhysicsProtocol.Packet(apply.kind(), apply.scope(), Math.incrementExact(outgoingSequence),
                    apply.offerSequence(), apply.application(), apply.snapshot(), apply.controls());
            outbound.accept(sequenced);
            if (lifetime == expectedLifetime) {
                submitted = sequenced;
                outgoingSequence = sequenced.sequence();
            }
        });
    }

    public Optional<PlayerPhysicsProtocol.Packet> input(PlayerPhysicsProtocol.Controls controls) {
        if (!drivesMotion()) return Optional.empty();
        outgoingSequence = Math.incrementExact(outgoingSequence);
        return Optional.of(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, snapshot.scope(),
                outgoingSequence, submitted.offerSequence(), Optional.empty(), Optional.empty(), Optional.of(controls)));
    }
}
