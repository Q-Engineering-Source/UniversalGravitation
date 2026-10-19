# Gravity and magnetic floors — confirmed product scope

Status: requirements captured on 2026-09-09. Runtime implementation has not begun.
This file records confirmed user decisions, not a completed architecture or an
acceptance claim. Ring-station and rotating-reference-frame work is deferred.

## Confirmed behaviour

- Floors have six possible face orientations. Their designated top face supplies
  the outward normal; their acceleration points back toward that face.
- Each floor affects a `1 x 1 x 16` block column projected from that face. Multiple
  floor blocks tile a larger area; the single-floor lateral footprint is not an
  unconfirmed sphere or wide square.
- Tentative default profile: constant strength from the face through8m; decrease
  from8 to16m, reaching zero at16m; no contribution beyond the range. Distances,
  strength and falloff must be configurable. Exact falloff function is not yet
  an adopted implementation decision.
- Real overlaps SUM VECTORS. Do not silently substitute priority/strongest-only
  gravity. Cancellation and diagonal resultants must remain representable.
- User-specified overlap examples are acceptance cases: a gravity floor beneath
  a magnetic floor contributes through the magnetic floor; aligned contributions
  increase acceleration, side-facing contributions cause lateral attraction,
  and airborne intersecting fields cause diagonal acceleration. Facing floors
  with equal opposing directional contributions cancel at their balance region.
  The magnetic component still applies only to eligible actors. A zero resultant
  is zero added acceleration, not a request to erase existing velocity or unrelated
  forces. Physical block collision remains enabled.
- User accepts six-cardinal physical standing even when actual resultant force
  is diagonal. Selecting a standing reference does not alter the force sum.
- Physical standing/collision must eventually allow feet on the target wall or
  ceiling, with consistent ground/jump/step/crouch behaviour. Visual BodyPose or
  horizon alignment alone is not completion.
- Magnetic floors affect configured magnetic drops, mobs and equipped actors,
  not all entities in their area. Nonmagnetic items/mobs remain unaffected by
  that magnetic floor, without implying immunity to unrelated ambient gravity.
- Use config-based magnetic object lists, analogous to the existing mass data.
  Mob magnetic parameters belong in existing `config/unigrav/mass_presets.json`;
  DO NOT introduce a separate mob-magnetism table. Item/boot identification must
  be configurable; iron boots and another mod's magnetic boots can be recognized
  by explicit IDs rather than inferred names/materials. No new boot item was
  authorized merely to make the filter work.
- Magnetic dropped-item rules must match exact namespaced item registry IDs and
  support NBT conditions. Numeric registry IDs are not an allowed replacement.
- User permits RoughlyEnoughIDs (REID) as a prerequisite if required. A concrete
  version/dependency choice and existing-world format implications are not yet
  verified; no REID installation or world conversion is authorized by this note.
- Magnetic boots must be able to adhere to any qualifying magnetic block surface,
  not just magnetic-floor blocks. Add a magnetic-block table with exact registry
  IDs and NBT conditions. Ordinary block state and block-entity NBT are distinct
  inputs; no-NBT blocks must not be assigned invented block-entity data.
- Magnetic block classification is not a request to make every such block emit
  the floor's16m column. Passive magnetic response and active boot adhesion are
  separate capabilities; their precise activation/range remains to be finalized.
- JSON defines gravity-floor/magnetic-floor registrations. Exact schema, default
  block identities, and orientation mapping for existing blocks remain to be
  finalized. JSON alone cannot grant a missing facing property to an arbitrary
  existing block.
- G remains explicit key-controlled intent. No floor arrival, equipment change
  or physical-reference transition may automatically toggle G.
- In answer to the question about active G, the user specified: Creative mode
  suppresses magnetic attraction; Survival mode remains susceptible. This does
  not grant Survival G/propulsion for free. Do not silently generalize this
  answer into immunity for every Creative state when G is off.

## Already verified repository facts

- `MassPresetsLoader` loads species entries during preInit, preserving old-file
  missing-key fallbacks; `BodyPhysics.SpeciesPreset` is the current public species
  record. `BodyPhysics(massKg, volumeM3)` remains the bulk-physics value.
- Current `GravityContribution.accelerationAt(position,time)` has no actor or
  dimension context. Registering a magnetic floor unconditionally in that global
  field would not implement the required actor filter.
- Existing region contributions are finite, additive and dimension-agnostic;
  block-floor discovery/lifecycle and magnetic filtering do not exist yet.
- Remote G/boost has no physical-upright synchronization. Natural field delta-v
  currently reaches the local player only for an integrated server; dedicated
  `EntityPlayerMP` deliberately skips server-side delta-v integration.
- Current UIE BodyPose/horizon logic is presentation. Native player collision
  stays world-axis-aligned; no UG floor-aware player collider exists.
- Reference `Up_And_Down_And_All_Around` uses six signed-permutation frames and
  gravity-aware AABBs, with broad movement/feet/eye/network hooks. Its old ASM
  superclass and camera replacements are not approved for direct import. See
  `up-and-down-collision-reference.md` for source citations and license identity.

## Open product/engineering boundaries

These are not silently decided by the requirements above:

- How floor contributions compose with nonzero ambient gravity and existing
  takeover exemptions.
- Any broader Creative magnetic immunity outside active Personal G, if desired,
  remains unspecified; active-G Creative suppression/Survival susceptibility is
  already confirmed above.
- Which existing block states are default floor registrations, or whether new
  standard UG six-facing floor blocks should also be introduced.
- The stacking example confirms that a magnetic floor does not occlude a gravity
  floor beneath it. Any additional general field-occlusion rules, exact actor
  sampling/boundary conventions, and cardinal-reference selection remain to be
  finalized; do not negate the confirmed stacking example with a visibility test.
- NBT matching mode/type semantics, item subtype/state selectors, live block-entity
  data changes, and magnetic-boot attachment/release range and control.
- Necessary server-authoritative state/sync and native collision integration,
  including nonplayer behaviour versus a full six-direction mob AI controller.

## Advisory and validation state

A compact repository-fact packet with all confirmed decisions was actually sent
through the built-in browser to the designated UG adviser at
`https://chatgpt.com/c/6a9a8e74-b9e4-83e8-b333-41d48d86953d`.
The composer explicitly displayed `5.6 Pro`. The first response completed after
12m57s and was read fully. A bounded follow-up including exact item/block ID+NBT,
REID permission, magnetic-boot adhesion and active-G Creative suppression/Survival
susceptibility was then actually submitted; the amendment completed after5m30s
and was also read fully. Retrieval of both advisory replies is complete.
Advisory suggestions and unverified native/API gates are recorded separately in
`.pensieve/.state/gravity-floors-20260909/advisor-summary.md`. This is not yet an
adopted implementation contract or a claim that physics/JSON/runtime validation
has passed. In particular, the suggested bounded NBT predicate subset must not
be presented as arbitrary list/array/full-NBT support without further validation.

Only requirements/glossary documentation was changed. No gameplay source, JSON
runtime config, artifact, server/client process or test world was modified.

Real GPU acceptance: not executed
