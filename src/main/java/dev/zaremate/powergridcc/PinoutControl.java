package dev.zaremate.powergridcc;

import org.patryk3211.powergrid.kinetics.punchcard.PunchCardReaderBlockEntity;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;

/** Persistent override of the reader's existing switches, without replacing its circuit. */
public final class PinoutControl {
    public static final String STATE_KEY = "powergrid_cc:pinout";

    private PinoutControl() {}

    public static boolean isControlled(PunchCardReaderBlockEntity reader) {
        return reader.getPersistentData().contains(STATE_KEY);
    }

    public static void setByte(PunchCardReaderBlockEntity reader, int value) {
        reader.getPersistentData().putInt(STATE_KEY, value);
        apply(reader);
        reader.setChanged();
    }

    public static void release(PunchCardReaderBlockEntity reader) {
        reader.getPersistentData().remove(STATE_KEY);
        // -2 differs from both an empty reader (-1) and all valid card rows (0–15).
        ((PinoutAccess) reader).powergridCC$invalidateCardIndex();
        reader.setChanged();
    }

    public static void apply(PunchCardReaderBlockEntity reader) {
        if (!isControlled(reader)) return;
        var wires = ((PinoutAccess) reader).powergridCC$getWires();
        if (wires == null) return;
        int value = reader.getPersistentData().getInt(STATE_KEY);
        for (int i = 0; i < wires.length; i++) {
            if (wires[i] != null) wires[i].setState((value & (1 << i)) != 0);
        }
    }

    public static void applyCardState(PunchCardReaderBlockEntity reader, SwitchedWire wire, boolean state) {
        // Computer control was applied before the electrical tick. Preserve it while
        // allowing the original card animation, redstone output, and heating to run.
        if (!isControlled(reader)) wire.setState(state);
    }
}
