package net.rev.stealthandalert.util;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Client-owned block-change generation for projection/exposure cache invalidation. */
public final class VisionWorldRevision {
    private static final Map<Level, Changes> REVISIONS = new WeakHashMap<>();
    private static final class Changes {
        long next;
        final Map<Long, Long> chunks = new HashMap<>();
    }
    private VisionWorldRevision() {}
    public static long get(Level level, Vec3 center, double range) {
        Changes changes = REVISIONS.get(level);
        if (changes == null) return 0;
        long revision = 0;
        for (int x = (int) Math.floor((center.x - range - 1) / 16); x <= (int) Math.floor((center.x + range + 1) / 16); x++)
            for (int z = (int) Math.floor((center.z - range - 1) / 16); z <= (int) Math.floor((center.z + range + 1) / 16); z++)
                revision += changes.chunks.getOrDefault(key(x, z), 0L);
        return revision;
    }
    public static void changed(Level level, BlockPos position) {
        if (!level.isClientSide()) return;
        Changes changes = REVISIONS.computeIfAbsent(level, ignored -> new Changes());
        changes.chunks.put(key(position.getX() >> 4, position.getZ() >> 4), ++changes.next);
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
