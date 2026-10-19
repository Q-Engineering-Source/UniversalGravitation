package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;

class DoubleSidedGravityFloorAxisTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void actualStateHasOnlyThreeAxesAndEveryLegacyMetadataKeepsItsAxis() {
        DoubleSidedGravityFloorBlock block = (DoubleSidedGravityFloorBlock) FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR;
        assertEquals(Set.of(DoubleSidedGravityFloorBlock.AXIS),
                Set.copyOf(block.getDefaultState().getPropertyKeys()));
        assertEquals(3, block.getBlockState().getValidStates().size());
        assertEquals(EnumFacing.Axis.Y, block.getDefaultState().getValue(DoubleSidedGravityFloorBlock.AXIS));
        for (EnumFacing oldFace : EnumFacing.values()) {
            IBlockState decoded = block.getStateFromMeta(oldFace.getIndex());
            assertEquals(oldFace.getAxis(), decoded.getValue(DoubleSidedGravityFloorBlock.AXIS));
            assertEquals(decoded, block.getStateFromMeta(block.getMetaFromState(decoded)));
            assertEquals(decoded, block.getStateForPlacement(null, null, oldFace, .5F, .5F, .5F, 0, null));
            assertEquals(List.of(
                    EnumFacing.getFacingFromAxis(EnumFacing.AxisDirection.NEGATIVE, oldFace.getAxis()),
                    EnumFacing.getFacingFromAxis(EnumFacing.AxisDirection.POSITIVE, oldFace.getAxis())),
                    block.emissionFaces(decoded));
            for (Rotation rotation : Rotation.values()) {
                assertEquals(rotation.rotate(oldFace).getAxis(),
                        block.withRotation(decoded, rotation).getValue(DoubleSidedGravityFloorBlock.AXIS));
            }
            for (Mirror mirror : Mirror.values()) {
                assertEquals(decoded, block.withMirror(decoded, mirror));
            }
        }
    }

    @Test
    void ordinaryFloorsRetainSixFacesAndExactMetadata() {
        for (DirectionalFloorBlock block : List.of(FloorBlocks.GRAVITY_FLOOR, FloorBlocks.MAGNETIC_FLOOR)) {
            assertEquals(Set.of(DirectionalFloorBlock.FACING), Set.copyOf(block.getDefaultState().getPropertyKeys()));
            assertEquals(6, block.getBlockState().getValidStates().size());
            for (EnumFacing facing : EnumFacing.values()) {
                IBlockState state = block.getStateFromMeta(facing.getIndex());
                assertEquals(facing, state.getValue(DirectionalFloorBlock.FACING));
                assertEquals(facing.getIndex(), block.getMetaFromState(state));
                assertEquals(List.of(facing), block.emissionFaces(state));
            }
        }
    }

    @Test
    void actualAxisStorageScannerAndIndexAttractBothSidesWithUnchangedProfile() {
        FloorFieldProfile profile = new FloorFieldProfile(32, 8, 16);
        for (EnumFacing.Axis axis : EnumFacing.Axis.values()) {
            ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
            sections[0] = new ExtendedBlockStorage(0, true);
            IBlockState state = FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DoubleSidedGravityFloorBlock.AXIS, axis);
            sections[0].set(4, 4, 4, state);
            FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> sections, profile);
            assertEquals(2, scanner.snapshot().size());
            FloorFieldIndex index = new FloorFieldIndex();
            index.replaceChunk(new ChunkKey(0, 0), new Object(), scanner.snapshot());
            for (EnumFacing face : FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.emissionFaces(state)) {
                for (double distance : new double[] {0, 4, 8, 12, 16}) {
                    double outward = .5 + distance;
                    Vec3 point = new Vec3(4.5 + face.getXOffset() * outward,
                            4.5 + face.getYOffset() * outward, 4.5 + face.getZOffset() * outward);
                    double strength = profile.strengthAt(distance);
                    Vec3 force = index.sample(point, 0).gravityAccel();
                    assertEquals(-face.getXOffset() * strength, force.x(), 1e-12);
                    assertEquals(-face.getYOffset() * strength, force.y(), 1e-12);
                    assertEquals(-face.getZOffset() * strength, force.z(), 1e-12);
                }
            }
            assertEquals(Vec3.ZERO, index.sample(new Vec3(4.5, 4.5, 4.5), 0).gravityAccel());
        }
    }
}
