package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

public class RangedCrossbowAttackPassiveGoal<T extends PathfinderMob & RangedAttackMob & CrossbowAttackMob> extends Goal {
    public static final UniformInt PATHFINDING_DELAY_RANGE = TimeUtil.rangeOfSeconds(1, 2);
    private final T mob;
    private final double speedModifier;
    private final float attackRadiusSqr;
    private static final double MIN_SHOOT_DISTANCE_SQR = 3.0D * 3.0D;
    /** Reverse walk speed used while backing off. Magnitude matches vanilla's own back-off strafe. */
    private static final float BACK_OFF_SPEED = -0.5F;
    protected double wantedX;
    protected double wantedY;
    protected double wantedZ;
    private CrossbowState crossbowState = CrossbowState.UNCHARGED;
    private int seeTime;
    private int attackDelay;
    private int updatePathDelay;
    private int runTime;

    public RangedCrossbowAttackPassiveGoal(T pMob, double pSpeedModifier, float pAttackRadius) {
        this.mob = pMob;
        this.speedModifier = pSpeedModifier;
        this.attackRadiusSqr = pAttackRadius * pAttackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.isValidTarget() && this.isHoldingCrossbow();
    }

    private boolean isHoldingCrossbow() {
        return this.mob.isHolding(is -> is.getItem() instanceof CrossbowItem);
    }

    @Override
    public boolean canContinueToUse() {
        return this.isValidTarget() && this.isHoldingCrossbow();
    }

    private boolean isValidTarget() {
        return this.mob.getTarget() != null && this.mob.getTarget().isAlive();
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAggressive(false);
        this.seeTime = 0;
        if (this.mob.isUsingItem()) {
            this.mob.releaseUsingItem();
            this.mob.setChargingCrossbow(false);
        }
        this.mob.setPose(Pose.STANDING);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.setAggressive(true);
    }

    @Override
    public void tick() {
        // A guard carrying a shield goes behind it for a few ticks now and then. Freeze the crossbow
        // cycle for those ticks rather than fighting over the use item: swapping the shield out
        // mid-charge is what lets a guard fire a bolt it never finished loading.
        if (this.mob instanceof GuardEntity guard && guard.shieldTicks > 0) {
            return;
        }
        LivingEntity livingentity = this.mob.getTarget();
        if (livingentity != null) {
            boolean canSee = this.mob.getSensing().hasLineOfSight(livingentity);
            boolean hasSeenEntityRecently = this.seeTime > 0;
            if (canSee != hasSeenEntityRecently) {
                this.seeTime = 0;
            }
            if (canSee) {
                ++this.seeTime;
            } else {
                --this.seeTime;
            }
            double d0 = this.mob.distanceToSqr(livingentity);
            if (this.mob.getRandom().nextInt(50) == 0) {
                if (this.mob.hasPose(Pose.STANDING))
                    this.mob.setPose(Pose.CROUCHING);
                else
                    this.mob.setPose(Pose.STANDING);
            }
            boolean canSee2 = (d0 > (double) this.attackRadiusSqr || this.seeTime < 5) && this.attackDelay == 0;
            if (d0 < MIN_SHOOT_DISTANCE_SQR) {
                this.backOff();
            } else if (canSee2) {
                --this.updatePathDelay;
                if (this.updatePathDelay <= 0) {
                    this.mob.getNavigation().moveTo(livingentity, this.canRun() ? this.speedModifier : this.speedModifier * 0.5D);
                    this.updatePathDelay = PATHFINDING_DELAY_RANGE.sample(this.mob.getRandom());
                }
            } else {
                this.updatePathDelay = 0;
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().strafe(0.0F, 0.0F);
            }
            this.mob.lookAt(livingentity, 30.0F, 30.0F);
            this.mob.getLookControl().setLookAt(livingentity, 30.0F, 30.0F);
            if (this.friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire)
                this.crossbowState = CrossbowState.FIND_NEW_POSITION;
            if (this.crossbowState == CrossbowState.FIND_NEW_POSITION && GuardVillagersConfig.friendlyFire) {
                this.mob.releaseUsingItem();
                this.mob.setChargingCrossbow(false);
                if (this.findPosition())
                    this.mob.getNavigation().moveTo(this.wantedX, this.wantedY, this.wantedZ, this.mob.isShiftKeyDown() ? 0.5F : 1.2D);
                this.crossbowState = CrossbowState.UNCHARGED;
            } else if (this.crossbowState == CrossbowState.UNCHARGED) {
                if (hasSeenEntityRecently) {
                    this.mob.startUsingItem(GuardVillagers.getHandWith(this.mob, item -> item instanceof CrossbowItem));
                    this.crossbowState = CrossbowState.CHARGING;
                    this.mob.setChargingCrossbow(true);
                }
            } else if (this.crossbowState == CrossbowState.CHARGING) {
                if (!this.mob.isUsingItem()) {
                    this.crossbowState = CrossbowState.UNCHARGED;
                }
                int i = this.mob.getTicksUsingItem();
                ItemStack itemstack = this.mob.getUseItem();
                if (i >= CrossbowItem.getChargeDuration(itemstack, livingentity) || CrossbowItem.isCharged(itemstack)) {
                    this.mob.releaseUsingItem();
                    this.crossbowState = CrossbowState.CHARGED;
                    this.attackDelay = 10 + this.mob.getRandom().nextInt(5);
                    this.mob.setChargingCrossbow(false);
                }
            } else if (this.crossbowState == CrossbowState.CHARGED) {
                --this.attackDelay;
                if (this.attackDelay == 0) {
                    this.crossbowState = CrossbowState.READY_TO_ATTACK;
                }
            } else if (this.crossbowState == CrossbowState.READY_TO_ATTACK && canSee) {
                this.mob.performRangedAttack(livingentity, 1.0F);
                ItemStack itemstack1 = this.mob.getItemInHand(GuardVillagers.getHandWith(this.mob, item -> item instanceof CrossbowItem));
                ((LivingEntity)this.mob).startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.CROSSBOW));
                ((CrossbowAttackMob)this.mob).setChargingCrossbow(false);
                this.crossbowState = CrossbowState.UNCHARGED;
            }
        }

    }

    /**
     * Walk straight backwards instead of pathing to a spot behind the guard. Navigation turns the
     * body toward its next node, so a "get away from here" path read as the guard spinning on the
     * spot, walking a couple of blocks and turning back around. Strafe mode drives the body in
     * reverse whatever way the look control is facing, so the guard keeps facing the mob while it
     * opens the gap. The crossbow is never released here, so a bolt already being loaded still fires.
     */
    private void backOff() {
        this.updatePathDelay = 0;
        this.mob.getNavigation().stop();
        this.mob.getMoveControl().strafe(BACK_OFF_SPEED, 0.0F);
    }

    private boolean friendlyInLineOfSight() {
        List<Entity> list = this.mob.level().getEntities(this.mob, this.mob.getBoundingBox().inflate(3.0D));
        for (Entity guard : list) {
            if (guard != this.mob.getTarget()) {
                boolean isVillager = ((GuardEntity) this.mob).getOwner() == guard || guard.getType() == EntityType.VILLAGER || guard.getType() == GuardVillagers.GUARD_VILLAGER || guard.getType() == EntityType.IRON_GOLEM;
                if (isVillager) {
                    Vec3 vector3d = this.mob.getLookAngle();
                    Vec3 vector3d1 = guard.position().vectorTo(this.mob.position()).normalize();
                    vector3d1 = new Vec3(vector3d1.x, vector3d1.y, vector3d1.z);
                    // dot < 1.0 also matches allies standing behind the guard, which aborted shots
                    // constantly in a village; only a friendly actually in front is in the way.
                    if (vector3d1.dot(vector3d) > 0.0D && this.mob.hasLineOfSight(guard) && guard.distanceTo(this.mob) <= 3.0D)
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
            return DefaultRandomPos.getPosAway(this.mob, 16, 7, this.mob.getTarget().position());
        else
            return DefaultRandomPos.getPos(this.mob, 16, 7);
    }

    private boolean canRun() {
        return this.crossbowState == CrossbowState.UNCHARGED;
    }

    public enum CrossbowState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        READY_TO_ATTACK,
        FIND_NEW_POSITION
    }
}