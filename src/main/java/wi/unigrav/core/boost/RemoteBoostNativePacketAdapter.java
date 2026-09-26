package wi.unigrav.core.boost;

import java.io.IOException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketPlayer;

import wi.unigrav.api.math.Vec3;

/**
 * Builds a common-side replacement for a bounded native movement proposal.
 *
 * <p>The parameterized {@code CPacketPlayer} constructors are client-only in this mapping. The
 * no-arg common constructors plus the packet's normal reader preserve rotation and on-ground
 * semantics without linking a dedicated server to a client-only constructor.
 */
final class RemoteBoostNativePacketAdapter {
    private RemoteBoostNativePacketAdapter() {
    }

    static CPacketPlayer replacePosition(CPacketPlayer original, Vec3 endpoint) {
        if (original == null || !BoostProtocol.finite(endpoint)) {
            throw new IllegalArgumentException("Finite packet and endpoint required");
        }
        boolean rotation = original instanceof CPacketPlayer.PositionRotation;
        if (!(original instanceof CPacketPlayer.Position) && !rotation) {
            return original;
        }
        ByteBuf bytes = Unpooled.buffer(rotation ? 34 : 25);
        try {
            PacketBuffer output = new PacketBuffer(bytes);
            output.writeDouble(endpoint.x());
            output.writeDouble(endpoint.y());
            output.writeDouble(endpoint.z());
            if (rotation) {
                output.writeFloat(original.getYaw(0.0F));
                output.writeFloat(original.getPitch(0.0F));
            }
            output.writeBoolean(original.isOnGround());

            CPacketPlayer replacement = rotation ? new CPacketPlayer.PositionRotation()
                    : new CPacketPlayer.Position();
            replacement.readPacketData(new PacketBuffer(bytes));
            return replacement;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode bounded common player packet", exception);
        } finally {
            bytes.release();
        }
    }

    /** Read-only common-side proof of both no-arg packet variants used by production replacement. */
    static int verifyNoArgRoundTrips() {
        CPacketPlayer position = readNoArg(false, 11.0D, 12.0D, 13.0D, 0.0F, 0.0F, true);
        CPacketPlayer boundedPosition = replacePosition(position, new Vec3(1.0D, 2.0D, 3.0D));
        require(boundedPosition instanceof CPacketPlayer.Position
                && !(boundedPosition instanceof CPacketPlayer.PositionRotation)
                && boundedPosition.getX(0.0D) == 1.0D && boundedPosition.getY(0.0D) == 2.0D
                && boundedPosition.getZ(0.0D) == 3.0D && boundedPosition.isOnGround(),
                "common no-arg Position replacement");

        CPacketPlayer rotation = readNoArg(true, 21.0D, 22.0D, 23.0D, 31.25F, -17.5F, false);
        CPacketPlayer boundedRotation = replacePosition(rotation, new Vec3(4.0D, 5.0D, 6.0D));
        require(boundedRotation instanceof CPacketPlayer.PositionRotation
                && boundedRotation.getX(0.0D) == 4.0D && boundedRotation.getY(0.0D) == 5.0D
                && boundedRotation.getZ(0.0D) == 6.0D && boundedRotation.getYaw(0.0F) == 31.25F
                && boundedRotation.getPitch(0.0F) == -17.5F && !boundedRotation.isOnGround(),
                "common no-arg PositionRotation replacement");
        return 2;
    }

    private static CPacketPlayer readNoArg(boolean rotation, double x, double y, double z, float yaw,
            float pitch, boolean onGround) {
        ByteBuf bytes = Unpooled.buffer(rotation ? 34 : 25);
        try {
            PacketBuffer output = new PacketBuffer(bytes);
            output.writeDouble(x);
            output.writeDouble(y);
            output.writeDouble(z);
            if (rotation) {
                output.writeFloat(yaw);
                output.writeFloat(pitch);
            }
            output.writeBoolean(onGround);
            CPacketPlayer result = rotation ? new CPacketPlayer.PositionRotation()
                    : new CPacketPlayer.Position();
            result.readPacketData(new PacketBuffer(bytes));
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read common no-arg player packet", exception);
        } finally {
            bytes.release();
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("Remote boost native packet check failed: " + check);
    }
}
