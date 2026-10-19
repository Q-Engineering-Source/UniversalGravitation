package wi.unigrav.core.player;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.init.Bootstrap;
import net.minecraft.server.management.PlayerInteractionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class NativeCardinalBodyProfileTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static final class ProfilePlayer extends EntityPlayerMP implements NativeBodyProfileProducer {
        private final Profile base;
        private Profile value;
        private Runnable afterRead;
        ProfilePlayer(BootWorldFixture world) {
            super(prepareSpawn(world), world.world(), new GameProfile(new UUID(0,11902),"body-profile"),
                    new PlayerInteractionManager(world.world()));
            base = new Profile(Source.VANILLA_CONSTRUCTOR,width,height,width,height);
            value = base;
            setPosition(8,65,8);
        }
        private static net.minecraft.server.MinecraftServer prepareSpawn(BootWorldFixture world) {
            world.prepareServerPlayerSpawn();
            return world.server();
        }
        @Override public Optional<Profile> unigrav$nativeBodyProfile() {
            var sampled = value;
            var callback = afterRead; afterRead = null;
            if (callback != null) callback.run();
            return Optional.ofNullable(sampled);
        }
    }

    private static PlayerPhysicalSnapshot accepted(ProfilePlayer player) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(player);
        var dimensions = new PlayerBodyShape.Dimensions(player.width,player.height,player.getEyeHeight());
        var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(),actual.position(),
                CardinalFrame.Y_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,dimensions));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),new UUID(0,11903),0,1),
                0,placement,new Vec3d(0,0,0));
    }

    @Test void changedFinalValueRejectsButSameValueHistoryDoesNotInventAnEventGeneration(@TempDir Path directory) {
        var previous = UnigravConfig.nativeBodyProfileEnvironment;
        UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.VANILLA_UNSCALED;
        try (var world = BootWorldFixture.open(directory,new UUID(0,11901),true)) {
            var player = new ProfilePlayer(world);
            var snapshot = accepted(player);
            var observation = NativeCardinalBodyProfile.observe(player,snapshot);
            player.value = new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR,
                    player.base.baseDiameter(),player.base.baseLength(),player.base.diameter(),player.base.length()*2);
            assertFalse(observation.matches(snapshot));
            player.value = player.base;
            assertTrue(observation.matches(snapshot),"pure D/L observation is value-current, not mutation-history proof");
            var other = new PlayerPhysicalSnapshot(snapshot.scope(),1,snapshot.placement(),snapshot.worldVelocity());
            assertFalse(observation.matches(other),"same geometry cannot replace the exact accepted identity");
            UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.UNKNOWN;
            assertFalse(observation.matches(snapshot),"same values do not retain changed provider qualification");
        } finally { UnigravConfig.nativeBodyProfileEnvironment = previous; }
    }

    @Test void explicitVanillaQualificationIsRequiredEvenWhenThePublicIndexIsEmpty(@TempDir Path directory) {
        var previous = UnigravConfig.nativeBodyProfileEnvironment;
        try (var world = BootWorldFixture.open(directory,new UUID(0,11905),true)) {
            var player = new ProfilePlayer(world);
            var snapshot = accepted(player);
            UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.UNKNOWN;
            assertTrue(NativeCardinalBodyProfile.sample(player).isEmpty());
            assertThrows(IllegalStateException.class, () -> NativeCardinalBodyProfile.observe(player,snapshot));
            UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.VANILLA_UNSCALED;
            assertTrue(NativeCardinalBodyProfile.sample(player).orElseThrow().current());
            assertTrue(NativeCardinalBodyProfile.observe(player,snapshot).matches(snapshot));
            player.value = new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.AQUA_STANDING,
                    player.base.baseDiameter(),player.base.baseLength(),player.base.diameter(),player.base.length());
            assertTrue(NativeCardinalBodyProfile.sample(player).isEmpty(), "AQ source requires actual indexed AQ");
            assertThrows(IllegalStateException.class, () -> NativeCardinalBodyProfile.observe(player,snapshot));
        } finally { UnigravConfig.nativeBodyProfileEnvironment = previous; }
    }

    @Test void sameProfileReadCannotHideAReentrantRawAnchorChange(@TempDir Path directory) {
        var previous = UnigravConfig.nativeBodyProfileEnvironment;
        UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.VANILLA_UNSCALED;
        try (var world = BootWorldFixture.open(directory,new UUID(0,11904),true)) {
            var player = new ProfilePlayer(world);
            var snapshot = accepted(player);
            var observation = NativeCardinalBodyProfile.observe(player,snapshot);
            player.afterRead = () -> player.setPosition(player.posX+1,player.posY,player.posZ);
            assertFalse(observation.matches(snapshot));
        } finally { UnigravConfig.nativeBodyProfileEnvironment = previous; }
    }
    @ParameterizedTest
    @ValueSource(strings = {"owner", "qualification", "query", "numeric", "post", "native", "reserved", "moved-profile"})
    void definiteOccupiedTargetRequiresCurrentUnreservedSourceAndRemainingSharedWork(
            String invalidation, @TempDir Path directory) {
        var previous = UnigravConfig.nativeBodyProfileEnvironment;
        UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.VANILLA_UNSCALED;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 11906), true)) {
            var player = new ProfilePlayer(world);
            new NetHandlerPlayServer(world.server(), new NetworkManager(EnumPacketDirection.SERVERBOUND), player);
            // Test adapter retains the real constructor profile while presenting a short raw pose.
            player.height = .6F;
            player.setPosition(8.5, 65, 8.5);
            var actual = WorldCollisionCandidates.ActorGeometry.read(player);
            var raw = new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight());
            var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, raw));
            var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                    new UUID(0, 11908), 0, 1), 0, placement, new Vec3d(0, 0, 0));
            var travel = new PlayerPhysicsStep.TravelState();
            var budget = new BoostMovementBudget(70, 0, new Vec3(player.posX, player.posY, player.posZ));
            var current = new AtomicBoolean(true);
            var resources = new FreeTickResources.Store().acquire(travel, world.world(), snapshot.scope(), 0);
            try (var tx = new FreeOwnerTransaction(player, snapshot, travel, budget, current::get, travel, resources)) {
                var observation = NativeCardinalBodyProfile.observe(player, snapshot);
                var shape = new PlayerBodyShape.FreeCapsule(raw, observation.physical(), Optional.of(new FreeEyeAnchor(
                        FreeEyeAnchor.STORED_BASIS_V1, placement.center(), placement.eye(), placement.attitude())));
                var target = new PlayerBodyPlacement(placement.center(), placement.attitude(), shape, tx.numeric());
                var pose = CapsulePlacementMath.pose(target, tx.numeric());
                var window = new Aabb(new Vec3d(7, 63, 7), new Vec3d(10, 68, 10));
                world.installCollisionFootprint(CollisionQueryFootprint.plan(window, net.minecraft.world.World.MAX_ENTITY_RADIUS,
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN));
                world.world().getChunk(0, 0).setBlockState(new BlockPos(8, 66, 8), Blocks.STONE.getDefaultState());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class, WorldCollisionCandidates.capture(
                        tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN,
                        resources.environmentObservationWork()));
                var before = budget.snapshot();
                var proof = assertInstanceOf(NativeCardinalBodyProfile.TargetOccupied.class,
                        NativeCardinalBodyProfile.certify(tx, observation, pose, capture, window));
                proof.requireRetainedCardinalCurrent(tx);
                assertEquals(before, budget.snapshot(), "occupied fit has not reserved or refunded movement");
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE, tx.installationStage());
                assertEquals(actual, WorldCollisionCandidates.ActorGeometry.read(player));
                switch (invalidation) {
                    case "owner" -> current.set(false);
                    case "qualification" -> UnigravConfig.nativeBodyProfileEnvironment =
                            UnigravConfig.NativeBodyProfileEnvironment.UNKNOWN;
                    case "query" -> {
                        var box = capture.boxes().getFirst();
                        for (int attempt = 0; attempt < 512 && !tx.queries().terminal(); attempt++) {
                            try { tx.queries().query(pose.capsule(), box, FreeOwnerTransaction.ACCURACY); }
                            catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
                                assertTrue(tx.queries().terminal());
                            }
                        }
                        assertTrue(tx.queries().terminal());
                    }
                    case "numeric" -> tx.numeric().charge(StoredDyadic.MAX_WORK_UNITS - tx.numeric().units());
                    case "post" -> resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS
                            - resources.postprocessing().units());
                    case "native" -> {
                        for (int count = resources.nativeAttempts(); count < FreeTickResources.MAX_NATIVE_ATTEMPTS; count++)
                            resources.claimNativeAttempt();
                    }
                    case "moved-profile" -> {
                        player.setPosition(player.posX + .1, player.posY, player.posZ);
                        proof.requireCardinalTailProfileCurrent(tx);
                        player.value = new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR,
                                player.base.baseDiameter(), player.base.baseLength(), player.base.diameter(), player.base.length() * 2);
                        assertThrows(IllegalStateException.class, () -> proof.requireCardinalTailProfileCurrent(tx));
                        player.value = player.base;
                        proof.requireCardinalTailProfileCurrent(tx);
                        UnigravConfig.nativeBodyProfileEnvironment = UnigravConfig.NativeBodyProfileEnvironment.UNKNOWN;
                        player.setPosition(actual.position().x(), actual.position().y(), actual.position().z());
                    }
                    case "reserved" -> assertTrue(tx.reserve(tx.nextLedgerSequence(), 0, .1).accepted());
                    default -> throw new AssertionError(invalidation);
                }
                assertThrows(IllegalStateException.class, () -> proof.requireRetainedCardinalCurrent(tx));
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE, tx.installationStage());
                assertEquals(actual, WorldCollisionCandidates.ActorGeometry.read(player), "failed gate writes no actor geometry");
            } finally { player.getAdvancements().dispose(); }
        } finally { UnigravConfig.nativeBodyProfileEnvironment = previous; }
    }

}
