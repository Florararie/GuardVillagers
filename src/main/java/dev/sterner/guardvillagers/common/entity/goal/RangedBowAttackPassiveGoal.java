package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/**
 * The bow counterpart of {@link RangedCrossbowAttackPassiveGoal}, deliberately sharing its rules:
 * hold the range the guard can shoot from, walk away instead of swinging when a mob is in its face,
 * never loose an arrow through a villager, and step aside while a shield window is up.
 */
public class RangedBowAttackPassiveGoal extends Goal {

    public static final UniformInt PATHFINDING_DELAY_RANGE = TimeUtil.rangeOfSeconds(1, 2);
    /** A bow has nothing worth shooting at this close; the guard makes room instead. */
    private static final double MIN_SHOOT_DISTANCE_SQR = 3.0D * 3.0D;
    /** Reverse walk speed used while backing off. Magnitude matches vanilla's own back-off strafe. */
    private static final float BACK_OFF_SPEED = -0.5F;
    /** Vanilla draws for twenty ticks, and a bow loosed sooner throws a weak arrow. */
    private static final int FULL_DRAW_TICKS = 20;

    private final GuardEntity guard;
    private final double speedModifier;
    private final float attackRadiusSqr;
    private final int attackInterval;
    protected double wantedX;
    protected double wantedY;
    protected double wantedZ;
    private int seeTime;
    private int updatePathDelay;
    private int shotCoolDown;

    public RangedBowAttackPassiveGoal(GuardEntity guard, double speedModifier, int attackInterval, float attackRadius) {
        this.guard = guard;
        this.speedModifier = speedModifier;
        this.attackInterval = attackInterval;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.isValidTarget() && this.isHoldingBow();
    }

    @Override
    public boolean canContinueToUse() {
        return this.isValidTarget() && this.isHoldingBow();
    }

    private boolean isValidTarget() {
        return this.guard.getTarget() != null && this.guard.getTarget().isAlive();
    }

    private boolean isHoldingBow() {
        // Mainhand only: performRangedAttack fires a mainhand bow, so matching a bow in either hand
        // would let a guard draw forever without an arrow ever leaving the bow.
        return !this.guard.isEating() && this.guard.getMainHandItem().getItem() instanceof BowItem;
    }

    @Override
    public void start() {
        this.guard.setAggressive(true);
    }

    @Override
    public void stop() {
        super.stop();
        this.guard.setAggressive(false);
        this.seeTime = 0;
        this.shotCoolDown = 0;
        this.releaseBow();
        this.guard.setPose(Pose.STANDING);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // Same shield hand-off as the crossbow: freeze the shooting cycle for the window instead of
        // letting two goals fight over the use item.
        if (this.guard.shieldTicks > 0)
            return;

        LivingEntity target = this.guard.getTarget();
        if (target == null)
            return;

        boolean canSee = this.guard.getSensing().hasLineOfSight(target);
        if (canSee != this.seeTime > 0)
            this.seeTime = 0;
        if (canSee)
            ++this.seeTime;
        else
            --this.seeTime;

        double distanceSqr = this.guard.distanceToSqr(target);
        if (distanceSqr < MIN_SHOOT_DISTANCE_SQR) {
            this.backOff();
        } else if (distanceSqr > (double) this.attackRadiusSqr || this.seeTime < 5) {
            --this.updatePathDelay;
            if (this.updatePathDelay <= 0) {
                this.guard.getNavigation().moveTo(target, this.speedModifier);
                this.updatePathDelay = PATHFINDING_DELAY_RANGE.sample(this.guard.getRandom());
            }
        } else {
            this.updatePathDelay = 0;
            this.guard.getNavigation().stop();
            this.guard.getMoveControl().strafe(0.0F, 0.0F);
        }
        // A patrolling guard holds its post instead of chasing the mob across the village.
        if (this.guard.isPatrolling()) {
            this.guard.getNavigation().stop();
            this.guard.getMoveControl().strafe(0.0F, 0.0F);
        }
        this.guard.lookAt(target, 30.0F, 30.0F);
        this.guard.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (GuardVillagersConfig.friendlyFire && this.friendlyInLineOfSight()) {
            // An arrow through a villager is worse than a missed shot: give up the draw and move.
            this.releaseBow();
            if (this.findPosition())
                this.guard.getNavigation().moveTo(this.wantedX, this.wantedY, this.wantedZ, 1.2D);
            return;
        }

        if (!this.guard.isUsingItem()) {
            if (--this.shotCoolDown <= 0 && this.seeTime >= 5)
                this.guard.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.guard, Items.BOW));
            return;
        }
        // The shield, or an unrelated item, may own the use slot. This goal only ever handles a bow.
        if (!(this.guard.getUseItem().getItem() instanceof BowItem))
            return;

        int drawTicks = this.guard.getTicksUsingItem();
        if (!canSee && this.seeTime < -60) {
            this.releaseBow();
            return;
        }
        if (drawTicks >= FULL_DRAW_TICKS) {
            this.releaseBow();
            this.guard.performRangedAttack(target, BowItem.getPowerForTime(drawTicks));
            this.shotCoolDown = this.attackInterval;
        }
    }

    /**
     * Walk straight backwards instead of pathing to a spot behind the guard. Navigation turns the
     * body toward its next node, so a "get away from here" path read as the guard spinning on the
     * spot, walking a couple of blocks and turning back around. Strafe mode drives the body in
     * reverse whatever way the look control is facing, so the guard keeps its eyes, and its bow,
     * on the mob the whole time. The draw is left alone: only the legs work here.
     */
    private void backOff() {
        this.updatePathDelay = 0;
        this.guard.getNavigation().stop();
        this.guard.getMoveControl().strafe(BACK_OFF_SPEED, 0.0F);
    }

    /**
     * Let go of the bow only. Releasing whatever happens to be in use would cancel a shield window
     * that another goal had already started.
     */
    private void releaseBow() {
        if (this.guard.isUsingItem() && this.guard.getUseItem().getItem() instanceof BowItem)
            this.guard.releaseUsingItem();
    }

    private boolean friendlyInLineOfSight() {
        List<Entity> list = this.guard.level().getEntities(this.guard, this.guard.getBoundingBox().inflate(3.0D));
        for (Entity ally : list) {
            if (ally != this.guard.getTarget()) {
                boolean isVillager = this.guard.getOwner() == ally
                        || ally.getType() == EntityType.VILLAGER
                        || ally.getType() == GuardVillagers.GUARD_VILLAGER
                        || ally.getType() == EntityType.IRON_GOLEM;
                if (isVillager) {
                    Vec3 look = this.guard.getLookAngle();
                    Vec3 toAlly = ally.position().vectorTo(this.guard.position()).normalize();
                    // Only a friendly actually in front is in the way, not one standing behind.
                    if (toAlly.dot(look) > 0.0D && this.guard.hasLineOfSight(ally) && ally.distanceTo(this.guard) <= 3.0D)
                        return true;
                }
            }
        }
        return false;
    }

    public boolean findPosition() {
        Vec3 vector3d = this.getPosition();
        if (vector3d == null) {
            return false;
        } else {
            this.wantedX = vector3d.x;
            this.wantedY = vector3d.y;
            this.wantedZ = vector3d.z;
            return true;
        }
    }

    @Nullable
    protected Vec3 getPosition() {
        if (this.isValidTarget())
            return DefaultRandomPos.getPosAway(this.guard, 16, 7, this.guard.getTarget().position());
        else
            return DefaultRandomPos.getPos(this.guard, 16, 7);
    }
}
