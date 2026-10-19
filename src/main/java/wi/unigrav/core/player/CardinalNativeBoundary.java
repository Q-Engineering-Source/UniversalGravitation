package wi.unigrav.core.player;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;

/** Lexical authority for native work belonging to one admitted Cardinal operation. */
public final class CardinalNativeBoundary {
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private CardinalNativeBoundary() { }

    public enum AbortKind { AUTHORITY_LOST, EXECUTION_FAILED }
    public static final class AuthorityLost extends RuntimeException {
        public AuthorityLost(String detail) {super(detail);}
    }
    public static final class Abort extends RuntimeException {
        private final Object token;
        private final String stage;
        private final AbortKind kind;
        private Abort(Object token, String stage, Throwable cause) {
            this(token,stage,cause,cause instanceof AuthorityLost ? AbortKind.AUTHORITY_LOST : AbortKind.EXECUTION_FAILED);
        }
        private Abort(Object token,String stage,Throwable cause,AbortKind kind) {
            super("Cardinal native boundary stopped at " + stage,cause);
            this.token=token;this.stage=stage;this.kind=kind;
        }
        public boolean belongsTo(Object operationToken) { return token == operationToken; }
        public String stage() { return stage; }
        public AbortKind kind() {return kind;}
    }

    private static final class Frame {
        final Object token;
        final Entity actor;
        final EnvironmentObservationWork work;
        final Runnable proof;
        int suspended;
        int depth;
        Abort aborted;
        boolean proofAuthorityFailure;
        Frame(Object token, Entity actor, EnvironmentObservationWork work, Runnable proof) {
            this.token=Objects.requireNonNull(token); this.actor=Objects.requireNonNull(actor);
            this.work=Objects.requireNonNull(work); this.proof=Objects.requireNonNull(proof);
        }
        Abort latch(String stage,RuntimeException cause) {
            if(cause instanceof Abort abort) {
                if(abort.belongsTo(token))aborted=abort;
                else if(aborted==null)aborted=new Abort(token,stage,abort);
                return abort;
            }
            if(aborted==null)aborted=new Abort(token,stage,cause);
            return aborted;
        }

        void check(String stage) {
            if (aborted != null) throw aborted;
            try { proof.run(); work.requireCurrent(actor); }
            catch (RuntimeException cause) {
                proofAuthorityFailure = aborted == null && (cause instanceof OwnerContextChangedException
                        || cause instanceof AuthorityLost);
                throw latch(stage,cause);
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Frame frame;
        private boolean closed;
        private Scope(Frame frame) { this.frame=frame; }
        public Object token() { return frame.token; }
        public void check(String stage) {
            if(closed || FRAMES.get().peek()!=frame)throw new IllegalStateException("inactive Cardinal operation scope");
            frame.check(stage);
        }
        /** Classification belongs to this exact lexical frame, not to the exception class at a caller. */
        boolean isOwnAuthorityFailure(RuntimeException failure) {
            return frame.proofAuthorityFailure && frame.aborted != null
                    && frame.aborted.belongsTo(frame.token)
                    && (frame.aborted == failure || frame.aborted.getCause() == failure);
        }
        @Override public void close() {
            if (closed) throw new IllegalStateException("Cardinal native scope already closed");
            var stack=FRAMES.get();
            if (stack.peek()!=frame || frame.depth!=0 || frame.suspended!=0)
                throw new IllegalStateException("Cardinal native scopes must close in lexical order");
            closed=true; stack.pop(); if(stack.isEmpty())FRAMES.remove();
        }
    }

    public static Scope open(Object token, Entity actor, EnvironmentObservationWork work, Runnable phaseProof) {
        Frame frame=new Frame(token,actor,work,phaseProof);
        frame.check("operation entry"); FRAMES.get().push(frame); return new Scope(frame);
    }

    /** A method invocation captures its actor-specific authority; callback nesting cannot acquire it. */
    public static final class Invocation implements AutoCloseable {
        private final Frame frame;
        private final String stage;
        private final int callDepth;
        private boolean closed;
        private Invocation(Frame frame,String stage) {
            this.frame=frame; this.stage=stage;
            if(frame!=null){frame.check(stage+" entry");frame.depth++;}
            callDepth=frame==null?0:frame.depth;
        }
        private void requireActive() {
            if(closed || frame!=null && (FRAMES.get().peek()!=frame || frame.suspended!=0 || frame.depth!=callDepth))
                throw new IllegalStateException("inactive Cardinal native invocation");
        }
        public void charge(EnvironmentObservationWork.Unit unit) {
            requireActive();if(frame==null)return;
            frame.check(stage);
            try {frame.work.charge(unit);}
            catch(RuntimeException cause) {
                throw frame.latch(stage,cause);
            }
        }
        public boolean active() { return frame!=null; }
        public void check() {requireActive();if(frame!=null)frame.check(stage);}
        /** An internal native chain keeps authority active for its own guarded callsites. */
        public <T> T internal(String callback,Supplier<T> original) {
            requireActive();if(frame==null)return original.get();
            charge(EnvironmentObservationWork.Unit.DISPATCH);
            T result;
            try {result=original.get();}
            catch(RuntimeException|Error failure) {
                latchExecution(stage+" internal "+callback,failure);
                throw failure;
            }
            frame.check(stage+" after "+callback);return result;
        }
        public void internal(String callback,Runnable original) {
            internal(callback,()->{original.run();return null;});
        }

        public <T> T external(String callback,Supplier<T> original) {
            requireActive();if(frame==null)return original.get();
            frame.check(stage+" before "+callback);
            try { frame.work.charge(EnvironmentObservationWork.Unit.DISPATCH); }
            catch(RuntimeException cause){
                throw frame.latch(stage+" dispatch "+callback,cause);
            }
            frame.suspended++;
            T result;
            try { result=original.get(); }
            catch(RuntimeException|Error failure) {
                // Preserve the producer exception now; a mod swallowing it cannot clear this operation's failure.
                latchExecution(stage+" callback "+callback,failure);
                throw failure;
            } finally { frame.suspended--; }
            frame.check(stage+" after "+callback);
            return result;
        }
        private void latchExecution(String stage,Throwable failure) {
            if(frame.aborted!=null)return;
            if(failure instanceof Abort abort && abort.belongsTo(frame.token))frame.aborted=abort;
            else frame.aborted=new Abort(frame.token,stage,failure,AbortKind.EXECUTION_FAILED);
        }        public void external(String callback,Runnable original) {
            external(callback,()->{original.run();return null;});
        }
        @Override public void close() {
            requireActive();closed=true;if(frame!=null)frame.depth--;
        }
    }

    /** One external dispatch made by the owner implementation, outside transformed native methods. */
    public static <T> T dispatch(Entity actor,String stage,Supplier<T> original) {
        try(var invocation=enter(actor,stage)) {return invocation.external(stage,original);}
    }
    public static void dispatch(Entity actor,String stage,Runnable original) {
        dispatch(actor,stage,()->{original.run();return null;});
    }
    public static void charge(Entity actor,String stage,EnvironmentObservationWork.Unit unit) {
        try(var invocation=enter(actor,stage)){invocation.charge(unit);}
    }
    public static void check(Entity actor,String stage) {
        try(var invocation=enter(actor,stage)){invocation.check();}
    }
    public static Invocation enter(Entity actor,String stage) {
        Frame frame=FRAMES.get().peek();
        return new Invocation(frame!=null && frame.actor==actor && frame.suspended==0?frame:null,stage);
    }
}