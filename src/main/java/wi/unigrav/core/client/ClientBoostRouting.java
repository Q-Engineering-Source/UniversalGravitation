package wi.unigrav.core.client;

import net.minecraft.client.entity.EntityPlayerSP;
import java.util.Objects;
import java.util.function.Function;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import wi.unigrav.api.math.Vec3;

/** Optional client-only source of an authoritative boost direction and selected gear. */
public final class ClientBoostRouting {

    private static Provider provider;
    private static Function<EntityPlayerSP, PlayerPhysicsProtocol.Controls> physicalProvider;
    private static Function<EntityPlayerSP, PlayerPhysicsClientPresentation.ViewBasis> viewBasisProvider;

    private ClientBoostRouting() {
    }

    public static synchronized Registration register(Provider nextProvider) {
        if (nextProvider == null) {
            throw new NullPointerException("nextProvider");
        }
        provider = nextProvider;
        return () -> {
            synchronized (ClientBoostRouting.class) {
                if (provider == nextProvider) {
                    provider = null;
                }
            }
        };
    }

    /**
     * Returns the active UIE-owned boost plan, if any. Remote boost deliberately
     * consumes this same Q-forward provider rather than synthesizing a second
     * direction source from vanilla Euler look.
     */
    public static synchronized Plan query(EntityPlayerSP player) {
        return provider != null ? provider.plan(player) : null;
    }

    /** Whether a camera integration has installed the sole boost-direction provider. */
    public static synchronized boolean hasProvider() {
        return provider != null;
    }

    /** Optional UIE-owned complete input sample, separate from legacy acceleration application. */
    public static synchronized Registration registerPhysical(Function<EntityPlayerSP, PlayerPhysicsProtocol.Controls> next) {
        physicalProvider = Objects.requireNonNull(next, "physical input provider");
        return () -> {
            synchronized (ClientBoostRouting.class) {
                if (physicalProvider == next) physicalProvider = null;
            }
        };
    }

    public static synchronized PlayerPhysicsProtocol.Controls queryPhysical(EntityPlayerSP player) {
        return physicalProvider == null ? null : physicalProvider.apply(player);
    }

    public static synchronized Registration registerViewBasis(
            Function<EntityPlayerSP, PlayerPhysicsClientPresentation.ViewBasis> next) {
        viewBasisProvider = Objects.requireNonNull(next, "view basis provider");
        return () -> {
            synchronized (ClientBoostRouting.class) {
                if (viewBasisProvider == next) viewBasisProvider = null;
            }
        };
    }

    public static synchronized PlayerPhysicsClientPresentation.ViewBasis queryViewBasis(EntityPlayerSP player) {
        return viewBasisProvider == null ? null : viewBasisProvider.apply(player);
    }

    @FunctionalInterface
    public interface Provider {
        Plan plan(EntityPlayerSP player);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public record Plan(Vec3 direction, double accelerationMps2, double speedLimitMps,
            int gear, boolean suppressed) {
        public Plan(Vec3 direction, double accelerationMps2, double speedLimitMps,
                boolean suppressed) {
            this(direction, accelerationMps2, speedLimitMps, 5, suppressed);
        }

        public Plan(Vec3 direction, double accelerationMps2, double speedLimitMps) {
            this(direction, accelerationMps2, speedLimitMps, 5, false);
        }
    }
}
