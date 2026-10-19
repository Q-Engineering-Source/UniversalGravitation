package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockLiquid;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootStandingEvidence;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorBlocks;
import wi.unigrav.core.floor.MagneticBlockReader;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import static org.junit.jupiter.api.Assertions.*;

class PlayerEnvironmentSamplerFreeTest {
    @TempDir Path temp;
    private static final UUID PLAYER = new UUID(0,4331);
    private static final CollisionQueryFootprint.Limits LIMITS = new CollisionQueryFootprint.Limits(16384,16,4096,4096);
    private static final CapsuleAabbContact3d.QueryLimits QUERY_POOL_LIMITS =
            new CapsuleAabbContact3d.QueryLimits(16384,100000,256,128,9344,150000,128);
    private record QueryProbe(Aabb obstacle,CapsuleAabbContact3d.Work work) { }
    private record Input(FreeOwnerTransaction tx, GravityEntityBinder binder, PlayerEnvironmentSampler sampler,
                         FreeTickResources.Store store, PlayerPhysicsStep.TravelState travel,
                         ActiveBootRules rules, BoostMovementBudget ledger) { }
    private Input input(BootWorldFixture fixture, Vec3d center, Rotation3d q, double width, double height) {
        return input(fixture,center,q,width,height,null,ActiveBootRules.core());
    }
    private Input input(BootWorldFixture fixture, Vec3d center, Rotation3d q, double width, double height,
                        Double logicalEyeHeight, ActiveBootRules rules) {
        var actor = fixture.serverPlayer(PLAYER);
        var travel = new PlayerPhysicsStep.TravelState();
        var scope = new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),actor.dimension,1);
        var store = new FreeTickResources.Store();
        var resources = store.acquire(travel,fixture.world(),scope,travel.correctionRevision());
        var placement = new PlayerBodyPlacement(center,q,new PlayerBodyShape.FreeCapsule(
                new PlayerBodyShape.Dimensions(width,height,logicalEyeHeight == null ? actor.getEyeHeight() : logicalEyeHeight)),resources.numeric());
        actor.width=(float)width; actor.height=(float)height;
        var p=placement.transport();actor.setPosition(p.x(),p.y(),p.z());var b=placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
        actor.setNoGravity(true);
        var chunk=fixture.world().getChunkProvider().loadedChunks.get(0L);
        FloorWorldFields.onChunkLoad(new ChunkEvent.Load(chunk));
        var accepted=new PlayerPhysicalSnapshot(scope,7,placement,new Vec3d(0,0,0));
        var ledger=new BoostMovementBudget(10,0,new Vec3(p.x(),p.y(),p.z()));
        var tx=new FreeOwnerTransaction(actor,accepted,travel,ledger,()->true,travel,resources);
        var binder=new GravityEntityBinder();
        return new Input(tx,binder,new PlayerEnvironmentSampler(binder,MagneticResponseRules.builtIn(),.5,rules),store,travel,rules,ledger);
    }
    private static ActiveBootRules.Snapshot nextRulesRevision(ActiveBootRules.Snapshot current) {
        long revision=Math.incrementExact(current.revision());
        var materials=new MagneticBlockReader.RuleSet(revision,current.materialRules().bindings());
        return new ActiveBootRules.Snapshot(revision,current.equipmentRules(),materials,current.tiers(),
                current.captureLimits(),current.compilerLimits(),current.queryLimits(),current.explicitMaterialTierOverrides());
    }
    private void put(BootWorldFixture f,BlockPos p,net.minecraft.block.state.IBlockState state) {
        f.world().getChunkProvider().loadedChunks.get(ChunkPos.asLong(p.getX()>>4,p.getZ()>>4)).setBlockState(p,state);
    }
    private PlayerEnvironmentSampler.FreeSample sample(Input i,boolean g) {
        var result=i.sampler.sampleFree(i.tx,Optional.of(g),LIMITS);
        return assertInstanceOf(PlayerEnvironmentSampler.FreeSample.class,result,()->result.toString());
    }

    private CertifiedReferenceEndpoint movedEndpoint(Input input, Vec3d delta) {
        var tx = input.tx();
        var pose = CapsulePlacementMath.pose(tx.accepted().placement(), tx.numeric());
        var tube = CapsulePlacementMath.chargedTranslationTube(pose, delta, tx.numeric());
        double allowance = tube.requestedDisplacementUpper();
        assertTrue(tx.reserve(tx.nextLedgerSequence(), tx.tick(), allowance).accepted());
        var window = CapsuleMovementResolver.window(tx.accepted(), tube);
        var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        LIMITS, tx.resources().environmentObservationWork()));
        var root = CapsulePathSource.actualFree(tx.accepted(), pose, capture, window,
                FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
        var resolved = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                CapsuleMovementResolver.resolveContinuationWithSlide(tx, root, tube, capture, 0, allowance));
        return CertifiedReferenceEndpoint.from(tx, CapsulePathSource.continueAfter(resolved, tx.numeric()));
    }

    @Test void endpointFloorReferenceRemainsAvailableInsideActualColumn() { endpointFloor(false); }
    @Test void endpointOutsideActualFloorColumnIsCompleteAbsenceWithoutChangingOriginalForce() { endpointFloor(true); }

    private void endpointFloor(boolean leaveColumn) {
        try (var fixture = BootWorldFixture.open(temp.resolve("endpoint-floor-" + leaveColumn), PLAYER, false)) {
            var input = input(fixture, new Vec3d(8.85, 70, 8.5), Rotation3d.identity(), .5, .5);
            put(fixture, new BlockPos(8, 60, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunkProvider().loadedChunks.get(0L)));
            try (var tx = input.tx()) {
                var original = sample(input, false);
                var acceleration = original.environmentAcceleration();
                assertTrue(original.fields().floorGravityStrengths().strength(CardinalFrame.Y_POS) > 0);
                var endpoint = movedEndpoint(input, new Vec3d(leaveColumn ? .3 : .05, 0, 0));
                var ledger = input.ledger().snapshot();
                var reference = assertInstanceOf(PlayerEnvironmentSampler.EndpointReference.class,
                        original.observeReference(endpoint, false));
                assertEquals(StandingConstraints.Availability.AVAILABLE, reference.fields().availability());
                assertEquals(leaveColumn, reference.fields().floorGravityStrengths().strength(CardinalFrame.Y_POS) == 0,
                        "existing finite column eligibility is reevaluated at the certified endpoint, without a touch prerequisite");
                assertEquals(endpoint.pose().placement().center(), reference.observation().physicalCenter());
                assertSame(tx.accepted(), reference.observation().accepted());
                assertTrue(reference.observation().recaptureMatches(tx));
                assertEquals(acceleration, original.environmentAcceleration(), "reference-only evaluation cannot reintegrate original forces");
                assertEquals(ledger, input.ledger().snapshot());
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE, tx.installationStage());
            }
        }
    }

    @Test void endpointRulesReloadInvalidatesRetainedObservationWithoutBecomingAbsence() {
        try (var fixture = BootWorldFixture.open(temp.resolve("endpoint-rules-reload"), PLAYER, false)) {
            var input = input(fixture, new Vec3d(8.5, 70, 8.5), Rotation3d.identity(), .5, .5);
            try (var tx = input.tx()) {
                var original = sample(input, false);
                var endpoint = movedEndpoint(input, new Vec3d(.1, 0, 0));
                var reference = assertInstanceOf(PlayerEnvironmentSampler.EndpointReference.class,
                        original.observeReference(endpoint, false));
                assertTrue(reference.observation().recaptureMatches(tx));
                var ledger = input.ledger().snapshot();
                input.rules().install(nextRulesRevision(input.rules().snapshot()));
                var invalidated = assertThrows(IllegalStateException.class, () -> reference.observation().matches(tx));
                assertEquals("Free floor, boot rules or policy changed during environment observation", invalidated.getMessage());
                assertThrows(IllegalStateException.class, () -> reference.observation().recaptureMatches(tx));
                assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class, original.observeReference(endpoint, false));
                assertEquals(ledger, input.ledger().snapshot());
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE, tx.installationStage());
            }
        }
    }

    @Test void endpointFieldExceptionPreservesExactCauseAndCannotBecomeAbsence() {
        try (var fixture = BootWorldFixture.open(temp.resolve("endpoint-field-throw"), PLAYER, false)) {
            var input = input(fixture, new Vec3d(8.5, 70, 8.5), Rotation3d.identity(), .5, .5);
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var marker = new IllegalStateException("endpoint field producer failure");
            var evaluations = new AtomicInteger();
            try (var binding = input.binder().bind(input.tx().actor(), GravityMode.REPLACE);
                 var field = GravityFields.get().registerContribution("fixture", "endpoint-field-throw", (point, tick) -> {
                     evaluations.incrementAndGet();
                     if (armed.get()) throw marker;
                     return new Vec3(0, -8, 0);
                 }); var tx = input.tx()) {
                var original = sample(input, false);
                assertEquals(1, evaluations.get());
                var endpoint = movedEndpoint(input, new Vec3d(.1, 0, 0));
                var ledger = input.ledger().snapshot();
                armed.set(true);
                var failed = assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,
                        original.observeReference(endpoint, false));
                assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED, failed.reason());
                assertSame(marker, failed.cause().orElseThrow());
                assertEquals(2, evaluations.get(), "one original evaluation and one endpoint reference evaluation");
                assertEquals(ledger, input.ledger().snapshot(), "failed reference work does not refund the path reservation");
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE, tx.installationStage());
            }
        }
    }

    @Test void actualTiltedFloatImageAndPhysicalCenterFieldKeepPfSeparate() {
        try(var f=BootWorldFixture.open(temp.resolve("center"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),new Rotation3d(0,0,1,3),.6,1.8);
            var seen=new AtomicReference<Vec3>();
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);
                var field=GravityFields.get().registerContribution("fixture","free-center",(p,t)->{seen.set(p);return new Vec3(0,-8,0);});
                var tx=i.tx) {
                long before=tx.numeric().units();long calls=tx.queries().calls();
                var s=sample(i,false);var o=s.observation();
                assertEquals(new Vec3(8.5,70,8.5),seen.get());assertEquals(tx.accepted().placement().center(),o.physicalCenter());
                assertNotEquals(o.physicalCenter(),o.physicalPf());assertNotEquals(tx.accepted().placement().transport(),o.physicalPf());
                assertEquals(.3,o.pose().capsule().radius());assertEquals((float).6,tx.actor().width);
                assertTrue(tx.numeric().units()>before);assertTrue(tx.queries().calls()>calls);
                assertTrue(o.recaptureMatches(tx));assertTrue(s.fields().actualSupport().isEmpty());assertTrue(s.fields().bootCandidates().isEmpty());
                assertEquals(new Vec3(0,-8,0),s.environmentAcceleration());
            }
        }
    }
    @Test void logicalEyeImageAcceptsNativeFloatAndReceiptRejectsRealSneakingChange() {
        try(var f=BootWorldFixture.open(temp.resolve("logical-eye"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.6,1.8,1.62,ActiveBootRules.core());
            try(var tx=i.tx) {
                float nativeEyeBefore=tx.actor().getEyeHeight();
                assertNotEquals((double)nativeEyeBefore,1.62);
                assertEquals(Float.floatToIntBits(nativeEyeBefore),Float.floatToIntBits((float)1.62));
                var observation=sample(i,false).observation();
                assertEquals(.3,observation.pose().capsule().radius());
                assertTrue(observation.matches(tx));
                tx.actor().setSneaking(true);
                assertNotEquals(Float.floatToIntBits(nativeEyeBefore),Float.floatToIntBits(tx.actor().getEyeHeight()));
                var stale=assertThrows(IllegalStateException.class,()->observation.matches(tx));
                assertTrue(stale.getMessage().contains("changed"));
            }
        }
    }
    @Test void floorForcesUsePhysicalCenterAndKeepSixAxisReferencesIndependent() {
        try(var f=BootWorldFixture.open(temp.resolve("floor-center"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),new Rotation3d(0,0,1,3),.6,1.8);
            put(f,new BlockPos(8,60,8),FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(f.world().getChunkProvider().loadedChunks.get(0L)));
            try(var tx=i.tx) {
                var decision=FloorWorldFields.captureDecision(f.world()).orElseThrow();
                var center=decision.sampleForStanding(new Vec3(8.5,70,8.5),0).orElseThrow();
                var p=tx.accepted().placement().transport();var proxy=decision.sampleForStanding(new Vec3(p.x(),p.y(),p.z()),0).orElseThrow();
                var s=sample(i,false);assertEquals(center.forces().gravityAccel(),s.fields().gravityAcceleration());
                assertNotEquals(proxy.forces().gravityAccel(),center.forces().gravityAccel());
                assertTrue(s.observation().support().supports().isEmpty());assertFalse(s.fields().faceEvidence().isEmpty());
            }
        }
    }
    @Test void noBootStillExecutesFullActualContactAndNearIsNotSupport() {
        for(double gap:new double[]{0,.125}) {
            try(var f=BootWorldFixture.open(temp.resolve("support"+gap),PLAYER,false)) {
                var i=input(f,new Vec3d(8.5,69.25+gap,8.5),Rotation3d.identity(),.5,.5);
                put(f,new BlockPos(8,68,8),Blocks.STONE.getDefaultState());
                try(var tx=i.tx) {
                    var o=sample(i,false).observation();
                    assertTrue(o.boots().measurements().isEmpty());assertFalse(o.support().foot().queries().isEmpty());
                    if(gap==0) assertEquals(1,o.support().supports().size());
                    else {assertTrue(o.support().supports().isEmpty());assertTrue(o.support().foot().queries().stream().anyMatch(q->q.classification()==CapsuleFootContacts.Classification.NEAR));}
                }
            }
        }
    }
    @Test void qualifiedSupportRequiresActualProducerFootTouchAndChargesSharedClassification() {
        try(var f=BootWorldFixture.open(temp.resolve("qualified-actual-touch"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.25,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(8,68,8),Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            try(var tx=i.tx) {
                var observation=sample(i,false).observation();
                var measurements=observation.boots().measurements().orElseThrow();
                long before=tx.postprocessing().units();
                var actual=observation.actualSupport(measurements).orElseThrow();
                assertTrue(actual.identity().isPresent(),"only the directly queried qualified producer may mint identity");
                assertEquals(new BlockPos(8,68,8),actual.identity().orElseThrow().primaryContributorCell());
                assertTrue(tx.postprocessing().units()>before,
                        "the actual candidate, contributor, face and interval classification must debit the owner ledger");
                assertFalse(tx.postprocessing().terminal());
            }
        }
    }
    @Test void multipleQualifiedCandidatesAccumulateInTheSameOwnerTickAndWorkLimitRejects() {
        try(var f=BootWorldFixture.open(temp.resolve("qualified-multi-candidate"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.25,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(7,68,8),Blocks.IRON_BLOCK.getDefaultState());
            put(f,new BlockPos(9,68,8),Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            try(var tx=i.tx) {
                var sampled=sample(i,false);
                var observation=sampled.observation();
                var measurements=observation.boots().measurements().orElseThrow();
                assertTrue(measurements.candidates().size()>=2,()->"expected two real magnetic fragments: "+measurements.candidates());
                assertTrue(measurements.candidates().stream().allMatch(candidate ->
                                candidate.proximity().gap().lowerMetres()!=0.0
                                        || candidate.proximity().gap().upperMetres()!=0.0),
                        "the separated side producers must not become actual foot contacts");
                long before=tx.postprocessing().units();
                assertTrue(observation.actualSupport(measurements).isEmpty(),
                        "two separated producer boxes cannot become a Free foot support");
                long charged=tx.postprocessing().units()-before;
                assertTrue(charged>=measurements.candidates().size(),
                        "an empty result proves every candidate was visited, with cumulative per-candidate charges");
                assertFalse(tx.postprocessing().terminal());

                long beforeAdapter=tx.postprocessing().units();
                var evidence=BootStandingEvidence.withBootQueryAndSupport(sampled.fields(),measurements,
                        Optional.empty(),tx.accepted(),tx.tick(),true,tx.postprocessing()::charge);
                assertEquals(measurements.candidates().size(),evidence.bootCandidates().size());
                assertTrue(tx.postprocessing().units()-beforeAdapter>measurements.candidates().size(),
                        "the Free adapter must charge candidate membership and contributor identity scans");
                assertFalse(tx.postprocessing().terminal());
            }
        }
        try(var f=BootWorldFixture.open(temp.resolve("qualified-work-exhaustion"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.25,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(7,68,8),Blocks.IRON_BLOCK.getDefaultState());
            put(f,new BlockPos(9,68,8),Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            try(var tx=i.tx) {
                var sampled=sample(i,false);
                var measurements=sampled.observation().boots().measurements().orElseThrow();
                assertTrue(measurements.candidates().size()>=2);
                tx.postprocessing().charge(FreePostprocessingWork.MAX_UNITS-tx.postprocessing().units()-2);
                var exhausted=assertThrows(FreePostprocessingWork.Exhausted.class,
                        ()->BootStandingEvidence.withBootQueryAndSupport(sampled.fields(),measurements,
                                Optional.empty(),tx.accepted(),tx.tick(),true,tx.postprocessing()::charge));
                assertTrue(exhausted.getMessage().contains("limit="),exhausted.getMessage());
                assertTrue(tx.postprocessing().terminal());
                assertTrue(tx.postprocessing().units()<=FreePostprocessingWork.MAX_UNITS);
                assertThrows(FreePostprocessingWork.Exhausted.class,()->tx.postprocessing().charge(1),
                        "a spent owner-tick work cap cannot reset for another candidate or fallback");
            }
        }
    }
    @Test void sameTickIronToStoneReplacementWithEqualBoxesInvalidatesFreeBootRecapture() {
        try(var f=BootWorldFixture.open(temp.resolve("same-box-material-replacement"),PLAYER,false)) {
            var rules=new ActiveBootRules(ActiveBootRules.core().snapshot());
            var i=input(f,new Vec3d(8.5,69.375,8.5),Rotation3d.identity(),.5,.5,null,rules);
            var producer=new BlockPos(8,68,8);
            put(f,producer,Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            try(var tx=i.tx) {
                var observation=sample(i,false).observation();
                var before=WorldCollisionCandidates.capture(tx,observation.support().foot().window(),
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        new CollisionQueryFootprint.Limits(16_384,16,4_096,4_096));
                var acceptedBefore=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,before);
                put(f,producer,Blocks.STONE.getDefaultState());
                var after=WorldCollisionCandidates.capture(tx,observation.support().foot().window(),
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        new CollisionQueryFootprint.Limits(16_384,16,4_096,4_096));
                var acceptedAfter=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,after);
                assertEquals(acceptedBefore.boxes(), acceptedAfter.boxes(),
                        "the generic collision AABBs remain exactly equal across iron-to-stone replacement");
                assertFalse(acceptedBefore.sameObservation(acceptedAfter),
                        "the full observation must retain the changed actual block producer despite equal geometry");
                assertFalse(observation.recaptureMatches(tx),
                        "qualified producer/material recapture must invalidate the same-tick stale boot source");
            }
        }
    }
    @Test void actualQualifiedBootUsesBoundSharedSensorAndEquipmentFreshness() {
        try(var f=BootWorldFixture.open(temp.resolve("boots"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.375,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(8,68,8),Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            try(var tx=i.tx) {
                var o=sample(i,false).observation();var m=o.boots().measurements().orElseThrow();
                assertTrue(m.source().hasArithmetic());assertSame(tx.accepted(),m.source().physical());assertFalse(m.candidates().isEmpty());
                assertTrue(m.numericAfter().orElseThrow().units()>m.numericBefore().orElseThrow().units());
                tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,ItemStack.EMPTY);
                assertThrows(IllegalStateException.class,()->o.matches(tx));
            }
        }
    }
    @Test void actualImmersionSuppliesBuoyancyWithoutNativeFlowOrMotionConsumption() {
        try(var f=BootWorldFixture.open(temp.resolve("water"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,68.5,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(8,68,8),Blocks.WATER.getDefaultState());
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE,new BodyPhysics(100,.1));
                var field=GravityFields.get().registerContribution("fixture","free-water",(p,t)->new Vec3(0,-8,0));var tx=i.tx) {
                i.binder.setForce(tx.actor(),new Vec3(200,0,0));assertFalse(tx.actor().isInWater());
                tx.actor().motionX=3;tx.actor().motionY=4;tx.actor().motionZ=5;
                var s=sample(i,false);assertTrue(s.observation().environment().inWater());
                assertEquals(new Vec3(2,-1.04,0),s.environmentAcceleration());
                assertFalse(tx.actor().isInWater());assertEquals(3,tx.actor().motionX);assertEquals(4,tx.actor().motionY);assertEquals(5,tx.actor().motionZ);
                assertTrue(tx.resources().claimWaterForce(),"pure sampler must leave canonical flow claim untouched");
            }
        }
    }
    @Test void bindingAndPersonalGKeepActualForcesAndUnknownDoesNotInventOff() {
        try(var f=BootWorldFixture.open(temp.resolve("g"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5);
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx;
                var field=GravityFields.get().registerContribution("fixture","free-g",(p,t)->new Vec3(0,-8,0))) {
                assertEquals(PlayerEnvironmentSampler.Reason.PERSONAL_G_UNKNOWN,assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,
                        i.sampler.sampleFree(tx,Optional.empty(),LIMITS)).reason());
                assertEquals(Vec3.ZERO,sample(i,true).environmentAcceleration());assertEquals(new Vec3(0,-8,0),sample(i,false).environmentAcceleration());
            }
        }
    }
    @Test void realCallbackCauseAndNativeGeometryMutationFailAtSamplerBoundary() {
        for(boolean throwing:new boolean[]{true,false}) {
            try(var f=BootWorldFixture.open(temp.resolve("callback"+throwing),PLAYER,false)) {
                var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5);var cause=new IllegalStateException("fixture original field cause");
                try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx;
                    var field=GravityFields.get().registerContribution("fixture","free-callback",(p,t)->{
                        if(throwing)throw cause;tx.actor().width=.75f;return Vec3.ZERO;})) {
                    var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,i.sampler.sampleFree(tx,Optional.of(false),LIMITS));
                    assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED,failure.reason());assertTrue(failure.cause().isPresent());
                    if(throwing)assertSame(cause,failure.cause().orElseThrow());
                }
            }
        }
    }
    @Test void realFieldCallbackFeetMutationIsRejectedAsBootContextFailure() {
        try(var f=BootWorldFixture.open(temp.resolve("mutation-feet"),PLAYER,false)) {
            var rules=new ActiveBootRules(ActiveBootRules.core().snapshot());
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5,null,rules);
            var callbackEntered=new java.util.concurrent.atomic.AtomicBoolean();
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx;
                var field=GravityFields.get().registerContribution("fixture","sampler-mutation-feet",(p,t)->{
                    callbackEntered.set(true);
                    tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
                    return new Vec3(0,-8,0);
                })) {
                var result=i.sampler.sampleFree(tx,Optional.of(false),LIMITS);
                assertTrue(callbackEntered.get(),"field callback must execute");
                var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,result);
                assertEquals(PlayerEnvironmentSampler.Reason.ACTIVE_BOOT_UNAVAILABLE,failure.reason());
                var bootFailure=assertInstanceOf(FreeBootObservation.Failed.class,failure.evidence());
                assertEquals("context",bootFailure.stage());
                assertTrue(bootFailure.cause().isEmpty(),"a nonthrowing state mutation is a typed rejection, not an exception");
                assertTrue(failure.cause().isEmpty());
                assertEquals(Items.IRON_BOOTS,tx.actor().getItemStackFromSlot(EntityEquipmentSlot.FEET).getItem());
            }
        }
    }
    @Test void realFieldCallbackBootRulesRevisionMutationIsRejectedAgainstOperationStart() {
        try(var f=BootWorldFixture.open(temp.resolve("mutation-rules"),PLAYER,false)) {
            var rules=new ActiveBootRules(ActiveBootRules.core().snapshot());
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5,null,rules);
            var baselineRules=rules.snapshot();
            var callbackEntered=new java.util.concurrent.atomic.AtomicBoolean();
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx;
                var field=GravityFields.get().registerContribution("fixture","sampler-mutation-rules",(p,t)->{
                    callbackEntered.set(true);
                    rules.install(nextRulesRevision(rules.snapshot()));
                    return new Vec3(0,-8,0);
                })) {
                var result=i.sampler.sampleFree(tx,Optional.of(false),LIMITS);
                assertTrue(callbackEntered.get(),"field callback must execute");
                var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,result,
                        "rules mutation during the field callback must not become the sampler baseline");
                assertEquals(PlayerEnvironmentSampler.Reason.ACTIVE_BOOT_UNAVAILABLE,failure.reason());
                var bootFailure=assertInstanceOf(FreeBootObservation.Failed.class,failure.evidence());
                assertEquals("context",bootFailure.stage());
                assertTrue(bootFailure.cause().isEmpty());
                assertTrue(failure.cause().isEmpty());
                assertEquals(Math.incrementExact(baselineRules.revision()),rules.snapshot().revision());
            }
        }
    }

    @Test void realBinderTickFilterBootRulesRevisionMutationIsRejectedAgainstSamplerEntry() {
        try(var f=BootWorldFixture.open(temp.resolve("mutation-binder-rules"),PLAYER,false)) {
            var rules=new ActiveBootRules(ActiveBootRules.core().snapshot());
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5,null,rules);
            var actor=i.tx.actor();
            assertTrue(f.world().spawnEntity(actor),"fixture actor must enter the real server world's loaded entity list");
            var baselineRules=rules.snapshot();
            var callbackCount=new AtomicInteger();
            try(var registration=i.binder.bindPredicate(entity -> entity == actor,GravityMode.REPLACE,entity -> {
                assertSame(actor,entity,"only the fixture actor should reach this registration");
                callbackCount.incrementAndGet();
                rules.install(nextRulesRevision(rules.snapshot()));
                return true;
            },new net.minecraft.world.WorldServer[]{f.world()});var tx=i.tx) {
                assertTrue(i.binder.isBound(actor),"bindPredicate must automatically bind the loaded real actor");
                var result=i.sampler.sampleFree(tx,Optional.of(false),LIMITS);
                assertEquals(1,callbackCount.get(),"sampleFree must execute the real binder tickFilter once");
                assertEquals(Math.incrementExact(baselineRules.revision()),rules.snapshot().revision(),
                        "tickFilter must actually install a newer rules snapshot");
                var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,result,
                        "a rules mutation before the sampler captures its floor decision must reject the operation");
                assertEquals(PlayerEnvironmentSampler.Reason.ACTIVE_BOOT_UNAVAILABLE,failure.reason());
                var bootFailure=assertInstanceOf(FreeBootObservation.Failed.class,failure.evidence());
                assertEquals("context",bootFailure.stage());
                assertTrue(bootFailure.cause().isEmpty(),"nonthrowing revision mutation is a typed freshness rejection");
                assertTrue(failure.cause().isEmpty(),"no exception was thrown by the mutation callback");
            }
        }
    }
    @Test void realFieldCallbackPhysicsPolicyMutationIsRejectedWithBoundaryEvidence() {
        double priorWaterBias=UnigravConfig.waterSinkBias;
        try {
            try(var f=BootWorldFixture.open(temp.resolve("mutation-policy"),PLAYER,false)) {
                var rules=new ActiveBootRules(ActiveBootRules.core().snapshot());
                var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5,null,rules);
                var callbackEntered=new java.util.concurrent.atomic.AtomicBoolean();
                try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx;
                    var field=GravityFields.get().registerContribution("fixture","sampler-mutation-policy",(p,t)->{
                        callbackEntered.set(true);
                        UnigravConfig.waterSinkBias=Math.nextUp(priorWaterBias);
                        return new Vec3(0,-8,0);
                    })) {
                    var result=i.sampler.sampleFree(tx,Optional.of(false),LIMITS);
                    assertTrue(callbackEntered.get(),"field callback must execute");
                    var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,result);
                    assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED,failure.reason());
                    var cause=assertInstanceOf(IllegalStateException.class,failure.cause().orElseThrow());
                    assertTrue(cause.getMessage().contains("policy changed"),cause.getMessage());
                    assertEquals(Math.nextUp(priorWaterBias),UnigravConfig.waterSinkBias);
                }
            }
        } finally {
            UnigravConfig.waterSinkBias=priorWaterBias;
        }
    }
    @Test void sameTickActualCollisionRecaptureDetectsChangedWorld() {
        try(var f=BootWorldFixture.open(temp.resolve("recapture"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.375,8.5),Rotation3d.identity(),.5,.5);
            try(var tx=i.tx) {
                var o=sample(i,false).observation();put(f,new BlockPos(8,68,8),Blocks.STONE.getDefaultState());
                assertFalse(o.recaptureMatches(tx));
            }
        }
    }
    @Test void sharedPartialNumericExhaustionPreservesOriginalCauseAndSpent() {
        try(var f=BootWorldFixture.open(temp.resolve("numeric"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5);
            try(var tx=i.tx) {
                tx.numeric().charge(StoredDyadic.MAX_WORK_UNITS-tx.numeric().units());
                var failure=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,i.sampler.sampleFree(tx,Optional.of(false),LIMITS));
                assertInstanceOf(NumericWorkExhaustedException.class,failure.cause().orElseThrow());
                assertEquals(StoredDyadic.MAX_WORK_UNITS,tx.numeric().units());
            }
        }
    }
    @Test void actualSamplerWaterQueryExhaustionRetainsEvidenceAndSameStoreTerminalState() {
        assertSamplerFluidQueryExhaustion("water",Blocks.WATER.getDefaultState());
    }
    @Test void actualSamplerLavaQueryExhaustionRetainsEvidenceAndSameStoreTerminalState() {
        assertSamplerFluidQueryExhaustion("lava",Blocks.LAVA.getDefaultState());
    }
    private void assertSamplerFluidQueryExhaustion(String fixtureName,net.minecraft.block.state.IBlockState fluid) {
        try(var f=BootWorldFixture.open(temp.resolve("query-budget-"+fixtureName),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,68.5,8.5),Rotation3d.identity(),.5,.5);
            var waterPos=new BlockPos(8,68,8);
            put(f,waterPos,fluid);
            try(var tx=i.tx) {
                var queries=tx.queries();
                var pose=CapsulePlacementMath.pose(tx.accepted().placement(),tx.numeric());
                var voxel=new Aabb(new Vec3d(waterPos.getX(),waterPos.getY(),waterPos.getZ()),
                        new Vec3d(waterPos.getX()+1,waterPos.getY()+1,waterPos.getZ()+1));
                var voxelProbe=CapsuleAabbContact3d.query(pose.capsule(),voxel,FreeOwnerTransaction.ACCURACY,
                        QUERY_POOL_LIMITS);
                assertEquals(CapsuleAabbContact3d.Relation.OVERLAPPING,voxelProbe.relation(),voxelProbe::toString);
                double liquidHeight=BlockLiquid.getBlockLiquidHeight(fluid,f.world(),waterPos);
                var fluidVolume=new Aabb(voxel.min(),new Vec3d(waterPos.getX()+1,waterPos.getY()+liquidHeight,
                        waterPos.getZ()+1));
                var fluidProbe=CapsuleAabbContact3d.query(pose.capsule(),fluidVolume,
                        FreeOwnerTransaction.ACCURACY,QUERY_POOL_LIMITS);
                assertEquals(CapsuleAabbContact3d.Relation.OVERLAPPING,fluidProbe.relation(),fluidProbe::toString);

                // Measure genuine separated CHB query costs, then plan the same exact-op total on this owner's pool.
                var separatedBoxes=List.of(
                        new Aabb(new Vec3d(9,68,8),new Vec3d(10,69,9)),
                        new Aabb(new Vec3d(9,68.5,8.5),new Vec3d(9,68.5,8.5)),
                        new Aabb(new Vec3d(9,68.5,8.5),new Vec3d(10,68.5,8.5)),
                        new Aabb(new Vec3d(9,68,8.5),new Vec3d(9,69,8.5)),
                        new Aabb(new Vec3d(9,68,8),new Vec3d(9,69,9)),
                        new Aabb(new Vec3d(9.0625,68.5,8.5),new Vec3d(9.0625,68.5,8.5)),
                        new Aabb(new Vec3d(9.125,68.5,8.5),new Vec3d(9.125,68.5,8.5)),
                        new Aabb(new Vec3d(9.25,68.5,8.5),new Vec3d(9.25,68.5,8.5)),
                        new Aabb(new Vec3d(9.5,68.5,8.5),new Vec3d(9.5,68.5,8.5)),
                        new Aabb(new Vec3d(9,68.125,8.5),new Vec3d(9.5,68.625,8.5)),
                        new Aabb(new Vec3d(9,68,8),new Vec3d(10,69,9)),
                        new Aabb(new Vec3d(9,68.25,8.25),new Vec3d(10,68.75,8.75)),
                        new Aabb(new Vec3d(9,67.5,8),new Vec3d(10,68.5,9)),
                        new Aabb(new Vec3d(9,69,8),new Vec3d(10,70,9)),
                        new Aabb(new Vec3d(9,68.5,8),new Vec3d(10,68.5,9)),
                        new Aabb(new Vec3d(9,68,8.5),new Vec3d(10,69,8.5)));
                var probes=new ArrayList<QueryProbe>();
                for(var obstacle:separatedBoxes) {
                    var measured=CapsuleAabbContact3d.query(pose.capsule(),obstacle,FreeOwnerTransaction.ACCURACY,
                            QUERY_POOL_LIMITS);
                    assertEquals(CapsuleAabbContact3d.Relation.SEPARATED,measured.relation(),measured::toString);
                    probes.add(new QueryProbe(obstacle,measured.work()));
                }
                int exactOpsNeededBySamplerVoxel=voxelProbe.work().exactOps();
                int preconsumeExactOps=QUERY_POOL_LIMITS.maxExactOps()-exactOpsNeededBySamplerVoxel;
                var plan=exactWorkPlan(probes,preconsumeExactOps);
                assertFalse(plan.isEmpty());
                assertTrue(plan.size()<QUERY_POOL_LIMITS.maxStaticEvaluations(),
                        "measured exact-work plan must leave the static-evaluation budget available");
                for(var probe:plan) {
                    var contact=queries.query(pose.capsule(),probe.obstacle(),FreeOwnerTransaction.ACCURACY);
                    assertEquals(CapsuleAabbContact3d.Relation.SEPARATED,contact.relation(),contact::toString);
                    assertEquals(probe.work(),contact.work(),"same real geometry must produce the measured CHB work");
                    assertFalse(queries.terminal(),"preconsumption must leave the sampler query itself available: "+queries.work());
                }
                assertEquals(preconsumeExactOps,queries.work().exactOps());
                assertEquals(plan.size(),queries.work().staticEvaluations());
                assertFalse(queries.terminal());
                long beforeCalls=queries.calls();var beforeWork=queries.work();
                var first=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,
                        i.sampler.sampleFree(tx,Optional.of(false),LIMITS));
                assertEquals(PlayerEnvironmentSampler.Reason.WORLD_UNAVAILABLE,first.reason());
                var evidence=assertInstanceOf(CapsuleEnvironmentObservation.Unavailable.class,first.evidence());
                assertEquals("actual capsule trigger query budget exhausted",evidence.reason());
                var original=assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class,first.cause().orElseThrow());
                assertSame(original,evidence.cause().orElseThrow());
                assertEquals(beforeCalls+2,queries.calls(),"voxel CHB query succeeds; fluid-volume query is denied by the same pool");
                assertEquals(QUERY_POOL_LIMITS.maxExactOps(),queries.work().exactOps(),
                        "sampler voxel contact must complete and exactly consume the remaining measured exact work");
                assertEquals(beforeWork.staticEvaluations()+voxelProbe.work().staticEvaluations(),
                        queries.work().staticEvaluations());
                assertTrue(queries.terminal());
                var spent=queries.work();
                assertTrue(spent.staticEvaluations()>beforeWork.staticEvaluations());
                assertTrue(original.getMessage().contains("after calls="+queries.calls()),original.getMessage());

                var reacquired=i.store.acquire(i.travel,tx.world(),tx.accepted().scope(),i.travel.correctionRevision());
                assertSame(tx.resources(),reacquired,"same owner/tick/scope must reuse the depleted store");
                assertSame(queries,reacquired.queries(),"a failed sample must not receive fresh query credit");
                long spentCalls=queries.calls();
                var retry=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,
                        i.sampler.sampleFree(tx,Optional.of(false),LIMITS));
                assertEquals(PlayerEnvironmentSampler.Reason.WORLD_UNAVAILABLE,retry.reason());
                var retryEvidence=assertInstanceOf(CapsuleEnvironmentObservation.Unavailable.class,retry.evidence());
                var retryCause=assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class,retry.cause().orElseThrow());
                assertSame(retryCause,retryEvidence.cause().orElseThrow());
                assertTrue(retryCause.getMessage().contains("after calls="+queries.calls()),retryCause.getMessage());
                assertEquals(spentCalls+1,queries.calls());
                assertSame(spent,queries.work(),"terminal denial cannot refund or replay work");
                assertTrue(queries.terminal());
            }
        }
    }
    @Test void sameTickNewTransactionKeepsTerminalPostprocessingForRealAdapter() {
        try(var f=BootWorldFixture.open(temp.resolve("same-tick-iron-postprocessing"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,69.25,8.5),Rotation3d.identity(),.5,.5);
            put(f,new BlockPos(8,68,8),Blocks.IRON_BLOCK.getDefaultState());
            i.tx.actor().setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            long worldTick=f.world().getTotalWorldTime();
            long ownerTick=i.tx.tick();
            var originalAccepted=i.tx.accepted();
            var originalGeometry=WorldCollisionCandidates.ActorGeometry.read(i.tx.actor());
            var originalLedger=i.ledger.snapshot();
            var originalPostprocessing=i.tx.postprocessing();
            long spent;
            try(var tx=i.tx) {
                var sampled=sample(i,false);
                var measurements=sampled.observation().boots().measurements().orElseThrow();
                assertTrue(measurements.candidates().stream()
                        .anyMatch(candidate->candidate.proximity().gap().lowerMetres()==0.0
                                && candidate.proximity().gap().upperMetres()==0.0),
                        "the adapter must receive a real same-tick iron-boot producer measurement");
                var work=tx.postprocessing();
                assertSame(originalPostprocessing,work);
                work.charge(FreePostprocessingWork.MAX_UNITS-work.units()-2);
                var failure=assertThrows(FreePostprocessingWork.Exhausted.class,
                        ()->BootStandingEvidence.withBootQueryAndSupport(sampled.fields(),measurements,
                                Optional.empty(),tx.accepted(),tx.tick(),true,work::charge));
                assertTrue(failure.getMessage().contains("limit="+FreePostprocessingWork.MAX_UNITS));
                assertTrue(work.terminal());
                spent=work.units();
            }

            var reacquired=i.store.acquire(i.travel,f.world(),i.tx.accepted().scope(),
                    i.travel.correctionRevision());
            assertSame(i.tx.resources(),reacquired,
                    "a new transaction in the same owner tick must reuse the original resource store");
            try(var next=new FreeOwnerTransaction(i.tx.actor(),i.tx.accepted(),i.travel,i.ledger,()->true,
                    i.travel,reacquired)) {
                assertEquals(worldTick,f.world().getTotalWorldTime(),"no world tick may advance between owner transactions");
                assertEquals(ownerTick,next.tick(),"the second transaction must remain in the same actual owner tick");
                assertSame(originalAccepted,next.accepted(),"accepted authority must remain the original snapshot");
                assertEquals(originalGeometry,WorldCollisionCandidates.ActorGeometry.read(next.actor()),
                        "classification exhaustion must not move or resize the actor");
                assertSame(originalPostprocessing,next.postprocessing(),
                        "a new transaction must retain the exact original postprocessing ledger object");
                assertTrue(next.postprocessing().terminal());
                assertEquals(spent,next.postprocessing().units());
                var unavailable=assertInstanceOf(PlayerEnvironmentSampler.FreeUnavailable.class,
                        i.sampler.sampleFree(next,Optional.of(false),LIMITS));
                assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED,unavailable.reason());
                assertInstanceOf(FreePostprocessingWork.Exhausted.class,unavailable.cause().orElseThrow(),
                        "the next real observation must reject the retained terminal owner ledger before any producer work");
                assertEquals(spent,next.postprocessing().units());
                assertTrue(next.postprocessing().terminal());
                assertSame(originalAccepted,next.accepted());
                assertEquals(originalGeometry,WorldCollisionCandidates.ActorGeometry.read(next.actor()));
            }
            assertEquals(originalLedger,i.ledger.snapshot(),"postprocessing failure and abort cannot reset or charge the movement ledger");
            assertEquals(worldTick,f.world().getTotalWorldTime());
        }
    }

    private static List<QueryProbe> exactWorkPlan(List<QueryProbe> probes,int exactWorkTarget) {
        int[] previous=new int[exactWorkTarget+1];Arrays.fill(previous,-1);
        int[] selected=new int[exactWorkTarget+1];previous[0]=0;
        for(int spent=0;spent<=exactWorkTarget;spent++) {
            if(previous[spent]<0)continue;
            for(int index=0;index<probes.size();index++) {
                int next=spent+probes.get(index).work().exactOps();
                if(next<=exactWorkTarget&&previous[next]<0) {previous[next]=spent;selected[next]=index;}
            }
        }
        assertTrue(previous[exactWorkTarget]>=0,"measured separated CHB workloads must exactly reserve one sampler voxel query");
        var result=new ArrayList<QueryProbe>();
        for(int spent=exactWorkTarget;spent>0;spent=previous[spent])result.add(probes.get(selected[spent]));
        return List.copyOf(result);
    }
    @Test void freePreAndRecaptureChargeTheSameOwnerFrameAndRejectRetiredCapability() {
        try(var f=BootWorldFixture.open(temp.resolve("owner-cap"),PLAYER,false)) {
            var i=input(f,new Vec3d(8.5,70,8.5),Rotation3d.identity(),.5,.5);
            try(var binding=i.binder.bind(i.tx.actor(),GravityMode.REPLACE);var tx=i.tx) {
                var work=tx.resources().environmentObservationWork();var before=work.usage();
                var observed=sample(i,false);var afterPre=work.usage();
                assertTrue(afterPre.units()>before.units());assertTrue(afterPre.nativeAttempts()>before.nativeAttempts());
                assertTrue(observed.observation().recaptureMatches(tx));
                assertTrue(work.usage().units()>afterPre.units());assertTrue(work.usage().nativeAttempts()>afterPre.nativeAttempts());
                long spent=work.usage().units();
                assertSame(tx.resources(),i.store.acquire(i.travel,f.world(),tx.accepted().scope(),i.travel.correctionRevision()));
                assertEquals(spent,work.usage().units(),"same frame acquisition does not refund real observations");
                i.store.invalidate();
                assertThrows(IllegalStateException.class,observed.observation()::requireForceCurrent);
            }
        }
    }
}

