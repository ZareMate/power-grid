package dev.zaremate.powergridcc;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.flag.FeatureFlagLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.kinetics.variac.VariacBlockEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariacPeripheralTest {
    private VariacBlockEntity variac;
    private ElectricBehaviour circuit;
    private VariacPeripheral peripheral;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        try (var flags = mockStatic(FeatureFlagLoader.class)) {
            Bootstrap.bootStrap();
        }
    }

    @BeforeEach
    void setup() {
        variac = mock(VariacBlockEntity.class);
        variac.arm = LerpedFloat.linear().chase(1, 0.1, LerpedFloat.Chaser.LINEAR);
        variac.arm.setValue(1);
        when(variac.getLevel()).thenReturn(mock(ServerLevel.class));
        when(variac.getRatio()).thenCallRealMethod();
        circuit = mock(ElectricBehaviour.class);
        when(circuit.hasInternals()).thenReturn(true);
        when(variac.getBehaviour(ElectricBehaviour.TYPE)).thenReturn(circuit);
        peripheral = new VariacPeripheral(variac);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 0.1, 0.5, 0.99, 1.0})
    void setsRatioAndRetainsItAcrossTicks(double ratio) throws Exception {
        peripheral.setRatio(ratio);
        assertEquals(ratio, peripheral.getRatio(), 1e-6);
        for (int i = 0; i < 40; i++) variac.arm.tickChaser();
        assertEquals(ratio, peripheral.getRatio(), 1e-6);
        var order = inOrder(variac);
        order.verify(variac).refreshParameters();
        order.verify(variac).setChanged();
        order.verify(variac).sendData();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 0.009, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidRatiosWithoutChangingState(double ratio) {
        assertThrows(LuaException.class, () -> peripheral.setRatio(ratio));
        assertEquals(1, variac.arm.getValue());
        verify(variac, never()).refreshParameters();
        verify(variac, never()).sendData();
    }

    @ParameterizedTest
    @ValueSource(floats = {-16, 16})
    void rejectsRotatingShaft(float speed) {
        when(variac.getSpeed()).thenReturn(speed);
        assertThrows(LuaException.class, () -> peripheral.setRatio(0.5));
        assertEquals(1, variac.arm.getValue());
        verify(variac, never()).refreshParameters();
    }

    @Test
    void rejectsUninitializedCircuit() {
        when(circuit.hasInternals()).thenReturn(false);
        assertThrows(LuaException.class, () -> peripheral.setRatio(0.5));
        assertEquals(1, variac.arm.getValue());
        verify(variac, never()).refreshParameters();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 0.37, 1.0})
    void armSettingSurvivesSavedNbtAndClientSync(double ratio) throws Exception {
        peripheral.setRatio(ratio);
        var saved = variac.arm.writeNBT();
        var restored = LerpedFloat.linear();
        restored.readNBT(saved, false);
        assertEquals(variac.arm.getValue(), restored.getValue(), 1e-6);
        restored.tickChaser();
        assertEquals(variac.arm.getValue(), restored.getValue(), 1e-6);

        // Test the synchronization requested by setRatio itself.
        var clientArm = LerpedFloat.linear().startWithValue(1);
        clientArm.readNBT(saved, true);
        clientArm.tickChaser();
        assertEquals(variac.arm.getValue(), clientArm.getValue(), 1e-6);
    }

    @Test
    void rejectsRemovedAndUnloadedBlocks() {
        when(variac.isRemoved()).thenReturn(true);
        assertThrows(LuaException.class, peripheral::getRatio);
        assertThrows(LuaException.class, () -> peripheral.setRatio(0.5));
        when(variac.isRemoved()).thenReturn(false);
        when(variac.isChunkUnloaded()).thenReturn(true);
        assertThrows(LuaException.class, peripheral::getRatio);
        assertThrows(LuaException.class, () -> peripheral.setRatio(0.5));
        verify(variac, never()).refreshParameters();
    }

    @Test
    void rejectsMissingWorldWithoutMutation() {
        when(variac.getLevel()).thenReturn(null);
        assertUnavailable();
    }

    @Test
    void rejectsClientWorldWithoutMutation() throws Exception {
        var clientLevel = mock(net.minecraft.world.level.Level.class);
        // Mockito skips Level's constructor, so initialize its final side flag.
        var side = net.minecraft.world.level.Level.class.getField("isClientSide");
        side.setAccessible(true);
        side.setBoolean(clientLevel, true);
        when(variac.getLevel()).thenReturn(clientLevel);
        assertUnavailable();
    }

    @Test
    void rejectsMissingArmWithoutMutation() {
        variac.arm = null;
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertEquals("Variac is no longer available",
                assertThrows(LuaException.class, peripheral::getRatio).getMessage());
        assertEquals("Variac is no longer available",
                assertThrows(LuaException.class, () -> peripheral.setRatio(0.5)).getMessage());
        verify(variac, never()).refreshParameters();
        verify(variac, never()).setChanged();
        verify(variac, never()).sendData();
    }

    @Test
    void retriesSuccessfullyAfterCircuitBecomesReady() throws Exception {
        when(variac.getBehaviour(ElectricBehaviour.TYPE)).thenReturn(null);
        assertEquals("Variac circuit is not ready; try again next tick",
                assertThrows(LuaException.class, () -> peripheral.setRatio(0.25)).getMessage());
        assertEquals(1, variac.arm.getValue());
        verify(variac, never()).setChanged();
        verify(variac, never()).sendData();
        when(variac.getBehaviour(ElectricBehaviour.TYPE)).thenReturn(circuit);
        peripheral.setRatio(0.25);
        assertEquals(0.25, peripheral.getRatio(), 1e-6);
    }

    @Test
    void rejectsValuesImmediatelyOutsideBounds() {
        assertThrows(LuaException.class, () -> peripheral.setRatio(Math.nextDown(0.01)));
        assertThrows(LuaException.class, () -> peripheral.setRatio(Math.nextUp(1.0)));
        verify(variac, never()).refreshParameters();
    }

    @Test
    void readsMechanicallyChangedRatioWithoutSideEffects() throws Exception {
        when(variac.getSpeed()).thenReturn(16f);
        variac.arm.setValue(0);
        assertEquals(0.01, peripheral.getRatio(), 1e-6);
        variac.arm.setValue(1);
        assertEquals(1, peripheral.getRatio(), 1e-6);
        verify(variac, never()).refreshParameters();
        verify(variac, never()).setChanged();
        verify(variac, never()).sendData();
    }

    @Test
    void allowsSettingAfterShaftStops() throws Exception {
        when(variac.getSpeed()).thenReturn(16f);
        assertEquals("Stop the variac shaft before setting its ratio",
                assertThrows(LuaException.class, () -> peripheral.setRatio(0.5)).getMessage());
        when(variac.getSpeed()).thenReturn(0f);
        peripheral.setRatio(0.5);
        assertEquals(0.5, peripheral.getRatio(), 1e-6);
    }

    @Test
    void repeatedSettingsReplacePreviousTargets() throws Exception {
        for (double ratio : new double[] {0.01, 1, 0.5, 0.5, 0.1}) {
            peripheral.setRatio(ratio);
            for (int tick = 0; tick < 20; tick++) variac.arm.tickChaser();
            assertEquals(ratio, peripheral.getRatio(), 1e-6);
        }
        verify(variac, times(5)).refreshParameters();
        verify(variac, times(5)).setChanged();
        verify(variac, times(5)).sendData();
    }

    @Test
    void identifiesTheSameBlockAcrossSides() {
        assertEquals("powergrid_variac", peripheral.getType());
        assertSame(variac, peripheral.getTarget());
        assertTrue(peripheral.equals(new VariacPeripheral(variac)));
        assertFalse(peripheral.equals(new VariacPeripheral(mock(VariacBlockEntity.class))));
        assertFalse(peripheral.equals(null));
    }

    @Test
    void worldAccessRunsOnServerThread() throws Exception {
        assertTrue(VariacPeripheral.class.getMethod("setRatio", double.class)
                .getAnnotation(LuaFunction.class).mainThread());
        assertTrue(VariacPeripheral.class.getMethod("getRatio")
                .getAnnotation(LuaFunction.class).mainThread());
    }
}
