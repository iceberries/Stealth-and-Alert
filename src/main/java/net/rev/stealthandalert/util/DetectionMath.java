package net.rev.stealthandalert.util;

import net.rev.stealthandalert.attachment.AlertData;
import net.rev.stealthandalert.config.CommonConfigs;

/** The same awareness rate is used by real detection and the visualization. */
public final class DetectionMath {
    private DetectionMath() {}

    public static double awarenessRate(double distance, double visibility, int state, double exposure) {
        double threshold = StealthUtils.VISIBILITY_THRESHOLD;
        double t = Math.clamp((visibility - threshold) / (1 - threshold), 0, 1);
        double vis = Math.clamp((0.6 + 0.4 * Math.sqrt(t))
                * CommonConfigs.AWARENESS.increaseVisibilityFactor.get(), 0.1, 10);
        double dist = 1;
        if (distance <= 16) {
            dist = Math.clamp(Math.pow(8 / Math.clamp(distance, 0.4, 16), 2.5), 1, 300)
                    * CommonConfigs.AWARENESS.increaseDistanceFactor.get();
        }
        double stateMod = state == AlertData.SUSPICIOUS ? CommonConfigs.AWARENESS.increaseSuspiciousFactor.get()
                : state == AlertData.SEARCHING ? CommonConfigs.AWARENESS.increaseSearchingFactor.get() : 1;
        return CommonConfigs.AWARENESS.increaseBasicRate.get() * vis * dist * stateMod
                * Math.clamp(exposure, 0, 1);
    }

    public static double discoveryTicks(double rate, double awareness, int reaction, int targetState) {
        if (targetState >= AlertData.TRACKING) return 0;
        if (rate <= 0) return Double.POSITIVE_INFINITY;
        // The transition tick also performs the first awareness increment.
        return (targetState == AlertData.UNTRACKED ? Math.max(0, reaction) : 0)
                + Math.max(1, Math.ceil(Math.max(0, 100 - awareness) / rate));
    }

    /** A shared soft time scale, retaining differences beyond the former 200-tick cutoff. */
    public static float danger(double ticks) {
        if (!Double.isFinite(ticks)) return 0;
        return (float) (120.0 / (120.0 + Math.max(0, ticks)));
    }
}
