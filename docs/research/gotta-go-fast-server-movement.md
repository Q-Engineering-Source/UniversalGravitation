# GottaGoFast 1.12 server movement research

## Scope and provenance

This is a source and cached-bytecode investigation only.  It does **not** authorize or implement a global anti-cheat bypass in Universal Gravitation (UG), and no GottaGoFast (GGF) build, load, server, or client was run.

- Upstream inspected: [`thiakil/GottaGoFast`, branch `1.12`, commit `3cbd2eb4345346179448e645ec4b0a9b9376f655`](https://github.com/thiakil/GottaGoFast/tree/3cbd2eb4345346179448e645ec4b0a9b9376f655).
- Read-only local reference clone: `C:/GradleCaches/artifacts/UG-GottaGoFast-reference-20260905T215211978`, created after confirming that exact target did not exist with `git -c http.proxy=http://127.0.0.1:10808 clone --depth 1 --branch 1.12 ...`; history was then fetched with the same per-command proxy.  Its `HEAD` is the commit above and its work tree was clean.
- Local mapping check: cached MCP stable 39 maps `func_147347_a` to `NetHandlerPlayServer.processPlayer(CPacketPlayer)` and `func_184338_a` to `processVehicleMove(CPacketVehicleMove)`.  Cached Cleanroom 0.6.8-alpha 1.12.2 JAR inspected with `javap` had SHA-256 `8ADA07DA5EE77DAD3527BD7278FBD05EE1FC8A597813B216A871A2D7D64CC64F`.

## What the current 1.12 tip actually changes

GGF is an old Forge coremod, not a Mixin.  Its loading plugin registers an `IClassTransformer` at the last sorting index ([loader lines 31-37](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastLoader.java#L31-L37)); the transformer returns all classes other than `net.minecraft.network.NetHandlerPlayServer` unchanged ([lines 33-41 and 73-100](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L33-L41)).  It locates these exact SRG/MCP methods and descriptors:

| Packet path | Current bytecode edit | Default result |
| --- | --- | --- |
| `processPlayer(CPacketPlayer)` | Replaces the vanilla `300.0F` Elytra and `100.0F` normal movement-limit constants with static config fields ([patterns](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L43-L61), [replacement](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L106-L133)). | Both player limits default to `Float.MAX_VALUE`: practically removes only this squared-distance threshold. |
| `processVehicleMove(CPacketVehicleMove)` | Replaces the vehicle `100.0D` constant ([pattern and replacement](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L63-L68), [lines 134-160](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L134-L160)). | Default remains `100.0`, so the current tip does **not** relax vehicle movement unless a server operator changes config. |

The three `gottagofast.cfg` entries are loaded during `FMLPreInitializationEvent`: normal player = `Float.MAX_VALUE`, Elytra player = `Float.MAX_VALUE`, vehicle = `100.0F`; each has a lower bound of `1F` ([config definition](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/GottaGoFastMod.java#L51-L54), [config defaults](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/GottaGoFastMod.java#L92-L100)).  The check is for the squared displacement/motion expression described by its own config text, not a direct metres-per-second cap.

### Transformer failure behavior

The matching policy is not strict fail-fast.  Missing each of the normal, Elytra, or vehicle instruction targets logs an error ([player path](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L124-L160)).  `processPlayer` is considered found when **either** its Elytra or normal replacement matched, and the whole class is emitted whenever either that player result or the vehicle result matched ([lines 133-160](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L133-L160)).  Consequently, a partial replacement can still produce transformed class bytes.  A caught `Exception` is logged and returns the original `basicClass` ([lines 73-100](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L73-L100)).  This is observed source behavior, not a UG pattern to copy: it lacks a verified all-target-or-no-transform invariant and does not provide strict compatibility validation.

### Checks that the current tip does not remove

The current transformer contains no `moved wrongly` target, no `CPacketConfirmTeleport`/teleport-ack target, and no flight/floating-counter target; it only replaces the three constants above.  Therefore, based on source rather than README wording:

- collision processing and the vanilla `moved wrongly` rejection remain in `processPlayer`;
- teleport/position acknowledgement handling is not hooked by GGF;
- player and vehicle floating checks/kicks are not hooked;
- all packet and position validation other than those three numeric thresholds remains outside GGF's edit scope.

This is corroborated, but not runtime-tested, against the cached Cleanroom JAR: `NetHandlerPlayServer` still contains the `100.0D` vehicle threshold, `300.0F`/`100.0F` player threshold selection, both `moved wrongly` logging sites, and floating/floating-vehicle kick paths.  The transformer matching pattern is bytecode-shape-sensitive; observing those constants alone does **not** prove that its exact instruction-list matches will succeed on Cleanroom.

## Historical source versus current source

The README still says the mod “removes” the movement check ([README](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/README.md#L1-L8)), but that describes the initial implementation, not current tip behavior.  Initial commit `ad8f3383cbf926ef51fe58a48e1249432f9aa708` searched for the `disableElytraMovementCheck`/movement-check sequence and inserted unconditional `GOTO`s to skip it ([target construction](https://github.com/thiakil/GottaGoFast/blob/ad8f3383cbf926ef51fe58a48e1249432f9aa708/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L45-L94), [skip insertion](https://github.com/thiakil/GottaGoFast/blob/ad8f3383cbf926ef51fe58a48e1249432f9aa708/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L126-L190)).  It also inserted a jump around `moved wrongly`.  Commit [`f8703623c2dc3aef777bc36b795955ef37dba004`](https://github.com/thiakil/GottaGoFast/commit/f8703623c2dc3aef777bc36b795955ef37dba004) replaced that bypass with configurable threshold substitution and added the vehicle path.  Do not attribute the old `moved wrongly` bypass to `3cbd2eb`.

## Velocity-packet boundary

GGF does not target `SPacketEntityVelocity`.  The checked Cleanroom JAR's mapped `SPacketEntityVelocity` constructor retains the vanilla per-axis clamp to `[-3.9, 3.9]` blocks/tick and encodes with `* 8000`.  At 20 ticks/s, `3.9` blocks/tick is **78 m/s per axis** (raw magnitude `31200`, below signed-short `32767`).  Thus the vanilla approximately 78 m/s packet cap remains; it is separate from `CPacketPlayer`/`CPacketVehicleMove` server movement validation.

## Loader and Cleanroom compatibility boundary

Source indicates the transformed target itself is server-side (`NetHandlerPlayServer`).  The README says client installation is unnecessary ([README lines 5-8](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/README.md#L5-L8)); on a physical client the coremod loader is still registered, but its transformer is a no-op until that server class is loaded (for example, an integrated server).  Main-source client resource-pack names occur only as string arguments to `Class.forName` in a lazy method ([lines 127-141](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/GottaGoFastMod.java#L127-L141)); test source is not evidence of a dedicated-server load result.

Unknowns requiring an isolated, authorized dedicated-server compatibility test are: Cleanroom 0.6.8-alpha's coremod acceptance/order, the exact ASM instruction-list match after all Cleanroom transforms, interaction with other coremods/Mixins, and Java 25 build/load compatibility.  None was exercised here.  Compatibility must be verified against the actual transformed target and project constraints; the present evidence does not establish that a fork or source change is necessary.  This is a compatibility boundary, **not** a recommendation to apply any global bypass.

## License and adoption status

The inspected tree contains no root `LICENSE`, `COPYING`, or `NOTICE` file.  Its Java files nevertheless carry a GPL-3.0-or-later-style header that names a different project (“ConcreteFactories”) ([transformer header](https://github.com/thiakil/GottaGoFast/blob/3cbd2eb4345346179448e645ec4b0a9b9376f655/src/main/java/com/thiakil/gottagofast/coremod/GottaGoFastASMTransformer.java#L1-L16)).  Those are the recorded source facts only; this note makes no legal-license conclusion.  No UG code was copied or changed.

## Relevance to UG

GGF can be a reference for the specific threshold-substitution mechanism, its player/Elytra/vehicle scope, its partial-match failure behavior, and the independent velocity-packet boundary.  It is not evidence that removing a global server check is safe, necessary, compatible with Cleanroom, or authorized for UG.  Any future narrowly scoped movement accommodation would need its own explicit authority, threat model, player/vehicle scope, packet/teleport/collision/floating invariants, and dedicated-server evidence; `Real GPU acceptance: not executed` for this research-only slice.
