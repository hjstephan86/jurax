# JuraX – Rechtliche Verfahrensverwaltung

Vollständiges System zur Verwaltung rechtlicher Verfahren auf Basis von **Jakarta EE 10**, **PostgreSQL** und einer modernen Single-Page-Webanwendung.

---

## Inhaltsverzeichnis

1. [Architektur](#architektur)
2. [Voraussetzungen](#voraussetzungen)
3. [Installation](#installation)
4. [Ersteinrichtung beim ersten Start](#4-ersteinrichtung-beim-ersten-start)
5. [Automatische Dokumentenmigration beim Systemstart](#automatische-dokumentenmigration-beim-systemstart)
6. [Dokumente manuell hochladen](#dokumente-manuell-hochladen)
7. [Anwendung starten](#anwendung-starten)
8. [Testausführung](#testausführung)

---

## Architektur

```
Browser (index.html)
    │  Aktenzeichen-Suche (fuzzy, 300 ms Debouncing)
    │  Datumsfilter (Jahr / Monat / Tag)
    │  PDF-Upload + eingebetteter PDF-Viewer
    ▼
Jakarta EE 10 REST API  (WildFly 40.0.1.Final)
    │  JPA / Hibernate 7.3.2
    │  StartupMigration (@Singleton @Startup)
    ▼
PostgreSQL 18.4
    │  Trigram-GIN-Index (pg_trgm) für AZ-Suche
    │  B-Tree-Index auf Jahr/Monat/Tag
    │  TABLESAMPLE BERNOULLI (Zufallsanzeige)
    ▼
Dateisystem
    │  {root}/{jahr}/{monat}/{tag}/{dateiname}.pdf
    ▼
PDF-Ablage nach Datum
```

**Designprinzip:** Die initiale Ansicht zeigt ~30 zufällige, **unsortierte** Verfahren. Erst beim Tippen im Suchfeld oder Auslösen des Datumsfilters entsteht Ordnung. Max. 500 Treffer pro Suche.

**Aktenzeichen-Fuzzy-Suche:** `14C123`, `14-C-123`, `14 C 123/24` und `14c` treffen alle dasselbe Verfahren — Bindestriche, Leerzeichen und Groß-/Kleinschreibung werden normalisiert.

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Java JDK | 21 |
| Apache Maven | 3.9+ |
| PostgreSQL | 18.4 |
| WildFly | 40.0.1.Final |

---

## Installation

### 1. Repository klonen

```bash
git clone https://github.com/hjstephan86/jurax.git
cd jurax
```

### 2. PostgreSQL einrichten

```bash
psql -U postgres -c "CREATE DATABASE juraxdb;"
psql -U postgres -c "CREATE USER juraxuser WITH PASSWORD 'jurax';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE juraxdb TO juraxuser;"
psql -U juraxuser -d juraxdb -f schema.sql
```

`schema.sql` richtet ein:
- Tabelle `verfahren` mit allen Feldern inkl. `datei_jahr`, `datei_monat`, `datei_tag`
- Trigram-GIN-Indizes auf normalisiertem und originalem Aktenzeichen
- B-Tree-Indizes auf Jahr/Monat/Tag für schnelle Datumsfilterung
- `random_verfahren(n)` via `TABLESAMPLE BERNOULLI`
- `search_verfahren_az(q)` — 3-Zweig-UNION für Fuzzy-AZ-Suche
- `search_verfahren_datum(jahr, monat, tag)` — optionale Parameter

### 3. WildFly DataSource konfigurieren

```xml
<datasource jndi-name="java:/JuraxDS" pool-name="JuraxDS" enabled="true">
    <connection-url>jdbc:postgresql://localhost:5432/juraxdb</connection-url>
    <driver>postgresql</driver>
    <security user-name="juraxuser" password="jurax"/>
</datasource>
```

### 4. Ersteinrichtung beim ersten Start

Weder `root.txt` noch `ai_api_key.txt` müssen manuell angelegt werden. JuraX erledigt die Konfiguration beim ersten Start vollautomatisch über einen **Setup-Dialog**.

#### Ablauf

Beim ersten Aufruf der Webanwendung (`http://localhost:8080/jurax/`) erkennt JuraX, dass noch keine Konfiguration vorhanden ist, und blendet einen **vollflächigen Setup-Dialog** ein, der die gesamte Oberfläche blockiert, bis alle Pflichtfelder ausgefüllt sind. Erst nach dem Speichern startet die Anwendung normal.

Der Dialog enthält zwei Felder:

**1. Wurzelverzeichnis (Pflichtfeld)**

Der absolute Pfad zu dem Verzeichnis, in dem JuraX alle PDF-Dokumente nach folgendem Schema ablegt:

```
{Wurzelverzeichnis}/
└── 2024/
    └── 03/
        └── 15/
            └── 14-C-123-24.pdf
```

JuraX legt das Verzeichnis automatisch an, falls es noch nicht existiert. Der Pfad wird in `root.txt` im Projektverzeichnis gespeichert.

**2. KI API-Key (optional)**

Der API-Key eines KI-Dienstes, der für die **automatische Dokumentenmigration beim Systemstart** benötigt wird (siehe nächster Abschnitt). Das Feld ist bewusst nicht auf einen bestimmten Anbieter festgelegt — es kann jeder kompatible KI-Dienst verwendet werden. Der Key wird in `ai_api_key.txt` im Projektverzeichnis gespeichert und als Passwort maskiert eingegeben.

Beim manuellen Hochladen von Dokumenten wird kein API-Key benötigt — der Benutzer legt das Ablage-Datum dort selbst fest.

#### Verhalten nach der Ersteinrichtung

Ab dem zweiten Start erscheint der Setup-Dialog nicht mehr. JuraX liest `root.txt` und `ai_api_key.txt` automatisch ein.

Die Konfiguration kann jederzeit zurückgesetzt werden, indem `root.txt` geleert oder gelöscht wird — beim nächsten Start erscheint der Setup-Dialog erneut.

> `root.txt` und `ai_api_key.txt` enthalten maschinenspezifische und sicherheitsrelevante Daten und werden nicht in das Git-Repository eingecheckt (`.gitignore`).

---

## Automatische Dokumentenmigration beim Systemstart

### Hintergrund

JuraX erwartet, dass alle PDF-Dokumente im Wurzelverzeichnis nach dem Schema

```
{root}/{jahr}/{monat}/{tag}/dateiname.pdf
```

abgelegt sind. Wenn das Wurzelverzeichnis beim Start **nicht** dieser Struktur entspricht — etwa weil Dokumente bisher in einer anderen, benutzerdefinierten Ordnerstruktur gespeichert wurden — führt JuraX automatisch eine **KI-gestützte Migration** durch.

> **Beim manuellen Hochladen von Dokumenten ist keine KI erforderlich** — der Benutzer legt das Ablage-Datum selbst fest (siehe Abschnitt [Anwendung starten](#anwendung-starten)). Die KI kommt ausschließlich beim Systemstart zum Einsatz, wenn vorhandene Dokumente in unbekannter Struktur automatisch einsortiert werden müssen.

Ein einfaches Skript reicht für die automatische Migration nicht aus: Jeder Benutzer kann eine völlig andere Ausgangsstruktur haben. Dateinamen wie `Urteil_OLG_Hamm.pdf`, `scan_2023-11_Bielefeld.pdf` oder `brief_an_jobcenter.pdf` erfordern individuelle Analyse — kein regelbasierter Ansatz kann das zuverlässig leisten.

### Ablauf der Migration

Beim Serverstart führt `StartupMigration` (`@Singleton @Startup`) folgende Schritte aus:

```
1. Prüfung: Enthält das Wurzelverzeichnis bereits {jahr}/{monat}/{tag}/?
        │
        ├── JA  → Keine Migration notwendig. Start normal.
        │
        └── NEIN → Migration starten:
                │
                ├── Schritt 1: Alle PDFs werden atomar nach {root}/bak/ gesichert.
                │             Namenskollisionen werden automatisch aufgelöst.
                │
                ├── Schritt 2: Für jede Datei in bak/ wird die KI-API befragt.
                │             Der Dateiname wird analysiert und Jahr/Monat/Tag ermittelt.
                │             Beispiele:
                │               "14-C-123-24.pdf"          → 2024/03/15
                │               "scan_2023-11_brief.pdf"   → 2023/11/01
                │               "urteil_olg_hamm.pdf"      → Claude schätzt anhand
                │                                             verfügbarer Hinweise
                │
                └── Schritt 3: Dokumente werden in {root}/{jahr}/{monat}/{tag}/ kopiert.
                              Nicht klassifizierbare Dateien verbleiben in bak/
                              mit Warnung im Serverlog.
```

### KI API-Key

Der KI API-Key wird beim ersten Start über den Setup-Dialog abgefragt und automatisch in `ai_api_key.txt` gespeichert. Eine manuelle Anlage ist nicht notwendig.

Der Key ist bewusst anbieterunabhängig gehalten — es kann jeder KI-Dienst verwendet werden, dessen API das Format der Anfragen unterstützt. Der Dienst muss in der Lage sein, aus einem Dateinamen ein Datum (Jahr, Monat, Tag) im JSON-Format zurückzugeben.

**Fehlt der API-Key:** JuraX startet dennoch. Dokumente in `bak/` werden mit dem heutigen Datum als Fallback eingeordnet, und eine Warnung erscheint im Serverlog. Die Ablage kann dann manuell korrigiert werden.

**Die `bak/`-Kopien bleiben immer erhalten** — kein Dokument wird gelöscht. Die Migration ist jederzeit rückgängig zu machen, indem der Inhalt von `bak/` wiederhergestellt wird.

---

## Dokumente manuell hochladen

JuraX unterstützt zwei Upload-Wege — in beiden Fällen legt der Benutzer das Ablage-Datum selbst fest, ohne KI-Unterstützung:

**Einzelner Upload** — Button „+ Verfahren / PDF hochladen":
Öffnet ein Formular mit allen Metadaten (Aktenzeichen, Gericht, Status, Eingangsdatum, Notizen) sowie zwei Datumsfeldern:
- **Eingangsdatum** — das juristische Eingangsdatum des Verfahrens
- **Ablage-Datum** — bestimmt den Ordnerpfad `{Jahr}/{Monat}/{Tag}/`; bleibt das Feld leer, wird das Eingangsdatum verwendet

**Mehrfach-Upload** — Button „📂 Mehrere PDFs hochladen":
Ermöglicht die gleichzeitige Auswahl beliebig vieler PDF-Dateien. Für jede Datei erscheint eine eigene Zeile in einer Tabelle, in der Aktenzeichen, Gericht, Eingangsdatum, Ablage-Datum und Status individuell eingetragen werden können. Zusätzlich gibt es ein globales „Ablage-Datum für alle setzen"-Feld, das alle Zeilen auf einmal befüllt und anschließend pro Datei noch überschrieben werden kann. Ein Fortschrittsbalken zeigt den Upload-Fortschritt an.

---

## Anwendung starten

```bash
# 1. WildFly starten
$WILDFLY_HOME/bin/standalone.sh

# 2. Projekt bauen
mvn clean package -DskipTests

# 3. WAR deployen
cp target/jurax.war $WILDFLY_HOME/standalone/deployments/

# 4. Aufrufen
#    Beim ersten Start erscheint der Setup-Dialog:
#    → Wurzelverzeichnis eingeben (wird automatisch angelegt)
#    → KI API-Key eingeben (optional, nur für automatische Migration)
#    Ab dem zweiten Start startet die Anwendung direkt.
open http://localhost:8080/jurax/
```

**Hot-Redeploy** (ohne WildFly-Neustart):
```bash
mvn clean package -DskipTests
$WILDFLY_HOME/bin/jboss-cli.sh --connect \
  --command="deploy target/jurax.war --force"
```

---

## Testausführung

```bash
# Unit- und Integrationstests
mvn test -Dsurefire.excludes="**/ui/**" -Djacoco.skip=true

# Alle Tests + JaCoCo-Report (WildFly muss laufen für UI-Tests)
mvn clean verify

# Playwright-Browser einmalig installieren
mvn exec:java -e \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"

# Coverage-Report
open doc/coverage/index.html
```

| Klasse | Typ | Tests |
|---|---|---|
| `VerfahrenResourceMockTest` | Unit (Mockito) | ~25 |
| `VerfahrenResourceIntegrationTest` | Integration (H2) | 10 |
| `VerfahrenUITest` | E2E (Playwright) | ~30 |
| **Gesamt** | | **~65** |

Konfiguriertes Minimum: **87 %**
