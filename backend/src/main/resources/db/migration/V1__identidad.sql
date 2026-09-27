CREATE TABLE usuarios (
    id                   uuid         PRIMARY KEY DEFAULT uuidv7(),
    correo               varchar(255) NOT NULL,
    nombre_usuario       varchar(20)  NOT NULL,
    nombre_visible       varchar(50)  NOT NULL,
    hash_contrasena      varchar(100) NOT NULL,
    info                 varchar(140),
    codigo_invitacion    varchar(8)   NOT NULL,
    correo_verificado_en timestamptz,
    ultima_conexion      timestamptz,
    creado_en            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT usuarios_correo_unico UNIQUE (correo),
    CONSTRAINT usuarios_nombre_usuario_unico UNIQUE (nombre_usuario),
    CONSTRAINT usuarios_codigo_invitacion_unico UNIQUE (codigo_invitacion),
    CONSTRAINT usuarios_correo_normalizado CHECK (correo = lower(btrim(correo))),
    CONSTRAINT usuarios_nombre_usuario_formato CHECK (nombre_usuario ~ '^[a-z][a-z0-9_.]{2,19}$'),
    CONSTRAINT usuarios_codigo_invitacion_formato CHECK (codigo_invitacion ~ '^[2-9A-HJKMNP-Za-kmnp-z]{8}$')
);
CREATE INDEX idx_usuarios_sin_verificar ON usuarios (creado_en) WHERE correo_verificado_en IS NULL;

CREATE TABLE refresh_tokens (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id  uuid        NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,
    expira_en   timestamptz NOT NULL,
    revocado_en timestamptz,
    creado_en   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_usuario ON refresh_tokens (usuario_id);

CREATE TABLE tokens_correo (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id uuid        NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    proposito  varchar(15) NOT NULL CHECK (proposito IN ('VERIFICACION', 'RECUPERACION')),
    token_hash varchar(64) NOT NULL UNIQUE,
    expira_en  timestamptz NOT NULL,
    usado_en   timestamptz,
    creado_en  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tokens_correo_usuario ON tokens_correo (usuario_id, proposito);
