package wi.unigrav.core.client.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.renderer.block.model.ModelRotation;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.Test;

class FloorBlockModelTest {

    @Test
    void blockstateRotationsMoveTheDistinguishedUpSurfaceToEveryStateNormal() throws IOException {
        assertStateNormals("gravity_floor");
        assertStateNormals("magnetic_floor");
    }

    @Test
    void modelsUseOneMechanicalSideAndDistinctBuiltInTopSurfaces() throws IOException {
        assertModelTextures("gravity_floor", "minecraft:blocks/piston_top_normal");
        assertModelTextures("magnetic_floor", "minecraft:blocks/piston_top_sticky");
    }

    @Test
    void inventoryModelsReferToTheirMatchingBlockModels() throws IOException {
        assertEquals("unigrav:block/gravity_floor", resource("models/item/gravity_floor.json")
                .get("parent").getAsString());
        assertEquals("unigrav:block/magnetic_floor", resource("models/item/magnetic_floor.json")
                .get("parent").getAsString());
    }

    private static void assertStateNormals(String blockId) throws IOException {
        JsonObject variants = resource("blockstates/" + blockId + ".json").getAsJsonObject("variants");
        assertEquals(6, variants.size());
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            String facingName = entry.getKey().substring("facing=".length());
            EnumFacing expectedNormal = EnumFacing.byName(facingName);
            assertNotNull(expectedNormal);

            JsonObject variant = entry.getValue().getAsJsonObject();
            assertEquals("unigrav:" + blockId, variant.get("model").getAsString());
            int x = rotationDegrees(variant, "x");
            int y = rotationDegrees(variant, "y");
            assertEquals(expectedNormal, ModelRotation.getModelRotation(x, y).rotate(EnumFacing.UP),
                    "top surface for " + entry.getKey());
        }
    }

    private static void assertModelTextures(String blockId, String expectedTopTexture) throws IOException {
        JsonObject model = resource("models/block/" + blockId + ".json");
        assertEquals("minecraft:block/cube", model.get("parent").getAsString());
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals("minecraft:blocks/iron_block", textures.get("side").getAsString());
        assertEquals(expectedTopTexture, textures.get("top").getAsString());

        JsonObject faces = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces");
        assertEquals("#top", faces.getAsJsonObject("up").get("texture").getAsString());
        for (String side : new String[] { "north", "south", "west", "east" }) {
            assertEquals("#side", faces.getAsJsonObject(side).get("texture").getAsString());
        }
    }

    private static int rotationDegrees(JsonObject variant, String axis) {
        return variant.has(axis) ? variant.get(axis).getAsInt() : 0;
    }

    private static JsonObject resource(String relativePath) throws IOException {
        String path = "/assets/unigrav/" + relativePath;
        try (InputStream stream = FloorBlockModelTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
