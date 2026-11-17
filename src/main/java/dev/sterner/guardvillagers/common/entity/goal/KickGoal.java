package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

public class KickGoal extends Goal {

    public final GuardEntity guard;

    public KickGoal(GuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canUse() {
        // Deliberately crossbow-only. useOnRelease is true for a crossbow, false for a bow and false
        // for a sword, and that asymmetry is the behaviour we want: a bow guard can keep firing
        // while it backs off and a kick from this priority-0 goal would interrupt the draw, while a
        // crossbow has to stand still to rack a bolt, so the mob gets kicked off it instead.
        return guard.getTarget() != null && guard.getTarget().distanceTo(guard) <= 2.5D && guard.getMainHandItem().getItem().useOnRelease(guard.getMainHandItem()) && !guard.isBlocking() && guard.kickCoolDown == 0;
    }

    @Override
    public void start() {
        guard.setKicking(true);
        if (guard.kickTicks <= 0) {
            guard.kickTicks = 10;
        }
        if (guard.level() instanceof ServerLevel serverWorld){

            guard.doHurtTarget(serverWorld, guard.getTarget());
        }
    }

    @Override
    public boolean canContinueToUse() {
        // The kick is a single instant hit. Returning false stops the goal on the next tick, which
        // sets kickCoolDown and lets the guard kick again later; the default (true) kept the goal
        // running forever, so a guard kicked once and then never again.
        return false;
    }

    @Override
    public void stop() {
        guard.setKicking(false);
        guard.kickCoolDown = 50;
    }
}