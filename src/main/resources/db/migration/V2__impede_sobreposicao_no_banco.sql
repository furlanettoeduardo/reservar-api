-- Fecha a janela do TOCTOU no unico lugar onde ela e realmente fechavel: o dado.
--
-- ReservaService verifica sobreposicao e depois grava. Sob READ_COMMITTED as duas
-- transacoes concorrentes nao enxergam a linha nao-commitada uma da outra, entao ambas
-- passam na verificacao e ambas gravam -- reproduzido em ConcorrenciaReservaIT.
--
-- Lock otimista e pessimista defenderiam o caminho de codigo Java. Esta constraint defende
-- a tabela, e vale igual para import manual, script de carga ou um segundo servico.

-- Necessaria porque a constraint mistura igualdade em bigint com sobreposicao em intervalo:
-- o GiST nativo nao tem operator class para = em tipos escalares. Vai junto na propria
-- migration para ela ser autocontida.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE reserva
    ADD CONSTRAINT reserva_sem_sobreposicao
        EXCLUDE USING gist (
            espaco_id WITH =,
            tstzrange(inicio, fim, '[)') WITH &&
        ) WHERE (status = 'CONFIRMADA');

-- '[)' e meio-aberto: repete no banco exatamente a semantica de Periodo e da JPQL de
-- existeSobreposicao. Uma reserva que termina 14:00 e outra que comeca 14:00 nao conflitam.
--
-- WHERE (status = 'CONFIRMADA') torna a constraint parcial: reserva cancelada nao ocupa o
-- espaco, mesma regra que o servico aplica. Sem isso, cancelar e reagendar no mesmo horario
-- seria rejeitado pelo banco.

COMMENT ON CONSTRAINT reserva_sem_sobreposicao ON reserva IS
    'Duas reservas CONFIRMADAS do mesmo espaco nao podem ter periodos sobrepostos.';
