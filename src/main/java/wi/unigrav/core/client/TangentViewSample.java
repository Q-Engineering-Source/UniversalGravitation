package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;

/** One immutable, owner-issued simulation view. Rendering may independently read live head Q. */
public final class TangentViewSample {
    private final PlayerPhysicsClientSession.TangentViewCustody custody;
    private final EntityPlayer actor;
    private final CardinalFrame frame;
    private final long viewSequence, clientTick;
    private final Vec3 headForward;
    private final ViewRelativeMovementBasis basis;
    private final float localHeadYaw;
    private final NativeYaw nativeYaw;

    /** Keep the actual vanilla table values and arithmetic; the normalized basis shares their direction. */
    private record NativeYaw(float sin, float cos) {
        Vec3 translation(double forward, double strafe) {
            return new Vec3(strafe * cos - forward * sin, 0, forward * cos + strafe * sin);
        }
    }

    private TangentViewSample(PlayerPhysicsClientSession.TangentViewCustody custody, EntityPlayer actor,
                              CardinalFrame frame, long sequence, long tick, Vec3 headForward,
                              ViewRelativeMovementBasis basis, NativeYaw nativeYaw) {
        this.custody = custody; this.actor = actor; this.frame = frame;
        viewSequence = sequence; clientTick = tick; this.headForward = headForward;
        this.basis = basis; this.nativeYaw = nativeYaw;
        Vec3d local = frame.toLocal(new Vec3d(basis.forward().x(), basis.forward().y(), basis.forward().z()));
        localHeadYaw = (float) Math.toDegrees(Math.atan2(-local.x(), local.z()));
    }

    static TangentViewSample prepare(PlayerPhysicsClientSession.TangentViewCustody custody, EntityPlayer actor,
                                     PlayerBodyReference.Effective reference, long sequence, long tick,
                                     Vec3 headForward, Float vanillaYaw, TangentViewSample previous) {
        var heading = reference.tangentHeading();
        NativeYaw nativeYaw = null;
        if (vanillaYaw != null && reference.frame() == CardinalFrame.Y_POS) {
            if (!Float.isFinite(vanillaYaw)) throw new IllegalArgumentException("Non-finite input yaw");
            float radians = vanillaYaw * 0.017453292F;
            nativeYaw = new NativeYaw(MathHelper.sin(radians), MathHelper.cos(radians));
        }
        // Native Y+ yaw remains defined at pitch poles. Establish that canonical direction here,
        // before any consumer sees the sample, rather than letting keyboard input bypass its basis.
        var basis = nativeYaw != null ? ViewRelativeMovementBasis.sample(new Vec3(0, 1, 0),
                nativeYaw.translation(1, 0), new Vec3(heading.x(), heading.y(), heading.z()))
                : ViewRelativeMovementBasis.continuous(reference.frame(), headForward,
                new Vec3(heading.x(), heading.y(), heading.z()), previous == null ? null : previous.frame,
                previous == null ? null : previous.basis.forward());
        return new TangentViewSample(custody, actor, reference.frame(), sequence, tick, headForward, basis, nativeYaw);
    }

    boolean current(PlayerPhysicsClientSession owner, EntityPlayer expectedActor) {
        return actor == expectedActor && custody.current(owner);
    }
    boolean sameLifetime(PlayerPhysicsClientSession owner, EntityPlayer expectedActor) {
        return actor == expectedActor && custody.sameLifetime(owner);
    }
    public CardinalFrame frame() { return frame; }
    public long viewSequence() { return viewSequence; }
    public long clientTick() { return clientTick; }
    public Vec3 headForward() { return headForward; }
    public ViewRelativeMovementBasis basis() { return basis; }
    public float localHeadYaw() { return localHeadYaw; }
    Vec3 keyboardTranslation(double forward, double strafe) {
        return nativeYaw != null ? nativeYaw.translation(forward, strafe)
                : basis.forward().scale(forward).add(basis.right().scale(strafe));
    }
}
