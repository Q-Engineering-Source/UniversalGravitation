package wi.unigrav.core.player;

/** An owner invariant rejected at its actual check, distinct from an external callback throwing. */
final class OwnerContextChangedException extends IllegalStateException {
    OwnerContextChangedException(String detail) { super(detail); }
}
