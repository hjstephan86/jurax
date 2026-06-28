package com.jurax.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "verfahren")
public class Verfahren {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aktenzeichen", nullable = false, length = 100)
    private String aktenzeichen;

    @Column(name = "bezeichnung", columnDefinition = "TEXT")
    private String bezeichnung;

    @Column(name = "gericht", length = 200)
    private String gericht;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "offen";

    @Column(name = "datum_eingang")
    private LocalDate datumEingang;

    @Column(name = "datum_urteil")
    private LocalDate datumUrteil;

    @Column(name = "notizen", columnDefinition = "TEXT")
    private String notizen;

    @Column(name = "datei_pfad", columnDefinition = "TEXT")
    private String dateiPfad;

    @Column(name = "datei_name", length = 255)
    private String dateiName;

    @Column(name = "datei_groesse")
    private Long dateiGroesse;

    @Column(name = "datei_jahr")
    private Short dateiJahr;

    @Column(name = "datei_monat")
    private Short dateiMonat;

    @Column(name = "datei_tag")
    private Short dateiTag;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getter & Setter ──────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }

    public String getAktenzeichen() { return aktenzeichen; }
    public void setAktenzeichen(String v) { this.aktenzeichen = v; }

    public String getBezeichnung() { return bezeichnung; }
    public void setBezeichnung(String v) { this.bezeichnung = v; }

    public String getGericht() { return gericht; }
    public void setGericht(String v) { this.gericht = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public LocalDate getDatumEingang() { return datumEingang; }
    public void setDatumEingang(LocalDate v) { this.datumEingang = v; }

    public LocalDate getDatumUrteil() { return datumUrteil; }
    public void setDatumUrteil(LocalDate v) { this.datumUrteil = v; }

    public String getNotizen() { return notizen; }
    public void setNotizen(String v) { this.notizen = v; }

    public String getDateiPfad() { return dateiPfad; }
    public void setDateiPfad(String v) { this.dateiPfad = v; }

    public String getDateiName() { return dateiName; }
    public void setDateiName(String v) { this.dateiName = v; }

    public Long getDateiGroesse() { return dateiGroesse; }
    public void setDateiGroesse(Long v) { this.dateiGroesse = v; }

    public Short getDateiJahr() { return dateiJahr; }
    public void setDateiJahr(Short v) { this.dateiJahr = v; }

    public Short getDateiMonat() { return dateiMonat; }
    public void setDateiMonat(Short v) { this.dateiMonat = v; }

    public Short getDateiTag() { return dateiTag; }
    public void setDateiTag(Short v) { this.dateiTag = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}