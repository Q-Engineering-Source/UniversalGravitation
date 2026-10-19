package wi.unigrav.core.client;

import wi.unigrav.core.client.gui.NfrPoseBackpackScreen;

/** Loaded only after the caller confirms both optional client GUI mods are present. */
public final class NfrPoseBackpackGuiBridge {
    private NfrPoseBackpackGuiBridge() { }

    public static void open() {
        NfrPoseBackpackScreen.open();
    }
}
