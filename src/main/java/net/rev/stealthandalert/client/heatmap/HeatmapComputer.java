package net.rev.stealthandalert.client.heatmap;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.rev.stealthandalert.attachment.AlertData;
import net.rev.stealthandalert.attachment.ModAttachments;
import net.rev.stealthandalert.attribute.ModAttributes;
import net.rev.stealthandalert.config.CommonConfigs;
import net.rev.stealthandalert.config.EntityAlertConditionConfigLoader;
import net.rev.stealthandalert.util.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread resumable sampling, with physical results separate from awareness updates. */
@OnlyIn(Dist.CLIENT)
public final class HeatmapComputer {
    public static final float NEVER_DISCOVERED = 1_000_000;
    private static final long PHYSICAL_TTL = 10;
    public record HeatmapResult(ConeRaycaster.OccludedConeData cone, float[][] discoveryTicks, BodyExposure[][] exposure) {}
    private record GeometryKey(Vec3 position, Vec3 eye, Vec3 look, double range, long revision) {}
    private record ExposureKey(GeometryKey geometry, float width, float height, float eyeHeight, double range) {}
    private record Physical(GeometryKey geometry, ExposureKey exposureKey, long coneTick, long exposureTick,
                            ConeRaycaster.OccludedConeData cone, BodyExposure[][] parts, double[][] distances) {}
    private final Map<UUID, Physical> physical = new HashMap<>();
    private final Map<UUID, HeatmapResult> results = new HashMap<>();
    public Job begin(Mob mob, Player player) { return new Job(mob, player); }
    public HeatmapResult compute(Mob mob, Player player) {
        Job job = begin(mob, player);
        while (!job.advance(Long.MAX_VALUE)) { }
        return job.result();
    }

    public final class Job {
        private final Mob mob;
        private final Player player;
        private final UUID id;
        private final long started, revision;
        private final GeometryKey geometry;
        private final ExposureKey exposureKey;
        private final AABB bounds;
        private final BodyVisibility.Context visibilityContext;
        private final Map<Vec3, Prediction> duplicatePoints = new HashMap<>();
        private ConeRaycaster.Cursor cursor;
        private ConeRaycaster.OccludedConeData cone;
        private BodyExposure[][] parts;
        private double[][] distances;
        private int index;
        private HeatmapResult result;

        private Job(Mob mob, Player player) {
            this.mob = mob;
            this.player = player;
            id = mob.getUUID();
            started = mob.level().getGameTime();
            double range = CommonUtils.getCorrectedViewRange(mob, player);
            revision = VisionWorldRevision.get(mob.level(), mob.position(), range);
            geometry = new GeometryKey(mob.position(), mob.getEyePosition(1), mob.getViewVector(1), range, revision);
            double detectionRange = range;
            if (player.getAttributeValue(ModAttributes.VISIBILITY) <= StealthUtils.VISIBILITY_THRESHOLD + 0.0001) {
                int targetState = mob.getData(ModAttachments.ALERT_DATA).targetStates().getOrDefault(player.getUUID(), AlertData.UNTRACKED);
                detectionRange = Math.min(range, targetState < AlertData.TRACKING
                        ? CommonConfigs.DETECTION.minInvisibleDistance.get() : CommonConfigs.DETECTION.minInvisibleDistanceToTracking.get());
            }
            exposureKey = new ExposureKey(geometry, player.getBbWidth(), player.getBbHeight(), player.getEyeHeight(), detectionRange);
            bounds = mob.getBoundingBox();
            visibilityContext = new BodyVisibility.Context(mob, detectionRange);
            Physical previous = physical.get(id);
            boolean freshCone = previous != null && started - previous.coneTick < PHYSICAL_TTL;
            boolean freshExposure = previous != null && started - previous.exposureTick < PHYSICAL_TTL;
            if (freshCone && previous.geometry.equals(geometry)) cone = previous.cone;
            else cursor = ConeRaycaster.begin(mob, range);
            if (freshExposure && previous.exposureKey.equals(exposureKey)) {
                parts = previous.parts;
                distances = previous.distances;
                index = (ConeRaycaster.CONE_RAYS + 1) * (ConeRaycaster.PITCH_STEPS + 1);
            } else {
                parts = new BodyExposure[ConeRaycaster.CONE_RAYS + 1][ConeRaycaster.PITCH_STEPS + 1];
                distances = new double[ConeRaycaster.CONE_RAYS + 1][ConeRaycaster.PITCH_STEPS + 1];
            }
        }
        public Vec3 position() { return geometry.position; }
        public Vec3 look() { return geometry.look; }
        public double range() { return geometry.range; }
        public float width() { return exposureKey.width; }
        public float height() { return exposureKey.height; }
        public long revision() { return revision; }
        public boolean invalidated() {
            if (!mob.isAlive() || player.level() != mob.level() || revision != VisionWorldRevision.get(mob.level(), geometry.position, geometry.range)
                    || exposureKey.width != player.getBbWidth() || exposureKey.height != player.getBbHeight()
                    || exposureKey.eyeHeight != player.getEyeHeight()) return true;
            double expectedRange = geometry.range;
            if (player.getAttributeValue(ModAttributes.VISIBILITY) <= StealthUtils.VISIBILITY_THRESHOLD + 0.0001) {
                int state = mob.getData(ModAttachments.ALERT_DATA).targetStates().getOrDefault(player.getUUID(), AlertData.UNTRACKED);
                expectedRange = Math.min(expectedRange, state < AlertData.TRACKING
                        ? CommonConfigs.DETECTION.minInvisibleDistance.get() : CommonConfigs.DETECTION.minInvisibleDistanceToTracking.get());
            }
            return expectedRange != exposureKey.range;
        }

        /** Check budget after each projection ray or body point, not only after an entire mob. */
        public boolean advance(long deadline) {
            if (result != null) return true;
            if (cursor != null) {
                while (!cursor.done()) {
                    if (System.nanoTime() >= deadline) return false;
                    cursor.step();
                }
                cone = cursor.result();
                cursor = null;
            }
            int columns = ConeRaycaster.PITCH_STEPS + 1, total = (ConeRaycaster.CONE_RAYS + 1) * columns;
            while (index < total) {
                if (System.nanoTime() >= deadline) return false;
                int y = index / columns, p = index % columns;
                Vec3 point = cone.grid().get(y).get(p), normal = cone.normals().get(y).get(p);
                Vec3 feet = point.add(normal.scale(0.02));
                if (normal.y < 0.5) feet = feet.add(0, -exposureKey.height * 0.5, 0);
                Prediction prediction = duplicatePoints.get(feet);
                if (prediction == null) {
                    BodyExposure exposure = visibilityContext.at(feet, exposureKey.width, exposureKey.height, exposureKey.eyeHeight);
                    double distance = geometry.position.distanceTo(feet), half = exposureKey.width * 0.5;
                    if (distance <= exposureKey.range && bounds.intersects(new AABB(feet.x - half, feet.y, feet.z - half,
                            feet.x + half, feet.y + exposureKey.height, feet.z + half).inflate(0.4))) exposure = BodyExposure.FULL;
                    prediction = new Prediction(exposure, distance);
                    duplicatePoints.put(feet, prediction);
                }
                parts[y][p] = prediction.parts;
                distances[y][p] = prediction.distance;
                index++;
            }
            Physical previous = physical.get(id);
            long coneTick = previous != null && previous.cone == cone ? previous.coneTick : started;
            long exposureTick = previous != null && previous.parts == parts ? previous.exposureTick : started;
            physical.put(id, new Physical(geometry, exposureKey, coneTick, exposureTick, cone, parts, distances));
            var alert = mob.getData(ModAttachments.ALERT_DATA);
            int state = alert.targetStates().getOrDefault(player.getUUID(), AlertData.UNTRACKED);
            int reaction = alert.targetReactionTicks().getOrDefault(player.getUUID(), EntityAlertConditionConfigLoader.get(mob.getType()).getReactionTicks());
            float awareness = alert.targetAwareness().getOrDefault(player.getUUID(), 0F);
            double visibility = player.getAttributeValue(ModAttributes.VISIBILITY);
            float[][] ticks = new float[parts.length][columns];
            for (int y = 0; y < parts.length; y++) for (int p = 0; p < columns; p++) {
                double rate = DetectionMath.awarenessRate(distances[y][p], visibility, alert.state(), parts[y][p].fraction());
                ticks[y][p] = (float) Math.min(NEVER_DISCOVERED, parts[y][p].visible()
                        ? DetectionMath.discoveryTicks(rate, awareness, reaction, state) : NEVER_DISCOVERED);
            }
            result = new HeatmapResult(cone, ticks, parts);
            results.put(id, result);
            return true;
        }
        public HeatmapResult result() {
            if (result == null) throw new IllegalStateException("Sampling is not complete");
            return result;
        }
    }
    private record Prediction(BodyExposure parts, double distance) {}
    public HeatmapResult get(Mob mob) { return results.get(mob.getUUID()); }
    public void remove(UUID uuid) { results.remove(uuid); physical.remove(uuid); }
    public void clear() { results.clear(); physical.clear(); }
    public int getResultCount() { return results.size(); }
}
