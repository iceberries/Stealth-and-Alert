package net.rev.stealthandalert.client.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.rev.stealthandalert.StealthAndAlert;
import net.rev.stealthandalert.attachment.AlertData;
import net.rev.stealthandalert.attachment.ModAttachments;
import net.rev.stealthandalert.attribute.ModAttributes;
import net.rev.stealthandalert.client.heatmap.HeatmapComputer;
import net.rev.stealthandalert.config.ClientConfigs;
import net.rev.stealthandalert.util.CommonUtils;
import net.rev.stealthandalert.util.DetectionMath;
import net.rev.stealthandalert.util.ModTags;
import net.rev.stealthandalert.util.VisionWorldRevision;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Retains projected flashlight cones while fusing their surfaces and risk colors. */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = StealthAndAlert.MOD_ID, value = Dist.CLIENT)
public final class VisionConeRenderer {
    private static final HeatmapComputer COMPUTER = new HeatmapComputer();
    private static final Map<UUID, CachedCone> CACHE = new HashMap<>();
    private static final Map<UUID, HeatmapComputer.Job> SAMPLE_JOBS = new HashMap<>();
    private static final VisionFieldScene FIELD_BUILDER = new VisionFieldScene();
    private static final int MAX_COMPUTE_PER_TICK = 4;
    private static final long COMPUTE_BUDGET_NANOS = 3_000_000;
    private static final double OUTLINE_HALF_WIDTH = 0.10;
    private static final ExecutorService GEOMETRY_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Stealth-VisionUnion");
        thread.setDaemon(true);
        return thread;
    });
    private static Level cachedLevel;
    private static UUID cachedPlayer;
    private static long lastTick = Long.MIN_VALUE;
    private static long worldEpoch;
    private static boolean dirty;
    private static Future<BuiltScene> pending;
    private static VertexBuffer fillBuffer, beamBuffer, outlineBuffer;
    private static GpuVisionField gpuField;
    private static VisionFieldScene.Geometry uploadedGeometry;
    private static List<SurfaceVisionFusion.Edge> uploadedBoundary = List.of();
    private static Vec3 sceneReference;
    private static boolean gpuSurface, gpuDisabled, gpuAnnounced;
    private static int shaderProgram = -1;

    private record CachedCone(HeatmapComputer.HeatmapResult heat, long tick, Vec3 position, Vec3 look,
                              double visibility, float width, float height, int state, int targetState,
                              float awareness, int reaction, double range, long revision) {}
    private record BuiltScene(long epoch, Set<UUID> entities, List<HeatmapComputer.HeatmapResult> cones,
                              SurfaceVisionFusion.Mesh surface, VisionFieldScene.Data field) {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        UUID player = mc.player == null ? null : mc.player.getUUID();
        if (cachedLevel != mc.level || !java.util.Objects.equals(cachedPlayer, player)) {
            cachedLevel = mc.level;
            cachedPlayer = player;
            worldEpoch++;
            CACHE.clear();
            SAMPLE_JOBS.clear();
            COMPUTER.clear();
            lastTick = Long.MIN_VALUE;
            dirty = false;
            closeBuffers();
            gpuDisabled = false;
            gpuAnnounced = false;
            sceneReference = mc.player == null ? Vec3.ZERO : new Vec3(Math.floor(mc.player.getX()), Math.floor(mc.player.getY()), Math.floor(mc.player.getZ()));
            // Keep one geometry job in flight; its epoch prevents results from a previous world being installed.
        }
        if (mc.player == null || !ClientConfigs.HEATMAP.enable.get() || !mc.player.isCrouching()) {
            if (!CACHE.isEmpty() || !SAMPLE_JOBS.isEmpty()) {
                CACHE.clear();
                SAMPLE_JOBS.clear();
                COMPUTER.clear();
                worldEpoch++;
                dirty = false;
                lastTick = Long.MIN_VALUE;
                closeBuffers();
            }
            return;
        }
        if (!mc.options.hideGui && mc.level != null && lastTick != mc.level.getGameTime()) {
            if (sceneReference.distanceToSqr(mc.player.position()) > 256 * 256) {
                sceneReference = new Vec3(Math.floor(mc.player.getX()), Math.floor(mc.player.getY()), Math.floor(mc.player.getZ()));
                worldEpoch++;
                dirty = !CACHE.isEmpty();
            }
            lastTick = mc.level.getGameTime();
            updateCones(mc.level, mc.player, lastTick);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !mc.player.isCrouching()
                || !ClientConfigs.HEATMAP.enable.get()) return;
        ModShaders.ensureInit();
        var shader = ModShaders.frustumShader;
        if (shader == null || !shader.isValid()) return;
        if (shaderProgram != shader.getProgram()) {
            shaderProgram = shader.getProgram();
            closeBuffers();
            dirty = !CACHE.isEmpty();
        }
        if (pending != null && pending.isDone()) {
            try {
                BuiltScene scene = pending.get();
                if (scene.epoch == worldEpoch && scene.entities.equals(CACHE.keySet())) upload(scene);
            } catch (Exception e) {
                StealthAndAlert.LOGGER.warn("[VisionCone] geometry fusion failed", e);
                closeBuffers();
                gpuDisabled = true;
                dirty = true;
            }
            pending = null;
        }
        if (dirty && pending == null) {
            List<HeatmapComputer.HeatmapResult> cones = CACHE.values().stream().map(CachedCone::heat).toList();
            Vec3 reference = sceneReference;
            long epoch = worldEpoch;
            Set<UUID> entities = Set.copyOf(CACHE.keySet());
            boolean useGpu = !gpuDisabled && !Boolean.getBoolean("stealthandalert.vision.forceCpu");
            pending = GEOMETRY_WORKER.submit(() -> {
                List<SurfaceVisionFusion.Triangle> triangles = collectSurfaces(cones);
                VisionFieldScene.Data field = useGpu ? FIELD_BUILDER.build(triangles, reference) : null;
                return new BuiltScene(epoch, entities, cones, field == null ? SurfaceVisionFusion.fuseForDisplay(triangles, reference) : null, field);
            });
            dirty = false;
        }
        if (fillBuffer == null) return;
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f()
                .rotate(event.getCamera().rotation().conjugate(new org.joml.Quaternionf()))
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        try (var fieldBindings = GpuVisionField.bind(shader, gpuSurface ? gpuField : null)) {
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection);
            var debugY = shader.getUniform("DebugForceY");
            if (debugY != null && debugY.isValid()) debugY.setFloat(ClientConfigs.HEATMAP.debugForceY.get().floatValue());
            var opacity = shader.getUniform("MaxOpacity");
            if (opacity != null && opacity.isValid()) opacity.setFloat(ClientConfigs.HEATMAP.maxOpacity.get().floatValue());
            var colorMode = shader.getUniform("RiskColoring");
            VeilRenderSystem.setShader(shader);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            // Preserve the existing translucent additive flashlight appearance. Fused surfaces have no overdraw.
            try {
                if (colorMode != null && colorMode.isValid()) colorMode.setFloat(gpuSurface ? 3 : 1);
                GpuVisionField.draw(shader, fillBuffer);
                if (beamBuffer != null) {
                    if (colorMode != null && colorMode.isValid()) colorMode.setFloat(2);
                    GpuVisionField.draw(shader, beamBuffer);
                }
                if (outlineBuffer != null) {
                    if (colorMode != null && colorMode.isValid()) colorMode.setFloat(0);
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                    GpuVisionField.draw(shader, outlineBuffer);
                }
            } finally {
                VertexBuffer.unbind();
                ShaderProgram.unbind();
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            }
        }
    }

    private static void updateCones(Level level, Player player, long tick) {
        double range = ClientConfigs.HEATMAP.searchRange.get();
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(range),
                mob -> mob.isAlive() && shouldShowVision(mob));
        HashSet<UUID> active = new HashSet<>();
        for (Mob mob : mobs) active.add(mob.getUUID());
        SAMPLE_JOBS.keySet().retainAll(active);
        if (CACHE.keySet().removeIf(uuid -> {
            if (active.contains(uuid)) return false;
            COMPUTER.remove(uuid);
            return true;
        })) dirty = true;
        if (CACHE.isEmpty()) closeBuffers();
        // Oldest overdue entries first, preventing a stationary or newly visible mob from starving.
        mobs.sort(Comparator.comparing((Mob mob) -> !SAMPLE_JOBS.containsKey(mob.getUUID())).thenComparingLong((Mob mob) -> {
            CachedCone previous = CACHE.get(mob.getUUID());
            return previous == null ? Long.MIN_VALUE : previous.tick + interval(mob, player, previous);
        }).thenComparingDouble(mob -> mob.distanceToSqr(player)));
        int computed = 0;
        long deadline = System.nanoTime() + COMPUTE_BUDGET_NANOS;
        for (Mob mob : mobs) {
            if (System.nanoTime() >= deadline) break;
            CachedCone previous = CACHE.get(mob.getUUID());
            var data = mob.getData(ModAttachments.ALERT_DATA);
            int targetState = data.targetStates().getOrDefault(player.getUUID(), AlertData.UNTRACKED);
            float awareness = data.targetAwareness().getOrDefault(player.getUUID(), 0F);
            int reaction = data.targetReactionTicks().getOrDefault(player.getUUID(), -1);
            double visibility = player.getAttributeValue(ModAttributes.VISIBILITY);
            double correctedRange = CommonUtils.getCorrectedViewRange(mob, player);
            long revision = VisionWorldRevision.get(level, mob.position(), correctedRange);
            boolean attributesChanged = previous == null || previous.visibility != visibility
                    || previous.width != player.getBbWidth() || previous.height != player.getBbHeight()
                    || previous.state != data.state() || previous.targetState != targetState
                    || previous.awareness != awareness || previous.reaction != reaction || previous.range != correctedRange || previous.revision != revision;
            HeatmapComputer.Job job = SAMPLE_JOBS.get(mob.getUUID());
            if (job != null && job.invalidated()) { SAMPLE_JOBS.remove(mob.getUUID()); job = null; }
            if (job == null && !attributesChanged && tick - previous.tick < interval(mob, player, previous)) continue;
            if (job == null) { job = COMPUTER.begin(mob, player); SAMPLE_JOBS.put(mob.getUUID(), job); }
            if (!job.advance(deadline)) break;
            var heat = job.result();
            SAMPLE_JOBS.remove(mob.getUUID());
            CACHE.put(mob.getUUID(), new CachedCone(heat, tick, job.position(), job.look(), visibility,
                    job.width(), job.height(), data.state(), targetState, awareness, reaction, job.range(), job.revision()));
            if (previous == null || !sameHeat(previous.heat, heat)) dirty = true;
            computed++;
            if (computed >= MAX_COMPUTE_PER_TICK || System.nanoTime() >= deadline) break;
        }
    }

    private static boolean sameHeat(HeatmapComputer.HeatmapResult a, HeatmapComputer.HeatmapResult b) {
        return a.cone().equals(b.cone()) && Arrays.deepEquals(a.discoveryTicks(), b.discoveryTicks());
    }

    private static int interval(Mob mob, Player player, CachedCone previous) {
        var data = mob.getData(ModAttachments.ALERT_DATA);
        if (data.targetStates().getOrDefault(player.getUUID(), AlertData.UNTRACKED) >= AlertData.AWARE
                || data.state() >= AlertData.SUSPICIOUS) return 1;
        if (mob.getDeltaMovement().lengthSqr() > 0.0001
                || previous.position.distanceToSqr(mob.position()) > 0.0001
                || previous.look.distanceToSqr(mob.getViewVector(1)) > 0.0001) return 2;
        return 10;
    }

    private static boolean shouldShowVision(Mob mob) {
        return mob.getType().is(ModTags.Entities.SEEKERS) || mob instanceof Enemy
                || ClientMarkManager.isMarked(mob.getId());
    }

    private static SurfaceVisionFusion.Vertex sample(HeatmapComputer.HeatmapResult heat, int yaw, int pitch) {
        Vec3 point = heat.cone().grid().get(yaw).get(pitch);
        return new SurfaceVisionFusion.Vertex(point, DetectionMath.danger(heat.discoveryTicks()[yaw][pitch]),
                (float) Math.min(1, point.distanceTo(heat.cone().origin()) / Math.max(heat.cone().viewRange(), 0.001)));
    }

    private static List<SurfaceVisionFusion.Triangle> collectSurfaces(List<HeatmapComputer.HeatmapResult> cones) {
        List<SurfaceVisionFusion.Triangle> triangles = new ArrayList<>();
        for (var heat : cones) {
            var grid = heat.cone().grid();
            for (int yaw = 0; yaw + 1 < grid.size(); yaw++) {
                for (int pitch = 0; pitch + 1 < grid.get(yaw).size(); pitch++) {
                    var a = sample(heat, yaw, pitch);
                    var b = sample(heat, yaw + 1, pitch);
                    var c = sample(heat, yaw + 1, pitch + 1);
                    var d = sample(heat, yaw, pitch + 1);
                    triangles.add(new SurfaceVisionFusion.Triangle(a, b, c));
                    triangles.add(new SurfaceVisionFusion.Triangle(a, c, d));
                }
            }
        }
        return triangles;
    }

    private static void upload(BuiltScene scene) {
        if (scene.field != null) {
            if (gpuField == null) gpuField = new GpuVisionField(VisionConeRenderer::shaderResource);
            gpuField.upload(scene.field);
            if (uploadedGeometry != scene.field.geometry()) {
                BufferBuilder fill = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (var triangle : scene.field.geometry().fill()) {
                    addFieldVertex(fill, triangle.a()); addFieldVertex(fill, triangle.b()); addFieldVertex(fill, triangle.c());
                }
                fillBuffer = uploadBuffer(fillBuffer, fill.build());
                uploadedGeometry = scene.field.geometry();
            }
            gpuSurface = true;
        } else {
            BufferBuilder fill = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var triangle : scene.surface.triangles()) {
                addVertex(fill, triangle.a()); addVertex(fill, triangle.b()); addVertex(fill, triangle.c());
            }
            fillBuffer = uploadBuffer(fillBuffer, fill.build());
            uploadedGeometry = null;
            gpuSurface = false;
        }
        BufferBuilder beam = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        // Keep the head-to-nearest-ring beams, which are not coplanar ground surface coverage.
        for (var heat : scene.cones) {
            for (int yaw = 0; yaw + 1 < heat.cone().grid().size(); yaw++) {
                var a = sample(heat, yaw, 0);
                var b = sample(heat, yaw + 1, 0);
                addVertex(beam, new SurfaceVisionFusion.Vertex(heat.cone().origin(), Math.max(a.danger(), b.danger()), 0));
                addVertex(beam, a);
                addVertex(beam, b);
            }
        }
        beamBuffer = uploadBuffer(beamBuffer, beam.build());
        List<SurfaceVisionFusion.Edge> boundary = scene.field != null ? scene.field.geometry().union().boundary() : scene.surface.boundary();
        if (!boundary.equals(uploadedBoundary)) {
            BufferBuilder outline = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var edge : boundary) addOutlineQuad(outline, edge);
            outlineBuffer = uploadBuffer(outlineBuffer, outline.build());
            uploadedBoundary = boundary;
        }
        if (gpuSurface && !gpuAnnounced) {
            gpuAnnounced = true;
            int red = 0, yellow = 0, green = 0;
            float minimum = HeatmapComputer.NEVER_DISCOVERED, maximum = 0;
            for (var heat : scene.cones) for (float[] row : heat.discoveryTicks()) for (float ticks : row) {
                if (ticks <= 200) red++; else if (ticks <= 600) yellow++; else green++;
                minimum = Math.min(minimum, ticks); maximum = Math.max(maximum, ticks);
            }
            StealthAndAlert.LOGGER.debug("[VisionCone] GPU field active: {} planes, {} sources, {} coverage triangles, atlas {}x{}",
                    scene.field.geometry().tiles().size(), scene.field.sources().length / 12, scene.field.geometry().fill().size(),
                    scene.field.geometry().width(), scene.field.geometry().height());
            StealthAndAlert.LOGGER.debug("[VisionCone] Discovery samples: red={}, yellow={}, green={}, ticks={}..{}",
                    red, yellow, green, minimum, maximum);
        }
    }

    private static String shaderResource(String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(StealthAndAlert.MOD_ID, "pinwheel/shaders/" + path);
        try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).open()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void addFieldVertex(BufferBuilder builder, VisionFieldScene.DrawVertex vertex) {
        Vec3 p = vertex.position();
        builder.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(vertex.u(), vertex.v())
                .setColor(0, 0, vertex.plane() & 255, (vertex.plane() >>> 8) & 255);
    }

    private static void addVertex(BufferBuilder builder, SurfaceVisionFusion.Vertex vertex) {
        Vec3 p = vertex.position();
        // Send scalar risk, not pre-mixed RGB. Fragment mapping preserves bright yellow between red and green.
        builder.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(0, 0).setColor(
                Math.round(vertex.danger() * 255), Math.round(vertex.distanceRatio() * 255), 0, 255);
    }

    private static void addOutlineQuad(BufferBuilder builder, SurfaceVisionFusion.Edge edge) {
        Vec3 direction = edge.b().subtract(edge.a());
        if (direction.lengthSqr() < 1e-10) return;
        Vec3 offset = direction.normalize().cross(edge.normal()).normalize().scale(OUTLINE_HALF_WIDTH);
        Vec3 a = edge.a().add(offset), b = edge.a().subtract(offset);
        Vec3 c = edge.b().subtract(offset), d = edge.b().add(offset);
        for (Vec3 p : List.of(a, b, c, a, c, d)) {
            builder.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(0, 0).setColor(70, 70, 70, 255);
        }
    }

    private static VertexBuffer uploadBuffer(VertexBuffer previous, MeshData mesh) {
        if (mesh == null) {
            if (previous != null) previous.close();
            return null;
        }
        VertexBuffer buffer = previous != null ? previous : new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        try {
            buffer.bind();
            buffer.upload(mesh);
            return buffer;
        } catch (RuntimeException e) {
            if (previous == null) buffer.close();
            throw e;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static void closeBuffers() {
        if (fillBuffer != null) { fillBuffer.close(); fillBuffer = null; }
        if (beamBuffer != null) { beamBuffer.close(); beamBuffer = null; }
        if (outlineBuffer != null) { outlineBuffer.close(); outlineBuffer = null; }
        if (gpuField != null) { gpuField.close(); gpuField = null; }
        uploadedGeometry = null;
        uploadedBoundary = List.of();
        gpuSurface = false;
    }
}
