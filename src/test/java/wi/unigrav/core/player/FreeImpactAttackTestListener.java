package wi.unigrav.core.player;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Public top-level owner is required by the actual Forge ASM event dispatcher. */
public final class FreeImpactAttackTestListener {
    private final Consumer<LivingAttackEvent> callback;
    public FreeImpactAttackTestListener(Consumer<LivingAttackEvent> callback) { this.callback=callback; }
    /** Registration errors are logged and swallowed by Forge; fail this TEST bootstrap explicitly. */
    void registerChecked() {
        var failure = new AtomicReference<Throwable>();
        var logger = (Logger) LogManager.getLogger("FML");
        var appender = new AbstractAppender("impact-registration-" + System.identityHashCode(this), null,
                PatternLayout.createDefaultLayout(), false) {
            @Override public void append(LogEvent event) {
                if (event.getMessage().getFormattedMessage().startsWith("Error registering event handler:")) {
                    var cause = event.getThrown();
                    failure.compareAndSet(null, cause != null ? cause
                            : new IllegalStateException(event.getMessage().getFormattedMessage()));
                }
            }
        };
        appender.start(); logger.addAppender(appender);
        try {
            MinecraftForge.EVENT_BUS.register(this);
            var cause = failure.get();
            if (cause != null) {
                MinecraftForge.EVENT_BUS.unregister(this);
                throw new IllegalStateException("Actual Forge impact listener registration failed", cause);
            }
        } finally { logger.removeAppender(appender); appender.stop(); }
    }
    @SubscribeEvent public void onAttack(LivingAttackEvent event) { callback.accept(event); }
}
