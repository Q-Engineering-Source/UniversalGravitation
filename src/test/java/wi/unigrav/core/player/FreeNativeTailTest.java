package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.IBlockAccess;
import javax.annotation.Nullable;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.EnumPacketDirection;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Ready.commit and TEST mapped native World/Block bridge; not production-Mixin/FML evidence. */
class FreeNativeTailTest {
    private GravityEntityBinder previousBinder;
    @BeforeEach void installUnboundNativeFixtureBinder() {
        previousBinder=UnigravMod.entityBinder;
        UnigravMod.entityBinder=new GravityEntityBinder();
    }
    @AfterEach void restoreNativeFixtureBinder() { UnigravMod.entityBinder=previousBinder; }

    @TempDir Path directory;
    private static final BlockPos FLOOR = new BlockPos(8, 68, 8);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);

    InstalledFreeEndpointTest.Scene scene(String name) throws Exception {
        var fixtures = new InstalledFreeEndpointTest(); fixtures.directory = directory;
        return fixtures.scene(false, name);
    }
    InstalledFreeEndpointTest.Scene oriented(String name, Rotation3d attitude, boolean sphere) throws Exception {
        return oriented(name, attitude, sphere, new Vec3d(0,0,0));
    }
    InstalledFreeEndpointTest.Scene oriented(String name, Rotation3d attitude, boolean sphere, Vec3d offset) throws Exception {
        return oriented(name, attitude, sphere, offset, actor -> { });
    }
    InstalledFreeEndpointTest.Scene oriented(String name, Rotation3d attitude, boolean sphere, Vec3d offset,
            java.util.function.Consumer<net.minecraft.entity.player.EntityPlayerMP> beforeGeometry) throws Exception {
        UUID id = UUID.randomUUID();
        var fixture = BootWorldFixture.open(directory.resolve(name), id, false);
        var actor = fixture.serverPlayer(id);
        beforeGeometry.accept(actor);
        var dimensions = new PlayerBodyShape.Dimensions(.5, sphere ? .5 : 1, .25);
        var up = attitude.basisY();
        var center = sphere ? new Vec3d(8.5, 69.25, 8.5)
                : new Vec3d(8.5, 68.5, 8.5).add(up.multiply(1));
        var placement = new PlayerBodyPlacement(center.add(offset), attitude, new PlayerBodyShape.FreeCapsule(dimensions));
        actor.width = .5F; actor.height = (float) dimensions.height(); actor.eyeHeight = .25F;
        var p = placement.transport(); var b = placement.bounds(); actor.setPosition(p.x(), p.y(), p.z());
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z()));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(id, UUID.randomUUID(), actor.dimension, 1),
                7, placement, new Vec3d(0, 0, 0));
        var travel = new PlayerPhysicsStep.TravelState(); var current = new AtomicBoolean(true);
        var ledger = new BoostMovementBudget(20, 0, new Vec3(p.x(), p.y(), p.z()));
        var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, current::get);
        return new InstalledFreeEndpointTest.Scene(fixture, tx, current, travel, ledger, placement, null);
    }
    void put(InstalledFreeEndpointTest.Scene scene, Block block) {
        scene.fixture().world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(FLOOR, block.getDefaultState());
    }
    CapsuleMovementResolver.Resolved resolve(InstalledFreeEndpointTest.Scene scene, Vec3d delta) {
        var tx = scene.tx();
        var pose = CapsulePlacementMath.pose(tx.accepted().placement(), tx.numeric());
        var tube = CapsulePlacementMath.translationTube(pose, delta, tx.numeric());
        assertTrue(tx.reserve(tx.nextLedgerSequence(), tx.tick(), tube.requestedDisplacementUpper()).accepted());
        var captured = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(tx.accepted(), tube),
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, tx.resources().environmentObservationWork()));
        return assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                CapsuleMovementResolver.resolveWithSlide(tx, tube, captured));
    }
    CapsuleMoveFinalizer.Ready prepare(InstalledFreeEndpointTest.Scene scene, Vec3d delta) {
        var actor = scene.tx().actor(); actor.motionX = delta.x(); actor.motionY = delta.y(); actor.motionZ = delta.z();
        return assertInstanceOf(CapsuleMoveFinalizer.Ready.class,
                CapsuleMoveFinalizer.prepare(scene.tx(), resolve(scene, delta)));
    }
    @Test void stationaryCertifiedSupportEstablishesGroundWithoutLandingOrGaitDistance() throws Exception {
        try (var scene = scene("stationary")) {
            var landed = new AtomicInteger(); var walked = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); super.onLanded(world, entity); }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity entity) { walked.incrementAndGet(); }
            });
            scene.tx().actor().onGround = false;
            var movement = resolve(scene, new Vec3d(0, 0, 0));
            assertTrue(movement.clipEvents().isEmpty());
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(scene.tx(), movement)).commit();
            assertTrue(scene.tx().actor().onGround);
            assertEquals(0, landed.get()); assertEquals(1, walked.get());
            assertEquals(0, scene.tx().actor().distanceWalkedModified);
        }
    }
    @Test void oldGroundIsClearedWhenActualFinalSupportIsGone() throws Exception {
        try (var scene = scene("unsupported")) {
            put(scene, Blocks.AIR); scene.tx().actor().onGround = true;
            prepare(scene, new Vec3d(0, 0, .125)).commit();
            assertFalse(scene.tx().actor().onGround);
            assertEquals(new Vec3d(0, 0, .125), new Vec3d(scene.tx().actor().motionX,
                    scene.tx().actor().motionY, scene.tx().actor().motionZ));
        }
    }
    @Test void actualTouchStartDownwardClipLandsOnceAndNativeStoneClearsNormal() throws Exception {
        try (var scene = scene("stone-touch-start")) {
            var landed = new AtomicInteger(); var walked = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) {
                    landed.incrementAndGet(); assertEquals(-.125, entity.motionY); super.onLanded(world, entity);
                }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity entity) { walked.incrementAndGet(); assertEquals(FLOOR, pos); }
            });
            var actor = scene.tx().actor(); actor.motionY = -.125;
            var movement = resolve(scene, new Vec3d(0, -.125, 0));
            assertFalse(movement.clipEvents().isEmpty(), "actual BLOCKED_AT_START must retain a real limiting event");
            assertEquals(0, movement.pathUpper());
            assertEquals(new Vec3d(0, 0, 0), FreeNativeTail.actualDelta(movement));
            var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(scene.tx(), movement));
            ready.commit();
            assertTrue(actor.onGround); assertEquals(1, landed.get()); assertEquals(1, walked.get());
            assertEquals(0, actor.motionY);
            assertThrows(IllegalStateException.class, ready::commit);
            assertEquals(1, landed.get(), "failed replay cannot dispatch a second landing");
        }
    }
    @Test void actualSlimeBounceAndSneakUseOriginalNativeMethodAndSurviveTail() throws Exception {
        for (boolean sneak : new boolean[] {false, true}) {
            var fixtures = new InstalledFreeEndpointTest(); fixtures.directory = directory;
            try (var scene = fixtures.scene(false, "slime-" + sneak, 10, actor -> actor.setSneaking(sneak))) {
                put(scene, Blocks.SLIME_BLOCK);
                var actor = scene.tx().actor();
                assertEquals(sneak, actor.isSneaking());
                assertEquals((double) actor.getEyeHeight(), scene.tx().accepted().placement().shape().dimensions().eyeHeight());
                prepare(scene, new Vec3d(0, -.125, 0)).commit();
                assertTrue(actor.onGround);
                assertEquals(sneak ? 0 : .125, actor.motionY, "post-tail response must not erase the actual slime callback");
            }
        }
    }
    @Test void originalLandingExceptionRetainsExactCauseAndInstalledCustodyAndStopsWalking() throws Exception {
        try (var scene = scene("landing-throw")) {
            var original = new IllegalStateException("actual native landing producer failure");
            var walked = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { throw original; }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity entity) { walked.incrementAndGet(); }
            });
            var ready = prepare(scene, new Vec3d(0, -.125, 0));
            var failure = assertThrows(RuntimeException.class, ready::commit);
            assertSame(original, failure);
            assertSame(original, scene.tx().originalFailure().orElseThrow());
            assertSame(scene.tx().installedJournal().orElseThrow(), ready.installation().orElseThrow());
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED, scene.tx().installationStage());
            assertEquals(0, walked.get());
        }
    }
    @Test void landingTeleportStopsOldTailAndDoesNotOverwriteSuccessorMotion() throws Exception {
        try (var scene = scene("landing-teleport")) {
            var walked = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) {
                    entity.setPosition(entity.posX + 4, entity.posY, entity.posZ);
                    entity.motionX = 7; entity.motionY = 8; entity.motionZ = 9;
                }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity entity) { walked.incrementAndGet(); }
            });
            var ready = prepare(scene, new Vec3d(0, -.125, 0));
            var failure = assertThrows(FreeNativeTail.Stop.class, ready::commit);
            var stopped = failure.result();
            assertFalse(stopped.nativeTransition());
            assertEquals(scene.tx().installationCustody(), stopped.custody());
            assertTrue(failure.belongsTo(scene.tx())); assertEquals(0, walked.get());
            assertEquals(new Vec3d(7, 8, 9), new Vec3d(scene.tx().actor().motionX, scene.tx().actor().motionY, scene.tx().actor().motionZ));
            assertTrue(scene.tx().installedJournal().isPresent());
        }
    }
    @Test void landingRetiresOwnerAndStopsBeforeWalkingWithoutRefund() throws Exception {
        try (var scene = scene("landing-retire")) {
            var walked = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { scene.current().set(false); }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity entity) { walked.incrementAndGet(); }
            });
            var ready = prepare(scene, new Vec3d(0, -.125, 0));
            long queries = scene.tx().queries().calls();
            var failure = assertThrows(FreeNativeTail.Stop.class, ready::commit);
            assertTrue(failure.belongsTo(scene.tx()));
            var stopped = failure.result();
            assertFalse(stopped.nativeTransition());
            assertEquals(scene.tx().installationCustody(), stopped.custody());
            assertEquals(0, walked.get()); assertTrue(scene.tx().queries().calls() >= queries);
            assertTrue(scene.tx().installedJournal().isPresent());
        }
    }
    @Test void tangentTranslationCountsOnlyActualDeltaAndDoesNotInventLanding() throws Exception {
        try (var scene = scene("tangent")) {
            var landed = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); }
            });
            prepare(scene, new Vec3d(0, 0, .125)).commit();
            assertTrue(scene.tx().actor().onGround); assertEquals(0, landed.get());
            assertEquals((float) (.125 * .6), scene.tx().actor().distanceWalkedModified);
            assertEquals(.125, scene.tx().actor().motionZ);
        }
    }
    @Test void sixAxisActualElongatedCapsulesLandThroughRealNativeLocalMotionHook() throws Exception {
        for (var frame : CardinalFrame.values()) {
            try (var scene = oriented("actual-six-axis-" + frame, frame.attitude(), false)) {
                var landed = new AtomicInteger();
                put(scene, new Block(Material.ROCK) {
                    @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); super.onLanded(world, entity); }
                });
                Vec3d delta = frame.toWorld(new Vec3d(0, -.125, 0));
                prepare(scene, delta).commit();
                assertTrue(scene.tx().actor().onGround, frame.toString()); assertEquals(1, landed.get(), frame.toString());
                var local = frame.toLocal(new Vec3d(scene.tx().actor().motionX, scene.tx().actor().motionY, scene.tx().actor().motionZ));
                assertEquals(0, local.y(), frame.toString());
            }
        }
    }
    @Test void negativeAxisContributionWithPositiveWholeUpRequestDoesNotLand() throws Exception {
        var q = new Rotation3d(Math.sqrt(.2), 0, 0, Math.sqrt(.8));
        try (var scene = oriented("oblique-whole-dot-counterexample", q, true)) {
            var landed = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); }
            });
            Vec3d request = new Vec3d(0, -.125, .375), up = scene.tx().accepted().placement().bodyUp();
            assertTrue(request.y() * up.y() < 0);
            assertTrue(request.x() * up.x() + request.y() * up.y() + request.z() * up.z() > 0);
            var actor = scene.tx().actor(); actor.motionY = request.y(); actor.motionZ = request.z();
            var movement = resolve(scene, request);
            assertFalse(movement.clipEvents().isEmpty(), "counterexample must actually clip an axis");
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(scene.tx(), movement)).commit();
            assertEquals(0, landed.get(), "a removed negative up contribution does not prove a downward request");
        }
    }

    @Test void strictSeparatedPrefixCannotBecomeLandingFromItsPlaneSeparator() throws Exception {
        try (var scene = oriented("separated-prefix", CardinalFrame.Y_POS.attitude(), true, new Vec3d(0,-4.75,0))) {
            var landed = new AtomicInteger();
            var cell = new BlockPos(8,63,8);
            var worldBox = new AxisAlignedBB(8,63,8,9,Math.nextDown(64.0),9);
            var producer = new Block(Material.ROCK) {
                @Override public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos,
                        AxisAlignedBB query, List<AxisAlignedBB> boxes, Entity entity, boolean actualState) {
                    // Retain the actual world-coordinate face. Adding a rounded local offset would erase its ulp.
                    if(query.intersects(worldBox))boxes.add(worldBox);
                }
                @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); }
            };
            scene.fixture().world().getChunkProvider().getLoadedChunk(0,0).setBlockState(cell,producer.getDefaultState());
            var tx=scene.tx();var actor=tx.actor();
            assertEquals(new Vec3d(8.5,64.5,8.5),tx.accepted().placement().center());
            assertEquals(new PlayerBodyShape.Dimensions(.5,.5,.25),tx.accepted().placement().shape().dimensions());
            actor.motionY=-.5;
            var movement=resolve(scene,new Vec3d(0,-.5,0));
            assertTrue(movement.belongsTo(tx));assertTrue(movement.fraction()>0 && movement.fraction()<1);
            assertEquals(1,movement.segments().size());
            var segment=movement.segments().getFirst();var capture=segment.capture();var tube=segment.tube();
            assertSame(tx.accepted().placement(),tube.start().placement());
            assertEquals(new Vec3d(0,-.5,0),tube.delta());assertSame(movement.end(),segment.end());
            var rawBox=new Aabb(new Vec3d(8,63,8),new Vec3d(9,worldBox.maxY,9));
            int ordinal=capture.boxes().indexOf(rawBox);assertTrue(ordinal>=0,"the actual producer box must be captured");
            var origin=capture.provenance().orElseThrow().entries().get(ordinal);
            assertEquals(NativeCollisionProvenance.Kind.BLOCK,origin.kind());assertEquals(cell,origin.cell());
            assertSame(producer,origin.state().getBlock());
            var limiting=movement.queries().stream().filter(query->query.ordinal()==ordinal && query.capture()==capture
                    && query.tube()==tube && query.limitsAt(movement.fraction())).findFirst().orElseThrow();
            assertSame(capture.boxes().get(ordinal),limiting.obstacle());
            var actual=movement.queries().stream().filter(query->query.ordinal()==ordinal && query.capture()==capture
                    && query.tube()==tube && query.actualFinal().isPresent()).findFirst().orElseThrow();
            assertEquals(Relation.SEPARATED,actual.actualFinal().orElseThrow().relation(),
                    "fixture must actually retain a separated safe prefix, not merely an arbitrary blocked result");
            var installed=actual.boundary().orElseThrow().installation().orElseThrow();
            assertEquals(movement.end().placement().center(),installed.storedCenter());
            assertEquals(Relation.SEPARATED,installed.storedContact().relation());
            var stationary=CapsulePlacementMath.translationTube(movement.end(),new Vec3d(0,0,0),tx.numeric());
            var plane=CapsuleTranslationPlaneProof.certify(stationary,actual.obstacle(),tx.numeric()).orElseThrow();
            assertEquals(1,plane.axis());assertEquals(1,plane.outwardSign());
            // Refinement may return an earlier certified prefix; require its actual exact positive gap, not a tightest endpoint.
            var physicalGap=new BigDecimal(movement.end().placement().center().y())
                    .subtract(new BigDecimal(movement.end().capsule().radius()))
                    .subtract(new BigDecimal(worldBox.maxY));
            assertTrue(physicalGap.signum()>0,"actual installed endpoint must remain strictly separated");
            long spent=tx.queries().calls();
            var ready=assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(tx,movement));
            ready.commit();
            assertEquals(0,landed.get());
            assertFalse(actor.onGround,"strict separated installed prefix cannot borrow its Y+ separator as landing");
            assertTrue(tx.installedJournal().isPresent());assertTrue(tx.queries().calls()>=spent);
            var endpoint=tx.installedFreeEndpoint();var window=CapsuleFootContacts.window(endpoint,0);
            var finalCapture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(tx,window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN,
                            tx.resources().environmentObservationWork()));
            var support=assertInstanceOf(CapsuleSupportObservation.Complete.class,
                    CapsuleSupportObservation.observe(endpoint,finalCapture,window,0,FreeOwnerTransaction.ACCURACY));
            assertTrue(support.supports().isEmpty(),"no actual TOUCHING qualified foot receipt exists at the separated endpoint");
            assertEquals(0,landed.get());assertFalse(actor.onGround);
        }
    }
    @Test void sameObstacleSideContactWithDownwardRequestDoesNotBorrowItsTopFace() throws Exception {
        try (var scene = oriented("same-box-side", CardinalFrame.Y_POS.attitude(), true, new Vec3d(-.75,-.5,0))) {
            var landed = new AtomicInteger();
            put(scene, new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { landed.incrementAndGet(); }
            });
            var request = new Vec3d(.125,-.125,0);
            var actor = scene.tx().actor(); actor.motionX = request.x(); actor.motionY = request.y();
            var movement = resolve(scene,request);
            assertTrue(movement.clipEvents().stream().anyMatch(event -> event.axis() == 0), "actual side clipping is required");
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(scene.tx(),movement)).commit();
            assertEquals(0,landed.get()); assertFalse(actor.onGround);
        }
    }
    @Test void unrelatedWallBoundaryCannotAuthorizeFloorLanding() throws Exception {
        try (var scene = oriented("unrelated-wall-boundary", CardinalFrame.Y_POS.attitude(), false, new Vec3d(.25,0,0))) {
            var floorLandings = new AtomicInteger(); var wallLandings = new AtomicInteger();
            put(scene,new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) { floorLandings.incrementAndGet(); }
            });
            scene.fixture().world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(9,69,8),
                    new Block(Material.ROCK) {
                        @Override public void onLanded(World world, Entity entity) { wallLandings.incrementAndGet(); }
                    }.getDefaultState());
            scene.tx().actor().motionX=.125;
            var movement=resolve(scene,new Vec3d(.125,0,0));
            assertTrue(movement.clipEvents().stream().anyMatch(event -> event.axis()==0));
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement)).commit();
            assertTrue(scene.tx().actor().onGround, "independent actual floor support may establish ground");
            assertEquals(0,floorLandings.get());assertEquals(0,wallLandings.get());
        }
    }

    @Test void actualCapsuleInsideTailRunsOnceAfterLandingAndPreservesCallbackMotion() throws Exception {
        try (var scene=scene("one-inside-tail")) {
            var inside=new AtomicInteger();
            var plant=new Block(Material.PLANTS) {
                @Override @Nullable public AxisAlignedBB getCollisionBoundingBox(IBlockState state,IBlockAccess world,BlockPos pos) {
                    return NULL_AABB;
                }
                @Override public void onEntityCollision(World world,BlockPos pos,IBlockState state,Entity entity) {
                    inside.incrementAndGet();entity.motionX=.75;entity.motionY=.875;entity.motionZ=1;
                }
            };
            scene.fixture().world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,69,8),plant.getDefaultState());
            prepare(scene,new Vec3d(0,-.125,0)).commit();
            assertEquals(1,inside.get(),"existing capsule tail must not run a second world/inside chain");
            var actor=scene.tx().actor();
            assertEquals(new Vec3d(.75,.875,1),new Vec3d(actor.motionX,actor.motionY,actor.motionZ));
        }
    }
    @Test void actualModePureArcAndResizeDoNotCreateLandingOrCountCorrectionAsGait() throws Exception {
        for (boolean resize : new boolean[] {false,true}) {
            try (var scene=scene("mode-no-translation-"+resize)) {
                var actor=scene.tx().actor();var landed=new AtomicInteger();
                put(scene,new Block(Material.ROCK) {
                    @Override public void onLanded(World world,Entity entity){landed.incrementAndGet();}
                });
                var channel=new EmbeddedChannel(actor.connection.netManager);
                PlayerPhysicsInboundConnections.onServerConnectedEvent(new FMLNetworkEvent.ServerConnectionFromClientEvent(actor.connection.netManager));
                try {
                    var connection=PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(actor.connection.netManager,
                            actor.connection,EnumPacketDirection.SERVERBOUND).orElseThrow();
                    var handle=PlayerPhysicsInboundConnections.INSTANCE.openScopeForOwner(connection,scene.tx().accepted().scope()).orElseThrow();
                    var q=new Rotation3d(0,Math.sin(.2),0,Math.cos(.2));
                    var request=new PlayerAttitudeRequest(4,scene.tx().accepted().scope(),1,0,scene.fixture().world(),connection,handle,0,q);
                    ModeTransitionPrepared.TrustedResizeObservation observation=null;
                    if(resize) {
                        actor.height=.75F;
                        observation=new ModeTransitionPrepared.TrustedResizeObservation(new PlayerBodyShape.Dimensions(.5,.75,.25),
                                WorldCollisionCandidates.ActorGeometry.read(actor));
                        actor.height=1F;
                    }
                    actor.motionX=.2;actor.motionY=-.125;actor.motionZ=.1;
                    var preparedResult=ModeTransitionPrepared.prepareFreeTick(scene.tx(),request,observation,new Vec3d(0,0,0),0);
                    var prepared=assertInstanceOf(ModeTransitionPrepared.Prepared.class,preparedResult,preparedResult::toString);
                    assertNull(prepared.movement());
                    var result=ModeTransitionFinalizer.install(prepared,0);
                    assertInstanceOf(ModeTransitionFinalizer.Applied.class,result,result::toString);
                    assertEquals(0,landed.get());assertEquals(0,actor.distanceWalkedModified);
                    assertEquals(new Vec3d(.2,-.125,.1),new Vec3d(actor.motionX,actor.motionY,actor.motionZ));
                } finally {
                    PlayerPhysicsInboundConnections.onServerDisconnectedEvent(new FMLNetworkEvent.ServerDisconnectionFromClientEvent(actor.connection.netManager));
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    @Test void equalReplacementColliderKeepsGeometryGroundWithoutInventingBlockCallbacks() throws Exception {
        try(var scene=scene("event-only-support")) {
            var landed=new AtomicInteger();var walked=new AtomicInteger();
            put(scene,new Block(Material.ROCK) {
                @Override public void onLanded(World world,Entity entity){landed.incrementAndGet();}
                @Override public void onEntityWalk(World world,BlockPos pos,Entity entity){walked.incrementAndGet();}
            });
            var listenerCalls=new AtomicInteger();var replacedBoxes=new AtomicInteger();
            var listener=new CollisionBoxesTestListener(event -> {
                    if(event.getEntity()!=scene.tx().actor())return;
                    listenerCalls.incrementAndGet();
                    var boxes=event.getCollisionBoxesList();
                    for(int n=0;n<boxes.size();n++) {
                        var old=boxes.get(n);
                        var replacement=new AxisAlignedBB(old.minX,old.minY,old.minZ,old.maxX,old.maxY,old.maxZ);
                        assertNotSame(old,replacement);boxes.set(n,replacement);replacedBoxes.incrementAndGet();
                    }
            });
            MinecraftForge.EVENT_BUS.register(listener);
            try {
                prepare(scene,new Vec3d(0,-.125,0)).commit();
                assertTrue(listenerCalls.get()>0,"real EventBus listener must execute inside World.getCollisionBoxes");
                assertTrue(replacedBoxes.get()>0,"the event must replace at least one actual collider identity");
                assertTrue(scene.tx().actor().onGround,"event-only geometric ground");assertEquals(0,landed.get(),"event-only landing count");assertEquals(0,walked.get(),"event-only walking count");
                assertEquals(0,scene.tx().actor().motionY,"geometry response still cancels blocked normal");
                var endpoint=scene.tx().installedFreeEndpoint();var window=CapsuleFootContacts.window(endpoint,0);
                var captured=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(scene.tx(),window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
                var complete=assertInstanceOf(CapsuleSupportObservation.Complete.class,
                        CapsuleSupportObservation.observe(endpoint,captured,window,0,FreeOwnerTransaction.ACCURACY));
                assertFalse(complete.supports().isEmpty());
                for(var support:complete.supports()) assertTrue(captured.blockProducer(endpoint,complete,support.ordinal()).isEmpty());
            } finally {MinecraftForge.EVENT_BUS.unregister(listener);}
        }
    }
    @Test void callbackChangingOnlyProducerStateStopsLaterWalkingAtFreshnessBoundary() throws Exception {
        try(var scene=scene("producer-changed-after-landing")) {
            var landed=new AtomicInteger();var walked=new AtomicInteger();
            put(scene,new Block(Material.ROCK) {
                @Override public void onLanded(World world,Entity entity){
                    landed.incrementAndGet();scene.fixture().world().getChunkProvider().getLoadedChunk(0,0)
                            .setBlockState(FLOOR,Blocks.DIAMOND_BLOCK.getDefaultState());
                }
                @Override public void onEntityWalk(World world,BlockPos pos,Entity entity){walked.incrementAndGet();}
            });
            var ready=prepare(scene,new Vec3d(0,-.125,0));
            var failure=assertThrows(IllegalStateException.class,ready::commit);
            assertTrue(failure.getMessage().contains("producer changed"),failure::toString);
            assertEquals(1,landed.get());assertEquals(0,walked.get());
            assertSame(failure,scene.tx().originalFailure().orElseThrow());
        }
    }

    @Test void realPostMovementArcOrResizeCannotReviveHistoricalFootLandingProof() throws Exception {
        for(boolean resize:new boolean[]{false,true}) {
            try(var scene=scene("post-movement-proof-change-"+resize)) {
                var tx=scene.tx();var pose=CapsulePlacementMath.pose(tx.accepted().placement(),tx.numeric());
                var window=new Aabb(new Vec3d(7,67,7),new Vec3d(10,72,10));
                var captured=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx,window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
                var source=CapsulePathSource.actualFree(tx.accepted(),pose,captured,window,FreeOwnerTransaction.ACCURACY,tx.queries(),tx.numeric());
                var tube=CapsulePlacementMath.translationTube(pose,new Vec3d(0,-.125,0),tx.numeric());
                assertTrue(tx.reserve(tx.nextLedgerSequence(),tx.tick(),.5).accepted());
                var movement=assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                        CapsuleMovementResolver.resolveContinuationWithSlide(tx,source,tube,captured,0));
                assertFalse(movement.clipEvents().isEmpty());
                var afterMovement=CapsulePathSource.continueAfter(movement,tx.numeric());
                CapsulePathSource finalSource;
                if(resize) {
                    var smaller=new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,.75,.25));
                    var target=new PlayerBodyPlacement(movement.end().placement().center().add(new Vec3d(0,-.125,0)),
                            movement.end().placement().attitude(),smaller);
                    var result=CapsuleResizeValidator.validate(afterMovement,target);
                    finalSource=CapsulePathSource.continueAfter(assertInstanceOf(CapsuleResizeValidator.VerifiedResize.class,result,result::toString),tx.numeric());
                } else {
                    var q=new Rotation3d(0,Math.sin(.2),0,Math.cos(.2));
                    var target=new PlayerBodyPlacement(movement.end().placement().center(),q,movement.end().placement().shape());
                    var result=CapsuleArcValidator.validate(afterMovement,target,new Vec3d(0,1,0));
                    finalSource=CapsulePathSource.continueAfter(assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,result),tx.numeric());
                }
                var certified=CertifiedReferenceEndpoint.from(tx,finalSource);
                var prepared=tx.prepareInstallation(certified.pose().placement(),certified);
                tx.beginInstallation(prepared);
                var actor=tx.actor();var target=certified.pose().placement();var b=target.bounds();var p=target.transport();
                actor.width=(float)target.shape().dimensions().width();actor.height=(float)target.shape().dimensions().height();
                actor.eyeHeight=(float)target.shape().dimensions().eyeHeight();
                actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
                actor.posX=p.x();actor.posY=p.y();actor.posZ=p.z();tx.recordInstalled(prepared);
                var installed=prepared.installedEndpoint();var footWindow=CapsuleFootContacts.window(installed,0);
                var finalCapture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx,footWindow,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
                var complete=assertInstanceOf(CapsuleSupportObservation.Complete.class,
                        CapsuleSupportObservation.observe(installed,finalCapture,footWindow,0,FreeOwnerTransaction.ACCURACY));
                assertFalse(complete.supports().isEmpty(),"negative proof needs actual final supported geometry");
                for(var event:movement.clipEvents())for(var support:complete.supports())
                    assertFalse(event.matchesFinal(installed,complete,finalCapture,support));
            }
        }
    }

}
