package wi.unigrav.core.client.floor;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import wi.unigrav.Reference;
import wi.unigrav.core.floor.FloorBlocks;

/** Client-only item-model registration for the common-side floor blocks. */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID, value = Side.CLIENT)
public final class FloorBlockModels {

    private FloorBlockModels() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        register(FloorBlocks.GRAVITY_FLOOR);
        register(FloorBlocks.MAGNETIC_FLOOR);
        register(FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR);
    }

    private static void register(Block block) {
        ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(block.getRegistryName(), "inventory"));
    }
}
