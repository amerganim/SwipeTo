# SwipeTo demo: recording script and checklist

A two-minute video that shows the whole idea without narration being necessary: watch on the
phone, lift, swipe, it continues on the big screen at the same second, photos follow, swipe down
brings it back.

## Before recording

**Devices**
- [ ] Windows PC or laptop with the receiver installed. Settings → name it something friendly ("Living room PC"). The firewall rule is allowed.
- [ ] Android phone with SwipeTo installed, on the **same Wi-Fi** as the PC, already **paired**. Pair off-camera unless the pairing itself is part of the demo.
- [ ] The camera has taught the PC's direction once: raise the phone at the PC so the QR marker is read.
- [ ] Phone: Do Not Disturb on, brightness up, battery above 50 %, the SwipeTo app in front. Turn off auto-rotate unless you rotate on purpose.
- [ ] PC: notifications muted (Focus assist), taskbar auto-hidden, volume at a comfortable level.

**Media**
- [ ] A video with **visible time**, so viewers can see that it continues at the same second: a clock or a counter, or the repo's test clip with a new colour every 10 s. 30–60 s into it is a good start point.
- [ ] 4–6 photos in one album on the phone, landscape ones look best on the PC.

**Cameras**
- [ ] Camera A (wide, tripod): the person on the sofa or at the desk, with both the phone and the PC screen in frame.
- [ ] Camera B, optional: close up on the phone. Or use the phone's own screen recording (Quick settings → Screen record, *Show touches* on).
- [ ] Light the room so the PC screen is not blown out; lower the PC brightness a little if it is.

## Shot list (about 2 minutes)

| # | Time | Action | What the viewer should see |
|---|---|---|---|
| 1 | 0:00 | The person is watching the video on the phone, held low, in a normal watching pose. | An ordinary moment. |
| 2 | 0:08 | They lift the phone toward the PC. | The PC edge **glows** and the phone says "Swipe up to send to …". |
| 3 | 0:10 | Swipe up on the video. | The video flies off the phone. The PC goes full screen and **continues at the same second**: hold on the clock. |
| 4 | 0:18 | Phone down, used as a remote: pause, ±10 s, slider. | The PC follows within a moment. |
| 5 | 0:35 | Swipe down on the phone. | The PC stops, and the phone continues from where the PC was. |
| 6 | 0:45 | Pick the album (Change → several photos). Lift and swipe up on the first photo. | The photo appears full screen on the PC. |
| 7 | 0:55 | Swipe left, left, right on the phone. | The photos **slide** on the PC in the matching direction; the phone shows "2 of 5". |
| 8 | 1:10 | Swipe down. | The photos are back on the phone. |
| 9 | 1:15 | Optional, resilience: during a video on the PC, turn the phone's Wi-Fi off for 5 s and back on. | The PC keeps playing with a small notice. The phone shows *Reconnecting…*, then is the remote again. |
| 10 | 1:35 | Optional, two screens (`--marker-corner left` and `right`, or a second PC): lift toward one, then the other. | Only the one in front glows. |
| 11 | 1:50 | End card: "SwipeTo — point, swipe, watch." | |

Do each shot 2–3 times. Swipes that miss are fine to cut; the glow before the swipe matters most.

## Settings that make the demo reliable

- Phone → Settings (gear) → sensitivity **Normal**, raise-to-aim **on**, camera targeting **on**, debug overlay **off**.
- Keep the phone app in front the whole time. In the background it closes its links, by design.
- Start the receiver before the phone app, so it is discovered at once.
- The first handoff after a fresh start is a cold start. Do one throwaway handoff before recording.

## After recording: check before publishing

- [ ] The position matches: the clock on the PC is within half a second of the phone at the swipe.
- [ ] No pairing code, trust token, Wi-Fi name or IP address is readable in any frame.
- [ ] No third-party video or music you have no rights to. Use your own clip.
- [ ] The repository is private and the project may be patented: **do not publish the video
      publicly** without the owner's OK. Share it privately (unlisted links count as public).

## Logs to keep with the recording

To show timings next to the video (handoff to first frame, prefetched vs cold):

- Receiver: `%LOCALAPPDATA%\SwipeTo\logs`, lines `First frame playing N ms after handoff …, PREFETCHED|cold`.
- Phone: `adb exec-out run-as dev.swipeto.sender cat files/events.log`, lines `swipe up: handing off …`.
