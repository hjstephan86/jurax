package com.jurax.rest;

import com.jurax.entity.Verfahren;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerfahrenResourceMockTest {

    @Mock EntityManager em;
    @Mock Query         query;
    @InjectMocks VerfahrenResource resource;

    /** Temporäres Verzeichnis als Wurzelverzeichnis für PDF-Tests */
    private static Path tempRoot;
    /** Ursprünglicher Inhalt von root.txt — wird nach den Tests wiederhergestellt */
    private static String originalRootTxt;

    @BeforeAll
    static void createRootTxt() throws Exception {
        tempRoot = Files.createTempDirectory("jurax-test-root");
        Path rootTxt = Paths.get(System.getProperty("user.dir"), "root.txt");
        // Vorhandenen Inhalt sichern, damit er nach den Tests wiederhergestellt werden kann
        if (Files.exists(rootTxt)) {
            originalRootTxt = Files.readString(rootTxt, java.nio.charset.StandardCharsets.UTF_8);
        }
        Files.writeString(rootTxt, tempRoot.toAbsolutePath().toString());
        // rootDir-Cache zurücksetzen, damit der neue Wert gelesen wird
        Field f = VerfahrenResource.class.getDeclaredField("rootDir");
        f.setAccessible(true);
        f.set(null, null);
    }

    @AfterAll
    static void cleanupRootTxt() throws Exception {
        Path rootTxt = Paths.get(System.getProperty("user.dir"), "root.txt");
        // Ursprünglichen Inhalt wiederherstellen oder Datei löschen wenn sie vorher nicht existierte
        if (originalRootTxt != null) {
            Files.writeString(rootTxt, originalRootTxt, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(rootTxt);
        }
        // Cache zurücksetzen
        Field f = VerfahrenResource.class.getDeclaredField("rootDir");
        f.setAccessible(true);
        f.set(null, null);
    }

    @BeforeEach
    void inject() throws Exception {
        Field f = VerfahrenResource.class.getDeclaredField("em");
        f.setAccessible(true);
        f.set(resource, em);
    }

    private Verfahren sample(Long id) {
        Verfahren v = new Verfahren();
        v.setId(id);
        v.setAktenzeichen("14 C 123/24");
        v.setBezeichnung("Mietstreit");
        v.setGericht("Amtsgericht Bielefeld");
        v.setStatus("offen");
        v.setDatumEingang(LocalDate.of(2024, 3, 15));
        v.setDateiPfad("2024/03/15/14-C-123-24.pdf");
        v.setDateiName("14-C-123-24.pdf");
        v.setDateiJahr((short)2024);
        v.setDateiMonat((short)3);
        v.setDateiTag((short)15);
        return v;
    }

    // ── random ────────────────────────────────────────────

    @Test void getRandom_returns200() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(sample(1L)));
        assertEquals(200, resource.getRandom(30).getStatus());
    }

    @Test void getRandom_emptyList_returns200() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        Response r = resource.getRandom(30);
        assertEquals(200, r.getStatus());
        assertTrue(((List<?>)r.getEntity()).isEmpty());
    }

    // ── searchAz ─────────────────────────────────────────

    @Test void searchAz_withTerm_callsDB() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(sample(1L)));
        Response r = resource.searchAz("14 C");
        assertEquals(200, r.getStatus());
        verify(query).setParameter("az", "14 C");
    }

    @Test void searchAz_blank_delegatesToRandom() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertEquals(200, resource.searchAz("").getStatus());
    }

    @Test void searchAz_null_delegatesToRandom() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertEquals(200, resource.searchAz(null).getStatus());
    }

    @Test void searchAz_trimsBlanks() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(eq("az"), eq("14C"))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        resource.searchAz("  14C  ");
        verify(query).setParameter("az", "14C");
    }

    @Test void searchAz_noResults_returns200Empty() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        Response r = resource.searchAz("XXXUNBEKANNT");
        assertEquals(200, r.getStatus());
        assertTrue(((List<?>)r.getEntity()).isEmpty());
    }

    // ── searchDatum ───────────────────────────────────────

    @Test void searchDatum_allParams_callsDB() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(sample(1L)));
        Response r = resource.searchDatum((short)2024, (short)3, (short)15);
        assertEquals(200, r.getStatus());
    }

    @Test void searchDatum_onlyJahr_callsDB() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertEquals(200, resource.searchDatum((short)2024, null, null).getStatus());
    }

    @Test void searchDatum_allNull_returnsAll() {
        when(em.createNativeQuery(anyString(), eq(Verfahren.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertEquals(200, resource.searchDatum(null, null, null).getStatus());
    }

    // ── getById ───────────────────────────────────────────

    @Test void getById_found_returns200() {
        when(em.find(Verfahren.class, 1L)).thenReturn(sample(1L));
        assertEquals(200, resource.getById(1L).getStatus());
    }

    @Test void getById_notFound_returns404() {
        when(em.find(Verfahren.class, 99L)).thenReturn(null);
        assertEquals(404, resource.getById(99L).getStatus());
    }

    // ── getPdf ────────────────────────────────────────────

    @Test void getPdf_notFound_returns404() {
        when(em.find(Verfahren.class, 99L)).thenReturn(null);
        assertEquals(404, resource.getPdf(99L).getStatus());
    }

    @Test void getPdf_noPath_returns404() {
        Verfahren v = new Verfahren(); v.setId(1L);
        when(em.find(Verfahren.class, 1L)).thenReturn(v);
        assertEquals(404, resource.getPdf(1L).getStatus());
    }

    @Test void getPdf_pathNotOnDisk_returns404() {
        Verfahren v = sample(1L);
        v.setDateiPfad("9999/99/99/nichtvorhanden.pdf");
        when(em.find(Verfahren.class, 1L)).thenReturn(v);
        assertEquals(404, resource.getPdf(1L).getStatus());
    }

    // ── create ────────────────────────────────────────────

    @Test void create_returns201() {
        Verfahren v = sample(null);
        doNothing().when(em).persist(v);
        doNothing().when(em).flush();
        assertEquals(201, resource.create(v).getStatus());
        verify(em).persist(v);
    }

    @Test void create_minimal_returns201() {
        Verfahren v = new Verfahren();
        v.setAktenzeichen("1 BvR 1/22");
        doNothing().when(em).persist(v);
        doNothing().when(em).flush();
        assertEquals(201, resource.create(v).getStatus());
    }

    // ── update ────────────────────────────────────────────

    @Test void update_found_returns200AndUpdatesFields() {
        Verfahren existing = sample(1L);
        when(em.find(Verfahren.class, 1L)).thenReturn(existing);
        when(em.merge(existing)).thenReturn(existing);

        Verfahren upd = new Verfahren();
        upd.setAktenzeichen("6 U 99/25");
        upd.setBezeichnung("Neue Bezeichnung");
        upd.setGericht("OLG Hamm");
        upd.setStatus("geschlossen");
        upd.setDatumEingang(LocalDate.of(2025, 1, 1));
        upd.setDatumUrteil(LocalDate.of(2025, 6, 1));
        upd.setNotizen("Aktualisiert");

        Response r = resource.update(1L, upd);
        assertEquals(200, r.getStatus());
        assertEquals("6 U 99/25",      existing.getAktenzeichen());
        assertEquals("geschlossen",    existing.getStatus());
        assertEquals("OLG Hamm",       existing.getGericht());
        assertEquals("Neue Bezeichnung", existing.getBezeichnung());
        verify(em).merge(existing);
    }

    @Test void update_notFound_returns404() {
        when(em.find(Verfahren.class, 99L)).thenReturn(null);
        assertEquals(404, resource.update(99L, new Verfahren()).getStatus());
        verify(em, never()).merge(any());
    }

    @Test void update_nullOptionals_setsNull() {
        Verfahren ex = sample(1L);
        when(em.find(Verfahren.class, 1L)).thenReturn(ex);
        when(em.merge(ex)).thenReturn(ex);
        Verfahren upd = new Verfahren();
        upd.setAktenzeichen("X");
        resource.update(1L, upd);
        assertNull(ex.getNotizen());
        assertNull(ex.getDatumUrteil());
    }

    // ── delete ────────────────────────────────────────────

    @Test void delete_found_returns204() {
        Verfahren v = sample(1L);
        when(em.find(Verfahren.class, 1L)).thenReturn(v);
        doNothing().when(em).remove(v);
        assertEquals(204, resource.delete(1L).getStatus());
        verify(em).remove(v);
    }

    @Test void delete_notFound_returns404() {
        when(em.find(Verfahren.class, 99L)).thenReturn(null);
        assertEquals(404, resource.delete(99L).getStatus());
        verify(em, never()).remove(any());
    }

    // ── upload (Multipart) ────────────────────────────────

    @Test void upload_missingAz_returns400() {
        Response r = resource.upload(null, null, null, "offen", null, null, null, null, null);
        assertEquals(400, r.getStatus());
    }

    @Test void upload_blankAz_returns400() {
        Response r = resource.upload("  ", null, null, "offen", null, null, null, null, null);
        assertEquals(400, r.getStatus());
    }

    @Test void upload_validAz_noFile_persistsMetadata() throws Exception {
        doNothing().when(em).persist(any(Verfahren.class));
        doNothing().when(em).flush();
        // Kein InputStream → Datei wird nicht geschrieben, aber Eintrag angelegt
        Response r = resource.upload("14 C 1/24", "Test", "AG", "offen",
                                     "2024-03-15", null, "Notiz", "test.pdf", null);
        assertEquals(201, r.getStatus());
    }
}