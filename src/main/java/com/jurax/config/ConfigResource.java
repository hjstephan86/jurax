package com.jurax.config;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * REST-Endpunkt zur Konfiguration des Wurzelverzeichnisses.
 *
 * GET  /api/config/rootdir  — Gibt das aktuell konfigurierte Wurzelverzeichnis zurück.
 *                             Ist root.txt nicht vorhanden oder leer, wird "" zurückgegeben.
 * POST /api/config/rootdir  — Schreibt das Wurzelverzeichnis in root.txt und
 *                             setzt den gecachten Wert in VerfahrenResource zurück.
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ConfigResource {

    private static final java.nio.file.Path ROOT_TXT =
        Paths.get(System.getProperty("user.dir"), "root.txt");

    @GET
    @Path("/rootdir")
    public Response getRootDir() {
        try {
            if (!Files.exists(ROOT_TXT)) {
                return Response.ok("{\"rootdir\":\"\",\"configured\":false}").build();
            }
            String value = Files.readString(ROOT_TXT, StandardCharsets.UTF_8).strip();
            boolean configured = !value.isEmpty();
            String json = String.format(
                "{\"rootdir\":\"%s\",\"configured\":%b}",
                value.replace("\\", "\\\\").replace("\"", "\\\""),
                configured);
            return Response.ok(json).build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/rootdir")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setRootDir(RootDirRequest req) {
        if (req == null || req.rootdir == null || req.rootdir.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"Wurzelverzeichnis darf nicht leer sein.\"}").build();
        }

        String path = req.rootdir.strip();

        // Verzeichnis anlegen falls nicht vorhanden
        try {
            java.nio.file.Path dir = Paths.get(path);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"Verzeichnis konnte nicht angelegt werden: " +
                    e.getMessage() + "\"}")
                .build();
        }

        // In root.txt schreiben
        try {
            Files.writeString(ROOT_TXT, path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Response.serverError()
                .entity("{\"error\":\"root.txt konnte nicht geschrieben werden: " +
                    e.getMessage() + "\"}")
                .build();
        }

        // Gecachten Wert in VerfahrenResource zurücksetzen,
        // damit beim nächsten Zugriff der neue Pfad geladen wird
        try {
            java.lang.reflect.Field f =
                com.jurax.rest.VerfahrenResource.class.getDeclaredField("rootDir");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception ignored) {}

        return Response.ok("{\"rootdir\":\"" +
            path.replace("\\", "\\\\").replace("\"", "\\\"") +
            "\",\"configured\":true}").build();
    }

    public static class RootDirRequest {
        public String rootdir;
    }
}
