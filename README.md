# NewsDrive

One-tap news for the car. Plays the latest bulletin of each source back to back:

1. **ANP Journaal** (ANP radio bulletin, hourly)
2. **BNR Nieuwsupdate** (every 30 min)
3. **NPR News Now** (hourly, about 5 min)

Works on the phone and in **Android Auto** (it is a native media app, which is the only kind Android Auto lists).

## How it behaves

- **Play news** (phone) or **Play all news** (Android Auto) fetches the newest episode of every source *at that moment* and queues them in the order above.
- Pressing play with nothing loaded (headset button, Android Auto "resume") also starts the news.
- **Refresh** re-downloads all three feeds and restarts the queue with the newest bulletins. On the phone it is the Refresh button; in Android Auto it is the custom action next to the playback controls.
- Next / previous skip between bulletins.
- If one source is unreachable it is skipped; the rest still play.

## Build

You need a computer with Android Studio, or a free GitHub account.

**Android Studio:** open this folder, let Gradle sync, run on your phone (USB debugging) or *Build > Build APK*.

**GitHub (no installs):** push this folder to a new repo, open the *Actions* tab, run *Build APK*, download `NewsDrive-debug-apk`, and install the `.apk` on your phone.

## Android Auto

Apps installed outside the Play Store only show up in Android Auto after you allow unknown sources:

1. Android Auto settings on the phone: tap the *Version* entry about 10 times to enable developer mode.
2. Three-dot menu > *Developer settings* > enable **Unknown sources**.
3. Reconnect to the car. NewsDrive appears in the media app list.

## Changing a station

Edit `NewsConfig.SOURCES` in `app/src/main/java/nl/newsdrive/app/NewsFeeds.kt`. Any podcast RSS feed whose newest item has an audio enclosure works; order in the list is playback order.

## Notes

- The ANP entry uses the ANP-produced bulletin published in the 538 feed, because ANP itself does not publish a public bulletin feed that I could find. If your Google Home "ANP" source differs, swap the URL.
- NPR episodes are sponsor-supported and may include a short ad at the start.
