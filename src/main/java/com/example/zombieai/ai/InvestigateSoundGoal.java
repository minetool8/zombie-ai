package com.example.zombieai.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Walks to the point chosen by PerceptionGoal (last seen position or approximate sound location). */
public class InvestigateSoundGoal extends Goal {
    private final Mob mob;
    private final PerceptionGoal perception;
    private Vec3 pathedTo;
    private int repath;
    private int stuck;

    public InvestigateSoundGoal(Mob mob, PerceptionGoal perception) {
        this.mob = mob;
        this.perception = perception;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return perception.hasInvestigation() && mob.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;   // react to a new sound point on the very next tick
    }

    @Override
    public void start() {
        pathedTo = null;
        repath = 0;
        stuck = 0;
    }

    @Override
    public void tick() {
        Vec3 pos = perception.investigatePos();
        if (pos == null) return;
        mob.getLookControl().setLookAt(pos.x, pos.y + 1.0, pos.z);   // turn toward the sound

        if (mob.distanceToSqr(pos) < 2.5 * 2.5) {
            perception.clearInvestigate();   // arrived, nothing here
            mob.getNavigation().stop();
            return;
        }
        PathNavigation nav = mob.getNavigation();

        // A NEW target point (the sound moved, or a retry): walk there right away instead of
        // finishing the old path first.
        if (pos != pathedTo) {
            pathedTo = pos;
            stuck = 0;
            repath = 10;
            nav.moveTo(pos.x, pos.y, pos.z, 1.0D);
            return;
        }

        if (--repath <= 0) {
            repath = 10;
            if (nav.isDone()) {
                if (++stuck >= 3) {
                    // Unreachable spot (inside rock, behind water...): try another spot around the sound.
                    if (!perception.retryInvestigate()) {
                        perception.clearInvestigate();
                    }
                    stuck = 0;
                    return;
                }
                nav.moveTo(pos.x, pos.y, pos.z, 1.0D);
            } else {
                stuck = 0;
            }
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
