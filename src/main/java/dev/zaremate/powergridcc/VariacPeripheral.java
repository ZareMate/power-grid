package dev.zaremate.powergridcc;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.createmod.catnip.animation.LerpedFloat;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.kinetics.variac.VariacBlockEntity;

public final class VariacPeripheral implements IPeripheral {
    private final VariacBlockEntity variac;

    public VariacPeripheral(VariacBlockEntity variac) {
        this.variac = variac;
    }

    @Override
    public String getType() { return "powergrid_variac"; }

    @Override
    public Object getTarget() { return variac; }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof VariacPeripheral peripheral && peripheral.variac == variac;
    }

    @LuaFunction(mainThread = true)
    public final double getRatio() throws LuaException {
        checkAvailable();
        return variac.getRatio();
    }

    @LuaFunction(mainThread = true)
    public final void setRatio(double ratio) throws LuaException {
        if (!Double.isFinite(ratio) || ratio < 0.01 || ratio > 1.0) {
            throw new LuaException("Ratio must be a finite number between 0.01 and 1.0");
        }
        checkAvailable();
        if (variac.getSpeed() != 0) {
            throw new LuaException("Stop the variac shaft before setting its ratio");
        }
        ElectricBehaviour circuit = variac.getBehaviour(ElectricBehaviour.TYPE);
        if (circuit == null || !circuit.hasInternals()) {
            throw new LuaException("Variac circuit is not ready; try again next tick");
        }
        float position = (float) ((ratio - 0.01) / 0.99);
        // Reset both position and chase target so later ticks retain this value.
        variac.arm.setValue(position);
        variac.arm.chase(position, 0, LerpedFloat.Chaser.LINEAR);
        variac.refreshParameters();
        variac.setChanged();
        variac.arm.forceNextSync();
        variac.sendData();
    }

    private void checkAvailable() throws LuaException {
        if (variac.isRemoved() || variac.isChunkUnloaded() || variac.getLevel() == null
                || variac.getLevel().isClientSide || variac.arm == null) {
            throw new LuaException("Variac is no longer available");
        }
    }
}
