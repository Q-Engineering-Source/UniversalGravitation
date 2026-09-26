package wi.unigrav.core.client.boost;

import wi.unigrav.core.boost.BoostProtocol;

/** Pure, de-duplicated presentation policy for received remote STATE packets. */
final class RemoteBoostPresentation {

    private RemoteBoostPresentation() {
    }

    static Notice notice(BoostProtocol.Mode previousMode, BoostProtocol.Packet packet) {
        if (packet.operation() == BoostProtocol.Operation.STOP) {
            return Notice.safetyStop(packet.reason());
        }
        if (packet.reason() != BoostProtocol.Reason.NONE) {
            return Notice.reason(packet.reason());
        }
        return packet.mode() != previousMode ? Notice.mode(packet.mode()) : null;
    }

    record Notice(Kind kind, BoostProtocol.Mode mode, BoostProtocol.Reason reason) {
        static Notice mode(BoostProtocol.Mode mode) {
            return new Notice(Kind.MODE, mode, BoostProtocol.Reason.NONE);
        }

        static Notice reason(BoostProtocol.Reason reason) {
            return new Notice(Kind.REASON, BoostProtocol.Mode.UNAVAILABLE, reason);
        }

        static Notice safetyStop(BoostProtocol.Reason reason) {
            return new Notice(Kind.SAFETY_STOP, BoostProtocol.Mode.UNAVAILABLE, reason);
        }
    }

    enum Kind {
        MODE,
        REASON,
        SAFETY_STOP
    }
}
