package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Raises the shield in short, deliberate windows instead of holding it up for a whole fight.
 *
 * <p>A shield that never comes down is worse than no shield: the guard blocks, the block shoves the
 * mob out of reach, the guard blocks again, and nothing ever dies. Every window here is followed by
 * a cooldown long enough for the guard to swing or shoot, so blocking buys a guard time instead of
 * replacing its attacks.
 */
public class RaiseShieldGoal extends Goal {

    /** How long one window stays up. */
    private static final int BLOCK_TICKS = 12;
    /** Then the guard puts the shield down and fights for this long before it may block again. */
    private static final int BLOCK_COOL_DOWN_TICKS = 50;
    /** Anything this close is already swinging at the guard. */
    private static final double MELEE_RANGE = 3.0D;
    /** Far enough out that raising the shield does not cost the guard its approach. */
    private static final double RANGED_RANGE = 5.0D;
    /** A charge this young is cheap to throw away; a nearly loaded crossbow is not. */
    private static final int CHARGE_TICKS_WORTH_KEEPING = 5;

    public final GuardEntity guard;

    public RaiseShieldGoal(GuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canUse() {
        return this.guard.getOffhandItem().getItem() == Items.SHIELD
                && this.guard.shieldCoolDown == 0
                && this.guard.shieldBlockCoolDown == 0
                && !this.guard.isEating()
                && !CrossbowItem.isCharged(this.guard.getMainHandItem())
                && this.canDropCurrentUse()
                && this.raiseShield();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guard.shieldTicks > 0
                && this.guard.getOffhandItem().getItem() == Items.SHIELD
                && this.guard.getTarget() != null
                && this.guard.isUsingItem()
                && this.guard.getUseItem().getItem() == Items.SHIELD;
    }

    @Override
    public void start() {
        if (this.guard.getOffhandItem().getItem() == Items.SHIELD) {
            this.guard.shieldTicks = BLOCK_TICKS;
            this.guard.startUsingItem(InteractionHand.OFF_HAND);
        }
    }

    @Override
    public void stop() {
        this.guard.shieldTicks = 0;
        this.guard.shieldBlockCoolDown = BLOCK_COOL_DOWN_TICKS;
        if (!GuardVillagersConfig.guardAlwaysShield)
            this.lowerShield();
    }

    /**
     * Give up whatever the guard is doing only when it is nearly worthless: an empty hand, or the
     * first few ticks of a crossbow load or a bow draw. A shot that is almost ready gets finished.
     */
    private boolean canDropCurrentUse() {
        if (!this.guard.isUsingItem())
            return true;
        Item useItem = this.guard.getUseItem().getItem();
        return (useItem instanceof CrossbowItem || useItem instanceof BowItem)
                && this.guard.getTicksUsingItem() <= CHARGE_TICKS_WORTH_KEEPING;
    }

    /**
     * Let go of the shield only. Releasing "the item in use" blindly is what used to cancel a
     * crossbow charge another goal had already started.
     */
    private void lowerShield() {
        if (this.guard.isUsingItem() && this.guard.getUseItem().getItem() == Items.SHIELD)
            this.guard.releaseUsingItem();
    }

    protected boolean raiseShield() {
        LivingEntity target = this.guard.getTarget();
        if (target == null || !target.isAlive())
            return false;
        // These two end a guard if they connect, so they are worth blocking every time.
        if (target instanceof Creeper || target instanceof Ravager)
            return true;
        if (GuardVillagersConfig.guardAlwaysShield)
            return true;
        if (!this.guard.hasLineOfSight(target))
            return false;

        double distance = this.guard.distanceTo(target);
        // Melee: block the swing that is already coming. This is not the old shuffle towards a
        // distant target; it only applies once the mob is close enough to hit back, and the window
        // is short enough that the guard's own attack still comes out between blocks.
        if (distance <= MELEE_RANGE)
            return true;
        // Ranged: block while something is shooting at the guard from outside melee reach, which
        // also covers bow and crossbow guards between shots.
        return target instanceof RangedAttackMob && distance >= RANGED_RANGE;
    }
}
