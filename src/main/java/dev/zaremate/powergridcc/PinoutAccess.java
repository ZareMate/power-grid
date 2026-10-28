package dev.zaremate.powergridcc;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;

/** Access supplied to the existing reader by the common-side mixin. */
public interface PinoutAccess {
    SwitchedWire[] powergridCC$getWires();
    void powergridCC$invalidateCardIndex();
}
