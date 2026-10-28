package dev.zaremate.powergridcc.mixin;

import dev.zaremate.powergridcc.PinoutAccess;
import dev.zaremate.powergridcc.PinoutControl;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.kinetics.punchcard.PunchCardReaderBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PunchCardReaderBlockEntity.class, remap = false)
public abstract class PunchCardReaderMixin implements PinoutAccess {
    @Shadow private SwitchedWire[] wires;
    @Shadow private int oldIndex;

    @Override
    public SwitchedWire[] powergridCC$getWires() { return wires; }

    @Override
    public void powergridCC$invalidateCardIndex() { oldIndex = -2; }

    @Inject(method = "electricalTick", at = @At("HEAD"))
    private void powergridCC$restoreBeforeTick(CallbackInfo ci) {
        PinoutControl.apply((PunchCardReaderBlockEntity) (Object) this);
    }

    @Inject(method = "buildCircuit", at = @At("RETURN"))
    private void powergridCC$restoreAfterBuild(CallbackInfo ci) {
        PinoutControl.apply((PunchCardReaderBlockEntity) (Object) this);
    }

    @Redirect(method = "electricalTick", at = @At(value = "INVOKE",
            target = "Lorg/patryk3211/powergrid/electricity/sim/SwitchedWire;setState(Z)V"))
    private void powergridCC$cardState(SwitchedWire wire, boolean state) {
        PinoutControl.applyCardState((PunchCardReaderBlockEntity) (Object) this, wire, state);
    }
}
