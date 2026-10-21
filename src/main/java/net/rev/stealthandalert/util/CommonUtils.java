package net.rev.stealthandalert.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.rev.stealthandalert.attribute.ModAttributes;
import net.rev.stealthandalert.compat.SupportedMods;
import net.rev.stealthandalert.compat.ironsspellbooks.IronsSpellbooksCompat;
import net.rev.stealthandalert.component.ModDataComponents;
import net.rev.stealthandalert.config.CommonConfigs;
import net.rev.stealthandalert.config.EntityAlertConditionConfigLoader;
import net.rev.stealthandalert.config.EntityAlertConditionSettings;
import net.rev.stealthandalert.enchantment.ModEnchantmentEffects;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class CommonUtils {
    public static float getWeaponBaseDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers != null) {
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
                    return (float) entry.modifier().amount();
                }
            }
        }
        return 0.0F;
    }

    public static float getAssassinationTotalMultiplier(ItemStack stack) {
        Float baseMultiplier = stack.getOrDefault(ModDataComponents.ASSASSINATION_BASE_MULTIPLIER, 0F);
        MutableFloat enchantBonus = new MutableFloat(0F);
        EnchantmentHelper.runIterationOnItem(stack, (enchantment, level) -> {
            List<ConditionalEffect<LevelBasedValue>> effectsList = enchantment.value().effects().get(ModEnchantmentEffects.ADD_ASSASSINATION_MULTIPLIER.get());
            if (effectsList != null) {
                for (ConditionalEffect<LevelBasedValue> conditionalEffect : effectsList) {
                    float bonus = conditionalEffect.effect().calculate(level);
                    enchantBonus.add(bonus);
                }
            }
        });
        return baseMultiplier + enchantBonus.getValue();
    }

    public static float getAssassinationDamage(float baseDamage, float multiplier) {
        return baseDamage * multiplier;
    }

    public static float getAssassinationDamage(ItemStack stack) {
        float baseDamage = getWeaponBaseDamage(stack);
        if (baseDamage <= 0F) return 0F;
        float multiplier = getAssassinationTotalMultiplier(stack);
        return getAssassinationDamage(baseDamage, multiplier);
    }

    public static boolean isPlayerPet(Entity entity, Player player, boolean withGolem) {
        if (player == null) return false;
        if (SupportedMods.IRONS_SPELLBOOKS.isLoaded()) {
            UUID ownerUuid = IronsSpellbooksCompat.getOwnerUuid(entity);
            if (player.getUUID().equals(ownerUuid)) return true;
        }
        if (entity instanceof OwnableEntity ownable) {
            return player.getUUID().equals(ownable.getOwnerUUID());
        }
        if (entity instanceof AbstractHorse horse) {
            return player.getUUID().equals(horse.getOwnerUUID());
        }
        if (withGolem) {
            if (entity instanceof IronGolem golem) {
                return golem.isPlayerCreated();
            }
            if (entity instanceof SnowGolem) {
                return true;
            }
        }
        return false;
    }

    public static double calculateReduction(double visibility, double threshold, CommonConfigs.Detection.DetectionRangeReduction model) {
        double maxReduction = CommonConfigs.DETECTION.visibilityMaxDetectionRangeReductionPercentage.get();
        if (model == CommonConfigs.Detection.DetectionRangeReduction.LINEAR) {
            double percentage = Math.clamp((visibility - threshold) / (1.0 - threshold), 0.0, 1.0);
            maxReduction = Math.clamp(maxReduction, 0.0, 1.0);
            return maxReduction * (1.0 - percentage);
        } else if (model == CommonConfigs.Detection.DetectionRangeReduction.SQUARE_ROOT) {
            double linearPercent = Math.clamp((visibility - threshold) / (1.0 - threshold), 0.0, 1.0);
            double stealthDegree = 1.0 - linearPercent;
            double curvedStealth = Math.sqrt(stealthDegree);
            return maxReduction * curvedStealth;
        } else {
            // Smoothstep
            double linearPercent = Math.clamp((visibility - threshold) / (1.0 - threshold), 0.0, 1.0);
            double stealthDegree = 1.0 - linearPercent;
            double curvedStealth = stealthDegree * stealthDegree * (3.0 - 2.0 * stealthDegree);
            return maxReduction * curvedStealth;
        }
    }

    /**
     * 计算生物对指定玩家的“可见度修正后”的有效视距。
     * 玩家越隐蔽（可见度越低），有效视距越短；
     * 与 {@link #hasLineOfSight} 共用同一逻辑，保证检测与显示一致。
     */
    public static double getCorrectedViewRange(Mob observer, Player target) {
        EntityAlertConditionSettings settings = EntityAlertConditionConfigLoader.get(observer.getType());
        double maxDistance = settings.getViewRange();
        double visibility = target.getAttributeValue(ModAttributes.VISIBILITY);
        double threshold = StealthUtils.VISIBILITY_THRESHOLD;
        double actualReduction = calculateReduction(visibility, threshold,
                CommonConfigs.DETECTION.visibilityDetectionRangeReductionModel.get());
        return maxDistance * (1.0 - actualReduction);
    }

    // 视线检测：如果mob能看到target，则返回true
    public static boolean hasLineOfSight(Mob observer, Entity target) {
        return BodyVisibility.between(observer, target).visible();
    }

    public static boolean isFullyNaked(Player player) {
        for (ItemStack armor : player.getArmorSlots()) {
            if (!armor.isEmpty()) return false;
        }
        return true;
    }

    // 射线上下文获取
    public static ClipContext getClipContext(@NotNull Entity beginner, Vec3 start, Vec3 end) {
        return new ClipContext(
                start, end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                beginner
        ) {
            @Override
            public @NotNull VoxelShape getBlockShape(@NotNull BlockState blockState, @NotNull BlockGetter level, @NotNull BlockPos pos) {
                if (blockState.is(ModTags.Blocks.SEE_THROUGHS)) {
                    return Shapes.empty();
                }
                return super.getBlockShape(blockState, level, pos);
            }
        };
    }

    // FOV判定
    public static boolean isWithinFOV(Mob observer, Vec3 lookVec, Vec3 targetDir) {
        return isWithinFOV(EntityAlertConditionConfigLoader.get(observer.getType()), lookVec, targetDir);
    }

    public static boolean isWithinFOV(EntityAlertConditionSettings settings, Vec3 lookVec, Vec3 targetDir) {
        return new FovContext(settings, lookVec).contains(targetDir);
    }

    /** Observer constants are shared by all body samples in a prediction job. */
    public static final class FovContext {
        private final boolean vertical;
        private final Vec3 horizontal;
        private final double horizontalThreshold, pitch, maxUp, maxDown;

        public FovContext(EntityAlertConditionSettings settings, Vec3 look) {
            vertical = Math.abs(look.x) < 0.0001 && Math.abs(look.z) < 0.0001;
            horizontal = new Vec3(look.x, 0, look.z).normalize();
            horizontalThreshold = Math.cos(Math.toRadians(settings.getHorizontalFov()) / 2);
            pitch = Math.toDegrees(Math.asin(Math.clamp(look.y, -1, 1)));
            maxUp = settings.getMaxUpPitch();
            maxDown = settings.getMaxDownPitch();
        }

        public boolean contains(Vec3 direction) {
            if (!vertical && horizontal.dot(new Vec3(direction.x, 0, direction.z).normalize()) < horizontalThreshold)
                return false;
            double relativePitch = Math.toDegrees(Math.asin(Math.clamp(direction.y, -1, 1))) - pitch;
            return relativePitch >= -maxDown && relativePitch <= maxUp;
        }
    }
}
