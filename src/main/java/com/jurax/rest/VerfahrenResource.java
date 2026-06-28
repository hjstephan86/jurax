package com.jurax.rest;

import com.jurax.entity.Verfahren;
import jakarta.enterprise.context.RequestScoped;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;

@Path("/verfahren")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class VerfahrenResource {

    @PersistenceContext(unitName = "juraxPU")
    private EntityManager em;

    // Wurzelverzeichnis für PDF-Ablage (konfigurierbar per System-Property)
    private static final String ROOT_DIR =
        System.getProperty("jurax.rootdir", System.getProperty("user.home") + "/jurax-docs");

    // ----------------------------------------------------------
    // GET /verfahren/random?limit=30
    // ----------------------------------------------------------
    @GET
    @Path("/random")
    public Response getRandom(@QueryParam("limit") @DefaultValue("30") int limit) {
        List<Verfahren> result = em
            .createNativeQuery("SELECT * FROM random_verfahren(:lim)", Verfahren.class)
            .setParameter("lim", limit)
            .getResultList();
        return Response.ok(result).build();
    }

    // ----------------------------------------------------------
    // GET /verfahren/search?az=14+C+123
    // Aktenzeichen-Suche: fuzzy, bindestrich-/case-agnostisch
    // ----------------------------------------------------------
    @GET
    @Path("/search")
    public Response searchAz(@QueryParam("az") @DefaultValue("") String az) {
        if (az == null || az.isBlank()) return getRandom(30);
        List<Verfahren> result = em
            .createNativeQuery("SELECT * FROM search_verfahren_az(:az)", Verfahren.class)
            .setParameter("az", az.trim())
            .getResultList();
        return Response.ok(result).build();
    }

    // ----------------------------------------------------------
    // GET /verfahren/datum?jahr=2024&monat=3&tag=15
    // Datumsfilter: alle Parameter optional
    // ----------------------------------------------------------
    @GET
    @Path("/datum")
    public Response searchDatum(
            @QueryParam("jahr")  Short jahr,
            @QueryParam("monat") Short monat,
            @QueryParam("tag")   Short tag) {
        List<Verfahren> result = em
            .createNativeQuery(
                "SELECT * FROM search_verfahren_datum(:jahr, :monat, :tag)",
                Verfahren.class)
            .setParameter("jahr",  jahr)
            .setParameter("monat", monat)
            .setParameter("tag",   tag)
            .getResultList();
        return Response.ok(result).build();
    }

    // ----------------------------------------------------------
    // GET /verfahren/{id}
    // ----------------------------------------------------------
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") Long id) {
        Verfahren v = em.find(Verfahren.class, id);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(v).build();
    }

    // ----------------------------------------------------------
    // GET /verfahren/{id}/pdf
    // Liefert die PDF-Datei aus dem Dateisystem
    // ----------------------------------------------------------
    @GET
    @Path("/{id}/pdf")
    @Produces("application/pdf")
    public Response getPdf(@PathParam("id") Long id) {
        Verfahren v = em.find(Verfahren.class, id);
        if (v == null || v.getDateiPfad() == null)
            return Response.status(Response.Status.NOT_FOUND).build();

        Path pdfPath = Paths.get(ROOT_DIR, v.getDateiPfad());
        if (!Files.exists(pdfPath))
            return Response.status(Response.Status.NOT_FOUND)
                .entity("Datei nicht gefunden: " + pdfPath).build();

        try {
            byte[] data = Files.readAllBytes(pdfPath);
            return Response.ok(data)
                .header("Content-Disposition",
                    "inline; filename=\"" + v.getDateiName() + "\"")
                .build();
        } catch (IOException e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    // ----------------------------------------------------------
    // POST /verfahren  (JSON-Metadaten ohne Datei)
    // ----------------------------------------------------------
    @POST
    @Transactional
    public Response create(Verfahren v) {
        em.persist(v);
        em.flush();
        return Response.status(Response.Status.CREATED).entity(v).build();
    }

    // ----------------------------------------------------------
    // POST /verfahren/upload
    // Multipart: PDF hochladen + Metadaten als Form-Parameter
    // Ablage: ROOT_DIR/{jahr}/{monat}/{tag}/{dateiname}
    // ----------------------------------------------------------
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Response upload(
            @FormParam("aktenzeichen") String aktenzeichen,
            @FormParam("bezeichnung")  String bezeichnung,
            @FormParam("gericht")      String gericht,
            @FormParam("status")       @DefaultValue("offen") String status,
            @FormParam("datumEingang") String datumEingangStr,
            @FormParam("notizen")      String notizen,
            @FormParam("dateiName")    String dateiName,
            @FormParam("dateiDaten")   InputStream dateiDaten) {

        if (aktenzeichen == null || aktenzeichen.isBlank())
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Aktenzeichen fehlt").build();

        LocalDate heute = LocalDate.now();
        LocalDate eingang = null;
        try {
            if (datumEingangStr != null && !datumEingangStr.isBlank())
                eingang = LocalDate.parse(datumEingangStr);
        } catch (Exception ignored) {}

        LocalDate ablage = eingang != null ? eingang : heute;
        short jahr  = (short) ablage.getYear();
        short monat = (short) ablage.getMonthValue();
        short tag   = (short) ablage.getDayOfMonth();

        // Sanitize Dateiname
        String sichereDateiName = dateiName != null
            ? dateiName.replaceAll("[^a-zA-Z0-9._\\-]", "_")
            : aktenzeichen.replaceAll("[^a-zA-Z0-9]", "-") + ".pdf";

        // Pfad: ROOT_DIR/2024/03/15/dateiname.pdf
        String relativPfad = String.format("%d/%02d/%02d/%s", jahr, monat, tag, sichereDateiName);
        Path zielPfad = Paths.get(ROOT_DIR, relativPfad);

        try {
            Files.createDirectories(zielPfad.getParent());
            long geschrieben = 0;
            if (dateiDaten != null) {
                geschrieben = Files.copy(dateiDaten, zielPfad,
                    StandardCopyOption.REPLACE_EXISTING);
            }

            Verfahren v = new Verfahren();
            v.setAktenzeichen(aktenzeichen);
            v.setBezeichnung(bezeichnung);
            v.setGericht(gericht);
            v.setStatus(status);
            v.setDatumEingang(eingang != null ? eingang : heute);
            v.setNotizen(notizen);
            v.setDateiPfad(relativPfad);
            v.setDateiName(sichereDateiName);
            v.setDateiGroesse(geschrieben);
            v.setDateiJahr(jahr);
            v.setDateiMonat(monat);
            v.setDateiTag(tag);

            em.persist(v);
            em.flush();
            return Response.status(Response.Status.CREATED).entity(v).build();

        } catch (IOException e) {
            return Response.serverError().entity("Fehler beim Speichern: " + e.getMessage()).build();
        }
    }

    // ----------------------------------------------------------
    // PUT /verfahren/{id}
    // ----------------------------------------------------------
    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") Long id, Verfahren upd) {
        Verfahren v = em.find(Verfahren.class, id);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();

        v.setAktenzeichen(upd.getAktenzeichen());
        v.setBezeichnung(upd.getBezeichnung());
        v.setGericht(upd.getGericht());
        v.setStatus(upd.getStatus());
        v.setDatumEingang(upd.getDatumEingang());
        v.setDatumUrteil(upd.getDatumUrteil());
        v.setNotizen(upd.getNotizen());

        em.merge(v);
        return Response.ok(v).build();
    }

    // ----------------------------------------------------------
    // DELETE /verfahren/{id}
    // ----------------------------------------------------------
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Verfahren v = em.find(Verfahren.class, id);
        if (v == null) return Response.status(Response.Status.NOT_FOUND).build();
        em.remove(v);
        return Response.noContent().build();
    }
}