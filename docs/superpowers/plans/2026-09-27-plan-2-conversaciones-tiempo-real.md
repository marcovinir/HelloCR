# Plan 2 — Backend: conversaciones y tiempo real

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que dos o más personas chateen en tiempo real: chats directos y grupos, mensajes con secuencia por conversación, historial paginado, marcas de entregado y leído, presencia, "escribiendo…" y administración de grupos, todo sobre STOMP.

**Architecture:** Cuatro paquetes nuevos en `backend/`: `conversaciones` (membresía, periodos, chats directos, lectura), `mensajes` (envío, marcas, historial), `tiempoReal` (STOMP, sesiones, presencia, entrega de eventos) y `grupos`. El acceso a estas tablas es SQL explícito con `JdbcClient`: cada escritura en una conversación empieza con `SELECT … FOR UPDATE` y la visibilidad se decide con los periodos de membresía. Los servicios publican eventos de dominio (`MensajeEnviado`, `EstadoActualizado`, `ConversacionActualizada`) y `EntregaTiempoReal` los reparte por `/user/queue/eventos` recién después del commit.

**Tech Stack:** Java 25 · Spring Boot 4.1.1 (Spring 7, Jackson 3) · Spring WebSocket + STOMP con broker simple · `JdbcClient` · PostgreSQL 18 (`btree_gist`) · JUnit 5 + MockMvc + `WebSocketStompClient`.

**Spec:** `docs/superpowers/specs/2026-09-27-hellocr-design.md` (secciones 6 [conversaciones y mensajes], 7.1 a 7.5, 8.1 a 8.5, 9.2, 9.3 [grupos], 11 y 12).

Este es el **plan 2 de 5**. Se apoya en el código del plan 1 que ya está en `main` (auth, usuarios, correo, `PruebaIntegracion`).

## Global Constraints

- Java **25**, Spring Boot **4.1.1**, Maven con wrapper. PostgreSQL **18** nativo, **sin Docker**. Paquete raíz `com.hellocr`.
- Dominio en español; sufijos técnicos en inglés. Errores REST: `ProblemDetail` con `codigo` de `CodigoError` y `detail` en **voseo tico**. Errores STOMP: evento `ERROR` `{idCliente?, codigo, detalle}` a la sesión que envió el frame.
- Todo "ahora" viene del `Clock`, truncado a microsegundos (`Tiempos.ahora(clock)`), porque PostgreSQL guarda microsegundos.
- Toda operación que agrega mensajes a una conversación empieza con `SELECT … FROM conversaciones WHERE id = ? FOR UPDATE`. La secuencia es `COALESCE(MAX(secuencia), 0) + 1` de esa conversación.
- Visibilidad: un usuario ve la secuencia `s` si tiene un periodo con `desde_secuencia ≤ s ≤ COALESCE(hasta_secuencia, ∞)` (inclusivos).
- Texto de un mensaje: recortado, de 1 a **4096 caracteres** (contados como PostgreSQL: por punto de código). Límite: **30 mensajes cada 10 s** por usuario. "Escribiendo": el servidor ignora avisos del mismo usuario y chat con menos de **2 s** de diferencia. Grupos: hasta **50** miembros activos (5 en el perfil `test`).
- STOMP: endpoint `/ws` con origen permitido `app.url-publica`; `CONNECT` con `Authorization: Bearer <jwt>`; `SUBSCRIBE` solo a `/user/queue/eventos`; `SEND` solo a `/app/mensajes.enviar`, `/app/mensajes.entregados`, `/app/mensajes.leidos`, `/app/escribiendo` y `/app/sesion.renovar`; latido de **10 s**; mensajes entrantes de hasta **64 KB**.
- Todos los eventos del servidor son JSON con un campo `tipo`: `MENSAJE_NUEVO`, `ESTADO_ACTUALIZADO`, `ESCRIBIENDO`, `PRESENCIA`, `CONVERSACION_ACTUALIZADA`, `ERROR`.
- Los tests de integración corren contra PostgreSQL real (`hellocr_test`), nunca H2. TDD: el test se escribe y se ve fallar antes de implementar.

## Desvíos acordados respecto al spec

1. Los adjuntos (tabla `adjuntos`, `archivoId` en el envío, `archivo` en `MensajeDto`, `ARCHIVO_INVALIDO`), las fotos de grupo (`grupos.foto_id`, `fotoId`, evento `FOTO_CAMBIADA`) y `EntregaPush` llegan en el **plan 3**, junto con `archivos` y `suscripciones_push`.
2. Conversaciones, mensajes y grupos se acceden con SQL (`JdbcClient`) en lugar de entidades JPA: las consultas con `FOR UPDATE`, `GREATEST`, la visibilidad por periodos, `LATERAL` y la paginación keyset quedan escritas tal cual. `usuarios.ultima_conexion` también se lee y escribe con SQL, sin mapearla en la entidad `Usuario`.
3. Los eventos de grupo no avanzan las marcas de quien los hace (solo los mensajes de texto propios); de todos modos no cuentan como no leídos.
4. STOMP procesa en orden los frames de cada sesión (`setPreserveReceiveOrder(true)`) y publica en orden (`setPreservePublishOrder(true)`): si no, dos mensajes enviados seguidos podrían recibir secuencias invertidas. Esas dos opciones tienen dos efectos en Spring 7.0 que el código resuelve: una excepción en el interceptor solo queda en el log (por eso `AutenticacionStomp` manda el frame `ERROR` por el canal de salida) y un mismo mensaje no se puede repartir a varias sesiones (por eso `EnviadorEventosStomp` manda uno por sesión).
5. Tests en tiempo real: base `PruebaTiempoReal` (servidor en puerto aleatorio) y `ClienteStomp`, que espera a que el servidor registre la suscripción consultando `SimpUserRegistry`.
6. `app.grupos.max-miembros` vale 5 en el perfil `test`, para probar `GRUPO_LLENO` sin crear 50 cuentas.
7. `PATCH /api/grupos/{id}` responde 200 con el `ConversacionDetalle` (el spec no fija la respuesta).
8. Códigos que el spec no fija: quitarse a uno mismo de un grupo → 400 `VALIDACION`; actuar sobre alguien que no está en el grupo → 404 `NO_ENCONTRADO`; quien nunca fue miembro de un grupo → 404 (como en 9.2); ex miembro → 403 `NO_ES_MIEMBRO`; "escribiendo" de quien no es miembro activo → `ERROR` `NO_ES_MIEMBRO`.
9. El `MENSAJE_NUEVO` que se reenvía por un `idCliente` repetido va a todos los dispositivos del remitente.
10. Hacer admin a quien ya lo es, o quitarle el rol a quien no lo tiene, responde 204 sin evento. Editar un grupo con el mismo nombre no genera `NOMBRE_CAMBIADO`.

## Review Focus

Casos que el spec implica y que un borrador de tests no cubría; cada uno tiene su test en la tarea indicada.

1. **Emojis y otros caracteres fuera del plano básico:** 4096 emojis se aceptan (PostgreSQL cuenta caracteres; Java, unidades UTF-16) y uno más da `TEXTO_MUY_LARGO`; nunca 500. → Task 4, `unMensajeDe4096EmojisEntraYUnoMasEsDemasiadoLargo`.
2. **Mensajes vacíos o con el carácter nulo `\u0000`:** `VALIDACION`, nunca 500 (PostgreSQL rechaza `\u0000` en un texto). → Task 4, `losMensajesVaciosOConCaracteresNulosSonInvalidos`.
3. **Acuses más allá de lo que existe** (un cliente adelantado o con la caché de otra conversación): se recortan a la mayor secuencia visible; las marcas nunca quedan por encima de los mensajes reales. → Task 5, `unAcuseMasAllaDeLoQueExisteSeRecorta`.
4. **Dos mensajes enviados seguidos desde el mismo dispositivo** llegan con secuencias en el orden en que se escribieron. → Task 8, `losMensajesEnviadosSeguidosConservanSuOrden`.
5. **Un miembro quitado que sigue conectado** recibe el evento de su salida pero ya no los mensajes siguientes, y no puede enviar. → Task 10, `alQuitadoLeLlegaSuSalidaPeroNoLosMensajesSiguientes`.

## Mapa de archivos

```
backend/
├── pom.xml                                     + spring-boot-starter-websocket (Task 7)
└── src/
    ├── main/java/com/hellocr/
    │   ├── comun/                              CodigoError (+7 códigos), Tiempos
    │   ├── config/SecurityConfig.java          /ws sin token HTTP (la autenticación va en el CONNECT)
    │   ├── conversaciones/                     TipoConversacion, Rol, Periodo, ConsultaMembresia, GestionMiembros,
    │   │                                       MembresiaRepository, ConversacionRepository, ChatDirectoRepository,
    │   │                                       ChatDirectoService, LecturaConversacionesService,
    │   │                                       ListaConversacionesService, ConversacionController, ConsultaPresencia,
    │   │                                       ConversacionActualizada, ErroresConversacion y DTOs
    │   ├── mensajes/                           MensajeDto, EventoDto, MensajeRepository, MarcasRepository,
    │   │                                       HistorialRepository, EnvioMensajesService, MarcasService,
    │   │                                       HistorialService, HistorialController, LimiteMensajes,
    │   │                                       MensajeEnviado, EstadoActualizado y DTOs
    │   ├── tiempoReal/                         WebSocketConfig, AutenticacionStomp, UsuarioStomp, RegistroSesiones,
    │   │                                       SesionesWebSocket, EventosDeSesion, CierreSesiones, PresenciaService,
    │   │                                       EnviadorEventos(+Stomp), EntregaTiempoReal, EscribiendoService,
    │   │                                       RenovacionSesion, MensajesStompController y los eventos Evento*
    │   └── grupos/                             TipoEvento, GruposProperties, GrupoRepository, EventosGrupoRepository,
    │                                           OperacionesGrupo, GrupoService, MiembrosGrupoService, GrupoController,
    │                                           MiembrosGrupoController y DTOs
    ├── main/resources/
    │   ├── application.yml                     + app.mensajes, app.grupos, app.tiempo-real
    │   └── db/migration/V3__conversaciones.sql
    └── test/
        ├── java/com/hellocr/soporte/           PruebaIntegracion (+conversaciones al vaciar), PruebaTiempoReal,
        │                                       ClienteStomp
        └── resources/application-test.yml      + app.grupos.max-miembros: 5
```

## Cómo correr los comandos

- Todos los comandos se ejecutan en **Git Bash** desde la raíz `HelloCR/`, con `JAVA_HOME` apuntando al JDK 25 (en PowerShell, `.\mvnw.cmd`).
- Los tests necesitan PostgreSQL corriendo con la base `hellocr_test`.
- `./mvnw -q test` no imprime nada si todo pasa; si algo falla, muestra el test y el motivo.

---

### Task 1: Esquema de conversaciones y mensajes

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__conversaciones.sql`
- Modify: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java` (vaciar también `conversaciones`)
- Test: `backend/src/test/java/com/hellocr/conversaciones/EsquemaConversacionesTest.java`

**Interfaces:**
- Produces: tablas `conversaciones`, `miembros`, `chats_directos`, `grupos`, `periodos_membresia`, `mensajes` y `eventos_grupo` (spec, sección 6), con las restricciones con nombre `periodos_sin_solapamiento`, `mensajes_secuencia_unica`, `mensajes_id_cliente_unico`, `mensajes_texto_obligatorio`, `mensajes_evento_sin_texto`, `mensajes_texto_no_vacio`, `eventos_afectado`, `eventos_valor`, `chats_directos_orden`, `chats_directos_par_unico` y `miembros_leida_no_supera_entregada`.
- Produces (tests): `PruebaIntegracion` vacía `tokens_correo, refresh_tokens, conversaciones, usuarios` (y en cascada todas las tablas nuevas) antes de cada test.

- [ ] **Step 1: La base de los tests vacía también las conversaciones**

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`

```java
package com.hellocr.soporte;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.auth.LimiteIntentosLogin;
import com.hellocr.usuarios.GeneradorCodigos;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de los tests de integración: contexto completo, base hellocr_test limpia, reloj fijo y buzón vacío. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PruebasConfig.class)
public abstract class PruebaIntegracion {

    public static final String CLAVE = "clave12345";

    /** CASCADE vacía también miembros, periodos, mensajes, eventos, chats directos y grupos. */
    private static final String TABLAS = "tokens_correo, refresh_tokens, conversaciones, usuarios";
    private static final PasswordEncoder CODIFICADOR = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RelojAjustable reloj;
    @Autowired
    protected BuzonPrueba buzon;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private GeneradorCodigos codigos;
    @Autowired
    private LimiteIntentosLogin limiteIntentos;

    @BeforeEach
    void reiniciarEstado() {
        reloj.reiniciar();
        buzon.vaciar();
        limiteIntentos.olvidarTodo();
        jdbc.execute("TRUNCATE " + TABLAS + " RESTART IDENTITY CASCADE");
    }

    /** Cuenta verificada con correo <nombreUsuario>@correo.cr y contraseña CLAVE. */
    protected Usuario crearUsuario(String nombreUsuario) {
        return guardar(nombreUsuario, true);
    }

    protected Usuario crearUsuarioSinVerificar(String nombreUsuario) {
        return guardar(nombreUsuario, false);
    }

    private Usuario guardar(String nombreUsuario, boolean verificado) {
        Usuario usuario = new Usuario(nombreUsuario + "@correo.cr", nombreUsuario, "Usuario " + nombreUsuario,
                CODIFICADOR.encode(CLAVE), codigos.codigoInvitacion(), reloj.instant());
        if (verificado) {
            usuario.verificarCorreo(reloj.instant());
        }
        return usuarios.save(usuario);
    }

    protected SesionPrueba iniciarSesion(String nombreUsuario) throws Exception {
        return SesionPrueba.de(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identificador": "%s", "contrasena": "%s"}
                                """.formatted(nombreUsuario, CLAVE)))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** Atajo: crea la cuenta verificada y abre sesión. */
    protected SesionPrueba sesionDe(String nombreUsuario) throws Exception {
        crearUsuario(nombreUsuario);
        return iniciarSesion(nombreUsuario);
    }

    protected static RequestPostProcessor con(SesionPrueba sesion) {
        return solicitud -> {
            solicitud.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + sesion.accessToken());
            return solicitud;
        };
    }

    /** Lanza las tareas en hilos distintos, todas a la vez, y devuelve sus resultados en orden. */
    protected static <T> List<T> enParalelo(List<Callable<T>> tareas) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(tareas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    return tarea.call();
                }));
            }
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    protected static <T> List<T> enParalelo(int veces, Callable<T> tarea) throws Exception {
        return enParalelo(Collections.nCopies(veces, tarea));
    }
}
```

- [ ] **Step 2: Test del esquema**

Archivo: `backend/src/test/java/com/hellocr/conversaciones/EsquemaConversacionesTest.java`

```java
package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EsquemaConversacionesTest extends PruebaIntegracion {

    private UUID ana;
    private UUID luis;

    @BeforeEach
    void crearUsuarios() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
    }

    @Test
    void unaConversacionNoPuedeSerGrupoYChatDirectoALaVez() {
        UUID directa = conversacion("DIRECTA");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO grupos (conversacion_id, nombre, creado_por) VALUES (?, 'Familia', ?)", directa, ana))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void losPeriodosDeUnMiembroNoSeSolapan() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        periodoCerrado(grupo, ana, 1, 3);

        assertThatCode(() -> periodoAbierto(grupo, ana, 4)).doesNotThrowAnyException();
        assertThatThrownBy(() -> periodoAbierto(grupo, ana, 10)).hasMessageContaining("periodos_sin_solapamiento");
        assertThatThrownBy(() -> periodoCerrado(grupo, ana, 3, 3)).hasMessageContaining("periodos_sin_solapamiento");
    }

    @Test
    void laSecuenciaEsUnicaPorConversacionYElIdClientePorRemitente() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        UUID idCliente = UUID.randomUUID();
        texto(grupo, 1, ana, idCliente, "hola");

        assertThatThrownBy(() -> texto(grupo, 1, ana, UUID.randomUUID(), "otra"))
                .hasMessageContaining("mensajes_secuencia_unica");
        assertThatThrownBy(() -> texto(grupo, 2, ana, idCliente, "otra"))
                .hasMessageContaining("mensajes_id_cliente_unico");
    }

    @Test
    void soloUnMiembroPuedeSerRemitente() {
        UUID grupo = conversacion("GRUPO");

        assertThatThrownBy(() -> texto(grupo, 1, ana, UUID.randomUUID(), "hola"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elTextoDependeDelTipoDeMensaje() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);

        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "TEXTO", null))
                .hasMessageContaining("mensajes_texto_obligatorio");
        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "EVENTO", "texto"))
                .hasMessageContaining("mensajes_evento_sin_texto");
        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "TEXTO", "   "))
                .hasMessageContaining("mensajes_texto_no_vacio");
    }

    @Test
    void losEventosExigenAfectadoOValorSegunSuTipoYSoloCuelganDeMensajesEvento() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        long agregado = mensaje(grupo, 1, ana, "EVENTO", null);
        long renombre = mensaje(grupo, 2, ana, "EVENTO", null);
        long texto = texto(grupo, 3, ana, UUID.randomUUID(), "hola");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'MIEMBRO_AGREGADO')", agregado))
                .hasMessageContaining("eventos_afectado");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'NOMBRE_CAMBIADO')", renombre))
                .hasMessageContaining("eventos_valor");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'GRUPO_CREADO')", texto))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unChatDirectoGuardaElParOrdenadoYSinRepetir() {
        UUID chat = conversacion("DIRECTA");
        miembro(chat, ana);
        miembro(chat, luis);
        UUID menor = jdbc.queryForObject("SELECT LEAST(?::uuid, ?::uuid)", UUID.class, ana, luis);
        UUID mayor = jdbc.queryForObject("SELECT GREATEST(?::uuid, ?::uuid)", UUID.class, ana, luis);

        assertThatThrownBy(() -> directo(chat, mayor, menor)).hasMessageContaining("chats_directos_orden");
        directo(chat, menor, mayor);

        UUID otro = conversacion("DIRECTA");
        miembro(otro, ana);
        miembro(otro, luis);
        assertThatThrownBy(() -> directo(otro, menor, mayor)).hasMessageContaining("chats_directos_par_unico");
    }

    @Test
    void laUltimaLeidaNoPuedeSuperarALaEntregada() {
        UUID chat = conversacion("DIRECTA");
        miembro(chat, ana);

        assertThatThrownBy(() -> jdbc.update("UPDATE miembros SET ultima_leida = 5 WHERE usuario_id = ?", ana))
                .hasMessageContaining("miembros_leida_no_supera_entregada");
    }

    private UUID conversacion(String tipo) {
        return jdbc.queryForObject("INSERT INTO conversaciones (tipo) VALUES (?) RETURNING id", UUID.class, tipo);
    }

    private void miembro(UUID conversacion, UUID usuario) {
        jdbc.update("INSERT INTO miembros (conversacion_id, usuario_id) VALUES (?, ?)", conversacion, usuario);
    }

    private void periodoAbierto(UUID conversacion, UUID usuario, long desde) {
        jdbc.update("""
                INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia) VALUES (?, ?, ?)
                """, conversacion, usuario, desde);
    }

    private void periodoCerrado(UUID conversacion, UUID usuario, long desde, long hasta) {
        jdbc.update("""
                INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia, hasta_secuencia)
                VALUES (?, ?, ?, ?)
                """, conversacion, usuario, desde, hasta);
    }

    private long texto(UUID conversacion, long secuencia, UUID remitente, UUID idCliente, String texto) {
        return jdbc.queryForObject("""
                INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                VALUES (?, ?, ?, ?, 'TEXTO', ?) RETURNING id
                """, Long.class, conversacion, secuencia, remitente, idCliente, texto);
    }

    private long mensaje(UUID conversacion, long secuencia, UUID remitente, String tipo, String texto) {
        return jdbc.queryForObject("""
                INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, conversacion, secuencia, remitente, UUID.randomUUID(), tipo, texto);
    }

    private void directo(UUID conversacion, UUID usuarioA, UUID usuarioB) {
        jdbc.update("INSERT INTO chats_directos (conversacion_id, usuario_a_id, usuario_b_id) VALUES (?, ?, ?)",
                conversacion, usuarioA, usuarioB);
    }
}
```

- [ ] **Step 3: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=EsquemaConversacionesTest`
Expected: FAIL — `relation "conversaciones" does not exist` (el `TRUNCATE` de la base de tests ya la nombra).

- [ ] **Step 4: Migración**

Archivo: `backend/src/main/resources/db/migration/V3__conversaciones.sql`

```sql
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
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores. Flyway aplica `V3` sobre `hellocr_test`.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): esquema de conversaciones, periodos de membresía, mensajes y eventos de grupo"
```

---

### Task 2: Membresía y periodos

**Files:**
- Create en `backend/src/main/java/com/hellocr/conversaciones/`: `TipoConversacion.java`, `Rol.java`, `Periodo.java`, `ConsultaMembresia.java`, `GestionMiembros.java`, `MembresiaRepository.java`, `ConversacionRepository.java`, `ErroresConversacion.java`
- Create: `backend/src/main/java/com/hellocr/comun/Tiempos.java`
- Modify: `backend/src/main/java/com/hellocr/comun/CodigoError.java`
- Test: `backend/src/test/java/com/hellocr/conversaciones/MembresiaTest.java`

**Interfaces:**
- Produces: `enum TipoConversacion { DIRECTA, GRUPO }`, `enum Rol { ADMIN, MIEMBRO }`, `record Periodo(long desde, Long hasta)`.
- Produces: `ConsultaMembresia` — `esMiembroActivo(UUID conversacionId, UUID usuarioId)`, `fueMiembro(...)`, `puedeVer(UUID conversacionId, UUID usuarioId, long secuencia)`, `Set<UUID> quienesPuedenVer(UUID conversacionId, long secuencia)`, `Set<UUID> miembrosActivos(UUID conversacionId)`.
- Produces: `GestionMiembros` — `incorporar(UUID conversacionId, UUID usuarioId, Rol rol, long desdeSecuencia)` (crea o reutiliza la fila de `miembros` y abre un periodo), `retirar(UUID conversacionId, UUID usuarioId, long hastaSecuencia)` (cierra el periodo abierto y deja el rol en `MIEMBRO`), `Optional<Rol> rolActivo(...)`, `cambiarRol(UUID, UUID, Rol)`, `int contarActivos(UUID)`, `int contarAdministradoresActivos(UUID)`, `Optional<UUID> activoMasAntiguo(UUID)`.
- Produces: `MembresiaRepository` implementa las dos interfaces y expone `static String visiblePara(String aliasMensaje)`: la condición SQL "el mensaje `<alias>` está dentro de un periodo del usuario `:yo`", que reutilizan las consultas de mensajes.
- Produces: `ConversacionRepository.crear(TipoConversacion, Instant): UUID` y `bloquear(UUID): Optional<TipoConversacion>` (`SELECT … FOR UPDATE`; vacío si no existe).
- Produces: `Tiempos.ahora(Clock)`, `Tiempos.sql(Instant): OffsetDateTime`, `Tiempos.instante(ResultSet, String): Instant`.
- Produces: `CodigoError` con `NO_ES_MIEMBRO` (403), `YA_ES_MIEMBRO` (409), `GRUPO_LLENO` (409), `ULTIMO_ADMIN` (409), `CHAT_CONSIGO_MISMO` (422), `TEXTO_MUY_LARGO` (400) y `DEMASIADOS_MENSAJES` (429); `ErroresConversacion.noEncontrada()`, `grupoNoEncontrado()`, `noEsMiembro()`, `sinPermiso()`, `personaFueraDelGrupo()`, `yaEsMiembro()`, `grupoLleno(int)`, `ultimoAdmin()`, `chatConsigoMismo()`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/conversaciones/MembresiaTest.java`

```java
package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tabla de casos de la visibilidad (spec, sección 12): en un grupo con mensajes 1..10,
 * Ana está desde el principio, Luis entra en el 4, Sofía sale en el 6 y Marco sale en el 3 y vuelve en el 8.
 */
class MembresiaTest extends PruebaIntegracion {

    @Autowired
    private ConversacionRepository conversaciones;
    @Autowired
    private ConsultaMembresia consulta;
    @Autowired
    private GestionMiembros gestion;
    @Autowired
    private TransactionTemplate transacciones;

    private UUID grupo;
    private UUID ana;
    private UUID luis;
    private UUID sofia;
    private UUID marco;

    @BeforeEach
    void escenario() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        sofia = crearUsuario("sofia").getId();
        marco = crearUsuario("marco").getId();
        grupo = conversaciones.crear(TipoConversacion.GRUPO, reloj.instant());
        gestion.incorporar(grupo, ana, Rol.ADMIN, 1);
        gestion.incorporar(grupo, sofia, Rol.MIEMBRO, 1);
        gestion.incorporar(grupo, marco, Rol.MIEMBRO, 1);
        for (long secuencia = 1; secuencia <= 10; secuencia++) {
            jdbc.update("""
                    INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                    VALUES (?, ?, ?, ?, 'TEXTO', 'hola')
                    """, grupo, secuencia, ana, UUID.randomUUID());
        }
        gestion.retirar(grupo, marco, 3);
        gestion.incorporar(grupo, luis, Rol.MIEMBRO, 4);
        gestion.retirar(grupo, sofia, 6);
        gestion.incorporar(grupo, marco, Rol.MIEMBRO, 8);
    }

    @Test
    void cadaUnoVeSoloLasSecuenciasDeSusPeriodos() {
        assertThat(visibles(ana)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(visibles(luis)).containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(visibles(sofia)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(visibles(marco)).containsExactly(1L, 2L, 3L, 8L, 9L, 10L);
    }

    @Test
    void quienesPuedenVerUnaSecuencia() {
        assertThat(consulta.quienesPuedenVer(grupo, 5)).containsExactlyInAnyOrder(ana, luis, sofia);
        assertThat(consulta.quienesPuedenVer(grupo, 7)).containsExactlyInAnyOrder(ana, luis);
        assertThat(consulta.quienesPuedenVer(grupo, 9)).containsExactlyInAnyOrder(ana, luis, marco);
    }

    @Test
    void losActivosSonLosQueTienenUnPeriodoAbierto() {
        assertThat(consulta.miembrosActivos(grupo)).containsExactlyInAnyOrder(ana, luis, marco);
        assertThat(consulta.esMiembroActivo(grupo, sofia)).isFalse();
        assertThat(consulta.fueMiembro(grupo, sofia)).isTrue();
        assertThat(consulta.fueMiembro(grupo, UUID.randomUUID())).isFalse();
        assertThat(gestion.contarActivos(grupo)).isEqualTo(3);
    }

    @Test
    void noSePuedeAbrirUnSegundoPeriodoSinCerrarElPrimero() {
        assertThatThrownBy(() -> gestion.incorporar(grupo, ana, Rol.MIEMBRO, 11))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void alRetirarseElRolVuelveAMiembroYElMasAntiguoEsElDelPeriodoMasViejo() {
        gestion.cambiarRol(grupo, luis, Rol.ADMIN);
        assertThat(gestion.contarAdministradoresActivos(grupo)).isEqualTo(2);

        gestion.retirar(grupo, luis, 11);

        assertThat(gestion.rolActivo(grupo, luis)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT rol FROM miembros WHERE conversacion_id = ? AND usuario_id = ?",
                String.class, grupo, luis)).isEqualTo("MIEMBRO");
        assertThat(gestion.contarAdministradoresActivos(grupo)).isEqualTo(1);
        assertThat(gestion.rolActivo(grupo, ana)).contains(Rol.ADMIN);
        assertThat(gestion.activoMasAntiguo(grupo)).contains(ana);
    }

    @Test
    void bloquearDevuelveElTipoOVacioSiNoExiste() {
        Optional<TipoConversacion> existente = transacciones.execute(estado -> conversaciones.bloquear(grupo));
        Optional<TipoConversacion> inexistente =
                transacciones.execute(estado -> conversaciones.bloquear(UUID.randomUUID()));

        assertThat(existente).contains(TipoConversacion.GRUPO);
        assertThat(inexistente).isEmpty();
    }

    private List<Long> visibles(UUID usuario) {
        return LongStream.rangeClosed(1, 10).filter(secuencia -> consulta.puedeVer(grupo, usuario, secuencia))
                .boxed().toList();
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=MembresiaTest`
Expected: FAIL de compilación — `cannot find symbol: class ConversacionRepository`.

- [ ] **Step 3: Códigos de error y utilidades de tiempo**

Archivo: `backend/src/main/java/com/hellocr/comun/CodigoError.java`

```java
package com.hellocr.comun;

import org.springframework.http.HttpStatus;

/** Catálogo de códigos de error de la API (spec, sección 11). El plan 3 agrega los de archivos. */
public enum CodigoError {
    VALIDACION(HttpStatus.BAD_REQUEST),
    TEXTO_MUY_LARGO(HttpStatus.BAD_REQUEST),
    NO_AUTENTICADO(HttpStatus.UNAUTHORIZED),
    CREDENCIALES_INVALIDAS(HttpStatus.UNAUTHORIZED),
    SIN_PERMISO(HttpStatus.FORBIDDEN),
    NO_ES_MIEMBRO(HttpStatus.FORBIDDEN),
    CORREO_NO_VERIFICADO(HttpStatus.FORBIDDEN),
    NO_ENCONTRADO(HttpStatus.NOT_FOUND),
    CORREO_EN_USO(HttpStatus.CONFLICT),
    NOMBRE_USUARIO_EN_USO(HttpStatus.CONFLICT),
    YA_ES_MIEMBRO(HttpStatus.CONFLICT),
    GRUPO_LLENO(HttpStatus.CONFLICT),
    ULTIMO_ADMIN(HttpStatus.CONFLICT),
    TOKEN_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT),
    NOMBRE_USUARIO_RESERVADO(HttpStatus.UNPROCESSABLE_CONTENT),
    CHAT_CONSIGO_MISMO(HttpStatus.UNPROCESSABLE_CONTENT),
    DEMASIADOS_INTENTOS(HttpStatus.TOO_MANY_REQUESTS),
    DEMASIADOS_MENSAJES(HttpStatus.TOO_MANY_REQUESTS),
    ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus estado;

    CodigoError(HttpStatus estado) {
        this.estado = estado;
    }

    public HttpStatus estado() {
        return estado;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/comun/Tiempos.java`

```java
package com.hellocr.comun;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Instantes para las consultas SQL de conversaciones y mensajes. */
public final class Tiempos {

    private Tiempos() {
    }

    /** PostgreSQL guarda microsegundos: truncar evita que un instante se vea distinto antes y después de guardarlo. */
    public static Instant ahora(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime sql(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    public static Instant instante(ResultSet fila, String columna) throws SQLException {
        OffsetDateTime valor = fila.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}
```

- [ ] **Step 4: Tipos, interfaces y repositorios**

Archivo: `backend/src/main/java/com/hellocr/conversaciones/TipoConversacion.java`

```java
package com.hellocr.conversaciones;

public enum TipoConversacion {
    DIRECTA, GRUPO
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/Rol.java`

```java
package com.hellocr.conversaciones;

/** Rol dentro de una conversación. En los chats directos los dos son MIEMBRO. */
public enum Rol {
    ADMIN, MIEMBRO
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/Periodo.java`

```java
package com.hellocr.conversaciones;

/** Tramo de secuencias que un miembro puede ver, inclusivo en los dos extremos. hasta null = sigue abierto. */
public record Periodo(long desde, Long hasta) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConsultaMembresia.java`

```java
package com.hellocr.conversaciones;

import java.util.Set;
import java.util.UUID;

/** Preguntas sobre quién está en una conversación y qué puede ver. La usan mensajes, grupos y tiempo real. */
public interface ConsultaMembresia {

    /** Tiene un periodo abierto. */
    boolean esMiembroActivo(UUID conversacionId, UUID usuarioId);

    /** Estuvo alguna vez (tiene fila en miembros), aunque ya haya salido. */
    boolean fueMiembro(UUID conversacionId, UUID usuarioId);

    boolean puedeVer(UUID conversacionId, UUID usuarioId, long secuencia);

    Set<UUID> quienesPuedenVer(UUID conversacionId, long secuencia);

    Set<UUID> miembrosActivos(UUID conversacionId);
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/GestionMiembros.java`

```java
package com.hellocr.conversaciones;

import java.util.Optional;
import java.util.UUID;

/** Altas, bajas y roles de los miembros. Solo la usan los chats directos al crearse y los grupos. */
public interface GestionMiembros {

    /** Crea (o reutiliza) la fila del miembro con ese rol y abre un periodo desde esa secuencia. */
    void incorporar(UUID conversacionId, UUID usuarioId, Rol rol, long desdeSecuencia);

    /** Cierra el periodo abierto en esa secuencia (inclusive) y deja el rol en MIEMBRO. */
    void retirar(UUID conversacionId, UUID usuarioId, long hastaSecuencia);

    /** El rol de un miembro activo; vacío si no tiene un periodo abierto. */
    Optional<Rol> rolActivo(UUID conversacionId, UUID usuarioId);

    void cambiarRol(UUID conversacionId, UUID usuarioId, Rol rol);

    int contarActivos(UUID conversacionId);

    int contarAdministradoresActivos(UUID conversacionId);

    /** El miembro activo con el periodo abierto más antiguo. */
    Optional<UUID> activoMasAntiguo(UUID conversacionId);
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/MembresiaRepository.java`

```java
package com.hellocr.conversaciones;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MembresiaRepository implements ConsultaMembresia, GestionMiembros {

    private static final String PERIODO_ABIERTO = """
            EXISTS (SELECT 1 FROM periodos_membresia p
                    WHERE p.conversacion_id = m.conversacion_id AND p.usuario_id = m.usuario_id
                      AND p.hasta_secuencia IS NULL)""";

    private final JdbcClient jdbc;

    public MembresiaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Condición SQL "el mensaje con alias {@code aliasMensaje} está dentro de un periodo del usuario :yo"
     * (spec 9.2, visibilidad). La consulta que la usa debe pasar el parámetro {@code yo}.
     */
    public static String visiblePara(String aliasMensaje) {
        return """
                EXISTS (SELECT 1 FROM periodos_membresia p
                        WHERE p.conversacion_id = %1$s.conversacion_id AND p.usuario_id = :yo
                          AND %1$s.secuencia >= p.desde_secuencia
                          AND (p.hasta_secuencia IS NULL OR %1$s.secuencia <= p.hasta_secuencia))""".formatted(aliasMensaje);
    }

    @Override
    public boolean esMiembroActivo(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM periodos_membresia
                                       WHERE conversacion_id = :c AND usuario_id = :u AND hasta_secuencia IS NULL)
                        """)
                .param("c", conversacionId).param("u", usuarioId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean fueMiembro(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM miembros WHERE conversacion_id = :c AND usuario_id = :u)")
                .param("c", conversacionId).param("u", usuarioId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean puedeVer(UUID conversacionId, UUID usuarioId, long secuencia) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM periodos_membresia
                                       WHERE conversacion_id = :c AND usuario_id = :u AND desde_secuencia <= :s
                                         AND (hasta_secuencia IS NULL OR hasta_secuencia >= :s))
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", secuencia)
                .query(Boolean.class).single();
    }

    @Override
    public Set<UUID> quienesPuedenVer(UUID conversacionId, long secuencia) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND desde_secuencia <= :s
                          AND (hasta_secuencia IS NULL OR hasta_secuencia >= :s)
                        """)
                .param("c", conversacionId).param("s", secuencia)
                .query(UUID.class).list());
    }

    @Override
    public Set<UUID> miembrosActivos(UUID conversacionId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND hasta_secuencia IS NULL
                        ORDER BY desde_secuencia, id
                        """)
                .param("c", conversacionId)
                .query(UUID.class).list());
    }

    @Override
    public void incorporar(UUID conversacionId, UUID usuarioId, Rol rol, long desdeSecuencia) {
        jdbc.sql("""
                        INSERT INTO miembros (conversacion_id, usuario_id, rol) VALUES (:c, :u, :rol)
                        ON CONFLICT (conversacion_id, usuario_id) DO UPDATE SET rol = EXCLUDED.rol
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("rol", rol.name())
                .update();
        jdbc.sql("INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia) VALUES (:c, :u, :s)")
                .param("c", conversacionId).param("u", usuarioId).param("s", desdeSecuencia)
                .update();
    }

    @Override
    public void retirar(UUID conversacionId, UUID usuarioId, long hastaSecuencia) {
        jdbc.sql("""
                        UPDATE periodos_membresia SET hasta_secuencia = :s
                        WHERE conversacion_id = :c AND usuario_id = :u AND hasta_secuencia IS NULL
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", hastaSecuencia)
                .update();
        cambiarRol(conversacionId, usuarioId, Rol.MIEMBRO);
    }

    @Override
    public Optional<Rol> rolActivo(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT m.rol FROM miembros m WHERE m.conversacion_id = :c AND m.usuario_id = :u AND "
                        + PERIODO_ABIERTO)
                .param("c", conversacionId).param("u", usuarioId)
                .query(String.class).optional()
                .map(Rol::valueOf);
    }

    @Override
    public void cambiarRol(UUID conversacionId, UUID usuarioId, Rol rol) {
        jdbc.sql("UPDATE miembros SET rol = :rol WHERE conversacion_id = :c AND usuario_id = :u")
                .param("c", conversacionId).param("u", usuarioId).param("rol", rol.name())
                .update();
    }

    @Override
    public int contarActivos(UUID conversacionId) {
        return jdbc.sql("SELECT count(*) FROM periodos_membresia WHERE conversacion_id = :c AND hasta_secuencia IS NULL")
                .param("c", conversacionId)
                .query(Integer.class).single();
    }

    @Override
    public int contarAdministradoresActivos(UUID conversacionId) {
        return jdbc.sql("SELECT count(*) FROM miembros m WHERE m.conversacion_id = :c AND m.rol = 'ADMIN' AND "
                        + PERIODO_ABIERTO)
                .param("c", conversacionId)
                .query(Integer.class).single();
    }

    @Override
    public Optional<UUID> activoMasAntiguo(UUID conversacionId) {
        return jdbc.sql("""
                        SELECT usuario_id FROM periodos_membresia
                        WHERE conversacion_id = :c AND hasta_secuencia IS NULL
                        ORDER BY desde_secuencia, id LIMIT 1
                        """)
                .param("c", conversacionId)
                .query(UUID.class).optional();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionRepository.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversacionRepository {

    private final JdbcClient jdbc;

    public ConversacionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID crear(TipoConversacion tipo, Instant ahora) {
        return jdbc.sql("INSERT INTO conversaciones (tipo, creada_en) VALUES (:tipo, :ahora) RETURNING id")
                .param("tipo", tipo.name()).param("ahora", Tiempos.sql(ahora))
                .query(UUID.class).single();
    }

    /**
     * SELECT … FOR UPDATE: toda escritura en una conversación empieza por acá (spec, sección 8), así las
     * secuencias no se repiten y los bloqueos siempre se toman en el mismo orden. Vacío si no existe.
     */
    public Optional<TipoConversacion> bloquear(UUID id) {
        return jdbc.sql("SELECT tipo FROM conversaciones WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class).optional()
                .map(TipoConversacion::valueOf);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ErroresConversacion.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;

/** Errores que comparten conversaciones, mensajes y grupos. */
public final class ErroresConversacion {

    private ErroresConversacion() {
    }

    public static ErrorNegocio noEncontrada() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "La conversación no existe.");
    }

    public static ErrorNegocio grupoNoEncontrado() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "El grupo no existe.");
    }

    public static ErrorNegocio noEsMiembro() {
        return new ErrorNegocio(CodigoError.NO_ES_MIEMBRO, "Ya no sos parte de esta conversación.");
    }

    public static ErrorNegocio sinPermiso() {
        return new ErrorNegocio(CodigoError.SIN_PERMISO, "Solo los administradores del grupo pueden hacer esto.");
    }

    public static ErrorNegocio personaFueraDelGrupo() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "Esa persona no está en el grupo.");
    }

    public static ErrorNegocio yaEsMiembro() {
        return new ErrorNegocio(CodigoError.YA_ES_MIEMBRO, "Esa persona ya está en el grupo.");
    }

    public static ErrorNegocio grupoLleno(int maximo) {
        return new ErrorNegocio(CodigoError.GRUPO_LLENO, "El grupo ya tiene el máximo de " + maximo + " personas.");
    }

    public static ErrorNegocio ultimoAdmin() {
        return new ErrorNegocio(CodigoError.ULTIMO_ADMIN, "El grupo tiene que tener al menos un administrador.");
    }

    public static ErrorNegocio chatConsigoMismo() {
        return new ErrorNegocio(CodigoError.CHAT_CONSIGO_MISMO, "No podés abrir un chat con vos mismo.");
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): membresía con periodos de visibilidad y bloqueo de conversaciones"
```

---

### Task 3: Chats directos y detalle de una conversación

**Files:**
- Create en `backend/src/main/java/com/hellocr/conversaciones/`: `ChatDirectoRepository.java`, `ChatDirectoService.java`, `LecturaConversacionesService.java`, `ConversacionDetalle.java`, `MiembroDetalle.java`, `SolicitudChatDirecto.java`, `ConversacionController.java`
- Test: `backend/src/test/java/com/hellocr/conversaciones/ChatDirectoControllerTest.java`

**Interfaces:**
- Consumes: `ConsultaMembresia`, `GestionMiembros`, `ConversacionRepository`, `ErroresConversacion`, `Tiempos` (Task 2); `UsuarioRepository.verificadoPorId`, `ErroresUsuario.noEncontrado`, `PerfilPublico` (plan 1).
- Produces (API): `POST /api/conversaciones/directas` `{usuarioId}` → 201 (nuevo) o 200 (ya existía) con `ConversacionDetalle`; `GET /api/conversaciones/{id}` → `ConversacionDetalle` (404 para quien nunca fue miembro).
- Produces: `record ConversacionDetalle(UUID id, TipoConversacion tipo, String titulo, String descripcion, boolean activa, Rol miRol, List<MiembroDetalle> miembros)`; `record MiembroDetalle(PerfilPublico usuario, Rol rol, long ultimaEntregada, long ultimaLeida, List<Periodo> periodos)`; `LecturaConversacionesService.detalle(UUID yo, UUID conversacionId)`; `ChatDirectoService.abrir(UUID yo, UUID otro): Apertura` con `record Apertura(ConversacionDetalle detalle, boolean creada)`; `ChatDirectoRepository.contactosDe(UUID): Set<UUID>` (la usa la presencia en la Task 7).

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/conversaciones/ChatDirectoControllerTest.java`

```java
package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ChatDirectoControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;

    private SesionPrueba ana;
    private SesionPrueba luis;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
    }

    @Test
    void abrirUnChatNuevoResponde201ConElDetalle() throws Exception {
        abrir(ana, luis.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("DIRECTA"))
                .andExpect(jsonPath("$.titulo").value("Usuario luis"))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"))
                .andExpect(jsonPath("$.miembros.length()").value(2))
                .andExpect(jsonPath("$.miembros[0].usuario.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.miembros[0].ultimaEntregada").value(0))
                .andExpect(jsonPath("$.miembros[0].periodos[0].desde").value(1))
                .andExpect(jsonPath("$.miembros[0].periodos[0].hasta").value(nullValue()))
                .andExpect(jsonPath("$.miembros[1].usuario.correo").doesNotExist());
    }

    @Test
    void abrirloDeNuevoDesdeCualquieraDeLosDosDevuelveElMismoChat() throws Exception {
        String id = idDe(abrir(ana, luis.usuarioId()).andExpect(status().isCreated()));

        abrir(ana, luis.usuarioId()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        abrir(luis, ana.usuarioId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.titulo").value("Usuario ana"));
    }

    @Test
    void noSePuedeAbrirUnChatConUnoMismo() throws Exception {
        abrir(ana, ana.usuarioId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CHAT_CONSIGO_MISMO"));
    }

    @Test
    void conAlguienInexistenteOSinVerificarEs404() throws Exception {
        UUID sofia = crearUsuarioSinVerificar("sofia").getId();

        abrir(ana, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        abrir(ana, sofia.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void sinUsuarioOConUnIdInvalidoEs400() throws Exception {
        mvc.perform(post("/api/conversaciones/directas").with(con(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.usuarioId").value("Falta la persona con quien chatear."));
        abrir(ana, "no-es-un-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void dosAperturasSimultaneasDejanUnSoloChat() throws Exception {
        UUID idAna = UUID.fromString(ana.usuarioId());
        UUID idLuis = UUID.fromString(luis.usuarioId());
        List<Callable<ChatDirectoService.Apertura>> tareas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tareas.add(() -> chats.abrir(idAna, idLuis));
            tareas.add(() -> chats.abrir(idLuis, idAna));
        }

        List<ChatDirectoService.Apertura> aperturas = enParalelo(tareas);

        assertThat(aperturas).extracting(apertura -> apertura.detalle().id()).containsOnly(aperturas.getFirst().detalle().id());
        assertThat(aperturas).filteredOn(ChatDirectoService.Apertura::creada).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversaciones", Integer.class)).isEqualTo(1);
    }

    @Test
    void elDetalleSoloLoVenLosMiembros() throws Exception {
        String id = idDe(abrir(ana, luis.usuarioId()));
        SesionPrueba sofia = sesionDe("sofia");

        mvc.perform(get("/api/conversaciones/{id}", id).with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Usuario luis"));
        mvc.perform(get("/api/conversaciones/{id}", id).with(con(sofia)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La conversación no existe."));
        mvc.perform(get("/api/conversaciones/{id}", UUID.randomUUID()).with(con(ana)))
                .andExpect(status().isNotFound());
    }

    private ResultActions abrir(SesionPrueba sesion, String usuarioId) throws Exception {
        return mvc.perform(post("/api/conversaciones/directas").with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"usuarioId": "%s"}
                        """.formatted(usuarioId)));
    }

    private static String idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=ChatDirectoControllerTest`
Expected: FAIL de compilación — `cannot find symbol: class ChatDirectoService`.

- [ ] **Step 3: DTOs y lectura del detalle**

Archivo: `backend/src/main/java/com/hellocr/conversaciones/MiembroDetalle.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.usuarios.PerfilPublico;
import java.util.List;

/** Un miembro actual o pasado, con sus marcas y periodos (el cliente calcula ✓✓ con esto, spec 8.3). */
public record MiembroDetalle(PerfilPublico usuario, Rol rol, long ultimaEntregada, long ultimaLeida,
        List<Periodo> periodos) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionDetalle.java`

```java
package com.hellocr.conversaciones;

import java.util.List;
import java.util.UUID;

/** Spec 9.2. Incluye a los ex miembros; la interfaz solo lista a los activos. El plan 3 agrega fotoId. */
public record ConversacionDetalle(UUID id, TipoConversacion tipo, String titulo, String descripcion, boolean activa,
        Rol miRol, List<MiembroDetalle> miembros) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/SolicitudChatDirecto.java`

```java
package com.hellocr.conversaciones;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SolicitudChatDirecto(@NotNull(message = "Falta la persona con quien chatear.") UUID usuarioId) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/LecturaConversacionesService.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.usuarios.PerfilPublico;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LecturaConversacionesService {

    private record Cabecera(TipoConversacion tipo, String nombre, String descripcion) {
    }

    private final JdbcClient jdbc;
    private final ConsultaMembresia membresia;

    public LecturaConversacionesService(JdbcClient jdbc, ConsultaMembresia membresia) {
        this.jdbc = jdbc;
        this.membresia = membresia;
    }

    /** Solo para miembros actuales o pasados; a cualquier otro le responde que no existe. */
    @Transactional(readOnly = true)
    public ConversacionDetalle detalle(UUID yo, UUID conversacionId) {
        if (!membresia.fueMiembro(conversacionId, yo)) {
            throw ErroresConversacion.noEncontrada();
        }
        Cabecera cabecera = jdbc.sql("""
                        SELECT c.tipo, g.nombre, g.descripcion
                        FROM conversaciones c LEFT JOIN grupos g ON g.conversacion_id = c.id
                        WHERE c.id = :c
                        """)
                .param("c", conversacionId)
                .query((fila, n) -> new Cabecera(TipoConversacion.valueOf(fila.getString("tipo")),
                        fila.getString("nombre"), fila.getString("descripcion")))
                .single();
        Map<UUID, List<Periodo>> periodos = new HashMap<>();
        jdbc.sql("""
                        SELECT usuario_id, desde_secuencia, hasta_secuencia FROM periodos_membresia
                        WHERE conversacion_id = :c ORDER BY desde_secuencia
                        """)
                .param("c", conversacionId)
                .query(fila -> {
                    periodos.computeIfAbsent(fila.getObject("usuario_id", UUID.class), id -> new ArrayList<>())
                            .add(new Periodo(fila.getLong("desde_secuencia"),
                                    fila.getObject("hasta_secuencia", Long.class)));
                });
        List<MiembroDetalle> miembros = jdbc.sql("""
                        SELECT u.id, u.nombre_usuario, u.nombre_visible, u.info,
                               m.rol, m.ultima_entregada, m.ultima_leida
                        FROM miembros m JOIN usuarios u ON u.id = m.usuario_id
                        WHERE m.conversacion_id = :c
                        ORDER BY u.nombre_usuario
                        """)
                .param("c", conversacionId)
                .query((fila, n) -> {
                    UUID id = fila.getObject("id", UUID.class);
                    return new MiembroDetalle(
                            new PerfilPublico(id, fila.getString("nombre_usuario"), fila.getString("nombre_visible"),
                                    fila.getString("info")),
                            Rol.valueOf(fila.getString("rol")), fila.getLong("ultima_entregada"),
                            fila.getLong("ultima_leida"), periodos.getOrDefault(id, List.of()));
                })
                .list();
        MiembroDetalle propio = miembros.stream().filter(m -> m.usuario().id().equals(yo)).findFirst().orElseThrow();
        boolean activa = propio.periodos().stream().anyMatch(periodo -> periodo.hasta() == null);
        return new ConversacionDetalle(conversacionId, cabecera.tipo(), titulo(cabecera, miembros, yo),
                cabecera.descripcion(), activa, propio.rol(), miembros);
    }

    /** El nombre del grupo, o el nombre visible de la otra persona en un chat directo. */
    private static String titulo(Cabecera cabecera, List<MiembroDetalle> miembros, UUID yo) {
        if (cabecera.tipo() == TipoConversacion.GRUPO) {
            return cabecera.nombre();
        }
        return miembros.stream().filter(m -> !m.usuario().id().equals(yo))
                .map(m -> m.usuario().nombreVisible()).findFirst().orElse("");
    }
}
```

- [ ] **Step 4: Chat directo y controlador**

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ChatDirectoRepository.java`

```java
package com.hellocr.conversaciones;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El par de un chat directo se ordena en PostgreSQL con LEAST/GREATEST: Java compara los UUID con signo y
 * daría otro orden que el de la restricción chats_directos_orden.
 */
@Repository
public class ChatDirectoRepository {

    private final JdbcClient jdbc;

    public ChatDirectoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> buscar(UUID uno, UUID otro) {
        return jdbc.sql("""
                        SELECT conversacion_id FROM chats_directos
                        WHERE usuario_a_id = LEAST(:uno, :otro) AND usuario_b_id = GREATEST(:uno, :otro)
                        """)
                .param("uno", uno).param("otro", otro)
                .query(UUID.class).optional();
    }

    /** Falla con DuplicateKeyException si otra transacción ya registró ese par. */
    public void registrar(UUID conversacionId, UUID uno, UUID otro) {
        jdbc.sql("""
                        INSERT INTO chats_directos (conversacion_id, usuario_a_id, usuario_b_id)
                        VALUES (:c, LEAST(:uno, :otro), GREATEST(:uno, :otro))
                        """)
                .param("c", conversacionId).param("uno", uno).param("otro", otro)
                .update();
    }

    /** Las personas con las que el usuario tiene un chat directo (ven su presencia, spec 7.5). */
    public Set<UUID> contactosDe(UUID usuarioId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT CASE WHEN usuario_a_id = :u THEN usuario_b_id ELSE usuario_a_id END
                        FROM chats_directos WHERE usuario_a_id = :u OR usuario_b_id = :u
                        """)
                .param("u", usuarioId)
                .query(UUID.class).list());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ChatDirectoService.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatDirectoService {

    public record Apertura(ConversacionDetalle detalle, boolean creada) {
    }

    private final UsuarioRepository usuarios;
    private final ChatDirectoRepository directos;
    private final ConversacionRepository conversaciones;
    private final GestionMiembros gestion;
    private final LecturaConversacionesService lectura;
    private final TransactionTemplate transacciones;
    private final Clock clock;

    public ChatDirectoService(UsuarioRepository usuarios, ChatDirectoRepository directos,
            ConversacionRepository conversaciones, GestionMiembros gestion, LecturaConversacionesService lectura,
            TransactionTemplate transacciones, Clock clock) {
        this.usuarios = usuarios;
        this.directos = directos;
        this.conversaciones = conversaciones;
        this.gestion = gestion;
        this.lectura = lectura;
        this.transacciones = transacciones;
        this.clock = clock;
    }

    /**
     * Devuelve el chat con esa persona y lo crea si no existía (spec 8.4). Si dos lo crean a la vez, la
     * restricción única del par hace fallar una transacción: se revierte entera y se lee el chat que ganó.
     */
    public Apertura abrir(UUID yo, UUID otro) {
        if (yo.equals(otro)) {
            throw ErroresConversacion.chatConsigoMismo();
        }
        if (usuarios.verificadoPorId(otro).isEmpty()) {
            throw ErroresUsuario.noEncontrado();
        }
        Optional<UUID> existente = directos.buscar(yo, otro);
        if (existente.isPresent()) {
            return new Apertura(lectura.detalle(yo, existente.get()), false);
        }
        try {
            UUID nueva = transacciones.execute(estado -> crear(yo, otro));
            return new Apertura(lectura.detalle(yo, nueva), true);
        } catch (DuplicateKeyException carrera) {
            return new Apertura(lectura.detalle(yo, directos.buscar(yo, otro).orElseThrow()), false);
        }
    }

    private UUID crear(UUID yo, UUID otro) {
        UUID id = conversaciones.crear(TipoConversacion.DIRECTA, Tiempos.ahora(clock));
        gestion.incorporar(id, yo, Rol.MIEMBRO, 1);
        gestion.incorporar(id, otro, Rol.MIEMBRO, 1);
        directos.registrar(id, yo, otro);
        return id;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionController.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversaciones")
public class ConversacionController {

    private final ChatDirectoService chats;
    private final LecturaConversacionesService lectura;

    public ConversacionController(ChatDirectoService chats, LecturaConversacionesService lectura) {
        this.chats = chats;
        this.lectura = lectura;
    }

    @PostMapping("/directas")
    public ResponseEntity<ConversacionDetalle> abrirDirecta(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SolicitudChatDirecto solicitud) {
        ChatDirectoService.Apertura apertura = chats.abrir(UsuarioAutenticado.id(jwt), solicitud.usuarioId());
        return ResponseEntity.status(apertura.creada() ? HttpStatus.CREATED : HttpStatus.OK).body(apertura.detalle());
    }

    @GetMapping("/{id}")
    public ConversacionDetalle detalle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lectura.detalle(UsuarioAutenticado.id(jwt), id);
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): chats directos sin duplicados y detalle de la conversación"
```

---
### Task 4: Envío de mensajes

**Files:**
- Create en `backend/src/main/java/com/hellocr/mensajes/`: `TipoMensaje.java`, `EventoDto.java`, `MensajeDto.java`, `SolicitudEnvio.java`, `MensajeEnviado.java`, `MensajeRepository.java`, `MarcasRepository.java`, `MensajesProperties.java`, `LimiteMensajes.java`, `EnvioMensajesService.java`
- Modify: `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/hellocr/mensajes/EnvioMensajesServiceTest.java`

**Interfaces:**
- Consumes: `ConversacionRepository.bloquear`, `ConsultaMembresia.esMiembroActivo`, `ErroresConversacion`, `Tiempos`, `CodigoError.TEXTO_MUY_LARGO/DEMASIADOS_MENSAJES` (Task 2); `ChatDirectoService` (Task 3, en los tests).
- Produces: `enum TipoMensaje { TEXTO, ADJUNTO, EVENTO }`; `record EventoDto(String evento, UUID afectadoId, String valor)`; `record MensajeDto(UUID conversacionId, long secuencia, UUID idCliente, UUID remitenteId, TipoMensaje tipo, String texto, EventoDto evento, Instant creadoEn)`; `record SolicitudEnvio(UUID idCliente, UUID conversacionId, String texto)`; evento `record MensajeEnviado(MensajeDto mensaje)` (se publica dentro de la transacción).
- Produces: `MensajeRepository.COLUMNAS`, `DESDE` (alias `m` y `e`) y `MAPEO` (`RowMapper<MensajeDto>`), `siguienteSecuencia(UUID)`, `porIdCliente(UUID remitente, UUID idCliente)`, `insertarTexto(...)`; `MarcasRepository.avanzarPropias(UUID conversacionId, UUID usuarioId, long secuencia)`.
- Produces: `EnvioMensajesService.enviar(UUID remitente, SolicitudEnvio): Resultado` con `record Resultado(MensajeDto mensaje, boolean nuevo)` (`nuevo = false` si el `idCliente` ya existía). Errores: `VALIDACION`, `TEXTO_MUY_LARGO`, `DEMASIADOS_MENSAJES`, `NO_ENCONTRADO`, `NO_ES_MIEMBRO`.
- Produces: propiedades `app.mensajes.limite-cantidad`/`limite-ventana`, `app.grupos.max-miembros` y `app.tiempo-real.latido` en `application.yml` (las usan las Tasks 7 y 9); `app.grupos.max-miembros: 5` en el perfil `test`.

- [ ] **Step 1: Configuración**

Archivo: `backend/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: hellocr
  datasource:
    url: jdbc:postgresql://localhost:5432/hellocr
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC

app:
  zona-horaria: America/Costa_Rica
  url-publica: http://localhost:5173
  jwt:
    duracion-access: 15m
    duracion-refresh: 30d
    cookie-segura: true
  correo:
    modo: consola
    asincrono: true
    remitente: "HelloCR <no-responder@hellocr.local>"
    duracion-verificacion: 24h
    duracion-recuperacion: 1h
    espera-reenvio: 1m
  cuentas:
    dias-sin-verificar: 7
  login:
    max-intentos: 5
    bloqueo: 15m
  mensajes:
    limite-cantidad: 30
    limite-ventana: 10s
  grupos:
    max-miembros: 50
  tiempo-real:
    latido: 10s
# Credenciales de la base y app.jwt.secreto van en application-local.yml (ignorado por git).
```

Archivo: `backend/src/test/resources/application-test.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/hellocr_test
    username: ${TEST_DB_USER:hellocr}
    password: ${TEST_DB_PASSWORD:hellocr}

app:
  jwt:
    secreto: secreto-solo-para-pruebas-con-mas-de-32-caracteres
  correo:
    asincrono: false
  grupos:
    # Chico a propósito: así los tests de GRUPO_LLENO no necesitan 50 cuentas.
    max-miembros: 5
```

- [ ] **Step 2: Test**

Archivo: `backend/src/test/java/com/hellocr/mensajes/EnvioMensajesServiceTest.java`

```java
package com.hellocr.mensajes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class EnvioMensajesServiceTest extends PruebaIntegracion {

    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private ApplicationEvents eventos;

    private UUID ana;
    private UUID luis;
    private UUID chat;

    @BeforeEach
    void abrirChat() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        chat = chats.abrir(ana, luis).detalle().id();
    }

    @Test
    void enviarAsignaSecuenciasConsecutivasYAvanzaLasMarcasDelRemitente() {
        MensajeDto primero = enviar(ana, "Hola").mensaje();
        MensajeDto segundo = enviar(luis, "¡Buenas!").mensaje();

        assertThat(primero.secuencia()).isEqualTo(1);
        assertThat(primero.tipo()).isEqualTo(TipoMensaje.TEXTO);
        assertThat(primero.remitenteId()).isEqualTo(ana);
        assertThat(primero.conversacionId()).isEqualTo(chat);
        assertThat(primero.creadoEn()).isEqualTo(reloj.instant());
        assertThat(segundo.secuencia()).isEqualTo(2);
        assertThat(marcas(ana)).containsEntry("ultima_entregada", 1L).containsEntry("ultima_leida", 1L);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 2L).containsEntry("ultima_leida", 2L);
        assertThat(eventos.stream(MensajeEnviado.class)).extracting(evento -> evento.mensaje().secuencia())
                .containsExactly(1L, 2L);
    }

    @Test
    void elTextoSeGuardaSinEspaciosAlrededor() {
        assertThat(enviar(ana, "  hola \n").mensaje().texto()).isEqualTo("hola");
        assertThat(jdbc.queryForObject("SELECT texto FROM mensajes", String.class)).isEqualTo("hola");
    }

    @Test
    void reenviarElMismoIdClienteDevuelveElOriginalSinPublicarlo() {
        UUID idCliente = UUID.randomUUID();
        envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "original"));
        enviar(luis, "otro");

        EnvioMensajesService.Resultado repetido = envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "reintento"));

        assertThat(repetido.nuevo()).isFalse();
        assertThat(repetido.mensaje().secuencia()).isEqualTo(1);
        assertThat(repetido.mensaje().texto()).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isEqualTo(2);
        assertThat(eventos.stream(MensajeEnviado.class)).hasSize(2);
    }

    @Test
    void unaConversacionInexistenteOAjenaSeRechaza() {
        UUID sofia = crearUsuario("sofia").getId();

        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), UUID.randomUUID(), "hola")))
                .satisfies(codigo(CodigoError.NO_ENCONTRADO));
        assertThatThrownBy(() -> enviar(sofia, "hola")).satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
    }

    @Test
    void losMensajesVaciosOConCaracteresNulosSonInvalidos() {
        for (String texto : Arrays.asList("   ", "", null, "hola\u0000mundo")) {
            assertThatThrownBy(() -> enviar(ana, texto)).as(String.valueOf(texto))
                    .satisfies(codigo(CodigoError.VALIDACION));
        }
        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(null, chat, "hola")))
                .satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), null, "hola")))
                .satisfies(codigo(CodigoError.VALIDACION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isZero();
    }

    @Test
    void unMensajeDe4096EmojisEntraYUnoMasEsDemasiadoLargo() {
        String emojis = "😀".repeat(4096);

        assertThat(enviar(ana, emojis).mensaje().texto()).isEqualTo(emojis);
        assertThat(jdbc.queryForObject("SELECT length(texto) FROM mensajes", Integer.class)).isEqualTo(4096);
        assertThatThrownBy(() -> enviar(ana, emojis + "😀")).satisfies(codigo(CodigoError.TEXTO_MUY_LARGO));
        assertThatThrownBy(() -> enviar(ana, "a".repeat(4097))).satisfies(codigo(CodigoError.TEXTO_MUY_LARGO));
    }

    @Test
    void masDeTreintaMensajesEnDiezSegundosSeFrenan() {
        for (int i = 1; i <= 30; i++) {
            enviar(ana, "mensaje " + i);
        }

        assertThatThrownBy(() -> enviar(ana, "uno más")).satisfies(codigo(CodigoError.DEMASIADOS_MENSAJES));
        reloj.avanzar(Duration.ofSeconds(10));
        assertThat(enviar(ana, "uno más").mensaje().secuencia()).isEqualTo(31);
    }

    @Test
    void veinteEnviosSimultaneosRecibenLasSecuenciasDel1Al20() throws Exception {
        List<Long> secuencias = enParalelo(20, () -> enviar(ana, "hola").mensaje().secuencia());

        assertThat(secuencias).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    void elMismoIdClienteCincoVecesALaVezCreaUnSoloMensaje() throws Exception {
        UUID idCliente = UUID.randomUUID();

        List<EnvioMensajesService.Resultado> resultados =
                enParalelo(5, () -> envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "hola")));

        assertThat(resultados).extracting(resultado -> resultado.mensaje().secuencia()).containsOnly(1L);
        assertThat(resultados).filteredOn(EnvioMensajesService.Resultado::nuevo).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isEqualTo(1);
    }

    private EnvioMensajesService.Resultado enviar(UUID remitente, String texto) {
        return envio.enviar(remitente, new SolicitudEnvio(UUID.randomUUID(), chat, texto));
    }

    private java.util.Map<String, Object> marcas(UUID usuario) {
        return jdbc.queryForMap("SELECT ultima_entregada, ultima_leida FROM miembros WHERE conversacion_id = ? "
                + "AND usuario_id = ?", chat, usuario);
    }

    private static Consumer<Throwable> codigo(CodigoError esperado) {
        return error -> assertThat(error).isInstanceOfSatisfying(ErrorNegocio.class,
                negocio -> assertThat(negocio.codigo()).isEqualTo(esperado));
    }
}
```

- [ ] **Step 3: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=EnvioMensajesServiceTest`
Expected: FAIL de compilación — `cannot find symbol: class EnvioMensajesService`.

- [ ] **Step 4: DTOs, eventos y repositorios**

Archivo: `backend/src/main/java/com/hellocr/mensajes/TipoMensaje.java`

```java
package com.hellocr.mensajes;

public enum TipoMensaje {
    TEXTO, ADJUNTO, EVENTO
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/EventoDto.java`

```java
package com.hellocr.mensajes;

import java.util.UUID;

/** Datos de un mensaje EVENTO: "Ana agregó a Luis", "Ana cambió el nombre a …". */
public record EventoDto(String evento, UUID afectadoId, String valor) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MensajeDto.java`

```java
package com.hellocr.mensajes;

import java.time.Instant;
import java.util.UUID;

/** Spec 7.3. En un EVENTO, texto es null y evento trae los datos; en un TEXTO, al revés. El plan 3 agrega archivo. */
public record MensajeDto(UUID conversacionId, long secuencia, UUID idCliente, UUID remitenteId, TipoMensaje tipo,
        String texto, EventoDto evento, Instant creadoEn) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/SolicitudEnvio.java`

```java
package com.hellocr.mensajes;

import java.util.UUID;

/** Cuerpo de /app/mensajes.enviar. El idCliente lo genera el celular: reintentar con el mismo no duplica. */
public record SolicitudEnvio(UUID idCliente, UUID conversacionId, String texto) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MensajeEnviado.java`

```java
package com.hellocr.mensajes;

/** Se guardó un mensaje nuevo (texto o evento). Tiempo real lo reparte después del commit. */
public record MensajeEnviado(MensajeDto mensaje) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MensajeRepository.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.Tiempos;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MensajeRepository {

    /** Columnas de un MensajeDto. La consulta tiene que usar {@link #DESDE} (alias m y e). */
    public static final String COLUMNAS = """
            m.conversacion_id, m.secuencia, m.id_cliente, m.remitente_id, m.tipo, m.texto, m.creado_en,
            e.evento, e.afectado_id, e.valor""";
    public static final String DESDE = "mensajes m LEFT JOIN eventos_grupo e ON e.mensaje_id = m.id";

    public static final RowMapper<MensajeDto> MAPEO = (fila, numero) -> {
        String evento = fila.getString("evento");
        return new MensajeDto(fila.getObject("conversacion_id", UUID.class), fila.getLong("secuencia"),
                fila.getObject("id_cliente", UUID.class), fila.getObject("remitente_id", UUID.class),
                TipoMensaje.valueOf(fila.getString("tipo")), fila.getString("texto"),
                evento == null ? null
                        : new EventoDto(evento, fila.getObject("afectado_id", UUID.class), fila.getString("valor")),
                Tiempos.instante(fila, "creado_en"));
    };

    private final JdbcClient jdbc;

    public MensajeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Llamar con la conversación bloqueada (ConversacionRepository.bloquear). */
    public long siguienteSecuencia(UUID conversacionId) {
        return jdbc.sql("SELECT COALESCE(MAX(secuencia), 0) + 1 FROM mensajes WHERE conversacion_id = :c")
                .param("c", conversacionId)
                .query(Long.class).single();
    }

    public Optional<MensajeDto> porIdCliente(UUID remitenteId, UUID idCliente) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM " + DESDE + " WHERE m.remitente_id = :r AND m.id_cliente = :ic")
                .param("r", remitenteId).param("ic", idCliente)
                .query(MAPEO).optional();
    }

    public MensajeDto insertarTexto(UUID conversacionId, long secuencia, UUID remitenteId, UUID idCliente,
            String texto, Instant ahora) {
        jdbc.sql("""
                        INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto, creado_en)
                        VALUES (:c, :s, :r, :ic, 'TEXTO', :t, :ahora)
                        """)
                .param("c", conversacionId).param("s", secuencia).param("r", remitenteId).param("ic", idCliente)
                .param("t", texto).param("ahora", Tiempos.sql(ahora))
                .update();
        return new MensajeDto(conversacionId, secuencia, idCliente, remitenteId, TipoMensaje.TEXTO, texto, null, ahora);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MarcasRepository.java`

```java
package com.hellocr.mensajes;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las marcas de agua de cada miembro (spec 6, nota 1): nunca bajan, por eso todo usa GREATEST. */
@Repository
public class MarcasRepository {

    private final JdbcClient jdbc;

    public MarcasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Los mensajes propios cuentan como entregados y leídos; responder implica haber leído lo anterior. */
    public void avanzarPropias(UUID conversacionId, UUID usuarioId, long secuencia) {
        jdbc.sql("""
                        UPDATE miembros
                        SET ultima_entregada = GREATEST(ultima_entregada, :s), ultima_leida = GREATEST(ultima_leida, :s)
                        WHERE conversacion_id = :c AND usuario_id = :u
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", secuencia)
                .update();
    }
}
```

- [ ] **Step 5: Límite de frecuencia y servicio de envío**

Archivo: `backend/src/main/java/com/hellocr/mensajes/MensajesProperties.java`

```java
package com.hellocr.mensajes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.mensajes")
@Validated
public record MensajesProperties(@Min(1) int limiteCantidad, @NotNull Duration limiteVentana) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/LimiteMensajes.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ventana deslizante en memoria (hay un solo servidor): app.mensajes.limite-cantidad en limite-ventana. */
@Component
public class LimiteMensajes {

    private final Map<UUID, Deque<Instant>> envios = new ConcurrentHashMap<>();
    private final MensajesProperties propiedades;
    private final Clock clock;

    public LimiteMensajes(MensajesProperties propiedades, Clock clock) {
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Cuenta un envío o lanza DEMASIADOS_MENSAJES si el usuario ya llegó al límite. */
    public void consumir(UUID usuarioId) {
        Instant ahora = clock.instant();
        Instant desde = ahora.minus(propiedades.limiteVentana());
        boolean[] permitido = {false};
        envios.compute(usuarioId, (clave, anteriores) -> {
            Deque<Instant> recientes = anteriores == null ? new ArrayDeque<>() : anteriores;
            while (!recientes.isEmpty() && !recientes.peekFirst().isAfter(desde)) {
                recientes.pollFirst();
            }
            if (recientes.size() < propiedades.limiteCantidad()) {
                recientes.addLast(ahora);
                permitido[0] = true;
            }
            return recientes;
        });
        if (!permitido[0]) {
            throw new ErrorNegocio(CodigoError.DEMASIADOS_MENSAJES,
                    "Estás enviando mensajes muy rápido. Esperá unos segundos.");
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void olvidarInactivos() {
        Instant desde = clock.instant().minus(propiedades.limiteVentana());
        envios.entrySet().removeIf(entrada -> entrada.getValue().isEmpty()
                || !entrada.getValue().peekLast().isAfter(desde));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/EnvioMensajesService.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.ErroresConversacion;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Spec 8.1: valida, asigna la secuencia y guarda. Quién se entera lo deciden los que escuchan MensajeEnviado. */
@Service
public class EnvioMensajesService {

    static final int MAX_CARACTERES = 4096;

    public record Resultado(MensajeDto mensaje, boolean nuevo) {
    }

    private final ConversacionRepository conversaciones;
    private final ConsultaMembresia membresia;
    private final MensajeRepository mensajes;
    private final MarcasRepository marcas;
    private final LimiteMensajes limite;
    private final ApplicationEventPublisher eventos;
    private final TransactionTemplate transacciones;
    private final Clock clock;

    public EnvioMensajesService(ConversacionRepository conversaciones, ConsultaMembresia membresia,
            MensajeRepository mensajes, MarcasRepository marcas, LimiteMensajes limite,
            ApplicationEventPublisher eventos, TransactionTemplate transacciones, Clock clock) {
        this.conversaciones = conversaciones;
        this.membresia = membresia;
        this.mensajes = mensajes;
        this.marcas = marcas;
        this.limite = limite;
        this.eventos = eventos;
        this.transacciones = transacciones;
        this.clock = clock;
    }

    public Resultado enviar(UUID remitenteId, SolicitudEnvio solicitud) {
        String texto = validar(solicitud);
        limite.consumir(remitenteId);
        try {
            return transacciones.execute(estado -> guardar(remitenteId, solicitud, texto));
        } catch (DuplicateKeyException carrera) {
            // Otra transacción guardó el mismo idCliente entre la consulta y el INSERT: es un reintento.
            return mensajes.porIdCliente(remitenteId, solicitud.idCliente())
                    .map(original -> new Resultado(original, false))
                    .orElseThrow(() -> carrera);
        }
    }

    private Resultado guardar(UUID remitenteId, SolicitudEnvio solicitud, String texto) {
        UUID conversacionId = solicitud.conversacionId();
        if (conversaciones.bloquear(conversacionId).isEmpty()) {
            throw ErroresConversacion.noEncontrada();
        }
        if (!membresia.esMiembroActivo(conversacionId, remitenteId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        Optional<MensajeDto> repetido = mensajes.porIdCliente(remitenteId, solicitud.idCliente());
        if (repetido.isPresent()) {
            return new Resultado(repetido.get(), false);
        }
        long secuencia = mensajes.siguienteSecuencia(conversacionId);
        MensajeDto mensaje = mensajes.insertarTexto(conversacionId, secuencia, remitenteId, solicitud.idCliente(),
                texto, Tiempos.ahora(clock));
        marcas.avanzarPropias(conversacionId, remitenteId, secuencia);
        eventos.publishEvent(new MensajeEnviado(mensaje));
        return new Resultado(mensaje, true);
    }

    /**
     * Devuelve el texto recortado. Se cuenta por punto de código, como PostgreSQL: un emoji es un carácter
     * aunque en Java ocupe dos. El carácter nulo se rechaza acá porque PostgreSQL no lo admite en un texto.
     */
    static String validar(SolicitudEnvio solicitud) {
        if (solicitud == null || solicitud.idCliente() == null || solicitud.conversacionId() == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Faltan datos del mensaje.");
        }
        String texto = solicitud.texto() == null ? "" : solicitud.texto().strip();
        if (texto.isEmpty()) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "El mensaje está vacío.");
        }
        if (texto.indexOf('\u0000') >= 0) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "El mensaje tiene caracteres no permitidos.");
        }
        if (texto.codePointCount(0, texto.length()) > MAX_CARACTERES) {
            throw new ErrorNegocio(CodigoError.TEXTO_MUY_LARGO, "El mensaje puede tener hasta 4096 caracteres.");
        }
        return texto;
    }
}
```

- [ ] **Step 6: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): envío de mensajes con secuencia por conversación, idempotencia y límite de frecuencia"
```

---

### Task 5: Marcas de entregado y leído

**Files:**
- Create en `backend/src/main/java/com/hellocr/mensajes/`: `Marcas.java`, `EstadoActualizado.java`, `MarcasService.java`
- Modify: `backend/src/main/java/com/hellocr/mensajes/MarcasRepository.java`
- Test: `backend/src/test/java/com/hellocr/mensajes/MarcasServiceTest.java`

**Interfaces:**
- Consumes: `ConsultaMembresia`, `MembresiaRepository.visiblePara`, `ErroresConversacion` (Task 2); `EnvioMensajesService`, `MarcasRepository` (Task 4); `ChatDirectoService` (Task 3, en los tests).
- Produces: `MarcasService.entregados(UUID usuarioId, UUID conversacionId, Long hasta)` y `leidos(...)` (recortan `hasta` a la mayor secuencia visible; si las marcas cambian publican `EstadoActualizado`); `record EstadoActualizado(UUID conversacionId, UUID usuarioId, long ultimaEntregada, long ultimaLeida)`; `record Marcas(long ultimaEntregada, long ultimaLeida)`; `MarcasRepository.maxVisible(UUID conversacionId, UUID usuarioId)`, `marcarEntregados(...)` y `marcarLeidos(...)`: `Optional<Marcas>` (vacío si no cambió nada).

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/mensajes/MarcasServiceTest.java`

```java
package com.hellocr.mensajes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class MarcasServiceTest extends PruebaIntegracion {

    @Autowired
    private MarcasService marcasService;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private ApplicationEvents eventos;

    private UUID ana;
    private UUID luis;
    private UUID chat;

    @BeforeEach
    void chatConTresMensajesDeAna() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        chat = chats.abrir(ana, luis).detalle().id();
        for (int i = 1; i <= 3; i++) {
            envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), chat, "mensaje " + i));
        }
    }

    @Test
    void entregadosAvanzaLaMarcaYAvisa() {
        marcasService.entregados(luis, chat, 2L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 2L).containsEntry("ultima_leida", 0L);
        assertThat(eventos.stream(EstadoActualizado.class)).containsExactly(new EstadoActualizado(chat, luis, 2, 0));
    }

    @Test
    void leidosTambienMarcaComoEntregado() {
        marcasService.leidos(luis, chat, 3L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
        assertThat(eventos.stream(EstadoActualizado.class)).containsExactly(new EstadoActualizado(chat, luis, 3, 3));
    }

    @Test
    void lasMarcasNuncaBajanYSinCambiosNoHayAviso() {
        marcasService.leidos(luis, chat, 3L);

        marcasService.entregados(luis, chat, 1L);
        marcasService.leidos(luis, chat, 2L);
        marcasService.leidos(luis, chat, 0L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
        assertThat(eventos.stream(EstadoActualizado.class)).hasSize(1);
    }

    @Test
    void unAcuseMasAllaDeLoQueExisteSeRecorta() {
        marcasService.entregados(luis, chat, 999L);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 0L);

        marcasService.leidos(luis, chat, Long.MAX_VALUE);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
    }

    @Test
    void faltanDatosOQuienNoEsMiembroActivoSonErrores() {
        UUID sofia = crearUsuario("sofia").getId();

        assertThatThrownBy(() -> marcasService.leidos(luis, chat, null)).satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> marcasService.leidos(luis, null, 1L)).satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> marcasService.leidos(sofia, chat, 1L)).satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
        assertThatThrownBy(() -> marcasService.leidos(luis, UUID.randomUUID(), 1L))
                .satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
    }

    private Map<String, Object> marcas(UUID usuario) {
        return jdbc.queryForMap("SELECT ultima_entregada, ultima_leida FROM miembros WHERE conversacion_id = ? "
                + "AND usuario_id = ?", chat, usuario);
    }

    private static Consumer<Throwable> codigo(CodigoError esperado) {
        return error -> assertThat(error).isInstanceOfSatisfying(ErrorNegocio.class,
                negocio -> assertThat(negocio.codigo()).isEqualTo(esperado));
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=MarcasServiceTest`
Expected: FAIL de compilación — `cannot find symbol: class MarcasService`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/mensajes/Marcas.java`

```java
package com.hellocr.mensajes;

public record Marcas(long ultimaEntregada, long ultimaLeida) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/EstadoActualizado.java`

```java
package com.hellocr.mensajes;

import java.util.UUID;

/** Cambiaron las marcas de un miembro. Tiempo real lo reparte como ESTADO_ACTUALIZADO después del commit. */
public record EstadoActualizado(UUID conversacionId, UUID usuarioId, long ultimaEntregada, long ultimaLeida) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MarcasRepository.java`

```java
package com.hellocr.mensajes;

import com.hellocr.conversaciones.MembresiaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las marcas de agua de cada miembro (spec 6, nota 1): nunca bajan, por eso todo usa GREATEST o compara antes. */
@Repository
public class MarcasRepository {

    private final JdbcClient jdbc;

    public MarcasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Los mensajes propios cuentan como entregados y leídos; responder implica haber leído lo anterior. */
    public void avanzarPropias(UUID conversacionId, UUID usuarioId, long secuencia) {
        jdbc.sql("""
                        UPDATE miembros
                        SET ultima_entregada = GREATEST(ultima_entregada, :s), ultima_leida = GREATEST(ultima_leida, :s)
                        WHERE conversacion_id = :c AND usuario_id = :u
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("s", secuencia)
                .update();
    }

    /** La mayor secuencia que ese usuario puede ver en la conversación (0 si no ve ninguna). */
    public long maxVisible(UUID conversacionId, UUID usuarioId) {
        return jdbc.sql("SELECT COALESCE(MAX(m.secuencia), 0) FROM mensajes m WHERE m.conversacion_id = :c AND "
                        + MembresiaRepository.visiblePara("m"))
                .param("c", conversacionId).param("yo", usuarioId)
                .query(Long.class).single();
    }

    /** Vacío si la marca ya estaba en esa secuencia o más adelante. */
    public Optional<Marcas> marcarEntregados(UUID conversacionId, UUID usuarioId, long hasta) {
        return jdbc.sql("""
                        UPDATE miembros SET ultima_entregada = :h
                        WHERE conversacion_id = :c AND usuario_id = :u AND ultima_entregada < :h
                        RETURNING ultima_entregada, ultima_leida
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("h", hasta)
                .query((fila, n) -> new Marcas(fila.getLong("ultima_entregada"), fila.getLong("ultima_leida")))
                .optional();
    }

    /** Leer también entrega. Vacío si la marca de leído ya estaba en esa secuencia o más adelante. */
    public Optional<Marcas> marcarLeidos(UUID conversacionId, UUID usuarioId, long hasta) {
        return jdbc.sql("""
                        UPDATE miembros SET ultima_leida = :h, ultima_entregada = GREATEST(ultima_entregada, :h)
                        WHERE conversacion_id = :c AND usuario_id = :u AND ultima_leida < :h
                        RETURNING ultima_entregada, ultima_leida
                        """)
                .param("c", conversacionId).param("u", usuarioId).param("h", hasta)
                .query((fila, n) -> new Marcas(fila.getLong("ultima_entregada"), fila.getLong("ultima_leida")))
                .optional();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/MarcasService.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spec 8.2: acuses de entregado y leído. */
@Service
public class MarcasService {

    private final ConsultaMembresia membresia;
    private final MarcasRepository marcas;
    private final ApplicationEventPublisher eventos;

    public MarcasService(ConsultaMembresia membresia, MarcasRepository marcas, ApplicationEventPublisher eventos) {
        this.membresia = membresia;
        this.marcas = marcas;
        this.eventos = eventos;
    }

    @Transactional
    public void entregados(UUID usuarioId, UUID conversacionId, Long hasta) {
        actualizar(usuarioId, conversacionId, hasta, false);
    }

    @Transactional
    public void leidos(UUID usuarioId, UUID conversacionId, Long hasta) {
        actualizar(usuarioId, conversacionId, hasta, true);
    }

    /** hasta se recorta a lo que el usuario puede ver: nunca quedan marcas por encima de los mensajes reales. */
    private void actualizar(UUID usuarioId, UUID conversacionId, Long hasta, boolean leido) {
        if (conversacionId == null || hasta == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Faltan la conversación o la secuencia.");
        }
        if (!membresia.esMiembroActivo(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        long limite = Math.min(hasta, marcas.maxVisible(conversacionId, usuarioId));
        if (limite <= 0) {
            return;
        }
        Optional<Marcas> nuevas = leido
                ? marcas.marcarLeidos(conversacionId, usuarioId, limite)
                : marcas.marcarEntregados(conversacionId, usuarioId, limite);
        nuevas.ifPresent(m -> eventos.publishEvent(
                new EstadoActualizado(conversacionId, usuarioId, m.ultimaEntregada(), m.ultimaLeida())));
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): acuses de entregado y leído con marcas que nunca bajan"
```

---

### Task 6: Historial paginado

**Files:**
- Create en `backend/src/main/java/com/hellocr/mensajes/`: `PaginaMensajes.java`, `HistorialRepository.java`, `HistorialService.java`, `HistorialController.java`
- Test: `backend/src/test/java/com/hellocr/mensajes/HistorialControllerTest.java`

**Interfaces:**
- Consumes: `MensajeRepository.COLUMNAS/DESDE/MAPEO` (Task 4); `MembresiaRepository.visiblePara`, `ConsultaMembresia.fueMiembro`, `ErroresConversacion`, `ConversacionRepository`, `GestionMiembros` (Task 2); `EnvioMensajesService` (Task 4) y `ChatDirectoService` (Task 3) en los tests.
- Produces (API): `GET /api/conversaciones/{id}/mensajes?antesDe=&despuesDe=&limite=` → `{mensajes: [MensajeDto], hayMas}` en orden ascendente; `limite` por defecto 50, de 1 a 200; `antesDe` y `despuesDe` no van juntos; 404 para quien nunca fue miembro.
- Produces: `record PaginaMensajes(List<MensajeDto> mensajes, boolean hayMas)`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/mensajes/HistorialControllerTest.java`

```java
package com.hellocr.mensajes;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class HistorialControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private ConversacionRepository conversaciones;
    @Autowired
    private GestionMiembros gestion;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private UUID chat;

    @BeforeEach
    void chatConDoceMensajes() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        chat = chats.abrir(id(ana), id(luis)).detalle().id();
        for (int i = 1; i <= 12; i++) {
            enviar(id(ana), chat, "m" + i);
        }
    }

    @Test
    void sinParametrosDevuelveLosMasRecientesEnOrdenAscendente() throws Exception {
        pagina(luis, chat, "?limite=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(8, 9, 10, 11, 12)))
                .andExpect(jsonPath("$.mensajes[0].texto").value("m8"))
                .andExpect(jsonPath("$.mensajes[0].tipo").value("TEXTO"))
                .andExpect(jsonPath("$.mensajes[0].remitenteId").value(ana.usuarioId()))
                .andExpect(jsonPath("$.mensajes[0].creadoEn").value("2026-10-01T15:00:00Z"))
                .andExpect(jsonPath("$.hayMas").value(true));
    }

    @Test
    void antesDeTraeLaPaginaAnterior() throws Exception {
        pagina(luis, chat, "?antesDe=8&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(3, 4, 5, 6, 7)))
                .andExpect(jsonPath("$.hayMas").value(true));
        pagina(luis, chat, "?antesDe=3&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(1, 2)))
                .andExpect(jsonPath("$.hayMas").value(false));
    }

    @Test
    void despuesDeTraeLoQueFalta() throws Exception {
        pagina(luis, chat, "?despuesDe=10")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(11, 12)))
                .andExpect(jsonPath("$.hayMas").value(false));
        pagina(luis, chat, "?despuesDe=0&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.hayMas").value(true));
    }

    @Test
    void porDefectoTraeHasta50() throws Exception {
        pagina(luis, chat, "")
                .andExpect(jsonPath("$.mensajes.length()").value(12))
                .andExpect(jsonPath("$.hayMas").value(false));
    }

    @Test
    void losParametrosInvalidosSon400() throws Exception {
        pagina(luis, chat, "?antesDe=5&despuesDe=1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.antesDe").value("Pedí mensajes antesDe o despuesDe, no los dos."));
        pagina(luis, chat, "?limite=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.limite").value("El límite tiene que estar entre 1 y 200."));
        pagina(luis, chat, "?limite=201").andExpect(status().isBadRequest());
        pagina(luis, chat, "?antesDe=abc").andExpect(status().isBadRequest());
    }

    @Test
    void cadaUnoVeSoloLoDeSusPeriodosYQuienNuncaFueMiembroRecibe404() throws Exception {
        SesionPrueba sofia = sesionDe("sofia");
        UUID grupo = conversaciones.crear(TipoConversacion.GRUPO, reloj.instant());
        gestion.incorporar(grupo, id(ana), Rol.ADMIN, 1);
        enviarVarios(grupo, 3);
        gestion.incorporar(grupo, id(luis), Rol.MIEMBRO, 4);
        enviarVarios(grupo, 3);
        gestion.retirar(grupo, id(luis), 5);
        enviarVarios(grupo, 2);

        pagina(luis, grupo, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(4, 5)));
        pagina(ana, grupo, "").andExpect(jsonPath("$.mensajes.length()").value(8));
        pagina(sofia, grupo, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    private ResultActions pagina(SesionPrueba sesion, UUID conversacion, String consulta) throws Exception {
        return mvc.perform(get("/api/conversaciones/" + conversacion + "/mensajes" + consulta).with(con(sesion)));
    }

    private void enviarVarios(UUID conversacion, int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            enviar(id(ana), conversacion, "hola");
        }
    }

    private void enviar(UUID remitente, UUID conversacion, String texto) {
        envio.enviar(remitente, new SolicitudEnvio(UUID.randomUUID(), conversacion, texto));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=HistorialControllerTest`
Expected: FAIL — el endpoint todavía no existe (por ejemplo `Status expected:<200> but was:<404>`).

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/mensajes/PaginaMensajes.java`

```java
package com.hellocr.mensajes;

import java.util.List;

/** Mensajes en orden ascendente de secuencia. hayMas indica si quedan más en la dirección pedida. */
public record PaginaMensajes(List<MensajeDto> mensajes, boolean hayMas) {
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/HistorialRepository.java`

```java
package com.hellocr.mensajes;

import com.hellocr.conversaciones.MembresiaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Paginación keyset por secuencia (nunca OFFSET): igual de rápida en la primera página que en la número 500. */
@Repository
public class HistorialRepository {

    private static final String VISIBLES = "SELECT " + MensajeRepository.COLUMNAS + " FROM " + MensajeRepository.DESDE
            + " WHERE m.conversacion_id = :c AND " + MembresiaRepository.visiblePara("m");

    private final JdbcClient jdbc;

    public HistorialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Los últimos {@code cantidad} mensajes visibles anteriores a antesDe (o los más recientes), de mayor a menor. */
    public List<MensajeDto> anteriores(UUID conversacionId, UUID usuarioId, Long antesDe, int cantidad) {
        JdbcClient.StatementSpec consulta = jdbc.sql(VISIBLES
                        + (antesDe == null ? "" : " AND m.secuencia < :antes")
                        + " ORDER BY m.secuencia DESC LIMIT :cantidad")
                .param("c", conversacionId).param("yo", usuarioId).param("cantidad", cantidad);
        if (antesDe != null) {
            consulta = consulta.param("antes", antesDe);
        }
        return consulta.query(MensajeRepository.MAPEO).list();
    }

    /** Los primeros {@code cantidad} mensajes visibles posteriores a despuesDe, de menor a mayor. */
    public List<MensajeDto> posteriores(UUID conversacionId, UUID usuarioId, long despuesDe, int cantidad) {
        return jdbc.sql(VISIBLES + " AND m.secuencia > :despues ORDER BY m.secuencia LIMIT :cantidad")
                .param("c", conversacionId).param("yo", usuarioId).param("despues", despuesDe)
                .param("cantidad", cantidad)
                .query(MensajeRepository.MAPEO).list();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/HistorialService.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistorialService {

    static final int LIMITE_POR_DEFECTO = 50;
    static final int LIMITE_MAXIMO = 200;

    private final ConsultaMembresia membresia;
    private final HistorialRepository historial;

    public HistorialService(ConsultaMembresia membresia, HistorialRepository historial) {
        this.membresia = membresia;
        this.historial = historial;
    }

    /** Pide uno más de lo necesario para saber si hay más sin contar todo. */
    @Transactional(readOnly = true)
    public PaginaMensajes pagina(UUID usuarioId, UUID conversacionId, Long antesDe, Long despuesDe, Integer limite) {
        int cantidad = limite == null ? LIMITE_POR_DEFECTO : limite;
        if (cantidad < 1 || cantidad > LIMITE_MAXIMO) {
            throw ErrorNegocio.validacion("limite", "El límite tiene que estar entre 1 y 200.");
        }
        if (antesDe != null && despuesDe != null) {
            throw ErrorNegocio.validacion("antesDe", "Pedí mensajes antesDe o despuesDe, no los dos.");
        }
        if (!membresia.fueMiembro(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEncontrada();
        }
        if (despuesDe != null) {
            List<MensajeDto> siguientes = historial.posteriores(conversacionId, usuarioId, despuesDe, cantidad + 1);
            return new PaginaMensajes(List.copyOf(siguientes.subList(0, Math.min(cantidad, siguientes.size()))),
                    siguientes.size() > cantidad);
        }
        List<MensajeDto> previos = historial.anteriores(conversacionId, usuarioId, antesDe, cantidad + 1);
        List<MensajeDto> pagina = new ArrayList<>(previos.subList(0, Math.min(cantidad, previos.size())));
        Collections.reverse(pagina);
        return new PaginaMensajes(List.copyOf(pagina), previos.size() > cantidad);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/mensajes/HistorialController.java`

```java
package com.hellocr.mensajes;

import com.hellocr.comun.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Los mensajes se leen por REST y se envían solo por STOMP (spec 9.2). */
@RestController
@RequestMapping("/api/conversaciones/{id}/mensajes")
public class HistorialController {

    private final HistorialService historial;

    public HistorialController(HistorialService historial) {
        this.historial = historial;
    }

    @GetMapping
    public PaginaMensajes pagina(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) Long antesDe, @RequestParam(required = false) Long despuesDe,
            @RequestParam(required = false) Integer limite) {
        return historial.pagina(UsuarioAutenticado.id(jwt), id, antesDe, despuesDe, limite);
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): historial de mensajes paginado por secuencia y filtrado por periodos"
```

---
### Task 7: Conexión STOMP, sesiones y presencia

**Files:**
- Modify: `backend/pom.xml` (dependencia `spring-boot-starter-websocket`), `backend/src/main/java/com/hellocr/config/SecurityConfig.java` (`/ws` sin token HTTP)
- Create en `backend/src/main/java/com/hellocr/tiempoReal/`: `TiempoRealProperties.java`, `UsuarioStomp.java`, `AutenticacionStomp.java`, `SesionesWebSocket.java`, `WebSocketConfig.java`, `RegistroSesiones.java`, `EnviadorEventos.java`, `EnviadorEventosStomp.java`, `EventoPresencia.java`, `PresenciaService.java`, `EventosDeSesion.java`, `CierreSesiones.java`
- Create: `backend/src/main/java/com/hellocr/conversaciones/ConsultaPresencia.java`
- Create: `backend/src/test/java/com/hellocr/soporte/ClienteStomp.java`, `backend/src/test/java/com/hellocr/soporte/PruebaTiempoReal.java`
- Test: `backend/src/test/java/com/hellocr/tiempoReal/RegistroSesionesTest.java`, `backend/src/test/java/com/hellocr/tiempoReal/ConexionStompTest.java`, `backend/src/test/java/com/hellocr/tiempoReal/PresenciaTest.java`

**Interfaces:**
- Consumes: `JwtDecoder`, `UsuarioAutenticado` (plan 1); `SesionesRevocadas` (plan 1, lo publica `RefreshTokenService.revocarTodos`); `ChatDirectoRepository.contactosDe` (Task 3); `Tiempos` (Task 2).
- Produces: endpoint STOMP `/ws` (spec 7.1): `CONNECT` con `Authorization: Bearer <jwt>`, `SUBSCRIBE` solo a `AutenticacionStomp.COLA_EVENTOS` (`/user/queue/eventos`), `SEND` solo a los cinco destinos de 7.2; lo demás recibe un frame `ERROR` y se cierra la conexión.
- Produces: `AutenticacionStomp.decodificar(String authorization): Optional<Jwt>` (lo usa la renovación en la Task 8); `record UsuarioStomp(UUID id, Instant venceEn) implements Principal` (su nombre es el id).
- Produces: `RegistroSesiones` (implementa `ConsultaPresencia.enLinea(UUID)`): `abrir(sesionId, usuarioId, venceEn): boolean` (true si es la primera del usuario), `cerrar(sesionId): Optional<UUID>` (el usuario si era su última sesión), `renovar(sesionId, usuarioId, venceEn): boolean`, `vencidas(Instant)`, `sesionesDe(UUID)`, `cantidad()`.
- Produces: `EnviadorEventos.enviar(UUID usuarioId, Object evento)` (a `/user/queue/eventos` de todas sus sesiones; `EnviadorEventosStomp.DESTINO = "/queue/eventos"`); `record EventoPresencia(String tipo, UUID usuarioId, boolean enLinea, Instant ultimaConexion)`; `CierreSesiones.cerrarVencidas()` (cada 60 s) y el cierre de todas las sesiones de un usuario al recibir `SesionesRevocadas`.
- Produces (tests): `PruebaTiempoReal` (servidor en puerto aleatorio) con `nuevoCliente()`, `conectar(SesionPrueba): ClienteStomp` (espera la suscripción) y `esperarHasta(BooleanSupplier, String)`; `ClienteStomp` con `conectar(token)`, `conOrigen(String)`, `suscribir`, `enviar(destino, cuerpo)`, `enviarBytes`, `renovar(token)`, `esperar(tipo): JsonNode`, `sinEventos(tipo, Duration)`, `esperarCierre()`, `estaConectado()`, `erroresStomp()` y `cerrar()`.

- [ ] **Step 1: Dependencia de WebSocket**

Archivo: `backend/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>com.hellocr</groupId>
	<artifactId>backend</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>hellocr</name>
	<description/>
	<url/>
	<licenses>
		<license/>
	</licenses>
	<developers>
		<developer/>
	</developers>
	<scm>
		<connection/>
		<developerConnection/>
		<tag/>
		<url/>
	</scm>
	<properties>
		<java.version>25</java.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-mail</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-websocket</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
		</dependency>

		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-mail-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-compiler-plugin</artifactId>
				<executions>
					<execution>
						<id>default-compile</id>
						<phase>compile</phase>
						<goals>
							<goal>compile</goal>
						</goals>
						<configuration>
							<annotationProcessorPaths>
								<path>
									<groupId>org.springframework.boot</groupId>
									<artifactId>spring-boot-configuration-processor</artifactId>
								</path>
							</annotationProcessorPaths>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>

</project>
```

- [ ] **Step 2: Soporte de tests en tiempo real**

Archivo: `backend/src/test/java/com/hellocr/soporte/ClienteStomp.java`

```java
package com.hellocr.soporte;

import com.hellocr.tiempoReal.AutenticacionStomp;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/** Cliente STOMP de pruebas: se conecta a /ws, se suscribe a /user/queue/eventos y guarda lo que le llega. */
public class ClienteStomp {

    private static final Duration ESPERA = Duration.ofSeconds(5);

    private record Recibido(JsonNode evento, AtomicBoolean leido) {
    }

    private final String url;
    private final WebSocketHttpHeaders cabecerasHandshake = new WebSocketHttpHeaders();
    private final WebSocketStompClient cliente;
    private final List<Recibido> recibidos = new CopyOnWriteArrayList<>();
    private final List<String> erroresStomp = new CopyOnWriteArrayList<>();
    private final CountDownLatch cerrado = new CountDownLatch(1);
    private StompSession sesion;

    public ClienteStomp(String url) {
        this.url = url;
        this.cliente = new WebSocketStompClient(new StandardWebSocketClient());
        this.cliente.setMessageConverter(new CompositeMessageConverter(
                List.of(new ByteArrayMessageConverter(), new JacksonJsonMessageConverter())));
    }

    public ClienteStomp conOrigen(String origen) {
        cabecerasHandshake.setOrigin(origen);
        return this;
    }

    /** Conecta con ese access token (o sin token si es null) y se suscribe a la cola de eventos. */
    public void conectar(String token) throws Exception {
        StompHeaders conexion = new StompHeaders();
        if (token != null) {
            conexion.add("Authorization", "Bearer " + token);
        }
        sesion = cliente.connectAsync(url, cabecerasHandshake, conexion, new ManejadorSesion())
                .get(ESPERA.toSeconds(), TimeUnit.SECONDS);
        suscribir(AutenticacionStomp.COLA_EVENTOS);
    }

    public void suscribir(String destino) {
        sesion.subscribe(destino, new ManejadorEventos());
    }

    /** El cuerpo se envía como JSON. */
    public void enviar(String destino, Object cuerpo) {
        sesion.send(destino, cuerpo);
    }

    /** El cuerpo se envía tal cual, como application/octet-stream. */
    public void enviarBytes(String destino, byte[] cuerpo) {
        sesion.send(destino, cuerpo);
    }

    public void renovar(String token) {
        StompHeaders cabeceras = new StompHeaders();
        cabeceras.setDestination("/app/sesion.renovar");
        cabeceras.add("Authorization", "Bearer " + token);
        sesion.send(cabeceras, new byte[0]);
    }

    /** Espera el próximo evento de ese tipo que todavía no se leyó y lo marca como leído. */
    public JsonNode esperar(String tipo) throws InterruptedException {
        long limite = System.nanoTime() + ESPERA.toNanos();
        while (System.nanoTime() < limite) {
            for (Recibido recibido : recibidos) {
                if (tipo.equals(recibido.evento().path("tipo").asString())
                        && recibido.leido().compareAndSet(false, true)) {
                    return recibido.evento();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No llegó ningún evento " + tipo + ". Llegaron: " + recibidos);
    }

    /** Falla si durante la espera llega un evento de ese tipo que nadie leyó. */
    public void sinEventos(String tipo, Duration espera) throws InterruptedException {
        Thread.sleep(espera.toMillis());
        List<JsonNode> sobrantes = recibidos.stream()
                .filter(recibido -> !recibido.leido().get() && tipo.equals(recibido.evento().path("tipo").asString()))
                .map(Recibido::evento).toList();
        if (!sobrantes.isEmpty()) {
            throw new AssertionError("Llegaron eventos " + tipo + " inesperados: " + sobrantes);
        }
    }

    public boolean esperarCierre() throws InterruptedException {
        return cerrado.await(ESPERA.toSeconds(), TimeUnit.SECONDS);
    }

    public boolean estaConectado() {
        return sesion != null && sesion.isConnected();
    }

    /** El header message de cada frame ERROR que mandó el servidor. */
    public List<String> erroresStomp() {
        return List.copyOf(erroresStomp);
    }

    public void cerrar() {
        if (estaConectado()) {
            sesion.disconnect();
        }
        cliente.stop();
    }

    private class ManejadorEventos implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders cabeceras) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders cabeceras, Object cuerpo) {
            recibidos.add(new Recibido((JsonNode) cuerpo, new AtomicBoolean()));
        }
    }

    /** Recibe los frames ERROR (a nivel de sesión) y se entera cuando el servidor corta la conexión. */
    private class ManejadorSesion extends StompSessionHandlerAdapter {

        @Override
        public Type getPayloadType(StompHeaders cabeceras) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders cabeceras, Object cuerpo) {
            erroresStomp.add(String.valueOf(cabeceras.getFirst("message")));
        }

        @Override
        public void handleTransportError(StompSession sesionCaida, Throwable error) {
            cerrado.countDown();
        }
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebaTiempoReal.java`

```java
package com.hellocr.soporte;

import com.hellocr.tiempoReal.RegistroSesiones;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

/** Base de los tests con WebSocket: servidor real en un puerto aleatorio y clientes STOMP que se cierran solos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PruebaTiempoReal extends PruebaIntegracion {

    @Value("${local.server.port}")
    private int puerto;
    @Autowired
    private SimpUserRegistry usuariosStomp;
    @Autowired
    protected RegistroSesiones registroSesiones;

    private final List<ClienteStomp> clientes = new ArrayList<>();

    protected ClienteStomp nuevoCliente() {
        ClienteStomp cliente = new ClienteStomp("ws://localhost:" + puerto + "/ws");
        clientes.add(cliente);
        return cliente;
    }

    /** Conecta y espera a que el servidor registre la suscripción, para no perder los primeros eventos. */
    protected ClienteStomp conectar(SesionPrueba sesion) throws Exception {
        UUID usuario = UUID.fromString(sesion.usuarioId());
        int antes = sesionesSuscritas(usuario);
        ClienteStomp cliente = nuevoCliente();
        cliente.conectar(sesion.accessToken());
        esperarHasta(() -> sesionesSuscritas(usuario) > antes, "la suscripción de " + usuario);
        return cliente;
    }

    @AfterEach
    void cerrarClientes() throws InterruptedException {
        clientes.forEach(ClienteStomp::cerrar);
        clientes.clear();
        esperarHasta(() -> registroSesiones.cantidad() == 0, "que el servidor cierre todas las sesiones");
    }

    protected static void esperarHasta(BooleanSupplier condicion, String que) throws InterruptedException {
        long limite = System.nanoTime() + 5_000_000_000L;
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("Pasaron 5 s esperando " + que);
            }
            Thread.sleep(20);
        }
    }

    private int sesionesSuscritas(UUID usuario) {
        SimpUser registrado = usuariosStomp.getUser(usuario.toString());
        return registrado == null ? 0
                : (int) registrado.getSessions().stream().filter(s -> !s.getSubscriptions().isEmpty()).count();
    }
}
```

- [ ] **Step 3: Tests**

Archivo: `backend/src/test/java/com/hellocr/tiempoReal/RegistroSesionesTest.java`

```java
package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistroSesionesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");

    private final RegistroSesiones registro = new RegistroSesiones();
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();

    @Test
    void laPrimeraSesionPoneEnLineaYLaUltimaDesconecta() {
        assertThat(registro.abrir("s1", ana, T0)).isTrue();
        assertThat(registro.abrir("s2", ana, T0)).isFalse();
        assertThat(registro.enLinea(ana)).isTrue();

        assertThat(registro.cerrar("s1")).isEmpty();
        assertThat(registro.enLinea(ana)).isTrue();
        assertThat(registro.cerrar("s2")).contains(ana);
        assertThat(registro.enLinea(ana)).isFalse();
        assertThat(registro.cerrar("desconocida")).isEmpty();
    }

    @Test
    void soloSeVencenLasSesionesConElTokenVencido() {
        registro.abrir("s1", ana, T0.plusSeconds(60));
        registro.abrir("s2", luis, T0.plusSeconds(600));

        assertThat(registro.vencidas(T0)).isEmpty();
        assertThat(registro.vencidas(T0.plusSeconds(60))).containsExactly("s1");
    }

    @Test
    void renovarSoloFuncionaParaLaDuenaDeLaSesion() {
        registro.abrir("s1", ana, T0);

        assertThat(registro.renovar("s1", luis, T0.plusSeconds(900))).isFalse();
        assertThat(registro.renovar("otra", ana, T0.plusSeconds(900))).isFalse();
        assertThat(registro.renovar("s1", ana, T0.plusSeconds(900))).isTrue();
        assertThat(registro.vencidas(T0.plusSeconds(60))).isEmpty();
    }

    @Test
    void listaLasSesionesDeCadaUsuario() {
        registro.abrir("s1", ana, T0);
        registro.abrir("s2", ana, T0);
        registro.abrir("s3", luis, T0);

        assertThat(registro.sesionesDe(ana)).containsExactlyInAnyOrder("s1", "s2");
        assertThat(registro.cantidad()).isEqualTo(3);
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/tiempoReal/ConexionStompTest.java`

```java
package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.auth.RefreshTokenService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConexionStompTest extends PruebaTiempoReal {

    @Autowired
    private CierreSesiones cierre;
    @Autowired
    private RefreshTokenService refreshTokens;

    private SesionPrueba ana;

    @BeforeEach
    void sesion() throws Exception {
        ana = sesionDe("ana");
    }

    @Test
    void conUnTokenValidoSeConectaYQuedaEnLinea() throws Exception {
        conectar(ana);

        assertThat(registroSesiones.enLinea(UUID.fromString(ana.usuarioId()))).isTrue();
    }

    @Test
    void sinTokenElServidorRechazaLaConexion() {
        ClienteStomp cliente = nuevoCliente();

        assertThatThrownBy(() -> cliente.conectar(null)).isInstanceOf(ExecutionException.class);
        assertThat(cliente.erroresStomp()).containsExactly("Falta el token o no es válido.");
    }

    @Test
    void conUnTokenVencidoOAlteradoTambien() {
        reloj.avanzar(Duration.ofMinutes(16));
        assertThatThrownBy(() -> nuevoCliente().conectar(ana.accessToken())).isInstanceOf(ExecutionException.class);

        reloj.reiniciar();
        String alterado = ana.accessToken().substring(0, ana.accessToken().length() - 4) + "AAAA";
        assertThatThrownBy(() -> nuevoCliente().conectar(alterado)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void desdeUnOrigenNoPermitidoNiSiquieraAbreElWebSocket() {
        ClienteStomp cliente = nuevoCliente().conOrigen("https://malicioso.example");

        assertThatThrownBy(() -> cliente.conectar(ana.accessToken())).isInstanceOf(ExecutionException.class);
    }

    @Test
    void suscribirseAOtraColaCierraLaConexion() throws Exception {
        ClienteStomp cliente = conectar(ana);

        cliente.suscribir("/topic/todo");

        assertThat(cliente.esperarCierre()).isTrue();
        assertThat(cliente.erroresStomp()).containsExactly("Solo podés suscribirte a /user/queue/eventos.");
    }

    @Test
    void enviarAUnDestinoQueNoExisteCierraLaConexion() throws Exception {
        ClienteStomp cliente = conectar(ana);

        cliente.enviar("/app/otra.cosa", Map.of("hola", "mundo"));

        assertThat(cliente.esperarCierre()).isTrue();
        assertThat(cliente.erroresStomp()).containsExactly("Ese destino no existe.");
    }

    @Test
    void unaSesionConElTokenVencidoSeCierra() throws Exception {
        ClienteStomp cliente = conectar(ana);
        reloj.avanzar(Duration.ofMinutes(16));

        cierre.cerrarVencidas();

        assertThat(cliente.esperarCierre()).isTrue();
        esperarHasta(() -> !registroSesiones.enLinea(UUID.fromString(ana.usuarioId())), "que Ana quede desconectada");
    }

    @Test
    void revocarLasSesionesCierraLosWebSockets() throws Exception {
        ClienteStomp celular = conectar(ana);
        ClienteStomp pc = conectar(ana);

        refreshTokens.revocarTodos(UUID.fromString(ana.usuarioId()));

        assertThat(celular.esperarCierre()).isTrue();
        assertThat(pc.esperarCierre()).isTrue();
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/tiempoReal/PresenciaTest.java`

```java
package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.RelojAjustable;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class PresenciaTest extends PruebaTiempoReal {

    @Autowired
    private ChatDirectoService chats;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void anaYLuisTienenUnChat() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
        chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId()));
    }

    @Test
    void alConectarseLoAvisaSoloASusContactosDeChatsDirectos() throws Exception {
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp deSofia = conectar(sofia);

        conectar(ana);

        JsonNode presencia = deLuis.esperar("PRESENCIA");
        assertThat(presencia.path("usuarioId").asString()).isEqualTo(ana.usuarioId());
        assertThat(presencia.path("enLinea").asBoolean()).isTrue();
        deSofia.sinEventos("PRESENCIA", Duration.ofMillis(300));
    }

    @Test
    void conDosDispositivosSigueEnLineaHastaCerrarElUltimo() throws Exception {
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp celular = conectar(ana);
        deLuis.esperar("PRESENCIA");
        ClienteStomp pc = conectar(ana);

        celular.cerrar();
        deLuis.sinEventos("PRESENCIA", Duration.ofMillis(400));
        pc.cerrar();

        JsonNode desconexion = deLuis.esperar("PRESENCIA");
        assertThat(desconexion.path("enLinea").asBoolean()).isFalse();
        assertThat(desconexion.path("ultimaConexion").asString()).isEqualTo("2026-10-01T15:00:00Z");
        assertThat(jdbc.queryForObject("SELECT ultima_conexion FROM usuarios WHERE nombre_usuario = 'ana'",
                OffsetDateTime.class).toInstant()).isEqualTo(RelojAjustable.INICIO);
    }
}
```

- [ ] **Step 4: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='RegistroSesionesTest,ConexionStompTest,PresenciaTest'`
Expected: FAIL de compilación — `package com.hellocr.tiempoReal does not exist`.

- [ ] **Step 5: Sesiones y presencia**

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConsultaPresencia.java`

```java
package com.hellocr.conversaciones;

import java.util.UUID;

/** ¿Tiene alguna sesión en tiempo real abierta? La implementa tiempoReal.RegistroSesiones. */
public interface ConsultaPresencia {

    boolean enLinea(UUID usuarioId);
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/RegistroSesiones.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.conversaciones.ConsultaPresencia;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sesiones STOMP abiertas y cuándo vence el token de cada una, en memoria (hay un solo servidor). */
@Component
public class RegistroSesiones implements ConsultaPresencia {

    private record Sesion(UUID usuarioId, Instant venceEn) {
    }

    private final Map<String, Sesion> sesiones = new HashMap<>();

    /** Registra la sesión; devuelve true si es la primera del usuario (pasa a estar en línea). */
    public synchronized boolean abrir(String sesionId, UUID usuarioId, Instant venceEn) {
        boolean primera = !enLinea(usuarioId);
        sesiones.put(sesionId, new Sesion(usuarioId, venceEn));
        return primera;
    }

    /** Quita la sesión; si era la última del usuario, devuelve su id (pasa a estar desconectado). */
    public synchronized Optional<UUID> cerrar(String sesionId) {
        Sesion quitada = sesiones.remove(sesionId);
        if (quitada == null || enLinea(quitada.usuarioId())) {
            return Optional.empty();
        }
        return Optional.of(quitada.usuarioId());
    }

    /** Solo la dueña de la sesión puede extenderla. */
    public synchronized boolean renovar(String sesionId, UUID usuarioId, Instant venceEn) {
        Sesion actual = sesiones.get(sesionId);
        if (actual == null || !actual.usuarioId().equals(usuarioId)) {
            return false;
        }
        sesiones.put(sesionId, new Sesion(usuarioId, venceEn));
        return true;
    }

    public synchronized List<String> vencidas(Instant ahora) {
        return sesiones.entrySet().stream()
                .filter(entrada -> !entrada.getValue().venceEn().isAfter(ahora))
                .map(Map.Entry::getKey).toList();
    }

    public synchronized List<String> sesionesDe(UUID usuarioId) {
        return sesiones.entrySet().stream()
                .filter(entrada -> entrada.getValue().usuarioId().equals(usuarioId))
                .map(Map.Entry::getKey).toList();
    }

    @Override
    public synchronized boolean enLinea(UUID usuarioId) {
        return sesiones.values().stream().anyMatch(sesion -> sesion.usuarioId().equals(usuarioId));
    }

    public synchronized int cantidad() {
        return sesiones.size();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EnviadorEventos.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;

/** Manda un evento a /user/queue/eventos de todas las sesiones abiertas del usuario (si no tiene, no pasa nada). */
public interface EnviadorEventos {

    void enviar(UUID usuarioId, Object evento);
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EnviadorEventosStomp.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

@Component
public class EnviadorEventosStomp implements EnviadorEventos {

    /** Sin el prefijo /user: Spring lo agrega y lo resuelve a las sesiones del usuario. */
    public static final String DESTINO = "/queue/eventos";

    private final SimpMessagingTemplate plantilla;
    private final SimpUserRegistry usuarios;

    public EnviadorEventosStomp(SimpMessagingTemplate plantilla, SimpUserRegistry usuarios) {
        this.plantilla = plantilla;
        this.usuarios = usuarios;
    }

    /**
     * Un mensaje por sesión, con su sessionId. Spring 7.0 con setPreservePublishOrder(true) no puede repartir un
     * mismo mensaje a varias sesiones: desde la segunda falla ("Expected mutable SimpMessageHeaderAccessor") y ese
     * dispositivo se queda sin el evento.
     */
    @Override
    public void enviar(UUID usuarioId, Object evento) {
        SimpUser usuario = usuarios.getUser(usuarioId.toString());
        if (usuario == null) {
            return;
        }
        for (SimpSession sesion : usuario.getSessions()) {
            SimpMessageHeaderAccessor cabeceras = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            cabeceras.setSessionId(sesion.getId());
            cabeceras.setLeaveMutable(true);
            plantilla.convertAndSendToUser(usuarioId.toString(), DESTINO, evento, cabeceras.getMessageHeaders());
        }
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoPresencia.java`

```java
package com.hellocr.tiempoReal;

import java.time.Instant;
import java.util.UUID;

public record EventoPresencia(String tipo, UUID usuarioId, boolean enLinea, Instant ultimaConexion) {

    public EventoPresencia(UUID usuarioId, boolean enLinea, Instant ultimaConexion) {
        this("PRESENCIA", usuarioId, enLinea, ultimaConexion);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/PresenciaService.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ChatDirectoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Spec 7.5: la presencia la ven solo quienes tienen un chat directo con esa persona. */
@Service
public class PresenciaService {

    private final ChatDirectoRepository directos;
    private final EnviadorEventos enviador;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PresenciaService(ChatDirectoRepository directos, EnviadorEventos enviador, JdbcClient jdbc, Clock clock) {
        this.directos = directos;
        this.enviador = enviador;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Pasó de 0 a 1 sesiones. */
    public void conectado(UUID usuarioId) {
        Instant ultimaConexion = jdbc.sql("SELECT ultima_conexion FROM usuarios WHERE id = :id")
                .param("id", usuarioId)
                .query((fila, n) -> Tiempos.instante(fila, "ultima_conexion"))
                .optional().orElse(null);
        avisar(new EventoPresencia(usuarioId, true, ultimaConexion));
    }

    /** Pasó de 1 a 0 sesiones: se guarda cuándo fue la última vez. */
    public void desconectado(UUID usuarioId) {
        Instant ahora = Tiempos.ahora(clock);
        jdbc.sql("UPDATE usuarios SET ultima_conexion = :ahora WHERE id = :id")
                .param("ahora", Tiempos.sql(ahora)).param("id", usuarioId)
                .update();
        avisar(new EventoPresencia(usuarioId, false, ahora));
    }

    private void avisar(EventoPresencia evento) {
        directos.contactosDe(evento.usuarioId()).forEach(contacto -> enviador.enviar(contacto, evento));
    }
}
```

- [ ] **Step 6: Configuración STOMP y autorización de frames**

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/TiempoRealProperties.java`

```java
package com.hellocr.tiempoReal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.tiempo-real")
@Validated
public record TiempoRealProperties(@NotNull Duration latido) {
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/UsuarioStomp.java`

```java
package com.hellocr.tiempoReal;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/** Principal de una sesión STOMP. Su nombre es el id del usuario: así Spring resuelve /user/queue/eventos. */
public record UsuarioStomp(UUID id, Instant venceEn) implements Principal {

    @Override
    public String getName() {
        return id.toString();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/AutenticacionStomp.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.UsuarioAutenticado;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Autoriza cada frame que manda el cliente (spec 7.1). Un frame rechazado no sigue: la sesión recibe un frame
 * ERROR con el motivo y Spring cierra la conexión.
 */
@Component
public class AutenticacionStomp implements ChannelInterceptor {

    public static final String COLA_EVENTOS = "/user/queue/eventos";
    static final Set<String> DESTINOS_PERMITIDOS = Set.of("/app/mensajes.enviar", "/app/mensajes.entregados",
            "/app/mensajes.leidos", "/app/escribiendo", "/app/sesion.renovar");
    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtDecoder decoder;
    private final MessageChannel salida;

    /** Lazy: el canal de salida lo crea la misma configuración de mensajería que registra este interceptor. */
    public AutenticacionStomp(JwtDecoder decoder,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel salida) {
        this.decoder = decoder;
        this.salida = salida;
    }

    @Override
    public Message<?> preSend(Message<?> mensaje, MessageChannel canal) {
        StompHeaderAccessor acceso = MessageHeaderAccessor.getAccessor(mensaje, StompHeaderAccessor.class);
        if (acceso == null || acceso.getCommand() == null) {
            return mensaje;
        }
        String rechazo = switch (acceso.getCommand()) {
            case CONNECT, STOMP -> autenticar(acceso);
            case SUBSCRIBE -> COLA_EVENTOS.equals(acceso.getDestination()) ? null
                    : "Solo podés suscribirte a " + COLA_EVENTOS + ".";
            case SEND -> acceso.getDestination() != null && DESTINOS_PERMITIDOS.contains(acceso.getDestination())
                    ? null : "Ese destino no existe.";
            default -> null;
        };
        if (rechazo == null) {
            return mensaje;
        }
        rechazar(acceso.getSessionId(), rechazo);
        return null;
    }

    /** Valida "Bearer <jwt>" con el mismo JwtDecoder de la API (vigencia y emisor). */
    public Optional<Jwt> decodificar(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIJO_BEARER)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decoder.decode(authorization.substring(PREFIJO_BEARER.length())));
        } catch (JwtException invalido) {
            return Optional.empty();
        }
    }

    /** Deja el usuario en la sesión; si el token falta o no sirve, devuelve el motivo del rechazo. */
    private String autenticar(StompHeaderAccessor acceso) {
        Optional<Jwt> jwt = decodificar(acceso.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (jwt.isEmpty()) {
            return "Falta el token o no es válido.";
        }
        acceso.setUser(new UsuarioStomp(UsuarioAutenticado.id(jwt.get()), jwt.get().getExpiresAt()));
        return null;
    }

    /**
     * Manda el ERROR por el canal de salida: al entregarlo, Spring cierra la conexión. Lanzar una excepción no
     * sirve, porque con setPreserveReceiveOrder(true) Spring solo la anota en el log y la sesión sigue abierta.
     */
    private void rechazar(String sesionId, String motivo) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(motivo);
        error.setSessionId(sesionId);
        salida.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/SesionesWebSocket.java`

```java
package com.hellocr.tiempoReal;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Guarda las conexiones abiertas para poder cerrarlas desde el servidor. El id es el mismo de la sesión STOMP. */
@Component
public class SesionesWebSocket {

    private static final Logger log = LoggerFactory.getLogger(SesionesWebSocket.class);

    private final Map<String, WebSocketSession> abiertas = new ConcurrentHashMap<>();

    public WebSocketHandler decorar(WebSocketHandler manejador) {
        return new WebSocketHandlerDecorator(manejador) {
            @Override
            public void afterConnectionEstablished(WebSocketSession sesion) throws Exception {
                abiertas.put(sesion.getId(), sesion);
                super.afterConnectionEstablished(sesion);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) throws Exception {
                abiertas.remove(sesion.getId());
                super.afterConnectionClosed(sesion, estado);
            }
        };
    }

    public void cerrar(String sesionId) {
        WebSocketSession sesion = abiertas.get(sesionId);
        if (sesion == null || !sesion.isOpen()) {
            return;
        }
        try {
            sesion.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException error) {
            log.warn("No se pudo cerrar la sesión {}", sesionId, error);
        }
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/WebSocketConfig.java`

```java
package com.hellocr.tiempoReal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP sobre WebSocket nativo con el broker simple en memoria (spec 7.1). Los frames de cada sesión se procesan
 * y se publican en orden: dos mensajes enviados seguidos reciben secuencias en el orden en que se escribieron.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AutenticacionStomp autenticacion;
    private final SesionesWebSocket sesiones;
    private final TiempoRealProperties propiedades;
    private final String urlPublica;
    private TaskScheduler programadorLatidos;

    public WebSocketConfig(AutenticacionStomp autenticacion, SesionesWebSocket sesiones,
            TiempoRealProperties propiedades, @Value("${app.url-publica}") String urlPublica) {
        this.autenticacion = autenticacion;
        this.sesiones = sesiones;
        this.propiedades = propiedades;
        this.urlPublica = urlPublica;
    }

    /** Lazy: el programador lo crea la misma configuración de mensajería que usa esta clase. */
    @Autowired
    void usarProgramador(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler programador) {
        this.programadorLatidos = programador;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registro) {
        registro.addEndpoint("/ws").setAllowedOrigins(urlPublica);
        registro.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registro) {
        long latido = propiedades.latido().toMillis();
        registro.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {latido, latido})
                .setTaskScheduler(programadorLatidos);
        registro.setApplicationDestinationPrefixes("/app");
        registro.setUserDestinationPrefix("/user");
        registro.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registro) {
        registro.interceptors(autenticacion);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registro) {
        registro.setMessageSizeLimit(64 * 1024);
        registro.addDecoratorFactory(sesiones::decorar);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventosDeSesion.java`

```java
package com.hellocr.tiempoReal;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Lleva el registro de sesiones y avisa la presencia al conectarse y al desconectarse. */
@Component
public class EventosDeSesion {

    private final RegistroSesiones registro;
    private final PresenciaService presencia;

    public EventosDeSesion(RegistroSesiones registro, PresenciaService presencia) {
        this.registro = registro;
        this.presencia = presencia;
    }

    @EventListener
    public void alConectar(SessionConnectedEvent evento) {
        if (evento.getUser() instanceof UsuarioStomp usuario) {
            String sesionId = SimpMessageHeaderAccessor.getSessionId(evento.getMessage().getHeaders());
            if (registro.abrir(sesionId, usuario.id(), usuario.venceEn())) {
                presencia.conectado(usuario.id());
            }
        }
    }

    @EventListener
    public void alDesconectar(SessionDisconnectEvent evento) {
        registro.cerrar(evento.getSessionId()).ifPresent(presencia::desconectado);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/CierreSesiones.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.auth.SesionesRevocadas;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Cierra las conexiones con el token vencido y las de quien restableció la contraseña (spec 7.1 y 5.2). */
@Component
public class CierreSesiones {

    private final RegistroSesiones registro;
    private final SesionesWebSocket sesiones;
    private final Clock clock;

    public CierreSesiones(RegistroSesiones registro, SesionesWebSocket sesiones, Clock clock) {
        this.registro = registro;
        this.sesiones = sesiones;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void cerrarVencidas() {
        registro.vencidas(clock.instant()).forEach(sesiones::cerrar);
    }

    /** Después del commit: si la revocación se deshace, las conexiones siguen abiertas. */
    @TransactionalEventListener(fallbackExecution = true)
    public void alRevocar(SesionesRevocadas evento) {
        registro.sesionesDe(evento.usuarioId()).forEach(sesiones::cerrar);
    }
}
```

- [ ] **Step 7: El handshake de /ws no lleva token HTTP**

Archivo: `backend/src/main/java/com/hellocr/config/SecurityConfig.java`

```java
package com.hellocr.config;

import com.hellocr.auth.JwtService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /** Registro, login, refresh y demás: públicos, e ignoran un Bearer (quizá vencido) que el cliente mande igual. */
    static final String PREFIJO_AUTH = "/api/auth/";

    @Bean
    public SecurityFilterChain cadenaSeguridad(HttpSecurity http, JwtDecoder decoder,
            SeguridadErrorHandler respuestas) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rutas -> rutas
                        .requestMatchers(HttpMethod.POST, PREFIJO_AUTH + "**").permitAll()
                        // El navegador no puede mandar headers en el handshake: el token viaja en el CONNECT de STOMP.
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(recursos -> recursos
                        .bearerTokenResolver(resolvedorBearer())
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas))
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(respuestas)
                        .accessDeniedHandler(respuestas));
        return http.build();
    }

    @Bean
    public SecretKey claveJwt(JwtProperties propiedades) {
        return new SecretKeySpec(propiedades.secreto().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey clave) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(clave));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey clave, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(clave).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator vigencia = new JwtTimestampValidator(Duration.ZERO);
        vigencia.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                vigencia, new JwtIssuerValidator(JwtService.EMISOR)));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static BearerTokenResolver resolvedorBearer() {
        DefaultBearerTokenResolver porDefecto = new DefaultBearerTokenResolver();
        return solicitud -> solicitud.getRequestURI().startsWith(PREFIJO_AUTH)
                ? null
                : porDefecto.resolve(solicitud);
    }
}
```

- [ ] **Step 8: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(backend): conexión STOMP autenticada con JWT, registro de sesiones y presencia"
```

---

### Task 8: Mensajes en tiempo real

**Files:**
- Create en `backend/src/main/java/com/hellocr/tiempoReal/`: `EventoMensajeNuevo.java`, `EventoEstado.java`, `EventoEscribiendo.java`, `EventoError.java`, `EntregaTiempoReal.java`, `EscribiendoService.java`, `RenovacionSesion.java`, `ErrorDeEnvio.java`, `SolicitudMarca.java`, `SolicitudEscribiendo.java`, `MensajesStompController.java`
- Test: `backend/src/test/java/com/hellocr/tiempoReal/MensajesTiempoRealTest.java`

**Interfaces:**
- Consumes: `EnvioMensajesService`, `MensajeEnviado`, `MensajeDto`, `SolicitudEnvio` (Task 4); `MarcasService`, `EstadoActualizado` (Task 5); `ConsultaMembresia`, `ErroresConversacion` (Task 2); `AutenticacionStomp.decodificar`, `RegistroSesiones`, `EnviadorEventos`, `EnviadorEventosStomp.DESTINO`, `CierreSesiones` (Task 7); `JwtService.emitir` (plan 1, en los tests).
- Produces (STOMP): `/app/mensajes.enviar` `{idCliente, conversacionId, texto}`, `/app/mensajes.entregados` y `/app/mensajes.leidos` `{conversacionId, hastaSecuencia}`, `/app/escribiendo` `{conversacionId}`, `/app/sesion.renovar` (token en el header `Authorization`). Eventos `MENSAJE_NUEVO` `{mensaje}`, `ESTADO_ACTUALIZADO` `{conversacionId, usuarioId, ultimaEntregada, ultimaLeida}`, `ESCRIBIENDO` `{conversacionId, usuarioId}` y `ERROR` `{idCliente, codigo, detalle}` (solo a la sesión que envió el frame).
- Produces: `EntregaTiempoReal` escucha `MensajeEnviado` (a quienes pueden ver la secuencia) y `EstadoActualizado` (a los miembros activos) después del commit; la Task 9 le agrega `ConversacionActualizada`. `record EventoMensajeNuevo(String tipo, MensajeDto mensaje)` con el constructor `EventoMensajeNuevo(MensajeDto)`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/tiempoReal/MensajesTiempoRealTest.java`

```java
package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.auth.JwtService;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.usuarios.UsuarioRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class MensajesTiempoRealTest extends PruebaTiempoReal {

    private static final Duration UN_RATO = Duration.ofMillis(500);

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private CierreSesiones cierre;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UsuarioRepository usuarios;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private UUID chat;

    @BeforeEach
    void chatEntreAnaYLuis() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        chat = chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId())).detalle().id();
    }

    @Test
    void anaEnviaYLeLlegaALuisYATodosLosDispositivosDeAna() throws Exception {
        ClienteStomp celularDeAna = conectar(ana);
        ClienteStomp pcDeAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        UUID idCliente = UUID.randomUUID();

        celularDeAna.enviar("/app/mensajes.enviar", envio(idCliente, "¡Hola, Luis!"));

        JsonNode recibido = deLuis.esperar("MENSAJE_NUEVO");
        assertThat(recibido.at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(recibido.at("/mensaje/texto").asString()).isEqualTo("¡Hola, Luis!");
        assertThat(recibido.at("/mensaje/tipo").asString()).isEqualTo("TEXTO");
        assertThat(recibido.at("/mensaje/remitenteId").asString()).isEqualTo(ana.usuarioId());
        assertThat(recibido.at("/mensaje/idCliente").asString()).isEqualTo(idCliente.toString());
        assertThat(recibido.at("/mensaje/conversacionId").asString()).isEqualTo(chat.toString());
        assertThat(celularDeAna.esperar("MENSAJE_NUEVO").at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(pcDeAna.esperar("MENSAJE_NUEVO").at("/mensaje/secuencia").asLong()).isEqualTo(1);
    }

    @Test
    void reenviarElMismoIdClienteSoloLeLlegaDeNuevoAlRemitente() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        UUID idCliente = UUID.randomUUID();
        deAna.enviar("/app/mensajes.enviar", envio(idCliente, "original"));
        deAna.esperar("MENSAJE_NUEVO");
        deLuis.esperar("MENSAJE_NUEVO");

        deAna.enviar("/app/mensajes.enviar", envio(idCliente, "reintento"));

        JsonNode repetido = deAna.esperar("MENSAJE_NUEVO");
        assertThat(repetido.at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(repetido.at("/mensaje/texto").asString()).isEqualTo("original");
        deLuis.sinEventos("MENSAJE_NUEVO", UN_RATO);
    }

    @Test
    void unErrorDeEnvioLlegaSoloALaSesionQueEnvioConSuIdCliente() throws Exception {
        ClienteStomp celularDeAna = conectar(ana);
        ClienteStomp pcDeAna = conectar(ana);
        UUID idCliente = UUID.randomUUID();

        celularDeAna.enviar("/app/mensajes.enviar", envio(idCliente, "   "));

        JsonNode error = celularDeAna.esperar("ERROR");
        assertThat(error.path("idCliente").asString()).isEqualTo(idCliente.toString());
        assertThat(error.path("codigo").asString()).isEqualTo("VALIDACION");
        assertThat(error.path("detalle").asString()).isEqualTo("El mensaje está vacío.");
        pcDeAna.sinEventos("ERROR", UN_RATO);
    }

    @Test
    void quienNoEsMiembroRecibeUnError() throws Exception {
        ClienteStomp deSofia = conectar(sesionDe("sofia"));

        deSofia.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), "hola"));

        assertThat(deSofia.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_ES_MIEMBRO");
    }

    @Test
    void unCuerpoQueNoEsJsonEsUnErrorDeValidacion() throws Exception {
        ClienteStomp deAna = conectar(ana);

        deAna.enviarBytes("/app/mensajes.enviar", "esto no es json".getBytes(StandardCharsets.UTF_8));

        JsonNode error = deAna.esperar("ERROR");
        assertThat(error.path("codigo").asString()).isEqualTo("VALIDACION");
        assertThat(error.path("idCliente").isNull()).isTrue();
    }

    @Test
    void losMensajesEnviadosSeguidosConservanSuOrden() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        for (int i = 1; i <= 10; i++) {
            deAna.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), String.valueOf(i)));
        }

        for (int i = 1; i <= 10; i++) {
            JsonNode recibido = deLuis.esperar("MENSAJE_NUEVO");
            assertThat(recibido.at("/mensaje/texto").asString()).isEqualTo(String.valueOf(i));
            assertThat(recibido.at("/mensaje/secuencia").asLong()).isEqualTo(i);
        }
    }

    @Test
    void entregadosYLeidosAvisanALosMiembrosYNuncaBajan() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        deAna.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), "hola"));
        deLuis.esperar("MENSAJE_NUEVO");

        deLuis.enviar("/app/mensajes.entregados", Map.of("conversacionId", chat, "hastaSecuencia", 1));

        JsonNode entregado = deAna.esperar("ESTADO_ACTUALIZADO");
        assertThat(entregado.path("conversacionId").asString()).isEqualTo(chat.toString());
        assertThat(entregado.path("usuarioId").asString()).isEqualTo(luis.usuarioId());
        assertThat(entregado.path("ultimaEntregada").asLong()).isEqualTo(1);
        assertThat(entregado.path("ultimaLeida").asLong()).isZero();
        assertThat(deLuis.esperar("ESTADO_ACTUALIZADO").path("ultimaEntregada").asLong()).isEqualTo(1);

        deLuis.enviar("/app/mensajes.leidos", Map.of("conversacionId", chat, "hastaSecuencia", 1));
        assertThat(deAna.esperar("ESTADO_ACTUALIZADO").path("ultimaLeida").asLong()).isEqualTo(1);

        deLuis.enviar("/app/mensajes.leidos", Map.of("conversacionId", chat, "hastaSecuencia", 0));
        deAna.sinEventos("ESTADO_ACTUALIZADO", UN_RATO);
    }

    @Test
    void escribiendoLlegaAlOtroMiembroYRespetaLosDosSegundos() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));

        JsonNode escribiendo = deLuis.esperar("ESCRIBIENDO");
        assertThat(escribiendo.path("usuarioId").asString()).isEqualTo(ana.usuarioId());
        assertThat(escribiendo.path("conversacionId").asString()).isEqualTo(chat.toString());
        deLuis.sinEventos("ESCRIBIENDO", UN_RATO);
        deAna.sinEventos("ESCRIBIENDO", Duration.ZERO);

        reloj.avanzar(Duration.ofSeconds(2));
        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deLuis.esperar("ESCRIBIENDO");
    }

    @Test
    void renovarLaSesionEvitaQueSeCierreAlVencerElToken() throws Exception {
        ClienteStomp renovado = conectar(ana);
        ClienteStomp sinRenovar = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        reloj.avanzar(Duration.ofMinutes(10));
        String tokenNuevo = jwtService.emitir(usuarios.findByNombreUsuario("ana").orElseThrow());

        renovado.renovar(tokenNuevo);
        // Los frames de una sesión se procesan en orden: cuando Luis ve el aviso, la renovación ya se aplicó.
        renovado.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deLuis.esperar("ESCRIBIENDO");
        reloj.avanzar(Duration.ofMinutes(10));
        cierre.cerrarVencidas();

        assertThat(sinRenovar.esperarCierre()).isTrue();
        assertThat(renovado.estaConectado()).isTrue();
    }

    @Test
    void renovarConUnTokenAjenoOInvalidoDaError() throws Exception {
        ClienteStomp deAna = conectar(ana);

        deAna.renovar(luis.accessToken());
        assertThat(deAna.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_AUTENTICADO");

        deAna.renovar("no-es-un-token");
        assertThat(deAna.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_AUTENTICADO");
    }

    private Map<String, Object> envio(UUID idCliente, String texto) {
        return Map.of("idCliente", idCliente, "conversacionId", chat, "texto", texto);
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=MensajesTiempoRealTest`
Expected: FAIL — el test compila (solo usa clases de la Task 7), pero ningún destino `/app/…` tiene quién lo atienda: `No llegó ningún evento MENSAJE_NUEVO` (y los demás eventos).

- [ ] **Step 3: Eventos y entrega después del commit**

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoMensajeNuevo.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.mensajes.MensajeDto;

public record EventoMensajeNuevo(String tipo, MensajeDto mensaje) {

    public EventoMensajeNuevo(MensajeDto mensaje) {
        this("MENSAJE_NUEVO", mensaje);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoEstado.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.mensajes.EstadoActualizado;
import java.util.UUID;

public record EventoEstado(String tipo, UUID conversacionId, UUID usuarioId, long ultimaEntregada, long ultimaLeida) {

    public EventoEstado(EstadoActualizado estado) {
        this("ESTADO_ACTUALIZADO", estado.conversacionId(), estado.usuarioId(), estado.ultimaEntregada(),
                estado.ultimaLeida());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoEscribiendo.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;

public record EventoEscribiendo(String tipo, UUID conversacionId, UUID usuarioId) {

    public EventoEscribiendo(UUID conversacionId, UUID usuarioId) {
        this("ESCRIBIENDO", conversacionId, usuarioId);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoError.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;

/** idCliente viene solo en los errores de /app/mensajes.enviar: así el celular sabe qué mensaje marcar con ❗. */
public record EventoError(String tipo, UUID idCliente, String codigo, String detalle) {

    public static EventoError de(UUID idCliente, ErrorNegocio error) {
        return new EventoError("ERROR", idCliente, error.codigo().name(), error.getMessage());
    }

    public static EventoError de(CodigoError codigo, String detalle) {
        return new EventoError("ERROR", null, codigo.name(), detalle);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EntregaTiempoReal.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.mensajes.EstadoActualizado;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeEnviado;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reparte los eventos de dominio por /user/queue/eventos recién después del commit (spec 7.3). */
@Component
public class EntregaTiempoReal {

    private final ConsultaMembresia membresia;
    private final EnviadorEventos enviador;

    public EntregaTiempoReal(ConsultaMembresia membresia, EnviadorEventos enviador) {
        this.membresia = membresia;
        this.enviador = enviador;
    }

    /** A quienes pueden ver esa secuencia, incluido el remitente en todos sus dispositivos. */
    @TransactionalEventListener
    public void alEnviarMensaje(MensajeEnviado evento) {
        MensajeDto mensaje = evento.mensaje();
        EventoMensajeNuevo nuevo = new EventoMensajeNuevo(mensaje);
        membresia.quienesPuedenVer(mensaje.conversacionId(), mensaje.secuencia())
                .forEach(usuario -> enviador.enviar(usuario, nuevo));
    }

    /** A los miembros activos, incluidos los otros dispositivos de quien marcó. */
    @TransactionalEventListener
    public void alActualizarEstado(EstadoActualizado evento) {
        EventoEstado estado = new EventoEstado(evento);
        membresia.miembrosActivos(evento.conversacionId()).forEach(usuario -> enviador.enviar(usuario, estado));
    }
}
```

- [ ] **Step 4: "Escribiendo…", renovación y controlador STOMP**

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EscribiendoService.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Spec 7.5: no se guarda nada; el servidor solo filtra avisos demasiado seguidos. */
@Service
public class EscribiendoService {

    static final Duration INTERVALO_MINIMO = Duration.ofSeconds(2);

    private final Map<String, Instant> ultimosAvisos = new ConcurrentHashMap<>();
    private final ConsultaMembresia membresia;
    private final EnviadorEventos enviador;
    private final Clock clock;

    public EscribiendoService(ConsultaMembresia membresia, EnviadorEventos enviador, Clock clock) {
        this.membresia = membresia;
        this.enviador = enviador;
        this.clock = clock;
    }

    /** Avisa a los otros miembros activos; si el mismo usuario avisó hace menos de 2 s, no hace nada. */
    public void avisar(UUID usuarioId, UUID conversacionId) {
        if (conversacionId == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Falta la conversación.");
        }
        if (!membresia.esMiembroActivo(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        if (!pasaElFiltro(usuarioId + "|" + conversacionId)) {
            return;
        }
        EventoEscribiendo evento = new EventoEscribiendo(conversacionId, usuarioId);
        membresia.miembrosActivos(conversacionId).stream()
                .filter(otro -> !otro.equals(usuarioId))
                .forEach(otro -> enviador.enviar(otro, evento));
    }

    private boolean pasaElFiltro(String clave) {
        Instant ahora = clock.instant();
        boolean[] pasa = {false};
        ultimosAvisos.compute(clave, (k, anterior) -> {
            if (anterior != null && ahora.isBefore(anterior.plus(INTERVALO_MINIMO))) {
                return anterior;
            }
            pasa[0] = true;
            return ahora;
        });
        return pasa[0];
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void olvidarViejos() {
        Instant limite = clock.instant().minus(Duration.ofMinutes(1));
        ultimosAvisos.values().removeIf(aviso -> aviso.isBefore(limite));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/RenovacionSesion.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Spec 7.1: el cliente manda un token nuevo antes de que venza el del CONNECT y la sesión sigue abierta. */
@Service
public class RenovacionSesion {

    private final AutenticacionStomp autenticacion;
    private final RegistroSesiones registro;

    public RenovacionSesion(AutenticacionStomp autenticacion, RegistroSesiones registro) {
        this.autenticacion = autenticacion;
        this.registro = registro;
    }

    public void renovar(String sesionId, UUID usuarioId, String authorization) {
        Jwt jwt = autenticacion.decodificar(authorization)
                .filter(token -> UsuarioAutenticado.id(token).equals(usuarioId))
                .orElseThrow(() -> new ErrorNegocio(CodigoError.NO_AUTENTICADO,
                        "El token no es válido o no es de esta sesión."));
        registro.renovar(sesionId, usuarioId, jwt.getExpiresAt());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/ErrorDeEnvio.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;

/** Un ErrorNegocio al enviar un mensaje, junto con el idCliente de ese mensaje. */
public class ErrorDeEnvio extends RuntimeException {

    private final UUID idCliente;
    private final ErrorNegocio causa;

    public ErrorDeEnvio(UUID idCliente, ErrorNegocio causa) {
        super(causa.getMessage(), causa);
        this.idCliente = idCliente;
        this.causa = causa;
    }

    public UUID idCliente() {
        return idCliente;
    }

    public ErrorNegocio causa() {
        return causa;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/SolicitudMarca.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;

/** Cuerpo de /app/mensajes.entregados y /app/mensajes.leidos. */
public record SolicitudMarca(UUID conversacionId, Long hastaSecuencia) {
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/SolicitudEscribiendo.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;

public record SolicitudEscribiendo(UUID conversacionId) {
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/MensajesStompController.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.MarcasService;
import com.hellocr.mensajes.SolicitudEnvio;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/** Los destinos /app/… de spec 7.2. Solo traduce frames a llamadas de servicio y errores a eventos ERROR. */
@Controller
public class MensajesStompController {

    private static final Logger log = LoggerFactory.getLogger(MensajesStompController.class);

    private final EnvioMensajesService envio;
    private final MarcasService marcas;
    private final EscribiendoService escribiendo;
    private final RenovacionSesion renovacion;
    private final EnviadorEventos enviador;

    public MensajesStompController(EnvioMensajesService envio, MarcasService marcas, EscribiendoService escribiendo,
            RenovacionSesion renovacion, EnviadorEventos enviador) {
        this.envio = envio;
        this.marcas = marcas;
        this.escribiendo = escribiendo;
        this.renovacion = renovacion;
        this.enviador = enviador;
    }

    /** Un mensaje nuevo lo reparte EntregaTiempoReal; un reintento solo se le reenvía al remitente. */
    @MessageMapping("mensajes.enviar")
    public void enviar(@Payload SolicitudEnvio solicitud, Principal usuario) {
        UUID remitente = id(usuario);
        EnvioMensajesService.Resultado resultado;
        try {
            resultado = envio.enviar(remitente, solicitud);
        } catch (ErrorNegocio error) {
            throw new ErrorDeEnvio(solicitud.idCliente(), error);
        }
        if (!resultado.nuevo()) {
            enviador.enviar(remitente, new EventoMensajeNuevo(resultado.mensaje()));
        }
    }

    @MessageMapping("mensajes.entregados")
    public void entregados(@Payload SolicitudMarca solicitud, Principal usuario) {
        marcas.entregados(id(usuario), solicitud.conversacionId(), solicitud.hastaSecuencia());
    }

    @MessageMapping("mensajes.leidos")
    public void leidos(@Payload SolicitudMarca solicitud, Principal usuario) {
        marcas.leidos(id(usuario), solicitud.conversacionId(), solicitud.hastaSecuencia());
    }

    @MessageMapping("escribiendo")
    public void escribiendo(@Payload SolicitudEscribiendo solicitud, Principal usuario) {
        escribiendo.avisar(id(usuario), solicitud.conversacionId());
    }

    @MessageMapping("sesion.renovar")
    public void renovar(Message<?> mensaje, Principal usuario) {
        StompHeaderAccessor acceso = StompHeaderAccessor.wrap(mensaje);
        renovacion.renovar(acceso.getSessionId(), id(usuario), acceso.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallarUnEnvio(ErrorDeEnvio error) {
        return EventoError.de(error.idCliente(), error.causa());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallar(ErrorNegocio error) {
        return EventoError.de(null, error);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallarSinEsperarlo(Exception error) {
        if (error instanceof MessageConversionException) {
            return EventoError.de(CodigoError.VALIDACION, "El mensaje no tiene un formato válido.");
        }
        log.error("Error inesperado en un frame STOMP", error);
        return EventoError.de(CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado. Intentá de nuevo.");
    }

    private static UUID id(Principal usuario) {
        return UUID.fromString(usuario.getName());
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): mensajes, acuses, escribiendo y renovación de sesión por STOMP"
```

---
### Task 9: Grupos — crear y editar

**Files:**
- Create en `backend/src/main/java/com/hellocr/grupos/`: `TipoEvento.java`, `GruposProperties.java`, `GrupoRepository.java`, `EventosGrupoRepository.java`, `OperacionesGrupo.java`, `SolicitudGrupo.java`, `SolicitudEdicionGrupo.java`, `GrupoService.java`, `GrupoController.java`
- Create: `backend/src/main/java/com/hellocr/conversaciones/ConversacionActualizada.java`, `backend/src/main/java/com/hellocr/tiempoReal/EventoConversacion.java`
- Modify: `backend/src/main/java/com/hellocr/tiempoReal/EntregaTiempoReal.java` (reparte `ConversacionActualizada`)
- Test: `backend/src/test/java/com/hellocr/grupos/GrupoControllerTest.java`

**Interfaces:**
- Consumes: `ConversacionRepository`, `ConsultaMembresia`, `GestionMiembros`, `Rol`, `TipoConversacion`, `ErroresConversacion`, `Tiempos` (Task 2); `LecturaConversacionesService.detalle`, `ConversacionDetalle`, `ChatDirectoService` (Task 3); `MensajeRepository.siguienteSecuencia`, `MensajeDto`, `EventoDto`, `TipoMensaje`, `MensajeEnviado` (Task 4); `EnviadorEventos` (Task 7); `EntregaTiempoReal` (Task 8); `UsuarioRepository.verificadoPorId`, `ErroresUsuario.noEncontrado` (plan 1).
- Produces (API): `POST /api/grupos` `{nombre, descripcion?, miembrosIds}` → 201 `ConversacionDetalle`; `PATCH /api/grupos/{id}` `{nombre?, descripcion?}` → 200 `ConversacionDetalle` (solo admins).
- Produces: `OperacionesGrupo` (la reutiliza la Task 10): `bloquearComoMiembro(grupoId, actor)` (404 si no es un grupo o el actor nunca fue miembro, 403 `NO_ES_MIEMBRO` si ya salió), `bloquearComoAdmin(grupoId, actor)` (además 403 `SIN_PERMISO`), `registrarEvento(grupoId, actor, TipoEvento, UUID afectado, String valor): long` (secuencia; publica `MensajeEnviado`) y `avisarCambio(grupoId, UUID... ademas)` (publica `ConversacionActualizada` para los activos más esos).
- Produces: `GrupoService.crear(UUID creador, SolicitudGrupo)` y `editar(UUID actor, UUID grupoId, SolicitudEdicionGrupo)`; `record SolicitudGrupo(String nombre, String descripcion, List<UUID> miembrosIds)`; `record ConversacionActualizada(UUID conversacionId, Set<UUID> afectados)`; evento STOMP `CONVERSACION_ACTUALIZADA` `{conversacionId}`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/grupos/GrupoControllerTest.java`

```java
package com.hellocr.grupos;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class GrupoControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
    }

    @Test
    void crearUnGrupoDevuelveElDetalleYRegistraElEventoInicial() throws Exception {
        String id = idDe(crear(ana, "Familia Rojas", "La familia", luis.usuarioId(), sofia.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("GRUPO"))
                .andExpect(jsonPath("$.titulo").value("Familia Rojas"))
                .andExpect(jsonPath("$.descripcion").value("La familia"))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miRol").value("ADMIN"))
                .andExpect(jsonPath("$.miembros.length()").value(3)));

        mvc.perform(get("/api/conversaciones/" + id + "/mensajes").with(con(luis)))
                .andExpect(jsonPath("$.mensajes.length()").value(1))
                .andExpect(jsonPath("$.mensajes[0].secuencia").value(1))
                .andExpect(jsonPath("$.mensajes[0].tipo").value("EVENTO"))
                .andExpect(jsonPath("$.mensajes[0].texto").value(nullValue()))
                .andExpect(jsonPath("$.mensajes[0].evento.evento").value("GRUPO_CREADO"))
                .andExpect(jsonPath("$.mensajes[0].remitenteId").value(ana.usuarioId()));
        mvc.perform(get("/api/conversaciones/" + id).with(con(luis)))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"));
    }

    @Test
    void losDatosInvalidosSon400() throws Exception {
        crear(ana, "  ", null, luis.usuarioId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombre").value("El grupo necesita un nombre."));
        crear(ana, "Familia", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("Elegí al menos una persona."));
        crear(ana, "Familia", null, ana.usuarioId(), luis.usuarioId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("No te incluyas en la lista: ya sos parte del grupo."));

        String[] cinco = new String[5];
        for (int i = 0; i < 5; i++) {
            cinco[i] = crearUsuario("invitado" + i).getId().toString();
        }
        crear(ana, "Familia", null, cinco)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("Un grupo puede tener hasta 5 personas, contándote a vos."));
    }

    @Test
    void unInvitadoInexistenteEs404YLosRepetidosCuentanUnaVez() throws Exception {
        crear(ana, "Familia", null, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        crear(ana, "Familia", null, luis.usuarioId(), luis.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.miembros.length()").value(2));
    }

    @Test
    void cambiarElNombreRegistraUnEventoYLaDescripcionNo() throws Exception {
        String id = idDe(crear(ana, "Familia", null, luis.usuarioId()));

        editar(ana, id, "{\"nombre\": \"  Familia Rojas \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Familia Rojas"));
        editar(ana, id, "{\"descripcion\": \"Solo cosas lindas\"}")
                .andExpect(jsonPath("$.descripcion").value("Solo cosas lindas"));
        editar(ana, id, "{\"nombre\": \"Familia Rojas\"}").andExpect(status().isOk());
        editar(ana, id, "{\"descripcion\": \"\"}").andExpect(jsonPath("$.descripcion").value(nullValue()));

        mvc.perform(get("/api/conversaciones/" + id + "/mensajes").with(con(luis)))
                .andExpect(jsonPath("$.mensajes[*].evento.evento").value(contains("GRUPO_CREADO", "NOMBRE_CAMBIADO")))
                .andExpect(jsonPath("$.mensajes[1].evento.valor").value("Familia Rojas"));
    }

    @Test
    void soloLosAdminsEditanYQuienNoEsDelGrupoNiLoVe() throws Exception {
        String id = idDe(crear(ana, "Familia", null, luis.usuarioId()));
        UUID chat = chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId())).detalle().id();

        editar(luis, id, "{\"nombre\": \"Otra\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"))
                .andExpect(jsonPath("$.detail").value("Solo los administradores del grupo pueden hacer esto."));
        editar(sofia, id, "{\"nombre\": \"Otra\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El grupo no existe."));
        editar(ana, chat.toString(), "{\"nombre\": \"Otra\"}").andExpect(status().isNotFound());
        editar(ana, id, "{\"nombre\": \"\"}").andExpect(status().isBadRequest());
    }

    private ResultActions crear(SesionPrueba sesion, String nombre, String descripcion, String... miembros)
            throws Exception {
        String ids = Arrays.stream(miembros).map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        return mvc.perform(post("/api/grupos").with(con(sesion)).contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre": "%s", "descripcion": %s, "miembrosIds": [%s]}
                """.formatted(nombre, descripcion == null ? "null" : "\"" + descripcion + "\"", ids)));
    }

    private ResultActions editar(SesionPrueba sesion, String grupo, String json) throws Exception {
        return mvc.perform(patch("/api/grupos/" + grupo).with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=GrupoControllerTest`
Expected: FAIL — el endpoint todavía no existe (por ejemplo `Status expected:<201> but was:<404>`).

- [ ] **Step 3: Eventos de grupo y avisos**

Archivo: `backend/src/main/java/com/hellocr/grupos/TipoEvento.java`

```java
package com.hellocr.grupos;

/** Deben coincidir con el CHECK de eventos_grupo.evento (V3). FOTO_CAMBIADA la usa el plan 3. */
public enum TipoEvento {
    GRUPO_CREADO, MIEMBRO_AGREGADO, MIEMBRO_QUITADO, MIEMBRO_SALIO, ADMIN_ASIGNADO, ADMIN_QUITADO, NOMBRE_CAMBIADO,
    FOTO_CAMBIADA
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/GruposProperties.java`

```java
package com.hellocr.grupos;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** maxMiembros cuenta a los miembros activos, incluido quien creó el grupo. */
@ConfigurationProperties("app.grupos")
@Validated
public record GruposProperties(@Min(2) int maxMiembros) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionActualizada.java`

```java
package com.hellocr.conversaciones;

import java.util.Set;
import java.util.UUID;

/** Cambió un grupo (miembros, roles, nombre). Los afectados vuelven a pedir la lista y el detalle. */
public record ConversacionActualizada(UUID conversacionId, Set<UUID> afectados) {
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EventoConversacion.java`

```java
package com.hellocr.tiempoReal;

import java.util.UUID;

public record EventoConversacion(String tipo, UUID conversacionId) {

    public EventoConversacion(UUID conversacionId) {
        this("CONVERSACION_ACTUALIZADA", conversacionId);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/tiempoReal/EntregaTiempoReal.java`

```java
package com.hellocr.tiempoReal;

import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ConversacionActualizada;
import com.hellocr.mensajes.EstadoActualizado;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeEnviado;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reparte los eventos de dominio por /user/queue/eventos recién después del commit (spec 7.3). */
@Component
public class EntregaTiempoReal {

    private final ConsultaMembresia membresia;
    private final EnviadorEventos enviador;

    public EntregaTiempoReal(ConsultaMembresia membresia, EnviadorEventos enviador) {
        this.membresia = membresia;
        this.enviador = enviador;
    }

    /** A quienes pueden ver esa secuencia, incluido el remitente en todos sus dispositivos. */
    @TransactionalEventListener
    public void alEnviarMensaje(MensajeEnviado evento) {
        MensajeDto mensaje = evento.mensaje();
        EventoMensajeNuevo nuevo = new EventoMensajeNuevo(mensaje);
        membresia.quienesPuedenVer(mensaje.conversacionId(), mensaje.secuencia())
                .forEach(usuario -> enviador.enviar(usuario, nuevo));
    }

    /** A los miembros activos, incluidos los otros dispositivos de quien marcó. */
    @TransactionalEventListener
    public void alActualizarEstado(EstadoActualizado evento) {
        EventoEstado estado = new EventoEstado(evento);
        membresia.miembrosActivos(evento.conversacionId()).forEach(usuario -> enviador.enviar(usuario, estado));
    }

    /** A los afectados por el cambio, incluidos quien entró y quien salió. */
    @TransactionalEventListener
    public void alActualizarConversacion(ConversacionActualizada evento) {
        EventoConversacion aviso = new EventoConversacion(evento.conversacionId());
        evento.afectados().forEach(usuario -> enviador.enviar(usuario, aviso));
    }
}
```

- [ ] **Step 4: Repositorios y operaciones comunes de los grupos**

Archivo: `backend/src/main/java/com/hellocr/grupos/GrupoRepository.java`

```java
package com.hellocr.grupos;

import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GrupoRepository {

    private final JdbcClient jdbc;

    public GrupoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void crear(UUID conversacionId, String nombre, String descripcion, UUID creadoPor) {
        jdbc.sql("""
                        INSERT INTO grupos (conversacion_id, nombre, descripcion, creado_por)
                        VALUES (:c, :nombre, :descripcion, :creadoPor)
                        """)
                .param("c", conversacionId).param("nombre", nombre)
                .param("descripcion", descripcion, Types.VARCHAR).param("creadoPor", creadoPor)
                .update();
    }

    public String nombre(UUID conversacionId) {
        return jdbc.sql("SELECT nombre FROM grupos WHERE conversacion_id = :c")
                .param("c", conversacionId)
                .query(String.class).single();
    }

    public void cambiarNombre(UUID conversacionId, String nombre) {
        jdbc.sql("UPDATE grupos SET nombre = :nombre WHERE conversacion_id = :c")
                .param("c", conversacionId).param("nombre", nombre)
                .update();
    }

    /** null borra la descripción. */
    public void cambiarDescripcion(UUID conversacionId, String descripcion) {
        jdbc.sql("UPDATE grupos SET descripcion = :descripcion WHERE conversacion_id = :c")
                .param("c", conversacionId).param("descripcion", descripcion, Types.VARCHAR)
                .update();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/EventosGrupoRepository.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.Tiempos;
import com.hellocr.mensajes.EventoDto;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.TipoMensaje;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Un evento de grupo es un mensaje EVENTO más su fila en eventos_grupo, en la misma transacción. */
@Repository
public class EventosGrupoRepository {

    private final JdbcClient jdbc;

    public EventosGrupoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MensajeDto insertar(UUID conversacionId, long secuencia, UUID actorId, TipoEvento evento, UUID afectadoId,
            String valor, Instant ahora) {
        UUID idCliente = UUID.randomUUID();
        long mensajeId = jdbc.sql("""
                        INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, creado_en)
                        VALUES (:c, :s, :r, :ic, 'EVENTO', :ahora) RETURNING id
                        """)
                .param("c", conversacionId).param("s", secuencia).param("r", actorId).param("ic", idCliente)
                .param("ahora", Tiempos.sql(ahora))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO eventos_grupo (mensaje_id, evento, afectado_id, valor) VALUES (:m, :evento, :afectado, :valor)")
                .param("m", mensajeId).param("evento", evento.name())
                .param("afectado", afectadoId, Types.OTHER).param("valor", valor, Types.VARCHAR)
                .update();
        return new MensajeDto(conversacionId, secuencia, idCliente, actorId, TipoMensaje.EVENTO, null,
                new EventoDto(evento.name(), afectadoId, valor), ahora);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/OperacionesGrupo.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ConversacionActualizada;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.ErroresConversacion;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeEnviado;
import com.hellocr.mensajes.MensajeRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Lo que comparten todas las operaciones de un grupo: el bloqueo, los permisos, los eventos y los avisos. */
@Component
public class OperacionesGrupo {

    private final ConversacionRepository conversaciones;
    private final ConsultaMembresia membresia;
    private final GestionMiembros gestion;
    private final MensajeRepository mensajes;
    private final EventosGrupoRepository eventosGrupo;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public OperacionesGrupo(ConversacionRepository conversaciones, ConsultaMembresia membresia,
            GestionMiembros gestion, MensajeRepository mensajes, EventosGrupoRepository eventosGrupo,
            ApplicationEventPublisher eventos, Clock clock) {
        this.conversaciones = conversaciones;
        this.membresia = membresia;
        this.gestion = gestion;
        this.mensajes = mensajes;
        this.eventosGrupo = eventosGrupo;
        this.eventos = eventos;
        this.clock = clock;
    }

    /**
     * Bloquea el grupo (spec 8) y exige que el actor sea miembro activo. A quien nunca fue miembro se le dice que
     * el grupo no existe; a quien ya salió, que no es miembro.
     */
    public void bloquearComoMiembro(UUID grupoId, UUID actorId) {
        boolean esGrupo = conversaciones.bloquear(grupoId).filter(tipo -> tipo == TipoConversacion.GRUPO).isPresent();
        if (!esGrupo || !membresia.fueMiembro(grupoId, actorId)) {
            throw ErroresConversacion.grupoNoEncontrado();
        }
        if (!membresia.esMiembroActivo(grupoId, actorId)) {
            throw ErroresConversacion.noEsMiembro();
        }
    }

    public void bloquearComoAdmin(UUID grupoId, UUID actorId) {
        bloquearComoMiembro(grupoId, actorId);
        if (gestion.rolActivo(grupoId, actorId).filter(rol -> rol == Rol.ADMIN).isEmpty()) {
            throw ErroresConversacion.sinPermiso();
        }
    }

    /** Inserta el mensaje EVENTO con la próxima secuencia y lo publica. Llamar con el grupo bloqueado. */
    public long registrarEvento(UUID grupoId, UUID actorId, TipoEvento evento, UUID afectadoId, String valor) {
        long secuencia = mensajes.siguienteSecuencia(grupoId);
        MensajeDto mensaje = eventosGrupo.insertar(grupoId, secuencia, actorId, evento, afectadoId, valor,
                Tiempos.ahora(clock));
        eventos.publishEvent(new MensajeEnviado(mensaje));
        return secuencia;
    }

    /** CONVERSACION_ACTUALIZADA para los miembros activos y, además, para quienes acaban de salir. */
    public void avisarCambio(UUID grupoId, UUID... ademas) {
        Set<UUID> afectados = new LinkedHashSet<>(membresia.miembrosActivos(grupoId));
        afectados.addAll(Arrays.asList(ademas));
        eventos.publishEvent(new ConversacionActualizada(grupoId, Set.copyOf(afectados)));
    }
}
```

- [ ] **Step 5: Servicio y controlador**

Archivo: `backend/src/main/java/com/hellocr/grupos/SolicitudGrupo.java`

```java
package com.hellocr.grupos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SolicitudGrupo(
        @NotBlank(message = "El grupo necesita un nombre.")
        @Size(max = 50, message = "El nombre puede tener hasta 50 caracteres.")
        String nombre,

        @Size(max = 500, message = "La descripción puede tener hasta 500 caracteres.")
        String descripcion,

        @NotNull(message = "Elegí al menos una persona.")
        @Size(min = 1, message = "Elegí al menos una persona.")
        List<@NotNull(message = "Hay una persona sin id.") UUID> miembrosIds) {

    public SolicitudGrupo {
        nombre = nombre == null ? null : nombre.trim();
        descripcion = descripcion == null || descripcion.isBlank() ? null : descripcion.trim();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/SolicitudEdicionGrupo.java`

```java
package com.hellocr.grupos;

import jakarta.validation.constraints.Size;

/** Campos opcionales: null no cambia nada; una descripción vacía la borra. */
public record SolicitudEdicionGrupo(
        @Size(min = 1, max = 50, message = "El nombre debe tener entre 1 y 50 caracteres.")
        String nombre,

        @Size(max = 500, message = "La descripción puede tener hasta 500 caracteres.")
        String descripcion) {

    public SolicitudEdicionGrupo {
        nombre = nombre == null ? null : nombre.trim();
        descripcion = descripcion == null ? null : descripcion.trim();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/GrupoService.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.Tiempos;
import com.hellocr.conversaciones.ConversacionDetalle;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.LecturaConversacionesService;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spec 8.5: crear y editar grupos. Los miembros y roles los maneja MiembrosGrupoService. */
@Service
public class GrupoService {

    private final UsuarioRepository usuarios;
    private final ConversacionRepository conversaciones;
    private final GrupoRepository grupos;
    private final GestionMiembros gestion;
    private final OperacionesGrupo operaciones;
    private final LecturaConversacionesService lectura;
    private final GruposProperties propiedades;
    private final Clock clock;

    public GrupoService(UsuarioRepository usuarios, ConversacionRepository conversaciones, GrupoRepository grupos,
            GestionMiembros gestion, OperacionesGrupo operaciones, LecturaConversacionesService lectura,
            GruposProperties propiedades, Clock clock) {
        this.usuarios = usuarios;
        this.conversaciones = conversaciones;
        this.grupos = grupos;
        this.gestion = gestion;
        this.operaciones = operaciones;
        this.lectura = lectura;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Quien crea queda como ADMIN; todos ven desde el evento GRUPO_CREADO (secuencia 1). */
    @Transactional
    public ConversacionDetalle crear(UUID creadorId, SolicitudGrupo solicitud) {
        Set<UUID> invitados = new LinkedHashSet<>(solicitud.miembrosIds());
        if (invitados.contains(creadorId)) {
            throw ErrorNegocio.validacion("miembrosIds", "No te incluyas en la lista: ya sos parte del grupo.");
        }
        if (invitados.size() > propiedades.maxMiembros() - 1) {
            throw ErrorNegocio.validacion("miembrosIds",
                    "Un grupo puede tener hasta " + propiedades.maxMiembros() + " personas, contándote a vos.");
        }
        for (UUID invitado : invitados) {
            if (usuarios.verificadoPorId(invitado).isEmpty()) {
                throw ErroresUsuario.noEncontrado();
            }
        }
        UUID grupoId = conversaciones.crear(TipoConversacion.GRUPO, Tiempos.ahora(clock));
        grupos.crear(grupoId, solicitud.nombre(), solicitud.descripcion(), creadorId);
        gestion.incorporar(grupoId, creadorId, Rol.ADMIN, 1);
        invitados.forEach(invitado -> gestion.incorporar(grupoId, invitado, Rol.MIEMBRO, 1));
        operaciones.registrarEvento(grupoId, creadorId, TipoEvento.GRUPO_CREADO, null, null);
        operaciones.avisarCambio(grupoId);
        return lectura.detalle(creadorId, grupoId);
    }

    /** Cambiar el nombre deja un evento en el historial; la descripción cambia sin evento. */
    @Transactional
    public ConversacionDetalle editar(UUID actorId, UUID grupoId, SolicitudEdicionGrupo solicitud) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (solicitud.nombre() != null && !solicitud.nombre().equals(grupos.nombre(grupoId))) {
            grupos.cambiarNombre(grupoId, solicitud.nombre());
            operaciones.registrarEvento(grupoId, actorId, TipoEvento.NOMBRE_CAMBIADO, null, solicitud.nombre());
        }
        if (solicitud.descripcion() != null) {
            grupos.cambiarDescripcion(grupoId, solicitud.descripcion().isEmpty() ? null : solicitud.descripcion());
        }
        operaciones.avisarCambio(grupoId);
        return lectura.detalle(actorId, grupoId);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/GrupoController.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.UsuarioAutenticado;
import com.hellocr.conversaciones.ConversacionDetalle;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grupos")
public class GrupoController {

    private final GrupoService grupos;

    public GrupoController(GrupoService grupos) {
        this.grupos = grupos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversacionDetalle crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SolicitudGrupo solicitud) {
        return grupos.crear(UsuarioAutenticado.id(jwt), solicitud);
    }

    @PatchMapping("/{id}")
    public ConversacionDetalle editar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SolicitudEdicionGrupo solicitud) {
        return grupos.editar(UsuarioAutenticado.id(jwt), id, solicitud);
    }
}
```

- [ ] **Step 6: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): crear y editar grupos con eventos en el historial y avisos en tiempo real"
```

---

### Task 10: Grupos — miembros y administradores

**Files:**
- Create en `backend/src/main/java/com/hellocr/grupos/`: `SolicitudMiembro.java`, `MiembrosGrupoService.java`, `MiembrosGrupoController.java`
- Test: `backend/src/test/java/com/hellocr/grupos/MiembrosGrupoTest.java`, `backend/src/test/java/com/hellocr/grupos/GruposTiempoRealTest.java`

**Interfaces:**
- Consumes: `OperacionesGrupo`, `GrupoService`, `SolicitudGrupo`, `TipoEvento`, `GruposProperties` (Task 9); `ConsultaMembresia`, `GestionMiembros`, `ErroresConversacion` (Task 2); `HistorialService`, `EnvioMensajesService` (Tasks 4 y 6, en los tests); `PruebaTiempoReal`, `ClienteStomp` (Task 7).
- Produces (API, spec 9.3): `POST /api/grupos/{id}/miembros` `{usuarioId}`, `DELETE /api/grupos/{id}/miembros/{usuarioId}`, `PUT` y `DELETE /api/grupos/{id}/administradores/{usuarioId}` y `POST /api/grupos/{id}/salir`, todos → 204.
- Produces: `MiembrosGrupoService.agregar`, `quitar`, `salir`, `hacerAdmin`, `quitarAdmin` (todos `(UUID actor, UUID grupoId, …)`).

- [ ] **Step 1: Tests**

Archivo: `backend/src/test/java/com/hellocr/grupos/MiembrosGrupoTest.java`

```java
package com.hellocr.grupos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.HistorialService;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.SolicitudEnvio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class MiembrosGrupoTest extends PruebaIntegracion {

    @Autowired
    private GrupoService grupos;
    @Autowired
    private HistorialService historial;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private GestionMiembros gestion;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;
    private UUID grupo;

    @BeforeEach
    void grupoDeAnaConLuis() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
        grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();
    }

    @Test
    void agregarAbreUnPeriodoDesdeElEvento() throws Exception {
        agregar(ana, sofia).andExpect(status().isNoContent());
        enviar(ana, "¡Bienvenida!");

        List<MensajeDto> deSofia = mensajes(sofia);
        assertThat(deSofia).extracting(MensajeDto::secuencia).containsExactly(2L, 3L);
        assertThat(deSofia.getFirst().evento().evento()).isEqualTo("MIEMBRO_AGREGADO");
        assertThat(deSofia.getFirst().evento().afectadoId()).isEqualTo(id(sofia));
        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 3L);
    }

    @Test
    void soloUnAdminAgregaYNoSePuedeRepetirNiPasarseDelMaximo() throws Exception {
        agregar(luis, sofia).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        agregar(ana, luis).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("YA_ES_MIEMBRO"));
        mvc.perform(post("/api/grupos/" + grupo + "/miembros").with(con(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usuarioId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        agregar(ana, sofia).andExpect(status().isNoContent());
        agregar(ana, sesionDe("marco")).andExpect(status().isNoContent());
        agregar(ana, sesionDe("elena")).andExpect(status().isNoContent());
        agregar(ana, sesionDe("diego"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("GRUPO_LLENO"))
                .andExpect(jsonPath("$.detail").value("El grupo ya tiene el máximo de 5 personas."));
    }

    @Test
    void quitarCierraElPeriodoEnElEventoYElQuitadoQuedaDeSoloLectura() throws Exception {
        quitar(ana, luis).andExpect(status().isNoContent());
        enviar(ana, "ya no lo ves");

        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L);
        assertThat(mensajes(luis).getLast().evento().evento()).isEqualTo("MIEMBRO_QUITADO");
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis)))
                .andExpect(jsonPath("$.activa").value(false))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"));
        assertThatThrownBy(() -> enviar(luis, "hola")).isInstanceOfSatisfying(ErrorNegocio.class,
                error -> assertThat(error.codigo()).isEqualTo(CodigoError.NO_ES_MIEMBRO));
        mvc.perform(patch("/api/grupos/" + grupo).with(con(luis))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\": \"Otra\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("NO_ES_MIEMBRO"));
    }

    @Test
    void noSePuedeQuitarAUnoMismoNiAQuienNoEsta() throws Exception {
        quitar(ana, ana)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.usuarioId").value("Para irte del grupo usá «Salir»."));
        quitar(ana, sofia)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Esa persona no está en el grupo."));
    }

    @Test
    void siSaleElUltimoAdminAsciendeElMiembroMasAntiguo() throws Exception {
        agregar(ana, sofia);

        salir(ana).andExpect(status().isNoContent());

        List<MensajeDto> deLuis = mensajes(luis);
        assertThat(deLuis).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "MIEMBRO_AGREGADO", "MIEMBRO_SALIO", "ADMIN_ASIGNADO");
        assertThat(deLuis.getLast().evento().afectadoId()).isEqualTo(id(luis));
        assertThat(deLuis.getLast().remitenteId()).isEqualTo(id(ana));
        assertThat(mensajes(ana)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 3L);
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis))).andExpect(jsonPath("$.miRol").value("ADMIN"));
        salir(ana).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("NO_ES_MIEMBRO"));
    }

    @Test
    void siQuedanOtrosAdminsNadieAsciendeYSiSaleElUltimoMiembroNoPasaNada() throws Exception {
        hacerAdmin(ana, luis).andExpect(status().isNoContent());

        salir(ana).andExpect(status().isNoContent());
        salir(luis).andExpect(status().isNoContent());

        assertThat(mensajes(ana)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "MIEMBRO_SALIO");
        assertThat(mensajes(luis)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "MIEMBRO_SALIO", "MIEMBRO_SALIO");
        assertThat(gestion.contarActivos(grupo)).isZero();
    }

    @Test
    void hacerYQuitarAdminDejaSiempreAlMenosUno() throws Exception {
        hacerAdmin(ana, luis).andExpect(status().isNoContent());
        hacerAdmin(ana, luis).andExpect(status().isNoContent());
        quitarAdmin(ana, luis).andExpect(status().isNoContent());
        quitarAdmin(ana, ana)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ULTIMO_ADMIN"));
        hacerAdmin(ana, sofia).andExpect(status().isNotFound());
        hacerAdmin(luis, luis).andExpect(status().isForbidden());

        assertThat(mensajes(ana)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "ADMIN_QUITADO");
    }

    @Test
    void quienVuelveAEntrarVeSoloSusPeriodos() throws Exception {
        quitar(ana, luis);
        enviar(ana, "mientras no estaba");
        agregar(ana, luis);
        enviar(ana, "de vuelta");

        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 4L, 5L);
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis)))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miembros[1].usuario.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$.miembros[1].periodos[0].desde").value(1))
                .andExpect(jsonPath("$.miembros[1].periodos[0].hasta").value(2))
                .andExpect(jsonPath("$.miembros[1].periodos[1].desde").value(4))
                .andExpect(jsonPath("$.miembros[1].periodos[1].hasta").value(nullValue()));
    }

    @Test
    void agregarEnParaleloNuncaPasaDelMaximo() throws Exception {
        List<Callable<Integer>> tareas = new ArrayList<>();
        for (String nombre : List.of("marco", "elena", "diego", "carla", "pablo")) {
            SesionPrueba invitado = sesionDe(nombre);
            tareas.add(() -> agregar(ana, invitado).andReturn().getResponse().getStatus());
        }

        List<Integer> estados = enParalelo(tareas);

        assertThat(Collections.frequency(estados, 204)).isEqualTo(3);
        assertThat(Collections.frequency(estados, 409)).isEqualTo(2);
        assertThat(gestion.contarActivos(grupo)).isEqualTo(5);
    }

    private List<MensajeDto> mensajes(SesionPrueba sesion) {
        return historial.pagina(id(sesion), grupo, null, null, null).mensajes();
    }

    private void enviar(SesionPrueba sesion, String texto) {
        envio.enviar(id(sesion), new SolicitudEnvio(UUID.randomUUID(), grupo, texto));
    }

    private ResultActions agregar(SesionPrueba actor, SesionPrueba invitado) throws Exception {
        return mvc.perform(post("/api/grupos/" + grupo + "/miembros").with(con(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"usuarioId\": \"" + invitado.usuarioId() + "\"}"));
    }

    private ResultActions quitar(SesionPrueba actor, SesionPrueba quitado) throws Exception {
        return mvc.perform(delete("/api/grupos/" + grupo + "/miembros/" + quitado.usuarioId()).with(con(actor)));
    }

    private ResultActions salir(SesionPrueba actor) throws Exception {
        return mvc.perform(post("/api/grupos/" + grupo + "/salir").with(con(actor)));
    }

    private ResultActions hacerAdmin(SesionPrueba actor, SesionPrueba otro) throws Exception {
        return mvc.perform(put("/api/grupos/" + grupo + "/administradores/" + otro.usuarioId()).with(con(actor)));
    }

    private ResultActions quitarAdmin(SesionPrueba actor, SesionPrueba otro) throws Exception {
        return mvc.perform(delete("/api/grupos/" + grupo + "/administradores/" + otro.usuarioId()).with(con(actor)));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/grupos/GruposTiempoRealTest.java`

```java
package com.hellocr.grupos;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class GruposTiempoRealTest extends PruebaTiempoReal {

    @Autowired
    private GrupoService grupos;
    @Autowired
    private MiembrosGrupoService miembros;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
    }

    @Test
    void crearUnGrupoLesAvisaATodosSusMiembros() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();

        for (ClienteStomp cliente : List.of(deAna, deLuis)) {
            assertThat(cliente.esperar("CONVERSACION_ACTUALIZADA").path("conversacionId").asString())
                    .isEqualTo(grupo.toString());
            assertThat(cliente.esperar("MENSAJE_NUEVO").at("/mensaje/evento/evento").asString())
                    .isEqualTo("GRUPO_CREADO");
        }
    }

    @Test
    void alQuitadoLeLlegaSuSalidaPeroNoLosMensajesSiguientes() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp deSofia = conectar(sofia);
        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis), id(sofia)))).id();
        for (ClienteStomp cliente : List.of(deAna, deLuis, deSofia)) {
            cliente.esperar("MENSAJE_NUEVO");
            cliente.esperar("CONVERSACION_ACTUALIZADA");
        }

        miembros.quitar(id(ana), grupo, id(sofia));

        JsonNode salida = deSofia.esperar("MENSAJE_NUEVO");
        assertThat(salida.at("/mensaje/evento/evento").asString()).isEqualTo("MIEMBRO_QUITADO");
        assertThat(salida.at("/mensaje/evento/afectadoId").asString()).isEqualTo(sofia.usuarioId());
        assertThat(deSofia.esperar("CONVERSACION_ACTUALIZADA").path("conversacionId").asString())
                .isEqualTo(grupo.toString());
        assertThat(deLuis.esperar("MENSAJE_NUEVO").at("/mensaje/evento/evento").asString())
                .isEqualTo("MIEMBRO_QUITADO");

        deAna.enviar("/app/mensajes.enviar",
                Map.of("idCliente", UUID.randomUUID(), "conversacionId", grupo, "texto", "¿Quién viene el domingo?"));
        assertThat(deLuis.esperar("MENSAJE_NUEVO").at("/mensaje/texto").asString())
                .isEqualTo("¿Quién viene el domingo?");
        deSofia.sinEventos("MENSAJE_NUEVO", Duration.ofMillis(500));

        deSofia.enviar("/app/mensajes.enviar",
                Map.of("idCliente", UUID.randomUUID(), "conversacionId", grupo, "texto", "¡Esperen!"));
        assertThat(deSofia.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_ES_MIEMBRO");
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
```

- [ ] **Step 2: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='MiembrosGrupoTest,GruposTiempoRealTest'`
Expected: FAIL de compilación — `cannot find symbol: class MiembrosGrupoService`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/grupos/SolicitudMiembro.java`

```java
package com.hellocr.grupos;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SolicitudMiembro(@NotNull(message = "Falta la persona que querés agregar.") UUID usuarioId) {
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/MiembrosGrupoService.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec 8.5: cada cambio deja un evento con su secuencia, y los periodos de membresía empiezan o terminan
 * justo en ese evento. Todo pasa con el grupo bloqueado, así nunca se supera el máximo de miembros.
 */
@Service
public class MiembrosGrupoService {

    private final UsuarioRepository usuarios;
    private final ConsultaMembresia membresia;
    private final GestionMiembros gestion;
    private final OperacionesGrupo operaciones;
    private final GruposProperties propiedades;

    public MiembrosGrupoService(UsuarioRepository usuarios, ConsultaMembresia membresia, GestionMiembros gestion,
            OperacionesGrupo operaciones, GruposProperties propiedades) {
        this.usuarios = usuarios;
        this.membresia = membresia;
        this.gestion = gestion;
        this.operaciones = operaciones;
        this.propiedades = propiedades;
    }

    /** Quien entra ve desde el evento "te agregaron" en adelante. */
    @Transactional
    public void agregar(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (usuarios.verificadoPorId(usuarioId).isEmpty()) {
            throw ErroresUsuario.noEncontrado();
        }
        if (membresia.esMiembroActivo(grupoId, usuarioId)) {
            throw ErroresConversacion.yaEsMiembro();
        }
        if (gestion.contarActivos(grupoId) >= propiedades.maxMiembros()) {
            throw ErroresConversacion.grupoLleno(propiedades.maxMiembros());
        }
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_AGREGADO, usuarioId, null);
        gestion.incorporar(grupoId, usuarioId, Rol.MIEMBRO, secuencia);
        operaciones.avisarCambio(grupoId);
    }

    /** Quien sale ve hasta el evento "te quitaron"; después, el chat le queda de solo lectura. */
    @Transactional
    public void quitar(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        if (actorId.equals(usuarioId)) {
            throw ErrorNegocio.validacion("usuarioId", "Para irte del grupo usá «Salir».");
        }
        if (!membresia.esMiembroActivo(grupoId, usuarioId)) {
            throw ErroresConversacion.personaFueraDelGrupo();
        }
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_QUITADO, usuarioId, null);
        gestion.retirar(grupoId, usuarioId, secuencia);
        operaciones.avisarCambio(grupoId, usuarioId);
    }

    /** Si se va el último admin y queda alguien, asciende el miembro activo más antiguo. */
    @Transactional
    public void salir(UUID actorId, UUID grupoId) {
        operaciones.bloquearComoMiembro(grupoId, actorId);
        long secuencia = operaciones.registrarEvento(grupoId, actorId, TipoEvento.MIEMBRO_SALIO, null, null);
        gestion.retirar(grupoId, actorId, secuencia);
        if (gestion.contarAdministradoresActivos(grupoId) == 0) {
            gestion.activoMasAntiguo(grupoId).ifPresent(sucesor -> {
                gestion.cambiarRol(grupoId, sucesor, Rol.ADMIN);
                operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_ASIGNADO, sucesor, null);
            });
        }
        operaciones.avisarCambio(grupoId, actorId);
    }

    @Transactional
    public void hacerAdmin(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        Rol rol = gestion.rolActivo(grupoId, usuarioId).orElseThrow(ErroresConversacion::personaFueraDelGrupo);
        if (rol == Rol.ADMIN) {
            return;
        }
        gestion.cambiarRol(grupoId, usuarioId, Rol.ADMIN);
        operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_ASIGNADO, usuarioId, null);
        operaciones.avisarCambio(grupoId);
    }

    /** Un admin puede quitarse el rol a sí mismo, siempre que quede otro. */
    @Transactional
    public void quitarAdmin(UUID actorId, UUID grupoId, UUID usuarioId) {
        operaciones.bloquearComoAdmin(grupoId, actorId);
        Rol rol = gestion.rolActivo(grupoId, usuarioId).orElseThrow(ErroresConversacion::personaFueraDelGrupo);
        if (rol == Rol.MIEMBRO) {
            return;
        }
        if (gestion.contarAdministradoresActivos(grupoId) == 1) {
            throw ErroresConversacion.ultimoAdmin();
        }
        gestion.cambiarRol(grupoId, usuarioId, Rol.MIEMBRO);
        operaciones.registrarEvento(grupoId, actorId, TipoEvento.ADMIN_QUITADO, usuarioId, null);
        operaciones.avisarCambio(grupoId);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/grupos/MiembrosGrupoController.java`

```java
package com.hellocr.grupos;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grupos/{id}")
public class MiembrosGrupoController {

    private final MiembrosGrupoService miembros;

    public MiembrosGrupoController(MiembrosGrupoService miembros) {
        this.miembros = miembros;
    }

    @PostMapping("/miembros")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void agregar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SolicitudMiembro solicitud) {
        miembros.agregar(UsuarioAutenticado.id(jwt), id, solicitud.usuarioId());
    }

    @DeleteMapping("/miembros/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.quitar(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @PutMapping("/administradores/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hacerAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.hacerAdmin(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @DeleteMapping("/administradores/{usuarioId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitarAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID usuarioId) {
        miembros.quitarAdmin(UsuarioAutenticado.id(jwt), id, usuarioId);
    }

    @PostMapping("/salir")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void salir(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        miembros.salir(UsuarioAutenticado.id(jwt), id);
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): agregar, quitar y salir de grupos, y roles de administrador"
```

---

### Task 11: Lista de conversaciones

**Files:**
- Create en `backend/src/main/java/com/hellocr/conversaciones/`: `OtroUsuario.java`, `ConversacionResumen.java`, `ListaConversacionesService.java`
- Modify: `backend/src/main/java/com/hellocr/conversaciones/ConversacionController.java` (`GET /api/conversaciones`)
- Test: `backend/src/test/java/com/hellocr/conversaciones/ListaConversacionesTest.java`

**Interfaces:**
- Consumes: `MembresiaRepository.visiblePara`, `TipoConversacion`, `Tiempos` (Task 2); `ChatDirectoService`, `ConversacionController` (Task 3); `MensajeRepository.COLUMNAS/DESDE/MAPEO`, `MensajeDto`, `EnvioMensajesService` (Task 4); `MarcasService` (Task 5); `ConsultaPresencia`, `RegistroSesiones` (Task 7); `GrupoService`, `MiembrosGrupoService` (Tasks 9 y 10, en los tests).
- Produces (API): `GET /api/conversaciones` → `[ConversacionResumen]` ordenada por el último mensaje visible (o por la creación si no hay mensajes), de más reciente a más antiguo; incluye las conversaciones de las que el usuario ya salió.
- Produces: `record ConversacionResumen(UUID id, TipoConversacion tipo, String titulo, OtroUsuario otroUsuario, MensajeDto ultimoMensaje, long ultimaSecuencia, long noLeidos, boolean activa)`; `record OtroUsuario(UUID id, String nombreUsuario, String nombreVisible, String info, boolean enLinea, Instant ultimaConexion)` (solo en chats directos).

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/conversaciones/ListaConversacionesTest.java`

```java
package com.hellocr.conversaciones;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.grupos.GrupoService;
import com.hellocr.grupos.MiembrosGrupoService;
import com.hellocr.grupos.SolicitudGrupo;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.MarcasService;
import com.hellocr.mensajes.SolicitudEnvio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.tiempoReal.RegistroSesiones;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class ListaConversacionesTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private MarcasService marcas;
    @Autowired
    private GrupoService grupos;
    @Autowired
    private MiembrosGrupoService miembros;
    @Autowired
    private RegistroSesiones registroSesiones;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
    }

    @Test
    void ordenaPorElUltimoMensajeEIncluyeChatsYGrupos() throws Exception {
        UUID conLuis = abrir(ana, luis);
        UUID conSofia = abrir(ana, sofia);
        grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis), id(sofia))));
        reloj.avanzar(Duration.ofMinutes(1));
        enviar(luis, conLuis, "hola");
        reloj.avanzar(Duration.ofMinutes(1));
        enviar(sofia, conSofia, "buenas");

        lista(ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].titulo").value(contains("Usuario sofia", "Usuario luis", "Familia")))
                .andExpect(jsonPath("$[2].tipo").value("GRUPO"))
                .andExpect(jsonPath("$[2].otroUsuario").value(nullValue()))
                .andExpect(jsonPath("$[2].ultimoMensaje.evento.evento").value("GRUPO_CREADO"))
                .andExpect(jsonPath("$[0].ultimoMensaje.texto").value("buenas"));
    }

    @Test
    void elChatDirectoTraeALaOtraPersonaConSuPresencia() throws Exception {
        abrir(ana, luis);
        jdbc.update("UPDATE usuarios SET ultima_conexion = '2026-09-30T20:00:00Z' WHERE nombre_usuario = 'luis'");

        lista(ana)
                .andExpect(jsonPath("$[0].titulo").value("Usuario luis"))
                .andExpect(jsonPath("$[0].otroUsuario.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$[0].otroUsuario.enLinea").value(false))
                .andExpect(jsonPath("$[0].otroUsuario.ultimaConexion").value("2026-09-30T20:00:00Z"))
                .andExpect(jsonPath("$[0].otroUsuario.correo").doesNotExist())
                .andExpect(jsonPath("$[0].ultimoMensaje").value(nullValue()))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(0))
                .andExpect(jsonPath("$[0].noLeidos").value(0))
                .andExpect(jsonPath("$[0].activa").value(true));

        registroSesiones.abrir("sesion-de-prueba", id(luis), reloj.instant().plus(Duration.ofMinutes(15)));
        try {
            lista(ana).andExpect(jsonPath("$[0].otroUsuario.enLinea").value(true));
        } finally {
            registroSesiones.cerrar("sesion-de-prueba");
        }
    }

    @Test
    void noLeidosCuentaSoloMensajesAjenosPosterioresALaUltimaLeida() throws Exception {
        UUID chat = abrir(ana, luis);
        for (int i = 1; i <= 3; i++) {
            enviar(luis, chat, "mensaje " + i);
        }

        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(3)).andExpect(jsonPath("$[0].ultimaSecuencia").value(3));
        marcas.leidos(id(ana), chat, 2L);
        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(1));
        enviar(ana, chat, "ya leí todo");
        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(0)).andExpect(jsonPath("$[0].ultimaSecuencia").value(4));
        lista(luis).andExpect(jsonPath("$[0].noLeidos").value(1));
    }

    @Test
    void unExMiembroVeElGrupoInactivoYSoloHastaSuSalida() throws Exception {
        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();
        miembros.quitar(id(ana), grupo, id(luis));
        enviar(ana, grupo, "ya no lo ves");

        lista(luis)
                .andExpect(jsonPath("$[0].activa").value(false))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(2))
                .andExpect(jsonPath("$[0].ultimoMensaje.evento.evento").value("MIEMBRO_QUITADO"))
                .andExpect(jsonPath("$[0].noLeidos").value(0));
        lista(ana)
                .andExpect(jsonPath("$[0].activa").value(true))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(3));
    }

    private ResultActions lista(SesionPrueba sesion) throws Exception {
        return mvc.perform(get("/api/conversaciones").with(con(sesion)));
    }

    private UUID abrir(SesionPrueba sesion, SesionPrueba otra) {
        return chats.abrir(id(sesion), id(otra)).detalle().id();
    }

    private void enviar(SesionPrueba sesion, UUID conversacion, String texto) {
        envio.enviar(id(sesion), new SolicitudEnvio(UUID.randomUUID(), conversacion, texto));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=ListaConversacionesTest`
Expected: FAIL — `GET /api/conversaciones` todavía no existe: `Status expected:<200> but was:<404>` (y `No value at JSON path` en los tests que no miran el estado).

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/conversaciones/OtroUsuario.java`

```java
package com.hellocr.conversaciones;

import java.time.Instant;
import java.util.UUID;

/** La otra persona de un chat directo: su perfil público más su presencia (spec 9.2). */
public record OtroUsuario(UUID id, String nombreUsuario, String nombreVisible, String info, boolean enLinea,
        Instant ultimaConexion) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionResumen.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.mensajes.MensajeDto;
import java.util.UUID;

/** Una fila de la lista de chats (spec 9.2). ultimaSecuencia es la última visible; 0 si no hay mensajes. */
public record ConversacionResumen(UUID id, TipoConversacion tipo, String titulo, OtroUsuario otroUsuario,
        MensajeDto ultimoMensaje, long ultimaSecuencia, long noLeidos, boolean activa) {
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ListaConversacionesService.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.Tiempos;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.MensajeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Una sola consulta para toda la lista (decenas de chats): el último mensaje visible sale con LATERAL y los no
 * leídos con una subconsulta; nada de esto se guarda (spec 6, 3FN).
 */
@Service
public class ListaConversacionesService {

    private static final String CONSULTA = """
            SELECT c.id, c.tipo AS tipo_conversacion, c.creada_en, g.nombre AS nombre_grupo,
                   EXISTS (SELECT 1 FROM periodos_membresia pa
                           WHERE pa.conversacion_id = c.id AND pa.usuario_id = :yo AND pa.hasta_secuencia IS NULL)
                       AS activa,
                   o.id AS otro_id, o.nombre_usuario AS otro_nombre_usuario, o.nombre_visible AS otro_nombre_visible,
                   o.info AS otro_info, o.ultima_conexion AS otro_ultima_conexion,
                   u.conversacion_id, u.secuencia, u.id_cliente, u.remitente_id, u.tipo, u.texto, u.creado_en,
                   u.evento, u.afectado_id, u.valor,
                   (SELECT count(*) FROM mensajes x
                    WHERE x.conversacion_id = c.id AND x.tipo <> 'EVENTO' AND x.remitente_id <> :yo
                      AND x.secuencia > mi.ultima_leida AND %1$s) AS no_leidos
            FROM miembros mi
            JOIN conversaciones c ON c.id = mi.conversacion_id
            LEFT JOIN grupos g ON g.conversacion_id = c.id
            LEFT JOIN chats_directos d ON d.conversacion_id = c.id
            LEFT JOIN usuarios o ON o.id = CASE WHEN d.usuario_a_id = :yo THEN d.usuario_b_id ELSE d.usuario_a_id END
            LEFT JOIN LATERAL (
                SELECT %2$s FROM %3$s
                WHERE m.conversacion_id = c.id AND %4$s
                ORDER BY m.secuencia DESC LIMIT 1) u ON true
            WHERE mi.usuario_id = :yo
            ORDER BY COALESCE(u.creado_en, c.creada_en) DESC
            """.formatted(MembresiaRepository.visiblePara("x"), MensajeRepository.COLUMNAS, MensajeRepository.DESDE,
            MembresiaRepository.visiblePara("m"));

    private final JdbcClient jdbc;
    private final ConsultaPresencia presencia;

    public ListaConversacionesService(JdbcClient jdbc, ConsultaPresencia presencia) {
        this.jdbc = jdbc;
        this.presencia = presencia;
    }

    @Transactional(readOnly = true)
    public List<ConversacionResumen> de(UUID usuarioId) {
        return jdbc.sql(CONSULTA)
                .param("yo", usuarioId)
                .query((fila, numero) -> {
                    TipoConversacion tipo = TipoConversacion.valueOf(fila.getString("tipo_conversacion"));
                    UUID otroId = fila.getObject("otro_id", UUID.class);
                    OtroUsuario otro = otroId == null ? null : new OtroUsuario(otroId,
                            fila.getString("otro_nombre_usuario"), fila.getString("otro_nombre_visible"),
                            fila.getString("otro_info"), presencia.enLinea(otroId),
                            Tiempos.instante(fila, "otro_ultima_conexion"));
                    MensajeDto ultimo = fila.getObject("secuencia") == null ? null
                            : MensajeRepository.MAPEO.mapRow(fila, numero);
                    String titulo = tipo == TipoConversacion.GRUPO ? fila.getString("nombre_grupo")
                            : otro == null ? "" : otro.nombreVisible();
                    return new ConversacionResumen(fila.getObject("id", UUID.class), tipo, titulo, otro, ultimo,
                            ultimo == null ? 0 : ultimo.secuencia(), fila.getLong("no_leidos"),
                            fila.getBoolean("activa"));
                })
                .list();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/conversaciones/ConversacionController.java`

```java
package com.hellocr.conversaciones;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversaciones")
public class ConversacionController {

    private final ChatDirectoService chats;
    private final LecturaConversacionesService lectura;
    private final ListaConversacionesService lista;

    public ConversacionController(ChatDirectoService chats, LecturaConversacionesService lectura,
            ListaConversacionesService lista) {
        this.chats = chats;
        this.lectura = lectura;
        this.lista = lista;
    }

    @GetMapping
    public List<ConversacionResumen> lista(@AuthenticationPrincipal Jwt jwt) {
        return lista.de(UsuarioAutenticado.id(jwt));
    }

    @PostMapping("/directas")
    public ResponseEntity<ConversacionDetalle> abrirDirecta(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SolicitudChatDirecto solicitud) {
        ChatDirectoService.Apertura apertura = chats.abrir(UsuarioAutenticado.id(jwt), solicitud.usuarioId());
        return ResponseEntity.status(apertura.creada() ? HttpStatus.CREATED : HttpStatus.OK).body(apertura.detalle());
    }

    @GetMapping("/{id}")
    public ConversacionDetalle detalle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return lectura.detalle(UsuarioAutenticado.id(jwt), id);
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): lista de conversaciones con último mensaje, no leídos y presencia"
```

---

### Task 12: Verificación final y README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Suite completa**

Run: `cd backend && ./mvnw test`
Expected: `Tests run: 201, Failures: 0, Errors: 0, Skipped: 0` y `BUILD SUCCESS`.

- [ ] **Step 2: Probar la app de punta a punta**

Arrancar en segundo plano: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. Esperar el log `Started HelloCrApplication` (Flyway aplica `V3__conversaciones` en la base `hellocr`).

```bash
curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"identificador":"yo_mismo","contrasena":"clave-de-prueba"}'
```

Expected: un cuerpo con `accessToken` (la cuenta `yo_mismo` la creó la prueba de punta a punta del plan 1; si no existe, crearla con los comandos de registro y verificación de ese paso). Con ese token:

```bash
curl -s localhost:8080/api/conversaciones -H "Authorization: Bearer <el accessToken>"
```

Expected: `[]`.

```bash
curl -s -i localhost:8080/ws
```

Expected: `HTTP/1.1 400` con `Can "Upgrade" only to "WebSocket".`: el endpoint existe y no pide token HTTP (la autenticación va en el `CONNECT`).

```bash
curl -s localhost:8080/api/conversaciones
```

Expected: `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Tenés que iniciar sesión.","codigo":"NO_AUTENTICADO"}`. Después, detener la app.

- [ ] **Step 3: README**

Archivo: `README.md`

````markdown
# HelloCR

App de mensajería al estilo de WhatsApp, instalable como PWA: chats 1 a 1 y grupos, imágenes y archivos,
estados de entrega, presencia y notificaciones push.

- Diseño: [`docs/superpowers/specs/2026-09-27-hellocr-design.md`](docs/superpowers/specs/2026-09-27-hellocr-design.md)
- Planes de implementación: [`docs/superpowers/plans/`](docs/superpowers/plans/)

| Carpeta | Tecnología |
|---|---|
| `backend/` | Java 25 · Spring Boot 4 · Maven · PostgreSQL 18 · Flyway · WebSocket/STOMP |
| `frontend/` | React · TypeScript · Vite · PWA *(plan 4)* |

## Requisitos

- JDK 25 (con `JAVA_HOME` apuntando a él)
- PostgreSQL 18 corriendo en `localhost:5432`

## Base de datos (una sola vez)

Con `psql` como usuario `postgres`:

```sql
CREATE ROLE hellocr LOGIN PASSWORD 'hellocr';
CREATE DATABASE hellocr OWNER hellocr;
CREATE DATABASE hellocr_test OWNER hellocr;
```

Las tablas las crea Flyway al arrancar. Si usás otra contraseña, cambiala en `application-local.yml` y
exportá `TEST_DB_PASSWORD` para los tests.

## Configuración

```bash
cp backend/src/main/resources/application-local.example.yml backend/src/main/resources/application-local.yml
```

Editá `application-local.yml` y poné un secreto para el JWT (`openssl rand -base64 48`). En desarrollo los
correos no se envían: aparecen en el log de la app, con el enlace para verificar la cuenta o restablecer la
contraseña. El mismo archivo explica cómo enviarlos de verdad con Gmail.

## Ejecutar

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

La API queda en `http://localhost:8080/api`.

## Tiempo real

Los mensajes viajan por STOMP sobre WebSocket en `ws://localhost:8080/ws`:

- **Conectar:** frame `CONNECT` con el header `Authorization: Bearer <accessToken>`, y suscribirse a
  `/user/queue/eventos`, donde llega todo: `MENSAJE_NUEVO`, `ESTADO_ACTUALIZADO`, `ESCRIBIENDO`, `PRESENCIA`,
  `CONVERSACION_ACTUALIZADA` y `ERROR`.
- **Enviar:** `/app/mensajes.enviar` `{idCliente, conversacionId, texto}`; acuses en `/app/mensajes.entregados` y
  `/app/mensajes.leidos` `{conversacionId, hastaSecuencia}`; "escribiendo…" en `/app/escribiendo`
  `{conversacionId}`; y `/app/sesion.renovar` con el header `Authorization: Bearer <accessToken nuevo>` antes
  de que venza el del `CONNECT` (si vence, el servidor cierra la conexión).
- **Leer:** el historial y la lista de chats se piden por REST: `GET /api/conversaciones` y
  `GET /api/conversaciones/{id}/mensajes?antesDe=&despuesDe=&limite=`.

## Tests

```bash
cd backend
./mvnw test
```

Corren contra la base `hellocr_test`, que se vacía antes de cada test. Los de tiempo real levantan el servidor en
un puerto aleatorio y se conectan con un cliente STOMP de verdad.
````

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: README con la parte de tiempo real"
```
