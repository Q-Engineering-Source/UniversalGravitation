# Native walking, GravityChanger, and optional YSM compatibility research

## Scope and evidence

Date: 2026-10-07, Asia/Shanghai. This is a source and artifact investigation requested by the user. No business implementation, game launch, server restart, deployment, or world/configuration change was performed. The user subsequently confirmed this repair objective: continue until repaired, with no time limit and no indefinite delay. The current scoped contract, method-level plan and main-chat goal entry are in [the repair planning package](../repair-plans/player-movement-ysm-20261007/README.md). This research note is not an OK delivery or an update to the sole progress ledger; specific runtime instances and final artifact acceptance still require live evidence.

The user supplied these actual-use observations:

- AquaAcrobatics and resizing potions change the player collider; affected players cannot move until kill/respawn resets them.
- Sneaking during walking produces a small forward lurch. The user explicitly confirmed actual position displacement and poor movement feel, so a purely visual eye-height explanation is excluded for that observation.
- Player model six-direction transforms and rotation also require optional compatibility with the supplied YSM artifact.

The evidence levels are deliberately separate: user-observed gameplay failure; retained server logs; isolated construction of the logged Dimensions input; cached Minecraft source; runtime UG/YSM artifact bytecode; current UG/UIE checkout source; external reference source. No new gameplay reproduction, fix, or Real GPU acceptance has been performed.

| Evidence | Fixed identity |
| --- | --- |
| UG checkout inspected | `111f5c99ea228067f882dfb9bbeb0de68cd77b5b`, with pre-existing untracked `docs/` preserved |
| Server UG R99 JAR | `967095` bytes; SHA-256 `2C7CE6E88FCAE179C1B2437B6DE854E2B442953281F6F96A554C152D75097325` |
| qouteall/GravityChanger | ref `1.20.4`, commit `de1703581e455b8b26f1388ac80812df8fc95b1a`; MC 1.20.4 |
| Gaider10/GravityChanger | ref `master`, commit `551785ab0246416860b7511fc74fff65b7752b7d`; MC 1.18.1 |
| Supplied YSM JAR | `948533` bytes; SHA-256 `5DFE1A06E058FF7EB006918E4EBC60C18C450FFC441CAC1A5CFF0766251F1D60`; modID `yes_steve_model`, version 1.1.9 |
| Cached 1.12.2 / Cleanroom 0.6.8 source archive | `minecraft-cleanroom-0.6.8-alpha-159832fe-1.12.2-sources.jar`; SHA-256 `D3E5FFD425423D57E6DC39BB346A17937E1148379B8D5F7C0A89E3FCF55A0349` |

The cached Minecraft source is a baseline, not proof of the live server's final transformed classes. IntelliJ currently has no open UG project; Minecraft Dev tools were not exposed. Files, archive inspection, and Java 25 `javap` were used. Current R101 source is not interchangeable with the server R99 artifact; R99 bytecode was separately checked for the relevant control flow.

## Minecraft 1.12.2 baseline

The cached source archive is at `C:/GradleCaches/caches/unimined/maven/net/minecraft/minecraft-cleanroom-0.6.8-alpha-159832fe/1.12.2/minecraft-cleanroom-0.6.8-alpha-159832fe-1.12.2-sources.jar`.

- `net/minecraft/entity/player/EntityPlayer.java:372-415`, `updateSize`: ordinary standing is width `0.6F`, height `1.8F`; sneaking keeps width `0.6F` and changes height to `1.65F`. It checks the candidate box for collision before `setSize`.
- `net/minecraft/entity/Entity.java:363-403`, `setSize`: shrinking width constructs the box around the current position. Equal-width height changes keep the old minX/minY/minZ. Width growth may itself call `move(SELF, oldWidth-newWidth, 0, oldWidth-newWidth)`. Therefore general resize callbacks cannot be assumed to be position-neutral. Ordinary equal-width sneaking does not take that width-growth displacement branch.
- `Entity.java:727-790`, the sneak edge clamp reduces horizontal requested movement toward zero by `0.05` while checking support. It is not the complete sneak speed implementation, and does not supply extra forward displacement.
- `EntityLivingBase.java:2075-2174`, ordinary travel derives acceleration from AI move speed and block slipperiness, calls `moveRelative`, performs the movement/collision pass, then applies gravity and drag. Reusing numerical constants without preserving this order is not evidence of matching the original feel.

UG `CardinalMovementResolver.java:159-175` already contains equivalent local-frame `0.05` sneak edge reduction. Inspection therefore supplies no reason to delete or weaken edge protection to treat the lurch. UG also has a separate authoritative player step; its ordering and the client/server input/pose/commit boundary must be compared, not just its collision loop.

## What the two GravityChanger implementations actually teach

Their version baselines are fixed by [qouteall gradle.properties](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/gradle.properties#L13-L14) and [Gaider gradle.properties](https://github.com/Gaider10/GravityChanger/blob/551785ab0246416860b7511fc74fff65b7752b7d/gradle.properties#L5-L6).

1. The inspected sneak edge injections return without overriding the original method for DOWN. For other cardinal directions they convert world displacement to player-local coordinates, reduce local x/z toward zero, perform support queries with the local-down offset transformed to world, and transform the result back. No position setter or resize-induced translation occurs in these methods. Sources: [qouteall PlayerMixin:150-215](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/PlayerMixin.java#L150-L215), [Gaider PlayerEntityMixin:212-269](https://github.com/Gaider10/GravityChanger/blob/551785ab0246416860b7511fc74fff65b7752b7d/src/main/java/me/andrew/gravitychanger/mixin/PlayerEntityMixin.java#L212-L269). This does not prove either reference fixes the user's sneak transition lurch.
2. The read movement Mixins retain the original travel body and convert selected coordinate-dependent inputs and queries. For example, qouteall converts the travel look vector world-to-player for non-DOWN. It also scales the gravity constant by an API gravity-strength value, so DOWN cannot be described as entirely unmodified travel. Sources: [LivingEntityMixin:117-131](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/LivingEntityMixin.java#L117-L131), [LivingEntityMixin:561-563](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/LivingEntityMixin.java#L561-L563).
3. The move/collision boundaries distinguish local motion, world motion, and world AABBs. Source: [qouteall EntityMixin:212-225](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/EntityMixin.java#L212-L225), [collision conversion:298-395](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/EntityMixin.java#L298-L395). The cardinal axis permutations make an axis-aligned solver possible; this is not an arbitrary-orientation CHB capsule solver.
4. AABB construction subtracts the existing entity position, rotates the shape around that origin, then adds the same position back. Pose-fit queries also rotate the candidate dimensions. This is a useful anchor discipline rather than a prescription to recover a new horizontal position from a changed box center. Sources: [qouteall EntityMixin:127-144](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/EntityMixin.java#L127-L144), [qouteall pose-fit:340-356](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/mixin/PlayerMixin.java#L340-L356), [Gaider EntityMixin:106-135](https://github.com/Gaider10/GravityChanger/blob/551785ab0246416860b7511fc74fff65b7752b7d/src/main/java/me/andrew/gravitychanger/mixin/EntityMixin.java#L106-L135).
5. qouteall's gravity-direction transition has its own rotation-center, previous-position, velocity and anti-suffocation handling. It must not be applied indiscriminately to every sneak/resize operation. Sources: [GravityComponent:312-395](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/GravityComponent.java#L312-L395), [rotation center:399-415](https://github.com/qouteall/GravityChanger/blob/de1703581e455b8b26f1388ac80812df8fc95b1a/src/main/java/gravity_changer/GravityComponent.java#L399-L415).

No Aqua/Pehkui-specific implementation was found in the inspected refs. Neither reference establishes compatibility with the supplied AquaAcrobatics/resizing-potion stack. Their modern Pose/EntityDimensions/VoxelShape injection points cannot be copied into 1.12.2 width/height/setSize/updateSize, and a second movement writer would violate UG's single owner, single commit, and shared budget contract.

## The UG resize/teleport recovery gap

The retained logs establish repeated Dimensions exceptions using width `0.6000000238418579`, height `0.5999999642372131`, eyeHeight `0.4000000059604645`. The width-height difference is `5.960464477539063E-8`. Constructing the actual R99 `PlayerBodyShape.Dimensions` with this input using Java 25 JShell rejects it with the logged exception; equal width/height is accepted. This is an isolated constructor verification, not a gameplay replay.

The R99 bytecode confirms that `confirmNativeTeleport` constructs Dimensions before clearing `teleportPending`, and `advanceOwned` returns immediately while that flag is set. That is a concrete failure-to-progress mechanism. `onRespawn` retires the previous session, consistent with the user's kill/respawn recovery observation; this alone does not identify which route occurred in every reported incident.

Current source `PlayerPhysicsServer.java:80-103` observes the completed native pose operation only when the pre-operation actor still matches its accepted placement. `PlayerEnvironmentSampler.java:98-99,208-214` rejects position/AABB/width/height/eye-height disagreement as ACTOR_CHANGED. R99's native pose bytecode has the same anchored-entry restriction. A legitimate resize occurring outside that observation can thus leave a stale accepted shape and a persistent rejection condition. Runtime sequencing remains to be reproduced.

The actual AquaAcrobatics 1.15.4 JAR has an independent `EntityPlayerMixin.recalculateSize` path that updates the AABB and width/height. Its data-parameter callback can invoke that path and then `ArtemisLibIntegration.updateSwimmingSize`. Its updateSize injection also calls Forge post-player-tick and updates the pose. This requires an actual callback-order audit; hooking only one native method is not evidence that every third-party size change was observed.

Repairing only a near-equality comparison would leave the stale-shape/recovery problem. A future repair must distinguish a legitimate, collision-checked size/pose operation from arbitrary stale writes, preserve accepted actual bounds, maintain one owner and budget, and provide an explicit recoverable boundary for representation or teleport-confirmation failure. Genuine flat shapes that do not satisfy capsule H >= W need a deliberate supported representation or movement handoff; they must not be silently changed into a fictional collider or permanently freeze the player.

For the real forward lurch, the distinguishing capture is the first nonzero extra displacement: record native pose callback entry/exit and physical movement commit separately, including position, AABB, width/height, eye height, accepted transport/feet/center, pending correction, controls, budget and committed delta. Resize effects, repeated input consumption, and prediction/correction are hypotheses until that first-delta boundary is observed. No instrumentation was installed in this research slice.

## Optional YSM adapter: actual artifact evidence

The supplied file is `D:/0_Minecraft_Server/Wi_Server/Q-Eng-Server/Main-RingBeltSpaceStation-crl_0.6.8/mods/YSM-yesstevemodel-1.12.2-release-1.1.9.jar`, with the identity in the table above. Archive and Java 25 javap inspection produced these facts:

- `com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent.onRender(RenderPlayerEvent.Pre)` can cancel the original render and synchronously call `CustomPlayerRenderer.doRender`. A RenderPlayer-only rotation adapter does not cover this replacement route.
- `com.elfmcys.yesstevemodel.client.renderer.CustomPlayerRenderer` supplies the selected custom model/texture, publishes `SpecialPlayerRenderEvent`, and uses its parent `com.elfmcys.yesstevemodel.geckolib3.geo.GeoReplacedEntityRenderer` rendering pipeline.
- That renderer implements its own render flow, matrix lifecycle, position translation, root `func_77043_a` rotation, body/head interpolation, model animation and attached layers. The position-translation-to-root-rotation boundary is the concrete adapter candidate. Sleep has a dedicated branch; a parent doRender hook does not cover the whole flow.
- `com.elfmcys.yesstevemodel.event.api.SpecialPlayerRenderEvent` exposes player, custom-player and model ID, but no root quaternion setter, renderer, partialTicks or paired matrix post hook. It must not be presented as a ready-made six-direction pose API.
- `CustomPlayerModel` adds native headPitch/netHeadYaw to the Head bone. Root rotation and head-local angles must have an explicit relative-frame contract to avoid applying world yaw/pitch twice. This is a mechanism risk, not a demonstrated rendering failure.
- Renderer width/height scales are visual model scales, not UG collider dimensions. The model's FIRST_PERSON_HEAD_POS is a local Y value; no camera/eye-height consumer was found in this JAR. It does not establish a six-direction world eye anchor.
- First-person arms, items, elytra layers, names/shadows and third-party attached rendering have distinct paths. Body-root compatibility does not establish those paths' acceptance.

UG currently registers `FlightApi.registerBodyPoseProvider` in `RevoUiSixDofCamera.java:178`; the inspected provider at lines 368-373 is local-player-only. `SixDofBodyPresentation` separates body yaw offset from camera tracking. The inspected UIE checkout targets RenderPlayer, with additional branch restrictions; its running client JAR was not identity-verified. Existing local pose provision cannot be counted as remote-player/YSM coverage.

The proposed adapter is optional and presentation-side, preserving UG physical-state ownership and UIE camera/input/picking/render ownership. It should consume the accepted/interpolated body frame, apply root orientation once inside YSM's existing matrix lifetime, and express head angles in that root's local frame. It should not rotate the world render origin or persistently change the player position, collision box, yaw/pitch to rotate a model.

Loading must be limited to the client, YSM presence and a verified supported version/structure. Common/server initialization must not link YSM client types. No YSM means the optional adapter is skipped and the existing player renderer remains usable. A present unsupported ABI must expose a clear compatibility-unavailable result, not log fictitious success. The optional enabled/disabled policy and default are specified in the current repair planning package.

## Proposed acceptance and remaining decisions

The user's subsequent instruction defines the complete repair outcome and removes a fixed deadline. See [the scoped acceptance contract](../repair-plans/player-movement-ysm-20261007/01-目标与验收合同.md) for walking/sneak correctness, resize and teleport recovery, zero-size seat binding, optional YSM compatibility, and the explicit completion gates. Medium/high objectives were not separately opened; they do not enlarge this repair scope. Live test-instance identity, client artifact hashes and actual gameplay/GPU/user acceptance remain to be established by the executing main chat.

Meaningful verification should cover:

- Ordinary ground walk/run/diagonal input, sneak press/release, edges, steps, jumping and block slipperiness. Size-only transitions add no unauthorized horizontal movement; movement feel is checked against the 1.12.2 baseline under the same gravity and configuration.
- Aqua swim/crawl enter/exit; shrink/grow and effect expiry; collider changes near obstacles; teleport/respawn while resized. The player recovers without kill and accepted physical state matches actual collision geometry.
- Optional adapter absent/disabled and present/enabled; dedicated server never loads client renderer classes.
- The supplied YSM artifact with all six cardinal body-up directions and continuous full body rotation, both local player and another player's observation. Root transforms occur once; feet/head alignment, model switch, own/other-model disable settings, attached layers and first-person paths are checked.
- Source/static and unit/integration results remain separate from final candidate dedicated-server and actual Real GPU/two-player evidence. Build success alone cannot close gameplay or model acceptance.

This research saved one note only. No source, dependency, configuration, running artifact, or unique progress-ledger change was made. No commit/push or deployment was performed. Real GPU acceptance: not executed.
