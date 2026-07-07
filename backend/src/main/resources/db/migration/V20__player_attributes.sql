-- Championship-Manager-style attribute expansion (Phase A).
-- The six legacy columns (pace, finishing, passing, tackling, goalkeeping, stamina) are
-- reused as anchors for VELOCIDADE/FINALIZACAO/PASSE/DESARME/REFLEXOS/FOLEGO; this migration
-- adds the other 22 attributes as columns on the players table. Defaults let the ALTER run
-- against existing rows; career creation and the generators overwrite them.
ALTER TABLE players
    -- Técnico
    ADD COLUMN drible          INT NOT NULL DEFAULT 0,
    ADD COLUMN cruzamento      INT NOT NULL DEFAULT 0,
    ADD COLUMN cabeceio        INT NOT NULL DEFAULT 0,
    ADD COLUMN marcacao        INT NOT NULL DEFAULT 0,
    ADD COLUMN bola_parada     INT NOT NULL DEFAULT 0,
    ADD COLUMN chute_longe     INT NOT NULL DEFAULT 0,
    -- Mental
    ADD COLUMN visao           INT NOT NULL DEFAULT 0,
    ADD COLUMN posicionamento  INT NOT NULL DEFAULT 0,
    ADD COLUMN decisao         INT NOT NULL DEFAULT 0,
    ADD COLUMN compostura      INT NOT NULL DEFAULT 0,
    ADD COLUMN concentracao    INT NOT NULL DEFAULT 0,
    ADD COLUMN determinacao    INT NOT NULL DEFAULT 0,
    ADD COLUMN agressividade   INT NOT NULL DEFAULT 0,
    ADD COLUMN lideranca       INT NOT NULL DEFAULT 0,
    -- Físico
    ADD COLUMN aceleracao      INT NOT NULL DEFAULT 0,
    ADD COLUMN forca           INT NOT NULL DEFAULT 0,
    ADD COLUMN resistencia     INT NOT NULL DEFAULT 0,
    ADD COLUMN equilibrio      INT NOT NULL DEFAULT 0,
    ADD COLUMN agilidade       INT NOT NULL DEFAULT 0,
    -- Goleiro
    ADD COLUMN posicionamento_gk INT NOT NULL DEFAULT 0,
    ADD COLUMN saida_de_gol      INT NOT NULL DEFAULT 0,
    ADD COLUMN jogo_com_os_pes   INT NOT NULL DEFAULT 0;
