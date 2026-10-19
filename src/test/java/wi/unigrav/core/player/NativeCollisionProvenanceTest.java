package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Direct bridge/ledger behavior only. These tests do not apply World Mixins. */
class NativeCollisionProvenanceTest {
    @TempDir Path temp;
    static final UUID PLAYER = new UUID(0, 9571);
    static final CollisionQueryFootprint.Limits LIMITS = new CollisionQueryFootprint.Limits(16384,16,4096,4096);
    static final BlockPos CELL = new BlockPos(8,68,8);
    record Input(FreeOwnerTransaction tx, AtomicBoolean current, CollisionQueryFootprint footprint, AxisAlignedBB query) implements AutoCloseable {
        NativeCollisionProvenance.Scope scope() {
            return NativeCollisionProvenance.open(tx, query, tx.resources().environmentObservationWork(), footprint, LIMITS.maxCandidateBoxes());
        }
        public void close() { tx.close(); }
    }
    static Input input(BootWorldFixture fixture) {
        var actor = fixture.serverPlayer(PLAYER);
        var placement = new PlayerBodyPlacement(new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,.5,.25)));
        actor.width=.5F; actor.height=.5F; actor.eyeHeight=.25F;
        var p=placement.transport(); actor.setPosition(p.x(),p.y(),p.z()); var b=placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
        var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),actor.dimension,1),7,placement,new Vec3d(0,0,0));
        var current=new AtomicBoolean(true);
        var tx=new FreeOwnerTransaction(actor,accepted,new PlayerPhysicsStep.TravelState(),
                new BoostMovementBudget(10,0,new Vec3(p.x(),p.y(),p.z())),current::get);
        fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(CELL,Blocks.STONE.getDefaultState());
        var footprint=CollisionQueryFootprint.plan(CapsuleFootContacts.window(accepted,.25),net.minecraft.world.World.MAX_ENTITY_RADIUS,LIMITS);
        var q=footprint.nativeBounds();
        return new Input(tx,current,footprint,new AxisAlignedBB(q.min().x(),q.min().y(),q.min().z(),q.max().x(),q.max().y(),q.max().z()));
    }
    static void actualStone(Input i,List<AxisAlignedBB> list) {
        var world=i.tx.world();
        var state=NativeCollisionProvenance.read(world,CELL,()->world.getBlockState(CELL));
        NativeCollisionProvenance.produce(state,world,CELL,i.query,list,i.tx.actor(),false,
                observed->state.addCollisionBoxToList(world,CELL,i.query,observed,i.tx.actor(),false));
    }
    NativeCollisionProvenance.Observation observe(Input i,Consumer<List<AxisAlignedBB>> producers,Consumer<List<AxisAlignedBB>> event) {
        var list=new ArrayList<AxisAlignedBB>();
        try(var scope=i.scope()) {
            try(var nativeCall=NativeCollisionProvenance.enterNative(i.tx.world(),i.tx.actor(),i.query)) {
                try(var collector=NativeCollisionProvenance.enterCollector(i.tx.world(),i.tx.actor(),i.query,list)) { producers.accept(list); }
                event.accept(list); nativeCall.returned(list);
            }
            return scope.finish(list);
        }
    }
    @Test void sameOwnerAndFrameDoNotAuthorizeDifferentTransaction() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("same-frame-owner"), PLAYER, false);
                var initial = input(fixture)) {
            var actor = initial.tx.actor();
            var accepted = initial.tx.accepted();
            var travel = new PlayerPhysicsStep.TravelState();
            var owner = new Object();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(),
                    accepted.scope(), travel.correctionRevision());
            var p = accepted.placement().transport();
            var ledger = new BoostMovementBudget(10, 0, new Vec3(p.x(), p.y(), p.z()));
            try (var first = new FreeOwnerTransaction(actor, accepted, travel, ledger,
                        initial.current::get, owner, resources);
                    var second = new FreeOwnerTransaction(actor, accepted, travel, ledger,
                        initial.current::get, owner, resources)) {
                first.requireCurrent(); second.requireCurrent();
                assertSame(first.resources(), second.resources());
                assertSame(first.queries(), second.queries());
                assertSame(first.numeric(), second.numeric());
                var observedInput = new Input(first, initial.current, initial.footprint, initial.query);
                var observation = observe(observedInput, list -> actualStone(observedInput, list), list -> { });
                assertTrue(observation.matchesOwner(first));
                assertFalse(observation.matchesOwner(second));
                observation.requireOwnerCurrent();
            }
        }
    }
    @Test void blockProducerJoinsExactTransactionWithRealEndpointAndComplete() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("full-transaction-join"), PLAYER, false);
                var initial = input(fixture)) {
            var actor = initial.tx.actor();
            var accepted = initial.tx.accepted();
            var travel = new PlayerPhysicsStep.TravelState();
            var owner = new Object();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(),
                    accepted.scope(), travel.correctionRevision());
            var p = accepted.placement().transport();
            var ledger = new BoostMovementBudget(10, 0, new Vec3(p.x(), p.y(), p.z()));
            try (var first = new FreeOwnerTransaction(actor, accepted, travel, ledger,
                        initial.current::get, owner, resources);
                    var second = new FreeOwnerTransaction(actor, accepted, travel, ledger,
                        initial.current::get, owner, resources)) {
                assertNotSame(first, second);
                assertSame(first.resources(), second.resources());
                assertSame(first.queries(), second.queries());
                assertSame(first.numeric(), second.numeric());
                var window = CapsuleFootContacts.window(accepted, .25);
                // Runs the real mapped World scan and Forge event through the TEST bridge.
                // It does not prove that the production Mixin applied in normal FML.
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(first, window,
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, LIMITS,
                                first.resources().environmentObservationWork()));
                var pose = CapsulePlacementMath.pose(accepted.placement(), first.numeric());
                var lineage = CapsulePathSource.actualFree(accepted, pose, capture, window,
                        FreeOwnerTransaction.ACCURACY, first.queries(), first.numeric());
                var endpointA = CertifiedReferenceEndpoint.from(first, lineage);
                var support = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                        CapsuleSupportObservation.observe(endpointA, capture, window, .25,
                                FreeOwnerTransaction.ACCURACY));
                assertFalse(support.supports().isEmpty(), "stone must provide real touching support");
                int ordinal = support.supports().getFirst().ordinal();
                var producer = capture.blockProducer(endpointA, support, ordinal).orElseThrow();
                assertEquals(CELL, producer.cell());
                assertSame(Blocks.STONE.getDefaultState(), producer.state());
                // Existing lineage/Complete constituent checks intentionally permit this same-Frame
                // endpoint; only the provenance's exact transaction may grant producer authority.
                var endpointB = CertifiedReferenceEndpoint.from(second, lineage);
                endpointB.requireCurrent();
                assertSame(endpointA.pose(), endpointB.pose());
                assertTrue(support.matches(second.accepted(), endpointB.pose(), capture, window,
                        second.queries(), second.numeric()));
                assertTrue(capture.blockProducer(endpointB, support, ordinal).isEmpty());
                assertSame(producer, capture.blockProducer(endpointA, support, ordinal).orElseThrow(),
                        "rejected borrowing must not invalidate the actual owner's evidence");
            }
        }
    }
    @Test void realStoneOutputAndSameObjectRemoveReinsertRemainBlock() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("stone"),PLAYER,false);var i=input(f)) {
            var result=observe(i,list->actualStone(i,list),list->{var box=list.remove(0);list.add(box);});
            assertEquals(1,result.entries().size()); assertEquals(NativeCollisionProvenance.Kind.BLOCK,result.entries().getFirst().kind());
            assertEquals(CELL,result.entries().getFirst().cell()); assertSame(Blocks.STONE.getDefaultState(),result.entries().getFirst().state());
        }
        assertFalse(NativeCollisionProvenance.scoped());
    }
    @Test void removalCopiedReplacementAndDuplicateIdentityDoNotGainBlockAuthority() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("event"),PLAYER,false);var i=input(f)) {
            assertTrue(observe(i,list->actualStone(i,list),List::clear).entries().isEmpty());
            var copied=observe(i,list->actualStone(i,list),list->{var b=list.remove(0);list.add(new AxisAlignedBB(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));});
            assertEquals(NativeCollisionProvenance.Kind.UNATTRIBUTED,copied.entries().getFirst().kind());
            var duplicate=observe(i,list->actualStone(i,list),list->list.add(list.getFirst()));
            assertTrue(duplicate.entries().stream().allMatch(e->e.kind()==NativeCollisionProvenance.Kind.AMBIGUOUS));
        }
    }
    @Test void syntheticBorderWithoutActualReadCannotClaimStoneCell() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("border"),PLAYER,false);var i=input(f)) {
            var result=observe(i,list->{var state=Blocks.STONE.getDefaultState();
                NativeCollisionProvenance.produce(state,i.tx.world(),CELL,i.query,list,i.tx.actor(),false,
                    observed->state.addCollisionBoxToList(i.tx.world(),CELL,i.query,observed,i.tx.actor(),false));},list->{});
            assertEquals(NativeCollisionProvenance.Kind.UNATTRIBUTED,result.entries().getFirst().kind());
        }
    }
    @Test void sameObjectClaimedByTwoInvocationsIsAmbiguousNeverLastWins() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("ambiguous"),PLAYER,false);var i=input(f)) {
            var shared=new AxisAlignedBB(8,68,8,9,69,9);
            var result=observe(i,list->{
                for(int n=0;n<2;n++) {
                    var state=NativeCollisionProvenance.read(i.tx.world(),CELL,()->i.tx.world().getBlockState(CELL));
                    NativeCollisionProvenance.produce(state,i.tx.world(),CELL,i.query,list,i.tx.actor(),false,observed->{observed.clear();observed.add(shared);});
                }
            },list->{});
            assertEquals(NativeCollisionProvenance.Kind.AMBIGUOUS,result.entries().getFirst().kind());
        }
    }
    @Test void observerHandlesSelfSubListIteratorAndSetActualMutations() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("list"),PLAYER,false);var i=input(f)) {
            var a=new AxisAlignedBB(8,68,8,9,69,9); var b=new AxisAlignedBB(8,67,8,9,68,9);
            var result=observe(i,list->{
                var state=NativeCollisionProvenance.read(i.tx.world(),CELL,()->i.tx.world().getBlockState(CELL));
                NativeCollisionProvenance.produce(state,i.tx.world(),CELL,i.query,list,i.tx.actor(),false,observed->{
                    observed.add(a); observed.addAll(observed); assertEquals(2,observed.size());
                    observed.subList(1,2).clear(); var iterator=observed.listIterator(); iterator.next(); iterator.set(b);
                    observed.addAll(observed.subList(0,1)); observed.remove(1);
                });
            },list->{});
            assertEquals(1,result.entries().size()); assertEquals(NativeCollisionProvenance.Kind.BLOCK,result.entries().getFirst().kind());
            assertEquals(67,result.entries().getFirst().bounds().min().y());
        }
    }
    @Test void originalExceptionAndOwnerRetireClearFramesAndDoNotRefund() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("failure"),PLAYER,false);var i=input(f)) {
            var failure=new IllegalStateException("actual callback failure");
            assertSame(failure,assertThrows(IllegalStateException.class,()->observe(i,list->{
                var state=NativeCollisionProvenance.read(i.tx.world(),CELL,()->i.tx.world().getBlockState(CELL));
                NativeCollisionProvenance.produce(state,i.tx.world(),CELL,i.query,list,i.tx.actor(),false,observed->{throw failure;});
            },list->{})));
            assertFalse(NativeCollisionProvenance.scoped()); long units=i.tx.resources().postprocessing().units();
            assertTrue(units>0);
            assertThrows(IllegalStateException.class,()->observe(i,list->{actualStone(i,list);i.current.set(false);},list->{}));
            assertFalse(NativeCollisionProvenance.scoped()); assertTrue(i.tx.resources().postprocessing().units()>=units);
        }
    }
    @Test void nestedUnownedNativeCallCannotClaimOuterObjects() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("nested"),PLAYER,false);var i=input(f)) {
            var result=observe(i,list->{
                try(var nested=NativeCollisionProvenance.enterNative(i.tx.world(),i.tx.actor(),i.query)) {
                    try(var collector=NativeCollisionProvenance.enterCollector(i.tx.world(),i.tx.actor(),i.query,list)) { actualStone(i,list); }
                    nested.returned(list);
                }
                actualStone(i,list);
            },list->{});
            assertEquals(2,result.entries().size());
            assertEquals(NativeCollisionProvenance.Kind.UNATTRIBUTED,result.entries().get(0).kind());
            assertEquals(NativeCollisionProvenance.Kind.BLOCK,result.entries().get(1).kind());
        }
    }
    @Test void sameFrameExhaustionIsExplicitAndCannotBeRefundedOrSwallowed() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("budget"),PLAYER,false);var i=input(f)) {
            var work=i.tx.resources().postprocessing();work.charge(FreePostprocessingWork.MAX_UNITS-work.units());
            assertThrows(FreePostprocessingWork.Exhausted.class,()->observe(i,list->actualStone(i,list),list->{}));
            assertEquals(FreePostprocessingWork.MAX_UNITS,work.units());assertFalse(NativeCollisionProvenance.scoped());
        }
    }
    @Test void missingWorldTransformationFailsExplicitlyRatherThanEmptyProvenance() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("missing"),PLAYER,false);var i=input(f);var scope=i.scope()) {
            var list=new ArrayList<AxisAlignedBB>();
            var failure=assertThrows(IllegalStateException.class,()->scope.finish(list));
            assertTrue(failure.getMessage().contains("missing or incomplete"));
        }
    }
}
