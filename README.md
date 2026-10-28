# PowerGrid CC

A server-only addon for Minecraft **1.21.1**, NeoForge **21.1.249**, and
Create: Power Grid **0.6.2**. Adds `powergrid_variac` and `powergrid_punch_card_reader` CC:Tweaked
peripherals to existing variacs and punch card readers. No replacement PowerGrid
jar or new client mod is needed.

## Installation

Build with Java 21 and `gradle build`, then put
`build/libs/powergrid-cc-1.1.0.jar` in the **server's** `mods` directory.
Remove the previous `powergrid-cc-1.0.0.jar` when upgrading; 1.1.0 includes both
features. The original CC-cpg-pinout mod is not required.
Restart the server. Clients do not need this addon; keep their normal PowerGrid
and other modpack dependencies installed.

Requires CC:Tweaked **1.120.0 or newer**, PowerGrid **0.6.2**, and Create
**6.0.9–6.0.x**, along with those mods' normal dependencies. Compilation and
tests use CC:Tweaked 1.120.0 and Create 6.0.10. For singleplayer, install the
addon in that instance so its integrated server can load it.

Place a computer beside a variac, or connect it through an attached wired modem
and enable the modem's peripheral connection. The peripheral is available from
any side. Stop the variac's shaft before setting a ratio.

```lua
local variac = peripheral.find("powergrid_variac")
assert(variac, "No variac connected")
variac.setRatio(0.5)
print(variac.getRatio()) -- approximately 0.5
```

For several variacs, use `peripheral.getNames()` and `peripheral.wrap(name)` to
select one explicitly.

## Lua API

| Method | Behavior |
| --- | --- |
| `getRatio()` | Returns the current ratio as a number from 0.01 to 1.0. |
| `setRatio(ratio)` | Immediately sets the ratio; returns no values. |

The argument is a fraction, not a percentage or the goggles' displayed ratio:
`0.01` corresponds to **100:1**, `0.5` to **2:1**, and `1.0` to **1:1**.
This is the transformer setting, not a guarantee of measured output voltage
under every electrical load. Small floating-point differences are normal.

`setRatio` raises a Lua error for non-finite/out-of-range numbers, a rotating
shaft, an unavailable block, or a circuit that has not initialized yet. It does
not clamp invalid input. Use `pcall` if your program needs to handle failures.
A circuit-not-ready error can be retried on a later tick.

Both methods run on the server thread. Setting a ratio resets the tuning arm's
position and target, refreshes the electrical simulation, marks the block for
saving, and sends PowerGrid's normal client update. Subsequent shaft rotation
resumes normal mechanical control; the addon does not permanently lock the arm.
Variac control uses public PowerGrid methods. Punch card control uses one
common-side mixin to access the existing switches and prevent card rows from
overwriting computer-controlled states. No new blocks or custom packets are used.

## Punch card reader pinout

Attach a computer or enabled wired modem to a normal PowerGrid punch card reader.
It exposes the `powergrid_punch_card_reader` type without requiring a card or shaft rotation.

```lua
local p = peripheral.find("powergrid_punch_card_reader")
assert(p, "No punch card reader connected")
p.setByte(0)                  -- take control with all switches open
p.connectPin({1, 3})          -- close pins 1 and 3 to common
p.setPins({[1] = false, [8] = true}) -- change only these two pins
print(p.comparePin(2))        -- pin 2 voltage minus common voltage
print(p.comparePin(2, 4))     -- pin 2 voltage minus pin 4 voltage
local volts = p.pinsVoltage() -- all nine voltages relative to common
local states = p.pinsConnected()
p.setByte(170)               -- pins 2, 4, 6, 8 connected
p.releaseControl()           -- normal card control resumes next electrical tick
```

| Method | Behavior |
| --- | --- |
| `pinsConnected()` | Boolean table keyed by pins 1–9; common (9) is always true. |
| `connectPin(pinOrTable)` | Connect one pin, or a table's pin-number values, to common. |
| `disconnectPin(pinOrTable)` | Disconnect one or several pins from common. |
| `setPins(states)` | Partially update a table keyed by pins 1–8. Boolean or finite numeric values; zero means false. Numeric-string keys are also accepted. |
| `setByte(value)` | Set all eight switches using an integer 0–255; bit 0 is pin 1, bit 7 is pin 8. |
| `comparePin(pin, referencePin=9)` | Signed voltage difference, in volts. Both pins may be 1–9. |
| `pinsVoltage(referencePin=9)` | Table of all nine signed voltages relative to the reference pin. |
| `pinLayout()` | Five rows describing the reader's physical terminal arrangement. |
| `isComputerControlled()` | Whether a saved computer override is active. |
| `releaseControl()` | Remove the override and force card-row re-evaluation on the next electrical tick. |

The first nonempty write takes control, preserving unspecified switches. Reads
and empty-table updates do not take control. The override is saved in the reader's
block-entity data and restored after world/chunk reload or circuit rebuild. It
persists after the computer disconnects; call `releaseControl()` to give control
back. Releasing an empty reader opens all switches on the next electrical tick.
Inserted cards, their movement, and their comparator output continue normally,
but cannot change switches while computer control is active. Contact resistance
and heating still use PowerGrid's reader simulation; this is not a voltage source.

Pin numbers match the reader's existing terminal labels. Facing its terminals:

```text
    9 (common)
 1             8
 2             7
 3             6
 4             5
```

The API is an independent implementation compatible with
[CC-cpg-pinout](https://github.com/INEEDCHATPROGRAAAAAMS/CC-cpg-pinout)
(reference commit `590e86b4bd437cff33246fda23b0c14fe6272d04`). Unlike that mod's
custom block, PowerGrid numbers the right bank bottom-to-top; `pinLayout()` reports
the actual reader layout. Update existing scripts to use `peripheral.find("powergrid_punch_card_reader")`,
but wiring must follow the reader's labels. This addon does not convert existing
custom Pinout blocks into readers.

Pin numbers must be finite integers. Invalid table entries reject the entire
operation before any switch changes. Fractional values are rejected rather than
silently truncated. Unavailable readers and uninitialized circuits raise Lua
errors instead of returning misleading zero-voltage readings. All world access
runs on the server thread; voltages reflect the latest simulation state.

## Validation

`gradle build` runs the JUnit tests every time. Tests use the released mod classes
and a real tuning arm, with mocked world/circuit boundaries. They cover ratio
limits, unchanged settings across arm ticks, refresh/save/sync calls, invalid
input, rotating shafts, unavailable blocks, peripheral identity, and server-thread
Lua annotations.

The suite also checks values immediately outside the bounds, repeated settings,
read-only access during mechanical rotation, missing/client worlds and missing
arms, recovery after shaft stop or circuit initialization, and saved/client NBT
round trips at both ratio endpoints and an intermediate setting.

Pinout tests exercise the real PowerGrid switched wires, all 256 byte values,
all pins and voltage references, atomic validation, partial updates, persistent
override restoration, card-write suppression and release, and the reader layout.
World and node voltage sources are mocked; these tests do not launch NeoForge or
apply the mixin through its runtime loader.

Live multiplayer validation is still required: connect a computer/modem, change
the ratio, inspect the goggles and arm from a client without this addon, then
save/reload the world and confirm the setting remains. Also verify mechanical
control resumes after starting the shaft.
For the reader, also check server startup/mixin application, direct and modem
peripheral discovery, voltage readings against a meter, card-versus-computer
control, chunk/world reload, and `releaseControl()` with both inserted and absent
cards. Clients should join with the normal pack and without this addon.

Dependency versions are pinned in `build.gradle`; Create's bundled libraries
are extracted for compilation and testing only. Dependencies are not bundled
in the addon jar. The `upstream/` checkout is reference material and is not used
to build the addon; the build resolves PowerGrid's released 0.6.2 artifact.
