package dev.zaremate.powergridcc;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.kinetics.punchcard.PunchCardReaderBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent implementation of the CC-cpg-pinout Lua API on a PowerGrid reader. */
public final class PinoutPeripheral implements IPeripheral {
    private final PunchCardReaderBlockEntity reader;

    public PinoutPeripheral(PunchCardReaderBlockEntity reader) { this.reader = reader; }

    @Override public String getType() { return "powergrid_punch_card_reader"; }
    @Override public Object getTarget() { return reader; }
    @Override public boolean equals(IPeripheral other) {
        return other instanceof PinoutPeripheral peripheral && peripheral.reader == reader;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Boolean> pinsConnected() throws LuaException {
        var wires = wires();
        Map<Integer, Boolean> states = new LinkedHashMap<>();
        for (int pin = 1; pin <= 8; pin++) states.put(pin, wires[pin - 1].getState());
        states.put(9, true);
        return states;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Double> pinsVoltage(IArguments args) throws LuaException {
        int reference = args.count() == 0 ? 9 : pin(args.get(0), 9);
        var circuit = circuit();
        double base = voltage(circuit, reference);
        Map<Integer, Double> values = new LinkedHashMap<>();
        for (int pin = 1; pin <= 9; pin++) values.put(pin, voltage(circuit, pin) - base);
        return values;
    }

    @LuaFunction(mainThread = true)
    public final double comparePin(IArguments args) throws LuaException {
        int pin = pin(args.get(0), 9);
        int reference = args.count() < 2 ? 9 : pin(args.get(1), 9);
        var circuit = circuit();
        return voltage(circuit, pin) - voltage(circuit, reference);
    }

    @LuaFunction(mainThread = true)
    public final void connectPin(IArguments args) throws LuaException { changePins(args, true); }

    @LuaFunction(mainThread = true)
    public final void disconnectPin(IArguments args) throws LuaException { changePins(args, false); }

    private void changePins(IArguments args, boolean state) throws LuaException {
        Object input = args.get(0);
        int mask = 0;
        if (input instanceof Map<?, ?> table) {
            for (Object value : table.values()) mask |= 1 << (pin(value, 8) - 1);
        } else {
            mask = 1 << (pin(input, 8) - 1);
        }
        int previous = currentByte();
        if (mask != 0) PinoutControl.setByte(reader, state ? previous | mask : previous & ~mask);
    }

    @LuaFunction(mainThread = true)
    public final void setPins(IArguments args) throws LuaException {
        if (!(args.get(0) instanceof Map<?, ?> table)) throw new LuaException("Expected table of pin states");
        int on = 0, off = 0;
        for (var entry : table.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String text) {
                try { key = Integer.parseInt(text); }
                catch (NumberFormatException e) { throw new LuaException("Pin keys must be integers 1-8"); }
            }
            int bit = 1 << (pin(key, 8) - 1);
            Object value = entry.getValue();
            boolean state;
            if (value instanceof Boolean bool) state = bool;
            else if (value instanceof Number number && Double.isFinite(number.doubleValue())) state = number.doubleValue() != 0;
            else throw new LuaException("Pin states must be booleans or finite numbers");
            if (((on | off) & bit) != 0) throw new LuaException("Duplicate pin key");
            if (state) on |= bit; else off |= bit;
        }
        int previous = currentByte();
        if ((on | off) != 0) PinoutControl.setByte(reader, (previous | on) & ~off);
    }

    @LuaFunction(mainThread = true)
    public final void setByte(double value) throws LuaException {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > 255)
            throw new LuaException("Byte value must be an integer between 0 and 255");
        wires();
        PinoutControl.setByte(reader, (int) value);
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() throws LuaException {
        checkAvailable();
        return PinoutControl.isControlled(reader);
    }

    @LuaFunction(mainThread = true)
    public final void releaseControl() throws LuaException {
        checkAvailable();
        PinoutControl.release(reader);
    }

    @LuaFunction
    public final Map<Integer, Object> pinLayout() {
        Map<Integer, Object> rows = new LinkedHashMap<>();
        rows.put(1, new Object[] {null, 9, null});
        // PowerGrid's reader numbers its right bank bottom-to-top.
        for (int row = 1; row <= 4; row++) rows.put(row + 1, new Object[] {row, null, 9 - row});
        return rows;
    }

    private static int pin(Object value, int max) throws LuaException {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < 1 || number.doubleValue() > max)
            throw new LuaException("Pin must be an integer between 1 and " + max);
        return number.intValue();
    }

    private int currentByte() throws LuaException {
        var wires = wires();
        int value = 0;
        for (int i = 0; i < 8; i++) if (wires[i].getState()) value |= 1 << i;
        return value;
    }

    private void checkAvailable() throws LuaException {
        if (reader.isRemoved() || reader.isChunkUnloaded() || reader.getLevel() == null || reader.getLevel().isClientSide)
            throw new LuaException("Punch card reader is no longer available");
    }

    private SwitchedWire[] wires() throws LuaException {
        checkAvailable();
        var wires = ((PinoutAccess) reader).powergridCC$getWires();
        if (wires == null || wires.length != 8) throw new LuaException("Reader circuit is not ready; try again next tick");
        for (var wire : wires) if (wire == null) throw new LuaException("Reader circuit is not ready; try again next tick");
        return wires;
    }

    private ElectricBehaviour circuit() throws LuaException {
        checkAvailable();
        var circuit = reader.getBehaviour(ElectricBehaviour.TYPE);
        if (circuit == null || !circuit.hasTerminal(8)) throw new LuaException("Reader circuit is not ready; try again next tick");
        return circuit;
    }

    private static double voltage(ElectricBehaviour circuit, int pin) {
        return circuit.getTerminal(pin == 9 ? 0 : pin).getVoltage();
    }
}
