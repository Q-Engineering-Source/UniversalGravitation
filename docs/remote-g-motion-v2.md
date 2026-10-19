# Creative remote G — key intent and bounded motion v2

## Product contract (2026-09-06)

This document supersedes the terrain admission, sample-count credit, incident STOP,
and manual-rearm requirements in `remote-creative-boost-v1.md`. It specifies the
current Creative dedicated-server experiment, not complete server-authoritative
propulsion physics or general gravity-field synchronization.

- G is explicit user intent. Only the configured G-key edge changes that intent.
  Nearby blocks, gravity magnitude, budget incidents and coordinate corrections do
  not turn it off. Transport/actor authority can be unavailable without rewriting
  the user's intent; a fresh eligible session may resume that existing request.
- Only Creative permission is implemented. A future Attitude Jetpack will enable
  attitude mode only in weightlessness and ordinary jetpack mode in gravity.
  Equipment, fuel, rewards, Survival authorization and axis-locked transit are not
  part of this change.
- Temporary corridor classification uses player **feet Y** only. Both endpoints
  belong to the restricted interval: `0 <= Y <= 256`. Its propulsion target is
  `min(selected gear, 70 m/s)`. Outside that interval, the original nine gears may
  reach 490 m/s, subject to the configured finite hard cap. Terrain type and nearby
  entities do not select the speed ceiling.
- The selected gear is retained. Low gears are neither raised to 70 nor rejected
  merely because they are below the ceiling. All ordinary FREE controls remain
  available. This is not noclip: native block/entity collision still decides the
  actual endpoint.
- High-speed entry starts a rapid continuous carrier decay at 840 m/s², rather
  than zeroing velocity or snapping it directly to 70. At 20 TPS, a 490 m/s carrier
  reaches 70 in 10 ticks (0.5 s). The scalar clamp preserves velocity direction;
  manual input may still brake. This is a simulation-tick policy, not a new TPS
  estimator or a guarantee of wall-clock speed under lag.

## Motion and protocol invariants

The fixed 70-byte payload and `ugboost1` channel remain, but the semantic handshake
version is **2**. FRAME velocity is a finite client continuity hint in m/s;
STATE's flag denotes effective restriction. The hint is not trusted evidence of
propulsion provenance. Legacy-version peers must not silently negotiate these
new semantics; formal acceptance requires the same released JAR on both ends.

G intent, transport grant, internal resynchronization, propulsion target and
carried speed ceiling are distinct state. Internal ARMING waits for a current
FRAME/position pair before seeding a movement origin. Genuine pre-grant position
traffic follows vanilla; expired companions must not be relabeled as current
movement. RESYNC uses the real native teleport ID/acknowledgement, without granting
a new epoch or fresh movement credit. Voluntary G-off releases camera/thrust while
the existing CLOSING motion allowance drains and the native position settles.

Distance credit is minted only by elapsed server ticks, with at most two ticks of
storage. Packet count is not a second distance/time gate. Each proposed endpoint
is clipped by a per-tick step ceiling and remaining aggregate credit before native
movement. Reserved candidate distance is not refunded by collision, abort or
coordinate rebase. Floating-point tolerance is finite and never creates clipped
displacement. Increasing a ceiling grants future capacity, not a new credit gift.

During carrier decay, credit uses the integral of the linear speed envelope. For
490 -> 448 m/s over one tick, it mints 23.45 m; the immediately previous carrier
is retained for one actual tick's proposal-step grace. Long gaps advance the full
decay but mint only the most recent two ticks, not historical high-speed credit.

Before native collision, a bounded non-loading XZ-column check clips the full
native broad-query rectangle to its loaded prefix. It does not call the vanilla
Y-bounded `isAreaLoaded` test, load/generate missing chunks, or classify partial
blocks as unsupported terrain. XYZ share one clipping fraction. Native movement
still runs once; the final accepted endpoint must be its finite, current-world
result within the bounded native movement envelope.

Ordinary incidents affect clipping/restriction, not G intent. Coordinate correction
is coalesced, uses native teleport/ACK and preserves camera rotation. A necessary
absolute position correction is followed by bounded continuity restoration, not
an unqualified velocity-zero STOP. Native tracker velocity remains delivered;
known Binder impulses are not duplicated. These integrations need connected-player
evidence in addition to pure helper tests.

## Test and deployment boundaries

Use the project-specific topology in `AGENTS.md`. The sole persistent dedicated
world remains `unigrav-remote-boost-20260906`; save and reuse it. Do not create world
copies, temporary smoke worlds, world snapshots or resets for this change.

The dedicated test instance currently has `remote_creative_boost=true`,
`remote_boost_maximum_speed=495.0`, `max_entity_speed=495.0`, `allow-flight=true`
and `online-mode=false`. These are user-authorized test-instance settings, not
changes to the distribution defaults or permission to alter another server.

Formal test sequence:

1. Run the production budget/cadence/corridor, loaded-prefix, key-intent/carry and
   protocol regressions; fresh build and exact release config-to-refmap check.
2. Stop the dedicated server through its original stdin; verify save and exit.
   Replace only the UG JAR, record its SHA-256 and restart the same persistent world.
3. Confirm ready, no new fatal/Mixin failure, and run actual common-side codec/native
   diagnostic checks. These checks do not simulate a connected player.
4. After the GPU client is closed, deploy that exact released JAR to its canonical
   Z instance and verify the same SHA-256. Never launch a local 3D client as fallback.
5. In one correlated GPU/server window, test G on/off beside full and partial
   blocks and in gravity; all low gears; 490 above 256/below 0; both inclusive
   altitude boundaries; oblique fast reentry; wall collision; correction/ACK;
   repeated on/off, GUI, reconnect and lifecycle cleanup. Confirm no incident G-off,
   no unexplained zero-speed correction, no hidden gain of aggregate distance, and
   selected/target/carrier HUD agreement. Collect both logs and GPU recording.

Current-turn runtime/build outcomes and exact artifact identity belong in the
verification report, not this normative contract. A lower gate never closes a
higher one. Until this candidate has actual correlated GPU evidence:

`Real GPU acceptance: not executed`
