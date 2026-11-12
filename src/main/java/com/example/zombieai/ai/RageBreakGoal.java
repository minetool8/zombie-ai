package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.EnumSet;

/**
 * An ENRAGED zombie (it heard a shout) breaks the blocks that stand between it and the player / the sound.
 * Normal zombies never break blocks. Breaking speed is 75% faster while enraged (configurable).
 *
 * It only starts when the zombie wants to go somewhere but is stuck against a solid block, so it still takes
 * open paths and leaps 1-block ledges first. Holds no goal flags. Respects the mobGriefing game rule.
 */
public class RageBreakGoal extends Goal {
    private static final int SAMPLE_TICKS = 5;

    private final Mob mob;
    private final PerceptionGoal perception;

    private int ticks;
    private Vec3 sampleAt;
    private int stuckSamples;

    private BlockPos breaking;
    private double progress;
    private int lastStage = -1;
    /** After a block broke, the next blocker is attacked at once (no need to wait to be stuck again). */
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

    @Override
    public void tick() {
        if (!mob.isAlive() || mob.level().isClientSide()) return;
        ticks++;
        Level level = mob.level();

        if (!perception.isEnraged() || !ZombieAIConfig.RAGE_BREAK_ENABLED.get()
                || !ForgeEventFactory.getMobGriefingEvent(level, mob)) {
            cancel();
            return;
        }
        Vec3 goal = goalPoint();
        if (goal == null) {
            cancel();
            stuckSamples = 0;
            sampleAt = null;
            return;
        }

        if (breaking != null) {
            continueBreaking(level);
            return;
        }
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
        if (!chaining && ++stuckSamples < 4) return;   // ~20 ticks: lets walking / the 1-block hop go first
        stuckSamples = 0;

        BlockPos blocker = findBlocker(level, hx, hz);
        if (blocker != null) {
            breaking = blocker;
            progress = 0.0;
            lastStage = -1;
        }
    }

    /** The first breakable solid block in the way (feet level, then head level) toward the goal. */
    private BlockPos findBlocker(Level level, double hx, double hz) {
        int sx = (int) Math.signum(hx);
        int sz = (int) Math.signum(hz);
        boolean xFirst = Math.abs(hx) >= Math.abs(hz);
        int[][] dirs = xFirst ? new int[][]{{sx, 0}, {0, sz}} : new int[][]{{0, sz}, {sx, 0}};
        BlockPos base = mob.blockPosition();
        int height = Math.max(2, Math.min(3, (int) Math.ceil(mob.getBbHeight())));
        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) continue;
            for (int dy = 0; dy < height; dy++) {
                BlockPos pos = base.offset(d[0], dy, d[1]);
                if (!level.hasChunkAt(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) continue;
                if (canBreak(level, pos, state)) return pos;
            }
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
            cancel();   // already gone, or the zombie got pushed away
            return;
        }
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        mob.getNavigation().stop();

        double hardness = Math.max(0.1, state.getDestroySpeed(level, pos));
        double need = hardness * ZombieAIConfig.BREAK_TICKS_PER_HARDNESS.get()
                / (1.0 + ZombieAIConfig.RAGE_BREAK_BONUS.get());
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
            chainUntil = ticks + 40;
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
    }

    @Override
    public void stop() {
        cancel();
    }
}
