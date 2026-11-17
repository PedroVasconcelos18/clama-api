-- Onda 1 da v2. As tasks seguintes acrescentam a este arquivo enquanto a branch nao sai.

-- Tema do clamor: categoria (nao e texto livre), por isso em claro.
ALTER TABLE pedido ADD COLUMN tema varchar(20)
  CHECK (tema IS NULL OR tema IN ('luto','ansiedade','familia','saude','sustento','fe','injustica'));

-- Triagem de crise (job triar-pedido): nulo ate triar. Derivado do texto do pedido: a purga e a anonimizacao zeram junto.
ALTER TABLE pedido ADD COLUMN risco boolean;
