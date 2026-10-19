package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootQueryFootprint;
import wi.unigrav.core.floor.BootSurfaceCompiler;
import wi.unigrav.core.floor.BootSurfaceQuery;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.BootWorldSurfaces;
import wi.unigrav.core.floor.FloorFieldProfile;
import wi.unigrav.core.floor.MagneticBlockReader;
import wi.unigrav.core.floor.MagneticBlockRule;
import static org.junit.jupiter.api.Assertions.*;

class BootSharedQualifiedQueryTest {
    @TempDir Path temp;
    private static final UUID PLAYER=new UUID(0,4281);
    private static final BlockPos FLOOR=new BlockPos(8,68,8);
    private static final BootQueryFootprint.Limits FOOTPRINT=new BootQueryFootprint.Limits(8192,32768,64,512);
    private static final BootSurfaceCompiler.Limits COMPILER=new BootSurfaceCompiler.Limits(512,3072,96,65536,2_000_000,4096);
    private static final BootSurfaceQuery.Limits QUERY=new BootSurfaceQuery.Limits(4096,65536);
    private record Input(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                         StoredDyadic.Work work, BootQueryArithmetic arithmetic, BootSurfaceGeometry.Source source,
                         BootQueryFootprint footprint) { }
    private Input input(BootWorldFixture fixture, Vec3d center, PlayerBodyShape.Dimensions dimensions,
                        Rotation3d attitude, double reach) {
        var work=new StoredDyadic.Work();
        var placement=new PlayerBodyPlacement(center,attitude,new PlayerBodyShape.FreeCapsule(dimensions),work);
        var pose=CapsulePlacementMath.pose(placement,work);
        var actor=fixture.serverPlayer(PLAYER);
        actor.width=(float)dimensions.width(); actor.height=(float)dimensions.height();
        var transport=placement.transport(); actor.setPosition(transport.x(),transport.y(),transport.z());
        var bounds=placement.bounds(); actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),bounds.max().x(),bounds.max().y(),bounds.max().z()));
        var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),actor.dimension,1),7,placement,new Vec3d(0,0,0));
        var arithmetic=BootQueryArithmetic.fromSameWork(work);
        var source=BootSurfaceGeometry.fromAccepted(accepted,arithmetic);
        long before=work.units();
        var footprint=BootQueryFootprint.plan(source,reach,FOOTPRINT,accepted.placement().bounds());
        assertTrue(work.units()>before,"real footprint must bill source bounds on the existing ledger");
        return new Input(actor,accepted,pose,work,arithmetic,source,footprint);
    }
    private Input sphere(BootWorldFixture fixture, Vec3d center, double reach) {
        return input(fixture,center,new PlayerBodyShape.Dimensions(.5,.5,.25),Rotation3d.identity(),reach);
    }
    private void put(BootWorldFixture fixture, BlockPos position, net.minecraft.block.state.IBlockState state) {
        fixture.world().getChunkProvider().loadedChunks.get(ChunkPos.asLong(position.getX()>>4,position.getZ()>>4)).setBlockState(position,state);
    }
    private MagneticBlockReader.RuleSet rules(long revision) {
        return new MagneticBlockReader.RuleSet(revision,List.of(new MagneticBlockReader.RuleBinding("fixture:iron",
                new MagneticBlockRule("minecraft:iron_block",Map.of(),null))));
    }
    private BootWorldSurfaces.QualifiedSnapshot capture(BootWorldFixture fixture, Input input, long revision) {
        return assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                BootWorldSurfaces.captureQualified(fixture.world(),input.actor,input.footprint,rules(revision)));
    }
    private StandingConstraints.BootTier tier(double reach) {
        return new StandingConstraints.BootTier(new FloorFieldProfile(32,.5,3),reach,4);
    }
    private BootSurfaceQuery.Result query(Input input, BootWorldSurfaces.QualifiedSnapshot capture, double reach) {
        return BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(reach)),COMPILER,QUERY,input.arithmetic);
    }
    private void cost(String label, Input input) { System.out.println("SHARED_QUALIFIED_COST "+label+" "+input.arithmetic.usage()); }

    @Test void legacyBoundEmptyCaptureRejectsBeforeReachEarlyReturn() {
        try(var fixture=BootWorldFixture.open(temp.resolve("legacy-empty-reach"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25);
            var empty=capture(fixture,input,51);
            assertTrue(empty.snapshot().primitives().isEmpty());
            long before=input.work.units();
            var failure=assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluateQualified(
                    empty,Map.of("fixture:iron",tier(.5)),COMPILER,QUERY));
            assertEquals("bound boot source requires explicit shared query entry",failure.getMessage());
            assertEquals(before,input.work.units());
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH,
                    assertInstanceOf(BootSurfaceQuery.Unavailable.class,query(input,empty,.5)).reason());
        }
    }
    @Test void legacyBoundCaptureRejectsBeforeActualCompilerUnavailable() {
        try(var fixture=BootWorldFixture.open(temp.resolve("legacy-compile"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25);
            put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
            put(fixture,FLOOR.east(),Blocks.STONE.getDefaultState());
            var capture=capture(fixture,input,52);
            assertEquals(2,capture.snapshot().primitives().size());
            var tiny=new BootSurfaceCompiler.Limits(1,6,2,1,1,1);
            var qualifications=Map.of(capture.materialMatches().keySet().iterator().next(),
                    new BootSurfaceCompiler.Qualification("fixture:iron",tier(.25)));
            assertEquals(BootSurfaceCompiler.Reason.PRIMITIVE_LIMIT,
                    assertInstanceOf(BootSurfaceCompiler.Unavailable.class,BootSurfaceCompiler.compile(
                            capture.snapshot().primitives(),qualifications,tiny)).reason());
            long before=input.work.units();
            assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluate(capture.snapshot(),qualifications,tiny,QUERY));
            assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(.25)),tiny,QUERY));
            assertEquals(before,input.work.units());
            assertEquals(BootSurfaceQuery.Reason.COMPILATION,assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(.25)),tiny,QUERY,input.arithmetic)).reason());
            var surfaces=assertInstanceOf(BootSurfaceCompiler.SurfaceSet.class,
                    BootSurfaceCompiler.compile(capture.snapshot().primitives(),qualifications,COMPILER));
            assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluateGeometry(input.source,surfaces,QUERY));
        }
    }
    @Test void actualTiltedSourceUsesStoredLowerBallAndNativeFloatImageWithExactCapture() {
        try(var fixture=BootWorldFixture.open(temp.resolve("tilt"),PLAYER,false)) {
            double angle=.3;
            var input=input(fixture,new Vec3d(8.5,70,8.5),new PlayerBodyShape.Dimensions(.6,1.8,1.5),
                    new Rotation3d(0,0,Math.sin(angle/2),Math.cos(angle/2)),.25);
            put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
            var capture=capture(fixture,input,41);
            var result=assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,capture,.25));
            assertSame(input.accepted,result.source().physical()); assertSame(capture,result.qualifiedCapture().orElseThrow());
            assertSame(capture.snapshot(),result.capture().orElseThrow()); assertSame(input.arithmetic,result.arithmetic().orElseThrow());
            assertEquals(input.accepted.placement().storedCapsuleGeometry().negativeEndpoint(),input.source.anchorBounds().min());
            assertEquals(.3,input.source.radius()); assertEquals((float).6,input.actor.width); assertEquals((float)1.8,input.actor.height);
            assertNotEquals(input.accepted.placement().center(),input.source.anchorBounds().min());
            assertNotEquals(input.source.anchorBounds().min(),input.source.anchorBounds().min().subtract(input.accepted.placement().bodyUp().multiply(input.source.radius())));
            assertFalse(result.candidates().isEmpty());
            assertTrue(result.numericAfter().orElseThrow().units()>result.numericBefore().orElseThrow().units());
            assertTrue(result.numericBefore().orElseThrow().units()>input.pose.numericWorkDelta()); cost("tilt",input);
        }
    }
    @Test void realExactRangeAndNextUlpBeyondRemainDifferentCompleteResults() {
        for(double reach:new double[]{.125,Math.nextDown(.125)}) {
            try(var fixture=BootWorldFixture.open(temp.resolve("range"+reach),PLAYER,false)) {
                var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25); put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
                var result=assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,capture(fixture,input,42),reach));
                if(reach==.125) {
                    assertEquals(1,result.candidates().size()); assertEquals(new BootSurfaceGeometry.Gap(.125,.125),result.candidates().getFirst().proximity().gap());
                    assertEquals(new Vec3d(0,-1,0),result.candidates().getFirst().proximity().pullDirection());
                } else assertTrue(result.candidates().isEmpty()); cost("range-"+reach,input);
            }
        }
    }
    @Test void realFiniteEdgeGapAndTangentialPullAreSharedPhysicalNumbers() {
        try(var fixture=BootWorldFixture.open(temp.resolve("edge"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(7.5,70,8.5),2.25);
            put(fixture,new BlockPos(9,67,8),Blocks.IRON_BLOCK.getDefaultState());
            var result=assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,capture(fixture,input,43),2.25));
            assertEquals(2,result.candidates().size());
            for(var candidate:result.candidates()) {
                assertEquals(new BootSurfaceGeometry.Gap(2.25,2.25),candidate.proximity().gap());
                assertEquals(new Vec3d(.6,-.8,0),candidate.proximity().pullDirection());
            }
            cost("edge",input);
        }
    }
    @Test void actualOcclusionAndSideUncertaintyNeverLeakPartialMeasurements() {
        for(boolean occluded:new boolean[]{true,false}) {
            try(var fixture=BootWorldFixture.open(temp.resolve(occluded?"occlusion":"side"),PLAYER,false)) {
                var input=sphere(fixture,occluded?new Vec3d(8.5,71.25,8.5):new Vec3d(9.125,68.5,8.5),occluded?2:.25);
                put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
                if(occluded) put(fixture,new BlockPos(8,70,8),Blocks.STONE.getDefaultState());
                var result=assertInstanceOf(BootSurfaceQuery.Unavailable.class,query(input,capture(fixture,input,44),occluded?2:.25));
                assertEquals(occluded?BootSurfaceQuery.Reason.VISIBILITY_UNRESOLVED:BootSurfaceQuery.Reason.SIDE_UNRESOLVED,result.reason());
                cost(occluded?"occlusion":"side",input);
            }
        }
    }
    @Test void emptyDomainStillChecksAllProfilesAndObservedMaterialCannotLoseItsSelector() {
        try(var fixture=BootWorldFixture.open(temp.resolve("domain"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25);
            var empty=capture(fixture,input,45);
            assertTrue(assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,empty,.25)).candidates().isEmpty());
            var outside=assertInstanceOf(BootSurfaceQuery.Unavailable.class,query(input,empty,.5));
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH,outside.reason());
            put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState()); var observed=capture(fixture,input,45);
            assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluateQualified(observed,Map.of(),COMPILER,QUERY,input.arithmetic));
            cost("domain",input);
        }
    }
    @Test void changedRealRecaptureAndEquipmentDoNotTurnOldPureReceiptIntoLiveAuthority() {
        try(var fixture=BootWorldFixture.open(temp.resolve("freshness"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25); put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
            var before=capture(fixture,input,46);
            var original=assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,before,.25));
            input.actor.setItemStackToSlot(EntityEquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
            put(fixture,FLOOR,Blocks.STONE.getDefaultState()); var after=capture(fixture,input,47);
            assertNotSame(before,after); assertNotEquals(before.rulesRevision(),after.rulesRevision());
            assertNotEquals(before.materialMatches(),after.materialMatches());
            assertFalse(original.candidates().isEmpty()); assertSame(before,original.qualifiedCapture().orElseThrow());
            assertTrue(assertInstanceOf(BootSurfaceQuery.Measurements.class,query(input,after,.25)).candidates().isEmpty());
            cost("recapture",input);
        }
    }
    @Test void foreignCapabilityAndImplicitLegacyQueryCannotBypassExplicitSharedEntry() {
        try(var fixture=BootWorldFixture.open(temp.resolve("foreign"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25); put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
            var capture=capture(fixture,input,48); var foreign=BootQueryArithmetic.fromSameWork(input.work); long before=input.work.units();
            assertThrows(IllegalArgumentException.class,()->queryWith(input,capture,foreign));
            assertThrows(IllegalArgumentException.class,()->input.source.queryBounds(.25,foreign));
            assertEquals(before,input.work.units());
            assertThrows(IllegalArgumentException.class,()->BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(.25)),COMPILER,QUERY));
            cost("foreign",input);
        }
    }
    private BootSurfaceQuery.Result queryWith(Input input,BootWorldSurfaces.QualifiedSnapshot capture,BootQueryArithmetic arithmetic) {
        return BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(.25)),COMPILER,QUERY,arithmetic);
    }
    @Test void realSharedVisibilityBudgetRejectsCompleteQueryRatherThanPartialCandidates() {
        try(var fixture=BootWorldFixture.open(temp.resolve("visibility-budget"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25);
            put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState()); put(fixture,FLOOR.east(),Blocks.STONE.getDefaultState());
            var capture=capture(fixture,input,49); assertEquals(2,capture.snapshot().primitives().size());
            var result=assertInstanceOf(BootSurfaceQuery.Unavailable.class,BootSurfaceQuery.evaluateQualified(capture,Map.of("fixture:iron",tier(.25)),COMPILER,
                    new BootSurfaceQuery.Limits(6,1),input.arithmetic));
            assertEquals(BootSurfaceQuery.Reason.VISIBILITY_BUDGET,result.reason()); cost("visibility-budget",input);
        }
    }
    @Test void realDiagonalLowerBallPrefixAndIrrationalGapUseExactChargedSlabs() {
        for(boolean outside:new boolean[]{false,true}) {
            try(var fixture=BootWorldFixture.open(temp.resolve(outside?"prefix-outside":"prefix-inside"),PLAYER,false)) {
                var input=input(fixture,new Vec3d(8.625,70.375,9.75),new PlayerBodyShape.Dimensions(1.5,1.5,.75),Rotation3d.identity(),1.5);
                put(fixture,new BlockPos(10,68,8),Blocks.IRON_BLOCK.getDefaultState());
                put(fixture,outside?new BlockPos(9,69,9):new BlockPos(8,70,9),Blocks.STONE.getDefaultState());
                var capture=capture(fixture,input,51); assertEquals(2,capture.snapshot().primitives().size());
                var queried=query(input,capture,1.5);
                if(outside) assertEquals(BootSurfaceQuery.Reason.VISIBILITY_UNRESOLVED,
                        assertInstanceOf(BootSurfaceQuery.Unavailable.class,queried).reason());
                else {
                    var result=assertInstanceOf(BootSurfaceQuery.Measurements.class,queried);
                    assertEquals(3,result.candidates().size());
                    var gap=result.candidates().getFirst().proximity().gap(); assertTrue(gap.lowerMetres()<gap.upperMetres());
                    var a=input.arithmetic; var low=a.add(a.exact(gap.lowerMetres()),a.exact(.75));
                    var high=a.add(a.exact(gap.upperMetres()),a.exact(.75));
                    assertTrue(a.compare(a.multiply(low,low),a.exact(4.34375))<=0);
                    assertTrue(a.compare(a.multiply(high,high),a.exact(4.34375))>=0);
                    // Raw nonmagnetic cell is wholly before the lower-ball radial prefix along this witness.
                    // This query is not full-body fit or standing permission for that actual overlapping cell.
                }
                cost(outside?"prefix-outside":"prefix-inside",input);
            }
        }
    }
    @Test void resourceExhaustionRetainsConstructorPoseAndBoundsSpentAndOriginalCause() {
        try(var fixture=BootWorldFixture.open(temp.resolve("spent"),PLAYER,false)) {
            var input=sphere(fixture,new Vec3d(8.5,69.375,8.5),.25); put(fixture,FLOOR,Blocks.IRON_BLOCK.getDefaultState());
            var capture=capture(fixture,input,50); long spent=input.work.units(); assertTrue(spent>0);
            input.work.charge(StoredDyadic.MAX_WORK_UNITS-input.work.units()-1);
            var failure=assertThrows(NumericWorkExhaustedException.class,()->query(input,capture,.25));
            assertEquals(input.work.units(),failure.used()); assertTrue(failure.used()>=spent); assertTrue(failure.requested()>0);
            assertNull(failure.getCause()); assertThrows(NumericWorkExhaustedException.class,()->input.source.queryBounds(.25));
            cost("exhaustion",input);
        }
    }
}

