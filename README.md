# Mistral

**Instagram and YouTube on Android, on a short leash: 7 minutes of use, then 15 minutes blocked.**

The app and the site, in any browser. No account, no server, no accessibility service. It does not cut your internet: when your time is up, it puts a full-screen cover over the app (or sends the browser Home) until the block ends.

Mistral, formerly Maverick Truck, is the phone sibling of [Maverick](https://github.com/luiz-nast/maverick), the Linux desktop app that limits YouTube and runs Instagram on the same kind of cycle. Like its siblings [Chiron](https://github.com/luiz-nast/chiron-routine), [Veyron](https://github.com/luiz-nast/Veyron), and [Tourbillon Alarm](https://github.com/luiz-nast/tourbillon-alarm), Mistral is named after a Bugatti model (the W16 Mistral). On this phone, it does what Maverick does on the Linux desktop.

| | |
|---|---|
| Version | 0.2.0 |
| Platform | Android 10+ (tested target: Android 16, Galaxy S24 FE) |
| Language | Kotlin, Jetpack Compose |
| Size | ~5 MB (3.4 MB are the car photos) |
| Permissions | Usage access, local VPN, cover blocked apps, notifications, unrestricted battery |
| Network | Nothing goes to any server of ours: there is none |
| App ID | `io.github.luiz_nast.mistral` |
| License | MIT (code); car photos under their own CC BY / CC BY-SA licenses, see [CREDITS.md](CREDITS.md) |

## The cycle

One leash for both. Instagram and YouTube share the same 7 minutes of use, and the 15-minute block covers both at once — so you cannot jump from a blocked Instagram to YouTube.

```mermaid
stateDiagram-v2
    direction LR
    Free: Free, counting use (0:00 → 7:00)
    Blocked: Blocked for 15:00, by the clock
    Free --> Blocked: 7 min of use
    Blocked --> Free: 15 min later, round starts at 0:00
```

| Situation | Counts toward the 7 min? |
|---|---|
| Instagram or YouTube app on screen | Yes, 1 s per second |
| instagram.com or youtube.com open in a browser on screen | Yes (see "How a site is noticed") |
| The app open but the screen off | No |
| Any other app on screen | No |
| The phone in your pocket during a block | The block keeps running: it is by the clock |

When a round runs out, a full-screen cover drops over the blocked app with "Instagram is blocked — free again at 14:32" and a **Leave** button that goes Home (`Cobertura`, the "Display over other apps" permission — not an accessibility service, so banks do not flag it). The cover appears only over the Instagram and YouTube apps, never over a bank or any other app. A notice also drops from the top. A quiet ongoing notification keeps the score: `Instagram & YouTube · 3:12 of 7:00 used`.

You can turn the shield off from the app, but only while nothing is blocked. That is the whole point.

### Tourbillon's morning routine

While [Tourbillon Alarm](https://github.com/luiz-nast/tourbillon-alarm)'s morning activity runs, its stage rules both sites (`Manha.kt`, read every 5 s from its `content://com.implacavel.alarme.modo` provider, same signature permission as the music library):

| Stage | Instagram | YouTube |
|---|---|---|
| Walk (`CAMINHADA`) | Free | Locked |
| Camera (`CAMERA`) | Locked | Free |
| Talk (`CONVERSA`) | Locked | Locked |

"Locked" is the cover, with no time on it: it opens when the routine ends. Free time in the routine does not count toward the 7 minutes, and the round picks up where it was when the routine ends (a block keeps running by the clock). The main screen shows the stage; there is nothing to pause or change meanwhile. It fails closed: no answer, an unknown stage, or a `valeAte` that has passed (or is more than 5 min ahead, a moved clock) means the usual rules, so a dead Tourbillon brings the usual rules back within 90 s. The YouTube page inside Tourbillon is never touched: the alarm stays outside the tunnel and is not a browser.

### Usage of the day, for Chiron

The shield also adds up how long each site was actually on screen (`Estado.uso`, 14 days, per app: Instagram or YouTube, the app or a browser on the site). Time under the block cover is not use; the Tourbillon morning routine counts only for its free site. [Chiron](https://github.com/luiz-nast/chiron-routine), on the same phone, reads it through a read-only content provider (`FonteDoUso.kt`) protected by the signature permission `io.github.luiz_nast.mistral.permission.USO`: only apps signed with the same key can read, and writing does nothing. Mistral never wakes Chiron; Chiron asks when it needs.

| URI (authority `io.github.luiz_nast.mistral.uso`) | Columns |
|---|---|
| `content://io.github.luiz_nast.mistral.uso/dias` | `dia` (`YYYY-MM-DD`, phone time zone), `app` (`instagram` or `youtube`), `ms` (screen use that day), `atualizado_em` (epoch ms); the last 14 days |
| `content://io.github.luiz_nast.mistral.uso/celular` | one row: `ultimo_uso` (epoch ms of the last shield tick with the screen on, 0 = never), `app` (package in front at that tick, or empty), `escudo` (1 = the shield is up, 0 = not) |

Example (time zone `America/Sao_Paulo`): on 2026-11-10, 12 min 34 s of Instagram and 2 min of YouTube, last written at 13:00:

| dia | app | ms | atualizado_em |
|---|---|---|---|
| 2026-11-10 | instagram | 754000 | 1794326400000 |
| 2026-11-10 | youtube | 120000 | 1794326400000 |

## How it works

```mermaid
flowchart LR
    Usage["Usage access: which app is on screen"] --> Shield
    Shield["Shield (foreground service)"] -->|"blocked app or site in front"| Cover["Full-screen cover + Leave"]
    DNS["Local VPN (DNS only, no blocking)"] -->|"which site a browser is on"| Shield
```

1. **It counts, it does not cut.** Every second the screen is on, the shield sees which app is in front. If it is Instagram or YouTube (the app, or a browser on the site), it adds to that site's round. At 7 minutes the site is blocked for 15 minutes by the clock. Your internet is never touched.
2. **A floating counter while you scroll.** While Instagram or YouTube is in front and still free, a small pill shows the shared time, "YouTube 5:12 / 7:00", greener to redder as it fills (`Medidor`) — the same idea as the desktop Maverick's counter. You can drag it anywhere and it stays there; it shows only over those apps.
3. **Blocked means you are thrown out.** While a site is blocked and its app is in front, a full-screen cover appears over it, with a carousel in the background: very luxurious cars (freely licensed photos from Wikimedia Commons, credited on the app's main screen and in [CREDITS.md](CREDITS.md)) plus the photos you add from your gallery (`Fotos.kt`), a new one every 5 s, image only. While it is up, a random song plays from the music library shared with Tourbillon Alarm (`Musicas.kt`), starting at **35% of the media volume**, then another when it ends; the app behind is paused, and the volume goes back when the cover leaves, unless you changed it. On top: "Instagram is blocked — free again at 14:32" — with a **Leave** button that goes Home (`Cobertura`). A browser sitting on a blocked site gets the same cover, with a second button, **Use the browser for something else**, that opens a blank Google page in a new tab of that browser, so the rest of the browser stays usable (close the blocked tab: a video still playing in it can bring the cover back). The cover shows only over the Instagram and YouTube apps and over a browser on those sites: never over a bank, the alarm, or anything else.
4. **Seeing a browser's site needs the DNS tunnel.** Without an accessibility service, the only way to know a browser is on youtube.com is the DNS it asks for. So the shield is a local VPN whose single route is a fake DNS server (`10.215.173.2`); every app's DNS question passes through, the shield notes the ones for Instagram and YouTube (and who asked), and forwards every question to your real DNS and hands the answer straight back. It blocks nothing and your traffic never goes through it. Answers for ruled names get a 10-second lifetime so the browser keeps asking, which is how the shield knows it is still on the site.
5. **Usage comes from Android's usage history** (the "Usage access" permission), read once per second while the screen is on.

### How a site is noticed

A browser is on screen, and that same browser asked for a ruled name in the last 60 s (Android tells a VPN who owns a connection; when it does not say, the question still counts). A question from another app, such as YouTube Music playing in the background or a link preview in WhatsApp, does not count. This is approximate: a page that only embeds a YouTube player or an Instagram post counts as that site.

### Who stays outside the tunnel

The app itself (it is the way out to the real DNS), banks (Nubank, Inter, Itaú, Banco do Brasil, Bradesco, Santander, Caixa, Mercado Pago, PicPay) and [Tourbillon Alarm](https://github.com/luiz-nast/tourbillon-alarm). Their DNS never passes through, and nothing of theirs is ever covered.

## Install

There is no Play Store release. With the phone on a USB cable and USB debugging on:

```bash
./install.sh
```

or build and install by hand:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then open **Mistral** and:

1. **Usage access** › Allow › find Mistral › on.
2. **Notifications** › Allow (Android 13+).
3. **Unrestricted battery** › Allow (Samsung and others put background apps to sleep).
4. **Cover blocked apps** › Allow (so a block screen can appear over Instagram and YouTube).
5. **TURN ON** › Android asks once to let it set up a VPN › OK. A key icon shows in the status bar while the shield is up.

It comes back by itself after a reboot or an update.

**Installing Mistral next to an old Maverick Truck.** Android keeps one VPN at a time. If the old Maverick Truck app is still on the phone, turning Mistral's shield on will take the VPN from the old app. Once Mistral is validated on the phone, turn the old app off (or disable it with `adb shell pm disable-user --user 0 io.github.luiz_nast.maverick.truck`, which you can undo).

**Private DNS.** If Settings › Connections › More connection settings › Private DNS is set to a provider (for example `dns.google`), Android sends DNS straight to that provider and the shield never sees a browser's site (the apps are still caught by usage). The app shows a red warning in that case. Set it to **Automatic** (or Off).

## Why not an accessibility service?

Most app blockers watch the screen through an accessibility service and press Home on the blocked app. On Android 13+, an app that is not from the Play Store and has an accessibility service on is exactly what the "app access risk" check of banking apps flags, because that is how remote-control scams work: Nubank refuses to open while one is on. The same owner hit that with Tourbillon Alarm in October 2026. So Mistral uses only:

| Need | What it uses | Not this |
|---|---|---|
| Which app is on screen | Usage access (`UsageStatsManager`) | Accessibility service |
| Seeing a browser's site | Local VPN, DNS only (no blocking) | Accessibility |
| Taking you out of the app | A cover over that app only (`SYSTEM_ALERT_WINDOW`) | Accessibility (banks flag it) |
| Keeping time | A foreground service, 1 tick per second with the screen on | Polling with the screen off |

## Known limits

- **The cover needs its permission.** The block is the cover, so without "Display over other apps" nothing stops you — grant it. The cover appears up to 1 second after you open the blocked app (the shield looks once a second).
- **A browser on the site.** The shield reads a browser's site from its DNS. Once the browser asks for Instagram/YouTube, it keeps counting while that browser stays in front, even when a playing video asks nothing more; it stops when you leave the browser or the browser asks for another site (a bank page ends it at once). Requests that come with those pages (Google, ads, Facebook CDN) do not end it. Switching to a tab that was already loaded can go unnoticed until you leave the browser.
- **DNS over TCP** to the shield is not answered. Android uses it only for answers too big for UDP (rare with modern resolvers); such a name can fail to resolve while the shield is on. Set Private DNS to Automatic, not a fixed provider.
- **Turning it off is possible from Android**: Settings › VPN, or the VPN tile, or uninstalling. So Mistral uses only what it needs to do its job. Mistral is a seatbelt, not a jail. It notifies you when the shield goes down.
- **Another VPN replaces it.** Android runs one VPN at a time.
- **Private DNS set to a provider** bypasses the shield (see above).
- **A browser with its own encrypted DNS** set to a fixed provider (Chrome › Settings › Privacy › Use secure DNS › custom provider) bypasses it for sites. Chrome's default ("automatic", with your current provider) is fine.
- **The app and the site count differently**: the app by what is on screen (exact), the site by the browser's DNS questions (approximate, see above).
- **Wall clock.** Blocks follow the phone's clock. Setting the clock back does not stretch a block beyond 15 min; setting it forward ends it early.

## Files

| File | What it does |
|---|---|
| `Regras.kt` | Pure rules, tested: the two sites (`Site`: packages, ruled domains), `siteDoDominio`, the round (`Rodada.avancar`, `LIVRE_MS` = 7 min, `BLOQUEIO_MS` = 15 min), `siteEmUso` (with who asked: `Consulta`), the morning stages (`Etapa`, `etapaQueVale`, `decidir`), the usage of the day (`UsoDoDia`, `siteVisivel`, `somarUso`, `podarUso`) |
| `Dns.kt` | Pure packet work, tested: read a DNS question from an IPv4/UDP packet (`lerPerguntaDns`), the question name, the 10 s TTL rewrite (`encurtarTtl`), build an IPv4/UDP packet with both checksums |
| `Escudo.kt` | The `VpnService`: the 1 s tick (usage, rounds, notices, the cover), the DNS-only tunnel (never blocks; rebuilt only if its reader dies), the reader thread (`Os.poll`), forwarding to the real DNS through protected sockets (re-read on every network change), who asked (`getConnectionOwnerUid`) |
| `Uso.kt` | Which app is on screen (`UsageStatsManager.queryEvents`), whether usage access is granted, which apps are browsers |
| `Estado.kt` | What is saved on the phone: on/off, the single shared round (Instagram + YouTube) and the usage of the day; the screen reads it as `StateFlow` |
| `FonteDoUso.kt` | The read-only provider that opens the usage of the day (`/dias`) and the last use of the phone (`/celular`) to Chiron, behind the signature permission `USO` |
| `Notificacoes.kt` | The ongoing status notification and the "blocked" heads-up |
| `MainActivity.kt` | The screen: the shared "Instagram & YouTube" ring, TURN ON (there is no off switch), permissions, the Private DNS warning |
| `Cobertura.kt` | The full-screen cover shown over a blocked app while it is in front (`SYSTEM_ALERT_WINDOW`); never over a bank |
| `Carros.kt` | The car photos of the cover's carousel (`res/drawable-nodpi/carro_XX.jpg`) and their on-screen credit lines |
| `Musicas.kt` | The music library shared with Tourbillon Alarm (list, open, add, delete through its `FonteDeSons` provider, signature permission), `volumeDaMusica` (35%) and `sortearSom` (random, never the same song twice in a row) |
| `Manha.kt` | Reads Tourbillon's morning stage (`etapa`, `valeAte`) from its provider, off the main thread |
| `Fotos.kt` | Your gallery photos for the carousel: picked with the system photo picker (no gallery permission), copied into the app at screen size |
| `Medidor.kt` | The small floating pill (same permission) with "used / 7:00", shown while Instagram or YouTube is in front and still free; drag it anywhere and it remembers the spot; like the desktop Maverick's counter; never over a bank |
| `BootReceiver.kt` | Brings the shield back after a reboot or an app update |

Code and comments are in Portuguese, like the sibling projects; everything on screen is in English. Logs: `adb logcat -s Mistral`.

## Design decisions

- **Usage-based, then clock-based.** The 7 minutes are of real use, so an idle phone does not burn them, and the 15 minutes are by the clock, so a block cannot be waited out with the screen off any faster. It matches Maverick on the desktop.
- **One leash for both (owner, 2026-10-31).** The 7 minutes are Instagram and YouTube together, and the block covers both, so a blocked Instagram cannot be dodged by opening YouTube.
- **Eject, do not cut.** Cutting the network left the already-loaded feed scrolling and never took you off the screen, so it did not feel like a block. A cover over the app does. The DNS tunnel stays only as a sensor, to tell when a browser is on a blocked site; it forwards every question untouched, so it cannot slow a download or break an app that is not ruled.

- **No off switch.** Once on, the app has no Turn off button: an off switch would make every block a suggestion.

## Build

JDK 17+ and the Android SDK. `./gradlew testDebugUnitTest` runs the rule and packet tests; `./gradlew assembleRelease` builds the APK (signed with the debug key, which is enough for your own phone).

## License

MIT
