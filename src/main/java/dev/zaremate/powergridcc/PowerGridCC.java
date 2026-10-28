package dev.zaremate.powergridcc;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.patryk3211.powergrid.collections.ModdedBlockEntities;

@Mod(PowerGridCC.MOD_ID)
public final class PowerGridCC {
    public static final String MOD_ID = "powergrid_cc";

    public PowerGridCC(IEventBus bus) {
        bus.addListener(PowerGridCC::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), ModdedBlockEntities.VARIAC.get(),
                (variac, side) -> new VariacPeripheral(variac));
        event.registerBlockEntity(PeripheralCapability.get(), ModdedBlockEntities.PUNCH_CARD_READER.get(),
                (reader, side) -> new PinoutPeripheral(reader));
    }
}
