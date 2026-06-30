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
| Java JDK | 21+ (getestet mit JDK 26) |
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

**Linux/macOS:**
```bash
psql -U postgres -c "CREATE DATABASE juraxdb;"
psql -U postgres -c "CREATE USER juraxuser WITH PASSWORD 'jurax';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE juraxdb TO juraxuser;"
psql -U postgres -d juraxdb -c "GRANT ALL ON SCHEMA public TO juraxuser;"
psql -U juraxuser -d juraxdb -f schema.sql
```

**Windows (PostgreSQL als Binär-Distribution, z. B. unter `%USERPROFILE%\Downloads\postgresql-18.4-2-windows-x64-binaries\pgsql`):**

PostgreSQL muss zunächst initialisiert und gestartet werden:
```powershell
$pgBin = "C:\...\postgresql-18.4-2-windows-x64-binaries\pgsql\bin"
$pgData = "C:\Users\<user>\pg_data"

# Cluster initialisieren
"postgres" | Out-File "$env:TEMP\pgpass.txt" -Encoding ascii -NoNewline
& "$pgBin\initdb.exe" -D $pgData -U postgres -A md5 "--pwfile=$env:TEMP\pgpass.txt"

# Server starten
& "$pgBin\pg_ctl.exe" -D $pgData -l "$pgData\pg.log" start

# Datenbank und Benutzer anlegen
$env:PGPASSWORD = "postgres"
& "$pgBin\psql.exe" -U postgres -c "CREATE DATABASE juraxdb;"
& "$pgBin\psql.exe" -U postgres -c "CREATE USER juraxuser WITH PASSWORD 'jurax';"
& "$pgBin\psql.exe" -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE juraxdb TO juraxuser;"
& "$pgBin\psql.exe" -U postgres -d juraxdb -c "GRANT ALL ON SCHEMA public TO juraxuser;"

# Schema laden
$env:PGPASSWORD = "jurax"
& "$pgBin\psql.exe" -U juraxuser -d juraxdb -f schema.sql
```

`schema.sql` richtet ein:
- Tabelle `verfahren` mit allen Feldern inkl. `datei_jahr`, `datei_monat`, `datei_tag`
- Trigram-GIN-Indizes auf normalisiertem und originalem Aktenzeichen
- B-Tree-Indizes auf Jahr/Monat/Tag für schnelle Datumsfilterung
- `random_verfahren(n)` via `TABLESAMPLE BERNOULLI`
- `search_verfahren_az(q)` — 3-Zweig-UNION für Fuzzy-AZ-Suche
- `search_verfahren_datum(jahr, monat, tag)` — optionale Parameter

### 3. WildFly DataSource konfigurieren

#### 3a. PostgreSQL-Modul anlegen

Das PostgreSQL JDBC-JAR (`postgresql-42.7.3.jar`) muss als WildFly-Modul eingebunden werden. Das JAR liegt nach einem `mvn package` im lokalen Maven-Repository unter `~/.m2/repository/org/postgresql/postgresql/42.7.3/`.

Verzeichnis anlegen und Datei kopieren:
```bash
mkdir -p $WILDFLY_HOME/modules/org/postgresql/main
cp ~/.m2/repository/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar \
   $WILDFLY_HOME/modules/org/postgresql/main/
```

Dann `module.xml` in demselben Verzeichnis anlegen:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<module name="org.postgresql" xmlns="urn:jboss:module:1.9">
    <resources>
        <resource-root path="postgresql-42.7.3.jar"/>
    </resources>
    <dependencies>
        <module name="javax.api"/>
        <module name="javax.transaction.api"/>
    </dependencies>
</module>
```

#### 3b. Treiber und DataSource in `standalone.xml` eintragen

In `$WILDFLY_HOME/standalone/configuration/standalone.xml` im Abschnitt `<datasources>` ergänzen:

```xml
<datasource jndi-name="java:/JuraxDS" pool-name="JuraxDS" enabled="true" use-java-context="true">
    <connection-url>jdbc:postgresql://localhost:5432/juraxdb</connection-url>
    <driver>postgresql</driver>
    <pool>
        <min-pool-size>5</min-pool-size>
        <max-pool-size>25</max-pool-size>
    </pool>
    <security user-name="juraxuser" password="jurax"/>
</datasource>
```

Im Abschnitt `<drivers>` ergänzen (falls noch nicht vorhanden):
```xml
<driver name="postgresql" module="org.postgresql">
    <xa-datasource-class>org.postgresql.xa.PGXADataSource</xa-datasource-class>
</driver>
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

**Linux/macOS:**
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

**Windows (PowerShell):**
```powershell
# 1. WildFly starten
Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$env:WILDFLY_HOME\bin\standalone.bat`"" -WindowStyle Minimized

# 2. Projekt bauen
mvn clean package -DskipTests

# 3. WAR deployen (WildFly erkennt neue Dateien automatisch)
Copy-Item target\jurax.war "$env:WILDFLY_HOME\standalone\deployments\" -Force

# 4. Aufrufen
Start-Process "http://localhost:8080/jurax/"
```

**Hot-Redeploy** (ohne WildFly-Neustart):
```bash
# Linux/macOS
mvn clean package -DskipTests
$WILDFLY_HOME/bin/jboss-cli.sh --connect \
  --command="deploy target/jurax.war --force"
```
```powershell
# Windows (alternativ: WAR direkt kopieren — WildFly redeploys automatisch)
mvn clean package -DskipTests
Copy-Item target\jurax.war "$env:WILDFLY_HOME\standalone\deployments\" -Force
```

---

## Testausführung

```bash
# Unit- und Integrationstests (Linux/macOS)
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

**Windows (PowerShell):** Die `-D`-Argumente müssen mit Backtick-Escaping übergeben werden:
```powershell
# Unit- und Integrationstests
mvn test `"-Dsurefire.excludes=**/ui/**`" `"-Djacoco.skip=true`"

# Playwright-Browser installieren
mvn exec:java -e `"-Dexec.mainClass=com.microsoft.playwright.CLI`" `"-Dexec.args=install chromium`"
```

| Klasse | Typ | Tests |
|---|---|---|
| `VerfahrenResourceMockTest` | Unit (Mockito) | ~25 |
| `VerfahrenResourceIntegrationTest` | Integration (H2) | 10 |
| `VerfahrenUITest` | E2E (Playwright) | ~30 |
| **Gesamt** | | **~65** |

Konfiguriertes Minimum: **87 %**

---

## Bekannte Fixes beim Aufsetzen

### Kompilierfehler: `Path` mehrdeutig (`ConfigResource.java`)

Beim Kompilieren mit JDK 21+ kann folgender Fehler auftreten:

```
Referenz zu Path ist mehrdeutig:
  java.nio.file.Path vs jakarta.ws.rs.Path
```

**Ursache:** Die Klasse `ConfigResource.java` importierte `java.nio.file.*` (Wildcard) und `jakarta.ws.rs.*` (Wildcard). Beide enthalten einen Typ namens `Path`.

**Lösung:** Den Wildcard-Import `import java.nio.file.*;` durch spezifische Imports ersetzen:
```java
// Statt:
import java.nio.file.*;

// Verwenden:
import java.nio.file.Files;
import java.nio.file.Paths;
```

Da `java.nio.file.Path` in der Klasse bereits überall vollständig qualifiziert (`java.nio.file.Path`) verwendet wird, ist dieser Wildcard-Import nicht notwendig.

### Test-Kompilierfehler: `upload()` — falsche Parameteranzahl

Die Methode `upload()` in `VerfahrenResource` wurde um den Parameter `ablageDatumStr` erweitert (Position 6), die Testaufrufe in `VerfahrenResourceMockTest` wurden jedoch nicht angepasst.

**Lösung:** In `VerfahrenResourceMockTest.java` bei allen `upload()`-Aufrufen `null` für `ablageDatumStr` als 6. Argument ergänzen:
```java
// Statt (8 Argumente):
resource.upload(az, bez, ger, status, datum, notizen, dateiName, stream)

// Richtig (9 Argumente):
resource.upload(az, bez, ger, status, datum, ablageDatum, notizen, dateiName, stream)
```

### PostgreSQL: Schema-Berechtigungen

Bei PostgreSQL 18+ fehlen dem neu angelegten Benutzer standardmäßig die Schreib-Rechte auf das `public`-Schema. Nach dem Anlegen des Benutzers muss daher folgendes ausgeführt werden:
```sql
GRANT ALL ON SCHEMA public TO juraxuser;
```
