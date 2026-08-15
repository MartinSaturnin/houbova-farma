# Mushroom Bridge Android

MVP Android aplikace pro projekt **Houbová farma**.

## Co dělá

- čte **SwitchBot Meter / Meter Plus** přímo přes BLE, bez Hubu a bez SwitchBot cloudu,
- umožní jednou přiřadit dva senzory:
  - `ENV-IN-01` — Houbový skládek IN,
  - `ENV-OUT-01` — Dveře skládek OUT,
- běží jako Android foreground service,
- při příchodu telefonu do dosahu stanice vytvoří jednu návštěvu,
- po krátkém čekání na oba senzory odešle aktuální T/RH/baterii/RSSI do GitHubu,
- zapisuje:
  - `sensor-live.json` — poslední stav,
  - `sensor-history.json` — archiv návštěv,
- po 10 minutách bez signálu považuje stanici za opuštěnou; nový příchod vytvoří další záznam.

## Důležité omezení MVP

SwitchBot veřejně dokumentuje aktuální hodnoty v BLE broadcastu, ale ne veřejný příkaz pro stažení celé interní historické paměti Meter Plus. Mushroom Bridge proto automatizuje **aktuální stav při návštěvě** a následná měření během přítomnosti telefonu, nikoliv zpětné stažení všech záznamů vytvořených v době, kdy telefon nebyl v dosahu.

## První nastavení

1. Nainstaluj debug APK z GitHub Actions artefaktu `MushroomBridge-debug-apk`.
2. Povol Bluetooth / Nearby devices a notifikace.
3. Stiskni **Spustit automatický Bridge**.
4. U stanice počkej, až se oba Meter Plus objeví v seznamu.
5. Vyber první a stiskni **Přiřadit IN**, druhý **Přiřadit OUT**.
6. Na GitHubu vytvoř **fine-grained personal access token**:
   - repository access: pouze `MartinSaturnin/houbova-farma`,
   - repository permission: `Contents: Read and write`,
   - bez dalších oprávnění.
7. Token vlož do aplikace a ulož. Token se ukládá šifrovaně přes Android Keystore.
8. Nech Bridge běžet. Aplikace má trvalou nízkoprioritní notifikaci, protože Android vyžaduje foreground service pro dlouhodobou BLE práci.

## GitHub formát

### `sensor-live.json`

```json
{
  "schemaVersion": 1,
  "visitId": "2026-08-15T19:45:00Z-1234abcd",
  "updatedAt": "2026-08-15T19:45:25Z",
  "source": "Mushroom Bridge Android",
  "sensors": {
    "ENV-IN-01": {
      "deviceMac": "AA:BB:CC:DD:EE:FF",
      "temperatureC": 19.1,
      "humidityRH": 88,
      "batteryPct": 100,
      "rssi": -61,
      "sampledAt": "2026-08-15T19:45:24Z"
    }
  }
}
```

## BLE protokol

Parser je založen na oficiální SwitchBot BLE Open API. Meter Plus má device type `0x69` (`i`). Teplota a RH jsou dekódovány z service data podle dokumentace Meter BLE API.

## Build

Repo obsahuje GitHub Actions workflow. Lokálně lze sestavit:

```bash
gradle :app:assembleDebug
```

Výstup: `app/build/outputs/apk/debug/app-debug.apk`.
