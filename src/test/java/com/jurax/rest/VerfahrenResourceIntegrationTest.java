package com.jurax.rest;

import com.jurax.entity.Verfahren;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class VerfahrenResourceIntegrationTest {

    private static EntityManagerFactory emf;
    private EntityManager em;
    private VerfahrenResource resource;
    private EntityTransaction tx;

    @BeforeAll static void startEmf() {
        emf = Persistence.createEntityManagerFactory("juraxTestPU");
    }
    @AfterAll  static void stopEmf()  { if (emf != null) emf.close(); }

    @BeforeEach
    void setUp() throws Exception {
        em       = emf.createEntityManager();
        tx       = em.getTransaction();
        resource = new VerfahrenResource();
        Field f  = VerfahrenResource.class.getDeclaredField("em");
        f.setAccessible(true);
        f.set(resource, em);
    }

    @AfterEach
    void tearDown() {
        if (tx.isActive()) tx.rollback();
        tx.begin();
        em.createQuery("DELETE FROM Verfahren").executeUpdate();
        tx.commit();
        em.close();
    }

    private Verfahren persist(String az, String status, int jahr, int monat, int tag) {
        Verfahren v = new Verfahren();
        v.setAktenzeichen(az);
        v.setStatus(status);
        v.setDatumEingang(LocalDate.of(jahr, monat, tag));
        v.setDateiJahr((short)jahr);
        v.setDateiMonat((short)monat);
        v.setDateiTag((short)tag);
        tx.begin(); em.persist(v); tx.commit(); em.clear();
        return v;
    }

    @Test void getById_found() {
        Verfahren v = persist("14 C 1/24", "offen", 2024, 3, 15);
        var r = resource.getById(v.getId());
        assertEquals(200, r.getStatus());
        assertEquals("14 C 1/24", ((Verfahren)r.getEntity()).getAktenzeichen());
    }

    @Test void getById_notFound_returns404() {
        assertEquals(404, resource.getById(Long.MAX_VALUE).getStatus());
    }

    @Test void create_persistsToDb() {
        Verfahren v = new Verfahren();
        v.setAktenzeichen("6 U 5/23");
        v.setStatus("offen");
        tx.begin();
        var r = resource.create(v);
        tx.commit();
        assertEquals(201, r.getStatus());
        assertNotNull(v.getId());
        assertNotNull(em.find(Verfahren.class, v.getId()));
    }

    @Test void create_setsTimestamps() {
        Verfahren v = new Verfahren();
        v.setAktenzeichen("S 1/24");
        v.setStatus("offen");
        tx.begin(); resource.create(v); tx.commit();
        assertNotNull(v.getCreatedAt());
        assertNotNull(v.getUpdatedAt());
    }

    @Test void update_changesStatus() {
        Verfahren v = persist("1 BvR 1/22", "offen", 2022, 7, 19);
        Verfahren upd = new Verfahren();
        upd.setAktenzeichen("1 BvR 1/22");
        upd.setStatus("erledigt");
        tx.begin(); resource.update(v.getId(), upd); tx.commit();
        em.clear();
        assertEquals("erledigt", em.find(Verfahren.class, v.getId()).getStatus());
    }

    @Test void update_notFound_returns404() {
        assertEquals(404, resource.update(Long.MAX_VALUE, new Verfahren()).getStatus());
    }

    @Test void delete_removesFromDb() {
        Verfahren v = persist("Az 7 K 1/24", "offen", 2024, 5, 21);
        Long id = v.getId();
        tx.begin(); resource.delete(id); tx.commit();
        assertNull(em.find(Verfahren.class, id));
    }

    @Test void delete_notFound_returns404() {
        assertEquals(404, resource.delete(Long.MAX_VALUE).getStatus());
    }

    @Test void getPdf_noPath_returns404() {
        Verfahren v = persist("X 1/24", "offen", 2024, 1, 1);
        assertEquals(404, resource.getPdf(v.getId()).getStatus());
    }

    @Test void multipleVerfahren_independentLifecycle() {
        Verfahren v1 = persist("A 1/24", "offen",      2024, 1, 1);
        Verfahren v2 = persist("B 2/24", "geschlossen", 2024, 2, 2);
        tx.begin(); resource.delete(v1.getId()); tx.commit();
        assertNull(em.find(Verfahren.class, v1.getId()));
        assertNotNull(em.find(Verfahren.class, v2.getId()));
    }
}