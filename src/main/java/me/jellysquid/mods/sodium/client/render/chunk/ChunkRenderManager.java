package me.jellysquid.mods.sodium.client.render.chunk;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.TranslucencyFramebuffer;
import me.jellysquid.mods.sodium.client.gl.util.GlFogHelper;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.cull.ChunkCuller;
import me.jellysquid.mods.sodium.client.render.chunk.cull.ChunkFaceFlags;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphCuller;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterator;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPassManager;
import me.jellysquid.mods.sodium.client.util.math.FrustumExtended;
import me.jellysquid.mods.sodium.client.world.ChunkStatusListener;
import me.jellysquid.mods.sodium.common.util.DirectionUtil;
import me.jellysquid.mods.sodium.common.util.IdTable;
import me.jellysquid.mods.sodium.common.util.collections.FutureDequeDrain;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import org.lwjgl.opengl.GL20;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

public class ChunkRenderManager<T extends ChunkGraphicsState> implements ChunkStatusListener {
    /**
     * The maximum distance a chunk can be from the player's camera in order to be eligible for blocking updates.
     */
    private static final double NEARBY_CHUNK_DISTANCE = Math.pow(48, 2.0);

    /**
     * The minimum distance the culling plane can be from the player's camera. This helps to prevent mathematical
     * errors that occur when the fog distance is less than 8 blocks in width, such as when using a blindness potion.
     */
    private static final float FOG_PLANE_MIN_DISTANCE = (float) Math.pow(8.0f, 2.0);

    /**
     * The distance past the fog's far plane at which to begin culling. Distance calculations use the center of each
     * chunk from the camera's position, and as such, special care is needed to ensure that the culling plane is pushed
     * back far enough. I'm sure there's a mathematical formula that should be used here in place of the constant,
     * but this value works fine in testing.
     */
    private static final float FOG_PLANE_OFFSET = 12.0f;

    private final ChunkBuilder<T> builder;
    private final ChunkRenderBackend<T> backend;

    private final Long2ObjectOpenHashMap<ChunkRenderColumn<T>> columns = new Long2ObjectOpenHashMap<>();
    private final IdTable<ChunkRenderContainer<T>> renders = new IdTable<>(16384);

    private final ObjectArrayFIFOQueue<ChunkRenderContainer<T>> importantRebuildQueue = new ObjectArrayFIFOQueue<>();
    private final ObjectArrayFIFOQueue<ChunkRenderContainer<T>> rebuildQueue = new ObjectArrayFIFOQueue<>();

    @SuppressWarnings("unchecked")
    private final ChunkRenderList<T>[] chunkRenderLists = new ChunkRenderList[BlockRenderPass.COUNT];
    private final ObjectList<ChunkRenderContainer<T>> tickableChunks = new ObjectArrayList<>();

    private final ObjectList<BlockEntity> visibleBlockEntities = new ObjectArrayList<>();

    private final SodiumWorldRenderer renderer;
    private final ClientWorld world;

    private final ChunkCuller culler;
    private final boolean useChunkFaceCulling;

    private float cameraX, cameraY, cameraZ;
    private boolean dirty;

    private int visibleChunkCount;

    private boolean useFogCulling;
    private double fogRenderCutoff;

    public ChunkRenderManager(SodiumWorldRenderer renderer, ChunkRenderBackend<T> backend, BlockRenderPassManager renderPassManager, ClientWorld world, int renderDistance) {
        this.backend = backend;
        this.renderer = renderer;
        this.world = world;

        this.builder = new ChunkBuilder<>(backend.getVertexType(), this.backend);
        this.builder.init(world, renderPassManager);

        this.dirty = true;

        for (int i = 0; i < this.chunkRenderLists.length; i++) {
            this.chunkRenderLists[i] = new ChunkRenderList<>();
        }

        this.culler = new ChunkGraphCuller(world, renderDistance);
        this.useChunkFaceCulling = SodiumClientMod.options().advanced.useChunkFaceCulling;
    }

    public void update(Camera camera, FrustumExtended frustum, int frame, boolean spectator) {
        this.reset();

        this.setup(camera);
        this.iterateChunks(camera, frustum, frame, spectator);

        this.dirty = false;
    }

    private void setup(Camera camera) {
        Vec3d cameraPos = camera.getPos();

        this.cameraX = (float) cameraPos.x;
        this.cameraY = (float) cameraPos.y;
        this.cameraZ = (float) cameraPos.z;

        this.useFogCulling = false;

        if (SodiumClientMod.options().advanced.useFogOcclusion) {
            float dist = GlFogHelper.getFogCutoff() + FOG_PLANE_OFFSET;

            if (dist != 0.0f) {
                this.useFogCulling = true;
                this.fogRenderCutoff = Math.max(FOG_PLANE_MIN_DISTANCE, dist * dist);
            }
        }
    }

    private void iterateChunks(Camera camera, FrustumExtended frustum, int frame, boolean spectator) {
        IntList list = this.culler.computeVisible(camera, frustum, frame, spectator);
        IntIterator it = list.iterator();

        while (it.hasNext()) {
            ChunkRenderContainer<T> render = this.renders.get(it.nextInt());

            this.addChunk(render);
        }
    }

    private void addChunk(ChunkRenderContainer<T> render) {
        if (render.needsRebuild() && render.canRebuild()) {
            if (render.needsImportantRebuild()) {
                this.importantRebuildQueue.enqueue(render);
            } else {
                this.rebuildQueue.enqueue(render);
            }
        }

        if (this.useFogCulling && render.getSquaredDistanceXZ(this.cameraX, this.cameraZ) >= this.fogRenderCutoff) {
            return;
        }

        if (!render.isEmpty()) {
            this.addChunkToRenderLists(render);
            this.addEntitiesToRenderLists(render);
        }
    }

    private void addChunkToRenderLists(ChunkRenderContainer<T> render) {
        int visibleFaces = this.computeVisibleFaces(render) & render.getFacesWithData();

        if (visibleFaces == 0) {
            return;
        }

        boolean added = false;
        T[] states = render.getGraphicsStates();

        for (int i = 0; i < states.length; i++) {
            T state = states[i];

            if (state != null) {
                ChunkRenderList<T> list = this.chunkRenderLists[i];
                list.add(state, visibleFaces);

                added = true;
            }
        }

        if (added) {
            if (render.isTickable()) {
                this.tickableChunks.add(render);
            }

            this.visibleChunkCount++;
        }
    }

    private int computeVisibleFaces(ChunkRenderContainer<T> render) {
        // If chunk face culling is disabled, render all faces
        if (!this.useChunkFaceCulling) {
            return ChunkFaceFlags.ALL;
        }

        ChunkRenderBounds bounds = render.getBounds();

        // Always render groups of vertices not belonging to any given face
        int visibleFaces = ChunkFaceFlags.UNASSIGNED;

        if (this.cameraY > bounds.y1) {
            visibleFaces |= ChunkFaceFlags.UP;
        }

        if (this.cameraY < bounds.y2) {
            visibleFaces |= ChunkFaceFlags.DOWN;
        }

        if (this.cameraX > bounds.x1) {
            visibleFaces |= ChunkFaceFlags.EAST;
        }

        if (this.cameraX < bounds.x2) {
            visibleFaces |= ChunkFaceFlags.WEST;
        }

        if (this.cameraZ > bounds.z1) {
            visibleFaces |= ChunkFaceFlags.SOUTH;
        }

        if (this.cameraZ < bounds.z2) {
            visibleFaces |= ChunkFaceFlags.NORTH;
        }

        return visibleFaces;
    }

    private void addEntitiesToRenderLists(ChunkRenderContainer<T> render) {
        Collection<BlockEntity> blockEntities = render.getData().getBlockEntities();

        if (!blockEntities.isEmpty()) {
            this.visibleBlockEntities.addAll(blockEntities);
        }
    }

    public ChunkRenderContainer<T> getRender(int x, int y, int z) {
        ChunkRenderColumn<T> column = this.columns.get(ChunkPos.toLong(x, z));

        if (column == null) {
            return null;
        }

        return column.getRender(y);
    }

    private void reset() {
        this.rebuildQueue.clear();
        this.importantRebuildQueue.clear();

        this.visibleBlockEntities.clear();

        for (ChunkRenderList<T> list : this.chunkRenderLists) {
            list.reset();
        }

        this.tickableChunks.clear();

        this.visibleChunkCount = 0;
    }

    public Collection<BlockEntity> getVisibleBlockEntities() {
        return this.visibleBlockEntities;
    }

    @Override
    public void onChunkAdded(int x, int z) {
        this.builder.onChunkStatusChanged(x, z);
        this.loadChunk(x, z);
    }

    @Override
    public void onChunkRemoved(int x, int z) {
        this.builder.onChunkStatusChanged(x, z);
        this.unloadChunk(x, z);
    }

    private void loadChunk(int x, int z) {
        ChunkRenderColumn<T> column = new ChunkRenderColumn<>(x, z);
        ChunkRenderColumn<T> prev;

        if ((prev = this.columns.put(ChunkPos.toLong(x, z), column)) != null) {
            this.unloadSections(prev);
        }

        this.connectNeighborColumns(column);
        this.loadSections(column);

        this.dirty = true;
    }

    private void unloadChunk(int x, int z) {
        ChunkRenderColumn<T> column = this.columns.remove(ChunkPos.toLong(x, z));

        if (column == null) {
            return;
        }

        this.disconnectNeighborColumns(column);
        this.unloadSections(column);

        this.dirty = true;
    }

    private void loadSections(ChunkRenderColumn<T> column) {
        int x = column.getX();
        int z = column.getZ();

        for (int y = 0; y < 16; y++) {
            ChunkRenderContainer<T> render = this.createChunkRender(column, x, y, z);
            column.setRender(y, render);

            this.culler.onSectionLoaded(x, y, z, render.getId());
        }
    }

    private void unloadSections(ChunkRenderColumn<T> column) {
        int x = column.getX();
        int z = column.getZ();

        for (int y = 0; y < 16; y++) {
            ChunkRenderContainer<T> render = column.getRender(y);

            if (render != null) {
                render.delete();

                this.renders.remove(render.getId());
            }

            this.culler.onSectionUnloaded(x, y, z);
        }
    }

    private void connectNeighborColumns(ChunkRenderColumn<T> column) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            ChunkRenderColumn<T> adj = this.getAdjacentColumn(column, dir);

            if (adj != null) {
                adj.setAdjacentColumn(dir.getOpposite(), column);
            }

            column.setAdjacentColumn(dir, adj);
        }
    }

    private void disconnectNeighborColumns(ChunkRenderColumn<T> column) {
        for (Direction dir : DirectionUtil.ALL_DIRECTIONS) {
            ChunkRenderColumn<T> adj = column.getAdjacentColumn(dir);

            if (adj != null) {
                adj.setAdjacentColumn(dir.getOpposite(), null);
            }

            column.setAdjacentColumn(dir, null);
        }
    }

    private ChunkRenderColumn<T> getAdjacentColumn(ChunkRenderColumn<T> column, Direction dir) {
        return this.getColumn(column.getX() + dir.getOffsetX(), column.getZ() + dir.getOffsetZ());
    }

    private ChunkRenderColumn<T> getColumn(int x, int z) {
        return this.columns.get(ChunkPos.toLong(x, z));
    }

    private ChunkRenderContainer<T> createChunkRender(ChunkRenderColumn<T> column, int x, int y, int z) {
        ChunkRenderContainer<T> render = new ChunkRenderContainer<>(this.backend, this.renderer, x, y, z, column);

        if (ChunkSection.isEmpty(this.world.getChunk(x, z).getSectionArray()[y])) {
            render.setData(ChunkRenderData.EMPTY);
        } else {
            render.scheduleRebuild(false);
        }

        render.setId(this.renders.add(render));

        return render;
    }

    public void renderLayer(MatrixStack matrixStack, BlockRenderPass pass, double x, double y, double z) {
        ChunkRenderList<T> chunkRenderList = this.chunkRenderLists[pass.ordinal()];
        ChunkRenderListIterator<T> iterator = chunkRenderList.iterator(pass.isTranslucent());

        if (pass.isTranslucent()) {
            Framebuffer mfb = this.renderer.getMainFramebuffer();
            mfb.endWrite();

            TranslucencyFramebuffer fb = this.renderer.translucentFramebuffer;
            fb.clear(MinecraftClient.IS_SYSTEM_MAC);
            fb.copyDepthFrom(mfb);

            fb.beginWrite(false);
            RenderSystem.depthFunc(GL20.GL_ALWAYS);
            this.backend.begin(matrixStack, pass.isTranslucent());

            GlStateManager.activeTexture(GL20.GL_TEXTURE5);
            GlStateManager.bindTexture (mfb.getDepthAttachment());

            this.backend.render(iterator, new ChunkCameraContext(x, y, z));

            this.backend.end(matrixStack);
            RenderSystem.depthFunc(GL20.GL_GREATER);
            fb.endWrite();

            mfb.copyDepthFrom(fb);
            mfb.beginWrite(false);
            this.compositeOIT(fb);
        } else {
            this.backend.begin(matrixStack, pass.isTranslucent());
            this.backend.render(iterator, new ChunkCameraContext(x, y, z));
            this.backend.end(matrixStack);
        }
    }

    public void compositeOIT(TranslucencyFramebuffer fb) {
        GlStateManager.disableDepthTest();
        this.renderer.translucencyProgram.bind();
        this.renderer.translucencyProgram.setup();

        GlStateManager.activeTexture(GL20.GL_TEXTURE3);
        GlStateManager.bindTexture(fb.getAccumAttachment());
        GlStateManager.activeTexture(GL20.GL_TEXTURE4);
        GlStateManager.bindTexture(fb.getRevealAttachment());

        GL20.glDrawArrays(GL20.GL_TRIANGLES, 0, 3);

        this.renderer.translucencyProgram.unbind();
        GlStateManager.enableDepthTest();
    }

    public void tickVisibleRenders() {
        for (ChunkRenderContainer<T> render : this.tickableChunks) {
            render.tick();
        }
    }

    public boolean isChunkVisible(int x, int y, int z) {
        return this.culler.isSectionVisible(x, y, z);
    }

    public void updateChunks() {
        Deque<CompletableFuture<ChunkBuildResult<T>>> futures = new ArrayDeque<>();

        int budget = this.builder.getSchedulingBudget();
        int submitted = 0;

        while (!this.importantRebuildQueue.isEmpty()) {
            ChunkRenderContainer<T> render = this.importantRebuildQueue.dequeue();

            // Do not allow distant chunks to block rendering
            if (!this.isChunkPrioritized(render)) {
                this.builder.deferRebuild(render);
            } else {
                futures.add(this.builder.scheduleRebuildTaskAsync(render));
            }

            this.dirty = true;
            submitted++;
        }

        while (submitted < budget && !this.rebuildQueue.isEmpty()) {
            ChunkRenderContainer<T> render = this.rebuildQueue.dequeue();

            this.builder.deferRebuild(render);
            submitted++;
        }

        this.dirty |= submitted > 0;

        // Try to complete some other work on the main thread while we wait for rebuilds to complete
        this.dirty |= this.builder.performPendingUploads();

        if (!futures.isEmpty()) {
            this.backend.upload(new FutureDequeDrain<>(futures));
        }
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void restoreChunks(LongCollection chunks) {
        LongIterator it = chunks.iterator();

        while (it.hasNext()) {
            long pos = it.nextLong();

            this.loadChunk(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos));
        }
    }

    public boolean isBuildComplete() {
        return this.builder.isBuildQueueEmpty();
    }

    public void setCameraPosition(double x, double y, double z) {
        this.builder.setCameraPosition(x, y, z);
    }

    public void destroy() {
        this.reset();

        for (ChunkRenderColumn<T> column : this.columns.values()) {
            this.unloadSections(column);
        }

        this.columns.clear();

        this.builder.stopWorkers();
    }

    public int getTotalSections() {
        return this.columns.size() * 16;
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        if (y < 0 || y >= 16) {
            return;
        }

        ChunkRenderContainer<T> render = this.getRender(x, y, z);

        if (render != null) {
            // Nearby chunks are always rendered immediately
            important = important || this.isChunkPrioritized(render);

            // Only enqueue chunks for updates during the next frame if it is visible and wasn't already dirty
            if (render.scheduleRebuild(important) && this.culler.isSectionVisible(x, y, z)) {
                (render.needsImportantRebuild() ? this.importantRebuildQueue : this.rebuildQueue)
                        .enqueue(render);
            }

            this.dirty = true;
        }
    }

    public boolean isChunkPrioritized(ChunkRenderContainer<T> render) {
        return render.getSquaredDistance(this.cameraX, this.cameraY, this.cameraZ) <= NEARBY_CHUNK_DISTANCE;
    }

    public int getVisibleChunkCount() {
        return this.visibleChunkCount;
    }

    public void onChunkRenderUpdates(int x, int y, int z, ChunkRenderData data) {
        this.culler.onSectionStateChanged(x, y, z, data.getOcclusionData());
    }
}
