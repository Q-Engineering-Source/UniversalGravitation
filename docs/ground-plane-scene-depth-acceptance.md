# Scene-depth preview: independent-GPU acceptance

## Scope and artifact identity

This is a new rendering path, not a visually proven fix. Source/static, Java tests and dedicated-server classloading cannot validate GLSL/driver/rendering behavior. Use the final remapped JAR from the development node and record its SHA-256, byte length, worktree state and test-window timestamp before testing. Match the independently read server/client JAR hashes to that artifact; do not combine different builds as one acceptance pass.

Canonical GPU instance as seen from the development node:
`Z:/原子炉计划文档/WIの整合包工坊/- test pack/UniversalGravitation/Crl - 0.6.8 - Client/.minecraft`.
The GPU host's local launcher/path are not inferred from this drive mapping. Never launch this instance on the development server.

The dedicated instance tests common/classloading and remote pass-through only. Positive G/6DOF preview still requires an integrated server on the independent GPU host because remote field synchronization is not implemented.

## Minimal repeatable check

1. Use this project's disposable Creative test world, retaining the current UIE/OptiFine combination and recording any active shader pack. Enable G. Record the existing flight HUD/F3 and confirm normal FREE mouse/roll operation.
2. Hold the rebindable ground-face selection chord (default Alt+H). An actual loaded block face may be selected up to 256 m away through at most four near-to-far native 64 m segments. A nearer block must win. MISS must produce no surface tint; release over a valid face must still commit that outward normal for alignment. Ctrl+H remains the master toggle.
3. On a sufficiently large level surface, verify the cyan fill is a world-plane circle of radius 64 m around the exact hit, not around the player. Coverage must stay full through radius 56 m, soften continuously from 56–64 m, and be zero at/outside 64 m; old square corners such as tangent offsets (60,60) must not tint. Perspective may project the world circle as an ellipse. The 328 m internal unprojection guard is not additional pick reach or a depth-precision guarantee.
4. Repeat on floor, ceiling and walls for all six outward normals. Rotate/roll the FREE camera while holding selection. Tint must remain attached to the selected geometric plane, not to screen/world-up assumptions.
5. Include adjacent surfaces one block, half a block and1/16 block away from the selected plane, plus a wall crossing the plane. They must not acquire cyan bands. Put an opaque noncoplanar foreground block or entity in front; its surface must remain untinted. Sky must remain untinted.
6. Record a static screenshot and a slow pan/translation video resembling the originally reported grey-floor banding. Include targets in each 64 m ray segment and both radial feather boundaries. This version intentionally has no fine grid. At far/grazing/uncertain depth, conservative fading or disappearance is allowed; widened height tolerance or wall bleeding is not.
7. Test release, MISS, GUI opening, focus loss, G off, resize/F11, resource reload and world reconnect. Check for lingering overlays, stale depth, missing hand/HUD/vanilla selection outline, or post-render state corruption. After resource reload, failed shader/resource state should be eligible for a clean retry.
8. If the current pipeline is unsupported, F3 should report the reason and omit the preview; selection/commit must remain usable. Do not call a blank overlay a visual pass. Capture exact FBO/depth diagnostic and client log for follow-up.

## Known limitations to record separately

- RWL depth does not identify blocks versus entities. A same-facing entity surface lying exactly on the target plane may tint.
- Glass, water and particles that do not write depth cannot fully occlude this post-pass tint. Record their actual appearance; do not infer universal transparency compatibility from one successful example.
- Only the verified standard single-sample Minecraft main-FBO D24/D24S8 path is supported initially. Merely having OptiFine installed is not a reason to disable it; custom attachments/FBO, MSAA, unsupported clip/depth convention must fail closed.
- A local depth/anchor consistency test is not a proof of arbitrary shader-pack compatibility.

## Evidence

Keep the final artifact hashes and test timestamp alongside `.minecraft/logs/latest.log`, optional `debug.log`, any `crash-reports` entry, screenshots and the slow-pan video. Record GPU/driver, resolution, active shader pack and the exact mode tested. Correlate dedicated and client evidence by the same external session label; UG has no built-in correlation ID.

Real GPU acceptance: not executed
