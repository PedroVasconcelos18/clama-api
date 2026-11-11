-- espelho do WordPress: so o que a API precisa saber de um post. A identidade e o id do WordPress; nunca e apagado.
CREATE TABLE blog_post (
  wp_id bigint PRIMARY KEY CHECK (wp_id > 0),
  slug varchar(200) NOT NULL,
  titulo varchar(200) NOT NULL DEFAULT '',
  url varchar(500) NOT NULL DEFAULT '',
  publicado boolean NOT NULL, -- status publish e sem senha: o unico que aceita comentario e curtida
  publicado_em timestamptz,
  atualizado_em timestamptz NOT NULL
);
CREATE INDEX blog_post_slug_ix ON blog_post (slug);

CREATE TABLE blog_comentario (
  id bigserial PRIMARY KEY,
  post_id bigint NOT NULL REFERENCES blog_post (wp_id),
  usuario_id bigint NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  conteudo text NOT NULL CHECK (char_length(conteudo) BETWEEN 3 AND 2000),
  suspeito boolean NOT NULL,
  ip_cifrado text, -- AES-GCM; NULL depois de 180 dias (purgar-ips, Marco Civil art. 15)
  criado_em timestamptz NOT NULL
);
CREATE INDEX blog_comentario_post_ix ON blog_comentario (post_id, criado_em DESC);
CREATE INDEX blog_comentario_usuario_ix ON blog_comentario (usuario_id);
CREATE INDEX blog_comentario_criado_ix ON blog_comentario (criado_em DESC);

CREATE TABLE blog_curtida (
  post_id bigint NOT NULL REFERENCES blog_post (wp_id),
  usuario_id bigint NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
  criado_em timestamptz NOT NULL,
  PRIMARY KEY (post_id, usuario_id)
);
CREATE INDEX blog_curtida_usuario_ix ON blog_curtida (usuario_id);

-- ban ativo: uma linha por conta. Revogar apaga a linha; o historico fica na auditoria.
CREATE TABLE blog_banimento (
  usuario_id bigint PRIMARY KEY REFERENCES usuario (id) ON DELETE CASCADE,
  motivo varchar(500) NOT NULL CHECK (char_length(motivo) >= 3),
  banido_por_id bigint REFERENCES usuario (id) ON DELETE SET NULL,
  banido_em timestamptz NOT NULL
);
