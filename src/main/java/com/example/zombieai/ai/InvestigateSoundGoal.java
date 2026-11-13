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
    /** How long a zombie waits beneath a sound it cannot reach before giving up. */
    private static final int WAIT_BENEATH_TICKS = 100;

    private Vec3 pathedTo;
    /** Where it actually walks: the sound itself, or the ground spot beneath it when the sound is high up. */
    private Vec3 approach;
    private int repath;
    private int stuck;
    private int beneath;

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
        approach = null;
        repath = 0;
        stuck = 0;
        beneath = 0;
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
        if (perception.isBreaking()) {   // digging toward the sound: not stuck, do not give up
            stuck = 0;
            return;
        }

        // Sound is high above us and we are standing beneath it (or at the closest ground spot): wait there,
        // WallHopGoal leaps up if the ledge is low enough.
        if (approach != null && approach != pos && pos == pathedTo) {
            double hx = pos.x - mob.getX();
            double hz = pos.z - mob.getZ();
            if (hx * hx + hz * hz < 2.5 * 2.5 || mob.distanceToSqr(approach) < 1.5 * 1.5) {
                nav.stop();
                if (++beneath > WAIT_BENEATH_TICKS) {
                    perception.clearInvestigate();
                    beneath = 0;
                }
                return;
            }
        }

        // A NEW target point (the sound moved, or a retry): walk there right away instead of
        // finishing the old path first.
        if (pos != pathedTo) {
            pathedTo = pos;
            approach = ApproachResolver.resolve(mob, pos);
            perception.setApproachPos(approach);
            stuck = 0;
            beneath = 0;
            repath = 10;
            nav.moveTo(approach.x, approach.y, approach.z, 1.0D);
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
                nav.moveTo(approach.x, approach.y, approach.z, 1.0D);
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
