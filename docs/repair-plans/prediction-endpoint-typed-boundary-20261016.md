# 026 prediction endpoint typed boundary

Scope: run32 candidate9 first product NG. Existing Hard thread `01a0de5a-21a2-7960-970e-d19bfb0c6c44` performed a read-only method review. This records the adopted contract, not executed repair or acceptance. Keep run32 original crash and all previous NG.

## Confirmed cause

Sampler.surface returns UnsupportedSurface(NATIVE_BLOCK_EFFECT) for a non-whitelisted block. Client.predictionEndpoint manufactures IllegalStateException from that expected typed rejection. Owner.predict calls the endpoint before state/history commit; onClientTick recovers and rethrows, exiting the actual client. The initial sample can be airborne and skip its support probe before landing on an unsupported endpoint. The frozen raw does not identify the actual endpoint block/state: NOT_OBSERVED.

## Repair contract

- Replace the endpoint Consumer with a typed validator used by predict and reconcile. Ungrounded endpoints explicitly qualify without a surface probe. Expected UnsupportedSurface and no-cause UnavailableSurface terminate that prediction owner and return empty/false with a concrete reason. Existing exact-owner recovery runs once; caller returns before projection, gait, session.input or inputSendSucceeded.
- Do not catch by exception message or suppress every failure. SAMPLING_FAILED with cause, real collision/world/getter exceptions retain original cause and stack through the existing explicit failure boundary. No cause-to-null conversion.
- Predict computes scratch, validates endpoint, then commits state/previousEye/history/lastTick and accepted slot. Prefer delaying new-slot metadata as well. If metadata/history were already changed, rejection still makes the owner terminal. Work already consumed is not refunded.
- Reconcile validates each replay endpoint and publishes no intermediate predicted state. Any rejection makes the owner terminal. History.after may already prune history: never leave that owner active. Preserve received canonical STATE and real server boundary facts; prediction refusal cannot undo authority.
- Validator captures exact world/actor/owner context. Check installed lifetime before and after callback. Reentrant TP, respawn or successor installation only ends the old prediction. Never use dynamic current acceptedOfferContext to recover or retire a successor from an old callback. Keep existing mutation/lease guards; no old-actor rollback before projection.
- Keep the two Y_POS support probes: pre-step minY-1 serves friction and matches the server normal-dry probe; physicalFoot-0.2 serves endpoint/native tail qualification. Their different cell selection for thin/partial blocks is not sufficient evidence of a bug. Do not unify them, expand the whitelist, remove endpoint checks or change 025 death lease gates to hide this NG.

## Necessary verification

1. Real supported/airborne start to unsupported landing: typed terminal rejection, no client throw, zero accepted slot/projection/gait/INPUT, one recovery.
2. Unloaded endpoint without cause: same fail-closed path.
3. Actual getBlockState throws unique cause X: preserve X and stack; never report ordinary unsupported.
4. Reconcile replay step N rejects: terminal owner, no intermediate predicted publication/new INPUT; canonical/boundary facts remain.
5. Endpoint callback installs successor/changes actor or scope: old call cannot recover/retire the successor.
6. Normal supported surface continues; initial-grounded unsupported and landing unsupported have consistent typed behavior. Work consumed on rejection is not refunded.

Use existing real logic/fixture seams, not source-text checks. Exact candidate9 base plus isolated 026 delta; 024 r3/r4 remain frozen and excluded. Preserve 025 product and unit/raw fixture bytes. After affected checks and loader/build/package gates, deliver a new identifiable CANDIDATE and replay the actual login/landing boundary. Then continue driver12-r2/controller-source02 alive rejection, controlled death/public respawn and six-direction scenes. Do not repeat unaffected helper preparation or claim no-crash as six-direction PASS.
