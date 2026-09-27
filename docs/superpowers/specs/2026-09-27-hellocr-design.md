# HelloCR — Diseño

- **Fecha:** 2026-09-27
- **Estado:** aprobado en conversación, pendiente de revisión escrita
- **Tipo:** proyecto propio (aprender mensajería en tiempo real y usarla de verdad con amigos y familia); se
  despliega en internet

## 1. Objetivo

Aplicación de mensajería al estilo de WhatsApp, instalable como PWA en el celular y usable desde la PC. Los
usuarios se registran con su correo, se encuentran por `@usuario` o con un link/QR de invitación y chatean 1 a 1
o en grupos, con imágenes, archivos, estados de entrega y notificaciones push. Pensada para decenas de usuarios.

**Criterios de éxito**

1. Dos personas se registran, se encuentran (por `@usuario` o invitación) y chatean en tiempo real desde el
   navegador o desde la PWA instalada.
2. Ningún mensaje se pierde ni se duplica: ni con la red cortada, ni con reintentos, ni con envíos simultáneos al
   mismo chat. Lo garantizan restricciones de la base de datos y lo verifican tests de concurrencia y E2E.
3. Enviado (✓), entregado (✓✓) y leído (✓✓ celeste), "en línea" / "últ. vez" y "escribiendo…" se actualizan
   sin recargar.
4. Con la app cerrada llega una notificación push en Android, PC y iPhone (este último con la PWA instalada,
   iOS 16.4 o superior).
5. La base de datos está en 3FN; la única redundancia está documentada y protegida por claves foráneas.
6. Corre en Oracle Cloud Always Free con HTTPS en `hellocr.duckdns.org` y backups diarios.

## 2. Alcance

**Incluye (v1):** registro con verificación de correo · login con JWT y refresh rotativo · recuperación de
contraseña · perfil (foto, nombre, info, `@usuario`) · búsqueda exacta por `@usuario` · link y QR de invitación ·
chats 1 a 1 · grupos de hasta 50 personas con administradores · mensajes de texto, imágenes y archivos · estados
enviado/entregado/leído · presencia y "escribiendo…" · bandeja de salida sin conexión · notificaciones push ·
PWA instalable · tema oscuro y claro · despliegue en Oracle con backups.

**Fuera de alcance (v1):** notas de voz · llamadas · cifrado de extremo a extremo · bloquear usuarios · editar o
borrar mensajes · responder citando · reacciones · búsqueda dentro de los mensajes · estados/historias · cambiar
la contraseña desde el perfil (se usa "olvidé mi contraseña") · borrar la cuenta · cerrar sesión en todos los
dispositivos · iniciar sesión con Google · app nativa · CI/CD. Ninguno obliga a rehacer lo definido aquí.

## 3. Stack y estructura

| Capa | Tecnología |
|---|---|
| Backend | Java 25 (LTS) · Spring Boot 4.x (última estable al crear el proyecto) · Maven (wrapper incluido) · Spring Web, WebSocket (STOMP), Data JPA, Security, OAuth2 Resource Server, Validation, Mail, Actuator · Flyway · webpush-java (`nl.martijndwars:web-push`) |
| Base de datos | PostgreSQL 18 nativo (sin Docker), extensión `btree_gist` |
| Frontend | React · TypeScript · Vite · React Router · TanStack Query (con persistencia en IndexedDB) · React Hook Form + Zod · Tailwind CSS v4 · `@stomp/stompjs` · `vite-plugin-pwa` (estrategia `injectManifest`) · `idb` · `qrcode` · Zustand |
| Tests | JUnit 5 + MockMvc + cliente STOMP real contra PostgreSQL (`hellocr_test`) · Vitest + React Testing Library + `fake-indexeddb` · Playwright |
| Producción | Oracle Cloud Always Free (VM ARM Ampere A1, Ubuntu Server LTS) · Caddy · systemd · DuckDNS |

```
HelloCR/
├── backend/      API REST y WebSocket en :8080
├── frontend/     Vite en :5173, proxy de /api y /ws → :8080 (mismo origen en desarrollo, sin CORS)
├── despliegue/   Caddyfile, hellocr.service, timer de backups, desplegar.ps1
└── docs/         specs y planes
```

**Convención de nombres:** el dominio va en español (`Mensaje`, `Conversacion`, `ultimaLeida`); los sufijos
técnicos en inglés (`MensajeController`, `EnvioMensajesService`, `MensajeRepository`).

### Backend — paquetes por funcionalidad (raíz `com.hellocr`)

| Paquete | Responsabilidad |
|---|---|
| `auth` | Registro, verificación de correo, login, refresh, logout, recuperación de contraseña, bloqueo por intentos |
| `usuarios` | Perfil, búsqueda por `@usuario`, código de invitación |
| `correo` | `EnviadorCorreo` (SMTP y consola), plantillas |
| `conversaciones` | Lista y detalle, chats directos, miembros y periodos (`ConsultaMembresia`) |
| `grupos` | Crear y editar grupos, administrar miembros (`GestionMiembros`), eventos de grupo |
| `mensajes` | Envío, historial paginado, marcas de entregado y leído |
| `tiempoReal` | Configuración STOMP, interceptor JWT, `RegistroSesiones`, presencia, "escribiendo…", `EntregaTiempoReal` |
| `archivos` | `AlmacenArchivos`, subida, descarga con control de acceso, limpieza de huérfanos |
| `notificaciones` | Suscripciones Web Push, `EnviadorPush`, `EntregaPush` |
| `config` | `SecurityFilterChain`, `@ConfigurationProperties`, `Clock`, `TaskScheduler` |
| `comun` | `ErrorNegocio`, manejo de errores (`@RestControllerAdvice` y su equivalente STOMP), utilidades |

Cada paquete contiene sus entidades, repositorios, servicios, controladores y DTOs (`record`). Las entidades JPA
nunca salen de la capa de servicio.

### Principios SOLID

| Principio | Aplicación |
|---|---|
| **S** — responsabilidad única | `EnvioMensajesService` solo valida, asigna la secuencia y guarda; no sabe quién se entera. Los controladores REST y STOMP solo traducen entrada y salida. |
| **O** — abierto/cerrado | Al confirmarse un mensaje se publica `MensajeEnviado`, que se escucha con `@TransactionalEventListener(phase = AFTER_COMMIT)`. Lo escuchan `EntregaTiempoReal` y `EntregaPush`; un canal nuevo es una clase nueva, sin tocar el envío. |
| **L** — sustitución de Liskov | `AlmacenArchivos` tiene `AlmacenDisco` (Oracle) y, solo si se usa el respaldo, `AlmacenS3` (Cloudflare R2). Toda implementación pasa la misma batería abstracta de tests de contrato. |
| **I** — segregación de interfaces | `ConsultaMembresia` (¿es miembro activo?, ¿puede ver la secuencia N?) la usan mensajes, archivos y tiempo real. `GestionMiembros` (agregar, quitar, cambiar rol) solo la usa `grupos`. |
| **D** — inversión de dependencias | Los servicios dependen de interfaces: `AlmacenArchivos`, `EnviadorCorreo`, `EnviadorPush`, `Clock`. Los tests inyectan implementaciones falsas. |

### Frontend

```
frontend/src/
├── api/              cliente HTTP (token en memoria · un solo /refresh a la vez · reintento tras 401)
├── auth/             AuthProvider, rutas protegidas, invitación pendiente
├── tiempoReal/       ClienteTiempoReal · BandejaSalida · Sincronizador
├── funcionalidades/
│   ├── acceso/       login, registro, verificación, recuperación
│   ├── chats/        lista de chats, nuevo chat
│   ├── chat/         conversación abierta, compositor, burbujas
│   ├── grupos/       crear grupo, información del grupo
│   ├── perfil/       perfil, mi QR y mi link
│   └── invitaciones/
├── ui/               componentes base (Avatar, Burbuja, Boton, …) y tokens del tema
├── sw.ts             service worker
└── rutas.tsx
```

## 4. Reglas y configuración

Propiedades `app.*` en `application.yml`:

| Propiedad | Por defecto | Regla |
|---|---|---|
| `app.zona-horaria` | `America/Costa_Rica` | Zona de las tareas programadas. La interfaz muestra las horas en la zona del dispositivo |
| `app.url-publica` | `http://localhost:5173` | Base de los links de los correos y de las invitaciones; único origen permitido para el WebSocket |
| `app.jwt.secreto` | — | ≥ 32 bytes, obligatorio |
| `app.jwt.duracion-access` | `15m` | |
| `app.jwt.duracion-refresh` | `30d` | Se renueva con cada rotación |
| `app.jwt.cookie-segura` | `true` | `false` solo en desarrollo |
| `app.correo.remitente` | — | Dirección del remitente |
| `app.correo.duracion-verificacion` | `24h` | |
| `app.correo.duracion-recuperacion` | `1h` | |
| `app.correo.espera-reenvio` | `1m` | Mínimo entre dos correos del mismo tipo a la misma cuenta |
| `app.cuentas.dias-sin-verificar` | `7` | Después se borran |
| `app.login.max-intentos` | `5` | Intentos fallidos por identificador + IP |
| `app.login.bloqueo` | `15m` | |
| `app.mensajes.limite-cantidad` / `limite-ventana` | `30` / `10s` | Mensajes por usuario |
| `app.grupos.max-miembros` | `50` | Miembros activos, incluido quien crea el grupo |
| `app.archivos.raiz` | `./datos/archivos` | Carpeta de `AlmacenDisco` |
| `app.archivos.max-imagen` | `5MB` | Tamaño ya comprimido |
| `app.archivos.max-archivo` | `25MB` | |
| `app.archivos.horas-huerfanos` | `24` | |
| `app.tiempo-real.latido` | `10s` | Heartbeat STOMP en ambos sentidos |
| `app.push.espera-entrega` | `10s` | Ver 7.6 |
| `app.push.vapid.publica` / `privada` / `sujeto` | — | Claves VAPID; el sujeto es un `mailto:` |

**Valores fijos en el código:** texto de un mensaje ≤ 4096 caracteres · el cliente avisa "escribiendo" como máximo
cada 3 s y lo muestra durante 5 s · el servidor ignora avisos del mismo usuario y chat con menos de 2 s de
diferencia · el cliente agrupa los acuses de entrega cada 500 ms.

**Validaciones**

| Campo | Regla |
|---|---|
| `correo` | Formato válido, ≤ 255; se guarda sin espacios y en minúsculas |
| `nombreUsuario` | Se quita un `@` inicial y se pasa a minúsculas; debe cumplir `^[a-z][a-z0-9_.]{2,19}$`. Reservados: `admin`, `administrador`, `hellocr`, `soporte`, `sistema`, `root` |
| `nombreVisible` | 1–50 caracteres, recortado |
| `info` | 0–140 caracteres |
| `contrasena` | 8–64 caracteres y ≤ 72 bytes en UTF-8 (límite de BCrypt) |
| Nombre de grupo | 1–50 caracteres, recortado |
| Descripción de grupo | 0–500 caracteres |
| Texto de mensaje | 1–4096 caracteres, recortado; un adjunto puede ir sin texto |

## 5. Autenticación, registro e invitaciones

### 5.1 Tokens

Mismo esquema que reservas-app (sección 5.1 de su spec y plan 1), con estas diferencias:

- **Claims:** `iss` (`hellocr`), `sub` (id del usuario), `nombreUsuario`, `iat`, `exp`. Sin roles ni permisos:
  la v1 no tiene administración global y los permisos de grupo se verifican contra `miembros.rol`.
- **Refresh de 30 días que se renueva con cada rotación** (sesión deslizante).
- **Margen de 30 s en la detección de reutilización**, igual que el plan 1 de reservas-app: si un refresh token
  revocado se reusa dentro de los 30 s posteriores a su revocación (dos pestañas refrescando a la vez), solo se
  rechaza ese request; pasado ese margen se revocan todas las sesiones.
- El WebSocket se autentica con el mismo access token (7.1).

Igual que en reservas-app: access token HS256 solo en memoria del frontend (nunca `localStorage`); refresh token
opaco de 32 bytes en la cookie `refresh_token` (`HttpOnly`, `SameSite=Strict`, `Path=/api/auth`, `Secure`
configurable), guardado en la base como hash SHA-256; emisión con `NimbusJwtEncoder` y validación con el soporte
de `oauth2-resource-server`; contraseñas con BCrypt (`DelegatingPasswordEncoder`); CSRF deshabilitado.

### 5.2 Flujos

- **Registro** `POST /api/auth/registro` `{correo, nombreUsuario, nombreVisible, contrasena}` → 201 sin sesión.
  Crea el usuario sin verificar, con un `codigo_invitacion` nuevo, y envía el correo de verificación.
  - Si el correo pertenece a una cuenta **sin verificar**, esa cuenta se borra y se crea la nueva en la misma
    transacción (evita que alguien bloquee un correo ajeno).
  - Correo de una cuenta verificada → 409 `CORREO_EN_USO`. `@usuario` ocupado (por cualquier cuenta) → 409
    `NOMBRE_USUARIO_EN_USO`. Reservado → 422 `NOMBRE_USUARIO_RESERVADO`.
- **Verificar** `POST /api/auth/verificar` `{token}` → marca el correo como verificado y el token como usado, y
  responde igual que el login (inicia sesión). Token inexistente, usado, vencido o de otro propósito → 422
  `TOKEN_INVALIDO`.
- **Reenviar verificación** `POST /api/auth/reenviar-verificacion` `{correo}` → siempre 204. Si existe una cuenta
  sin verificar con ese correo y pasó `espera-reenvio` desde el último envío, marca como usados los tokens de
  verificación anteriores y envía uno nuevo.
- **Login** `POST /api/auth/login` `{identificador, contrasena}` → `{accessToken, usuario: UsuarioPropioDto}` +
  cookie.
  - Si el identificador contiene `@` después del primer carácter es un correo; si no, un `@usuario`.
  - Credenciales incorrectas → 401 `CREDENCIALES_INVALIDAS`, sin indicar si la cuenta existe.
  - Tras `max-intentos` fallidos del mismo identificador + IP → 429 `DEMASIADOS_INTENTOS` con `Retry-After`
    durante `bloqueo`. Contador en memoria.
  - Contraseña correcta pero correo sin verificar → 403 `CORREO_NO_VERIFICADO` (solo lo descubre quien conoce la
    contraseña).
- **Refresh** `POST /api/auth/refresh` → como en reservas-app, con el margen de 30 s.
- **Logout** `POST /api/auth/logout` `{endpointPush?}` → revoca el refresh token, borra la cookie y, si viene
  `endpointPush`, borra esa suscripción push. Siempre 204.
- **Recuperar** `POST /api/auth/recuperar` `{correo}` → siempre 204. Si la cuenta existe y pasó `espera-reenvio`,
  envía un link que vence en `duracion-recuperacion`.
- **Restablecer** `POST /api/auth/restablecer` `{token, contrasenaNueva}` → 204. Cambia el hash, marca el token
  como usado, marca el correo como verificado si no lo estaba (el link prueba que es el dueño), revoca todos los
  refresh tokens del usuario y cierra sus WebSockets. Token inválido → 422 `TOKEN_INVALIDO`.

`UsuarioPropioDto`: `{id, correo, nombreUsuario, nombreVisible, info, fotoId, codigoInvitacion}`.

### 5.3 Invitaciones, búsqueda y perfil

- **Código de invitación:** 8 caracteres de `23456789ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz` (sin
  caracteres ambiguos), generados con `SecureRandom`; si chocan con la restricción única, se genera otro.
- **Link:** `{app.url-publica}/i/{codigo}`. El QR se genera en el frontend con ese link; para escanearlo basta la
  cámara del celular.
- **Abrir una invitación:** `GET /api/invitaciones/{codigo}` (con sesión) → `PerfilPublicoDto` o 404. El frontend
  muestra "Chatear con Marco (@marco)" y llama a `POST /api/conversaciones/directas`.
- **Sin sesión:** el frontend guarda el código en `localStorage` (`invitacionPendiente`) y lleva al registro o al
  login; al iniciar sesión muestra la tarjeta y borra el código.
- **Regenerar:** `POST /api/usuarios/yo/codigo-invitacion` → `{codigoInvitacion}` nuevo; el anterior deja de
  funcionar.
- **Búsqueda:** `GET /api/usuarios/buscar?nombreUsuario=marco` → `PerfilPublicoDto` o 404. Solo coincidencia
  exacta y solo cuentas verificadas.
- **Perfil:** `GET /api/usuarios/yo` · `PATCH /api/usuarios/yo` `{nombreVisible?, info?, fotoId?,
  nombreUsuario?}`. `fotoId` debe ser una imagen subida por el mismo usuario (422 `ARCHIVO_INVALIDO`); `null`
  quita la foto. Cambiar el `@usuario` no rompe los links de invitación.
- `GET /api/usuarios/{id}` → `PerfilPublicoDto` (solo cuentas verificadas).

`PerfilPublicoDto`: `{id, nombreUsuario, nombreVisible, info, fotoId}`.

### 5.4 Correo

`EnviadorCorreo.enviar(CorreoSaliente)` con dos implementaciones:

- `EnviadorCorreoSmtp` (perfil `prod`): Gmail, `smtp.gmail.com:587` con STARTTLS y una contraseña de aplicación
  (requiere verificación en dos pasos en la cuenta). Límite de Gmail: unos 500 correos por día.
- `EnviadorCorreoConsola` (perfil `local`): escribe el correo y el link en el log.

Los tests usan un falso que guarda los correos en memoria. Plantillas HTML simples con versión de texto plano, en
voseo tico, con el link y su vencimiento. El envío ocurre después del commit y de forma asíncrona; si falla, se
registra en el log y el usuario puede pedir un reenvío.

## 6. Modelo de datos

Migraciones con Flyway; cada plan agrega las suyas y nunca se edita una migración aplicada. IDs `uuid DEFAULT
uuidv7()` (nativo en PostgreSQL 18) para todo lo que viaja en URLs; `bigint GENERATED ALWAYS AS IDENTITY` para lo
interno. Tiempos `timestamptz`. `btree_gist` es una extensión *trusted*: la crea el dueño de la base.

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ── Identidad ─────────────────────────────────────────────────────────────
usuarios (
  id uuid PK DEFAULT uuidv7(),
  correo varchar(255) NOT NULL UNIQUE,
  nombre_usuario varchar(20) NOT NULL UNIQUE CHECK (nombre_usuario ~ '^[a-z][a-z0-9_.]{2,19}$'),
  nombre_visible varchar(50) NOT NULL,
  hash_contrasena varchar(100) NOT NULL,
  info varchar(140),
  foto_id uuid → archivos,                     -- FK agregada con ALTER TABLE (referencia circular)
  codigo_invitacion char(8) NOT NULL UNIQUE,
  correo_verificado_en timestamptz,
  ultima_conexion timestamptz,
  creado_en timestamptz NOT NULL DEFAULT now()
)

refresh_tokens (
  id bigint PK, usuario_id → usuarios ON DELETE CASCADE NOT NULL, token_hash char(64) NOT NULL UNIQUE,
  expira_en timestamptz NOT NULL, revocado_en timestamptz, creado_en
)

tokens_correo (
  id bigint PK, usuario_id → usuarios ON DELETE CASCADE NOT NULL,
  proposito varchar(15) NOT NULL CHECK (proposito IN ('VERIFICACION', 'RECUPERACION')),
  token_hash char(64) NOT NULL UNIQUE, expira_en timestamptz NOT NULL, usado_en timestamptz, creado_en
)
-- índice: tokens_correo (usuario_id, proposito)

-- ── Conversaciones: supertipo y subtipos exclusivos ──────────────────────
conversaciones (
  id uuid PK DEFAULT uuidv7(),
  tipo varchar(10) NOT NULL CHECK (tipo IN ('DIRECTA', 'GRUPO')),
  creada_en timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, tipo)
)

miembros (
  conversacion_id uuid → conversaciones ON DELETE CASCADE,
  usuario_id uuid → usuarios,
  rol varchar(10) NOT NULL DEFAULT 'MIEMBRO' CHECK (rol IN ('ADMIN', 'MIEMBRO')),
  ultima_entregada bigint NOT NULL DEFAULT 0,
  ultima_leida bigint NOT NULL DEFAULT 0,
  CHECK (ultima_leida <= ultima_entregada),
  PRIMARY KEY (conversacion_id, usuario_id)
)
-- índice: miembros (usuario_id)

chats_directos (
  conversacion_id uuid PK,
  tipo varchar(10) NOT NULL DEFAULT 'DIRECTA' CHECK (tipo = 'DIRECTA'),
  usuario_a_id uuid NOT NULL, usuario_b_id uuid NOT NULL,
  CHECK (usuario_a_id < usuario_b_id),
  UNIQUE (usuario_a_id, usuario_b_id),
  FOREIGN KEY (conversacion_id, tipo) REFERENCES conversaciones (id, tipo) ON DELETE CASCADE,
  FOREIGN KEY (conversacion_id, usuario_a_id) REFERENCES miembros (conversacion_id, usuario_id),
  FOREIGN KEY (conversacion_id, usuario_b_id) REFERENCES miembros (conversacion_id, usuario_id)
)

grupos (
  conversacion_id uuid PK,
  tipo varchar(10) NOT NULL DEFAULT 'GRUPO' CHECK (tipo = 'GRUPO'),
  nombre varchar(50) NOT NULL,
  descripcion varchar(500),
  foto_id uuid → archivos,
  creado_por uuid NOT NULL → usuarios,
  FOREIGN KEY (conversacion_id, tipo) REFERENCES conversaciones (id, tipo) ON DELETE CASCADE
)

periodos_membresia (
  id bigint PK,
  conversacion_id uuid NOT NULL, usuario_id uuid NOT NULL,
  desde_secuencia bigint NOT NULL CHECK (desde_secuencia > 0),
  hasta_secuencia bigint,                      -- NULL = periodo abierto (miembro activo)
  CHECK (hasta_secuencia IS NULL OR hasta_secuencia >= desde_secuencia),
  FOREIGN KEY (conversacion_id, usuario_id) REFERENCES miembros ON DELETE CASCADE,
  EXCLUDE USING gist (conversacion_id WITH =, usuario_id WITH =,
                      int8range(desde_secuencia, hasta_secuencia, '[]') WITH &&)
)

-- ── Mensajes: supertipo y subtipos exclusivos ────────────────────────────
mensajes (
  id bigint PK,
  conversacion_id uuid NOT NULL,
  secuencia bigint NOT NULL CHECK (secuencia > 0),
  remitente_id uuid NOT NULL,
  id_cliente uuid NOT NULL,
  tipo varchar(10) NOT NULL CHECK (tipo IN ('TEXTO', 'ADJUNTO', 'EVENTO')),
  texto varchar(4096),
  creado_en timestamptz NOT NULL DEFAULT now(),
  UNIQUE (conversacion_id, secuencia),
  UNIQUE (remitente_id, id_cliente),
  UNIQUE (id, tipo),
  FOREIGN KEY (conversacion_id, remitente_id) REFERENCES miembros (conversacion_id, usuario_id),
  CHECK (tipo <> 'TEXTO' OR texto IS NOT NULL),
  CHECK (tipo <> 'EVENTO' OR texto IS NULL),
  CHECK (texto IS NULL OR length(btrim(texto)) > 0)
)

adjuntos (
  mensaje_id bigint PK,
  tipo varchar(10) NOT NULL DEFAULT 'ADJUNTO' CHECK (tipo = 'ADJUNTO'),
  archivo_id uuid NOT NULL UNIQUE → archivos,
  FOREIGN KEY (mensaje_id, tipo) REFERENCES mensajes (id, tipo) ON DELETE CASCADE
)

eventos_grupo (
  mensaje_id bigint PK,
  tipo varchar(10) NOT NULL DEFAULT 'EVENTO' CHECK (tipo = 'EVENTO'),
  evento varchar(20) NOT NULL CHECK (evento IN ('GRUPO_CREADO', 'MIEMBRO_AGREGADO', 'MIEMBRO_QUITADO',
         'MIEMBRO_SALIO', 'ADMIN_ASIGNADO', 'ADMIN_QUITADO', 'NOMBRE_CAMBIADO', 'FOTO_CAMBIADA')),
  afectado_id uuid → usuarios,
  valor varchar(50),
  FOREIGN KEY (mensaje_id, tipo) REFERENCES mensajes (id, tipo) ON DELETE CASCADE,
  CHECK ((evento IN ('MIEMBRO_AGREGADO', 'MIEMBRO_QUITADO', 'ADMIN_ASIGNADO', 'ADMIN_QUITADO'))
         = (afectado_id IS NOT NULL)),
  CHECK ((evento = 'NOMBRE_CAMBIADO') = (valor IS NOT NULL))
)

-- ── Archivos ──────────────────────────────────────────────────────────────
archivos (
  id uuid PK DEFAULT uuidv7(),                 -- se guarda en {app.archivos.raiz}/{id}
  subido_por uuid NOT NULL → usuarios,
  nombre_original varchar(255) NOT NULL,
  tipo_mime varchar(100) NOT NULL,
  tamano_bytes bigint NOT NULL CHECK (tamano_bytes > 0),
  creado_en timestamptz NOT NULL DEFAULT now()
)
-- índice: archivos (creado_en)

imagenes (
  archivo_id uuid PK → archivos ON DELETE CASCADE,
  ancho int NOT NULL CHECK (ancho > 0),
  alto int NOT NULL CHECK (alto > 0)
)

-- ── Notificaciones ────────────────────────────────────────────────────────
suscripciones_push (
  id bigint PK, usuario_id → usuarios ON DELETE CASCADE NOT NULL,
  endpoint varchar(1000) NOT NULL UNIQUE, p256dh varchar(200) NOT NULL, auth varchar(50) NOT NULL,
  creado_en
)
```

**Notas**

- **Subtipos exclusivos:** el `tipo` del supertipo forma parte de la FK del subtipo con un valor fijo por `CHECK`.
  Así una conversación no puede ser grupo y chat directo a la vez, ni un mensaje adjunto y evento. Que exista la
  fila del subtipo cuando corresponde (un `ADJUNTO` con su fila en `adjuntos`) lo garantiza el servicio, que
  inserta ambas en la misma transacción.
- **Orden de `usuario_a_id < usuario_b_id`:** lo calcula PostgreSQL con `LEAST`/`GREATEST` al insertar. No se usa
  `UUID.compareTo` de Java, que compara con signo y da otro orden.
- **Remitente de un evento:** es quien hizo la acción. Las filas de `miembros` se conservan aunque la persona
  salga (su periodo queda cerrado), así que la FK del remitente siempre se cumple; que el remitente de un mensaje
  nuevo tenga un periodo abierto lo verifica el servicio.
- **`id_cliente`** lo genera el celular para los mensajes del usuario y el servidor para los eventos.
- **Periodos:** `desde_secuencia` y `hasta_secuencia` son inclusivos. La restricción `EXCLUDE` impide periodos
  solapados, y con ello más de un periodo abierto por miembro.
- **Referencia circular** `usuarios.foto_id` ↔ `archivos.subido_por`: se crean las tablas y luego se agrega la FK
  con `ALTER TABLE`.
- **Lo que no se guarda:** quién está en línea y quién escribe viven en memoria; solo `ultima_conexion` se guarda,
  al cerrarse la última sesión.

**Justificación de 3FN**

- **1FN:** no hay valores compuestos (el par de usuarios de un chat directo son dos columnas; los eventos de grupo
  tienen su tabla en lugar de un `jsonb`) ni grupos repetidos (cada periodo de membresía es una fila).
- **2FN:** en las tablas con clave compuesta (`miembros`) todo depende de la clave completa: rol y marcas de agua
  son de ese usuario en esa conversación.
- **3FN:** no hay dependencias transitivas ni datos calculables. Se eliminaron `ultima_secuencia` (es el máximo de
  `mensajes.secuencia`), `archivos.conversacion_id` (se deduce del mensaje), `archivos.ruta` (se deduce del id) y
  el tipo IMAGEN/ARCHIVO del mensaje (se deduce de `imagenes`). Los atributos que solo aplican a una variante
  viven en su subtipo (`grupos`, `imagenes`). Los discriminadores `tipo` son declarados, no calculados, y las FK
  compuestas los mantienen consistentes.
- **`expira_en`** no es un dato derivado: fija la política vigente al emitir el token, que puede cambiar después.
- **Única redundancia controlada:** los dos usuarios de `chats_directos` también aparecen en `miembros`. Hace
  falta para la restricción única del par; las FK compuestas hacia `miembros` impiden inconsistencias.
- **Listas de valores fijos:** `CHECK` cuando el valor no tiene atributos propios (`rol`, `tipo`, `evento`,
  `proposito`). Si un valor ganara atributos (por ejemplo, permisos configurables por rol de grupo), pasaría a una
  tabla catálogo como en reservas-app.

## 7. Protocolo en tiempo real

### 7.1 Conexión y seguridad

- Endpoint `/ws`: STOMP 1.2 sobre WebSocket nativo (sin SockJS), con `setAllowedOrigins(app.url-publica)`.
  Broker simple en memoria de Spring (un solo servidor).
- El frame `CONNECT` debe traer `Authorization: Bearer <jwt>`. Un `ChannelInterceptor` lo valida con el mismo
  `JwtDecoder` de la API y fija el `Principal` (su nombre es el id del usuario). Sin token o con uno inválido →
  frame `ERROR` y cierre.
- **Latidos** cada `app.tiempo-real.latido` en ambos sentidos. Mensajes entrantes de hasta 64 KB.
- **Renovación:** el cliente envía `/app/sesion.renovar` con el header `Authorization` un minuto antes del `exp`.
  Si el token es válido y del mismo usuario, se actualiza el vencimiento de la sesión. Una tarea cada 60 s cierra
  las sesiones vencidas; el cliente reconecta con un token nuevo.
- `RegistroSesiones` (en memoria): usuario → sesiones abiertas con su vencimiento. Sirve para la presencia, para
  decidir los push y para cerrar todas las sesiones de un usuario (restablecer contraseña).
- **Autorización de frames:** `SUBSCRIBE` solo a `/user/queue/eventos`; `SEND` solo a los destinos de 7.2.
  Cualquier otro frame se rechaza con `ERROR`.

### 7.2 Destinos

Todo lo que el servidor envía va a la cola personal `/user/queue/eventos`, que Spring entrega a todas las
sesiones del usuario (celular y PC a la vez). El servidor decide quién recibe qué; no hay canales por
conversación que autorizar.

| Cliente → servidor (`SEND`) | Cuerpo |
|---|---|
| `/app/mensajes.enviar` | `{idCliente, conversacionId, texto?, archivoId?}` |
| `/app/mensajes.entregados` | `{conversacionId, hastaSecuencia}` |
| `/app/mensajes.leidos` | `{conversacionId, hastaSecuencia}` |
| `/app/escribiendo` | `{conversacionId}` |
| `/app/sesion.renovar` | sin cuerpo; token en el header `Authorization` |

### 7.3 Eventos (servidor → cliente)

Todos son JSON con un campo `tipo`:

| `tipo` | Campos | Quién lo recibe |
|---|---|---|
| `MENSAJE_NUEVO` | `mensaje: MensajeDto` | Los miembros que pueden ver esa secuencia, incluido el remitente en todos sus dispositivos |
| `ESTADO_ACTUALIZADO` | `conversacionId, usuarioId, ultimaEntregada, ultimaLeida` | Los miembros activos de la conversación |
| `ESCRIBIENDO` | `conversacionId, usuarioId` | Los miembros activos conectados, menos quien escribe |
| `PRESENCIA` | `usuarioId, enLinea, ultimaConexion` | Los usuarios conectados que tienen un chat directo con esa persona |
| `CONVERSACION_ACTUALIZADA` | `conversacionId` | Los afectados por un cambio de grupo, incluidos quien entra y quien sale; el cliente vuelve a pedir la lista y el detalle |
| `ERROR` | `idCliente?, codigo, detalle` | La sesión que envió el frame rechazado |

```json
{
  "tipo": "MENSAJE_NUEVO",
  "mensaje": {
    "conversacionId": "0192d1c4-…", "secuencia": 58, "idCliente": "6f1c2a9e-…",
    "remitenteId": "0192d1a0-…", "tipo": "ADJUNTO", "texto": "Mirá el atardecer en Tamarindo",
    "archivo": { "id": "0192d1c9-…", "nombre": "IMG_2041.jpg", "tipoMime": "image/jpeg",
                 "tamanoBytes": 284113, "ancho": 1600, "alto": 1200 },
    "evento": null,
    "creadoEn": "2026-09-27T20:05:13Z"
  }
}
```

En un `EVENTO`, `evento` es `{evento, afectadoId, valor}` y `texto` y `archivo` son `null`. En imágenes,
`archivo` trae `ancho` y `alto`; en los demás archivos vienen en `null`.

### 7.4 Comportamiento del cliente

- **Enviar:** `idCliente = crypto.randomUUID()`. El mensaje se guarda en la **bandeja de salida** (IndexedDB:
  `{idCliente, conversacionId, texto, archivoId, blob?, creadoEn, estado}`) y se muestra con 🕓. Si hay conexión,
  se envía. Al llegar el `MENSAJE_NUEVO` con ese `idCliente`, se borra de la bandeja.
- **Adjuntos pendientes:** la bandeja guarda el `Blob` hasta que la subida (`POST /api/archivos`) devuelve el
  `archivoId`; recién entonces se envía el mensaje.
- **Reintentos:** al reconectar se reenvía la bandeja en orden de creación. Si un mensaje no se confirma en 15 s
  con la conexión abierta, se reenvía (es idempotente).
- **Errores:** un `ERROR` con `idCliente` marca el mensaje con ❗ y ofrece "Reintentar" (mismo `idCliente`) o
  "Eliminar". Los errores de validación no se reintentan solos; los de red, sí.
- **Recepción:** se descartan duplicados por `(conversacionId, secuencia)`. Si llega una secuencia mayor que la
  última conocida + 1, se pide el hueco con `?despuesDe=`.
- **Reconexión:** espera de 1, 2, 4, 8, 16 y luego 30 s, con ±20 % aleatorio; también se intenta al recibir el
  evento `online` o al volver a hacerse visible la pestaña. Orden al reconectar:
  1. `CONNECT` y `SUBSCRIBE` (primero, para no perder nada mientras se sincroniza).
  2. `GET /api/conversaciones`; en cada conversación con mensajes en caché y `ultimaSecuencia` mayor que la
     conocida, `GET …/mensajes?despuesDe=`.
  3. Reenviar la bandeja de salida.
- **Acuses:** al recibir mensajes de otros se envía `entregados` con la secuencia máxima de cada conversación,
  agrupando cada 500 ms. Con el chat visible (ruta abierta y `document.visibilityState === 'visible'`) se envía
  `leidos` hasta la última secuencia: al abrir el chat, al recibir mensajes y al volver a la pestaña.
- **Escribiendo:** al teclear se envía como máximo un aviso cada 3 s. El indicador se muestra 5 s desde el último
  aviso y se oculta al llegar un mensaje de esa persona.

### 7.5 Presencia

- **En línea** = al menos una sesión abierta en `RegistroSesiones`.
- Al pasar de 0 a 1 sesiones se emite `PRESENCIA` con `enLinea: true`. Al pasar de 1 a 0 se guarda
  `ultima_conexion = ahora` y se emite `PRESENCIA` con `enLinea: false` y `ultimaConexion`.
- La presencia solo la ven quienes tienen un chat directo con esa persona; el estado inicial viene en
  `GET /api/conversaciones`.

### 7.6 Cuándo se envía una notificación push

`EntregaPush` escucha `MensajeEnviado` (después del commit, en un ejecutor asíncrono) solo para mensajes `TEXTO`
y `ADJUNTO`. Para cada destinatario (miembro activo que no es el remitente):

- **Sin sesiones abiertas** → push inmediato.
- **Con sesiones abiertas** → se programa una revisión a los `app.push.espera-entrega`; si su `ultima_entregada`
  sigue siendo menor que la secuencia, se envía el push. Cubre el caso del celular que suspendió el WebSocket
  antes de que el servidor lo notara. La revisión vive en memoria: si el servidor se reinicia justo en esos 10 s,
  ese push se pierde (aceptado; el mensaje sí queda guardado).
- **App abierta en segundo plano:** la propia página recibe el `MENSAJE_NUEVO`, envía el acuse y muestra la
  notificación con `registration.showNotification`, así que el servidor no envía push.

## 8. Lógica clave

Todo "ahora" viene de un `Clock` inyectado (fijo en los tests). Toda operación que agrega mensajes a una
conversación, incluidos los eventos de grupo, empieza con `SELECT … FROM conversaciones WHERE id = ? FOR UPDATE`:
serializa las escrituras de cada conversación y evita deadlocks, porque el primer bloqueo siempre es ese.

### 8.1 Enviar un mensaje (`EnvioMensajesService`)

Validaciones previas, sin transacción:

1. Estructura: `idCliente` y `conversacionId` presentes; `texto` de 1 a 4096 caracteres o `archivoId` presente →
   si no, `VALIDACION` o `TEXTO_MUY_LARGO`.
2. Límite de frecuencia del usuario (`limite-cantidad` en `limite-ventana`, en memoria) → `DEMASIADOS_MENSAJES`.

En una transacción:

3. `FOR UPDATE` de la conversación → si no existe, `NO_ENCONTRADO`.
4. El remitente tiene un periodo abierto → si no, `NO_ES_MIEMBRO`.
5. **Idempotencia:** si ya existe un mensaje con `(remitente_id, id_cliente)`, se devuelve ese mensaje sin
   insertar nada y se le reenvía el `MENSAJE_NUEVO` solo al remitente.
6. Si hay `archivoId`: el archivo existe, lo subió el remitente y no está en `adjuntos` → si no,
   `ARCHIVO_INVALIDO`.
7. `secuencia = COALESCE(MAX(secuencia), 0) + 1` de la conversación (usa el índice único).
8. Insertar el mensaje (`TEXTO` o `ADJUNTO`) y, si corresponde, su fila en `adjuntos`.
9. Avanzar las marcas del remitente: `ultima_entregada` y `ultima_leida` = `GREATEST(actual, secuencia)`. Sus
   propios mensajes no cuentan como no leídos y responder implica haber leído lo anterior.
10. Publicar `MensajeEnviado`.

Si aun así salta la restricción única `(remitente_id, id_cliente)` (SQLState `23505`), se trata como el paso 5.
Después del commit, `EntregaTiempoReal` envía `MENSAJE_NUEVO` y `EntregaPush` decide los push (7.6).

### 8.2 Marcar entregados y leídos

- `hastaSecuencia` se limita a la mayor secuencia que el usuario puede ver en esa conversación.
- **Entregados:** `ultima_entregada = GREATEST(ultima_entregada, hasta)`.
- **Leídos:** `ultima_leida = GREATEST(ultima_leida, hasta)` y `ultima_entregada = GREATEST(ultima_entregada,
  hasta)`.
- Las marcas nunca bajan. Si no cambian, no se emite nada; si cambian, se emite `ESTADO_ACTUALIZADO` a los
  miembros activos (incluidos los otros dispositivos del mismo usuario, para actualizar los no leídos).
- Un usuario sin periodo abierto recibe `NO_ES_MIEMBRO`.

### 8.3 Estado de un mensaje propio (se calcula en el cliente)

Para un mensaje propio con secuencia `s`, los destinatarios son los otros miembros con un periodo que contiene
`s`:

- 🕓 pendiente: está en la bandeja de salida.
- ✓ enviado: confirmado por el servidor.
- ✓✓ entregado: todos los destinatarios tienen `ultimaEntregada ≥ s`.
- ✓✓ celeste, leído: todos los destinatarios tienen `ultimaLeida ≥ s`.

Si un destinatario sale del grupo sin haber leído, ese mensaje queda como entregado.

### 8.4 Chat directo (`POST /api/conversaciones/directas`)

1. `usuarioId` existe y está verificado (404) y no es uno mismo (422 `CHAT_CONSIGO_MISMO`).
2. Si ya existe la fila en `chats_directos` para el par → 200 con ese chat.
3. Si no, en una transacción: insertar la conversación `DIRECTA`, los dos `miembros`, un periodo abierto para cada
   uno desde la secuencia 1 y la fila en `chats_directos` (el par se ordena con `LEAST`/`GREATEST`) → 201.
4. Si dos personas lo crean a la vez, la restricción única del par hace fallar una transacción; se revierte, se
   lee el chat existente y se responde 200.

### 8.5 Grupos (`GestionMiembros` y servicio de grupos)

Cada cambio inserta un mensaje `EVENTO` con su fila en `eventos_grupo`, en la misma transacción. Después del
commit se envía `MENSAJE_NUEVO` a quienes pueden ver esa secuencia y `CONVERSACION_ACTUALIZADA` a los afectados.

- **Crear** `{nombre, descripcion?, fotoId?, miembrosIds}`: `miembrosIds` tiene de 1 a `max-miembros − 1`
  usuarios distintos, verificados y distintos de quien crea. Se insertan la conversación, el grupo, los miembros
  (quien crea como `ADMIN`), el evento `GRUPO_CREADO` con secuencia 1 y un periodo abierto desde 1 para todos.
- **Agregar** (admin): el usuario existe y está verificado; no tiene un periodo abierto (409 `YA_ES_MIEMBRO`);
  los miembros activos son menos que `max-miembros` (409 `GRUPO_LLENO`). Evento `MIEMBRO_AGREGADO` con secuencia
  `s`; se crea o reutiliza su fila en `miembros` (con rol `MIEMBRO`) y se abre un periodo desde `s`.
- **Quitar** (admin): no se puede quitar a uno mismo (se usa "salir"). Evento `MIEMBRO_QUITADO` con secuencia `s`;
  se cierra su periodo con `hasta_secuencia = s` y su rol vuelve a `MIEMBRO`.
- **Salir** (miembro activo): evento `MIEMBRO_SALIO` con secuencia `s`; se cierra su periodo y su rol vuelve a
  `MIEMBRO`. Si no quedan administradores entre los miembros activos y queda alguien, el miembro activo con el
  periodo abierto más antiguo pasa a `ADMIN` con el evento `ADMIN_ASIGNADO` (secuencia `s + 1`; el remitente es
  quien salió).
- **Hacer admin / quitar admin** (admin): eventos `ADMIN_ASIGNADO` / `ADMIN_QUITADO`. No se puede dejar el grupo
  sin administradores (409 `ULTIMO_ADMIN`).
- **Editar** (admin) `{nombre?, descripcion?, fotoId?}`: `NOMBRE_CAMBIADO` (con `valor` = nombre nuevo) y
  `FOTO_CAMBIADA`. La descripción cambia sin evento. `fotoId` debe ser una imagen subida por quien edita.
- Acciones de admin hechas por quien no lo es → 403 `SIN_PERMISO`. Hechas por quien no es miembro activo → 403
  `NO_ES_MIEMBRO`.

### 8.6 Archivos

**Subida** `POST /api/archivos` (multipart: `archivo`, `tipo` = `IMAGEN` | `ARCHIVO`) → 201 `ArchivoDto`
`{id, nombre, tipoMime, tamanoBytes, ancho, alto}`:

- **`IMAGEN`:** el cliente ya la comprimió (lado mayor ≤ 1600 px, JPEG, calidad 0,8; perfil y grupo: cuadrado de
  512 px). El servidor exige ≤ `max-imagen` (413 `ARCHIVO_MUY_GRANDE`) y la lee con `ImageIO`: si no es un JPEG
  válido, 422 `ARCHIVO_INVALIDO`. De ahí toma `ancho` y `alto` e inserta la fila en `imagenes`. Al recomprimir en
  el cliente se pierden los metadatos EXIF, incluida la ubicación GPS.
- **`ARCHIVO`:** cualquier tipo hasta `max-archivo`. El nombre se limpia (sin rutas ni caracteres de control, ≤ 255)
  y el tipo MIME del cliente se guarda solo como dato; si no es válido, `application/octet-stream`.
- El contenido se guarda con `AlmacenArchivos.guardar(id, InputStream)` y la fila después; si falla la
  transacción, se borra el archivo.

**Descarga** `GET /api/archivos/{id}`. Tiene acceso quien cumple alguna condición:

1. Lo subió.
2. Es la foto de un usuario (`usuarios.foto_id`) o de un grupo (`grupos.foto_id`).
3. Está en `adjuntos` de un mensaje que un periodo de ese usuario le permite ver.

Si no → 404 (no se revela que existe). Respuesta con `Cache-Control: private, max-age=31536000, immutable` y
`X-Content-Type-Options: nosniff`. Las imágenes van como `image/jpeg` en línea; los demás archivos siempre con
`Content-Disposition: attachment` (con `filename*` según RFC 5987), así el navegador nunca los abre directamente.

**Contrato de `AlmacenArchivos`:** `guardar(id, InputStream)`, `abrir(id): InputStream`, `borrar(id)`, `existe(id)`.
`AlmacenDisco` guarda en `{app.archivos.raiz}/{id}` y escribe primero en un temporal que luego renombra, para que
nunca quede un archivo a medio escribir.

### 8.7 Notificaciones push

- **Suscripción:** `POST /api/push/suscripciones` `{endpoint, keys: {p256dh, auth}}` hace un *upsert* por
  `endpoint`: si ese endpoint estaba asociado a otro usuario (un dispositivo compartido), se reasigna.
- **Envío** (`EnviadorPush`, implementado con webpush-java y VAPID): TTL de 24 h, urgencia `high`. Payload
  `{conversacionId, secuencia, titulo, cuerpo}`:
  - Chat directo: título = nombre del remitente; cuerpo = texto (máx. 100 caracteres), "📷 Foto" o
    "📎 {nombre}".
  - Grupo: título = nombre del grupo; cuerpo = "Ana: …".
- Respuesta `404` o `410` del servicio de push → se borra la suscripción. Otros errores → log y sin reintento.
- El contenido viaja cifrado de extremo a extremo hasta el navegador (lo exige el estándar Web Push); el servicio
  de Google, Apple o Mozilla no puede leerlo.

### 8.8 Tareas programadas

| Cuándo | Tarea |
|---|---|
| Cada 60 s | Cerrar las sesiones WebSocket con el token vencido |
| 04:00 | Borrar refresh tokens vencidos y tokens de correo vencidos o usados hace más de 7 días |
| 04:10 | Borrar cuentas sin verificar con más de `dias-sin-verificar` |
| 04:20 | Borrar archivos huérfanos: más de `horas-huerfanos`, fuera de `adjuntos` y sin uso como foto de usuario o grupo. Se borra la fila y, después del commit, el contenido; los fallos van al log |

## 9. API REST

**Convenciones:** JSON en `camelCase`. Las fechas viajan en UTC (ISO-8601, `Instant`) y el frontend las muestra
en la zona del dispositivo. Todas las rutas requieren sesión salvo las marcadas con "—".

### 9.1 Autenticación y usuarios

| Método | Ruta | Requiere | Notas |
|---|---|---|---|
| POST | `/api/auth/registro` | — | 201 sin sesión (5.2) |
| POST | `/api/auth/verificar` | — | Igual que el login |
| POST | `/api/auth/reenviar-verificacion` | — | 204 siempre |
| POST | `/api/auth/login` | — | `{accessToken, usuario}` + cookie |
| POST | `/api/auth/refresh` | cookie | `{accessToken, usuario}` + cookie nueva |
| POST | `/api/auth/logout` | cookie | `{endpointPush?}` → 204 |
| POST | `/api/auth/recuperar` | — | 204 siempre |
| POST | `/api/auth/restablecer` | — | 204 |
| GET | `/api/usuarios/yo` | sesión | `UsuarioPropioDto` |
| PATCH | `/api/usuarios/yo` | sesión | `{nombreVisible?, info?, fotoId?, nombreUsuario?}` |
| POST | `/api/usuarios/yo/codigo-invitacion` | sesión | Regenera el código |
| GET | `/api/usuarios/buscar?nombreUsuario=` | sesión | Coincidencia exacta o 404 |
| GET | `/api/usuarios/{id}` | sesión | `PerfilPublicoDto` |
| GET | `/api/invitaciones/{codigo}` | sesión | `PerfilPublicoDto` o 404 |

### 9.2 Conversaciones y mensajes

| Método | Ruta | Requiere | Notas |
|---|---|---|---|
| GET | `/api/conversaciones` | sesión | `[ConversacionResumenDto]` ordenada por el último mensaje, de más reciente a más antiguo. Incluye las conversaciones de las que el usuario ya salió. Sin paginación (decenas de chats) |
| GET | `/api/conversaciones/{id}` | miembro actual o pasado | `ConversacionDetalleDto` |
| GET | `/api/conversaciones/{id}/mensajes?antesDe=&despuesDe=&limite=` | miembro actual o pasado | `{mensajes: [MensajeDto], hayMas}` en orden ascendente |
| POST | `/api/conversaciones/directas` | sesión | `{usuarioId}` → 200 (existía) o 201 (nueva) con `ConversacionDetalleDto` |

- **`ConversacionResumenDto`:** `{id, tipo, titulo, fotoId, otroUsuario, ultimoMensaje, ultimaSecuencia,
  noLeidos, activa}`. `titulo` es el nombre del grupo o el `nombreVisible` del otro usuario. `otroUsuario` (solo
  en `DIRECTA`) es `PerfilPublicoDto` + `enLinea` + `ultimaConexion`. `activa` indica si el usuario tiene un
  periodo abierto.
- **`noLeidos`:** mensajes `TEXTO` y `ADJUNTO` visibles con secuencia mayor que la `ultima_leida` propia.
- **`ConversacionDetalleDto`:** `{id, tipo, titulo, descripcion, fotoId, activa, miRol, miembros: [{usuario:
  PerfilPublicoDto, rol, ultimaEntregada, ultimaLeida, periodos: [{desde, hasta}]}]}`. Incluye a los ex miembros,
  porque sus periodos cuentan para el estado de los mensajes (8.3); la interfaz solo lista a los activos.
- **Paginación keyset por `secuencia`:** `antesDe=N` devuelve los `limite` mensajes visibles anteriores a `N` (sin
  `antesDe`, los más recientes); `despuesDe=N`, los posteriores a `N`. `limite` por defecto 50, máximo 200. Nunca
  `OFFSET`.
- **Visibilidad:** toda consulta de mensajes filtra con `EXISTS` sobre `periodos_membresia` del usuario
  (`desde_secuencia ≤ secuencia ≤ COALESCE(hasta_secuencia, ∞)`).
- Quien nunca fue miembro de la conversación recibe 404 (no se revela que existe).
- Los mensajes se envían solo por STOMP (7.2); no hay `POST` REST de mensajes.

### 9.3 Grupos, archivos y push

| Método | Ruta | Requiere | Notas |
|---|---|---|---|
| POST | `/api/grupos` | sesión | `{nombre, descripcion?, fotoId?, miembrosIds}` → 201 `ConversacionDetalleDto` |
| PATCH | `/api/grupos/{id}` | admin | `{nombre?, descripcion?, fotoId?}` |
| POST | `/api/grupos/{id}/miembros` | admin | `{usuarioId}` → 204 |
| DELETE | `/api/grupos/{id}/miembros/{usuarioId}` | admin | 204 |
| PUT | `/api/grupos/{id}/administradores/{usuarioId}` | admin | 204 |
| DELETE | `/api/grupos/{id}/administradores/{usuarioId}` | admin | 204 o 409 `ULTIMO_ADMIN` |
| POST | `/api/grupos/{id}/salir` | miembro activo | 204 |
| POST | `/api/archivos` | sesión | multipart → 201 `ArchivoDto` (8.6) |
| GET | `/api/archivos/{id}` | ver 8.6 | Contenido binario |
| GET | `/api/push/clave-publica` | — | `{clave}` (VAPID pública) |
| POST | `/api/push/suscripciones` | sesión | `{endpoint, keys: {p256dh, auth}}` → 204 |
| DELETE | `/api/push/suscripciones` | sesión | `{endpoint}` → 204 |

## 10. Frontend

### 10.1 Rutas

| Ruta | Acceso | Contenido |
|---|---|---|
| `/` | Sesión | Lista de chats. Desde 1024 px: lista a la izquierda y chat a la derecha ("Elegí un chat" si no hay ninguno abierto) |
| `/chats/:id` | Sesión | Chat abierto (7.4, 8.3) |
| `/chats/:id/info` | Sesión | Información del contacto o del grupo: miembros activos, roles, acciones de admin, salir |
| `/nuevo` | Sesión | Buscar por `@usuario` o crear grupo |
| `/grupos/nuevo` | Sesión | Elegir miembros (chats directos recientes y búsqueda), nombre y foto |
| `/perfil` | Sesión | Foto, nombre, info, `@usuario`, mi QR y mi link (copiar, compartir, regenerar), estado de las notificaciones, cerrar sesión |
| `/i/:codigo` | Todos | Invitación (5.3) |
| `/login`, `/registro`, `/revisa-tu-correo`, `/verificar`, `/recuperar`, `/restablecer` | Sin sesión | Acceso |

### 10.2 Comportamiento

- **Sesión:** igual que reservas-app. El `AuthProvider` guarda el access token y el usuario en memoria y, al abrir
  la app, intenta un refresh. Refresh único compartido: todos los 401 simultáneos esperan la misma llamada y
  reintentan una vez; si falla con 401, se cierra la sesión.
- **Sin conexión al abrir:** si el refresh falla por la red (no por 401), la app entra en modo sin conexión:
  muestra los datos persistidos y la bandeja de salida, y reintenta al volver la conexión.
- **Datos:** TanStack Query es la única fuente de verdad de los datos del servidor. Los eventos del WebSocket
  actualizan su caché con `setQueryData`; `CONVERSACION_ACTUALIZADA` invalida la lista y el detalle.
- **Persistencia:** la caché de TanStack Query se persiste en IndexedDB (lista de chats y mensajes ya cargados,
  hasta 7 días). Al cerrar sesión se borran esa caché y la bandeja de salida.
- **Estado efímero** (Zustand): estado de la conexión, presencia y "escribiendo…".
- **Chat:** separadores "Hoy", "Ayer" y fecha; eventos de grupo centrados; las imágenes reservan su espacio con
  `ancho`/`alto`; scroll hacia arriba carga páginas anteriores; al abrir salta al primer no leído.
- **Archivos protegidos:** `useArchivoProtegido(id)` descarga con `fetch` y el header `Authorization`, crea un
  blob URL y lo revoca al desmontar.
- **Formularios:** React Hook Form + Zod, con las mismas reglas que el backend.
- **Formato:** `Intl.DateTimeFormat('es-CR')`: "14:32", "Ayer", "lun.", "12 de septiembre".

### 10.3 PWA y notificaciones

- **Manifest:** `name` y `short_name` "HelloCR", `display: standalone`, `theme_color #9146FF`, `background_color
  #0E0E10`, íconos de 192 y 512 px, incluida la versión *maskable*.
- **Service worker** (`sw.ts`, con `injectManifest`): precachea el *app shell* (no las respuestas de la API);
  maneja `push` (muestra la notificación con `tag = conversacionId`, así los mensajes de un chat se agrupan) y
  `notificationclick` (enfoca una ventana abierta y navega al chat, o abre `/chats/{id}`).
- **Actualizaciones:** aviso "Nueva versión disponible · Recargar".
- **Activar notificaciones:** tras el login se muestra un banner; el permiso se pide recién al tocarlo (iOS lo
  exige). Con permiso: `pushManager.subscribe` con la clave de `/api/push/clave-publica` y
  `POST /api/push/suscripciones`.
- **iPhone sin instalar:** si Safari no está en modo `standalone`, el banner explica "Compartir → Agregar a
  inicio", porque iOS solo permite push en PWAs instaladas.
- **App abierta en segundo plano:** la página muestra la notificación con `registration.showNotification` cuando
  llega un mensaje de otro en un chat que no está visible.

### 10.4 Tema

Tema oscuro por defecto, al estilo de Twitch; el claro se activa con `prefers-color-scheme: light`. Tokens como
variables CSS en `@theme` de Tailwind v4. Contrastes verificados según WCAG AA (texto ≥ 4,5:1; íconos ≥ 3:1).

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `primario` | `#9146FF` | `#772CE8` | Botones, badges, enlaces; texto blanco encima (4,6:1 y 6,3:1) |
| `fondo` | `#0E0E10` | `#F7F7F8` | Fondo de la app |
| `superficie` | `#18181B` | `#FFFFFF` | Barras, lista de chats, compositor |
| `burbuja-propia` | `#5C16C5` | `#772CE8` | Tus mensajes, texto blanco |
| `burbuja-ajena` | `#26262C` | `#EFEFF1` | Mensajes de los demás, texto con el token `texto` |
| `texto` | `#EFEFF1` | `#0E0E10` | Texto principal |
| `texto-secundario` | `#ADADB8` | `#53535F` | Horas, vistas previas |
| `acento-suave` | `#BF94FF` | `#772CE8` | "escribiendo…", "CR" del logotipo |
| `check` | `#B9A6E0` | `#C9B3F5` | ✓ y ✓✓ sobre la burbuja propia |
| `leido-burbuja` | `#5CE1E6` | `#5CE1E6` | ✓✓ leído sobre la burbuja propia (5,6:1 y 4,0:1) |
| `leido` | `#5CE1E6` | `#0E8A92` | ✓✓ leído sobre `superficie` (lista de chats) |

Los checks llevan además `aria-label` ("Pendiente", "Enviado", "Entregado", "Leído"), para no depender solo del
color.

### 10.5 Textos

Voseo tico en toda la interfaz: "Escribí un mensaje", "Revisá tu correo", "Elegí un chat", "Activá las
notificaciones". Mensajes de error que dicen qué pasó y qué hacer, sin "Error:" al inicio.

## 11. Manejo de errores

**REST:** `ProblemDetail` (RFC 9457) con el campo adicional `codigo`, y `errores` (por campo) en las validaciones,
igual que reservas-app. Los `detail` están en español, en voseo y listos para mostrar.

| HTTP | Códigos |
|---|---|
| 400 | `VALIDACION` (con `errores: {campo: mensaje}`) |
| 401 | `NO_AUTENTICADO`, `CREDENCIALES_INVALIDAS` |
| 403 | `SIN_PERMISO`, `NO_ES_MIEMBRO`, `CORREO_NO_VERIFICADO` |
| 404 | `NO_ENCONTRADO` |
| 409 | `CORREO_EN_USO`, `NOMBRE_USUARIO_EN_USO`, `YA_ES_MIEMBRO`, `GRUPO_LLENO`, `ULTIMO_ADMIN` |
| 413 | `ARCHIVO_MUY_GRANDE` |
| 422 | `TOKEN_INVALIDO`, `NOMBRE_USUARIO_RESERVADO`, `CHAT_CONSIGO_MISMO`, `ARCHIVO_INVALIDO` |
| 429 | `DEMASIADOS_INTENTOS` (con `Retry-After`) |
| 500 | `ERROR_INTERNO` (mensaje genérico; el detalle solo en el log) |

**STOMP:** un `@MessageExceptionHandler` convierte `ErrorNegocio` en un evento `ERROR` `{idCliente?, codigo,
detalle}` para la sesión que envió el frame. Códigos: `VALIDACION`, `TEXTO_MUY_LARGO`, `NO_ENCONTRADO`,
`NO_ES_MIEMBRO`, `ARCHIVO_INVALIDO`, `DEMASIADOS_MENSAJES`, `ERROR_INTERNO`. Los fallos de autenticación del
`CONNECT` y los frames no permitidos se responden con un frame `ERROR` de STOMP y se cierra la conexión.

**Frontend:** los errores de validación van debajo de cada campo; el resto, en un aviso. En el chat, un mensaje
rechazado muestra ❗ con "Reintentar" y "Eliminar". Sin conexión, una barra fija dice "Sin conexión.
Reintentando…".

## 12. Tests

Se escriben antes que el código (TDD).

**Backend — unitarios (sin Spring, con `Clock` fijo y dependencias falsas)**

- Normalización y validación de `@usuario`, correo e identificador de login.
- Contador de intentos de login y límite de frecuencia de mensajes.
- Cálculo de destinatarios y de la decisión de push (sin sesión → inmediato; con sesión → revisión diferida).
- Contrato abstracto de `AlmacenArchivos`, ejecutado con `AlmacenDisco` sobre una carpeta temporal.

**Backend — integración (`@SpringBootTest` + MockMvc, base `hellocr_test`, tablas vaciadas antes de cada test)**

- **Auth:** registro → correo capturado → verificar → sesión; login sin verificar (403); registro que reemplaza
  una cuenta sin verificar; reenviar con espera; recuperar → restablecer revoca las sesiones; refresh con rotación
  y margen de 30 s; bloqueo tras 5 intentos.
- **Usuarios:** búsqueda exacta, invitaciones, regenerar código, cambio de `@usuario`.
- **Chats directos:** crear, reutilizar y creación simultánea del mismo par (un solo chat).
- **Grupos:** cada regla de 8.5 y cada código 403/409 tiene al menos un test.
- **Periodos (tabla de casos):** quien entra no ve lo anterior, quien sale no ve lo posterior, quien vuelve ve
  solo sus periodos; aplica a mensajes, conteo de no leídos y descarga de archivos.
- **Archivos:** JPEG válido e inválido, tamaños límite, reglas de acceso, `Content-Disposition`, limpieza de
  huérfanos.
- **Migraciones:** Flyway aplica todo sobre una base vacía (lo cubre el arranque del contexto).

**Backend — WebSocket (servidor en puerto aleatorio + `WebSocketStompClient`)**

- `CONNECT` sin token o con token vencido → rechazado. `SUBSCRIBE` a otra cola → rechazado.
- Ana envía y Luis recibe `MENSAJE_NUEVO`; los dos dispositivos de Ana también lo reciben.
- `entregados` y `leidos` actualizan las marcas y emiten `ESTADO_ACTUALIZADO`; las marcas nunca bajan.
- Presencia al conectar y desconectar; "escribiendo…" con su filtro de 2 s.
- `sesion.renovar` extiende la sesión; restablecer la contraseña cierra las sesiones.
- Push: con `EnviadorPush` falso y un planificador controlable, sin sesión → inmediato; con sesión y sin acuse →
  a los 10 s; con acuse → nada.

**Backend — concurrencia** (reutiliza la idea de `PruebaIntegracion.enParalelo` de reservas-app)

- 20 envíos simultáneos al mismo chat → secuencias exactamente 1..20, sin huecos ni duplicados.
- El mismo `idCliente` enviado 5 veces a la vez → un solo mensaje.
- Agregar miembros en paralelo a un grupo casi lleno → nunca se supera `max-miembros`.

**Frontend (Vitest + React Testing Library)**

- Lógica pura: estado del mensaje a partir de las marcas y los periodos; detección de huecos; eliminación de
  duplicados; agrupación de acuses.
- `BandejaSalida` con `fake-indexeddb`: guardar, reenviar en orden, confirmar, fallar.
- `ClienteTiempoReal` con un STOMP falso: orden de reconexión, renovación del token, reintento a los 15 s.
- Cliente HTTP: varios 401 simultáneos → un solo `/refresh`.
- Componentes: burbuja con cada estado, lista de chats con no leídos, banner de notificaciones en iOS sin
  instalar.

**E2E (Playwright, perfil `e2e` del backend con la base `hellocr_e2e`)**

El perfil `e2e` crea al arrancar las cuentas verificadas de Ana y Luis. Dos contextos de navegador a la vez:

1. Chat 1 a 1: Ana escribe, Luis lee, Ana ve ✓✓ celeste.
2. Sin conexión: Ana pierde la red (`context.setOffline(true)`), escribe, recupera la red y Luis recibe el
   mensaje una sola vez.
3. Grupo: Ana crea un grupo con Luis, lo quita y Luis ve el chat en modo solo lectura.

## 13. Despliegue

### 13.1 Servidor (Oracle Cloud Always Free)

- **VM:** Ampere A1 (ARM) con Ubuntu Server LTS; IP pública reservada (gratis) apuntada desde
  `hellocr.duckdns.org` (se configura una vez).
- **Instalación nativa:** JDK 25 (ARM), PostgreSQL 18 (repositorio PGDG) y Caddy. Base `hellocr` con su usuario,
  como en desarrollo.
- **Firewall:** abrir 80 y 443 en la *security list* de Oracle **y** en `iptables` de Ubuntu (las imágenes de
  Oracle traen reglas propias que los bloquean).
- **Reclamo de instancias inactivas:** según la política de Oracle (verificarla al crear la cuenta), las
  instancias Always Free con uso muy bajo de CPU, red y memoria durante 7 días pueden ser reclamadas. Solución:
  pasar la cuenta a *Pay As You Go* (sigue siendo gratis dentro de los límites Always Free) y crear una alerta
  de presupuesto de USD 1.

### 13.2 Servicios

- **Backend:** `/opt/hellocr/hellocr.jar` como `hellocr.service` (systemd), con el usuario de sistema `hellocr`,
  perfil `prod`, `Restart=on-failure`. Secretos en `/etc/hellocr/hellocr.env` (permisos 600): secreto JWT,
  contraseña de la base, contraseña de aplicación de Gmail y claves VAPID. Archivos en
  `/var/lib/hellocr/archivos`.
- **Actuator:** solo `health`, en `127.0.0.1:8081` (Caddy no lo expone).
- **IP del cliente:** Spring confía en `X-Forwarded-For` de Caddy (`server.forward-headers-strategy`), necesario
  para el bloqueo de login por IP.
- **Caddy:**

```
hellocr.duckdns.org {
    encode zstd gzip
    header {
        Strict-Transport-Security "max-age=31536000"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        Content-Security-Policy "default-src 'self'; img-src 'self' blob: data:; connect-src 'self' wss://hellocr.duckdns.org; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    }
    handle /api/* {
        reverse_proxy localhost:8080
    }
    handle /ws {
        reverse_proxy localhost:8080
    }
    handle {
        root * /var/www/hellocr
        header /assets/* Cache-Control "public, max-age=31536000, immutable"
        header /sw.js Cache-Control "no-cache"
        header /index.html Cache-Control "no-cache"
        try_files {path} /index.html
        file_server
    }
}
```

La CSP es el punto de partida; se ajusta al implementar si algo legítimo queda bloqueado.

### 13.3 Desplegar

`despliegue/desplegar.ps1`, desde la PC con Windows:

1. `./mvnw -q package` en `backend/` y `npm run build` en `frontend/`.
2. `scp` del `.jar` y de `dist/` al servidor.
3. `ssh`: mover los archivos, `systemctl restart hellocr` y esperar a que `curl 127.0.0.1:8081/actuator/health`
   responda `UP` (si no, mostrar las últimas líneas de `journalctl -u hellocr`).

Flyway aplica las migraciones al arrancar.

### 13.4 Backups

- `hellocr-backup.timer` (systemd), todos los días a las 03:00: `pg_dump -Fc` a `/var/backups/hellocr` y copia
  incremental de la carpeta de archivos (no cambian nunca, así que la copia es barata). Se conservan 7 dumps.
- Copia fuera del servidor con `rclone` a Object Storage de Oracle (20 GB gratis).
- La restauración (`pg_restore` en una base temporal) se prueba una vez al terminar el despliegue.

### 13.5 Respaldo si Oracle no acepta la cuenta

Backend en Render (plan gratuito), base en Neon y archivos en Cloudflare R2 con `AlmacenS3` (el disco de Render se
borra en cada despliegue). Consecuencia aceptada: tras unos 15 minutos sin uso el servidor se duerme y el primer
request tarda cerca de un minuto.

## 14. Entorno de desarrollo

**Requisitos:** JDK 25 · Node 24 · PostgreSQL 18 nativo (los mismos que la Task 0 de reservas-app).

**Base de datos** (una sola vez):

```sql
CREATE ROLE hellocr LOGIN PASSWORD '<tu-contraseña>';
CREATE DATABASE hellocr OWNER hellocr;
CREATE DATABASE hellocr_test OWNER hellocr;
CREATE DATABASE hellocr_e2e OWNER hellocr;
```

**Configuración**

- `backend/src/main/resources/application.yml`: valores por defecto, sin secretos.
- `backend/src/main/resources/application-local.yml`: URL y credenciales de la base, `app.jwt.secreto`, claves
  VAPID de desarrollo, `app.jwt.cookie-segura: false` y `EnviadorCorreoConsola`. **Ignorado por git**; en el
  repo queda `application-local.example.yml`.
- `backend/src/test/resources/application-test.yml`: apunta a `hellocr_test`; credenciales desde
  `TEST_DB_USER` y `TEST_DB_PASSWORD`, con `hellocr` como valor por defecto.
- **Claves VAPID:** `npx web-push generate-vapid-keys`, un par para desarrollo y otro para producción.

**Ejecutar**

- Backend: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (desde `backend/`).
- Frontend: `npm run dev` (desde `frontend/`), en `http://localhost:5173`. Las notificaciones push funcionan en
  `localhost` porque el navegador lo trata como origen seguro.

## 15. División en planes (propuesta)

Se confirma al escribir los planes; cada plan se escribe después de ejecutar el anterior, para apoyarse en el
código real.

1. **Backend base:** proyecto, configuración, errores, auth completa, correo, perfil, búsqueda, invitaciones y
   limpieza de cuentas y tokens.
2. **Backend conversaciones y tiempo real:** esquema de conversaciones y mensajes, chats directos, envío e
   historial, STOMP, marcas de agua, presencia, "escribiendo…" y grupos.
3. **Backend archivos y push:** `AlmacenArchivos` y su contrato, subida, descarga y acceso, fotos, huérfanos,
   suscripciones y `EntregaPush`.
4. **Frontend PWA.**
5. **Despliegue y E2E.**

## 16. Decisiones descartadas

| Alternativa | Por qué no |
|---|---|
| Node.js + Socket.IO | Ecosistema nuevo que no refuerza lo aprendido en reservas-app; Socket.IO resuelve solo la confirmación y la reconexión, que son parte de lo que se quiere aprender |
| Supabase | Casi no se aprende backend en tiempo real; el plan gratuito pausa proyectos inactivos; instalarlo en un servidor propio requiere Docker |
| App nativa (React Native) | Más complejidad y una cuenta de Apple Developer de pago; la PWA cubre la v1 |
| Teléfono + SMS | Cada SMS cuesta y exige un proveedor externo; correo + `@usuario` es gratis |
| Docker | Proyecto individual: la instalación nativa es más simple |
| RabbitMQ o Redis como broker | Un solo servidor; el broker simple alcanza. Con varias instancias se cambiaría a un relay STOMP |
| SockJS | Todos los navegadores objetivo soportan WebSocket |
| Estado por mensaje y destinatario | Crece como mensajes × miembros; las marcas de agua crecen solo con los miembros |
| Cifrado de extremo a extremo | Complica el historial en varios dispositivos, los grupos y la búsqueda; la conexión va cifrada con TLS |
| Firebase Cloud Messaging | Dependencia de Google; Web Push estándar funciona en todos los navegadores objetivo |
| URLs firmadas para archivos | `fetch` + blob reutiliza la misma autorización sin secretos extra |
| Imágenes en PostgreSQL (`bytea`) | Infla la base y los backups |
| Paginación con `OFFSET` | Keyset por secuencia es estable y rápida en cualquier página |
| Contador `ultima_secuencia` | Dato derivado (rompe 3FN); `FOR UPDATE` + `MAX + 1` con el índice único alcanza |
| H2 para tests | No soporta `uuidv7()`, `EXCLUDE` ni `btree_gist`; los tests corren contra PostgreSQL real |
| Render como destino principal | Se duerme sin uso; queda solo como respaldo |
| ✓✓ leído en lavanda | Poco distinguible de "entregado" sobre la burbuja morada; se eligió celeste |
