package wi.unigrav.core.client;

import java.util.Optional;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** Minecraft field projection only; the caller owns side, connection and physical lifetime checks. */
public final class PlayerPhysicsClientMirror {
    private PlayerPhysicsClientMirror() { }
    public enum Rejection { NONE, NO_ACTOR, DEAD, NO_CONTACTS, PLAYER_ID, DIMENSION, SHAPE, FRAME, WIDTH, HEIGHT }

    /** A successful preparation can only be created by {@link #prepare}; its actor and snapshot are identity-bound. */
    public static final class Preparation {
        private final Rejection rejection;
        private final PreparedMirror prepared;

        private Preparation(Rejection rejection, PreparedMirror prepared) {
            this.rejection = rejection;
            this.prepared = prepared;
        }

        public Rejection rejection() { return rejection; }
        public Optional<PreparedMirror> prepared() { return Optional.ofNullable(prepared); }
    }

    /** Immutable projection calculated without writing any EntityPlayer fields. */
    public static final class PreparedMirror {
        private final EntityPlayer actor;
        private final PlayerPhysicalSnapshot snapshot;
        private final float width;
        private final float height;
        private final float eyeHeight;
        private final AxisAlignedBB bounds;
        private final double positionX;
        private final double positionY;
        private final double positionZ;
        private final double motionX;
        private final double motionY;
        private final double motionZ;
        private final boolean onGround;
        private final boolean collidedHorizontally;
        private final boolean collidedVertically;
        private final boolean collided;
        private final float fallDistance;

        private PreparedMirror(EntityPlayer actor, PlayerPhysicalSnapshot snapshot, float width, float height,
                               float eyeHeight, AxisAlignedBB bounds, double positionX, double positionY,
                               double positionZ, double motionX, double motionY, double motionZ,
                               PlayerPhysicalSnapshot.Contacts contacts) {
            this.actor = actor;
            this.snapshot = snapshot;
            this.width = width;
            this.height = height;
            this.eyeHeight = eyeHeight;
            this.bounds = bounds;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionZ = positionZ;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.onGround = contacts.onGround();
            this.collidedHorizontally = contacts.horizontal();
            this.collidedVertically = contacts.vertical();
            this.collided = contacts.horizontal() || contacts.vertical();
            this.fallDistance = contacts.fallDistance();
        }

        /** The original attempt must still match these exact object identities after native callbacks. */
        public boolean isBoundTo(EntityPlayer expectedActor, PlayerPhysicalSnapshot expectedSnapshot) {
            return actor == expectedActor && snapshot == expectedSnapshot;
        }
    }

    /** Typed proof that the bounding-box callback returned for this prepared write sequence. */
    public static final class BoundsApplied {
        private final PreparedMirror prepared;
        private boolean remainingCommitted;

        private BoundsApplied(PreparedMirror prepared) { this.prepared = prepared; }
    }

    /** Proof that both native mirror stages completed and their accepted geometry still matches the actor. */
    public static final class InstalledProof {
        private final PreparedMirror prepared;

        private InstalledProof(PreparedMirror prepared) { this.prepared = prepared; }

        /** Retransmission snapshots may be re-decoded objects, so compare immutable snapshot values here. */
        public boolean matchesCurrent(EntityPlayer actor, PlayerPhysicalSnapshot candidate) {
            return actor == prepared.actor && candidate != null && candidate.equals(prepared.snapshot)
                    && matchesPreparedGeometry(actor, prepared);
        }
    }

    public static Rejection rejection(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (player == null) return Rejection.NO_ACTOR;
        if (player.isDead) return Rejection.DEAD;
        if (snapshot.contacts().isEmpty()) return Rejection.NO_CONTACTS;
        if (!player.getUniqueID().equals(snapshot.scope().playerId())) return Rejection.PLAYER_ID;
        if (player.dimension != snapshot.scope().dimension()) return Rejection.DIMENSION;
        PlayerBodyShape shape = snapshot.placement().shape();
        if (!(shape instanceof PlayerBodyShape.Cardinal) && !(shape instanceof PlayerBodyShape.FreeCapsule)) return Rejection.SHAPE;
        // Free geometry remains in the accepted bounds; native dimension fields carry only its float image.
        // Cardinal projection retains its existing exact native-dimension contract.
        if (shape instanceof PlayerBodyShape.Cardinal) {
            if ((double) (float) shape.dimensions().width() != shape.dimensions().width()) return Rejection.WIDTH;
            if ((double) (float) shape.dimensions().height() != shape.dimensions().height()) return Rejection.HEIGHT;
        } else {
            if (!Float.isFinite((float) shape.dimensions().width()) || (float) shape.dimensions().width() <= 0)
                return Rejection.WIDTH;
            if (!Float.isFinite((float) shape.dimensions().height()) || (float) shape.dimensions().height() <= 0)
                return Rejection.HEIGHT;
        }
        return Rejection.NONE;
    }

    /**
     * Computes every mirror value before the caller begins mutation. Rejections leave the actor untouched.
     * The accepted height, not the actor's prior native height, determines the native sneak eye offset.
     */
    public static Preparation prepare(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        Rejection rejection = rejection(player, snapshot);
        if (rejection != Rejection.NONE) return new Preparation(rejection, null);

        var placement = snapshot.placement();
        var dimensions = placement.shape().dimensions();
        float width = (float) dimensions.width();
        float height = (float) dimensions.height();
        float eyeHeight = (float) dimensions.eyeHeight();
        if (player.isSneaking() || height == 1.65F) eyeHeight += .08F;

        var accepted = placement.bounds();
        var bounds = new AxisAlignedBB(accepted.min().x(), accepted.min().y(), accepted.min().z(),
                accepted.max().x(), accepted.max().y(), accepted.max().z());
        var transport = placement.transport();
        var velocity = snapshot.worldVelocity();
        var contacts = snapshot.contacts().orElseThrow();
        return new Preparation(Rejection.NONE, new PreparedMirror(player, snapshot, width, height, eyeHeight,
                bounds, transport.x(), transport.y(), transport.z(),
                velocity.x() * GravityEntityBinder.TICK_SECONDS,
                velocity.y() * GravityEntityBinder.TICK_SECONDS,
                velocity.z() * GravityEntityBinder.TICK_SECONDS, contacts));
    }

    /**
     * Projects dimensions and transport through the native AABB setter, then returns a typed stage token.
     * If a mod override throws, its original exception propagates unchanged and no token is returned.
     */
    public static BoundsApplied commitPrepared(PreparedMirror prepared) {
        EntityPlayer player = prepared.actor;
        player.width = prepared.width;
        player.height = prepared.height;
        // Forge stores the base eye; its native getter subtracts the sneak offset.
        // Aqua's independent getter is projected at the client proxy boundary below.
        player.eyeHeight = prepared.eyeHeight;
        // Keep vanilla history relative to the actor's position at this commit stage.
        player.prevPosX = player.posX;
        player.prevPosY = player.posY;
        player.prevPosZ = player.posZ;
        player.posX = prepared.positionX;
        player.posY = prepared.positionY;
        player.posZ = prepared.positionZ;
        // Do not call setPosition: its nominal box rebuild would discard accepted collision geometry.
        player.setEntityBoundingBox(prepared.bounds);
        return new BoundsApplied(prepared);
    }

    /** Completes only the prepared velocity/contact projection after the caller's attempt recheck. */
    public static void commitRemaining(BoundsApplied boundsApplied) {
        PreparedMirror prepared = boundsApplied.prepared;
        EntityPlayer player = prepared.actor;
        player.motionX = prepared.motionX;
        player.motionY = prepared.motionY;
        player.motionZ = prepared.motionZ;
        player.onGround = prepared.onGround;
        player.collidedHorizontally = prepared.collidedHorizontally;
        player.collidedVertically = prepared.collidedVertically;
        player.collided = prepared.collided;
        player.fallDistance = prepared.fallDistance;
        boundsApplied.remainingCommitted = true;
    }

    /** Minted only after both mirror stages; the caller must retain and recheck it at publication. */
    public static Optional<InstalledProof> proveInstalled(BoundsApplied boundsApplied) {
        if (boundsApplied == null || !boundsApplied.remainingCommitted) return Optional.empty();
        PreparedMirror prepared = boundsApplied.prepared;
        return matchesPreparedGeometry(prepared.actor, prepared)
                ? Optional.of(new InstalledProof(prepared)) : Optional.empty();
    }

    /** Read-only check for an already installed mirror; it never repairs or reinstalls geometry. */
    public static boolean matchesAcceptedPresentationGeometry(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (rejection(player, snapshot) != Rejection.NONE) return false;
        var dimensions = snapshot.placement().shape().dimensions();
        var transport = snapshot.placement().transport();
        var accepted = snapshot.placement().bounds();
        AxisAlignedBB current = player.getEntityBoundingBox();
        return player.width == (float) dimensions.width() && player.height == (float) dimensions.height()
                && player.posX == transport.x() && player.posY == transport.y() && player.posZ == transport.z()
                && current.minX == accepted.min().x() && current.minY == accepted.min().y()
                && current.minZ == accepted.min().z() && current.maxX == accepted.max().x()
                && current.maxY == accepted.max().y() && current.maxZ == accepted.max().z();
    }

    private static boolean matchesPreparedGeometry(EntityPlayer player, PreparedMirror prepared) {
        var snapshot = prepared.snapshot;
        if (rejection(player, snapshot) != Rejection.NONE) return false;
        AxisAlignedBB current = player.getEntityBoundingBox();
        AxisAlignedBB accepted = prepared.bounds;
        return player.width == prepared.width && player.height == prepared.height
                && player.eyeHeight == prepared.eyeHeight
                && player.posX == prepared.positionX && player.posY == prepared.positionY
                && player.posZ == prepared.positionZ
                && current.minX == accepted.minX && current.minY == accepted.minY
                && current.minZ == accepted.minZ && current.maxX == accepted.maxX
                && current.maxY == accepted.maxY && current.maxZ == accepted.maxZ;
    }

    /** Compatibility entry point for callers that do not need an intervening attempt check. */
    public static boolean install(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        Preparation preparation = prepare(player, snapshot);
        if (preparation.rejection() != Rejection.NONE) return false;
        BoundsApplied boundsApplied = commitPrepared(preparation.prepared().orElseThrow());
        commitRemaining(boundsApplied);
        return true;
    }
}
