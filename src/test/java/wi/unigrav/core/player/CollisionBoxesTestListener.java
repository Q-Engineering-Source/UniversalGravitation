package wi.unigrav.core.player;

import java.util.Objects;
import java.util.function.Consumer;

import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Public subscriber required by Cleanroom's MethodHandles-based EventListenerFactory. */
public final class CollisionBoxesTestListener {
    private final Consumer<GetCollisionBoxesEvent> listener;

    public CollisionBoxesTestListener(Consumer<GetCollisionBoxesEvent> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @SubscribeEvent
    public void boxes(GetCollisionBoxesEvent event) {
        listener.accept(event);
    }
}
