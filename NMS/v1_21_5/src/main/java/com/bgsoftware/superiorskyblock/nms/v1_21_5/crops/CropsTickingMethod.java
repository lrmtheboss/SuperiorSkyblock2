package com.bgsoftware.superiorskyblock.nms.v1_21_5.crops;

import com.bgsoftware.common.reflection.ReflectField;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventType;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.core.key.types.MaterialKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public abstract class CropsTickingMethod {

    private static final ReflectField<RandomSource> SERVER_LEVEL_SIMPLE_RANDOM = new ReflectField<>(
            ServerLevel.class, RandomSource.class, "simpleRandom");

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private static final CropsTickingMethod INSTANCE = SERVER_LEVEL_SIMPLE_RANDOM.isValid() ?
            new PaperCropsTickingMethod() : new SpigotCropsTickingMethod();

    private static Set<Block> CROPS_TO_GROW_CACHE;

    static {
        plugin.getPluginEventsDispatcher().registerCallback(PluginEventType.SETTINGS_UPDATE_EVENT, CropsTickingMethod::onSettingsUpdate);
    }

    protected CropsTickingMethod() {

    }

    public static void register() {
        // Calls the static initializer which registers the callback.
    }

    public static void tick(LevelChunk levelChunk, int tickSpeed) {
        INSTANCE.doTick(levelChunk, tickSpeed);
    }

    protected abstract void doTick(LevelChunk levelChunk, int tickSpeed);

    private static void onSettingsUpdate() {
        CROPS_TO_GROW_CACHE = new HashSet<>();
        plugin.getSettings().getCropsToGrow().forEach(cropName -> {
            Key key = Keys.ofMaterialAndData(cropName);
            if (key instanceof MaterialKey materialKey) {
                Block block = CraftMagicNumbers.getBlock(materialKey.getMaterial());
                if (block != null && block.defaultBlockState().isRandomlyTicking())
                    CROPS_TO_GROW_CACHE.add(block);
            }
        });
    }

    private static class PaperCropsTickingMethod extends CropsTickingMethod {

        @Override
        protected void doTick(LevelChunk levelChunk, int tickSpeed) {
            ServerLevel serverLevel = levelChunk.level;
            LevelChunkSection[] sections = levelChunk.getSections();

            int minSection = serverLevel.getMinSectionY();
            ChunkPos chunkPos = levelChunk.getPos();
            int chunkOffsetX = chunkPos.x << 4;
            int chunkOffsetZ = chunkPos.z << 4;

            RandomSource simpleRandom = SERVER_LEVEL_SIMPLE_RANDOM.get(serverLevel);

            for (int sectionIndex = 0, sectionsLen = sections.length; sectionIndex < sectionsLen; sectionIndex++) {
                LevelChunkSection levelChunkSection = sections[sectionIndex];
                if (levelChunkSection == null || !levelChunkSection.isRandomlyTickingBlocks()) {
                    continue;
                }

                ca.spottedleaf.moonrise.common.list.ShortList tickList =
                        levelChunkSection.moonrise$getTickingBlockList();
                int tickingBlocks = tickList.size();
                if (tickingBlocks <= 0) {
                    continue;
                }

                PalettedContainer<BlockState> states = levelChunkSection.states;

                int offsetY = (sectionIndex + minSection) << 4;

                for (int i = 0; i < tickSpeed; ++i) {
                    int index = simpleRandom.nextInt() & 0xFFF;

                    if (index >= tickingBlocks) {
                        continue;
                    }

                    int location = (int) tickList.getRaw(index) & 0xFFFF;
                    BlockState blockState = states.get(location);
                    if (!CROPS_TO_GROW_CACHE.contains(blockState.getBlock()))
                        continue;

                    // do not use a mutable pos, as some random tick implementations store the input without calling immutable()!
                    BlockPos blockPos = new BlockPos((location & 15) | chunkOffsetX,
                            ((location >>> 8) & 15) | offsetY,
                            ((location >>> 4) & 15) | chunkOffsetZ);

                    blockState.randomTick(serverLevel, blockPos, simpleRandom);
                }
            }

        }

    }

    private static class SpigotCropsTickingMethod extends CropsTickingMethod {

        private static int random = ThreadLocalRandom.current().nextInt();

        @Override
        protected void doTick(LevelChunk levelChunk, int tickSpeed) {
            LevelChunkSection[] sections = levelChunk.getSections();
            ServerLevel serverLevel = levelChunk.level;

            ChunkPos chunkPos = levelChunk.getPos();
            int chunkOffsetX = chunkPos.x << 4;
            int chunkOffsetZ = chunkPos.z << 4;

            for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
                LevelChunkSection levelChunkSection = sections[sectionIndex];
                if (levelChunkSection == null || !levelChunkSection.isRandomlyTickingBlocks()) {
                    continue;
                }

                int sectionBottomY = serverLevel.getSectionYFromSectionIndex(sectionIndex) << 4;

                for (int i = 0; i < tickSpeed; i++) {
                    random = random * 3 + 1013904223;
                    int factor = random >> 2;
                    int x = factor & 15;
                    int z = factor >> 8 & 15;
                    int y = factor >> 16 & 15;
                    BlockState blockState = levelChunkSection.getBlockState(x, y, z);
                    if (blockState.isRandomlyTicking() && CROPS_TO_GROW_CACHE.contains(blockState.getBlock())) {
                        BlockPos blockPos = new BlockPos(x + chunkOffsetX, y + sectionBottomY, z + chunkOffsetZ);
                        blockState.randomTick(serverLevel, blockPos, serverLevel.getRandom());
                    }
                }
            }
        }
    }

}
