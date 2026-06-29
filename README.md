# JuraX – Rechtliche Verfahrensverwaltung

Vollständiges System zur Verwaltung rechtlicher Verfahren auf Basis von **Jakarta EE 10**, **PostgreSQL** und einer modernen Single-Page-Webanwendung.

---

## Inhaltsverzeichnis

1. [Architektur](#architektur)
2. [Voraussetzungen](#voraussetzungen)
3. [Installation](#installation)
4. [Anwendung starten](#anwendung-starten)
5. [Testausführung](#testausführung)
6. [API-Referenz](#api-referenz)
7. [Projektstruktur](#projektstruktur)

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

### 4. Wurzelverzeichnis konfigurieren

Das Wurzelverzeichnis für die PDF-Ablage wird in der Datei `root.txt` im Projektverzeichnis eingetragen. Die Datei muss **vor dem Serverstart** manuell angelegt werden:

```bash
echo "/pfad/zu/jurax-docs" > root.txt
```

Die PDF-Dateien werden in folgender Struktur abgelegt:

```
/pfad/zu/jurax-docs/
└── 2024/
    └── 03/
        └── 15/
            └── 14-C-123-24.pdf
```

> `root.txt` enthält einen maschinenspezifischen absoluten Pfad und wird nicht eingecheckt (`.gitignore`).

---

## Automatische Dokumentenmigration beim Systemstart

> **Dies ist ein zentrales Feature von JuraX und muss vor dem ersten Start verstanden werden.**

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
                ├── Schritt 2: Für jede Datei in bak/ wird die Claude-API befragt.
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

### Claude API-Key konfigurieren

Lege den API-Key **vor dem Serverstart** in einer Datei `claude_api_key.txt` im Projektverzeichnis ab:

```bash
echo "sk-ant-..." > claude_api_key.txt
```

> `claude_api_key.txt` wird nicht eingecheckt (`.gitignore`).

**Fehlt der API-Key:** JuraX startet dennoch. Dokumente in `bak/` werden mit dem heutigen Datum als Fallback eingeordnet und eine Warnung erscheint im Serverlog. Die Ablage muss dann manuell korrigiert werden.

**Die `bak/`-Kopien bleiben immer erhalten** — kein Dokument wird gelöscht. Die Migration ist jederzeit rückgängig zu machen, indem der Inhalt von `bak/` wiederhergestellt wird.

---

## Anwendung starten

```bash
# 1. root.txt und claude_api_key.txt anlegen (siehe oben)

# 2. WildFly starten — Migration läuft automatisch beim Hochfahren
$WILDFLY_HOME/bin/standalone.sh

# 3. Projekt bauen
mvn clean package -DskipTests

# 4. WAR deployen
cp target/jurax.war $WILDFLY_HOME/standalone/deployments/

# 5. Aufrufen
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
