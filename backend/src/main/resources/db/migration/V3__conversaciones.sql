CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE conversaciones (
    id        uuid        PRIMARY KEY DEFAULT uuidv7(),
    tipo      varchar(10) NOT NULL CHECK (tipo IN ('DIRECTA', 'GRUPO')),
    creada_en timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conversaciones_id_tipo UNIQUE (id, tipo)
);

CREATE TABLE miembros (
    conversacion_id  uuid        NOT NULL REFERENCES conversaciones (id) ON DELETE CASCADE,
    usuario_id       uuid        NOT NULL REFERENCES usuarios (id),
    rol              varchar(10) NOT NULL DEFAULT 'MIEMBRO' CHECK (rol IN ('ADMIN', 'MIEMBRO')),
    ultima_entregada bigint      NOT NULL DEFAULT 0,
    ultima_leida     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT miembros_leida_no_supera_entregada CHECK (ultima_leida <= ultima_entregada),
    PRIMARY KEY (conversacion_id, usuario_id)
);
CREATE INDEX idx_miembros_usuario ON miembros (usuario_id);

CREATE TABLE chats_directos (
    conversacion_id uuid        PRIMARY KEY,
    tipo            varchar(10) NOT NULL DEFAULT 'DIRECTA' CHECK (tipo = 'DIRECTA'),
    usuario_a_id    uuid        NOT NULL,
    usuario_b_id    uuid        NOT NULL,
    CONSTRAINT chats_directos_orden CHECK (usuario_a_id < usuario_b_id),
    CONSTRAINT chats_directos_par_unico UNIQUE (usuario_a_id, usuario_b_id),
    FOREIGN KEY (conversacion_id, tipo) REFERENCES conversaciones (id, tipo) ON DELETE CASCADE,
    FOREIGN KEY (conversacion_id, usuario_a_id) REFERENCES miembros (conversacion_id, usuario_id),
    FOREIGN KEY (conversacion_id, usuario_b_id) REFERENCES miembros (conversacion_id, usuario_id)
);

CREATE TABLE grupos (
    conversacion_id uuid         PRIMARY KEY,
    tipo            varchar(10)  NOT NULL DEFAULT 'GRUPO' CHECK (tipo = 'GRUPO'),
    nombre          varchar(50)  NOT NULL,
    descripcion     varchar(500),
    creado_por      uuid         NOT NULL REFERENCES usuarios (id),
    FOREIGN KEY (conversacion_id, tipo) REFERENCES conversaciones (id, tipo) ON DELETE CASCADE
);

CREATE TABLE periodos_membresia (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversacion_id uuid   NOT NULL,
    usuario_id      uuid   NOT NULL,
    desde_secuencia bigint NOT NULL CHECK (desde_secuencia > 0),
    hasta_secuencia bigint,
    CONSTRAINT periodos_orden CHECK (hasta_secuencia IS NULL OR hasta_secuencia >= desde_secuencia),
    FOREIGN KEY (conversacion_id, usuario_id) REFERENCES miembros (conversacion_id, usuario_id) ON DELETE CASCADE,
    CONSTRAINT periodos_sin_solapamiento EXCLUDE USING gist (
        conversacion_id WITH =, usuario_id WITH =, int8range(desde_secuencia, hasta_secuencia, '[]') WITH &&)
);
CREATE INDEX idx_periodos_miembro ON periodos_membresia (conversacion_id, usuario_id, desde_secuencia);

CREATE TABLE mensajes (
    id              bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversacion_id uuid          NOT NULL,
    secuencia       bigint        NOT NULL CHECK (secuencia > 0),
    remitente_id    uuid          NOT NULL,
    id_cliente      uuid          NOT NULL,
    tipo            varchar(10)   NOT NULL CHECK (tipo IN ('TEXTO', 'ADJUNTO', 'EVENTO')),
    texto           varchar(4096),
    creado_en       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT mensajes_secuencia_unica UNIQUE (conversacion_id, secuencia),
    CONSTRAINT mensajes_id_cliente_unico UNIQUE (remitente_id, id_cliente),
    CONSTRAINT mensajes_id_tipo UNIQUE (id, tipo),
    FOREIGN KEY (conversacion_id, remitente_id) REFERENCES miembros (conversacion_id, usuario_id),
    CONSTRAINT mensajes_texto_obligatorio CHECK (tipo <> 'TEXTO' OR texto IS NOT NULL),
    CONSTRAINT mensajes_evento_sin_texto CHECK (tipo <> 'EVENTO' OR texto IS NULL),
    CONSTRAINT mensajes_texto_no_vacio CHECK (texto IS NULL OR length(btrim(texto)) > 0)
);

CREATE TABLE eventos_grupo (
    mensaje_id  bigint      PRIMARY KEY,
    tipo        varchar(10) NOT NULL DEFAULT 'EVENTO' CHECK (tipo = 'EVENTO'),
    evento      varchar(20) NOT NULL CHECK (evento IN ('GRUPO_CREADO', 'MIEMBRO_AGREGADO', 'MIEMBRO_QUITADO',
                    'MIEMBRO_SALIO', 'ADMIN_ASIGNADO', 'ADMIN_QUITADO', 'NOMBRE_CAMBIADO', 'FOTO_CAMBIADA')),
    afectado_id uuid        REFERENCES usuarios (id),
    valor       varchar(50),
    FOREIGN KEY (mensaje_id, tipo) REFERENCES mensajes (id, tipo) ON DELETE CASCADE,
    CONSTRAINT eventos_afectado CHECK ((evento IN ('MIEMBRO_AGREGADO', 'MIEMBRO_QUITADO', 'ADMIN_ASIGNADO',
                                                   'ADMIN_QUITADO')) = (afectado_id IS NOT NULL)),
    CONSTRAINT eventos_valor CHECK ((evento = 'NOMBRE_CAMBIADO') = (valor IS NOT NULL))
);
