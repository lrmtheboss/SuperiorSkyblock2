package com.bgsoftware.superiorskyblock.nms.v1_21_3;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.common.reflection.ReflectField;
import com.bgsoftware.common.reflection.ReflectMethod;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.ObjectsPool;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.collections.CompletableFutureList;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor;
import com.bgsoftware.superiorskyblock.nms.v1_21_3.world.PropertiesMapper;
import com.bgsoftware.superiorskyblock.tag.ByteTag;
import com.bgsoftware.superiorskyblock.tag.CompoundTag;
import com.bgsoftware.superiorskyblock.tag.IntArrayTag;
import com.bgsoftware.superiorskyblock.tag.StringTag;
import com.bgsoftware.superiorskyblock.tag.Tag;
import com.google.common.base.Suppliers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class NMSUtils {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private static final ReflectMethod<Void> SEND_PACKETS_TO_RELEVANT_PLAYERS = new ReflectMethod<>(
            ChunkHolder.class, 1, Packet.class, boolean.class);
    private static final ReflectField<Map<Long, ChunkHolder>> VISIBLE_CHUNKS = new ReflectField<>(
            ChunkMap.class, Map.class, Modifier.PUBLIC | Modifier.VOLATILE, 1);
    private static final ReflectMethod<LevelChunk> CHUNK_CACHE_SERVER_GET_CHUNK_IF_CACHED = new ReflectMethod<>(
            ServerChunkCache.class, "getChunkAtIfCachedImmediately", int.class, int.class);
    private static final ReflectField<PersistentEntitySectionManager<Entity>> SERVER_LEVEL_ENTITY_MANAGER = new ReflectField<>(
            ServerLevel.class, PersistentEntitySectionManager.class, Modifier.PUBLIC | Modifier.FINAL, 1);
    private static final ReflectField<SimpleRegionStorage> ENTITY_STORAGE_REGION_STORAGE = new ReflectField<>(
            EntityStorage.class, SimpleRegionStorage.class, Modifier.PRIVATE | Modifier.FINAL, 1);

    private static final List<CompletableFuture<Void>> PENDING_CHUNK_ACTIONS = new LinkedList<>();

    public static final ObjectsPool<ObjectsPools.Wrapper<BlockPos.MutableBlockPos>> BLOCK_POS_POOL =
            ObjectsPools.createNewPool(() -> new BlockPos.MutableBlockPos(0, 0, 0));

    private NMSUtils() {

    }

    @Nullable
    public static <T extends BlockEntity> T getBlockEntityAt(Location location, Class<T> type) {
        World bukkitWorld = location.getWorld();

        if (bukkitWorld == null)
            return null;

        ServerLevel serverLevel = ((CraftWorld) bukkitWorld).getHandle();

        try (ObjectsPools.Wrapper<BlockPos.MutableBlockPos> wrapper = NMSUtils.BLOCK_POS_POOL.obtain()) {
            BlockPos.MutableBlockPos blockPos = wrapper.getHandle();
            blockPos.set(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
            return !type.isInstance(blockEntity) ? null : type.cast(blockEntity);
        }
    }

    public static void runActionOnEntityChunks(Collection<ChunkPosition> chunksCoords,
                                               ChunkCallback chunkCallback) {
        runActionOnChunksInternal(chunksCoords, chunkCallback, unloadedChunks ->
                runActionOnUnloadedEntityChunks(unloadedChunks, chunkCallback));
    }

    public static void runActionOnChunks(Collection<ChunkPosition> chunksCoords,
                                         boolean saveChunks, ChunkCallback chunkCallback) {
        runActionOnChunksInternal(chunksCoords, chunkCallback, unloadedChunks ->
                runActionOnUnloadedChunks(unloadedChunks, saveChunks, chunkCallback));
    }

    private static void runActionOnChunksInternal(Collection<ChunkPosition> chunksCoords,
                                                  ChunkCallback chunkCallback,
                                                  Consumer<List<ChunkPosition>> onUnloadChunkAction) {
        List<ChunkPosition> unloadedChunks = new LinkedList<>();
        List<LevelChunk> loadedChunks = new LinkedList<>();

        chunksCoords.forEach(chunkPosition -> {
            ServerLevel serverLevel = ((CraftWorld) chunkPosition.getWorld()).getHandle();

            ChunkAccess chunkAccess;

            try {
                chunkAccess = serverLevel.getChunkIfLoadedImmediately(chunkPosition.getX(), chunkPosition.getZ());
            } catch (Throwable ex) {
                chunkAccess = serverLevel.getChunkIfLoaded(chunkPosition.getX(), chunkPosition.getZ());
            }

            if (chunkAccess instanceof LevelChunk levelChunk) {
                loadedChunks.add(levelChunk);
            } else {
                unloadedChunks.add(chunkPosition.copy());
            }
        });

        boolean hasUnloadedChunks = !unloadedChunks.isEmpty();

        if (!loadedChunks.isEmpty())
            runActionOnLoadedChunks(loadedChunks, chunkCallback);

        if (hasUnloadedChunks) {
            onUnloadChunkAction.accept(unloadedChunks);
        } else {
            chunkCallback.onFinish();
        }
    }

    private static void runActionOnLoadedChunks(Collection<LevelChunk> chunks, ChunkCallback chunkCallback) {
        chunks.forEach(chunkCallback::onLoadedChunk);
    }

    private static void runActionOnUnloadedChunks(Collection<ChunkPosition> chunks,
                                                  boolean saveChunks, ChunkCallback chunkCallback) {
        if (CHUNK_CACHE_SERVER_GET_CHUNK_IF_CACHED.isValid()) {
            Iterator<ChunkPosition> chunksIterator = chunks.iterator();
            while (chunksIterator.hasNext()) {
                ChunkPosition chunkPosition = chunksIterator.next();

                ServerLevel serverLevel = ((CraftWorld) chunkPosition.getWorld()).getHandle();

                LevelChunk cachedUnloadedChunk = serverLevel.getChunkSource().getChunkAtIfCachedImmediately(
                        chunkPosition.getX(), chunkPosition.getZ());

                if (cachedUnloadedChunk != null) {
                    chunkCallback.onLoadedChunk(cachedUnloadedChunk);
                    chunksIterator.remove();
                }
            }

            if (chunks.isEmpty()) {
                chunkCallback.onFinish();
                return;
            }
        }

        CompletableFuture<Void> pendingTask = new CompletableFuture<>();
        PENDING_CHUNK_ACTIONS.add(pendingTask);

        BukkitExecutor.createTask().runAsync(v -> {
            List<UnloadedChunkCompound> chunkCompounds = new LinkedList<>();

            chunks.forEach(chunkPosition -> {
                try {
                    ChunkPos chunkPos = new ChunkPos(chunkPosition.getX(), chunkPosition.getZ());

                    ServerLevel serverLevel = ((CraftWorld) chunkPosition.getWorld()).getHandle();
                    ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;

                    net.minecraft.nbt.CompoundTag chunkCompound = chunkMap.read(chunkPos).join().orElse(null);

                    if (chunkCompound == null) {
                        chunkCallback.onChunkNotExist(chunkPosition);
                        return;
                    }

                    net.minecraft.nbt.CompoundTag chunkDataCompound = chunkMap.upgradeChunkTag(serverLevel.getTypeKey(),
                            Suppliers.ofInstance(serverLevel.getDataStorage()), chunkCompound,
                            Optional.empty(), chunkPos, serverLevel);

                    UnloadedChunkCompound unloadedChunkCompound = new UnloadedChunkCompound(chunkPosition, chunkDataCompound);
                    chunkCallback.onUnloadedChunk(unloadedChunkCompound);

                    if (saveChunks)
                        chunkCompounds.add(unloadedChunkCompound);
                } catch (Exception error) {
                    Log.error(error, "An unexpected error occurred while interacting with unloaded chunk ", chunkPosition, ":");
                }
            });

            return chunkCompounds;
        }).runSync(chunkCompounds -> {
            chunkCompounds.forEach(unloadedChunkCompound -> {

                ServerLevel serverLevel = unloadedChunkCompound.serverLevel();
                ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;

                ChunkPos chunkPos = unloadedChunkCompound.chunkPos();

                try {
                    chunkMap.write(chunkPos, () -> unloadedChunkCompound.chunkCompound);
                } catch (Exception error) {
                    Log.error(error, "An unexpected error occurred while saving unloaded chunk ", unloadedChunkCompound.chunkPosition, ":");
                }
            });

            chunkCallback.onFinish();

            pendingTask.complete(null);
            PENDING_CHUNK_ACTIONS.remove(pendingTask);
        });
    }

    private static void runActionOnUnloadedEntityChunks(Collection<ChunkPosition> chunks, ChunkCallback chunkCallback) {
        if (SERVER_LEVEL_ENTITY_MANAGER.isValid()) {
            CompletableFutureList<Void> readChunksTasksList = new CompletableFutureList<>(-1);

            chunks.forEach(chunkPosition -> {
                ServerLevel serverLevel = ((CraftWorld) chunkPosition.getWorld()).getHandle();

                PersistentEntitySectionManager<Entity> entityManager = SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
                SimpleRegionStorage regionStorage = ENTITY_STORAGE_REGION_STORAGE.get(entityManager.permanentStorage);

                ChunkPos chunkPos = new ChunkPos(chunkPosition.getX(), chunkPosition.getZ());

                CompletableFuture<Void> readTask = new CompletableFuture<>();
                readChunksTasksList.add(readTask);

                regionStorage.read(chunkPos).whenComplete((entityDataOptional, error) -> {
                    if (error != null) {
                        readTask.completeExceptionally(error);
                    } else {
                        entityDataOptional.ifPresent(entityData -> {
                            UnloadedChunkCompound unloadedChunkCompound = new UnloadedChunkCompound(chunkPosition, entityData);
                            chunkCallback.onUnloadedChunk(unloadedChunkCompound);
                        });

                        readTask.complete(null);
                    }
                });
            });

            BukkitExecutor.createTask().runAsync(v -> {
                readChunksTasksList.forEachCompleted(p -> {
                    // Wait for all chunks to load.
                }, error -> {
                    Log.error(error, "An unexpected error occurred while interacting with unloaded chunk:");
                });
            }).runSync(v -> {
                chunkCallback.onFinish();
            });
        } else {
            BukkitExecutor.createTask().runAsync(v -> {
                chunks.forEach(chunkPosition -> {
                    ServerLevel serverLevel = ((CraftWorld) chunkPosition.getWorld()).getHandle();

                    try {
                        MoonriseRegionFileIO.RegionDataController regionDataController =
                                serverLevel.moonrise$getEntityChunkDataController();
                        int chunkX = chunkPosition.getX();
                        int chunkZ = chunkPosition.getZ();
                        MoonriseRegionFileIO.RegionDataController.ReadData readData =
                                regionDataController.readData(chunkX, chunkZ);
                        if (readData != null && readData.result() == MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.SYNC_READ) {
                            net.minecraft.nbt.CompoundTag entityData = readData.syncRead();
                            if (entityData != null) {
                                UnloadedChunkCompound unloadedChunkCompound = new UnloadedChunkCompound(chunkPosition, entityData);
                                chunkCallback.onUnloadedChunk(unloadedChunkCompound);
                            }
                        }
                    } catch (IOException error) {
                        Log.error(error, "An unexpected error occurred while interacting with unloaded chunk ", chunkPosition, ":");
                    }
                });
            }).runSync(v -> {
                chunkCallback.onFinish();
            });
        }
    }

    public static List<CompletableFuture<Void>> getPendingChunkActions() {
        return Collections.unmodifiableList(PENDING_CHUNK_ACTIONS);
    }

    public static ProtoChunk createProtoChunk(ChunkPos chunkPos, ServerLevel serverLevel) {
        return new ProtoChunk(chunkPos,
                UpgradeData.EMPTY,
                serverLevel,
                serverLevel.registryAccess().lookupOrThrow(Registries.BIOME),
                null);
    }

    public static void sendPacketToRelevantPlayers(ServerLevel serverLevel, int chunkX, int chunkZ, Packet<?> packet) {
        ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        ChunkHolder chunkHolder;

        try {
            chunkHolder = chunkMap.getVisibleChunkIfPresent(chunkPos.toLong());
        } catch (Throwable ex) {
            chunkHolder = VISIBLE_CHUNKS.get(chunkMap).get(chunkPos.toLong());
        }

        if (chunkHolder != null) {
            if (SEND_PACKETS_TO_RELEVANT_PLAYERS.isValid()) {
                SEND_PACKETS_TO_RELEVANT_PLAYERS.invoke(chunkHolder, packet, false);
            } else {
                chunkHolder.playerProvider.getPlayers(chunkPos, false).forEach(serverPlayer ->
                        serverPlayer.connection.send(packet));
            }
        }
    }

    public static void setBlock(LevelChunk levelChunk, BlockPos blockPos, int combinedId,
                                CompoundTag statesTag, CompoundTag tileEntity) {
        ServerLevel serverLevel = levelChunk.level;

        if (!isValidPosition(serverLevel, blockPos))
            return;

        BlockState blockState = Block.stateById(combinedId);

        if (statesTag != null) {
            for (Map.Entry<String, Tag<?>> entry : statesTag.entrySet()) {
                try {
                    // noinspection rawtypes
                    Property property = PropertiesMapper.getProperty(entry.getKey());
                    if (property != null) {
                        if (entry.getValue() instanceof ByteTag) {
                            // noinspection unchecked
                            blockState = blockState.setValue(property, ((ByteTag) entry.getValue()).getValue() == 1);
                        } else if (entry.getValue() instanceof IntArrayTag) {
                            int[] data = ((IntArrayTag) entry.getValue()).getValue();
                            // noinspection unchecked
                            blockState = blockState.setValue(property, data[0]);
                        } else if (entry.getValue() instanceof StringTag) {
                            String data = ((StringTag) entry.getValue()).getValue();
                            // noinspection unchecked
                            blockState = blockState.setValue(property, Enum.valueOf(property.getValueClass(), data));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if ((blockState.liquid() && plugin.getSettings().isLiquidUpdate()) ||
                blockState.getBlock() instanceof BedBlock) {
            serverLevel.setBlock(blockPos, blockState, 3);
            return;
        }

        int indexY = serverLevel.getSectionIndex(blockPos.getY());

        LevelChunkSection levelChunkSection = levelChunk.getSections()[indexY];

        int blockX = blockPos.getX() & 15;
        int blockY = blockPos.getY();
        int blockZ = blockPos.getZ() & 15;

        boolean isOriginallyChunkSectionEmpty = levelChunkSection.hasOnlyAir();

        levelChunkSection.setBlockState(blockX, blockY & 15, blockZ, blockState, false);

        levelChunk.heightmaps.get(Heightmap.Types.MOTION_BLOCKING).update(blockX, blockY, blockZ, blockState);
        levelChunk.heightmaps.get(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).update(blockX, blockY, blockZ, blockState);
        levelChunk.heightmaps.get(Heightmap.Types.OCEAN_FLOOR).update(blockX, blockY, blockZ, blockState);
        levelChunk.heightmaps.get(Heightmap.Types.WORLD_SURFACE).update(blockX, blockY, blockZ, blockState);

        levelChunk.markUnsaved();

        boolean isChunkSectionEmpty = levelChunkSection.hasOnlyAir();

        if (isOriginallyChunkSectionEmpty != isChunkSectionEmpty)
            serverLevel.getLightEngine().updateSectionStatus(blockPos, isChunkSectionEmpty);

        serverLevel.getLightEngine().checkBlock(blockPos);

        if (tileEntity != null) {
            net.minecraft.nbt.CompoundTag tileEntityCompound = (net.minecraft.nbt.CompoundTag) tileEntity.toNBT();
            if (tileEntityCompound != null) {
                tileEntityCompound.putInt("x", blockPos.getX());
                tileEntityCompound.putInt("y", blockPos.getY());
                tileEntityCompound.putInt("z", blockPos.getZ());
                BlockEntity worldBlockEntity = serverLevel.getBlockEntity(blockPos);
                if (worldBlockEntity != null)
                    worldBlockEntity.loadWithComponents(tileEntityCompound, MinecraftServer.getServer().registryAccess());
            }
        }
    }

    public static boolean isDoubleBlock(Block block, BlockState blockState) {
        return (block.defaultBlockState().is(BlockTags.SLABS) || block.defaultBlockState().is(BlockTags.WOODEN_SLABS)) &&
                blockState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
    }

    @Nullable
    public static LevelChunk getCraftChunkHandle(CraftChunk craftChunk) {
        ServerLevel serverLevel = craftChunk.getCraftWorld().getHandle();
        LevelChunk loadedChunk = serverLevel.getChunkIfLoaded(craftChunk.getX(), craftChunk.getZ());
        if (loadedChunk != null)
            return loadedChunk;

        return (LevelChunk) serverLevel.getChunk(craftChunk.getX(), craftChunk.getZ(), ChunkStatus.FULL, true);
    }

    public static void buildSurfaceForChunk(ServerLevel serverLevel, ChunkAccess chunkAccess) {
        CustomChunkGenerator customChunkGenerator = new CustomChunkGenerator(serverLevel,
                serverLevel.getChunkSource().getGenerator(), serverLevel.generator);

        ChunkStep surfaceStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.SURFACE);

        // Unsafe: we do not provide chunks cache, even tho it is required.
        // Should be fine in normal flow, as the only method that access the chunsk cache
        // is WorldGenRegion#getChunk. Mimic`ing the cache seems to result an error:
        // https://github.com/BG-Software-LLC/SuperiorSkyblock2/issues/2121
        WorldGenRegion region = new WorldGenRegion(serverLevel, null, surfaceStep, chunkAccess);

        customChunkGenerator.buildSurface(region,
                serverLevel.structureManager().forWorldGenRegion(region),
                serverLevel.getChunkSource().randomState(),
                chunkAccess);
    }

    public record UnloadedChunkCompound(ChunkPosition chunkPosition,
                                        net.minecraft.nbt.CompoundTag chunkCompound) {

        public ListTag getSections() {
            return chunkCompound.getList("sections", 10);
        }

        public ListTag getEntities() {
            return chunkCompound.getList("Entities", 10);
        }

        public void setSections(ListTag sectionsList) {
            chunkCompound.put("sections", sectionsList);
        }

        public void setEntities(ListTag entitiesList) {
            chunkCompound.put("entities", entitiesList);
        }

        public void setBlockEntities(ListTag blockEntitiesList) {
            chunkCompound.put("block_entities", blockEntitiesList);
        }

        public ServerLevel serverLevel() {
            return ((CraftWorld) chunkPosition.getWorld()).getHandle();
        }

        public ChunkPos chunkPos() {
            return new ChunkPos(chunkPosition.getX(), chunkPosition.getZ());
        }

    }

    private static boolean isValidPosition(ServerLevel serverLevel, BlockPos blockPos) {
        return blockPos.getX() >= -30000000 && blockPos.getZ() >= -30000000 &&
                blockPos.getX() < 30000000 && blockPos.getZ() < 30000000 &&
                blockPos.getY() >= serverLevel.getMinY() && blockPos.getY() < serverLevel.getMaxY();
    }

    public interface ChunkCallback {

        void onLoadedChunk(LevelChunk levelChunk);

        void onUnloadedChunk(UnloadedChunkCompound unloadedChunkCompound);

        default void onChunkNotExist(ChunkPosition chunkPosition) {

        }

        void onFinish();

    }

}
