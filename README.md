# Mi Band 8 Pro × Home Assistant

Example project: control **Home Assistant** from **Xiaomi Mi Band 8 Pro** watch
quick‑apps, through a phone companion bridged by **Mi Wear Bridge** (an LSPosed module,
separate repo). A reference for building your own watch ⇄ phone ⇄ anything integrations.

```
Watch quick-app (.rpk)            Phone companion (Android)        Home Assistant
  @system.interconnect   <──>   XMS MessageApi (+ Mi Wear Bridge) ──HTTP──> REST API
```

A watch app sends small JSON commands over `@system.interconnect`; the companion
receives them via the XMS wearable API (unlocked by Mi Wear Bridge), calls the Home
Assistant REST API, and sends the result back to the watch.

Two watch apps are included to demonstrate the **coordinator** model (many watch apps →
one phone app):

- **lights** (`com.elli.halights`) — the coordinator app. Toggles a floor lamp, two
  scene scripts (Harry Potter / Cafe house), a space‑projector button, and a
  remote/IR "birds" light with several modes.
- **plants** (`com.elli.plants`) — soil/air temperature & humidity from HA sensors
  (Strawberry: soil moisture + temperature; Sage: humidity + temperature). Routed
  through the lights companion as its coordinator.

## Repo layout

```
android/            # phone companion (Kotlin) — XMS <-> HA REST. The coordinator.
rpk/lights/         # watch quick-app: lights (com.elli.halights) — also the coordinator app
rpk/plants/         # watch quick-app: plants (com.elli.plants)
```

## Prerequisites

- **Mi Band 8 Pro** paired with **Mi Fitness** (`com.xiaomi.wearable`).
- **Mi Wear Bridge** module installed & active in **LSPosed** (root + Xposed API 82+).
- A running **Home Assistant** + a **long‑lived access token**.
- Android Studio / Gradle + Node.js (`aiot` toolkit for the quick‑apps).

## Signing — read this first

Interconnect requires every watch quick‑app and the phone companion to share the **same
package‑signing key**. Generate one key and use it for **all** of: the companion APK and
every `.rpk` (`rpk/*/sign/debug/{private,certificate}.pem`). The coordinator's
fingerprint is what the bridge uses, so the coordinator and its watch apps must match.

## Setup

### 1. Companion (phone)
```bash
cd android
cp src/main/java/com/elli/halights/Config.kt.example \
   src/main/java/com/elli/halights/Config.kt
# edit Config.kt: set HA_URL and HA_TOKEN
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
`Config.kt` is git‑ignored — your token never gets committed.

### 2. Watch apps (.rpk)
```bash
cd rpk/lights && npm install && npm run build   # -> dist/com.elli.halights.debug.<ver>.rpk
cd ../plants  && npm install && npm run build    # -> dist/com.elli.plants.debug.<ver>.rpk
```
Put your signing key in each app's `sign/debug/*.pem` first. Install the `.rpk`s on the
watch via Mi Wear Bridge. Edit entities in `rpk/*/src/constants.js` to match your HA.

### 3. Mi Wear Bridge — scope & bindings (root + LSPosed)
- In LSPosed, enable the module on **`com.xiaomi.wearable`** (+ `com.mi.health` if used)
  **and on every companion app** (e.g. `com.elli.halights`). The companion needs the
  module in its own process — it redirects the stock SDK's bind to Mi Fitness.
- In Mi Fitness → Profile → About → tap the User Agreement line → **Bind apps to watch**:
  add a binding `watch package → coordinator app` for each extra watch app
  (e.g. `com.elli.plants → com.elli.halights`). A watch app whose package equals the
  companion package needs no binding (self‑fallback).
- Restart Mi Fitness after changes. Re‑check scope after reinstalling the module
  (LSPosed resets it).

## Coordinator protocol (for your own apps)

- **Same key** for the coordinator + all its watch apps (see Signing).
- **Watch → phone:** each watch app puts its own package in the request (`src` field) so
  the coordinator knows who asked.
- **Phone → watch:** the coordinator prefixes its reply with `@w:<watchPackage>\n`; the
  bridge strips it and routes the reply to that watch app.

## Security

- Never commit `Config.kt` (HA token) or signing keys (`sign/*.pem`, `*.jks`) — all
  git‑ignored.
- Use a dedicated HA token you can revoke. If `Config.kt` ever hit git history, rotate it.

## Disclaimer

Personal / educational example. Mi Wear Bridge modifies Mi Fitness at runtime. Not
affiliated with Xiaomi or Home Assistant. No warranty.
