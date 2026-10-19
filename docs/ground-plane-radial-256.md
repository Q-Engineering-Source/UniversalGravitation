# Radial ground-plane preview and 256 m selection

2026-09-05 user request: increase the ground-selection hit ray to256m and remove square truncation in favor of a soft circular edge. This updates the scene-depth preview, independently of the soft-pitch control slice.

## Scope

- Keep the prior128m diameter: circle radius64m around the exact native hit in the target plane. Coverage is1 inside56m, smoothly fades with `1-smoothstep(56,64,r)`, and is0 at/outside64m. This is a world-plane circle, so perspective may project it as an ellipse.
- Compute radial distance from the two orthonormal plane tangents, not screen distance. Position/normal/depth-precision gates remain unchanged. No square mask or old mesh fallback remains.
- Raise the numerical reconstruction guard to328m =256m selection reach +64m radius +8m margin. This guard is NOT a precision guarantee. The unchanged1/64m maximum plane tolerance can still reject/fade distant unreliable D24 samples; no false whole-scene tint.
- Selection uses at most four64m segments of ONE immutable UIE camera measurement. Query public `CameraApi.pick(CameraPickingRequest)` in near-to-far order; return the first native BLOCK hit, stop on nearer obstruction, and preserve native hit point/face. Do not use a new Mixin or custom block DDA.

## Confirmed engine reason

Cleanroom1.12.2 `World.rayTraceBlocks` checks the start cell and then runs `int k1=200; while(k1-->=0)`, at most201 traversal iterations. UIE's default explicit picking directly calls this method and asks for last uncollidable MISS. A single256m call can exhaust the native budget before a far target, especially diagonally. A64m unit-direction segment stays safely below that budget. Public CameraPickingRequest has no upper-distance clamp and accepts explicit origin/direction from the existing measurement; no new camera sample is acquired between segments.

This does not force client chunks to load or increase render distance. Native public picking providers remain part of UIE's dispatch, and a hit is still validated against the current loaded world/border before selection commits.

## Validation

Pure tests cover four segment endpoints, no calls past a nearer hit, normalized/diagonal/negative directions, invalid-vector rejection; radial0/56/60/64m coverage, rotational symmetry, rejection of old square corners, six face normals and translation invariance. Native block-shape endpoint behavior,256m feel, loaded/rendered terrain and GPU depth/appearance still require independent client testing.

Before snapshots: `.pensieve/.state/radial-preview-256/before/`. Source baselineD206B70A39585637D2F2CFA0253F268CBE97CA2B42B84D200EE2FF6DF1EBFEC8.

Real GPU acceptance: not executed

## 2026-09-05 development result

Combined radial256/soft-pitch candidate completed Standards and Spec at P0/P1/P2=0/0/0. Fresh full build:28 suites/182 tests,all zero; release293866 bytes,SHA-256`E1E4DD6CC7FCAEAE225EC388A1D1C279CA43BD0340A42D6BB2D6B99F0DC4AAE1`,154 classes major69 only,UIE entries0,bothshader resources present. Dedicated common/classloading smoke reachedDone(11.139s),then original-stdin stop/save/unload/exit0,final PID/listener0; known AE2 signatureERROR remains qualified separately.

The single canonical Z-client replacement attempt failed with access denied while testing the client mods directory, before any client file backup or copy. Development is complete; per project rule no automatic retry until the user says`替换`. Detailed evidence:`.pensieve/.state/radial-preview-256/verification.md`.

2026-09-05 17:14 +08:00: the user requested replacement again. The canonical Z client was successfully backed up and replaced; its293866-byte JAR matches the archived candidate and dedicated-server SHA-256 E1E4DD6CC7FCAEAE225EC388A1D1C279CA43BD0340A42D6BB2D6B99F0DC4AAE1. Client handoff is complete; real GPU acceptance remains not executed. See the successful retry receipt in the verification report.
