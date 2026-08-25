# GOAL — GRIDLOCK HEAT

Top-down 45° (2.5D-look) endless drift-chase arcade game for Android.
Completely OFFLINE (zero network permissions). One-thumb controls:
touch + horizontal drag steers, auto-throttle always accelerates.
Police swarm multiplies over time; survive via drifting, dodging,
and chaining near-misses into combo multipliers.

## Success criteria (all measurable)
- [ ] `assembleDebug` + `lintDebug` + `testDebugUnitTest` all green
- [ ] Core rules unit-tested: drift heading math, near-miss detection,
      combo decay, seeded chunk generation determinism
- [ ] Desktop smoke run: playable drive+dodge at 60fps (<500 quads/frame)
- [ ] APK install size ≤ 40MB budget (target 12–18MB)
- [ ] AndroidManifest has NO INTERNET permission
- [ ] Highscore persists via Preferences
- [ ] Cloud-built APK downloadable from GitHub Actions artifact

## Fixed decisions
- Stack: libGDX 1.14.2 + Kotlin (G2 rung), JDK 17.0.20+8
- Art: Kenney Racing Pack (CC0) re-tinted + Road Textures ground
  pre-squashed ×~0.71; blob shadows; pooled particles
- Feel targets: heading lerps toward velocity (grip const), trauma screenshake,
  skid decals, hit-stop on busts, combo pitch-ladder SFX
