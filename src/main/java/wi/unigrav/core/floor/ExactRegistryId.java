package wi.unigrav.core.floor;

import net.minecraft.util.ResourceLocation;

/** Shared canonical ID parsing for configured magnetic items and blocks; never a numeric registry lookup. */
final class ExactRegistryId {
    private ExactRegistryId() {
    }

    static ResourceLocation parse(String registryId) {
        if (registryId == null) {
            throw new IllegalArgumentException("registryId must be a lowercase namespaced registry ID");
        }
        int separator = registryId.indexOf(':');
        if (separator <= 0 || separator != registryId.lastIndexOf(':') || separator == registryId.length() - 1) {
            throw new IllegalArgumentException("registryId must be a lowercase namespaced registry ID");
        }
        String namespace = registryId.substring(0, separator);
        String path = registryId.substring(separator + 1);
        if (!hasOnlyRegistryCharacters(namespace, false) || !hasOnlyRegistryCharacters(path, true)) {
            throw new IllegalArgumentException("registryId must match [a-z0-9_.-]+:[a-z0-9_./-]+");
        }
        try {
            return new ResourceLocation(namespace, path);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid registryId: " + registryId, exception);
        }
    }

    private static boolean hasOnlyRegistryCharacters(String value, boolean allowSlash) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '.' || character == '-'
                    || allowSlash && character == '/';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
