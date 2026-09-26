package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import org.junit.jupiter.api.Test;

class NbtPatternTest {

    @Test
    void compoundsAreSubsetsButListsAreOrderedAndExactLength() {
        NBTTagCompound requiredChild = new NBTTagCompound();
        requiredChild.setString("name", "anchor");
        NBTTagList requiredList = new NBTTagList();
        requiredList.appendTag(new NBTTagInt(3));
        requiredList.appendTag(new NBTTagInt(5));
        NBTTagCompound source = new NBTTagCompound();
        source.setTag("child", requiredChild);
        source.setTag("sequence", requiredList);
        NbtPattern pattern = NbtPattern.compile(source);

        NBTTagCompound candidate = source.copy();
        candidate.getCompoundTag("child").setInteger("extra", 9);
        candidate.setString("extraRoot", "allowed");
        assertTrue(pattern.matches(candidate));

        NBTTagList reversed = new NBTTagList();
        reversed.appendTag(new NBTTagInt(5));
        reversed.appendTag(new NBTTagInt(3));
        candidate.setTag("sequence", reversed);
        assertFalse(pattern.matches(candidate));

        NBTTagList shorter = new NBTTagList();
        shorter.appendTag(new NBTTagInt(3));
        candidate.setTag("sequence", shorter);
        assertFalse(pattern.matches(candidate));
    }

    @Test
    void compoundElementsInListsUseSubsetMatching() {
        NBTTagCompound requiredElement = new NBTTagCompound();
        requiredElement.setString("id", "north");
        NBTTagList requiredList = new NBTTagList();
        requiredList.appendTag(requiredElement);
        NBTTagCompound source = new NBTTagCompound();
        source.setTag("entries", requiredList);
        NbtPattern pattern = NbtPattern.compile(source);

        NBTTagCompound actualElement = requiredElement.copy();
        actualElement.setInteger("extra", 1);
        NBTTagList actualList = new NBTTagList();
        actualList.appendTag(actualElement);
        NBTTagCompound candidate = new NBTTagCompound();
        candidate.setTag("entries", actualList);
        assertTrue(pattern.matches(candidate));

        actualElement.removeTag("id");
        assertFalse(pattern.matches(candidate));
    }

    @Test
    void allNativeNumericTypesAndPrimitiveArrayValuesMustMatchExactly() {
        NBTTagCompound source = new NBTTagCompound();
        source.setByte("byte", (byte) 1);
        source.setShort("short", (short) 2);
        source.setInteger("int", 3);
        source.setLong("long", 4L);
        source.setFloat("float", 5.0F);
        source.setDouble("double", 6.0D);
        source.setByteArray("bytes", new byte[] {1, 2, 3});
        source.setIntArray("ints", new int[] {7, 11});
        source.setTag("longs", new NBTTagLongArray(new long[] {13L, 17L}));
        NbtPattern pattern = NbtPattern.compile(source);

        assertTrue(pattern.matches(source.copy()));

        NBTTagCompound wrongByte = source.copy();
        wrongByte.setShort("byte", (short) 1);
        assertFalse(pattern.matches(wrongByte));

        NBTTagCompound wrongShort = source.copy();
        wrongShort.setInteger("short", 2);
        assertFalse(pattern.matches(wrongShort));

        NBTTagCompound wrongInt = source.copy();
        wrongInt.setLong("int", 3L);
        assertFalse(pattern.matches(wrongInt));

        NBTTagCompound wrongLong = source.copy();
        wrongLong.setFloat("long", 4.0F);
        assertFalse(pattern.matches(wrongLong));

        NBTTagCompound wrongFloat = source.copy();
        wrongFloat.setDouble("float", 5.0D);
        assertFalse(pattern.matches(wrongFloat));

        NBTTagCompound wrongDouble = source.copy();
        wrongDouble.setFloat("double", 6.0F);
        assertFalse(pattern.matches(wrongDouble));

        NBTTagCompound wrongByteValue = source.copy();
        wrongByteValue.setByte("byte", (byte) 2);
        assertFalse(pattern.matches(wrongByteValue));

        NBTTagCompound wrongShortValue = source.copy();
        wrongShortValue.setShort("short", (short) 3);
        assertFalse(pattern.matches(wrongShortValue));

        NBTTagCompound wrongIntValue = source.copy();
        wrongIntValue.setInteger("int", 4);
        assertFalse(pattern.matches(wrongIntValue));

        NBTTagCompound wrongLongValue = source.copy();
        wrongLongValue.setLong("long", 5L);
        assertFalse(pattern.matches(wrongLongValue));

        NBTTagCompound wrongFloatValue = source.copy();
        wrongFloatValue.setFloat("float", 6.0F);
        assertFalse(pattern.matches(wrongFloatValue));

        NBTTagCompound wrongDoubleValue = source.copy();
        wrongDoubleValue.setDouble("double", 7.0D);
        assertFalse(pattern.matches(wrongDoubleValue));

        NBTTagCompound wrongBytes = source.copy();
        wrongBytes.setByteArray("bytes", new byte[] {1, 2, 4});
        assertFalse(pattern.matches(wrongBytes));

        NBTTagCompound wrongInts = source.copy();
        wrongInts.setIntArray("ints", new int[] {7, 12});
        assertFalse(pattern.matches(wrongInts));

        NBTTagCompound wrongLongs = source.copy();
        wrongLongs.setTag("longs", new NBTTagLongArray(new long[] {13L, 18L}));
        assertFalse(pattern.matches(wrongLongs));

        NBTTagCompound wrongLongLength = source.copy();
        wrongLongLength.setTag("longs", new NBTTagLongArray(new long[] {13L}));
        assertFalse(pattern.matches(wrongLongLength));

        NBTTagCompound wrongLongType = source.copy();
        wrongLongType.setIntArray("longs", new int[] {13, 17});
        assertFalse(pattern.matches(wrongLongType));

        NBTTagCompound missingRequiredKey = source.copy();
        missingRequiredKey.removeTag("double");
        assertFalse(pattern.matches(missingRequiredKey));
    }

    @Test
    void compilationTakesDefensiveOwnershipOfNestedValuesAndArrays() {
        NBTTagCompound source = new NBTTagCompound();
        byte[] sourceBytes = {4, 5};
        source.setByteArray("bytes", sourceBytes);
        NBTTagCompound nested = new NBTTagCompound();
        nested.setInteger("value", 4);
        source.setTag("nested", nested);
        NbtPattern pattern = NbtPattern.compile(source);
        sourceBytes[0] = 9;
        source.getByteArray("bytes")[0] = 9;
        nested.setInteger("value", 5);

        NBTTagCompound originalValue = new NBTTagCompound();
        originalValue.setByteArray("bytes", new byte[] {4, 5});
        NBTTagCompound originalNested = new NBTTagCompound();
        originalNested.setInteger("value", 4);
        originalValue.setTag("nested", originalNested);
        assertTrue(pattern.matches(originalValue));
        assertFalse(pattern.matches(source));
    }

    @Test
    void compilationRejectsNodeDepthStringAndArrayBounds() {

        NBTTagCompound tooManyNodes = new NBTTagCompound();
        for (int index = 0; index < NbtPattern.MAX_NODES; index++) {
            tooManyNodes.setInteger("key" + index, index);
        }
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(tooManyNodes));

        NBTTagCompound deepRoot = new NBTTagCompound();
        NBTTagCompound cursor = deepRoot;
        for (int index = 0; index <= NbtPattern.MAX_DEPTH; index++) {
            NBTTagCompound child = new NBTTagCompound();
            cursor.setTag("child", child);
            cursor = child;
        }
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(deepRoot));

        NBTTagCompound oversizedLongArray = new NBTTagCompound();
        oversizedLongArray.setTag("longs", new NBTTagLongArray(
                new long[NbtPattern.MAX_SERIALIZED_BYTES / Long.BYTES]));
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(oversizedLongArray));

        NBTTagCompound oversizedString = new NBTTagCompound();
        oversizedString.setString("value", "x".repeat(NbtPattern.MAX_STRING_CHARS + 1));
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(oversizedString));

        NBTTagCompound oversizedKey = new NBTTagCompound();
        oversizedKey.setInteger("k".repeat(NbtPattern.MAX_STRING_CHARS + 1), 1);
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(oversizedKey));

        NBTTagCompound oversizedBytes = new NBTTagCompound();
        oversizedBytes.setByteArray("values", new byte[NbtPattern.MAX_ARRAY_ELEMENTS + 1]);
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(oversizedBytes));

        NBTTagCompound oversizedInts = new NBTTagCompound();
        oversizedInts.setIntArray("values", new int[NbtPattern.MAX_ARRAY_ELEMENTS + 1]);
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(oversizedInts));
    }

    @Test
    void nullCandidatesNeverMatch() {
        assertFalse(NbtPattern.compile(new NBTTagCompound()).matches(null));
        assertThrows(IllegalArgumentException.class, () -> NbtPattern.compile(null));
    }
}
