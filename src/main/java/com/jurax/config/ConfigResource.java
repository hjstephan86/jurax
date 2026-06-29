package com.jurax.config;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * REST-Endpunkt zur Erstkonfiguration von JuraX.
 *
 * GET  /api/config/setup    — Prüft ob Wurzelverzeichnis und KI API-Key konfiguriert sind.
 * POST /api/config/setup    — Speichert Wurzelverzeichnis (root.txt) und KI API-Key (ai_api_key.txt).
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ConfigResource {

    private static final java.nio.file.Path ROOT_TXT =
        Paths.get(System.getProperty("user.dir"), "root.txt");

    private static final java.nio.file.Path AI_KEY_TXT =
        Paths.get(System.getProperty("user.dir"), "ai_api_key.txt");

    // ── GET /api/config/setup ─────────────────────────────────────────────

    @GET
    @Path("/setup")
    public Response getSetup() {
        boolean rootConfigured = isConfigured(ROOT_TXT);
        boolean keyConfigured  = isConfigured(AI_KEY_TXT);
        String json = String.format(
            "{\"rootConfigured\":%b,\"keyConfigured\":%b,\"configured\":%b}",
            rootConfigured, keyConfigured, rootConfigured && keyConfigured);
        return Response.ok(json).build();
    }

    // ── POST /api/config/setup ────────────────────────────────────────────

    @POST
    @Path("/setup")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSetup(SetupRequest req) {
        if (req == null) {
            return bad("Keine Konfigurationsdaten empfangen.");
        }

        // Wurzelverzeichnis
        if (req.rootdir == null || req.rootdir.isBlank()) {
            return bad("Wurzelverzeichnis darf nicht leer sein.");
        }
        String rootPath = req.rootdir.strip();
        try {
            java.nio.file.Path dir = Paths.get(rootPath);
            if (!Files.exists(dir)) Files.createDirectories(dir);
        } catch (IOException e) {
            return bad("Verzeichnis konnte nicht angelegt werden: " + e.getMessage());
        }
        try {
            Files.writeString(ROOT_TXT, rootPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return err("root.txt konnte nicht geschrieben werden: " + e.getMessage());
        }

        // KI API-Key (optional — Warnung wenn leer, aber kein Fehler)
        String apiKey = req.aiApiKey != null ? req.aiApiKey.strip() : "";
        if (!apiKey.isEmpty()) {
            try {
                Files.writeString(AI_KEY_TXT, apiKey, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return err("ai_api_key.txt konnte nicht geschrieben werden: " + e.getMessage());
            }
        }

        // Cache in VerfahrenResource und StartupMigration zurücksetzen
        resetCache("com.jurax.rest.VerfahrenResource", "rootDir");
        resetCache("com.jurax.startup.StartupMigration", "rootDir");

        return Response.ok(String.format(
            "{\"configured\":true,\"rootdir\":\"%s\",\"keyConfigured\":%b}",
            escape(rootPath), !apiKey.isEmpty())).build();
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────

    private boolean isConfigured(java.nio.file.Path p) {
        try {
            return Files.exists(p) && !Files.readString(p, StandardCharsets.UTF_8).isBlank();
        } catch (IOException e) { return false; }
    }

    private Response bad(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"" + escape(msg) + "\"}").build();
    }

    private Response err(String msg) {
        return Response.serverError()
            .entity("{\"error\":\"" + escape(msg) + "\"}").build();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void resetCache(String className, String fieldName) {
        try {
            Class<?> cls = Class.forName(className);
            java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception ignored) {}
    }

    // ── Request-Klasse ────────────────────────────────────────────────────

    public static class SetupRequest {
        public String rootdir;
        public String aiApiKey;
    }
}
