package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class DoubleSidedGravityFloorResourceTest {

    @Test
    void blockItemAndThreeAxisVariantsUseTheDoubleSidedModel() throws IOException {
        JsonObject blockState = json("/assets/unigrav/blockstates/double_sided_gravity_floor.json");
        JsonObject variants = blockState.getAsJsonObject("variants");
        assertEquals(Set.of("axis=x", "axis=y", "axis=z"),
                variants.entrySet().stream().map(entry -> entry.getKey()).collect(Collectors.toSet()));
        for (var variant : variants.entrySet()) {
            assertEquals("unigrav:double_sided_gravity_floor", variant.getValue().getAsJsonObject()
                    .get("model").getAsString());
        }

        JsonObject item = json("/assets/unigrav/models/item/double_sided_gravity_floor.json");
        assertEquals("unigrav:block/double_sided_gravity_floor", item.get("parent").getAsString());

        JsonObject model = json("/assets/unigrav/models/block/double_sided_gravity_floor.json");
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals("minecraft:blocks/piston_top_normal", textures.get("top").getAsString());
        assertEquals("minecraft:blocks/piston_top_normal", textures.get("bottom").getAsString());
        assertEquals("minecraft:blocks/iron_block", textures.get("side").getAsString());
    }

    @Test
    void englishAndChineseNamesArePresent() throws IOException {
        assertNotNull(resourceLine("/assets/unigrav/lang/en_us.lang",
                "tile.unigrav.double_sided_gravity_floor.name=Double-Sided Gravity Floor"));
        assertNotNull(resourceLine("/assets/unigrav/lang/zh_cn.lang",
                "tile.unigrav.double_sided_gravity_floor.name=双面重力地板"));
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream stream = DoubleSidedGravityFloorResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing resource " + path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader).getAsJsonObject();
            }
        }
    }

    private static String resourceLine(String path, String expectedLine) throws IOException {
        try (InputStream stream = DoubleSidedGravityFloorResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing resource " + path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new BufferedReader(reader).lines()
                        .filter(line -> line.equals(expectedLine))
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}
