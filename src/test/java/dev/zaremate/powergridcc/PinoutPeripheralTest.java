package dev.zaremate.powergridcc;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.ObjectArguments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.kinetics.punchcard.PunchCardReaderBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PinoutPeripheralTest {
    private PunchCardReaderBlockEntity reader;
    private PinoutAccess access;
    private PinoutPeripheral peripheral;
    private CompoundTag saved;
    private SwitchedWire[] wires;
    private ElectricBehaviour circuit;

    @BeforeAll static void bootstrap() { VariacPeripheralTest.bootstrap(); }

    @BeforeEach void setup() {
        reader = mock(PunchCardReaderBlockEntity.class, withSettings().extraInterfaces(PinoutAccess.class));
        access = (PinoutAccess) reader;
        when(reader.getLevel()).thenReturn(mock(ServerLevel.class));
        saved = new CompoundTag();
        when(reader.getPersistentData()).thenReturn(saved);
        wires = new SwitchedWire[8];
        for (int i = 0; i < 8; i++) {
            wires[i] = new SwitchedWire(0.01f, mock(IElectricNode.class), mock(IElectricNode.class), false);
        }
        when(access.powergridCC$getWires()).thenReturn(wires);
        circuit = mock(ElectricBehaviour.class);
        when(reader.getBehaviour(ElectricBehaviour.TYPE)).thenReturn(circuit);
        when(circuit.hasTerminal(8)).thenReturn(true);
        for (int i = 0; i < 9; i++) {
            var node = mock(OwnedFloatingNode.class);
            when(node.getVoltage()).thenReturn(10.0 + i * 2);
            when(circuit.getTerminal(i)).thenReturn(node);
        }
        peripheral = new PinoutPeripheral(reader);
    }

    @Test void everyByteHasCorrectBitMappingAndPersists() throws Exception {
        for (int value = 0; value <= 255; value++) {
            peripheral.setByte(value);
            var states = peripheral.pinsConnected();
            assertEquals(9, states.size());
            assertTrue(states.get(9));
            for (int pin = 1; pin <= 8; pin++) assertEquals((value & (1 << (pin - 1))) != 0, states.get(pin));
            assertEquals(value, saved.getInt(PinoutControl.STATE_KEY));
            assertTrue(peripheral.isComputerControlled());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void connectsAndDisconnectsIndividualPins(int pin) throws Exception {
        peripheral.connectPin(new ObjectArguments(pin));
        assertEquals(1 << (pin - 1), saved.getInt(PinoutControl.STATE_KEY));
        peripheral.disconnectPin(new ObjectArguments(pin));
        assertEquals(0, saved.getInt(PinoutControl.STATE_KEY));
    }

    @Test void updatesTablesWithoutTouchingUnspecifiedPins() throws Exception {
        peripheral.connectPin(new ObjectArguments(Map.of(1, 1, 2, 3, 3, 8)));
        peripheral.disconnectPin(new ObjectArguments(Map.of(1, 3, 2, 8)));
        assertEquals(1, saved.getInt(PinoutControl.STATE_KEY));
        peripheral.setPins(new ObjectArguments(Map.of("2", true, 3.0, 5, 4, 0)));
        assertEquals(7, saved.getInt(PinoutControl.STATE_KEY));
        peripheral.setPins(new ObjectArguments(Map.of(1, false)));
        assertEquals(6, saved.getInt(PinoutControl.STATE_KEY));
    }

    @Test void invalidTableDoesNotPartiallyMutate() throws Exception {
        var input = new LinkedHashMap<Integer, Object>();
        input.put(1, 1);
        input.put(2, 9);
        assertThrows(LuaException.class, () -> peripheral.connectPin(new ObjectArguments(input)));
        input.put(1, true);
        input.put(2, "invalid");
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(input)));
        assertFalse(peripheral.isComputerControlled());
        assertFalse(wires[0].getState());
        verify(reader, never()).setChanged();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 256, 0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidBytes(double value) {
        assertThrows(LuaException.class, () -> peripheral.setByte(value));
        assertTrue(saved.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 9, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidControlledPins(double pin) {
        assertThrows(LuaException.class, () -> peripheral.connectPin(new ObjectArguments(pin)));
        assertThrows(LuaException.class, () -> peripheral.disconnectPin(new ObjectArguments(pin)));
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(Map.of(pin, true))));
        assertTrue(saved.isEmpty());
    }

    @Test void rejectsMalformedInputsAndDuplicatePinKeys() {
        assertThrows(LuaException.class, () -> peripheral.connectPin(new ObjectArguments("1")));
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(1)));
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(Map.of("x", true))));
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(Map.of(1, Double.NaN))));
        assertThrows(LuaException.class, () -> peripheral.setPins(new ObjectArguments(Map.of(1, true, "1", false))));
        assertThrows(LuaException.class, () -> peripheral.comparePin(new ObjectArguments()));
        assertThrows(LuaException.class, () -> peripheral.pinsVoltage(new ObjectArguments(10)));
        assertTrue(saved.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    void measuresSignedVoltagesRelativeToAnyPin(int reference) throws Exception {
        var voltages = peripheral.pinsVoltage(new ObjectArguments(reference));
        double base = reference == 9 ? 10 : 10 + reference * 2;
        for (int pin = 1; pin <= 9; pin++) {
            double expected = (pin == 9 ? 10 : 10 + pin * 2) - base;
            assertEquals(expected, voltages.get(pin));
            assertEquals(expected, peripheral.comparePin(new ObjectArguments(pin, reference)));
        }
        assertEquals(0, voltages.get(reference));
    }

    @Test void defaultsVoltageReferenceToCommonWithoutTakingControl() throws Exception {
        assertEquals(2, peripheral.comparePin(new ObjectArguments(1)));
        assertEquals(16, peripheral.pinsVoltage(new ObjectArguments()).get(8));
        assertFalse(peripheral.isComputerControlled());
        verify(reader, never()).setChanged();
    }

    @Test void restoresOverrideAfterReloadOrCircuitRebuild() throws Exception {
        peripheral.setByte(165);
        var restored = saved.copy();
        when(reader.getPersistentData()).thenReturn(restored);
        for (var wire : wires) wire.setState(false);
        PinoutControl.apply(reader);
        assertTrue(wires[0].getState());
        assertFalse(wires[1].getState());
        assertTrue(wires[7].getState());
        assertEquals(165, restored.getInt(PinoutControl.STATE_KEY));
    }

    @Test void overrideSuppressesCardsUntilReleased() throws Exception {
        PinoutControl.applyCardState(reader, wires[0], true);
        assertTrue(wires[0].getState());
        peripheral.setByte(0);
        PinoutControl.applyCardState(reader, wires[0], true);
        assertFalse(wires[0].getState());
        peripheral.releaseControl();
        assertFalse(peripheral.isComputerControlled());
        verify(access).powergridCC$invalidateCardIndex();
        PinoutControl.applyCardState(reader, wires[0], true);
        assertTrue(wires[0].getState());
        PinoutControl.apply(reader);
        assertTrue(wires[0].getState());
    }

    @Test void partialWritePreservesExistingCardConnections() throws Exception {
        wires[6].setState(true);
        peripheral.connectPin(new ObjectArguments(1));
        assertEquals(65, saved.getInt(PinoutControl.STATE_KEY));
    }

    @Test void emptyUpdatesDoNotTakeControl() throws Exception {
        peripheral.connectPin(new ObjectArguments(Map.of()));
        peripheral.disconnectPin(new ObjectArguments(Map.of()));
        peripheral.setPins(new ObjectArguments(Map.of()));
        assertFalse(peripheral.isComputerControlled());
    }

    @Test void unavailableReaderRejectsReadsAndWrites() {
        when(reader.isRemoved()).thenReturn(true);
        assertThrows(LuaException.class, peripheral::pinsConnected);
        assertThrows(LuaException.class, () -> peripheral.setByte(255));
        assertThrows(LuaException.class, () -> peripheral.pinsVoltage(new ObjectArguments()));
        assertThrows(LuaException.class, peripheral::releaseControl);
        assertTrue(saved.isEmpty());
    }

    @Test void uninitializedCircuitIsReportedRatherThanReturningFakeVoltage() {
        when(access.powergridCC$getWires()).thenReturn(null);
        when(circuit.hasTerminal(8)).thenReturn(false);
        assertThrows(LuaException.class, () -> peripheral.setByte(1));
        assertThrows(LuaException.class, peripheral::pinsConnected);
        assertThrows(LuaException.class, () -> peripheral.comparePin(new ObjectArguments(1)));
        assertTrue(saved.isEmpty());
    }

    @Test void layoutMatchesReadersReversedRightBank() {
        var rows = peripheral.pinLayout();
        assertArrayEquals(new Object[] {null, 9, null}, (Object[]) rows.get(1));
        assertArrayEquals(new Object[] {1, null, 8}, (Object[]) rows.get(2));
        assertArrayEquals(new Object[] {4, null, 5}, (Object[]) rows.get(5));
    }

    @Test void identifiesSameReaderAcrossSidesAndSchedulesWorldAccess() {
        assertEquals("powergrid_punch_card_reader", peripheral.getType());
        assertSame(reader, peripheral.getTarget());
        assertTrue(peripheral.equals(new PinoutPeripheral(reader)));
        assertFalse(peripheral.equals(new PinoutPeripheral(mock(PunchCardReaderBlockEntity.class))));
        assertFalse(peripheral.equals(null));
        for (var method : PinoutPeripheral.class.getDeclaredMethods()) {
            var annotation = method.getAnnotation(LuaFunction.class);
            if (annotation != null && !method.getName().equals("pinLayout")) assertTrue(annotation.mainThread());
        }
    }
}
