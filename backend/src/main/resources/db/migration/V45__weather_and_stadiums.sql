-- Club geography (drives match-day weather generation) + real stadium capacities,
-- replacing the derived 8000 + reputation*500. Backfilled by club name, following the
-- V33__stadium_name.sql / V43__research_backfill.sql precedent.
ALTER TABLE clubs ADD COLUMN city VARCHAR(60);
ALTER TABLE clubs ADD COLUMN state VARCHAR(2);
ALTER TABLE clubs ADD COLUMN climate_zone VARCHAR(20);

UPDATE clubs SET city = 'Criciúma',              state = 'SC', climate_zone = 'SUL',              stadium_capacity = 19225 WHERE name = 'Criciúma Esporte Clube';
UPDATE clubs SET city = 'Florianópolis',         state = 'SC', climate_zone = 'SUL',              stadium_capacity = 17800 WHERE name = 'Avaí Futebol Clube';
UPDATE clubs SET city = 'Florianópolis',         state = 'SC', climate_zone = 'SUL',              stadium_capacity = 19584 WHERE name = 'Figueirense Futebol Clube';
UPDATE clubs SET city = 'Chapecó',               state = 'SC', climate_zone = 'SUL',              stadium_capacity = 20089 WHERE name = 'Associação Chapecoense de Futebol';
UPDATE clubs SET city = 'Brusque',               state = 'SC', climate_zone = 'SUL',              stadium_capacity =  5000 WHERE name = 'Brusque Futebol Clube';
UPDATE clubs SET city = 'Itajaí',                state = 'SC', climate_zone = 'SUL',              stadium_capacity =  5000 WHERE name = 'Clube Náutico Marcílio Dias';
UPDATE clubs SET city = 'Balneário Camboriú',    state = 'SC', climate_zone = 'SUL',              stadium_capacity =  3000 WHERE name = 'Associação Desportiva Barra';
UPDATE clubs SET city = 'Concórdia',             state = 'SC', climate_zone = 'SUL',              stadium_capacity =  4000 WHERE name = 'Associação Esportiva Concórdia';
UPDATE clubs SET city = 'Tubarão',               state = 'SC', climate_zone = 'SUL',              stadium_capacity =  7000 WHERE name = 'Hercílio Luz Futebol Clube';
UPDATE clubs SET city = 'Camboriú',              state = 'SC', climate_zone = 'SUL',              stadium_capacity =  4000 WHERE name = 'Camboriú Futebol Clube';
UPDATE clubs SET city = 'Ponta Grossa',          state = 'PR', climate_zone = 'SUL',              stadium_capacity = 10000 WHERE name = 'Operário Ferroviário Esporte Clube';
UPDATE clubs SET city = 'Londrina',              state = 'PR', climate_zone = 'SUL',              stadium_capacity = 30000 WHERE name = 'Londrina Esporte Clube';
UPDATE clubs SET city = 'Caxias do Sul',         state = 'RS', climate_zone = 'SUL',              stadium_capacity = 19924 WHERE name = 'Esporte Clube Juventude';
UPDATE clubs SET city = 'Novo Horizonte',        state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 15000 WHERE name = 'Grêmio Novorizontino';
UPDATE clubs SET city = 'Campinas',              state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 19722 WHERE name = 'Ponte Preta Futebol Clube';
UPDATE clubs SET city = 'Ribeirão Preto',        state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 16000 WHERE name = 'Botafogo Futebol Clube (SP)';
UPDATE clubs SET city = 'São Bernardo do Campo', state = 'SP', climate_zone = 'SUDESTE',          stadium_capacity = 15000 WHERE name = 'São Bernardo Futebol Clube';
UPDATE clubs SET city = 'Belo Horizonte',        state = 'MG', climate_zone = 'SUDESTE',          stadium_capacity = 23018 WHERE name = 'América Futebol Clube';
UPDATE clubs SET city = 'São João del-Rei',      state = 'MG', climate_zone = 'SUDESTE',          stadium_capacity =  5000 WHERE name = 'Athletic Club';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 14450 WHERE name = 'Goiás Esporte Clube';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 13500 WHERE name = 'Vila Nova Futebol Clube';
UPDATE clubs SET city = 'Goiânia',               state = 'GO', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 12500 WHERE name = 'Atlético Clube Goianiense';
UPDATE clubs SET city = 'Cuiabá',                state = 'MT', climate_zone = 'CENTRO_OESTE',     stadium_capacity = 44097 WHERE name = 'Cuiabá Esporte Clube';
UPDATE clubs SET city = 'Recife',                state = 'PE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 26000 WHERE name = 'Sport Club do Recife';
UPDATE clubs SET city = 'Recife',                state = 'PE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 22000 WHERE name = 'Clube Náutico Capibaribe';
UPDATE clubs SET city = 'Fortaleza',             state = 'CE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 20000 WHERE name = 'Ceará Sporting Club';
UPDATE clubs SET city = 'Fortaleza',             state = 'CE', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 63903 WHERE name = 'Fortaleza Esporte Clube';
UPDATE clubs SET city = 'Maceió',                state = 'AL', climate_zone = 'NORDESTE_LITORAL', stadium_capacity = 17000 WHERE name = 'Clube de Regatas Brasil';

-- Defensive: any club row not matched above (the seed is closed, so this should match zero rows)
-- keeps its derived capacity and gets the neutral zone, so weather generation never sees a null.
UPDATE clubs SET climate_zone = 'SUDESTE' WHERE climate_zone IS NULL;
ALTER TABLE clubs ALTER COLUMN climate_zone SET NOT NULL;

-- Realized crowd for a played fixture. Nullable: unplayed fixtures and every fixture in
-- existing saves have none, and all callers null-guard.
ALTER TABLE fixtures ADD COLUMN attendance INT;
