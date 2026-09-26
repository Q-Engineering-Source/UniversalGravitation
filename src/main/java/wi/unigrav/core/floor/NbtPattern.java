package wi.unigrav.core.floor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

/**
 * An immutable, bounded subset predicate compiled from a native NBT compound.
 *
 * <p>Compound patterns require every pattern key but permit additional candidate
 * keys. Lists are exact-length ordered sequences. Primitive tags compare their
 * native NBT type and exact value; numeric values are never coerced.
 */
public final class NbtPattern {

    /** Maximum nesting below the root compound accepted from configuration. */
    public static final int MAX_DEPTH = 16;
    /** Maximum NBT tags, including the root compound, accepted from configuration. */
    public static final int MAX_NODES = 128;
    /** Maximum UTF-16 code units in a configured NBT string or key. */
    public static final int MAX_STRING_CHARS = 4_096;
    /** Maximum elements in a byte or int array with a public native getter. */
    public static final int MAX_ARRAY_ELEMENTS = 1_024;
    /** Maximum uncompressed native NBT bytes retained for one pattern. */
    public static final int MAX_SERIALIZED_BYTES = 16 * 1_024;

    private final NBTTagCompound pattern;

    private NbtPattern(NBTTagCompound pattern) {
        this.pattern = pattern;
    }

    /**
     * Compiles a defensive native-NBT copy of {@code source}.
     *
     * <p>The bounded serialization pass is also the size guard for
     * {@link NBTTagLongArray}: the 1.12 native API deliberately exposes no
     * public length or array getter for that tag.
     *
     * @throws IllegalArgumentException if the source is null, malformed, too
     *         deeply nested, or exceeds a configured bound
     */
    public static NbtPattern compile(NBTTagCompound source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        validateTree(source);
        NBTTagCompound owned = copyThroughBoundedNativeNbt(source);
        validateTree(owned);
        return new NbtPattern(owned);
    }

    /**
     * Returns whether {@code candidate} satisfies this compound-subset pattern.
     * A null candidate never matches.
     */
    public boolean matches(NBTTagCompound candidate) {
        return candidate != null && matchesTag(pattern, candidate);
    }

    private static NBTTagCompound copyThroughBoundedNativeNbt(NBTTagCompound source) {
        BoundedOutputStream output = new BoundedOutputStream(MAX_SERIALIZED_BYTES);
        try {
            CompressedStreamTools.write(source, new DataOutputStream(output));
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(output.bytes()))) {
                return CompressedStreamTools.read(input);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "unable to compile NBT pattern within configured native NBT limits", exception);
        }
    }

    private static void validateTree(NBTTagCompound root) {
        validateTag(root, 0, new NodeBudget());
    }

    private static void validateTag(NBTBase tag, int depth, NodeBudget budget) {
        if (tag == null) {
            throw new IllegalArgumentException("NBT pattern contains a null tag");
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("NBT pattern exceeds maximum depth " + MAX_DEPTH);
        }
        budget.consume();
        if (!isNativeTag(tag)) {
            throw new IllegalArgumentException("NBT pattern contains an unsupported native tag type "
                    + tag.getId());
        }

        if (tag instanceof NBTTagCompound compound) {
            validateCompound(compound, depth, budget);
        } else if (tag instanceof NBTTagList list) {
            validateList(list, depth, budget);
        } else if (tag instanceof NBTTagString string) {
            validateString(string.getString(), "NBT string");
        } else if (tag instanceof NBTTagByteArray bytes) {
            if (bytes.getByteArray().length > MAX_ARRAY_ELEMENTS) {
                throw new IllegalArgumentException("NBT byte array exceeds " + MAX_ARRAY_ELEMENTS
                        + " elements");
            }
        } else if (tag instanceof NBTTagIntArray integers
                && integers.getIntArray().length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException("NBT int array exceeds " + MAX_ARRAY_ELEMENTS
                    + " elements");
        }
    }

    private static void validateCompound(NBTTagCompound compound, int depth, NodeBudget budget) {
        Set<String> keys = compound.getKeySet();
        if (keys == null || keys.size() > budget.remaining()) {
            throw new IllegalArgumentException("NBT compound exceeds maximum node count " + MAX_NODES);
        }
        for (String key : keys) {
            if (key == null) {
                throw new IllegalArgumentException("NBT compound contains a null key");
            }
            validateString(key, "NBT compound key");
            validateTag(compound.getTag(key), depth + 1, budget);
        }
    }

    private static void validateList(NBTTagList list, int depth, NodeBudget budget) {
        int count = list.tagCount();
        if (count < 0 || count > budget.remaining()) {
            throw new IllegalArgumentException("NBT list exceeds maximum node count " + MAX_NODES);
        }
        int elementType = list.getTagType();
        for (int index = 0; index < count; index++) {
            NBTBase element = list.get(index);
            if (element == null || element.getId() != elementType) {
                throw new IllegalArgumentException("NBT list has inconsistent element types");
            }
            validateTag(element, depth + 1, budget);
        }
    }

    private static void validateString(String value, String description) {
        if (value == null || value.length() > MAX_STRING_CHARS) {
            throw new IllegalArgumentException(description + " exceeds " + MAX_STRING_CHARS
                    + " characters");
        }
    }

    private static boolean matchesTag(NBTBase expected, NBTBase actual) {
        if (actual == null || expected.getId() != actual.getId() || !isNativeTag(actual)) {
            return false;
        }
        if (expected instanceof NBTTagCompound expectedCompound) {
            NBTTagCompound actualCompound = (NBTTagCompound) actual;
            for (String key : expectedCompound.getKeySet()) {
                if (!actualCompound.hasKey(key)
                        || !matchesTag(expectedCompound.getTag(key), actualCompound.getTag(key))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof NBTTagList expectedList) {
            NBTTagList actualList = (NBTTagList) actual;
            if (expectedList.tagCount() != actualList.tagCount()) {
                return false;
            }
            for (int index = 0; index < expectedList.tagCount(); index++) {
                if (!matchesTag(expectedList.get(index), actualList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof NBTTagByteArray expectedBytes) {
            return Arrays.equals(expectedBytes.getByteArray(),
                    ((NBTTagByteArray) actual).getByteArray());
        }
        if (expected instanceof NBTTagIntArray expectedIntegers) {
            return Arrays.equals(expectedIntegers.getIntArray(),
                    ((NBTTagIntArray) actual).getIntArray());
        }
        // NBTTagLongArray has no public getter in 1.12. Its native equals is the
        // only public exact-value API; the compiled serialization cap bounds the
        // pattern length before this comparison.
        return expected.equals(actual);
    }

    private static boolean isNativeTag(NBTBase tag) {
        return switch (tag.getId()) {
            case 1 -> tag instanceof NBTTagByte;
            case 2 -> tag instanceof NBTTagShort;
            case 3 -> tag instanceof NBTTagInt;
            case 4 -> tag instanceof NBTTagLong;
            case 5 -> tag instanceof NBTTagFloat;
            case 6 -> tag instanceof NBTTagDouble;
            case 7 -> tag instanceof NBTTagByteArray;
            case 8 -> tag instanceof NBTTagString;
            case 9 -> tag instanceof NBTTagList;
            case 10 -> tag instanceof NBTTagCompound;
            case 11 -> tag instanceof NBTTagIntArray;
            case 12 -> tag instanceof NBTTagLongArray;
            default -> false;
        };
    }

    private static final class NodeBudget {
        private int nodes;

        void consume() {
            if (nodes >= MAX_NODES) {
                throw new IllegalArgumentException("NBT pattern exceeds maximum node count "
                        + MAX_NODES);
            }
            nodes++;
        }

        int remaining() {
            return MAX_NODES - nodes;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final byte[] buffer;
        private int size;

        BoundedOutputStream(int maximumBytes) {
            this.buffer = new byte[maximumBytes];
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            buffer[size++] = (byte) value;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            if (values == null || offset < 0 || length < 0 || length > values.length - offset) {
                throw new IndexOutOfBoundsException("invalid output byte range");
            }
            requireCapacity(length);
            System.arraycopy(values, offset, buffer, size, length);
            size += length;
        }

        byte[] bytes() {
            return Arrays.copyOf(buffer, size);
        }

        private void requireCapacity(int requested) throws IOException {
            if (requested > buffer.length - size) {
                throw new IOException("configured NBT byte budget exceeded");
            }
        }
    }
}
