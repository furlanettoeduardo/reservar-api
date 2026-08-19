CREATE TABLE cliente (
                         id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         nome      VARCHAR(150) NOT NULL,
                         email     VARCHAR(255) NOT NULL UNIQUE,
                         criado_em TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE espaco (
                        id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        nome       VARCHAR(150)  NOT NULL,
                        capacidade INTEGER       NOT NULL CHECK (capacidade > 0),
                        preco_hora NUMERIC(19,4) NOT NULL CHECK (preco_hora >= 0),
                        criado_em  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE reserva (
                         id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         espaco_id   BIGINT        NOT NULL REFERENCES espaco(id),
                         cliente_id  BIGINT        NOT NULL REFERENCES cliente(id),
                         inicio      TIMESTAMPTZ   NOT NULL,
                         fim         TIMESTAMPTZ   NOT NULL,
                         status      VARCHAR(20)   NOT NULL,
                         valor_total NUMERIC(19,4) NOT NULL,
                         criado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
                         CONSTRAINT reserva_periodo_valido CHECK (fim > inicio)
);

CREATE INDEX idx_reserva_espaco_periodo ON reserva (espaco_id, inicio, fim);