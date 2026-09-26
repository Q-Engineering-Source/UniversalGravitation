package wi.unigrav.core.boost;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.math.AxisAlignedBB;

import wi.unigrav.api.math.Vec3;

/** Dedicated-only native/Mixin target portion of /ugboost selftest. */
final class RemoteBoostNativeDiagnostics {
    private RemoteBoostNativeDiagnostics() {
    }

    static RemoteBoostDiagnostics.Result runHeadlessSelfTest() {
        int checks = 0;
        RemoteBoostDiagnostics.require(NetHandlerPlayServer.class != null,
                "NetHandlerPlayServer class literal");
        checks++;
        RemoteBoostDiagnostics.require(EntityTrackerEntry.class != null,
                "EntityTrackerEntry class literal");
        checks++;
        AxisAlignedBB player = new AxisAlignedBB(-0.3D, 0.0D, -0.3D, 0.3D, 1.8D, 0.3D);
        AxisAlignedBB wall = new AxisAlignedBB(5.0D, 0.0D, -1.0D, 6.0D, 2.0D, 1.0D);
        RemoteBoostDiagnostics.require(Math.abs(wall.calculateXOffset(player, 24.5D) - 4.7D) <= 1.0e-9,
                "native AABB wall offset");
        checks++;
        ByteBuf payload = Unpooled.buffer(BoostProtocol.PACKET_BYTES);
        try {
            BoostProtocol.Packet source = BoostProtocol.frame(17L, 3L, 1L, 0, 5, false,
                    new Vec3(1.0D, -2.0D, 3.0D));
            new BoostWireMessage(source).toBytes(payload);
            BoostWireMessage decoded = new BoostWireMessage();
            decoded.fromBytes(payload);
            RemoteBoostDiagnostics.require(source.equals(decoded.packet()),
                    "common Forge packet adapter round-trip");
            checks++;
        } finally {
            payload.release();
        }
        checks += RemoteBoostNativePacketAdapter.verifyNoArgRoundTrips();
        checks += RemoteBoostDiagnostics.runPureSelfTest().checks();
        return new RemoteBoostDiagnostics.Result(checks,
                "headless common-codec/noarg-native-packet/budget/native-AABB; not connected-player acceptance");
    }
}
