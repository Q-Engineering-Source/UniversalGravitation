package wi.unigrav.core.player;

import java.util.Objects;

/**
 * One bounded packet view over caller-owned cumulative numeric work. A network
 * owner supplies the actual framed byte count and shares one StoredDyadic.Work
 * across its decode attempts; constructing this view never resets that work.
 */
final class PhysicsDecodeBudget {
    // Numeric materialization admits the same bounded wire envelope as the protocol parser.
    static final int MAX_PACKET_BYTES = PlayerPhysicsProtocol.MAX_BYTES;
    static final int MAX_FIELDS_PER_RESTORE = 64;
    static final int QUATERNION_VERIFICATION_SLOTS = 108;

    private final int encodedPacketBytes;
    private final StoredDyadic.Work numericWork;
    private int rawFieldReads;
    private int quaternionReconstructionCalls;
    private boolean eyeAnchorQuaternionReserved;
    private int reservedQuaternionVerificationSlots;
    private int restoreAttempts;

    PhysicsDecodeBudget(int encodedPacketBytes, StoredDyadic.Work callerOwnedNumericWork) {
        if (encodedPacketBytes < 0) throw new IllegalArgumentException("encoded packet length must not be negative");
        this.encodedPacketBytes = encodedPacketBytes;
        numericWork = Objects.requireNonNull(callerOwnedNumericWork, "callerOwnedNumericWork");
    }

    void beginFreeRestore() {
        beginRestore();
    }

    void beginInputRestore() {
        beginRestore();
    }

    private void beginRestore() {
        restoreAttempts++;
        if (restoreAttempts != 1) {
            throw new IllegalStateException("a packet decode budget can materialize only once");
        }
        if (encodedPacketBytes == 0 || encodedPacketBytes > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("physical-state packet exceeds the bounded decode envelope");
        }
    }

    void chargeRawFieldReads(int fieldCount) {
        if (fieldCount < 0 || fieldCount > MAX_FIELDS_PER_RESTORE - rawFieldReads) {
            throw new IllegalArgumentException("physical-state raw-field budget exceeded");
        }
        rawFieldReads += fieldCount;
        // A field remains charged when any later proof validation fails.
        numericWork.charge(fieldCount);
    }

    void reserveQuaternionReconstruction() {
        if (quaternionReconstructionCalls != 0) {
            throw new IllegalStateException("one exact quaternion reconstruction is allowed per placement");
        }
        quaternionReconstructionCalls++;
        reservedQuaternionVerificationSlots += QUATERNION_VERIFICATION_SLOTS;
        // Reserve the documented upper bound before invoking the external exact constructor.
        numericWork.charge(QUATERNION_VERIFICATION_SLOTS);
    }

    void reserveEyeAnchorQuaternionReconstruction() {
        if (eyeAnchorQuaternionReserved) throw new IllegalStateException("one eye anchor quaternion is allowed per placement");
        eyeAnchorQuaternionReserved = true;
        reservedQuaternionVerificationSlots += QUATERNION_VERIFICATION_SLOTS;
        numericWork.charge(QUATERNION_VERIFICATION_SLOTS);
    }

    StoredDyadic.Work numericWork() { return numericWork; }
    int encodedPacketBytes() { return encodedPacketBytes; }
    int rawFieldReads() { return rawFieldReads; }
    int quaternionReconstructionCalls() { return quaternionReconstructionCalls; }
    int reservedQuaternionVerificationSlots() { return reservedQuaternionVerificationSlots; }
    int restoreAttempts() { return restoreAttempts; }
    long numericWorkUnits() { return numericWork.units(); }
    int peakNumericBits() { return numericWork.peakBits(); }

    /** This numeric restoration layer has not performed any CHB narrow-phase query. */
    int chbNarrowPhaseQueryWork() { return 0; }
}
