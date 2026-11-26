# MeshKidsChat – Projektstand & Kontext (v2.9 / versionCode 20)

## Aktueller Stand
- **App-Name:** MeshKidsChat
- **Version:** `2.9` (`versionCode = 20`, `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`, Kotlin + Jetpack Compose)
- **Transport:** Umschaltbar zwischen **BLE** (Nordic UART Service `6E400001-...`) und **WLAN (TCP)** mit editierbarem Host-/DynDNS-Feld sowie editierbarem Port (vorbelegt mit `5000`).

## Wichtige Features (v2.8 & v2.9)
- **WLAN-Verbindung (neu in v2.9):** Im Admin-Bereich umschaltbar zwischen `BLE` und `WLAN (TCP)`. Unterstützt IP-Adressen und DynDNS-Hosts/-Pfade sowie einen editierbaren TCP-Port (Standard `5000`) inklusive automatischem Neuverbinden.
- **Scrollverhalten bei ungelesenen Nachrichten (v2.8):** Speichert pro Chat den letzten Lesezeitpunkt (`last_read_timestamps_json`), zeigt den Trenner *Ungelesene Nachrichten* über der ersten ungelesenen Nachricht und verhindert, dass ungelesene Nachrichten nach oben aus dem sichtbaren Bereich geschoben werden.
- **Admin-Bereich (PIN, Standard `1234`):** Verbindungsart & Ziel, Kontakte & Gruppenkanäle laden/freigeben, Alias-Namen, Region/Scope, Anzeigename, Schnelltexte.
