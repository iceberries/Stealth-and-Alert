package net.rev.stealthandalert.util;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.rev.stealthandalert.attachment.AlertData;
import net.rev.stealthandalert.attribute.ModAttributes;
import net.rev.stealthandalert.config.CommonConfigs;
import net.rev.stealthandalert.config.EntityAlertConditionConfigLoader;
import net.rev.stealthandalert.config.EntityAlertConditionSettings;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class StealthEngine {
    public record IndividualResult(float level, int reaction, int pState, int memory) {
    }

    public record GlobalResult(
            int state,
            Optional<Vec3> lkp,
            Optional<UUID> primaryTarget,
            int stateTicks,
            int patienceTicks,
            boolean isSeeingAnyone,
            boolean willFighting
    ) {
    }

    public static IndividualResult updateIndividual(
            Player player,
            Mob mob,
            int state,
            float currentLevel,
            int currentReaction,
            int currentPState,
            int currentMemory,
            boolean canSee
    ) {
        float nextLevel = currentLevel;
        int nextReaction = currentReaction;
        int nextPState = currentPState;
        int nextMemory = currentMemory;
        EntityAlertConditionSettings settings = EntityAlertConditionConfigLoader.get(mob.getType());

        if (!player.isAlive()) {
            return new IndividualResult(0F, settings.getReactionTicks(), AlertData.UNTRACKED, 0);
        }

        if (canSee) { // 看见了
            // 反应期：从UNTRACKED向AWARE迁徙
            if (currentPState == AlertData.UNTRACKED) {
                if (currentReaction > 0) {
                    nextReaction--;
                    nextMemory = Math.max(0, --currentMemory); // 反应期，记忆也应衰减
                } else {
                    nextPState = AlertData.AWARE;
                }
            }

            // 涨条期：在AWARE状态下累积
            if (nextPState == AlertData.AWARE) {
                nextReaction = 0;
                boolean touching = mob.getBoundingBox().intersects(player.getBoundingBox().inflate(0.4));
                double exposure = touching ? 1.0 : BodyVisibility.between(mob, player).fraction();
                double awarenessIncreaseRate = DetectionMath.awarenessRate(
                        Math.sqrt(mob.distanceToSqr(player)),
                        player.getAttributeValue(ModAttributes.VISIBILITY), state, exposure);
                nextLevel = (float) Math.min(100.0, currentLevel + awarenessIncreaseRate);
                if (nextLevel >= 100.0F) {
                    nextPState = AlertData.TRACKING;
                }
            }

            // 锁定期
            if (nextPState == AlertData.TRACKING) {
                nextLevel = 100.0F;
                nextReaction = 0;
                nextMemory = settings.getMemoryTicks();
            }
        } else { // 没看见
            // 回落
            // 如果当前目标处于 TRACKING 状态，则通过另一种方式来视线丢失视野后的计时
            if (currentPState == AlertData.TRACKING) {
                // 若处于 TRACKING 状态，则初始 currentReaction 必定为0
                if (nextReaction <= 0) {
                    nextReaction = settings.getTrackingTicks();
                    if (nextReaction <= 0) nextPState = AlertData.AWARE;
                } else {
                    nextReaction--;
                    if (nextReaction <= 0) {
                        nextPState = AlertData.AWARE;
                    }
                }
            } else {
                // 状态系数
                float stateModifier = 1F;
                if (state == AlertData.SUSPICIOUS) {
                    stateModifier = CommonConfigs.AWARENESS.decreaseSuspiciousFactor.get().floatValue();
                } else if (state == AlertData.SEARCHING) {
                    stateModifier = CommonConfigs.AWARENESS.decreaseSearchingFactor.get().floatValue();
                }

                float basicDecreaseRate = CommonConfigs.AWARENESS.decreaseBasicRate.get().floatValue();
                float awarenessDecreaseRate = basicDecreaseRate * stateModifier;
                nextLevel = Math.max(0.0F, currentLevel - awarenessDecreaseRate);
                nextMemory = Math.max(0, --currentMemory); // 只要看不见，记忆就开始衰减
            }

            // 阶梯式回落判定
            if (nextLevel <= 0.0F) {
                nextPState = AlertData.UNTRACKED;
                nextReaction = settings.getReactionTicks();
            }
        }
        return new IndividualResult(nextLevel, nextReaction, nextPState, nextMemory);
    }

    public static GlobalResult updateGlobalContext(
            Mob mob,
            AlertData oldData,
            Map<UUID, IndividualResult> currentResults,
            boolean anyTargetVisible
    ) {
        int nextState = oldData.state();
        int nextStateTicks = oldData.stateChangeTicks();
        int nextPatienceTicks = oldData.patienceTicks();
        Optional<Vec3> nextLkp = oldData.lastKnownPos();
        Optional<UUID> nextPrimary = oldData.primaryTarget();
        boolean willFighting = oldData.willFighting();
        EntityAlertConditionSettings settings = EntityAlertConditionConfigLoader.get(mob.getType());

        // 找出当前全场最高的警戒值
        float maxLevel = 0.0F;
        for (IndividualResult res : currentResults.values()) {
            maxLevel = Math.max(maxLevel, res.level());
        }

        // 主目标竞争：警戒值最高为优先，若同样高，距离最近为优先
        // 应保证在怪物变回IDLE之前，存储主目标的Optional中始终有值
        UUID topTargetUuid = null;
        UUID oldId = null;
        if (nextPrimary.isPresent()) {
            oldId = nextPrimary.get();
        }
        Player oldPlayer = null;

        if (oldId != null) {
            oldPlayer = mob.level().getPlayerByUUID(oldId);
            if (oldPlayer == null || !oldPlayer.isAlive()) {
                // 若主目标已死或退出，主目标直接清空
                nextPrimary = Optional.empty();
            }
        }

        if (nextPrimary.isPresent()) {
            if (currentResults.containsKey(oldId) && currentResults.get(oldId).level() >= maxLevel) {
                topTargetUuid = oldId; // 若主目标的警戒值仍然最高，保持之，并开始判断同样高的目标之间距离更近的

                for (Map.Entry<UUID, IndividualResult> entry : currentResults.entrySet()) {
                    UUID candidateId = entry.getKey();
                    if (candidateId.equals(oldId)) continue;
                    if (entry.getValue().level() >= maxLevel) {
                        Player candidatePlayer = mob.level().getPlayerByUUID(candidateId);
                        if (candidatePlayer != null &&
                                currentResults.get(candidateId).pState() >= AlertData.AWARE &&
                                StealthUtils.shouldArouseAlert(mob, candidatePlayer)) {
                            if (mob.distanceToSqr(candidatePlayer) < mob.distanceToSqr(oldPlayer)) {
                                topTargetUuid = candidateId;
                                oldPlayer = candidatePlayer;
                            }
                        }
                    }
                }
            }
        }

        // 若原主目标不再有最高警戒值，或没有主目标
        if (topTargetUuid == null && maxLevel > 0.0F && anyTargetVisible) {
            for (Map.Entry<UUID, IndividualResult> entry : currentResults.entrySet()) {
                if (entry.getValue().level() >= maxLevel) {
                    UUID currentCandidate = entry.getKey();
                    Player p = mob.level().getPlayerByUUID(currentCandidate);
                    if (p == null || !StealthUtils.shouldArouseAlert(mob, p) || entry.getValue().pState() < AlertData.AWARE)
                        continue;
                    if (topTargetUuid == null) {
                        topTargetUuid = currentCandidate;
                    } else {
                        Player winner = mob.level().getPlayerByUUID(topTargetUuid);
                        if (winner != null && mob.distanceToSqr(p) < mob.distanceToSqr(winner)) {
                            topTargetUuid = currentCandidate;
                        }
                    }
                }
            }

            if (topTargetUuid != null) {
                nextPrimary = Optional.of(topTargetUuid);
            } else {
                nextPrimary = Optional.empty();
            }
        }

        // A: 状态升级（看到人）
        if (anyTargetVisible) {
            // 只有当全场最高警戒值大于0，即怪物至少对看到的一个人反应过来后，才重置耐心值和状态切换计时器
            if (maxLevel > 0.0F) {
                nextPatienceTicks = settings.getPatienceTicks();
                if (!willFighting) {
                    nextStateTicks = 0;
                }
            }

            // 如果有人处于TRACKING状态，强制进入FIGHTING
            // 但在此之前，应有一个很短的计时，这个计时与红条动画时长相同
            boolean anyoneTracking = currentResults.values().stream()
                    .anyMatch(r -> r.pState() == AlertData.TRACKING);

            if (anyoneTracking) {
                if (nextState < AlertData.FIGHTING && !willFighting) {
                    nextState = AlertData.SEARCHING;
                    nextStateTicks = 10;
                    willFighting = true;
                }
            } else if (!willFighting) {
                if (maxLevel >= 50.0F && nextState < AlertData.SEARCHING) {
                    nextState = AlertData.SEARCHING;
                } else if (maxLevel > 0.0F && nextState < AlertData.SUSPICIOUS) {
                    nextState = AlertData.SUSPICIOUS;
                }
            }

            // 计时
            if (nextStateTicks > 0) {
                nextStateTicks--;
                if (nextStateTicks <= 0 && willFighting) {
                    nextState = AlertData.FIGHTING;
                    willFighting = false;
                }
            } else if (willFighting) {
                willFighting = false;
            }

            // LKP竞争更新
            // 只有当主目标存在时才会更新；如果主目标不存在了，旧的LKP也不会清空，直到状态变回IDLE
            if (nextPrimary.isPresent()) {
                Player primary = mob.level().getPlayerByUUID(nextPrimary.get());
                if (primary != null && StealthUtils.shouldArouseAlert(mob, primary)) {
                    nextLkp = Optional.of(primary.position());
                }
            }
        }

        // B: 状态降级（看不到人，开始阶梯回落）
        else {
            if (!willFighting) {
                // 如果怪物看不到人，且仍处于 FIGHTING 状态，
                // 则为了确保怪物在战斗中不轻易丢失锁定，为 FIGHTING 状态下的降级设定一个很短的计时
                if (nextState == AlertData.FIGHTING) {
                    if (nextStateTicks <= 0) {
                        nextStateTicks = settings.getTrackingTicks();
                    }
                    if (nextPrimary.isPresent() && oldData.targetStates().getOrDefault(nextPrimary.get(), AlertData.UNTRACKED) == AlertData.TRACKING) {
                        Player primary = mob.level().getPlayerByUUID(nextPrimary.get());
                        if (primary != null) {
                            nextLkp = Optional.of(primary.position());
                        }
                    }
                }
                if (maxLevel <= 0.0F) {
                    // 如果所有人警戒值都为空，开始计时降级
                    if (nextState == AlertData.SEARCHING) {
                        // 如果到了LKP附近或者耐心耗尽
                        boolean reachedLkp = nextLkp.isPresent() && mob.distanceToSqr(nextLkp.get()) < 4.0;
                        if (reachedLkp || --nextPatienceTicks <= 1) {
                            if (nextStateTicks <= 0) {
                                nextStateTicks = 600;
                            }
                        }
                    } else if (nextState == AlertData.SUSPICIOUS) {
                        // SUSPICIOUS状态直接回落
                        if (nextStateTicks <= 0) {
                            nextStateTicks = 300;
                        }
                    }
                }
            }
            // 统一计时
            if (nextStateTicks > 0) {
                nextStateTicks--;
                if (nextStateTicks <= 0) {
                    if (willFighting) {
                        nextState = AlertData.FIGHTING;
                        willFighting = false;
                    } else if (nextState == AlertData.FIGHTING) {
                        nextState = AlertData.SEARCHING;
                    } else {
                        nextState = AlertData.IDLE;
                        nextLkp = Optional.empty();
                        nextPrimary = Optional.empty();
                        nextPatienceTicks = settings.getPatienceTicks();
                    }
                }
            }
        }
        return new GlobalResult(nextState, nextLkp, nextPrimary, nextStateTicks, nextPatienceTicks, anyTargetVisible, willFighting);
    }
}
