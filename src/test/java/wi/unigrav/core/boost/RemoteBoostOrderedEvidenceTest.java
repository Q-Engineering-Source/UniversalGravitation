package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteBoostOrderedEvidenceTest {
    @Test
    void staleAndCurrentFrameCompanionsRemainOrdered() {
        RemoteBoostOrderedEvidence evidence = new RemoteBoostOrderedEvidence(8);
        evidence.append(RemoteBoostOrderedEvidence.Entry.dropped());
        evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(9, 20, 5, false)));

        assertTrue(evidence.consume().drop());
        assertFalse(evidence.consume().drop());
    }

    @Test
    void expirationAndOverflowCannotShiftLaterFramesForward() {
        RemoteBoostOrderedEvidence evidence = new RemoteBoostOrderedEvidence(2);
        evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(1, 1, 5, false)));
        evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(2, 10, 5, false)));
        assertFalse(evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(3, 10, 5, false))));
        assertFalse(evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(4, 10, 5, false))));
        assertTrue(evidence.consume().drop());
        assertTrue(evidence.consume().drop());
        RemoteBoostOrderedEvidence.Entry third = evidence.consume();
        RemoteBoostOrderedEvidence.Entry fourth = evidence.consume();
        assertFalse(third.drop());
        assertFalse(fourth.drop());
        assertTrue(third.frame().sequence() == 3L);
        assertTrue(fourth.frame().sequence() == 4L);

        evidence.append(RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(4, 1, 5, false)));
        RemoteBoostOrderedEvidence.Entry expired = evidence.consume();
        assertTrue(RemoteBoostOrderedEvidence.expired(expired, 10, 2));
    }
}
