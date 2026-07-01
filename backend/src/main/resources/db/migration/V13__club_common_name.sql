-- Common/popular club name (e.g. "Criciúma" for "Criciúma Esporte Clube"),
-- used for compact standings display. Nullable; new careers get it from the seed
-- (SaveGameService), while existing save games are backfilled below.
ALTER TABLE clubs ADD COLUMN common_name VARCHAR(80);

-- Backfill already-seeded clubs so existing saves show the common name too.
UPDATE clubs SET common_name = 'Criciúma'     WHERE name = 'Criciúma Esporte Clube';
UPDATE clubs SET common_name = 'Avaí'         WHERE name = 'Avaí Futebol Clube';
UPDATE clubs SET common_name = 'Figueirense'  WHERE name = 'Figueirense Futebol Clube';
UPDATE clubs SET common_name = 'Chapecoense'  WHERE name = 'Associação Chapecoense de Futebol';
UPDATE clubs SET common_name = 'Brusque'      WHERE name = 'Brusque Futebol Clube';
UPDATE clubs SET common_name = 'Marcílio Dias' WHERE name = 'Clube Náutico Marcílio Dias';
UPDATE clubs SET common_name = 'Barra'        WHERE name = 'Associação Desportiva Barra';
UPDATE clubs SET common_name = 'Concórdia'    WHERE name = 'Associação Esportiva Concórdia';
UPDATE clubs SET common_name = 'Hercílio Luz' WHERE name = 'Hercílio Luz Futebol Clube';
UPDATE clubs SET common_name = 'Camboriú'     WHERE name = 'Camboriú Futebol Clube';
UPDATE clubs SET common_name = 'Goiás'        WHERE name = 'Goiás Esporte Clube';
UPDATE clubs SET common_name = 'Coritiba'     WHERE name = 'Coritiba Foot Ball Club';
UPDATE clubs SET common_name = 'Novorizontino' WHERE name = 'Grêmio Novorizontino';
UPDATE clubs SET common_name = 'Sport'        WHERE name = 'Sport Club do Recife';
UPDATE clubs SET common_name = 'Ceará'        WHERE name = 'Ceará Sporting Club';
UPDATE clubs SET common_name = 'Vila Nova'    WHERE name = 'Vila Nova Futebol Clube';
UPDATE clubs SET common_name = 'Paysandu'     WHERE name = 'Clube Atlético Paysandu';
UPDATE clubs SET common_name = 'Operário'     WHERE name = 'Operário Ferroviário Esporte Clube';
UPDATE clubs SET common_name = 'CRB'          WHERE name = 'Clube de Regatas Brasil';
UPDATE clubs SET common_name = 'Guarani'      WHERE name = 'Guarani Futebol Clube';
UPDATE clubs SET common_name = 'Ponte Preta'  WHERE name = 'Ponte Preta Futebol Clube';
UPDATE clubs SET common_name = 'Mirassol'     WHERE name = 'Mirassol Futebol Clube';
UPDATE clubs SET common_name = 'Remo'         WHERE name = 'Clube do Remo';
UPDATE clubs SET common_name = 'Fortaleza'    WHERE name = 'Fortaleza Esporte Clube';
UPDATE clubs SET common_name = 'Botafogo-SP'  WHERE name = 'Botafogo Futebol Clube (SP)';

-- Any club not covered above (e.g. future additions) falls back to its full name.
UPDATE clubs SET common_name = name WHERE common_name IS NULL;
