# Raise-to-aim and swipe tuning guide

Every threshold lives in one file:
[`app/src/main/kotlin/dev/swipeto/sender/aim/AimConfig.kt`](app/src/main/kotlin/dev/swipeto/sender/aim/AimConfig.kt).
`Sensitivity` (Settings → Sensitivity) picks one of three presets; tune the **Normal** preset first.

## What the phone measures

**Camera elevation**: where the back camera points, in degrees above the horizon. It comes from
the game rotation vector (accelerometer and gyroscope, no compass), falling back to the rotation
vector, then gravity alone. Turning the phone between portrait and landscape does not change it.

| Pose | Elevation |
|---|---|
| Flat on a table, screen up | −90° |
| Watching a video in your hands | about −30° to −60° |
| Held upright, camera at the TV | about 0° |
| Camera at the ceiling | +90° |

Turn on **Settings → Sensor debug** to see the live value, the detector phase and the aim state,
plus **Aim now / End aim** buttons that skip the sensor entirely.

## The raise-to-aim rule

Aiming starts when all three hold:

1. Elevation is inside **`bandMinDeg`…`bandMaxDeg`** (−20°…+40°).
2. Within **`raiseWindowMs`** (900 ms) *before* entering that band, it was at or below
   **`raisedFromBelowDeg`** (−35°). This means a deliberate raise; holding the phone upright,
   or drifting up slowly, never triggers.
3. It has stayed in the band, moving less than **`steadyJitterDeg`** (10°), for **`dwellMs`**
   (120 ms). Swinging past to the ceiling doesn't count.

Aiming ends after **`exitDwellMs`** (250 ms) below **`exitBelowDeg`** (−40°) or above
**`exitAboveDeg`** (+65°). The gap between −20° and −40° is hysteresis, so a wobble doesn't end it.

From the moment the phone reaches the band, the screen should glow within about 120 ms of dwell
plus one network hop. Standby links keep a greeted connection to each paired screen open, so
there is no connect or hello on the way.

## Swipes

`SwipeConfig`: a swipe needs at least **`minDistanceDp`** (110 dp) of travel and
**`minVelocityDpPerSec`** (800 dp/s) at release. It must lean at most **`maxAngleFromVerticalDeg`**
(30°) from vertical and last at most **`maxDurationMs`** (700 ms). Scrubbing the timeline
(sideways), slow drags and short nudges are rejected. Swipe-up only does anything while a target
is chosen; otherwise the app explains what to do.

## Choosing the target

1. **Camera marker**, if *Find the screen with the camera* is on and the permission is granted.
   The rear camera runs for up to **`markerSearchMs`** (1.5 s) and ML Kit looks for a
   `swipeto:<id>:<nonce>` QR. The phone reports the nonce; a screen that answers
   `aim_ack {locked: true}` shows a green glow, and the phone shows a ✓.
2. **Fallback**: the only paired screen online, otherwise the one used most recently.

## Tuning on a phone

| Symptom | Change |
|---|---|
| Raising doesn't trigger | Lower `raisedFromBelowDeg` towards −28 (or use High). Check the debug elevation while watching: your viewing pose must dip below it. |
| Triggers while watching | Raise `raisedFromBelowDeg` towards −45, shorten `raiseWindowMs`, lengthen `dwellMs`. |
| Aim drops while pointing | Lower `exitBelowDeg`, lengthen `exitDwellMs`. |
| Glow feels late | Shorten `dwellMs` (not below ~60 ms, or swings trigger). |
| Swipes missed | Lower `minVelocityDpPerSec` / `minDistanceDp`. |
| Scrubbing sends | Lower `maxAngleFromVerticalDeg`. |
| Camera never finds the marker | Lengthen `markerSearchMs`; check room light and that the QR card is in view. |

The unit tests in `app/src/test/.../aim/` replay synthetic traces (a raise, holding upright, slow
drift, a swing, twenty minutes of casual handling). Re-run them after changing thresholds:
`gradlew :app:testDebugUnitTest`.
