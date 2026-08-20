-- Fecha o lost update medido em AtualizacaoPerdidaIT.
--
-- O Hibernate emite UPDATE de todas as colunas em qualquer alteracao, entao duas transacoes
-- editando campos DIFERENTES da mesma linha nao conflitam logicamente e ainda assim uma apaga
-- a outra: a segunda a commitar reescreve o campo da primeira com o valor que ela carregou.
-- Medido: falhas=0, as duas transacoes "deram certo", uma das edicoes desapareceu.
--
-- DEFAULT 0 nao e detalhe de estilo. ADD COLUMN ... NOT NULL sem default falha em tabela com
-- linhas -- e essa e a classe de erro que schema vazio nunca pega. Migration e sobre dados que
-- ja existem.

ALTER TABLE espaco  ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reserva ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN espaco.versao IS
    'Lock otimista. O UPDATE passa a incluir "and versao = ?", entao a segunda transacao a '
    'commitar afeta 0 linhas e o Hibernate lanca OptimisticLockException em vez de sobrescrever.';
