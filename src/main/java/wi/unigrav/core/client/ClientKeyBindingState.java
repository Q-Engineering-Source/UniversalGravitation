package wi.unigrav.core.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyModifier;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Reads the physical held state of a configurable Forge key binding. */
public final class ClientKeyBindingState {

    private ClientKeyBindingState() {
    }

    /**
     * Unlike {@link KeyBinding#isKeyDown()}, this samples the binding's current
     * key code directly and checks its configured modifier. This avoids the
     * KeyBindingMap event-routing conflict between base and modified bindings.
     */
    public static boolean isDown(KeyBinding binding) {
        if (binding == null || !binding.getKeyConflictContext().isActive()
                || !modifierSatisfied(binding)) {
            return false;
        }
        int keyCode = binding.getKeyCode();
        if (keyCode == Keyboard.KEY_NONE) {
            return false;
        }
        return keyCode < 0
                ? Mouse.isButtonDown(keyCode + 100)
                : Keyboard.isKeyDown(keyCode);
    }

    private static boolean modifierSatisfied(KeyBinding binding) {
        KeyModifier modifier = binding.getKeyModifier();
        return modifier == null || modifier == KeyModifier.NONE
                || modifier.isActive(binding.getKeyConflictContext());
    }
}
