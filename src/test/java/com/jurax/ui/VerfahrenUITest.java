package com.jurax.ui;

import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Playwright E2E-Tests für index.html
 * Deckt: Seitenaufbau, AZ-Suche, Datumssuche, Modal, PDF-Viewer, Delete
 */
class VerfahrenUITest extends BaseUITest {

    // ── Seitenaufbau ────────────────────────────────────

    @Test void pageTitle_correct() {
        assertTrue(page.title().contains("JuraX"));
    }

    @Test void header_visible() {
        assertTrue(page.locator("header h1").textContent().contains("JuraX"));
    }

    @Test void searchInput_emptyOnLoad() {
        assertEquals("", page.locator("#azInput").inputValue());
    }

    @Test void uploadButton_visible() {
        assertTrue(page.locator("header button").isVisible());
    }

    @Test void randomVerfahren_displayedOnLoad() {
        assertTrue(rowCount() >= 1);
    }

    @Test void resultInfo_showsRandomMessage() {
        assertTrue(resultInfo().contains("zufällige"));
    }

    @Test void tableHeaders_present() {
        assertTrue(page.locator("thead th").count() >= 6);
    }

    @Test void statusBadge_rendered() {
        assertTrue(page.locator(".badge").count() >= 1);
    }

    @Test void actionButtons_present() {
        assertTrue(page.locator("button.btn-warning").count() >= 1);
        assertTrue(page.locator("button.btn-danger").count() >= 1);
    }

    @Test void aktenzeichen_visibleInRow() {
        String t = page.locator("#visibleRows tbody tr").first().textContent();
        assertTrue(t.contains("14 C 123") || t.contains("6 U 45"),
            "Zeile: " + t);
    }

    // ── Aktenzeichen-Suche ───────────────────────────────

    @Test void azSearch_triggersApiAfterDebounce() {
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren/search"),
            () -> typeAz("14 C"));
        assertEquals(200, r.status());
        assertTrue(r.url().contains("az="));
    }

    @Test void azSearch_rendersResult() {
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/search"),
            () -> typeAz("14 C"));
        assertTrue(rowCount() >= 1);
        assertTrue(page.locator("#visibleRows tbody tr").first()
            .textContent().contains("14 C 123"));
    }

    @Test void azSearch_noResults_zeroRows() {
        stubFor(get(urlPathMatching("/api/verfahren/search.*"))
            .willReturn(okJson(EMPTY)));
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/search"),
            () -> typeAz("XYZUNBEKANNT"));
        assertEquals(0, rowCount());
    }

    @Test void azSearch_clearInput_reloadsRandom() {
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/search"),
            () -> typeAz("14 C"));
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/random"),
            () -> page.locator("#azInput").fill(""));
        assertTrue(resultInfo().contains("zufällige"));
    }

    @Test void azSearch_bindestriche_encodeCorrectly() {
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/search"),
            () -> typeAz("14-C-123"));
        verify(getRequestedFor(urlPathMatching("/api/verfahren/search.*"))
            .withQueryParam("az", matching(".*14.*")));
    }

    @Test void debouncing_noCallBeforeTimeout() throws InterruptedException {
        page.locator("#azInput").pressSequentially("1",
            new com.microsoft.playwright.Locator.PressSequentiallyOptions().setDelay(10));
        Thread.sleep(100);
        verify(0, getRequestedFor(urlPathMatching("/api/verfahren/search.*")));
    }

    // ── Datumssuche ──────────────────────────────────────

    @Test void datumSearch_jahrOnly_callsApi() {
        page.locator("#jahrInput").fill("2024");
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren/datum"),
            () -> page.locator(".toolbar button").first().click());
        assertEquals(200, r.status());
        assertTrue(r.url().contains("jahr=2024"));
    }

    @Test void datumSearch_vollstaendig_callsApi() {
        page.locator("#jahrInput").fill("2024");
        page.locator("#monatInput").fill("3");
        page.locator("#tagInput").fill("15");
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren/datum"),
            () -> page.locator(".toolbar button").first().click());
        assertTrue(r.url().contains("jahr=2024"));
        assertTrue(r.url().contains("monat=3"));
        assertTrue(r.url().contains("tag=15"));
    }

    @Test void reset_button_reloadsRandom() {
        page.locator("#jahrInput").fill("2024");
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren/random"),
            () -> page.locator("button.btn-outline").click());
        assertEquals("", page.locator("#jahrInput").inputValue());
    }

    // ── Modal ─────────────────────────────────────────────

    @Test void uploadButton_opensModal() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        assertTrue(page.locator(".modal-overlay").getAttribute("class").contains("open"));
    }

    @Test void modalTitle_neuVerfahren() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        assertTrue(page.locator("#modalTitle").textContent().contains("hochladen"));
    }

    @Test void abbrechenButton_closesModal() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        page.locator(".btn-outline").click();
        assertFalse(page.locator(".modal-overlay").getAttribute("class").contains("open"));
    }

    @Test void saveWithoutAz_showsToast() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        page.locator(".modal .btn").last().click();
        page.waitForTimeout(200);
        assertTrue(page.locator(".toast").isVisible());
    }

    @Test void saveWithAz_noFile_sendsPost() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        page.locator("#fAz").fill("14 C 99/25");
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren") && res.request().method().equals("POST"),
            () -> page.locator(".modal .btn").last().click());
        assertEquals(201, r.status());
    }

    @Test void saveWithAz_closesModal() {
        page.locator("header button").click();
        page.waitForSelector(".modal-overlay.open");
        page.locator("#fAz").fill("14 C 99/25");
        page.waitForResponse(
            res -> res.url().contains("/api/verfahren") && res.request().method().equals("POST"),
            () -> page.locator(".modal .btn").last().click());
        page.waitForTimeout(300);
        assertFalse(page.locator(".modal-overlay").getAttribute("class").contains("open"));
    }

    @Test void editButton_opensModalWithData() {
        page.waitForResponse(
            res -> res.url().matches(".*\\/api\\/verfahren\\/\\d+$"),
            () -> page.locator("button.btn-warning").first().click());
        page.waitForSelector(".modal-overlay.open");
        assertEquals("Verfahren bearbeiten", page.locator("#modalTitle").textContent());
        assertFalse(page.locator("#fAz").inputValue().isEmpty());
    }

    @Test void editSave_sendsPut() {
        page.waitForResponse(
            res -> res.url().matches(".*\\/api\\/verfahren\\/\\d+$") && res.request().method().equals("GET"),
            () -> page.locator("button.btn-warning").first().click());
        page.waitForSelector(".modal-overlay.open");
        page.locator("#fBez").fill("Aktualisiert");
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren/") && res.request().method().equals("PUT"),
            () -> page.locator(".modal .btn").last().click());
        assertEquals(200, r.status());
    }

    // ── Delete ────────────────────────────────────────────

    @Test void deleteButton_showsConfirmDialog() {
        boolean[] shown = {false};
        page.onDialog(d -> { shown[0] = true; d.dismiss(); });
        page.locator("button.btn-danger").first().click();
        page.waitForTimeout(300);
        assertTrue(shown[0]);
    }

    @Test void deleteCancel_noDeleteRequest() {
        page.onDialog(d -> d.dismiss());
        page.locator("button.btn-danger").first().click();
        page.waitForTimeout(400);
        verify(0, deleteRequestedFor(urlPathMatching("/api/verfahren/.*")));
    }

    @Test void deleteConfirm_sendsDelete() {
        page.onDialog(d -> d.accept());
        Response r = page.waitForResponse(
            res -> res.url().contains("/api/verfahren/") && res.request().method().equals("DELETE"),
            () -> page.locator("button.btn-danger").first().click());
        assertEquals(204, r.status());
    }

    // ── PDF-Button ────────────────────────────────────────

    @Test void pdfButton_visible_forVerfahrenWithFile() {
        assertTrue(page.locator("button.btn-pdf").count() >= 1);
    }
}