package wi.unigrav.core.floor;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;

/** Common-side registration of UG's concrete, six-facing floor blocks. */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class FloorBlocks {

    public static final DirectionalFloorBlock GRAVITY_FLOOR = create(
            "gravity_floor", DirectionalFloorBlock.Kind.GRAVITY);
    public static final DirectionalFloorBlock MAGNETIC_FLOOR = create(
            "magnetic_floor", DirectionalFloorBlock.Kind.MAGNETIC);

    private FloorBlocks() {
    }

    private static DirectionalFloorBlock create(String path, DirectionalFloorBlock.Kind kind) {
        DirectionalFloorBlock block = new DirectionalFloorBlock(kind);
        block.setRegistryName(Reference.MOD_ID, path);
        block.setTranslationKey(Reference.MOD_ID + "." + path);
        return block;
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().registerAll(GRAVITY_FLOOR, MAGNETIC_FLOOR);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().registerAll(itemBlock(GRAVITY_FLOOR), itemBlock(MAGNETIC_FLOOR));
    }

    private static ItemBlock itemBlock(Block block) {
        ItemBlock item = new ItemBlock(block);
        item.setRegistryName(block.getRegistryName());
        return item;
    }
}
