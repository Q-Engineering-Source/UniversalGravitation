package wi.unigrav.core.player;

import net.minecraft.entity.player.EntityPlayer;

import java.util.Objects;
import java.util.Optional;

/** Result admission only. Existing movement solvers validate collisions and commit once afterward. */
public final class PlayerPositionValidation {
    private PlayerPositionValidation() { }
    public enum Reason { SCOPE, MOVEMENT, BODY }
    public sealed interface Result permits Accepted, Rejected { }
    public record Accepted() implements Result { }
    public record Rejected(Reason reason, String detail, Optional<RuntimeException> cause) implements Result {
        public Rejected {
            Objects.requireNonNull(reason); Objects.requireNonNull(detail); Objects.requireNonNull(cause);
        }
    }

    /** The caller derives allowance from server time/capabilities, never the reported velocity. */
    public static Result validate(EntityPlayer actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicalSnapshot submitted, PlayerPhysicsProtocol.Controls controls, double allowanceMetres) {
        Objects.requireNonNull(actor); Objects.requireNonNull(accepted); Objects.requireNonNull(submitted);
        Objects.requireNonNull(controls);
        if (!Double.isFinite(allowanceMetres) || allowanceMetres < 0)
            throw new IllegalArgumentException("Invalid server movement allowance");
        if (!accepted.scope().equals(submitted.scope()) || actor.isDead
                || actor.world == null || actor.dimension != accepted.scope().dimension()
                || !actor.getUniqueID().equals(accepted.scope().playerId()))
            return reject(Reason.SCOPE, "Movement is not for this live player/world");
        var source = accepted.placement();
        var target = submitted.placement();
        var delta = target.center().subtract(source.center());
        double distance = Math.hypot(Math.hypot(delta.x(), delta.y()), delta.z());
        if (!Double.isFinite(distance) || distance > allowanceMetres)
            return reject(Reason.MOVEMENT, "distance=" + distance + ",serverAllowance=" + allowanceMetres);
        // Native input pose and mode/reference adoption run at their existing owner boundaries.
        // A result packet cannot grant a new collider or standing frame by declaring one.
        if (!source.shape().equals(target.shape()))
            return reject(Reason.BODY, "Submitted collider differs from the current owner collider");
        if (source.shape() instanceof PlayerBodyShape.Cardinal && !source.attitude().equals(target.attitude()))
            return reject(Reason.BODY, "MOVE cannot rotate the accepted standing reference");
        return new Accepted();
    }

    private static Rejected reject(Reason reason, String detail) {
        return new Rejected(reason, detail, Optional.empty());
    }
}
