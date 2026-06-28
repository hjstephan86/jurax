-- ============================================================
-- JuraX – Rechtliche Verfahrensverwaltung: PostgreSQL Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- IMMUTABLE-Wrapper (erforderlich für Indexausdrücke)
CREATE OR REPLACE FUNCTION immutable_unaccent(text)
RETURNS text AS $$
    SELECT unaccent($1);
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- ------------------------------------------------------------
-- Tabelle: verfahren
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS verfahren (
    id              BIGSERIAL PRIMARY KEY,
    aktenzeichen    VARCHAR(100) NOT NULL,
    bezeichnung     TEXT,
    gericht         VARCHAR(200),
    status          VARCHAR(30) NOT NULL DEFAULT 'offen'
                        CHECK (status IN ('offen','geschlossen','ruhend','erledigt')),
    datum_eingang   DATE,
    datum_urteil    DATE,
    notizen         TEXT,
    -- Dateipfad: relativ zum Wurzelverzeichnis, z.B. 2024/03/15/az-123.pdf
    datei_pfad      TEXT,
    datei_name      VARCHAR(255),
    datei_groesse   BIGINT,
    -- Datum-Hierarchie für schnelle Filterung
    datei_jahr      SMALLINT,
    datei_monat     SMALLINT,
    datei_tag       SMALLINT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ------------------------------------------------------------
-- INDIZES — optimiert für Aktenzeichen-Suche + Datumsfilter
-- ------------------------------------------------------------

-- Trigram-Index auf normalisiertes Aktenzeichen
-- (Bindestriche entfernt, Kleinschreibung → fuzzy AZ-Suche)
CREATE INDEX idx_verfahren_az_trgm
    ON verfahren
    USING GIN (
        lower(regexp_replace(aktenzeichen, '[-\s]', '', 'g')) gin_trgm_ops
    );

-- Trigram auf Originalform (für Präfix-Suche)
CREATE INDEX idx_verfahren_az_orig_trgm
    ON verfahren
    USING GIN (aktenzeichen gin_trgm_ops);

-- B-Tree für exakte Datumsfilterung (Jahr / Monat / Tag)
CREATE INDEX idx_verfahren_jahr   ON verfahren (datei_jahr);
CREATE INDEX idx_verfahren_monat  ON verfahren (datei_monat);
CREATE INDEX idx_verfahren_tag    ON verfahren (datei_tag);
CREATE INDEX idx_verfahren_datum  ON verfahren (datei_jahr, datei_monat, datei_tag);

-- B-Tree für Status + Eingangsdatum
CREATE INDEX idx_verfahren_status  ON verfahren (status);
CREATE INDEX idx_verfahren_eingang ON verfahren (datum_eingang DESC);

-- Volltext auf Bezeichnung + Gericht
CREATE INDEX idx_verfahren_fts
    ON verfahren
    USING GIN (
        to_tsvector('german',
            coalesce(bezeichnung, '') || ' ' || coalesce(gericht, ''))
    );

-- ============================================================
-- FUNKTION: Zufällige Stichprobe
-- ============================================================
CREATE OR REPLACE FUNCTION random_verfahren(target INT DEFAULT 30)
RETURNS SETOF verfahren AS $$
DECLARE
    total BIGINT;
    pct   DOUBLE PRECISION;
BEGIN
    SELECT reltuples::BIGINT INTO total
    FROM pg_class WHERE relname = 'verfahren';
    IF total <= 0 THEN total := 1; END IF;
    pct := LEAST((target::DOUBLE PRECISION / total) * 100 * 3, 100);
    RETURN QUERY
        SELECT * FROM verfahren TABLESAMPLE BERNOULLI(pct) LIMIT target;
END;
$$ LANGUAGE plpgsql STABLE;

-- ============================================================
-- FUNKTION: Aktenzeichen-Suche (fuzzy, bindestrich-/case-agnostisch)
-- UNION aus einzeln indexierbaren Teilabfragen
-- ============================================================
CREATE OR REPLACE FUNCTION search_verfahren_az(suchbegriff TEXT)
RETURNS SETOF verfahren LANGUAGE sql STABLE AS $$
    -- Zweig 1: normalisiert (Bindestriche entfernt, lowercase)
    SELECT v.* FROM verfahren v
    WHERE lower(regexp_replace(v.aktenzeichen, '[-\s]', '', 'g'))
          % lower(regexp_replace(suchbegriff, '[-\s]', '', 'g'))
    UNION
    -- Zweig 2: Originalform Trigram
    SELECT v.* FROM verfahren v
    WHERE v.aktenzeichen ILIKE '%' || suchbegriff || '%'
    UNION
    -- Zweig 3: normalisierter LIKE
    SELECT v.* FROM verfahren v
    WHERE lower(regexp_replace(v.aktenzeichen, '[-\s]', '', 'g'))
          LIKE '%' || lower(regexp_replace(suchbegriff, '[-\s]', '', 'g')) || '%'
    ORDER BY aktenzeichen
    LIMIT 500;
$$;

-- ============================================================
-- FUNKTION: Datumssuche (Jahr / Monat / Tag)
-- ============================================================
CREATE OR REPLACE FUNCTION search_verfahren_datum(
    p_jahr  SMALLINT DEFAULT NULL,
    p_monat SMALLINT DEFAULT NULL,
    p_tag   SMALLINT DEFAULT NULL
)
RETURNS SETOF verfahren LANGUAGE sql STABLE AS $$
    SELECT * FROM verfahren
    WHERE (p_jahr  IS NULL OR datei_jahr  = p_jahr)
      AND (p_monat IS NULL OR datei_monat = p_monat)
      AND (p_tag   IS NULL OR datei_tag   = p_tag)
    ORDER BY datei_jahr DESC, datei_monat DESC, datei_tag DESC, aktenzeichen
    LIMIT 500;
$$;

-- ============================================================
-- Beispieldaten
-- ============================================================
INSERT INTO verfahren (aktenzeichen, bezeichnung, gericht, status,
                       datum_eingang, datei_pfad, datei_name,
                       datei_jahr, datei_monat, datei_tag)
VALUES
('14 C 123/24',  'Mietstreit Hauptstraße 5',     'Amtsgericht Bielefeld',   'offen',      '2024-03-15', '2024/03/15/14-C-123-24.pdf',  '14-C-123-24.pdf',  2024, 3, 15),
('6 U 45/23',    'Berufung Kaufvertrag',          'OLG Hamm',                'geschlossen','2023-11-02', '2023/11/02/6-U-45-23.pdf',    '6-U-45-23.pdf',    2023, 11, 2),
('S 12/2024',    'Sozialleistungsklage',          'Sozialgericht Detmold',   'offen',      '2024-01-08', '2024/01/08/S-12-2024.pdf',    'S-12-2024.pdf',    2024, 1, 8),
('1 BvR 99/22',  'Verfassungsbeschwerde',         'BVerfG Karlsruhe',        'ruhend',     '2022-07-19', '2022/07/19/1-BvR-99-22.pdf',  '1-BvR-99-22.pdf',  2022, 7, 19),
('Az 7 K 300/24','Verwaltungsstreit Baugenehmigung','VG Minden',             'offen',      '2024-05-21', '2024/05/21/Az-7-K-300-24.pdf','Az-7-K-300-24.pdf',2024, 5, 21);