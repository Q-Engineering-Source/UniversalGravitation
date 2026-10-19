package wi.unigrav.core.player;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Context contracts only; this suite does not claim native Mixin transformation. */
class CardinalNativeBoundaryTest {
    @TempDir Path directory;

    @Test void externalCallbackSuspendsOnlyOldFrameAndNewOperationCanComplete() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93001),false)) {
            var actor=world.serverPlayer(new UUID(0,93002));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93003),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            var validA=new AtomicBoolean(true);var calls=new AtomicInteger();Object a=new Object(),b=new Object();
            try(var owner=CardinalNativeBoundary.open(a,actor,resources.environmentObservationWork(),()->{
                if(!validA.get())throw new CardinalNativeBoundary.AuthorityLost("A retired");
            });var invocation=CardinalNativeBoundary.enter(actor,"A native")) {
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->invocation.external("producer",()->{
                    validA.set(false);
                    try(var nested=CardinalNativeBoundary.enter(actor,"callback nested native")) {
                        assertFalse(nested.active(),"callback cannot accidentally borrow old A authority");
                    }
                    try(var newOwner=CardinalNativeBoundary.open(b,actor,resources.environmentObservationWork(),()->{});
                        var nativeB=CardinalNativeBoundary.enter(actor,"B native")) {
                        assertTrue(nativeB.active());nativeB.external("B producer",()->{return calls.incrementAndGet();});
                        newOwner.check("B completed");
                    }
                    return 1;
                }));
                assertTrue(abort.belongsTo(a));assertFalse(abort.belongsTo(b));
                assertTrue(owner.isOwnAuthorityFailure(abort), "the exact frame identifies its own post-callback proof loss");
                assertEquals(CardinalNativeBoundary.AbortKind.AUTHORITY_LOST,abort.kind());
                assertEquals(1,calls.get());
                validA.set(true);
                assertSame(abort,assertThrows(CardinalNativeBoundary.Abort.class,invocation::check),
                        "restored proof cannot clear A's aborted latch");
            }
        }
    }

    @Test void callbackExceptionRemainsPrimaryEvenWhenItRetiresOwner() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93011),false)) {
            var actor=world.serverPlayer(new UUID(0,93012));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93013),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            var valid=new AtomicBoolean(true);var original=new IllegalArgumentException("original producer failure");
            try(var owner=CardinalNativeBoundary.open(new Object(),actor,resources.environmentObservationWork(),()->{
                if(!valid.get())throw new CardinalNativeBoundary.AuthorityLost("retired");
            });var invocation=CardinalNativeBoundary.enter(actor,"native")) {
                assertSame(original,assertThrows(IllegalArgumentException.class,()->invocation.external("producer",(Supplier<Object>)()->{
                    valid.set(false);throw original;
                })));
            }
        }
    }

    @Test void realSharedBudgetFailureIsLatchedWithExactCauseAndNoCallback() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93021),false)) {
            var actor=world.serverPlayer(new UUID(0,93022));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93023),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
            var calls=new AtomicInteger();Object token=new Object();
            try(var owner=CardinalNativeBoundary.open(token,actor,resources.environmentObservationWork(),()->{});
                var invocation=CardinalNativeBoundary.enter(actor,"native")) {
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->invocation.external("producer",()->{return calls.incrementAndGet();}));
                assertTrue(abort.belongsTo(token));assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED,abort.kind());
                assertInstanceOf(FreePostprocessingWork.Exhausted.class,abort.getCause());
                assertEquals(0,calls.get());assertEquals(FreePostprocessingWork.MAX_UNITS,resources.postprocessing().units());
                assertTrue(resources.postprocessing().terminal());
                assertSame(abort,assertThrows(CardinalNativeBoundary.Abort.class,invocation::check));
            }
        }
    }

    @Test void noContextCallsOriginalOnceAndPreservesOriginalThrowable() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93031),false)) {
            var actor=world.serverPlayer(new UUID(0,93032));var calls=new AtomicInteger();
            var original=new IllegalStateException("native original");
            try(var invocation=CardinalNativeBoundary.enter(actor,"unowned native")) {
                assertFalse(invocation.active());
                assertEquals(1,invocation.external("producer",()->{return calls.incrementAndGet();}));
                assertSame(original,assertThrows(IllegalStateException.class,()->invocation.external("producer",(Supplier<Object>)()->{throw original;})));
                invocation.check();assertEquals(1,calls.get());
            }
        }
    }
    @Test void caughtForeignCallbackAbortRemainsPrimaryAndRetiresOnlyOldOperation() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93041),false)) {
            var actor=world.serverPlayer(new UUID(0,93042));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93043),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            Object a=new Object(),b=new Object();var foreign=new AtomicReference<CardinalNativeBoundary.Abort>();
            try(var owner=CardinalNativeBoundary.open(a,actor,resources.environmentObservationWork(),()->{});
                var invocation=CardinalNativeBoundary.enter(actor,"A native")) {
                var primary=assertThrows(CardinalNativeBoundary.Abort.class,()->invocation.external("nested B",(Supplier<Object>)()->{
                    var validB=new AtomicBoolean(true);
                    try(var ownerB=CardinalNativeBoundary.open(b,actor,resources.environmentObservationWork(),()->{
                        if(!validB.get())throw new CardinalNativeBoundary.AuthorityLost("B retired");
                    })) {
                        validB.set(false);
                        var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->ownerB.check("B failure"));
                        foreign.set(abort);throw abort;
                    }
                }));
                assertSame(foreign.get(),primary);assertTrue(primary.belongsTo(b));assertFalse(primary.belongsTo(a));
                var own=assertThrows(CardinalNativeBoundary.Abort.class,invocation::check);
                assertTrue(own.belongsTo(a));assertFalse(own.belongsTo(b));assertSame(primary,own.getCause());
                assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED,own.kind());
                assertSame(own,assertThrows(CardinalNativeBoundary.Abort.class,()->invocation.external("later",()->{return 1;})));
            }
        }
    }

    @Test void caughtInternalOverrideFailureCannotResumeOldNativeTail() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93051),false)) {
            var actor=world.serverPlayer(new UUID(0,93052));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93053),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            Object token=new Object();var original=new IllegalStateException("override failed before super");
            try(var owner=CardinalNativeBoundary.open(token,actor,resources.environmentObservationWork(),()->{});
                var invocation=CardinalNativeBoundary.enter(actor,"A native")) {
                assertSame(original,assertThrows(IllegalStateException.class,()->invocation.internal("override",(Supplier<Object>)()->{throw original;})));
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,invocation::check);
                assertTrue(abort.belongsTo(token));assertSame(original,abort.getCause());
                assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED,abort.kind());
            }
        }
    }

    @Test void foreignAbortFromProofAlsoLatchesAnOwnExecutionFailure() {
        try(var world=BootWorldFixture.open(directory,new UUID(0,93061),false)) {
            var actor=world.serverPlayer(new UUID(0,93062));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,93063),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,world.world(),scope,0);
            Object a=new Object(),b=new Object();var validB=new AtomicBoolean(true);
            CardinalNativeBoundary.Abort foreign;
            try(var ownerB=CardinalNativeBoundary.open(b,actor,resources.environmentObservationWork(),()->{
                if(!validB.get())throw new CardinalNativeBoundary.AuthorityLost("B retired");
            })) {
                validB.set(false);foreign=assertThrows(CardinalNativeBoundary.Abort.class,()->ownerB.check("B failure"));
            }
            var fault=new AtomicReference<CardinalNativeBoundary.Abort>();
            try(var ownerA=CardinalNativeBoundary.open(a,actor,resources.environmentObservationWork(),()->{
                if(fault.get()!=null)throw fault.get();
            })) {
                fault.set(foreign);
                assertSame(foreign,assertThrows(CardinalNativeBoundary.Abort.class,()->ownerA.check("foreign proof")));
                var own=assertThrows(CardinalNativeBoundary.Abort.class,()->ownerA.check("after caught foreign"));
                assertTrue(own.belongsTo(a));assertSame(foreign,own.getCause());
                assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED,own.kind());
            }
        }
    }
    @Test void callbackThrowingTheAuthorityExceptionTypeRemainsAnExecutionFailure() {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 93091), false)) {
            var actor = world.serverPlayer(new UUID(0, 93092));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 93093), actor.dimension, 1);
            var resources = new FreeTickResources.Store().acquire(actor, world.world(), scope, 0);
            var failure = new OwnerContextChangedException("producer deliberately threw the proof exception type");
            try (var owner = CardinalNativeBoundary.open(new Object(), actor,
                    resources.environmentObservationWork(), () -> {});
                 var invocation = CardinalNativeBoundary.enter(actor, "native callback provenance")) {
                assertSame(failure, assertThrows(OwnerContextChangedException.class,
                        () -> invocation.external("producer", (Supplier<Object>) () -> { throw failure; })));
                assertFalse(owner.isOwnAuthorityFailure(failure), "callback origin wins over the exception type");
                var latched = assertThrows(CardinalNativeBoundary.Abort.class, invocation::check);
                assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED, latched.kind());
                assertSame(failure, latched.getCause());
                assertFalse(owner.isOwnAuthorityFailure(latched));
            }
        }
    }
}