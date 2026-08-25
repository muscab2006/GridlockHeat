# GRIDLOCK HEAT 🔥🚔

Offline top-down 45° drift-chase arcade game for Android.
Auto-throttle driving, multiplying police swarm, near-miss combo scoring.
Built with libGDX 1.14.2 + Kotlin. Zero network permissions — fully offline.

## Build (cloud)
Push to `main` → GitHub Actions builds APK → download artifact
`gridlockheat-debug-apk` from the run page.

## Build (local)
```
gradlew.bat :android:assembleDebug
adb install -r android\build\outputs\apk\debug\android-debug.apk
```

## Play
- Touch & drag left/right = steer (you never stop accelerating)
- Near-miss cops (close pass without touching) = +combo multiplier
- Getting boxed in or touched = BUSTED. Score = survival × combos.

## Tests
`gradlew.bat :core:test` — drift math, proximity classifier, deterministic world.
