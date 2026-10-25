package com.example.zombieai.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * An idle zombie (no target, nothing to investigate) stands completely still: no wandering, no walking
 * through villages, no random head turning. It holds the MOVE and LOOK flags at high priority, and it also
 * cancels any path another mod (e.g. Zombie Awareness light attraction) tries to give it.
 * It only moves again when PerceptionGoal gives it something to do: a sound, blood, being hurt, or a target.
 */
public class DormantGoal extends Goal {
    private final Mob mob;
    private final PerceptionGoal perception;

    public DormantGoal(Mob mob, PerceptionGoal perception) {
        this.mob = mob;
        this.perception = perception;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return perception.isFrozen();
    }

    @Override
    public boolean canContinueToUse() {
        return perception.isFrozen();
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!mob.getNavigation().isDone()) {
            mob.getNavigation().stop();   // no walking "at any cost" while frozen
        }
    }
}
