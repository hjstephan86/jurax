package com.jurax.startup;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Wird beim WildFly-Start automatisch ausgeführt.
 *
 * Prüft, ob das Wurzelverzeichnis (aus root.txt) bereits die erwartete
 * Ordnerstruktur {root}/{jahr}/{monat}/{tag}/ enthält.
 *
 * Falls nicht:
 *   1. Alle PDF-Dokumente werden in {root}/bak/ gesichert.
 *   2. Für jedes Dokument in bak/ befragt das System die Claude-API,
 *      um Jahr, Monat und Tag aus Dateiname und Metadaten zu ermitteln.
 *   3. Die Dokumente werden in {root}/{jahr}/{monat}/{tag}/ verschoben.
 *
 * Der KI API-Key wird aus der Datei ai_api_key.txt im Projektverzeichnis gelesen.
 */
@Singleton
@Startup
public class StartupMigration {

    private static final Logger LOG = Logger.getLogger(StartupMigration.class.getName());

    // Pfad zur root.txt im Projektverzeichnis
    private static final java.nio.file.Path ROOT_TXT =
        Paths.get(System.getProperty("user.dir"), "root.txt");

    // Pfad zur ai_api_key.txt im Projektverzeichnis
    private static final java.nio.file.Path API_KEY_TXT =
        Paths.get(System.getProperty("user.dir"), "ai_api_key.txt");

    @PostConstruct
    public void init() {
        try {
            String rootDir = readFile(ROOT_TXT, "root.txt");
            java.nio.file.Path root = Paths.get(rootDir);

            if (!Files.exists(root)) {
                Files.createDirectories(root);
                LOG.info("[JuraX] Wurzelverzeichnis angelegt: " + root);
                return;
            }

            if (hasCorrectStructure(root)) {
                LOG.info("[JuraX] Ordnerstruktur korrekt — keine Migration notwendig.");
                return;
            }

            LOG.warning("[JuraX] Unbekannte Ordnerstruktur erkannt — starte Migration.");
            List<java.nio.file.Path> pdfs = collectPdfs(root);

            if (pdfs.isEmpty()) {
                LOG.info("[JuraX] Keine PDF-Dokumente gefunden — nichts zu migrieren.");
                return;
            }

            // 1. Sicherung in bak/
            java.nio.file.Path bak = root.resolve("bak");
            Files.createDirectories(bak);
            for (java.nio.file.Path pdf : pdfs) {
                java.nio.file.Path dest = bak.resolve(pdf.getFileName());
                // Bei gleichnamigen Dateien: Präfix mit Index
                if (Files.exists(dest)) {
                    String name = pdf.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String base = dot >= 0 ? name.substring(0, dot) : name;
                    String ext  = dot >= 0 ? name.substring(dot)    : "";
                    int i = 1;
                    while (Files.exists(dest)) {
                        dest = bak.resolve(base + "_" + i + ext);
                        i++;
                    }
                }
                Files.move(pdf, dest, StandardCopyOption.ATOMIC_MOVE);
                LOG.info("[JuraX] Gesichert: " + pdf.getFileName() + " → bak/");
            }

            // 2. KI-gestützte Einsortierung
            String apiKey = readApiKey();
            List<java.nio.file.Path> bakPdfs = collectPdfs(bak);

            for (java.nio.file.Path pdf : bakPdfs) {
                try {
                    DateClassification date = classifyWithAI(pdf, apiKey);
                    java.nio.file.Path targetDir = root.resolve(
                        String.format("%d/%02d/%02d", date.year, date.month, date.day));
                    Files.createDirectories(targetDir);
                    java.nio.file.Path target = targetDir.resolve(pdf.getFileName());
                    // Eindeutigen Namen sicherstellen
                    if (Files.exists(target)) {
                        String name = pdf.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        String base = dot >= 0 ? name.substring(0, dot) : name;
                        String ext  = dot >= 0 ? name.substring(dot)    : "";
                        int i = 1;
                        while (Files.exists(target)) {
                            target = targetDir.resolve(base + "_" + i + ext);
                            i++;
                        }
                    }
                    Files.copy(pdf, target, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info(String.format("[JuraX] Eingeordnet: %s → %d/%02d/%02d/",
                        pdf.getFileName(), date.year, date.month, date.day));
                } catch (Exception e) {
                    LOG.warning("[JuraX] Konnte " + pdf.getFileName() +
                        " nicht einordnen: " + e.getMessage() +
                        " — Datei verbleibt in bak/");
                }
            }

            LOG.info("[JuraX] Migration abgeschlossen.");

        } catch (Exception e) {
            LOG.severe("[JuraX] Fehler beim Systemstart: " + e.getMessage());
        }
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────────

    /**
     * Prüft, ob das Wurzelverzeichnis bereits die JuraX-Struktur enthält:
     * mindestens ein Unterverzeichnis der Form {jahr}/{monat}/{tag}/.
     * Verzeichnisse namens "bak" werden ignoriert.
     */
    private boolean hasCorrectStructure(java.nio.file.Path root) throws IOException {
        try (var stream = Files.list(root)) {
            List<java.nio.file.Path> children = stream
                .filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().equals("bak"))
                .collect(Collectors.toList());

            for (java.nio.file.Path child : children) {
                String name = child.getFileName().toString();
                // Erwartet: vierstellige Jahreszahl
                if (!name.matches("\\d{4}")) return false;
                // Prüfe ob Unterstruktur {monat}/{tag} vorhanden
                try (var months = Files.list(child)) {
                    boolean hasMonth = months.filter(Files::isDirectory).anyMatch(m -> {
                        try (var days = Files.list(m)) {
                            return days.filter(Files::isDirectory).findAny().isPresent();
                        } catch (IOException ex) { return false; }
                    });
                    if (hasMonth) return true;
                }
            }
        }
        return false;
    }

    /**
     * Sammelt rekursiv alle PDF-Dateien unterhalb von root,
     * außer solchen in bak/ selbst.
     */
    private List<java.nio.file.Path> collectPdfs(java.nio.file.Path root) throws IOException {
        List<java.nio.file.Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) {
                if (file.toString().toLowerCase().endsWith(".pdf")) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * Liest eine einzeilige Textdatei und gibt den bereinigten Inhalt zurück.
     */
    private String readFile(java.nio.file.Path path, String label) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException(label + " nicht gefunden: " + path.toAbsolutePath());
        }
        String content = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (content.isEmpty()) {
            throw new IOException(label + " ist leer.");
        }
        return content;
    }

    /**
     * Liest den KI API-Key aus ai_api_key.txt.
     * Falls die Datei fehlt, wird eine leere Zeichenkette zurückgegeben
     * (Migration schlägt dann pro Datei mit einer Warnung fehl).
     */
    private String readApiKey() {
        try {
            return readFile(API_KEY_TXT, "ai_api_key.txt");
        } catch (IOException e) {
            LOG.warning("[JuraX] ai_api_key.txt nicht gefunden — KI-Einsortierung nicht möglich.");
            return "";
        }
    }

    /**
     * Befragt die Claude-API, um aus dem Dateinamen das Datum (Jahr/Monat/Tag)
     * zu ermitteln, unter dem das Dokument abzulegen ist.
     *
     * Rückgabe: DateClassification mit year, month, day.
     * Falls kein Datum erkannt wird, wird das heutige Datum verwendet.
     */
    private DateClassification classifyWithAI(java.nio.file.Path pdf, String apiKey)
            throws IOException, InterruptedException {

        if (apiKey == null || apiKey.isEmpty()) {
            LOG.warning("[JuraX] Kein API-Key — verwende heutiges Datum für: " + pdf.getFileName());
            LocalDate today = LocalDate.now();
            return new DateClassification(today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        }

        String filename = pdf.getFileName().toString();

        String prompt = "Du bist ein Assistent für ein Rechtsverwaltungssystem namens JuraX. " +
            "Deine Aufgabe ist es, aus dem Dateinamen eines juristischen Dokuments " +
            "das relevante Datum (Jahr, Monat, Tag) zu ermitteln, unter dem es abgelegt werden soll.\n\n" +
            "Dateiname: \"" + filename + "\"\n\n" +
            "Analysiere den Dateinamen sorgfältig. Mögliche Hinweise auf ein Datum sind:\n" +
            "- Jahreszahlen (z.B. 2024, 24, /24)\n" +
            "- Monatsangaben (z.B. 03, März, Mar)\n" +
            "- Tagesangaben\n" +
            "- Aktenzeichen mit Jahreszahl (z.B. 14-C-123-24 → Jahr 2024)\n" +
            "- Datumsmuster wie 2024-03-15 oder 15.03.2024\n\n" +
            "Falls kein Datum erkennbar ist, nutze das aktuelle Jahr " + LocalDate.now().getYear() + ", Monat 1, Tag 1.\n\n" +
            "Antworte ausschließlich im JSON-Format, ohne Präambel oder Erklärung:\n" +
            "{\"year\": 2024, \"month\": 3, \"day\": 15, \"reasoning\": \"kurze Begründung\"}";

        String requestBody = "{"
            + "\"model\": \"claude-sonnet-4-6\","
            + "\"max_tokens\": 200,"
            + "\"messages\": [{\"role\": \"user\", \"content\": "
            + jsonString(prompt)
            + "}]"
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Claude API Fehler: HTTP " + response.statusCode());
        }

        return parseAIResponse(response.body(), filename);
    }

    /**
     * Parst die JSON-Antwort der Claude-API und extrahiert Jahr, Monat, Tag.
     */
    private DateClassification parseAIResponse(String responseBody, String filename) {
        try {
            // Extrahiere den Text-Inhalt aus der API-Antwort
            String text = extractTextFromResponse(responseBody);

            // JSON-Felder parsen (einfach, ohne externe Bibliothek)
            int year  = extractInt(text, "year");
            int month = extractInt(text, "month");
            int day   = extractInt(text, "day");

            // Plausibilitätsprüfung
            if (year < 1900 || year > 2100) year = LocalDate.now().getYear();
            if (month < 1   || month > 12)  month = 1;
            if (day < 1     || day > 31)    day   = 1;

            String reasoning = extractString(text, "reasoning");
            LOG.info(String.format("[JuraX] KI-Klassifizierung für '%s': %d/%02d/%02d — %s",
                filename, year, month, day, reasoning));

            return new DateClassification(year, month, day);

        } catch (Exception e) {
            LOG.warning("[JuraX] KI-Antwort konnte nicht geparst werden für '" +
                filename + "': " + e.getMessage() + " — verwende heutiges Datum");
            LocalDate today = LocalDate.now();
            return new DateClassification(today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        }
    }

    /** Extrahiert den Textinhalt aus einer Claude-API-JSON-Antwort. */
    private String extractTextFromResponse(String json) {
        // "content":[{"type":"text","text":"..."}]
        int textIdx = json.indexOf("\"text\":");
        if (textIdx < 0) throw new RuntimeException("Kein 'text'-Feld in der Antwort");
        int start = json.indexOf('"', textIdx + 7) + 1;
        // Escaped JSON-String auslesen
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else sb.append(c);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extrahiert einen Integer-Wert aus einem einfachen JSON-String. */
    private int extractInt(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) throw new RuntimeException("Key '" + key + "' nicht gefunden");
        int colon = json.indexOf(':', idx + search.length());
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    /** Extrahiert einen String-Wert aus einem einfachen JSON-String. */
    private String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + search.length());
        int start = json.indexOf('"', colon) + 1;
        int end   = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    /** Wandelt einen String in ein JSON-konformes String-Literal um. */
    private String jsonString(String s) {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            + "\"";
    }

    // ── Datenklasse ──────────────────────────────────────────────────────────

    private static class DateClassification {
        final int year, month, day;
        DateClassification(int year, int month, int day) {
            this.year = year; this.month = month; this.day = day;
        }
    }
}
