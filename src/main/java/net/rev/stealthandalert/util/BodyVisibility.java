package net.rev.stealthandalert.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.rev.stealthandalert.config.EntityAlertConditionConfigLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Shared by server detection and client surface prediction. World reads stay on the caller's main thread. */
public final class BodyVisibility {
    private BodyVisibility() {}

    private static final Map<Level, TickCache> CACHES = new WeakHashMap<>();
    private static final class TickCache {
        long tick;
        final Map<CacheKey, BodyExposure> values = new HashMap<>();
    }
    private record CacheKey(int observer, int target, Vec3 observerPosition, Vec3 look, Vec3 targetPosition,
                            float width, float height, float eyeHeight, double range) {}

    /** Tick-local reuse prevents the alert gate and awareness update from casting the same rays twice. */
    public static BodyExposure between(Mob observer, Entity target) {
        TickCache cache;
        synchronized (CACHES) {
            cache = CACHES.computeIfAbsent(observer.level(), ignored -> new TickCache());
        }
        // A level is owned by one main thread; client and integrated server do not lock each other's rays.
        long tick = observer.level().getGameTime();
        if (cache.tick != tick) {
            cache.values.clear();
            cache.tick = tick;
        }
        double range = target instanceof Player player ? CommonUtils.getCorrectedViewRange(observer, player)
                : EntityAlertConditionConfigLoader.get(observer.getType()).getViewRange();
        CacheKey key = new CacheKey(observer.getId(), target.getId(), observer.position(), observer.getViewVector(1),
                target.position(), target.getBbWidth(), target.getBbHeight(), target.getEyeHeight(), range);
        return cache.values.computeIfAbsent(key, ignored -> at(observer, target.position(),
                target.getBbWidth(), target.getBbHeight(), target.getEyeHeight(), range));
    }

    public static BodyExposure at(Mob observer, Vec3 feet, double width, double height,
                                  double eyeHeight, double range) {
        return new Context(observer, range).at(feet, width, height, eyeHeight);
    }

    /** Main-thread-only snapshot. It never escapes into geometry/GPU preparation workers. */
    public static final class Context {
        private final Mob observer;
        private final Level level;
        private final Vec3 position, eye;
        private final double rangeSqr;
        private final CommonUtils.FovContext fov;

        public Context(Mob observer, double range) {
            this.observer = observer;
            level = observer.level();
            position = observer.position();
            eye = observer.getEyePosition();
            rangeSqr = range * range;
            fov = new CommonUtils.FovContext(EntityAlertConditionConfigLoader.get(observer.getType()), observer.getViewVector(1));
        }

        public BodyExposure at(Vec3 feet, double width, double height, double eyeHeight) {
            if (position.distanceToSqr(feet) > rangeSqr) return BodyExposure.NONE;
            return sample(eye, feet, width, height, eyeHeight, point -> {
                Vec3 direction = point.subtract(eye);
                return (direction.lengthSqr() <= 1e-10 || fov.contains(direction.normalize()))
                        && level.clip(CommonUtils.getClipContext(observer, eye, point)).getType() == HitResult.Type.MISS;
            });
        }
    }

    /** Pure body sampling, also usable for geometric regression tests without a live world. */
    public static BodyExposure sample(Vec3 eye, Vec3 feet, double width, double height,
                                      double eyeHeight, Predicate<Vec3> visible) {
        Vec3 toward = feet.subtract(eye);
        Vec3 right = new Vec3(-toward.z, 0, toward.x);
        right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
        right = right.scale(width * 0.40);
        double head = part(feet.add(0, Math.min(eyeHeight, height * 0.95), 0), right, visible);
        double torso = part(feet.add(0, height * 0.55, 0), right, visible);
        double legs = part(feet.add(0, Math.min(0.25, height * 0.15), 0), right, visible);
        return new BodyExposure(head, torso, legs);
    }

    private static double part(Vec3 center, Vec3 side, Predicate<Vec3> isVisible) {
        int visible = 0;
        for (int offset = -1; offset <= 1; offset++) {
            Vec3 point = center.add(side.scale(offset));
            if (isVisible.test(point)) visible++;
        }
        return visible / 3.0;
    }
}
