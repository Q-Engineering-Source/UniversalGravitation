package wi.unigrav.core.config;

import wi.unigrav.core.GravityMode;

/**
 * One declarative entity-binding rule from {@code config/unigrav/entities.json}:
 * bind every entity of a registered entity type, in a given mode.
 *
 * <p>This class is deliberately free of Minecraft imports so the rule
 * parsing/validation logic can be unit-tested without an MC environment;
 * the registry lookup ({@code EntityList}) lives in
 * {@link EntityBindingsLoader}.
 */
public record EntityBindingRule(String entityName, GravityMode mode) {

    /**
     * Pseudo-mode for {@code entities.json} entries that exempt an entity
     * type from the global takeover: it keeps vanilla gravity. Not a
     * {@link GravityMode}; recognized by the loader before mode parsing.
     */
    public static final String VANILLA_MODE = "VANILLA";

    /** Wildcard entity name matching every entity type. */
    public static final String WILDCARD = "*";

    /**
     * Parses a mode name, case-insensitively. Returns {@code null} for
     * unrecognized values (including the pseudo-mode {@link #VANILLA_MODE},
     * which the loader handles separately).
     */
    public static GravityMode parseMode(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "REPLACE" -> GravityMode.REPLACE;
            case "ADDITIVE" -> GravityMode.ADDITIVE;
            default -> null;
        };
    }

    /** Whether the text names the {@link #VANILLA_MODE} pseudo-mode, case-insensitively. */
    public static boolean isVanillaMode(String text) {
        return text != null && text.trim().equalsIgnoreCase(VANILLA_MODE);
    }

    /**
     * Validates an entity registry name in {@code namespace:path} form;
     * the namespace may be omitted (defaults to {@code minecraft} in the
     * registry lookup). Charset follows ResourceLocation rules. The
     * {@link #WILDCARD} is also accepted.
     */
    public static boolean isValidEntityName(String name) {
        return name != null
                && (WILDCARD.equals(name) || name.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+"));
    }
}
