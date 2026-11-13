package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.EnumSet;

/**
 * Block breaking toward a player / sound.
 *
 *  1. FIRST the zombie checks whether a walkable path to its goal exists. If one does, it just walks and
 *     breaks nothing.
 *  2. Only when there is NO path (and it is stuck against something) does it dig toward the goal.
 *
 * What counts as "in the way" is decided from the REAL collision shapes, like a player moving: a block only
 * blocks when its shape physically hits the zombie's body on the way forward. A vertical slab (or any partial
 * block) the zombie can pass by is never broken, a low step it walks over is never broken, a 1-block ledge it
 * can leap onto is left to WallHopGoal.
 *
 * Every zombie digs at base speed; an ENRAGED zombie (heard a shout) digs 75% faster. Respects mobGriefing.
 * Holds no goal flags.
 */
public class RageBreakGoal extends Goal {
    private static final int SAMPLE_TICKS = 5;
    private static final double STEP_HEIGHT = 0.6;
    private static final double PROBE = 0.9;

    private final Mob mob;
    private final PerceptionGoal perception;

    private int ticks;
    private Vec3 sampleAt;
    private int stuckSamples;
    private int pathCheckAt = -1000;
    private boolean pathExists = true;

    private BlockPos breaking;
    private double progress;
    private int lastStage = -1;
    private int chainUntil;

    public RageBreakGoal(Mob mob, PerceptionGoal perception) {
        this.mob = mob;
        this.perception = perception;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override public boolean canUse() { return true; }
    @Override public boolean canContinueToUse() { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    private Vec3 goalPoint() {
        LivingEntity target = mob.getTarget();
        if (target != null) return target.position();
        return perception.hasInvestigation() ? perception.approachPos() : null;
    }

    private boolean allowed(Level level) {
        boolean enabled = perception.isEnraged()
                ? ZombieAIConfig.RAGE_BREAK_ENABLED.get() || ZombieAIConfig.BREAK_WHEN_NO_PATH.get()
                : ZombieAIConfig.BREAK_WHEN_NO_PATH.get();
        return enabled && ForgeEventFactory.getMobGriefingEvent(level, mob);
    }

    @Override
    public void tick() {
        if (!mob.isAlive() || mob.level().isClientSide()) return;
        ticks++;
        Level level = mob.level();

        Vec3 goal = goalPoint();
        if (goal == null || !allowed(level)) {
            cancel();
            stuckSamples = 0;
            sampleAt = null;
            return;
        }
        if (breaking != null) {
            perception.markBreaking(true);
            continueBreaking(level);
            return;
        }
        perception.markBreaking(false);
        if (ticks % SAMPLE_TICKS != 0) return;

        Vec3 here = mob.position();
        if (sampleAt == null) {
            sampleAt = here;
            return;
        }
        double mx = here.x - sampleAt.x;
        double mz = here.z - sampleAt.z;
        sampleAt = here;
        double hx = goal.x - here.x;
        double hz = goal.z - here.z;
        boolean chaining = ticks <= chainUntil;
        boolean moving = mx * mx + mz * mz > 0.08 * 0.08;
        if (!chaining && (moving || hx * hx + hz * hz < 1.5 * 1.5)) {
            stuckSamples = 0;
            return;
        }
        if (!chaining && ++stuckSamples < 2) return;   // ~10 ticks standing still
        stuckSamples = 0;

        // STEP 1: is there a way to walk there? Then walk (checked at most every 10 ticks).
        if (!hasPath(level, goal)) {
            BlockPos blocker = findBlocker(level, hx, hz);
            if (blocker != null) {
                breaking = blocker;
                progress = 0.0;
                lastStage = -1;
                perception.markBreaking(true);
            }
        }
    }

    private boolean hasPath(Level level, Vec3 goal) {
        if (ticks - pathCheckAt < 10) return pathExists;
        if (!mob.onGround()) return pathExists;   // cannot ask the pathfinder while airborne
        pathCheckAt = ticks;
        Path p = mob.getNavigation().createPath(BlockPos.containing(goal), 1);
        pathExists = p != null && p.canReach();
        return pathExists;
    }

    /**
     * First breakable block that PHYSICALLY blocks the zombie's body on the way toward the goal.
     * Tries the main axis, then the other one.
     */
    private BlockPos findBlocker(Level level, double hx, double hz) {
        int sx = (int) Math.signum(hx);
        int sz = (int) Math.signum(hz);
        boolean xFirst = Math.abs(hx) >= Math.abs(hz);
        int[][] dirs = xFirst ? new int[][]{{sx, 0}, {0, sz}} : new int[][]{{0, sz}, {sx, 0}};
        AABB box = mob.getBoundingBox();
        double feetY = mob.getY();
        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) continue;
            AABB swept = box.expandTowards(d[0] * PROBE, 0.0, d[1] * PROBE).contract(0.0, 0.05, 0.0);
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            double highest = feetY;
            boolean any = false;
            for (BlockPos pos : BlockPos.betweenClosed(
                    BlockPos.containing(swept.minX, swept.minY, swept.minZ),
                    BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))) {
                if (!level.hasChunkAt(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                for (AABB part : state.getCollisionShape(level, pos).toAabbs()) {
                    AABB w = part.move(pos);
                    if (!w.intersects(swept) || w.maxY <= feetY + STEP_HEIGHT + 0.01) continue;   // walked over
                    any = true;
                    highest = Math.max(highest, w.maxY);
                    if (!canBreak(level, pos, state)) continue;
                    double dist = pos.distToCenterSqr(mob.getX(), mob.getY() + 0.5, mob.getZ());
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos.immutable();
                    }
                }
            }
            if (!any) continue;   // this way is physically open
            // A 1-block ledge with room above is for WallHopGoal to leap, not for us to dig.
            if (highest <= feetY + 1.001 && level.noCollision(mob, box.move(d[0] * PROBE, 1.05, d[1] * PROBE))) continue;
            if (best != null) return best;
        }
        return null;
    }

    private boolean canBreak(Level level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0.0F && hardness <= ZombieAIConfig.BREAK_MAX_HARDNESS.get();
    }

    private void continueBreaking(Level level) {
        BlockPos pos = breaking;
        BlockState state = level.getBlockState(pos);
        double dx = pos.getX() + 0.5 - mob.getX();
        double dz = pos.getZ() + 0.5 - mob.getZ();
        if (state.isAir() || state.getCollisionShape(level, pos).isEmpty() || dx * dx + dz * dz > 2.6 * 2.6
                || Math.abs(pos.getY() + 0.5 - mob.getY()) > 3.5) {
            cancel();
            return;
        }
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        mob.getNavigation().stop();

        double hardness = Math.max(0.1, state.getDestroySpeed(level, pos));
        double bonus = perception.isEnraged() ? ZombieAIConfig.RAGE_BREAK_BONUS.get() : 0.0;
        double need = hardness * ZombieAIConfig.BREAK_TICKS_PER_HARDNESS.get() / (1.0 + bonus);
        progress += 1.0;
        if (ticks % 10 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        int stage = (int) Math.min(9, progress * 10.0 / Math.max(1.0, need));
        if (stage != lastStage) {
            lastStage = stage;
            level.destroyBlockProgress(mob.getId(), pos, stage);
        }
        if (progress >= need) {
            level.destroyBlockProgress(mob.getId(), pos, -1);
            level.destroyBlock(pos, true, mob);
            breaking = null;
            progress = 0.0;
            lastStage = -1;
            chainUntil = ticks + 40;   // look at once for the next blocker
            pathCheckAt = -1000;       // the wall changed: ask the pathfinder again
            sampleAt = null;
        }
    }

    private void cancel() {
        if (breaking != null) {
            mob.level().destroyBlockProgress(mob.getId(), breaking, -1);
            breaking = null;
            progress = 0.0;
            lastStage = -1;
        }
        perception.markBreaking(false);
    }

    @Override
    public void stop() {
        cancel();
    }
}
