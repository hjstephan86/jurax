package com.jurax.ui;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public abstract class BaseUITest {

    protected static WireMockServer wm;
    protected static Playwright     playwright;
    protected static Browser        browser;
    protected BrowserContext context;
    protected Page           page;

    // ── Fixtures ─────────────────────────────────────────
    protected static final String V1 = """
        {"id":1,"aktenzeichen":"14 C 123/24","bezeichnung":"Mietstreit",
         "gericht":"Amtsgericht Bielefeld","status":"offen",
         "datumEingang":"2024-03-15","dateiName":"14-C-123-24.pdf",
         "dateiPfad":"2024/03/15/14-C-123-24.pdf",
         "dateiJahr":2024,"dateiMonat":3,"dateiTag":15}""";

    protected static final String V2 = """
        {"id":2,"aktenzeichen":"6 U 45/23","bezeichnung":"Berufung",
         "gericht":"OLG Hamm","status":"geschlossen",
         "datumEingang":"2023-11-02","dateiName":null,"dateiPfad":null,
         "dateiJahr":2023,"dateiMonat":11,"dateiTag":2}""";

    protected static final String LIST_2  = "[" + V1 + "," + V2 + "]";
    protected static final String LIST_1  = "[" + V1 + "]";
    protected static final String EMPTY   = "[]";

    @BeforeAll
    static void startInfra() {
        wm = new WireMockServer(WireMockConfiguration.options().port(8091));
        wm.start();
        configureFor("localhost", 8091);
        playwright = Playwright.create();
        browser    = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void stopInfra() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
        if (wm         != null) wm.stop();
    }

    @BeforeEach
    void openPage() throws Exception {
        wm.resetMappings();

        stubFor(get(urlPathEqualTo("/api/verfahren/random"))
            .willReturn(okJson(LIST_2)));
        stubFor(get(urlPathMatching("/api/verfahren/search.*"))
            .willReturn(okJson(LIST_1)));
        stubFor(get(urlPathMatching("/api/verfahren/datum.*"))
            .willReturn(okJson(LIST_1)));
        stubFor(get(urlPathEqualTo("/api/verfahren/1"))
            .willReturn(okJson(V1)));
        stubFor(get(urlPathEqualTo("/api/verfahren/2"))
            .willReturn(okJson(V2)));
        stubFor(post(urlPathEqualTo("/api/verfahren"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type","application/json").withBody(V1)));
        stubFor(post(urlPathEqualTo("/api/verfahren/upload"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type","application/json").withBody(V1)));
        stubFor(put(urlPathMatching("/api/verfahren/.*"))
            .willReturn(okJson(V1)));
        stubFor(delete(urlPathMatching("/api/verfahren/.*"))
            .willReturn(aResponse().withStatus(204)));
        stubFor(get(urlPathMatching("/api/verfahren/.*/pdf"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type","application/pdf")
                .withBody(new byte[]{37,80,68,70})));

        String html = Files.readString(
            Paths.get("src/main/webapp/index.html"));
        stubFor(get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type","text/html; charset=utf-8")
                .withBody(html)));

        context = browser.newContext(new Browser.NewContextOptions()
            .setBaseURL("http://localhost:8091"));
        page = context.newPage();
        page.navigate("http://localhost:8091/");
        page.waitForSelector("td", new Page.WaitForSelectorOptions().setTimeout(5000));
    }

    @AfterEach
    void closePage() {
        if (page    != null) page.close();
        if (context != null) context.close();
    }

    protected void typeAz(String text) {
        page.locator("#azInput").fill(text);
        page.locator("#azInput").press("a");
        page.waitForTimeout(450);
    }

    protected String resultInfo() {
        return page.locator("#resultInfo").textContent().trim();
    }

    protected int rowCount() {
        return page.locator("#visibleRows tbody tr").count();
    }
}