package com.example.zombieai.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * At night / in rain an idle zombie (no target, not investigating) stands completely still:
 * it holds the MOVE and LOOK flags at high priority, so wandering, village walking and
 * random head turning cannot run. Hearing and vision keep working in PerceptionGoal;
 * as soon as the zombie gets a target or something to investigate, this goal stops by itself.
 * A zombie that is already chasing is never frozen.
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
}
