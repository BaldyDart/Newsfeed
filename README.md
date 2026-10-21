# News Now!

One-tap news for the car. Plays the latest bulletin of each source back to back:

1. **ANP Journaal** (ANP radio bulletin, hourly)
2. **BNR Nieuwsupdate** (every 30 min)
3. **NPR News Now** (hourly, about 5 min)
4. **VRT NWS** (live radio stream, plays 5 minutes, then moves on)
5. **BBC World News** (BBC Minute, one-minute bulletin every hour)

Off by default, switch on under Settings > Feeds: **BBC Global News** (about 30 min) and **L1 Limburg** (live regional radio for Dutch Limburg, plays 5 minutes).

The stations are shown as swipeable 3D cards; swiping while playing jumps to that bulletin.

Works on the phone and in **Android Auto** (it is a native media app, which is the only kind Android Auto lists).

## How it behaves

- **Play news** (phone) or **Play all news** (Android Auto) fetches the newest episode of every source *at that moment* and queues them in the order above.
- Pressing play with nothing loaded (headset button, Android Auto "resume") also starts the news.
- **Previous / next bulletin** jump between the three sources. Previous on the first one restarts it.
- **Back / forward 10 seconds** on the phone screen, in the notification, and as buttons in Android Auto.
- **Artwork tile** of the current station on the phone, notification, lock screen and car display.
- **Refresh** (top-right on the phone, a button in Android Auto) re-downloads all three feeds and restarts with the newest bulletins.
- If one source is unreachable it is skipped; the rest still play.
- Material 3 look: follows your wallpaper colours and light/dark mode on Android 12+.

## Settings

Tap the gear in the top-right corner of the phone screen:

- **Feeds**: switch each news feed on or off. Feeds play in the order shown, at least one must stay on, and the choice is remembered. It applies the next time you start or refresh the news, in the app and in Android Auto.
- **About**: app version, author, year and the project URL (https://github.com/BaldyDart/Newsfeed).

## Options (gear > Options)

- **Skip bulletins I have heard**: a refresh plays only new bulletins; if nothing is new, the latest play again.
- **Announce the source**: speaks the station name before each bulletin.
- **Playback speed**: 1× to 2× for bulletins (not live streams).
- **Start automatically in the car**: starts the news when Android Auto connects, or when the phone connects to the Bluetooth device you pick. Best effort: Android limits what apps may start in the background.
- Voice: "Hey Google, play News Now", or "play BBC on News Now".

## Feeds (gear > Feeds)

Switch feeds on or off, reorder them with the arrows, set how long a live stream plays, and add your own podcast feed or live stream with **Add feed**. The last bulletin of each podcast feed is kept on the phone and used when there is no connection.

## Build

You need a computer with Android Studio, or a free GitHub account.

**Android Studio:** open this folder, let Gradle sync, run on your phone (USB debugging) or *Build > Build APK*.

**GitHub (no installs):** push this folder to a new repo, open the *Actions* tab, run *Build APK*, download `News Now!-debug-apk`, and install the `.apk` on your phone.

## Android Auto

Apps installed outside the Play Store only show up in Android Auto after you allow unknown sources:

1. Android Auto settings on the phone: tap the *Version* entry about 10 times to enable developer mode.
2. Three-dot menu > *Developer settings* > enable **Unknown sources**.
3. Reconnect to the car. News Now! appears in the media app list.

## Changing a station

Edit `NewsConfig.SOURCES` in `app/src/main/java/nl/newsdrive/app/NewsFeeds.kt`. Any podcast RSS feed whose newest item has an audio enclosure works, and a live stream works with `streamUrl` and `liveMinutes`; order in the list is playback order.

## Notes

- The ANP entry uses the ANP-produced bulletin published in the 538 feed, because ANP itself does not publish a public bulletin feed that I could find. If your Google Home "ANP" source differs, swap the URL.
- NPR episodes are sponsor-supported and may include a short ad at the start.
