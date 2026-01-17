# DirectCAN - Android CAN-Bus Analyzer

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" alt="DirectCAN Logo">
</p>

**DirectCAN** ist eine Android-App zur Analyse und Überwachung von CAN-Bus-Daten. Die App verbindet sich über USB-Serial mit CAN-Bus-Adaptern und bietet Funktionen wie DBC-Dekodierung, KI-gestützte Analyse, TX-Scripting und Echtzeit-Visualisierung.

---

## Inhaltsverzeichnis

1. [Features](#features)
2. [Hardware-Anforderungen](#hardware-anforderungen)
3. [Installation](#installation)
4. [Schnellstart](#schnellstart)
5. [Screens & Funktionen](#screens--funktionen)
   - [Home](#home)
   - [Monitor](#monitor)
   - [Sniffer](#sniffer)
   - [Signals](#signals)
   - [Signal Graph](#signal-graph)
   - [DBC Manager](#dbc-manager)
   - [TX Script Manager](#tx-script-manager)
   - [Gemini AI](#gemini-ai)
   - [Settings](#settings)
6. [DBC-Format](#dbc-format)
7. [TX Script Sprache](#tx-script-sprache)
8. [USB-Protokoll](#usb-protokoll)
9. [Architektur](#architektur)
10. [Build & Development](#build--development)
11. [Lizenz](#lizenz)

---

## Features

### Kern-Funktionen
- **USB-Serial CAN-Adapter Support** - Verbindung mit Feather M4 CAN und kompatiblen Adaptern
- **Echtzeit CAN-Bus Monitoring** - Live-Anzeige aller CAN-Frames mit bis zu 2 Mbit/s
- **DBC-Datei Support** - Import/Export und Echtzeit-Signal-Dekodierung
- **Multi-Screen Analyse** - Monitor, Sniffer und Signal-Ansichten
- **Frame-Filtering** - Globaler Filter für alle Ansichten

### Erweiterte Funktionen
- **TX Script Engine** - Eigene Scripting-Sprache für CAN-Sequenzen
- **Gemini AI Integration** - KI-gestützte CAN-Analyse und DBC-Generierung
- **Signal-Graphen** - Echtzeit-Visualisierung von bis zu 8 Signalen
- **Snapshot & Logging** - Speichern und Analysieren von CAN-Daten
- **Chat-Export** - AI-Chats als Markdown, Text oder JSON exportieren

### UI & Bedienung
- **Material 3 Design** - Moderne Android-UI
- **Mehrsprachig** - Deutsch und Englisch
- **Dark/Light Mode** - System-Theme-Unterstützung
- **Tablet-optimiert** - Responsive Layout

---

## Hardware-Anforderungen

### Android-Gerät
- **Android Version**: 8.0 (API 26) oder höher
- **USB-Host Support**: Erforderlich (USB OTG)
- **Empfohlen**: Tablet für beste Übersicht

### CAN-Bus Adapter
DirectCAN ist optimiert für den **Adafruit Feather M4 CAN Express** mit angepasster Firmware:

| Komponente | Spezifikation |
|------------|---------------|
| Mikrocontroller | ATSAMD51 (Feather M4) |
| CAN-Transceiver | MCP2515 / TJA1051 |
| Baudrate USB | 2.000.000 bps |
| CAN-Baudraten | 125, 250, 500, 1000 kbit/s |

**Andere kompatible Adapter:**
- Jeder USB-Serial-zu-CAN Adapter mit kompatiblem Textprotokoll
- ESP32-CAN mit entsprechender Firmware
- Arduino-basierte CAN-Lösungen

---

## Installation

### APK Installation
1. APK-Datei auf das Android-Gerät übertragen
2. Installation aus unbekannten Quellen erlauben
3. APK installieren

### Build aus Quellcode
```bash
git clone https://github.com/your-repo/directcan.git
cd directcan
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```

---

## Schnellstart

### 1. Adapter verbinden
1. CAN-Adapter per USB-OTG mit dem Android-Gerät verbinden
2. USB-Permission bestätigen
3. Im Home-Screen auf "Verbinden" tippen

### 2. DBC laden (optional)
1. Zu **DBC Manager** navigieren
2. DBC-Datei importieren
3. Zurück zum Monitor für dekodierte Daten

### 3. Monitoring starten
1. **Monitor**-Screen öffnen
2. Frames werden automatisch angezeigt
3. Filter über die Sidebar konfigurieren

---

## Screens & Funktionen

### Home

Der Home-Screen bietet eine Übersicht über den Verbindungsstatus und Quick-Actions.

#### Verbindungs-Karte
- **Geräte-Dropdown**: Verfügbare USB-Geräte
- **Verbinden/Trennen**: Button zum Herstellen/Beenden der Verbindung
- **Status-Anzeige**: Verbunden/Getrennt/Verbinde...

#### DBC-Karte
- Zeigt die aktuell aktive DBC-Datei
- Quick-Link zum DBC Manager

#### Debug-Karte
- **Firmware Info**: Sendet `i` Befehl zur Firmware
- **Status**: Sendet `Status?` Befehl

#### Einstellungen
- **Auto-Start Logging**: Automatisches Logging bei Verbindung
- **Statistiken zurücksetzen**: Alle Daten löschen

---

### Monitor

Der Monitor ist das Herzstück der App - eine SavvyCAN-ähnliche Ansicht für CAN-Frames.

#### Frame-Liste
| Spalte | Beschreibung |
|--------|-------------|
| # | Frame-Index |
| Time | Zeitstempel (ms) |
| ID | CAN-ID (Hex) |
| Len | Datenlänge (0-8) |
| Data | Hex-Bytes |
| ASCII | ASCII-Darstellung |
| Decoded | Signal-Werte (bei aktiver DBC) |

#### Anzeigemodi
- **Overwrite Mode**: Zeigt nur die letzten N Frames pro ID (Standard)
- **Append Mode**: Zeigt alle Frames chronologisch

#### CAN Senden Panel

Das ausklappbare Send-Panel ermöglicht das Senden von CAN-Frames:

| Feld | Beschreibung |
|------|-------------|
| En | Aktiviert wiederholtes Senden |
| Bus | Bus-Nummer (für Multi-Bus) |
| ID | CAN-ID in Hex (z.B. 7DF) |
| Ext | Extended ID (29-bit) |
| RTR | Remote Transmission Request |
| Data | Hex-Bytes (z.B. 02 01 00) |
| Interval | Wiederholungsintervall (ms, 0=manuell) |
| Count | Anzahl Wiederholungen (0=endlos) |
| Sent | Gesendete Frames |

**Buttons:**
- **Zeile hinzufügen**: Neue Send-Zeile
- **Script einhängen**: TX Script Panel öffnen
- **Send (➤)**: Einzelnen Frame senden
- **Delete (🗑)**: Zeile löschen

#### TX Script Integration

Im Monitor kann ein TX Script direkt ausgeführt werden:
1. "Script einhängen" klicken
2. Script aus Dropdown wählen
3. Play-Button zum Starten
4. Pause/Stop für Kontrolle
5. Error-Badge zeigt Fehler an

#### Sidebar - Control Panel
- **Statistiken**: Total Frames, FPS
- **Logging**: Start/Stop Capturing
- **Clear**: Frames löschen
- **Filter**: IDs ein-/ausblenden

---

### Sniffer

Der Sniffer analysiert CAN-Frames auf Byte-Ebene mit Änderungserkennung.

#### Byte-Änderungs-Visualisierung
- **Grün**: Wert erhöht sich
- **Rot**: Wert verringert sich
- **Fade-Out**: Nach konfigurierbarer Zeit

#### Ansichtsmodi
- **Bytes View**: Hex-Bytes mit Farbmarkierung
- **Bits View**: Einzelne Bits anzeigen
- **ASCII**: Lesbare Zeichen

#### Einstellungen
| Einstellung | Beschreibung |
|-------------|-------------|
| Highlight Duration | Dauer der Farbmarkierung (0-1000ms) |
| Never Expire | IDs bleiben auch ohne neue Frames |
| Mute Notched | Bits ohne Änderung ausblenden |

---

### Signals

Die Signal-Ansicht zeigt dekodierte Werte aus der aktiven DBC.

#### Features
- **Echtzeit-Updates**: Live-Werte aller Signale
- **Suche**: Nach Signal, Message oder ID suchen
- **Nur geändert**: Nur aktive Signale anzeigen
- **Gruppierung**: Nach Message gruppiert

#### Signal-Anzeige
```
[Message-Name] 0x123
├── Signal1: 1234.5 rpm  [▓▓▓▓▓▓░░░░]
├── Signal2: 45.2 °C    [▓▓▓░░░░░░░]
└── Signal3: ON         [Grün]
```

---

### Signal Graph

Echtzeit-Graphen für bis zu 8 Signale gleichzeitig.

#### Bedienung
1. Signale aus der Liste auswählen (Checkbox)
2. Graph zeigt Verlauf der letzten 2000 Samples
3. Auto-Update kann pausiert werden

#### Graph-Features
- **Multi-Color**: Jedes Signal in eigener Farbe
- **Y-Achse**: Automatische Skalierung
- **X-Achse**: Zeit in Samples
- **Legende**: Zeigt aktive Signale

---

### DBC Manager

Verwaltung von DBC-Dateien (Database Container für CAN-Definitionen).

#### DBC-Liste
- Import über Datei-Picker
- Export für Backup/Sharing
- Aktivieren/Deaktivieren pro DBC
- Löschen

#### DBC-Details Dialog
- **Messages**: Anzahl definierter Messages
- **Signals**: Gesamtzahl Signale
- **Nodes**: Definierte ECU-Knoten

### DBC Editor

Vollständiger Editor für DBC-Inhalte.

#### Message bearbeiten
| Feld | Beschreibung |
|------|-------------|
| ID | CAN-ID (Hex) |
| Name | Message-Bezeichnung |
| Length | Datenlänge (1-8 Bytes) |
| Transmitter | Sendender Knoten |
| Extended | 29-bit ID |
| Description | Beschreibung |

#### Signal bearbeiten
| Feld | Beschreibung |
|------|-------------|
| Name | Signal-Bezeichnung |
| Start Bit | Startposition (0-63) |
| Length | Bit-Länge |
| Byte Order | Little/Big Endian |
| Value Type | Signed/Unsigned |
| Factor | Skalierungsfaktor |
| Offset | Offset-Wert |
| Min/Max | Wertebereich |
| Unit | Einheit (z.B. "rpm") |
| Value Descriptions | Lookup-Tabelle |

---

### TX Script Manager

Verwaltung und Ausführung von TX Scripts.

#### Script-Liste
- **Erstellen**: Neues leeres Script
- **Importieren**: .txs Datei laden
- **Exportieren**: Script als Datei speichern
- **Duplizieren**: Kopie erstellen
- **Umbenennen**: Namen ändern
- **Löschen**: Script entfernen

#### Script Editor
- **Syntax-Validierung**: Echtzeit-Fehleranzeige
- **Zeilennummern**: Mit Fehlermarkierung
- **Speichern**: Änderungen sichern
- **Validieren**: Syntax prüfen

#### Ausführungs-Panel
- **Play**: Script starten
- **Pause**: Temporär anhalten
- **Resume**: Fortsetzen
- **Stop**: Beenden und zurücksetzen
- **Errors**: Fehlerlog anzeigen

---

### AI Chat

KI-gestützte CAN-Analyse mit verschiedenen Anbietern.

#### Unterstützte Provider
- **OpenRouter** - Viele kostenlose Modelle! (Llama, Gemma, Mistral, etc.)
- **Google Gemini** - gemini-1.5-flash, gemini-1.5-pro, etc.
- **OpenAI** - gpt-4o, gpt-4o-mini, gpt-4-turbo, etc.
- **Anthropic Claude** - claude-3-5-sonnet, claude-3-opus, etc.

#### Kostenlose Modelle (OpenRouter)
- Llama 3.2 3B
- Gemma 2 9B
- Mistral 7B
- Zephyr 7B
- OpenChat 7B
- Nous Capybara 7B

#### Einrichtung
1. **Settings → AI Chat** öffnen
2. Provider auswählen (OpenRouter, Gemini, OpenAI, Claude)
3. API-Key eingeben (OpenRouter: openrouter.ai - kostenlos!)
4. Modell auswählen
5. Verbindung testen

#### Features
- **Snapshot-Analyse**: CAN-Daten an AI senden
- **DBC-Generierung**: AI erstellt Signal-Definitionen
- **Chat-History**: Mehrere Gespräche verwalten
- **Delta-Mode**: Nur Änderungen senden (Token-sparend)

#### Chat-Export
Chats können in verschiedenen Formaten exportiert werden:
- **Markdown** (.md): Formatiert mit Emojis und collapsible Sections
- **Text** (.txt): Einfaches Textformat
- **JSON** (.json): Vollständige Daten für Import/Backup

Export-Optionen:
- **Einzelner Chat**: Share-Button in der Chat-Ansicht
- **Alle Chats**: "Exportieren"-Button in den Gemini Settings
- **Teilen**: Direkt an andere Apps senden

#### AI-generierte DBC
Die AI kann DBC-Befehle generieren:
```json
{
  "commands": [
    {"type": "addMessage", "id": 513, "name": "EngineData", "length": 8},
    {"type": "addSignal", "messageId": 513, "name": "RPM", "startBit": 0, "length": 16}
  ]
}
```

Diese werden automatisch geparst und zur DBC hinzugefügt.

---

### Settings

Zentrale Einstellungen der App.

#### CAN-Bus Einstellungen
| Einstellung | Beschreibung |
|-------------|-------------|
| Baudrate | CAN-Bus Geschwindigkeit |
| Auto-Connect | Automatisch verbinden |
| Loopback | Test-Modus (Echo) |

#### Erscheinungsbild
| Einstellung | Beschreibung |
|-------------|-------------|
| Sprache | System/Deutsch/English |

#### Protokollierung
| Einstellung | Beschreibung |
|-------------|-------------|
| Log-Speicherort | Ordner für Log-Dateien |
| Snapshot Manager | Gespeicherte Snapshots verwalten |

#### TX Scripts
| Einstellung | Beschreibung |
|-------------|-------------|
| TX Script Manager | Scripts verwalten |

#### Künstliche Intelligenz
| Einstellung | Beschreibung |
|-------------|-------------|
| AI Chat | Provider, API-Key und Modell konfigurieren |

#### Entwickler
| Einstellung | Beschreibung |
|-------------|-------------|
| Status-Logging | Debug-Logs aktivieren |
| Log-Intervall | Intervall für Dev-Logs |

---

## DBC-Format

DirectCAN unterstützt das Standard-DBC-Format für CAN-Datenbanken.

### Unterstützte Elemente

```dbc
VERSION "1.0"

NS_ :

BS_:

BU_: ECU Cluster Gateway

BO_ 201 EngineRPM: 8 ECU
 SG_ RPM : 0|16@1+ (0.25,0) [0|16383.75] "rpm" Cluster
 SG_ EngineRunning : 16|1@1+ (1,0) [0|1] "" Cluster
 SG_ EngineTemp : 24|8@1+ (1,-40) [-40|215] "C" Cluster

BO_ 180 VehicleSpeed: 8 ECU
 SG_ Speed : 0|16@1+ (0.01,0) [0|655.35] "km/h" Cluster

CM_ BO_ 201 "Engine status message";
CM_ SG_ 201 RPM "Engine revolutions per minute";

VAL_ 201 EngineRunning 0 "Off" 1 "Running" ;
```

### Signal-Dekodierung

#### Byte Order
- **Little Endian (Intel)**: `@1+` - LSB first
- **Big Endian (Motorola)**: `@0+` - MSB first

#### Value Type
- **Unsigned**: `+` am Ende
- **Signed**: `-` am Ende

#### Formel
```
Physical Value = (Raw Value × Factor) + Offset
```

### Beispiel: RPM-Dekodierung

```
Signal: RPM : 0|16@1+ (0.25,0) [0|16383.75] "rpm"

Raw Data: 0x30 0x75 ...
Raw Value: 0x7530 = 30000
Physical: 30000 × 0.25 + 0 = 7500 rpm
```

---

## TX Script Sprache

DirectCAN bietet eine eigene Domain-Specific Language (DSL) für CAN-Sequenzen.

### Dateiformat
- **Endung**: `.txs`
- **Encoding**: UTF-8
- **Kommentare**: `//` (Zeile) oder `/* */` (Block)

### Grundbefehle

#### Frames senden
```txscript
// Standard CAN-ID mit Daten
send(0x7DF, [02, 01, 00])

// Extended ID (29-bit)
send(0x18DAF110, [02, 3E, 00], ext)

// Mit Variablen
var diagId = 0x7DF
var data = [02, 01, 0C]
send(diagId, data)
```

#### Verzögerung
```txscript
delay(100)    // 100 Millisekunden
delay(2s)     // 2 Sekunden
```

#### Schleifen
```txscript
// Feste Anzahl
repeat(5) {
    send(0x7DF, [02, 01, 0D])
    delay(100)
}

// Endlos (bis Stop)
loop {
    send(0x7DF, [02, 01, 00])
    delay(500)
}
```

#### Bedingungen
```txscript
if (response.id == 0x7E8) {
    send(0x7DF, [02, 01, 0C])
} else {
    send(0x7DF, [02, 01, 00])
}
```

#### Response abwarten
```txscript
// Auf bestimmte ID warten
wait_for(0x7E8, timeout: 1000)

// Mit Daten-Pattern (* = Wildcard)
wait_for(0x7E8, data: [41, 01, *])
```

### Variablen & Ausdrücke

```txscript
// Variablen
var diagId = 0x7DF
var responseId = 0x7E8
var ecuData = [02, 01, 00]

// Berechnungen
var rpm = (response.data[3] * 256 + response.data[4]) / 4
var checksum = (data[0] + data[1] + data[2]) & 0xFF

// Zufallswerte
var randomByte = random(0x00, 0xFF)
var randomData = random_bytes(8)
```

### Funktionen

```txscript
// Definition
function requestPID(pid) {
    send(0x7DF, [02, 01, pid])
    wait_for(0x7E8, timeout: 500)
    return response.data[3]
}

// Aufruf
var speed = requestPID(0x0D)
print("Speed: ", speed)
```

### Trigger

```txscript
// Bei Empfang einer ID
on_receive(0x123) {
    send(0x456, [01, 02, 03])
}

// Periodisch
on_interval(1000) {
    send(0x7DF, [02, 01, 00])
}
```

### Eingebaute Variablen

| Variable | Beschreibung |
|----------|-------------|
| `response` | Letzter empfangener Frame |
| `response.id` | ID des Frames |
| `response.data` | Daten-Array |
| `response.data[n]` | n-tes Byte |
| `response.timestamp` | Zeitstempel |
| `now` | Aktuelle Zeit (ms) |
| `iteration` | Schleifenzähler |

### Operatoren

| Kategorie | Operatoren |
|-----------|-----------|
| Arithmetik | `+` `-` `*` `/` `%` |
| Vergleich | `==` `!=` `<` `<=` `>` `>=` |
| Logik | `&&` `\|\|` `!` |
| Bitweise | `&` `\|` `^` `~` `<<` `>>` |

### Beispiel: OBD-II Diagnose

```txscript
// OBD-II Fahrzeug-Diagnose Script
var diagId = 0x7DF
var timeout = 1000

function queryPID(pid) {
    send(diagId, [02, 01, pid])
    wait_for(0x7E8, timeout: timeout)
    return response
}

// Motor-RPM abfragen
repeat(10) {
    queryPID(0x0C)
    if (response.data[0] == 0x41) {
        var rpm = (response.data[3] * 256 + response.data[4]) / 4
        print("RPM: ", rpm)
    }
    delay(500)
}
```

### Beispiel: Tester Present

```txscript
// Diagnose-Session aufrecht erhalten
loop {
    send(0x7E0, [02, 3E, 00])
    delay(2000)
}
```

---

## USB-Protokoll

DirectCAN kommuniziert über ein textbasiertes Protokoll mit dem CAN-Adapter.

### Frame-Format (Empfang)

```
t<TIMESTAMP> <ID>[X][R] <LENGTH> <DATA_BYTES>

Beispiele:
t12345 123 8 01 02 03 04 05 06 07 08
t12346 7DFX 3 02 01 00                    // Extended ID
t12347 123R 0                             // Remote Frame
```

| Feld | Beschreibung |
|------|-------------|
| t | Frame-Präfix |
| TIMESTAMP | Zeitstempel in ms |
| ID | CAN-ID (Hex, ohne 0x) |
| X | Extended ID Marker (optional) |
| R | Remote Frame Marker (optional) |
| LENGTH | Datenlänge (0-8) |
| DATA_BYTES | Hex-Bytes mit Leerzeichen |

### Befehle (Senden)

```
// CAN-Frame senden
s<ID>[X] <LENGTH> <DATA_BYTES>

// Beispiele:
s7DF 3 02 01 00                // Standard ID
s18DAF110X 3 02 3E 00          // Extended ID

// Konfiguration
i                              // Firmware Info
Status?                        // Status abfragen
```

### Baudrate

Die USB-Serial-Verbindung verwendet 2.000.000 bps (2 Mbit/s).

---

## Architektur

### Projekt-Struktur

```
app/src/main/java/at/planqton/directcan/
├── DirectCanApplication.kt      # App-Singleton mit Repositories
├── MainActivity.kt              # Navigation Host
│
├── data/
│   ├── can/
│   │   ├── CanModels.kt        # CanFrame, DecodedFrame
│   │   ├── CanDataRepository.kt # Zentrale Datenverarbeitung
│   │   └── CanSimulator.kt     # Test-Simulation
│   │
│   ├── dbc/
│   │   ├── DbcModels.kt        # DbcFile, DbcMessage, DbcSignal
│   │   ├── DbcParser.kt        # DBC-Datei Parser
│   │   └── DbcRepository.kt    # DBC-Verwaltung
│   │
│   ├── usb/
│   │   └── UsbSerialManager.kt # USB-Kommunikation
│   │
│   ├── settings/
│   │   └── SettingsRepository.kt # DataStore Einstellungen
│   │
│   ├── gemini/
│   │   ├── GeminiRepository.kt  # AI API Client
│   │   ├── GeminiResponseParser.kt
│   │   └── DbcCommands.kt       # AI-generierte DBC-Befehle
│   │
│   └── txscript/
│       ├── TxScriptModels.kt    # Script-Datenmodelle
│       ├── TxScriptState.kt     # Ausführungszustand
│       ├── TxScriptCommands.kt  # AST-Definitionen
│       ├── TxScriptRepository.kt # Script-Verwaltung
│       ├── TxScriptExecutor.kt  # Script-Ausführung
│       └── parser/
│           ├── TxScriptLexer.kt # Tokenizer
│           └── TxScriptParser.kt # Parser
│
├── ui/
│   ├── navigation/
│   │   └── Navigation.kt        # Screen-Definitionen
│   │
│   ├── screens/
│   │   ├── home/
│   │   │   └── HomeScreen.kt
│   │   ├── monitor/
│   │   │   └── MonitorScreen.kt
│   │   ├── sniffer/
│   │   │   └── SnifferScreen.kt
│   │   ├── signals/
│   │   │   ├── SignalViewerScreen.kt
│   │   │   └── SignalGraphScreen.kt
│   │   ├── dbc/
│   │   │   ├── DbcManagerScreen.kt
│   │   │   ├── DbcEditorScreen.kt
│   │   │   ├── MessageEditorDialog.kt
│   │   │   └── SignalEditorDialog.kt
│   │   ├── settings/
│   │   │   └── SettingsScreen.kt
│   │   ├── logs/
│   │   │   └── LogManagerScreen.kt
│   │   ├── gemini/
│   │   │   ├── GeminiSettingsScreen.kt
│   │   │   └── GeminiChatScreen.kt
│   │   └── txscript/
│   │       ├── TxScriptManagerScreen.kt
│   │       ├── ScriptEditorScreen.kt
│   │       ├── ScriptErrorLogDialog.kt
│   │       └── components/
│   │           └── ScriptControlPanel.kt
│   │
│   └── theme/
│       └── Theme.kt
│
├── service/
│   └── CanLoggingService.kt     # Hintergrund-Logging
│
└── util/
    └── LocaleHelper.kt          # Sprachverwaltung
```

### Datenfluss

```
┌─────────────────────────────────────────────────────────────┐
│                     USB CAN Adapter                         │
└─────────────────────────┬───────────────────────────────────┘
                          │ USB Serial (2 Mbit/s)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   UsbSerialManager                          │
│  • Verbindungsmanagement                                    │
│  • Frame-Parsing (CanFrame.fromTextLine)                    │
│  • receivedLines: SharedFlow<String>                        │
└─────────────────────────┬───────────────────────────────────┘
                          │ Flow Collection
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   CanDataRepository                         │
│  • processFrame() - Zentrale Verarbeitung                   │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │
│  │monitorFrames│ │snifferFrames│ │signalValues/History │   │
│  │  List<>     │ │  Map<>      │ │     Map<>           │   │
│  └──────┬──────┘ └──────┬──────┘ └──────────┬──────────┘   │
└─────────┼───────────────┼───────────────────┼───────────────┘
          │               │                   │
          ▼               ▼                   ▼
    ┌──────────┐   ┌──────────┐   ┌────────────────────┐
    │ Monitor  │   │ Sniffer  │   │ Signals/Graph      │
    │ Screen   │   │ Screen   │   │ Screens            │
    └──────────┘   └──────────┘   └────────────────────┘
```

### State Management

Die App verwendet Kotlin Coroutines mit StateFlow für reaktive Updates:

```kotlin
// Repository
private val _frames = MutableStateFlow<List<CanFrame>>(emptyList())
val frames: StateFlow<List<CanFrame>> = _frames.asStateFlow()

// UI (Composable)
val frames by repository.frames.collectAsState()
```

### Dependency Injection

Manuelle DI über DirectCanApplication Singleton:

```kotlin
class DirectCanApplication : Application() {
    lateinit var usbSerialManager: UsbSerialManager
    lateinit var dbcRepository: DbcRepository
    lateinit var canDataRepository: CanDataRepository
    // ...

    companion object {
        lateinit var instance: DirectCanApplication
    }
}

// Usage
val repository = DirectCanApplication.instance.canDataRepository
```

---

## Build & Development

### Voraussetzungen

- **Android Studio**: Arctic Fox oder neuer
- **JDK**: 17
- **Android SDK**: API 26-34

### Build

```bash
# Debug Build
./gradlew assembleDebug

# Release Build (signiert)
./gradlew assembleRelease

# Tests
./gradlew test
```

### Dependencies

| Library | Version | Verwendung |
|---------|---------|------------|
| Jetpack Compose | 1.5+ | UI Framework |
| Material 3 | 1.1+ | Design System |
| Kotlin Coroutines | 1.7+ | Async/Flow |
| Kotlin Serialization | 1.6+ | JSON |
| usb-serial-for-android | 3.7+ | USB Serial |
| Vico | 2.0+ | Charts |
| Google Generative AI | 0.9+ | Gemini API |
| DataStore | 1.0+ | Preferences |
| Navigation Compose | 2.7+ | Navigation |

### Code Style

- **Kotlin**: Offizielle Kotlin Coding Conventions
- **Compose**: Single-State-Hoisting Pattern
- **Repository Pattern**: Für Datenzugriff

---

## Lizenz

```
Copyright (c) 2024 Planqton

Dieses Projekt ist unter der MIT-Lizenz lizenziert.
Siehe LICENSE-Datei für Details.
```

---

## Support & Feedback

- **Issues**: [GitHub Issues](https://github.com/your-repo/directcan/issues)
- **Dokumentation**: Diese README
- **Email**: support@planqton.at

---

<p align="center">
  Entwickelt für die CAN-Bus Community
</p>
