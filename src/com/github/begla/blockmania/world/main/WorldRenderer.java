/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.begla.blockmania.world.main;

import com.github.begla.blockmania.audio.AudioManager;
import com.github.begla.blockmania.configuration.ConfigurationManager;
import com.github.begla.blockmania.datastructures.AABB;
import com.github.begla.blockmania.game.Blockmania;
import com.github.begla.blockmania.game.PortalManager;
import com.github.begla.blockmania.game.blueprints.BlockGrid;
import com.github.begla.blockmania.generators.ChunkGeneratorTerrain;
import com.github.begla.blockmania.rendering.interfaces.RenderableObject;
import com.github.begla.blockmania.rendering.manager.MobManager;
import com.github.begla.blockmania.rendering.manager.ShaderManager;
import com.github.begla.blockmania.rendering.manager.TextureManager;
import com.github.begla.blockmania.rendering.particles.BlockParticleEmitter;
import com.github.begla.blockmania.world.characters.Player;
import com.github.begla.blockmania.world.chunk.Chunk;
import com.github.begla.blockmania.world.chunk.ChunkMesh;
import com.github.begla.blockmania.world.chunk.ChunkUpdateManager;
import com.github.begla.blockmania.world.entity.Entity;
import com.github.begla.blockmania.world.horizon.Skysphere;
import com.github.begla.blockmania.world.interfaces.WorldProvider;
import com.github.begla.blockmania.world.physics.BulletPhysicsRenderer;
import com.github.begla.blockmania.world.simulators.GrowthSimulator;
import com.github.begla.blockmania.world.simulators.LiquidSimulator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.newdawn.slick.openal.SoundStore;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class WorldRenderer implements RenderableObject {

    private static final boolean BOUNDING_BOXES_ENABLED = (Boolean) ConfigurationManager.getInstance().getConfig().get("System.Debug.renderChunkBoundingBoxes");
    private static final boolean OCCLUSION_CULLING_ENABLED = (Boolean) ConfigurationManager.getInstance().getConfig().get("Graphics.OcclusionCulling.enabled");
    private static final double OCCLUSION_CULLING_DISTANCE_OFFSET = (Double) ConfigurationManager.getInstance().getConfig().get("Graphics.OcclusionCulling.distanceOffset");
    private static final long OCCLUSION_CULLING_GAP = (Long) ConfigurationManager.getInstance().getConfig().get("Graphics.OcclusionCulling.timeGap");

    /* VIEWING DISTANCE */
    private int _viewingDistance = 8;

    /* WORLD PROVIDER */
    private final WorldProvider _worldProvider;

    /* PLAYER */
    private Player _player;

    /* CHUNKS */
    private final ArrayList<Chunk> _chunksInProximity = new ArrayList<Chunk>();
    private final ArrayList<Chunk> _visibleChunks = new ArrayList<Chunk>();
    private int _chunkPosX, _chunkPosZ;
    /* CORE GAME OBJECTS */
    private final PortalManager _portalManager;
    private final MobManager _mobManager;

    /* PARTICLE EMITTERS */
    private final BlockParticleEmitter _blockParticleEmitter = new BlockParticleEmitter(this);

    /* HORIZON */
    private final Skysphere _skysphere;

    /* SIMULATORS */
    private final LiquidSimulator _liquidSimulator;
    private final GrowthSimulator _growthSimulator;

    /* WATER AND LAVA ANIMATION */
    private int _tick = 0;
    private int _tickTock = 0;
    private long _lastTick;

    /* UPDATING */
    private final ChunkUpdateManager _chunkUpdateManager;

    /* EVENTS */
    private final WorldTimeEventManager _worldTimeEventManager;

    /* PHYSICS */
    private final BulletPhysicsRenderer _bulletPhysicsRenderer;

    /* BLOCK GRID */
    private final BlockGrid _blockGrid;

    /* STATISTICS */
    private int _statVisibleTriangles = 0;
    private int _statOcclusionCulled, _statSubMeshCulled, _statEmpty;

    /* RENDERING */
    private boolean _occlusionQueryToggle = false;
    private long _occlusionLastUpdate = 0;

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     */
    public WorldRenderer(String title, String seed) {
        _worldProvider = new LocalWorldProvider(title, seed);
        _skysphere = new Skysphere(this);
        _chunkUpdateManager = new ChunkUpdateManager();
        _worldTimeEventManager = new WorldTimeEventManager(_worldProvider);
        _portalManager = new PortalManager(this);
        _mobManager = new MobManager(this);
        _blockGrid = new BlockGrid(this);
        _bulletPhysicsRenderer = new BulletPhysicsRenderer(this);

        _liquidSimulator = new LiquidSimulator(_worldProvider);
        _growthSimulator = new GrowthSimulator(_worldProvider);

        initTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @param force Forces the update
     * @return True if the list was changed
     */
    public boolean updateChunksInProximity(boolean force) {

        int newChunkPosX = calcPlayerChunkOffsetX();
        int newChunkPosZ = calcPlayerChunkOffsetZ();

        if (_chunkPosX != newChunkPosX || _chunkPosZ != newChunkPosZ || force) {

            _chunksInProximity.clear();

            for (int x = -(_viewingDistance / 2); x < (_viewingDistance / 2); x++) {
                for (int z = -(_viewingDistance / 2); z < (_viewingDistance / 2); z++) {
                    Chunk c = _worldProvider.getChunkProvider().loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                    _chunksInProximity.add(c);
                }
            }

            _chunkPosX = newChunkPosX;
            _chunkPosZ = newChunkPosZ;

            Collections.sort(_chunksInProximity);
            return true;
        }

        return false;
    }

    public boolean isInRange(Vector3f pos) {
        Vector3f dist = new Vector3f();
        dist.sub(_player.getPosition(), pos);

        float distLength = dist.length();

        return distLength < (_viewingDistance * 8);
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void initTimeEvents() {
        // SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.01, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Sunrise").playAsMusic(1.0f, 1.0f, false);
            }
        });

        // AFTERNOON
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.33, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Afternoon").playAsMusic(1.0f, 1.0f, false);
            }
        });

        // SUNSET
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.44, true) {
            @Override
            public void run() {
                SoundStore.get().setMusicVolume(0.2f);
                AudioManager.getInstance().getAudio("Sunset").playAsMusic(1.0f, 1.0f, false);
            }
        });
    }

    /**
     * Updates the currently visible chunks (in sight of the player).
     */
    public void updateVisibleChunks() {
        _visibleChunks.clear();
        _bulletPhysicsRenderer.resetChunks();

        boolean noMoreUpdates = false;
        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);

            for (int j = 0; j < Chunk.VERTICAL_SEGMENTS; j++) {
                if (c.isReadyForRendering()) {
                    if (c.getActiveSubMesh(j)._bulletMeshShape != null) {
                        Vector3f position = new Vector3f(c.getPosition());
                        position.x *= Chunk.CHUNK_DIMENSION_X;
                        position.y *= Chunk.CHUNK_DIMENSION_Y;
                        position.z *= Chunk.CHUNK_DIMENSION_Z;

                        _bulletPhysicsRenderer.addStaticChunk(position, c.getActiveSubMesh(j)._bulletMeshShape);
                    }
                }
            }

            if (isChunkVisible(c)) {
                _visibleChunks.add(c);

                if ((c.isDirty() || c.isLightDirty()) && !noMoreUpdates) {
                    if (!_chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT)) {
                        noMoreUpdates = true;
                        continue;
                    }
                }

                c.update();
            }
        }
    }

    /**
     * Renders the world.
     */
    public void render() {
        /* SKYSPHERE */
        _player.getActiveCamera().lookThroughNormalized();
        _skysphere.render();

        /* WORLD RENDERING */
        _player.getActiveCamera().lookThrough();

        updateChunksInProximity(false);
        updateVisibleChunks();

        _player.render();
        renderChunksAndEntities();

        /* PARTICLE EFFECTS */
        _blockParticleEmitter.render();
        _blockGrid.render();
    }


    /**
     * Renders all chunks that are currently in the player's field of view.
     */
    private void renderChunksAndEntities() {
        ShaderManager.getInstance().enableShader("chunk");

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        TextureManager.getInstance().bindTexture("custom_lava_still");
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        TextureManager.getInstance().bindTexture("custom_water_still");
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        TextureManager.getInstance().bindTexture("terrain");

        int daylight = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "daylight");
        int swimming = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "swimming");

        int lavaTexture = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "textureLava");
        int waterTexture = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "textureWater");
        int textureAtlas = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "textureAtlas");
        GL20.glUniform1i(lavaTexture, 1);
        GL20.glUniform1i(waterTexture, 2);
        GL20.glUniform1i(textureAtlas, 0);

        int tick = GL20.glGetUniformLocation(ShaderManager.getInstance().getShader("chunk"), "tick");

        boolean playerIsSwimming = _player.isHeadUnderWater();

        GL20.glUniform1f(tick, _tick);
        GL20.glUniform1f(daylight, getDaylight());
        GL20.glUniform1i(swimming, playerIsSwimming ? 1 : 0);

        int occlusionDistanceOffset = (int) (_visibleChunks.size() * OCCLUSION_CULLING_DISTANCE_OFFSET);

        _statOcclusionCulled = 0;
        _statSubMeshCulled = 0;
        _statEmpty = 0;
        _statVisibleTriangles = 0;

        /*
         * FIRST RENDER PASS: OPAQUE ELEMENTS
         */
        for (int i = 0; i < _visibleChunks.size(); i++) {
            Chunk c = _visibleChunks.get(i);

            if (OCCLUSION_CULLING_ENABLED) {
                ShaderManager.getInstance().enableShader(null);
                if (!_occlusionQueryToggle) {
                    c.applyOcclusionQueries();
                } else if (i > occlusionDistanceOffset) {
                    c.executeOcclusionQuery();
                } else {
                    c.resetOcclusionCulled();
                }
                ShaderManager.getInstance().enableShader("chunk");
            }

            GL11.glPushMatrix();
            GL11.glTranslatef(c.getPosition().x * Chunk.CHUNK_DIMENSION_X - _worldProvider.getRenderingReferencePoint().x, c.getPosition().y * Chunk.CHUNK_DIMENSION_Y - _worldProvider.getRenderingReferencePoint().y, c.getPosition().z * Chunk.CHUNK_DIMENSION_Z - _worldProvider.getRenderingReferencePoint().z);

            for (int j = 0; j < Chunk.VERTICAL_SEGMENTS; j++) {
                if (!c.isSubMeshOcclusionCulled(j)) {
                    if (isAABBVisible(c.getSubMeshAABB(j))) {
                        boolean rendered = c.render(ChunkMesh.RENDER_TYPE.OPAQUE, j);
                        _statEmpty += rendered ? 0 : 1;

                        if (rendered) {
                            // Chunk was rendered
                            c.setSubMeshCulled(j, false);
                        } else {
                            // Chunk was empty -> No second rendering pass needed
                            c.setSubMeshCulled(j, true);
                        }
                    } else {
                        c.setSubMeshCulled(j, true);
                        _statSubMeshCulled++;
                    }
                } else {
                    _statOcclusionCulled++;
                }
            }

            glPopMatrix();

            if (BOUNDING_BOXES_ENABLED) {
                ShaderManager.getInstance().enableShader(null);
                c.renderAABBs(false);
                ShaderManager.getInstance().enableShader("chunk");
            }
        }

        /*
         * SECOND RENDER PASS: BILLBOARDS
         */
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        for (int i = 0; i < _visibleChunks.size(); i++) {
            Chunk c = _visibleChunks.get(i);

            GL11.glPushMatrix();
            GL11.glTranslatef(c.getPosition().x * Chunk.CHUNK_DIMENSION_X - _worldProvider.getRenderingReferencePoint().x, c.getPosition().y * Chunk.CHUNK_DIMENSION_Y - _worldProvider.getRenderingReferencePoint().y, c.getPosition().z * Chunk.CHUNK_DIMENSION_Z - _worldProvider.getRenderingReferencePoint().z);

            for (int k = 0; k < Chunk.VERTICAL_SEGMENTS; k++) {
                if (!c.isSubMeshOcclusionCulled(k)) {
                    if (!c.isSubMeshCulled(k)) {
                        c.render(ChunkMesh.RENDER_TYPE.BILLBOARD_AND_TRANSLUCENT, k);
                    }
                }
            }

            glPopMatrix();
        }

        glDisable(GL11.GL_BLEND);


        _mobManager.renderAll();

        ShaderManager.getInstance().enableShader("chunk");
        TextureManager.getInstance().bindTexture("terrain");

        // Make sure the water surface is rendered if the player is swimming
        if (playerIsSwimming) {
            glDisable(GL11.GL_CULL_FACE);
        }

        /*
        * THIRD RENDER PASS: WATER AND ICE
        */
        for (int j = 0; j < 2; j++) {
            if (j == 0) {
                glColorMask(false, false, false, false);
            } else {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glColorMask(true, true, true, true);
            }


            for (int i = 0; i < _visibleChunks.size(); i++) {
                Chunk c = _visibleChunks.get(i);

                GL11.glPushMatrix();
                GL11.glTranslatef(c.getPosition().x * Chunk.CHUNK_DIMENSION_X - _worldProvider.getRenderingReferencePoint().x, c.getPosition().y * Chunk.CHUNK_DIMENSION_Y - _worldProvider.getRenderingReferencePoint().y, c.getPosition().z * Chunk.CHUNK_DIMENSION_Z - _worldProvider.getRenderingReferencePoint().z);

                for (int k = 0; k < Chunk.VERTICAL_SEGMENTS; k++) {
                    if (!c.isSubMeshOcclusionCulled(k)) {
                        if (!c.isSubMeshCulled(k)) {
                            c.render(ChunkMesh.RENDER_TYPE.WATER_AND_ICE, k);
                        }
                    }
                }

                glPopMatrix();
            }

            glDisable(GL_BLEND);
        }

        glEnable(GL11.GL_CULL_FACE);

        ShaderManager.getInstance().enableShader("block");
        _bulletPhysicsRenderer.render();

        ShaderManager.getInstance().enableShader(null);

        long now = Blockmania.getInstance().getTime();
        if (now - _occlusionLastUpdate > OCCLUSION_CULLING_GAP) {
            _occlusionQueryToggle = !_occlusionQueryToggle;
            _occlusionLastUpdate = now;
        }
    }

    public void update() {
        updateTick();

        _skysphere.update();
        _player.update();
        _mobManager.updateAll();

        _bulletPhysicsRenderer.update();

        // Update the particle emitters
        _blockParticleEmitter.update();

        // Free unused space
        _worldProvider.getChunkProvider().flushCache();

        // And finally fire any active events
        _worldTimeEventManager.fireWorldTimeEvents();

        /* SIMULATE! */
        simulate();
    }

    private void simulate() {
        _liquidSimulator.simulate();
        _growthSimulator.simulate();
    }

    /**
     * Performs and maintains tick-based logic. If the game is paused this logic is not executed
     * First effect: update the _tick variable that animation is based on
     * Secondary effect: Trigger spawning (via PortalManager) once every second
     * Tertiary effect: Trigger socializing (via MobManager) once every 10 seconds
     */
    private void updateTick() {
        // Update the animation tick
        _tick++;

        // This block is based on seconds or less frequent timings
        if (Blockmania.getInstance().getTime() - _lastTick >= 1000) {
            _tickTock++;
            _lastTick = Blockmania.getInstance().getTime();

            // PortalManager ticks for spawning once a second
            _portalManager.tickSpawn();


            // MobManager ticks for AI every 10 seconds
            if (_tickTock % 10 == 0) {
                _mobManager.tickAI();
            }
        }
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.CHUNK_DIMENSION_Y - 1; y >= 0; y--) {
            if (_worldProvider.getBlock(x, y, z) != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Chunk.CHUNK_DIMENSION_X);
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Chunk.CHUNK_DIMENSION_Z);
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(Player p) {
        if (_player != null) {
            _player.unregisterObserver(_chunkUpdateManager);
            _player.unregisterObserver(_bulletPhysicsRenderer);
            _player.unregisterObserver(_liquidSimulator);
            _player.unregisterObserver(_growthSimulator);
        }

        _player = p;
        _player.registerObserver(_chunkUpdateManager);
        _player.registerObserver(_bulletPhysicsRenderer);
        _player.registerObserver(_liquidSimulator);
        _player.registerObserver(_growthSimulator);

        _worldProvider.setRenderingReferencePoint(_player.getPosition());

        _player.setSpawningPoint(_worldProvider.nextSpawningPoint());
        _player.reset();
        _player.respawn();

        updateChunksInProximity(true);
    }

    /**
     * Creates the first Portal if it doesn't exist yet
     */
    public void initPortal() {
        if (!_portalManager.hasPortal()) {
            Vector3f loc = new Vector3f(_player.getPosition().x, _player.getPosition().y + 4, _player.getPosition().z);
            Blockmania.getInstance().getLogger().log(Level.INFO, "Portal location is" + loc);
            _worldProvider.setBlock((int) loc.x - 1, (int) loc.y, (int) loc.z, (byte) 30, false, true);
            _portalManager.addPortal(loc);
        }
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        _worldProvider.dispose();
        AudioManager.getInstance().stopAllSounds();
    }

    @Override
    public String toString() {
        return String.format("world (biome: %s, time: %.2f, sun: %.2f, cache: %d, cu: %.2fms, triangles: %d, vcs: %d, seed: \"%s\", title: \"%s\", ocul: %d, smcul: %d, ec: %d)", getActiveBiome(), _worldProvider.getTime(), _skysphere.getSunPosAngle(), _worldProvider.getChunkProvider().size(), _chunkUpdateManager.getAverageUpdateDuration(), _statVisibleTriangles, _visibleChunks.size(), _worldProvider.getSeed(), _worldProvider.getTitle(), _statOcclusionCulled, _statSubMeshCulled, _statEmpty);
    }

    public Player getPlayer() {
        return _player;
    }

    public boolean isAABBVisible(AABB aabb) {
        return _player.getActiveCamera().getViewFrustum().intersects(aabb);
    }

    public boolean isChunkVisible(Chunk c) {
        return _player.getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public boolean isEntityVisible(Entity e) {
        return _player.getActiveCamera().getViewFrustum().intersects(e.getAABB());
    }

    public float getDaylight() {
        return _skysphere.getDaylight();
    }

    public BlockParticleEmitter getBlockParticleEmitter() {
        return _blockParticleEmitter;
    }

    public ChunkGeneratorTerrain.BIOME_TYPE getActiveBiome() {
        return _worldProvider.getActiveBiome((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveHumidity() {
        return _worldProvider.getHumidityAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveTemperature() {
        return _worldProvider.getTemperatureAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public WorldProvider getWorldProvider() {
        return _worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return _blockGrid;
    }

    public MobManager getMobManager() {
        return _mobManager;
    }

    public BulletPhysicsRenderer getBulletPhysicsRenderer() {
        return _bulletPhysicsRenderer;
    }

    public int getTick() {
        return _tick;
    }

    public int getViewingDistance() {
        return _viewingDistance;
    }

    public void setViewingDistance(int distance) {
        _viewingDistance = distance;
        updateChunksInProximity(true);
        Blockmania.getInstance().resetOpenGLParameters();
    }

    public void standaloneGenerateChunks() {
        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);
            c.generateVBOs();

            if (c.isDirty() || c.isLightDirty()) {
                _chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
            }
        }
    }
}
