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
    ▼
PostgreSQL 18.4
    │  Trigram-GIN-Index (pg_trgm) für AZ-Suche
    │  B-Tree-Index auf Jahr/Monat/Tag
    │  TABLESAMPLE BERNOULLI (Zufallsanzeige)
    ▼
Dateisystem
    │  ROOT_DIR/{jahr}/{monat}/{tag}/{dateiname}.pdf
    ▼
PDF-Ablage nach Datum
```

**Designprinzip:** Die initiale Ansicht zeigt ~30 zufällige, **unsortierte** Verfahren. Erst beim Tippen im Suchfeld oder Auslösen des Datumsfilters entsteht Ordnung. Max. 500 Treffer pro Suche.

**Aktenzeichen-Fuzzy-Suche:** `14C123`, `14-C-123`, `14 C 123/24` und `14c` treffen alle dasselbe Verfahren — Bindestriche, Leerzeichen und Groß-/Kleinschreibung werden normalisiert.

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Java JDK | 26 |
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

Beim ersten Zugriff auf die API liest die Anwendung diesen Pfad automatisch ein. Die PDF-Dateien werden dann abgelegt als:

```
/pfad/zu/jurax-docs/
└── 2024/
    └── 03/
        └── 15/
            └── 14-C-123-24.pdf
```

> **Hinweis:** `root.txt` enthält einen absoluten Pfad und sollte nicht in das Git-Repository eingecheckt werden. Trage sie daher in `.gitignore` ein.

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
# Alle Tests + JaCoCo-Report
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

Konfiguriertes Minimum: **87 %