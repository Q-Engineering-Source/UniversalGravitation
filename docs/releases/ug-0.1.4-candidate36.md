# UG 0.1.4 / Candidate36

Status: saved candidate pending user installation and runtime acceptance. SOURCE review,
compilation and artifact structure do not close gameplay NGs.

## Changes

- A player-requested Free rotation rejected by an actual target/midpoint overlap can retain
  the accepted orientation and independently prove the same movement intent. The rejected
  attitude remains pending; required reference transitions and unresolved source geometry
  do not use this branch. A complete new capture, original body profile, owner fences,
  trusted resize and single movement reservation remain required.
- A missing sweep-prefix certificate can enter the existing all-obstacle original capsule
  prefix proof. Positive conservative progress carries its actual installation proof and
  creates no unsupported contact normal, landing or slide event.
- DE and DA inherited armor use fresh public `ICustomArmor` summary parameters for native
  flight speed, vertical override, inertia and predicted jump effects. Server-local armor
  and the selected input slot determine movement authority. Prediction retains sampled
  parameters for replay. Scoped actor-specific sprint input restores nested context in
  `finally`; ordinary DE callbacks outside that scope retain their original behavior.
- Ordinary shoes no longer trigger the client's blanket magnetic-boot prediction refusal.
  The server still refuses prediction for qualified magnetic boots and active boot memory.
- Existing bounded server diagnostics and new bounded client input diagnostics report
  sampled flight parameters, input identity, and accepted/result velocity. Eye completion
  preserves rejected-attitude diagnostics. Server samples are bound to the exact accepted
  snapshot and world tick, so stale parameters are not reported as current.

## Build and artifact identity

Java 25.0.2; protocol 21; 631 frozen inputs; 1301 frozen dependencies. The exact invocation
and input hashes are retained with the candidate. The authorized offline `jar` invocation
executes exactly `generateJavaTemplates`, `verifyChbArtifacts`, `compileJava`,
`generateResourceTemplates`, `processResources`, `classes`, `jar`, and `remapJar`.
Native build exit 0, 50.847 seconds; 14 annotation-processor warnings unchanged from C35.
No automated tests, JVM probes, simulation or server/client startup were performed.

Runtime `unigrav-0.1.4.jar`, 2,899,465 bytes:
`CA8689C0267FBBEC89069A33944E9EC8A06D1D00341A2D4DAEAA345D745CDFE1`.

Development JAR, 2,911,653 bytes:
`F3FBC2A368F67E27097106802DC3FE0C0A4E50B4668A51D9556AAFE5716EE9A0`.

CHB remains exactly `0.1.0-ug-telemetry-candidate.1`, runtime SHA-256
`DB1C5987DFE5257C4AA55EE68934707F149AC0553B83712DCDD15C89569FDA20`.
The actual Forge dependency annotation and metadata were statically parsed and agree.
No optional library is embedded, and DE/DA are not new required runtime dependencies.

The DE compile-only public ABI is the installed 2.3.28.354 artifact, supplied through
`-PdraconicFlightAbiJar=<exact jar>`, SHA-256
`1EECDEFA00056EFAC98FF56E25F7ED840B70E2D809C628E38C9C5B5518FF14FF`.
Existing exact CHB, UIE, Aqua, Artemis, NFR and ModularUI build inputs remain required;
see the candidate's invocation and dependency manifests. Development/classpath artifacts
are not installation files.

## Acceptance still required

The user installs matching runtime UG JARs on both sides. G airborne flight was previously
confirmed to work and must remain working. G ground contact/movement and non-G DE/DA
high-speed flight still require user retest, together with ordinary Y+ movement and WASD,
short/held jump, non-G creative flight, air/ground transitions, input release and NFR
camera/model consistency. Preserve failing candidates and paired logs. No runtime PASS
is claimed by this commit; previous unclosed NGs remain open.

Original canonical Mainline bytes were preserved before integration. Extra historical test
and performance sources remain in place. Generated local evidence and private project
memory stay outside public Git history.
