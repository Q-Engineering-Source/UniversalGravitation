package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPredictionSeedObservationTest {
    private static final UUID PLAYER=new UUID(0,7311);
    @TempDir Path temp;
    private record Input(net.minecraft.entity.player.EntityPlayerMP actor, PlayerPhysicalSnapshot snapshot,
                         PlayerPhysicsStep.TravelState travel, FreeTickResources resources,
                         PlayerEnvironmentSampler sampler, PlayerPhysicsStep step) { }
    private Input input(BootWorldFixture fixture, GravityEntityBinder binder) {
        var actor=fixture.serverPlayer(PLAYER);actor.setPosition(8.5,80,8.5);actor.setNoGravity(true);
        FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0,0)));
        var shape=new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight()));
        var bounds=actor.getEntityBoundingBox();
        var placement=PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(bounds.minX,bounds.minY,bounds.minZ),
                new Vec3d(bounds.maxX,bounds.maxY,bounds.maxZ)),new Vec3d(actor.posX,actor.posY,actor.posZ),CardinalFrame.Y_POS.attitude(),shape);
        var scope=new PlayerPhysicalSnapshot.Scope(PLAYER,new UUID(0,7312),actor.dimension,1);
        var snapshot=new PlayerPhysicalSnapshot(scope,0,placement,new Vec3d(0,0,0));
        var travel=new PlayerPhysicsStep.TravelState();
        var resources=new FreeTickResources.Store().acquire(travel,fixture.world(),scope,travel.correctionRevision());
        var sampler=new PlayerEnvironmentSampler(binder,MagneticResponseRules.builtIn(),.5,ActiveBootRules.core());
        return new Input(actor,snapshot,travel,resources,sampler,new PlayerPhysicsStep(sampler));
    }
    private CardinalPredictionSeed seed(Input i) {
        return i.step.observePredictionSeed(i.actor,i.snapshot,Optional.of(false),i.travel,10,i.resources,
                ()->i.resources.requireCurrent(i.travel,i.actor.world,i.snapshot.scope(),i.travel.correctionRevision()),null);
    }
    @Test void actualMissingChunkIsTypedUnavailableAndOriginalThrowingProducerIsDistinct() {
        try(var fixture=BootWorldFixture.open(temp.resolve("missing-cause"),PLAYER,false)) {
            var binder=new GravityEntityBinder();var i=input(fixture,binder);
            try(var binding=binder.bind(i.actor,GravityMode.REPLACE)) {
                var chunk=fixture.world().getChunkProvider().loadedChunks.remove(0L);
                var unavailable=assertInstanceOf(CardinalPredictionSeed.Unavailable.class,seed(i));
                assertEquals(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE,unavailable.reason());
                long spent=i.resources.environmentObservationWork().usage().units();
                assertTrue(spent>0);
                fixture.world().getChunkProvider().loadedChunks.put(0L,chunk);
                assertInstanceOf(CardinalPredictionSeed.Ready.class,seed(i));
                assertTrue(i.resources.environmentObservationWork().usage().units()>spent,"same Frame cannot refund failed work");
                var injected=new IllegalStateException("actual seed producer cause");
                try(var source=AmbientWorldFields.field(fixture.world()).registerContribution("seed-test","throwing",(p,t)->{throw injected;})) {
                    var failed=assertInstanceOf(CardinalPredictionSeed.Unavailable.class,seed(i));
                    assertEquals(CardinalPredictionSeed.Reason.SAMPLING_FAILED,failed.reason());
                    assertSame(injected,failed.cause().orElseThrow());
                    assertArrayEquals(injected.getStackTrace(),failed.cause().orElseThrow().getStackTrace());
                }
            }
        }
    }
    @Test void ownerSampleRetainsRawSourceReceiptWithoutRecomputingItAndExcludesQualifiedBoots() {
        try(var fixture=BootWorldFixture.open(temp.resolve("raw-receipt"),PLAYER,false)) {
            var binder=new GravityEntityBinder();var i=input(fixture,binder);var calls=new AtomicInteger();
            try(var binding=binder.bind(i.actor,GravityMode.REPLACE)) {
                var source=AmbientWorldFields.field(fixture.world()).registerContribution("seed-test","source",(p,t)->{calls.incrementAndGet();return new Vec3(1,2,3);});
                var sample=assertInstanceOf(PlayerEnvironmentSampler.Sample.class,i.sampler.sample(i.actor,i.snapshot,Optional.of(false),null,i.resources.environmentObservationWork()));
                assertEquals(1,calls.get());sample.requireCurrent();assertEquals(1,calls.get());
                source.close();assertThrows(IllegalStateException.class,sample::requireCurrent);assertEquals(1,calls.get());
                i.actor.setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
                var boots=assertInstanceOf(CardinalPredictionSeed.Unsupported.class,seed(i));
                assertEquals(CardinalPredictionSeed.Reason.ACTIVE_BOOTS,boots.reason());
            }
        }
    }
    @Test void readyStandingReferenceMirrorsNativeAndSavedManualPriorityWithoutCommit() {
        try(var fixture=BootWorldFixture.open(temp.resolve("reference-policy"),PLAYER,false)) {
            var binder=new GravityEntityBinder();var i=input(fixture,binder);
            assertTrue(assertInstanceOf(CardinalPredictionSeed.Ready.class,seed(i)).standingReference());
            i.travel.nativeNoGravity=true;
            assertFalse(assertInstanceOf(CardinalPredictionSeed.Ready.class,seed(i)).standingReference());
            var manual=new PlayerBodyReference.ManualIntent(1,CardinalFrame.Y_POS,new Vec3d(0,0,1));
            var state=PlayerBodyReference.State.initial(CardinalFrame.Y_POS,new Vec3d(0,0,1))
                    .withManual(Optional.of(manual),PlayerBodyReference.Transition.STABLE);
            i.travel.bodyReference(state);
            assertTrue(assertInstanceOf(CardinalPredictionSeed.Ready.class,seed(i)).standingReference());
            assertSame(state,i.travel.bodyReference(),"seed observation must not commit reference state");
        }
    }}
