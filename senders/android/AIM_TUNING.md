# Raise-to-aim and swipe tuning guide

Every threshold lives in one file:
[`app/src/main/kotlin/dev/swipeto/sender/aim/AimConfig.kt`](app/src/main/kotlin/dev/swipeto/sender/aim/AimConfig.kt).
`Sensitivity` (Settings → Sensitivity) picks one of three presets; tune the **Normal** preset first.

## What the phone measures

**Camera elevation**: where the back camera points, in degrees above the horizon. It comes from
the game rotation vector (accelerometer and gyroscope), falling back to the rotation vector, the
gravity sensor, and finally the **raw accelerometer through a low-pass filter**. That last one is
what the Galaxy A15 uses: like many budget phones it has no gyroscope. Turning the phone between
portrait and landscape does not change the elevation.

| Pose (measured on a Galaxy A15) | Elevation |
|---|---|
| Flat on a table, screen up | −88° |
| Watching a video in your hands | −45° to −75° |
| Pointing at a laptop on a desk | −27° to −37° |
| Pointing at a wall TV | about 0° (estimate) |
| Camera at the ceiling | +90° |

Turn on **Settings → Sensor debug** to see the live value, the detector phase and the aim state,
plus **Aim now / End aim** buttons that skip the sensor entirely. Raises, aims, locks, swipes and
handoffs are written to `files/events.log` on the phone as well as logcat, so a run done unplugged
can be read afterwards: `adb exec-out run-as dev.swipeto.sender cat files/events.log`.

## The raise-to-aim rule

A laptop on a desk is *below* eye level, so pointing at it still leaves the camera tilted down. An
absolute "near level" rule never triggered on the A15. Aiming is therefore a **rise relative to the
moment before**. It starts when all three hold:

1. Elevation is inside **`bandMinDeg`…`bandMaxDeg`** (−45°…+40°).
2. It is at least **`minRiseDeg`** (18°) above the lowest point of the last **`raiseWindowMs`**
   (1.2 s): a quick, deliberate lift. Holding still or drifting slowly never triggers.
3. It has stayed so, moving less than **`steadyJitterDeg`** (10°), for **`dwellMs`** (120 ms).

Aiming ends after **`exitDwellMs`** (250 ms) more than **`exitDropDeg`** (18°) below the highest
point of the aim, below **`exitBelowDeg`** (−60°), or above **`exitAboveDeg`** (+65°).

From the moment the phone settles, the screen glows after about 120 ms of dwell plus one network
hop. Standby links keep a greeted connection to each paired screen open, so there is no connect or
hello on the way, and the phone repeats `aim_start` every 4 s so the glow stays while you aim.

`app/src/test/resources/a15-raises.csv` is a real recording from the A15: watching, then five slow
raises toward a laptop. The detector must find exactly four (the fifth peaked for under 100 ms)
and nothing while watching. Run it after any threshold change.

## Swipes

`SwipeConfig` (Normal):

- At least **`minDistanceDp`** (70 dp) of travel.
- At least **`minVelocityDpPerSec`** (650 dp/s) at release.
- Leaning at most **`maxAngleFromVerticalDeg`** (40°) from vertical.
- Lasting at most **`maxDurationMs`** (700 ms).

These were loosened after recording real thumb swipes on the A15, which arc 30–50° and are often
under 100 dp. Loose is safe because swipe-up only acts while a target is chosen; otherwise the app
says what to do. Scrubbing sideways, taps and flicks that end going the other way are still rejected.

## Choosing the target

1. **Camera marker**, if *Find the screen with the camera* is on and the permission is granted.
   The rear camera runs for up to **`markerSearchMs`** (1.5 s) and ML Kit looks for a
   `swipeto:<id>:<nonce>` QR. The phone reports the nonce; a screen that answers
   `aim_ack {locked: true}` shows a green glow, and the phone shows a ✓.
2. **Fallback**: the only paired screen online, otherwise the one used most recently.

## Tuning on a phone

| Symptom | Change |
|---|---|
| Raising doesn't trigger | Lower `minRiseDeg` (High uses 13°) or `bandMinDeg`. Read the trace: the raise must climb that far within `raiseWindowMs`. |
| Triggers while watching | Raise `minRiseDeg`, shorten `raiseWindowMs`, lengthen `dwellMs`. |
| Aim drops while pointing | Raise `exitDropDeg`, lengthen `exitDwellMs`. |
| Glow feels late | Shorten `dwellMs` (not below ~60 ms, or swings trigger). |
| Swipes missed | Lower `minVelocityDpPerSec` / `minDistanceDp`. |
| Scrubbing sends | Lower `maxAngleFromVerticalDeg`. |
| Camera never finds the marker | Lengthen `markerSearchMs`; check room light and that the QR card is in view. |

The unit tests in `app/src/test/.../aim/` replay synthetic traces (a raise, holding upright, slow
drift, a swing, twenty minutes of casual handling). Re-run them after changing thresholds:
`gradlew :app:testDebugUnitTest`.
