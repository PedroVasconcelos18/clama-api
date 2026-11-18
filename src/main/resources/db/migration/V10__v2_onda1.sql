-- Onda 1 da v2. As tasks seguintes acrescentam a este arquivo enquanto a branch nao sai.

-- Tema do clamor: categoria (nao e texto livre), por isso em claro.
ALTER TABLE pedido ADD COLUMN tema varchar(20)
  CHECK (tema IS NULL OR tema IN ('luto','ansiedade','familia','saude','sustento','fe','injustica'));

-- Triagem de crise (job triar-pedido): nulo ate triar. Derivado do texto do pedido: a purga e a anonimizacao zeram junto.
ALTER TABLE pedido ADD COLUMN risco boolean;

-- Metas das instituicoes (todas opcionais): o front so mostra a barra quando ha meta.
ALTER TABLE instituicao ADD COLUMN causa varchar(140),
  ADD COLUMN meta_mensal_centavos bigint CHECK (meta_mensal_centavos IS NULL OR meta_mensal_centavos > 0),
  ADD COLUMN custo_unidade_centavos bigint CHECK (custo_unidade_centavos IS NULL OR custo_unidade_centavos > 0),
  ADD COLUMN unidade varchar(40);
