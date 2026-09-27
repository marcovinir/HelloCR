# Plan 1 — Backend: base, autenticación, perfil e invitaciones

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dejar funcionando la API de HelloCR con registro y verificación de correo, login con JWT y refresh rotativo, bloqueo por intentos fallidos, recuperación de contraseña, perfil, búsqueda por `@usuario` e invitaciones.

**Architecture:** Un proyecto Maven en `backend/` organizado por funcionalidad (`auth`, `usuarios`, `correo`, `config`, `comun`). La base PostgreSQL se crea con Flyway y Hibernate solo valida el esquema. Los correos se publican como evento y se envían recién después del commit, a través de la interfaz `EnviadorCorreo` (consola en desarrollo, SMTP en producción, buzón en memoria en los tests). Todo "ahora" sale de un `Clock` inyectable.

**Tech Stack:** Java 25 · Spring Boot 4.1.1 (Spring Security 7, Hibernate 7, Jackson 3) · Maven wrapper · PostgreSQL 18 · Flyway · Spring Mail · JUnit 5 + MockMvc + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-27-hellocr-design.md` (secciones 3, 4, 5, 6 [identidad], 8.8 [tareas de tokens y cuentas], 9.1, 11, 12 [auth y usuarios] y 14).

Este es el **plan 1 de 5** (spec, sección 15). Cada plan siguiente se escribe al terminar el anterior, apoyándose en el código real.

## Global Constraints

- Java **25**, Spring Boot **4.1.1**, Maven con wrapper (`./mvnw`). Base **PostgreSQL 18** instalada nativa en Windows, **sin Docker**.
- Paquete raíz `com.hellocr`; `groupId` `com.hellocr`, `artifactId` `backend`.
- Dominio en español (`Usuario`, `nombreUsuario`, `TokenCorreo`); sufijos técnicos en inglés (`SesionController`, `RegistroService`, `UsuarioRepository`, `JwtProperties`).
- Errores: siempre `ProblemDetail` (RFC 9457) con la propiedad `codigo` del enum `CodigoError`; `detail` en español con **voseo tico** ("Revisá tu correo"), listo para mostrar.
- JSON en `camelCase`; las entidades JPA nunca salen de los servicios (se devuelven DTOs `record`).
- Hora: todo "ahora" viene del `Clock` inyectado, en `app.zona-horaria` = `America/Costa_Rica`. Columnas `timestamptz`; Hibernate con `jdbc.time_zone: UTC`.
- IDs de usuario: `uuid` versión 7. Hibernate los genera con `@UuidGenerator(style = VERSION_7)`; la columna tiene `DEFAULT uuidv7()` para inserts directos en SQL.
- Access token: JWT HS256, `iss` = `hellocr`, claims `sub` (id) y `nombreUsuario`, dura **15 min**. Refresh token: 32 bytes aleatorios, dura **30 días** y se renueva con cada rotación; cookie `refresh_token` con `HttpOnly`, `SameSite=Strict`, `Path=/api/auth`; en la base solo su hash SHA-256; margen de reutilización de **30 s**.
- Tokens de correo: 32 bytes aleatorios, en la base solo su hash. Verificación **24 h**, recuperación **1 h**, espera de reenvío **1 min**.
- `@usuario`: se quita un `@` inicial, se pasa a minúsculas y debe cumplir `^[a-z][a-z0-9_.]{2,19}$`. Reservados: `admin`, `administrador`, `hellocr`, `soporte`, `sistema`, `root`.
- Contraseña: 8–64 caracteres y como máximo **72 bytes** en UTF-8 (límite de BCrypt).
- Login: 5 intentos fallidos del mismo identificador + IP bloquean **15 min** (429 con `Retry-After`).
- `spring.jpa.hibernate.ddl-auto=validate` y `spring.jpa.open-in-view=false`.
- Los tests de integración corren contra PostgreSQL real (`hellocr_test`), **nunca H2**. TDD: el test se escribe y se ve fallar antes de implementar.

## Desvíos acordados respecto al spec

Surgieron al bajar el spec a código con Spring Boot 4.1.1 y PostgreSQL 18; no cambian el comportamiento descrito salvo donde se indica.

1. `usuarios.foto_id` (y `fotoId` en los DTOs) y el `endpointPush` del logout llegan en el **plan 3**, junto con las tablas `archivos` y `suscripciones_push` de las que dependen. `usuarios.ultima_conexion` se crea ahora pero la mapea el plan 2 (presencia).
2. `codigo_invitacion` y `token_hash` son `varchar(n)` con un `CHECK` de formato en lugar de `char(n)`: la validación de esquema de Hibernate no acepta `char` para un `String`.
3. Propiedades nuevas: `app.correo.modo` (`consola` o `smtp`) elige el `EnviadorCorreo`, y `app.correo.asincrono` (`false` en los tests) decide si el envío después del commit ocurre en otro hilo.
4. `POST /api/auth/registro` responde `{correo}` para que el frontend muestre "Revisá tu correo".
5. El evento `SesionesRevocadas(usuarioId)` se publica al restablecer la contraseña **y** al detectar la reutilización de un refresh token. En este plan nadie lo escucha; el plan 2 lo usa para cerrar los WebSockets.
6. La ventana del contador de intentos de login dura lo mismo que el bloqueo (`app.login.bloqueo`) y empieza con el primer fallo.
7. Los tokens de correo que se borran a las 04:00 son todos los vencidos (un token usado hace más de 7 días ya venció, porque ninguno dura más de 24 h).

## Review Focus

Casos que el spec implica y que un borrador de tests no cubría; cada uno tiene su test en la tarea indicada.

1. **Nombre visible con HTML o comillas** (`<b>Ana</b> & "Luis"`) en el correo: debe verse tal cual, sin inyectar HTML. → Task 7, `elNombreVisibleSeEscapaEnElHtml`.
2. **Doble clic en "Crear cuenta"** (registros simultáneos con el mismo correo y usuario): una sola cuenta, nunca 500. → Task 11, `registrosSimultaneosDejanUnaSolaCuenta`.
3. **Enlace de verificación abierto dos veces** (desde el celular y desde la PC): la segunda vez responde 422, pero la cuenta sigue verificada y el login funciona. → Task 11, `verificarDosVecesFallaLaSegundaPeroLaCuentaQuedaVerificada`.
4. **Mayúsculas, espacios y `@` del autocompletado** en el correo o el usuario (login, registro, reenvío, recuperación y búsqueda): deben funcionar igual. → Task 10, `loginAceptaCorreoOUsuarioConMayusculasEspaciosYArroba`; Task 11, `normalizaElCorreoYElNombreDeUsuario`; Task 12, `recuperarEnviaUnEnlaceParaRestablecer`; Task 13, `buscarSoloEncuentraCoincidenciasExactasDeCuentasVerificadas`.
5. **Contraseña con tildes o eñes de más de 72 bytes** aunque tenga menos de 64 caracteres: 400 con el error en el campo (registro, restablecer) o 401 (login), nunca 500. → Task 10, `unaContrasenaDeMasDe72BytesNoProvocaErrorInterno`; Task 11, `unaContrasenaDeMasDe72BytesSeRechazaSinErrorInterno`; Task 12, `unaContrasenaNuevaInvalidaNoGastaElEnlace`.

## Mapa de archivos

```
HelloCR/
├── .gitignore                                  ignora application-local.yml y archivos de editores
├── README.md                                   cómo configurar, ejecutar y testear (Task 14)
└── backend/                                    generado con start.spring.io (Task 1)
    ├── pom.xml
    └── src/
        ├── main/java/com/hellocr/
        │   ├── HelloCrApplication.java         @ConfigurationPropertiesScan, @EnableScheduling
        │   ├── comun/                          CodigoError, ErrorNegocio, ApiErrorHandler, TokensSeguros,
        │   │                                   UsuarioAutenticado
        │   ├── config/                         TiempoConfig (Clock), SecurityConfig, SeguridadErrorHandler,
        │   │                                   JwtProperties
        │   ├── usuarios/                       Usuario (+ repositorio), NombresReservados, GeneradorCodigos,
        │   │                                   ErroresUsuario, UsuarioPropio, PerfilPublico, UsuarioService,
        │   │                                   UsuarioController, InvitacionController, LimpiezaCuentas,
        │   │                                   CuentasProperties y DTOs
        │   ├── correo/                         CorreoSaliente, EnviadorCorreo (+ Consola, Smtp), PlantillasCorreo,
        │   │                                   CorreosDeCuenta, CorreoPendiente, DespachadorCorreo, CorreoProperties
        │   └── auth/                           JwtService, RefreshToken(+ repositorio y servicio), TokenCorreo
        │                                       (+ repositorio y servicio), LimiteIntentosLogin, SesionService/
        │                                       Controller, RegistroService/Controller, RecuperacionService/
        │                                       Controller, CookieRefresh y DTOs
        ├── main/resources/
        │   ├── application.yml                 valores por defecto, sin secretos
        │   ├── application-local.example.yml   plantilla de application-local.yml
        │   └── db/migration/V1__identidad.sql
        └── test/
            ├── java/com/hellocr/soporte/       PruebaIntegracion, PruebasConfig, RelojAjustable, SesionPrueba,
            │                                   BuzonPrueba
            ├── java/com/hellocr/...            un test por clase o endpoint
            └── resources/application-test.yml  base hellocr_test
```

## Cómo correr los comandos

- Todos los comandos se ejecutan en **Git Bash** desde la raíz `HelloCR/` (en PowerShell, cambiá `./mvnw` por `.\mvnw.cmd`).
- Los tests necesitan PostgreSQL corriendo con la base `hellocr_test` (Task 0).
- `./mvnw -q test` no imprime nada si todo pasa; si algo falla, muestra el test y el motivo.

---

### Task 0: Preparar el entorno (lo hace la persona usuaria)

Instalar software y crear bases requiere permisos y contraseñas que solo la persona usuaria tiene. **El agente no ejecuta estos pasos**: se los muestra, espera a que confirme que están hechos y verifica con los comandos de verificación. Si ya se hizo la Task 0 de reservas-app, los pasos 1 y 2 ya están listos.

- [ ] **Step 1: Instalar el JDK 25**

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

En esta máquina hay un Java 24 en el `PATH`, así que hay que apuntar `JAVA_HOME` al 25 (el wrapper de Maven usa `JAVA_HOME`). En PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*" | Select-Object -First 1).FullName, "User")
```

Cerrar y volver a abrir la terminal. Verificación (Git Bash):

```bash
"$JAVA_HOME/bin/java" -version
```

Esperado: `openjdk version "25...`.

- [ ] **Step 2: Instalar PostgreSQL 18**

```powershell
winget install PostgreSQL.PostgreSQL.18 --interactive
```

En el asistente: puerto `5432` y una contraseña para el usuario `postgres` (anotarla).

- [ ] **Step 3: Crear el usuario y las bases**

```bash
"/c/Program Files/PostgreSQL/18/bin/psql" -U postgres -c "CREATE ROLE hellocr LOGIN PASSWORD 'hellocr';" -c "CREATE DATABASE hellocr OWNER hellocr;" -c "CREATE DATABASE hellocr_test OWNER hellocr;"
```

Pide la contraseña de `postgres`. La contraseña `hellocr` es solo para desarrollo local; si se usa otra, hay que exportar `TEST_DB_PASSWORD` con ese valor antes de correr los tests.

- [ ] **Step 4: Verificar la conexión**

```bash
PGPASSWORD=hellocr "/c/Program Files/PostgreSQL/18/bin/psql" -U hellocr -d hellocr_test -c "select version();"
```

Esperado: una fila con `PostgreSQL 18...`.

---

### Task 1: Esqueleto del backend

**Files:**
- Create: `backend/` completo (generado por start.spring.io)
- Create: `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-local.example.yml`, `backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/java/com/hellocr/HelloCrApplication.java`, `backend/src/main/java/com/hellocr/config/TiempoConfig.java`, `backend/src/test/java/com/hellocr/HelloCrApplicationTests.java`, `.gitignore`
- Delete: `backend/src/main/resources/application.properties`, `backend/HELP.md` y la clase principal y el test generados

**Interfaces:**
- Produces: bean `java.time.Clock` en la zona `app.zona-horaria`; perfil `test` apuntando a `hellocr_test`; perfil `local` que lee `application-local.yml`; `@ConfigurationPropertiesScan` activo (los `record` con `@ConfigurationProperties` de tareas siguientes se registran solos); `@EnableScheduling` activo. Todas las propiedades `app.*` de este plan ya quedan en `application.yml`.

- [ ] **Step 1: Generar el proyecto con Spring Initializr**

```bash
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d bootVersion=4.1.1 -d javaVersion=25 \
  -d groupId=com.hellocr -d artifactId=backend -d name=hellocr -d packageName=com.hellocr \
  -d dependencies=web,data-jpa,security,oauth2-resource-server,validation,flyway,postgresql,mail,configuration-processor \
  -o backend.zip && unzip -q backend.zip -d backend && rm backend.zip
rm backend/src/main/resources/application.properties backend/HELP.md
rm backend/src/main/java/com/hellocr/*Application.java backend/src/test/java/com/hellocr/*ApplicationTests.java
```

Verificar que `backend/pom.xml` tiene `<version>4.1.1</version>` en el parent, `<java.version>25</java.version>` y estas dependencias: `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `spring-boot-starter-mail`, `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-validation`, `spring-boot-starter-webmvc`, `flyway-database-postgresql`, `postgresql`, y sus `*-test`.

- [ ] **Step 2: Configuración**

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
# Credenciales de la base y app.jwt.secreto van en application-local.yml (ignorado por git).
```

Archivo: `backend/src/main/resources/application-local.example.yml`

```yaml
# Copiá este archivo como application-local.yml (git lo ignora) y cambiá los valores.
spring:
  datasource:
    username: hellocr
    password: hellocr

app:
  jwt:
    # Generá uno con: openssl rand -base64 48
    secreto: cambia-esto-por-un-secreto-largo-de-al-menos-32-caracteres
    # En desarrollo el frontend corre en http://localhost, sin HTTPS.
    cookie-segura: false
  correo:
    # consola: los correos se escriben en el log. smtp: se envían de verdad (ver abajo).
    modo: consola

# Para probar el envío real con Gmail (requiere verificación en dos pasos y una contraseña de aplicación),
# descomentá y completá esto, y cambiá app.correo.modo a smtp:
#
# spring:
#   mail:
#     host: smtp.gmail.com
#     port: 587
#     username: tu-cuenta@gmail.com
#     password: la-contraseña-de-aplicación
#     properties:
#       mail.smtp.auth: true
#       mail.smtp.starttls.enable: true
# app:
#   correo:
#     remitente: "HelloCR <tu-cuenta@gmail.com>"
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
```

Archivo: `.gitignore`

```text
# Configuración local con credenciales
backend/src/main/resources/application-local.yml

# Editores
.idea/
*.iml
.vscode/

# Sistema
.DS_Store
Thumbs.db
```

- [ ] **Step 3: Clase principal y reloj**

Archivo: `backend/src/main/java/com/hellocr/HelloCrApplication.java`

```java
package com.hellocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HelloCrApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloCrApplication.class, args);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/config/TiempoConfig.java`

```java
package com.hellocr.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TiempoConfig {

    /** Todo "ahora" de la app sale de este reloj. */
    @Bean
    public Clock clock(@Value("${app.zona-horaria}") String zonaHoraria) {
        return Clock.system(ZoneId.of(zonaHoraria));
    }
}
```

- [ ] **Step 4: Test de arranque con el perfil `test`**

Archivo: `backend/src/test/java/com/hellocr/HelloCrApplicationTests.java`

```java
package com.hellocr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifica que el contexto arranca y se conecta a la base hellocr_test. */
@SpringBootTest
@ActiveProfiles("test")
class HelloCrApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: Correr el test**

Run: `cd backend && ./mvnw -q test`
Expected: termina sin errores (el contexto arranca y se conecta a `hellocr_test`). Si falla con `Connection refused` o `password authentication failed`, la Task 0 no está completa.

- [ ] **Step 6: Commit**

```bash
git add .gitignore backend
git commit -m "chore(backend): esqueleto Spring Boot 4.1.1 con perfiles local y test"
```

---

### Task 2: Esquema de identidad y base de los tests de integración

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__identidad.sql`
- Create: `backend/src/test/java/com/hellocr/soporte/RelojAjustable.java`, `backend/src/test/java/com/hellocr/soporte/PruebasConfig.java`, `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`
- Create: `backend/src/test/java/com/hellocr/EsquemaTest.java`
- Modify: `backend/src/test/java/com/hellocr/HelloCrApplicationTests.java`

**Interfaces:**
- Consumes: bean `Clock` (Task 1).
- Produces: tablas `usuarios`, `refresh_tokens` y `tokens_correo` (spec, sección 6) con las restricciones con nombre `usuarios_correo_unico`, `usuarios_nombre_usuario_unico`, `usuarios_codigo_invitacion_unico`, `usuarios_correo_normalizado`, `usuarios_nombre_usuario_formato` y `usuarios_codigo_invitacion_formato`.
- Produces (tests): `PruebaIntegracion` — clase base abstracta con `protected MockMvc mvc`, `protected JdbcTemplate jdbc`, `protected RelojAjustable reloj` y `static enParalelo(List<Callable<T>>)` / `enParalelo(int, Callable<T>)`; antes de cada test vacía las tablas. `RelojAjustable` — `INICIO` (2026-10-01 09:00 en Costa Rica), `reiniciar()`, `fijar(Instant)`, `avanzar(Duration)`; reemplaza al `Clock` en todos los beans.

- [ ] **Step 1: Soporte de tests**

Archivo: `backend/src/test/java/com/hellocr/soporte/RelojAjustable.java`

```java
package com.hellocr.soporte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Reloj de pruebas: arranca siempre en INICIO y se mueve solo cuando el test lo pide. */
public class RelojAjustable extends Clock {

    /** Jueves 1 de octubre de 2026, 09:00 en Costa Rica (UTC-6). */
    public static final Instant INICIO = Instant.parse("2026-10-01T15:00:00Z");

    private final ZoneId zona;
    private volatile Instant ahora = INICIO;

    public RelojAjustable(ZoneId zona) {
        this.zona = zona;
    }

    public void reiniciar() {
        ahora = INICIO;
    }

    public void fijar(Instant instante) {
        ahora = instante;
    }

    public void avanzar(Duration duracion) {
        ahora = ahora.plus(duracion);
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId otraZona) {
        return Clock.fixed(ahora, otraZona);
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebasConfig.java`

```java
package com.hellocr.soporte;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class PruebasConfig {

    /** Reemplaza al Clock del sistema en todos los beans que piden un Clock. */
    @Bean
    @Primary
    public RelojAjustable relojAjustable(@Value("${app.zona-horaria}") String zonaHoraria) {
        return new RelojAjustable(ZoneId.of(zonaHoraria));
    }

    /** Para que las aserciones de MockMvc lean bien las tildes de las respuestas. */
    @Bean
    public MockMvcBuilderCustomizer respuestasEnUtf8() {
        return constructor -> constructor.defaultResponseCharacterEncoding(StandardCharsets.UTF_8);
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`

```java
package com.hellocr.soporte;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Base de los tests de integración: contexto completo, base hellocr_test limpia y reloj fijo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PruebasConfig.class)
public abstract class PruebaIntegracion {

    private static final String TABLAS = "tokens_correo, refresh_tokens, usuarios";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RelojAjustable reloj;

    @BeforeEach
    void reiniciarEstado() {
        reloj.reiniciar();
        jdbc.execute("TRUNCATE " + TABLAS + " RESTART IDENTITY CASCADE");
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

Archivo: `backend/src/test/java/com/hellocr/EsquemaTest.java`

```java
package com.hellocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EsquemaTest extends PruebaIntegracion {

    @Test
    void losIdsDeUsuarioSonUuidVersion7() {
        UUID id = insertarUsuario("ana@correo.cr", "ana", "Ab3dEf7h");

        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void elCorreoDebeGuardarseNormalizado() {
        assertThatThrownBy(() -> insertarUsuario("Ana@Correo.cr", "ana", "Ab3dEf7h"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("usuarios_correo_normalizado");
    }

    @Test
    void elNombreDeUsuarioRespetaElFormato() {
        for (String invalido : List.of("Ana", "1ana", "an", "ana-maria", "_ana")) {
            assertThatThrownBy(() -> insertarUsuario("x@correo.cr", invalido, "Ab3dEf7h"))
                    .as(invalido)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("usuarios_nombre_usuario_formato");
        }
        assertThatCode(() -> insertarUsuario("x@correo.cr", "ana.maria_2", "Ab3dEf7h")).doesNotThrowAnyException();
    }

    @Test
    void elCodigoDeInvitacionNoAdmiteCaracteresAmbiguos() {
        assertThatThrownBy(() -> insertarUsuario("ana@correo.cr", "ana", "O0Il1abc"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("usuarios_codigo_invitacion_formato");
    }

    @Test
    void borrarUnUsuarioBorraSusTokens() {
        UUID id = insertarUsuario("ana@correo.cr", "ana", "Ab3dEf7h");
        jdbc.update("INSERT INTO refresh_tokens (usuario_id, token_hash, expira_en) VALUES (?, repeat('a', 64), now())", id);
        jdbc.update("""
                INSERT INTO tokens_correo (usuario_id, proposito, token_hash, expira_en)
                VALUES (?, 'VERIFICACION', repeat('b', 64), now())
                """, id);

        jdbc.update("DELETE FROM usuarios WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tokens_correo", Integer.class)).isZero();
    }

    private UUID insertarUsuario(String correo, String nombreUsuario, String codigo) {
        return jdbc.queryForObject("""
                INSERT INTO usuarios (correo, nombre_usuario, nombre_visible, hash_contrasena, codigo_invitacion)
                VALUES (?, ?, 'Ana', 'x', ?) RETURNING id
                """, UUID.class, correo, nombreUsuario, codigo);
    }
}
```

- [ ] **Step 3: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=EsquemaTest`
Expected: FAIL — `relation "usuarios" does not exist` (todavía no hay tablas).

- [ ] **Step 4: Migración**

Archivo: `backend/src/main/resources/db/migration/V1__identidad.sql`

```sql
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
```

- [ ] **Step 5: El test de arranque pasa a usar la base común**

Archivo: `backend/src/test/java/com/hellocr/HelloCrApplicationTests.java`

```java
package com.hellocr;

import com.hellocr.soporte.PruebaIntegracion;
import org.junit.jupiter.api.Test;

class HelloCrApplicationTests extends PruebaIntegracion {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores (6 tests). Flyway aplica `V1` sobre `hellocr_test` la primera vez.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): esquema de identidad con Flyway y base de tests de integración"
```

---

### Task 3: Formato uniforme de errores

**Files:**
- Create: `backend/src/main/java/com/hellocr/comun/CodigoError.java`, `backend/src/main/java/com/hellocr/comun/ErrorNegocio.java`, `backend/src/main/java/com/hellocr/comun/ApiErrorHandler.java`
- Test: `backend/src/test/java/com/hellocr/comun/ApiErrorHandlerTest.java`

**Interfaces:**
- Produces: `enum CodigoError` con los códigos de este plan y `HttpStatus estado()` (los planes 2 y 3 agregan los suyos). `class ErrorNegocio extends RuntimeException` con constructores `(CodigoError, String)` y `(CodigoError, String, Map<String, Object> extras)`, métodos `codigo()` y `extras()`, la constante `REINTENTAR_EN_SEGUNDOS` y la fábrica `static ErrorNegocio validacion(String campo, String mensaje)`. Los extras se agregan como propiedades del `ProblemDetail`; si hay `reintentarEnSegundos`, también va en el header `Retry-After`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/comun/ApiErrorHandlerTest.java`

```java
package com.hellocr.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class ApiErrorHandlerTest {

    private final ApiErrorHandler manejador = new ApiErrorHandler();

    @Test
    void errorDeNegocioUsaElEstadoDeSuCodigoYAgregaLosExtras() {
        ErrorNegocio error = new ErrorNegocio(CodigoError.NOMBRE_USUARIO_EN_USO, "Ese nombre de usuario ya está en uso.",
                Map.of("sugerencia", "ana_2"));

        ResponseEntity<ProblemDetail> respuesta = manejador.negocio(error);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail cuerpo = respuesta.getBody();
        assertThat(cuerpo.getDetail()).isEqualTo("Ese nombre de usuario ya está en uso.");
        assertThat(cuerpo.getProperties())
                .containsEntry("codigo", "NOMBRE_USUARIO_EN_USO")
                .containsEntry("sugerencia", "ana_2");
    }

    @Test
    void unTokenInvalidoEs422() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.negocio(new ErrorNegocio(CodigoError.TOKEN_INVALIDO, "El enlace venció."));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void demasiadosIntentosEs429ConRetryAfter() {
        ErrorNegocio error = new ErrorNegocio(CodigoError.DEMASIADOS_INTENTOS, "Probá más tarde.",
                Map.of(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, 900L));

        ResponseEntity<ProblemDetail> respuesta = manejador.negocio(error);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
        assertThat(respuesta.getBody().getProperties()).containsEntry("reintentarEnSegundos", 900L);
    }

    @Test
    void validacionDeUnCampoTieneElMismoFormatoQueBeanValidation() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.negocio(ErrorNegocio.validacion("contrasena", "La contraseña es demasiado larga."));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody().getProperties())
                .containsEntry("codigo", "VALIDACION")
                .containsEntry("errores", Map.of("contrasena", "La contraseña es demasiado larga."));
    }

    @Test
    void accesoDenegadoEs403SinPermiso() {
        ResponseEntity<ProblemDetail> respuesta = manejador.sinPermiso(new AccessDeniedException("no"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(respuesta.getBody().getProperties()).containsEntry("codigo", "SIN_PERMISO");
    }

    @Test
    void erroresDeSpringConservanSuEstadoYRecibenUnCodigo() {
        ResponseEntity<ProblemDetail> noEncontrado =
                manejador.inesperado(new ErrorResponseException(HttpStatus.NOT_FOUND));
        ResponseEntity<ProblemDetail> metodo =
                manejador.inesperado(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(noEncontrado.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noEncontrado.getBody().getProperties()).containsEntry("codigo", "NO_ENCONTRADO");
        assertThat(metodo.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(metodo.getBody().getProperties()).containsEntry("codigo", "VALIDACION");
    }

    @Test
    void errorInesperadoEs500ySinDetallesInternos() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.inesperado(new IllegalStateException("password de la base: 1234"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(respuesta.getBody().getProperties()).containsEntry("codigo", "ERROR_INTERNO");
        assertThat(respuesta.getBody().getDetail())
                .isEqualTo("Ocurrió un error inesperado. Intentá de nuevo.")
                .doesNotContain("1234");
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=ApiErrorHandlerTest`
Expected: FAIL de compilación — `cannot find symbol: class ApiErrorHandler`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/comun/CodigoError.java`

```java
package com.hellocr.comun;

import org.springframework.http.HttpStatus;

/** Catálogo de códigos de error de la API (spec, sección 11). Los planes 2 y 3 agregan los suyos. */
public enum CodigoError {
    VALIDACION(HttpStatus.BAD_REQUEST),
    NO_AUTENTICADO(HttpStatus.UNAUTHORIZED),
    CREDENCIALES_INVALIDAS(HttpStatus.UNAUTHORIZED),
    SIN_PERMISO(HttpStatus.FORBIDDEN),
    CORREO_NO_VERIFICADO(HttpStatus.FORBIDDEN),
    NO_ENCONTRADO(HttpStatus.NOT_FOUND),
    CORREO_EN_USO(HttpStatus.CONFLICT),
    NOMBRE_USUARIO_EN_USO(HttpStatus.CONFLICT),
    TOKEN_INVALIDO(HttpStatus.UNPROCESSABLE_CONTENT),
    NOMBRE_USUARIO_RESERVADO(HttpStatus.UNPROCESSABLE_CONTENT),
    DEMASIADOS_INTENTOS(HttpStatus.TOO_MANY_REQUESTS),
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

Archivo: `backend/src/main/java/com/hellocr/comun/ErrorNegocio.java`

```java
package com.hellocr.comun;

import java.util.Map;

/** Error esperado de la aplicación: se convierte en una respuesta ProblemDetail con su código. */
public class ErrorNegocio extends RuntimeException {

    /** Extra con los segundos que faltan para reintentar; ApiErrorHandler lo copia al header Retry-After. */
    public static final String REINTENTAR_EN_SEGUNDOS = "reintentarEnSegundos";

    private final CodigoError codigo;
    private final Map<String, Object> extras;

    public ErrorNegocio(CodigoError codigo, String mensaje) {
        this(codigo, mensaje, Map.of());
    }

    public ErrorNegocio(CodigoError codigo, String mensaje, Map<String, Object> extras) {
        super(mensaje);
        this.codigo = codigo;
        this.extras = Map.copyOf(extras);
    }

    /** Error de un solo campo, con el mismo formato que las validaciones de Bean Validation. */
    public static ErrorNegocio validacion(String campo, String mensaje) {
        return new ErrorNegocio(CodigoError.VALIDACION, "Hay datos inválidos.",
                Map.of("errores", Map.of(campo, mensaje)));
    }

    public CodigoError codigo() {
        return codigo;
    }

    public Map<String, Object> extras() {
        return extras;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/comun/ApiErrorHandler.java`

```java
package com.hellocr.comun;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    public static ProblemDetail problema(CodigoError codigo, String detalle) {
        return problema(codigo.estado(), codigo, detalle);
    }

    private static ProblemDetail problema(HttpStatusCode estado, CodigoError codigo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setProperty("codigo", codigo.name());
        return problema;
    }

    @ExceptionHandler(ErrorNegocio.class)
    public ResponseEntity<ProblemDetail> negocio(ErrorNegocio error) {
        ProblemDetail problema = problema(error.codigo(), error.getMessage());
        error.extras().forEach(problema::setProperty);
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(problema.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (error.extras().get(ErrorNegocio.REINTENTAR_EN_SEGUNDOS) instanceof Number segundos) {
            respuesta.header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos.longValue()));
        }
        return respuesta.body(problema);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validacion(MethodArgumentNotValidException error) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (FieldError campo : error.getBindingResult().getFieldErrors()) {
            errores.putIfAbsent(campo.getField(), campo.getDefaultMessage());
        }
        ProblemDetail problema = problema(CodigoError.VALIDACION, "Hay datos inválidos.");
        problema.setProperty("errores", errores);
        return responder(problema);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<ProblemDetail> solicitudIlegible(Exception error) {
        return responder(problema(CodigoError.VALIDACION, "La solicitud no tiene un formato válido."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> sinPermiso(AccessDeniedException error) {
        return responder(problema(CodigoError.SIN_PERMISO, "No tenés permiso para esta acción."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception error) {
        if (error instanceof ErrorResponse respuesta && respuesta.getStatusCode().is4xxClientError()) {
            HttpStatusCode estado = respuesta.getStatusCode();
            return switch (estado.value()) {
                case 401 -> responder(problema(estado, CodigoError.NO_AUTENTICADO, "Tenés que iniciar sesión."));
                case 403 -> responder(problema(estado, CodigoError.SIN_PERMISO, "No tenés permiso para esta acción."));
                case 404 -> responder(problema(estado, CodigoError.NO_ENCONTRADO, "El recurso no existe."));
                default -> responder(problema(estado, CodigoError.VALIDACION, "La solicitud no es válida."));
            };
        }
        log.error("Error inesperado", error);
        return responder(problema(CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado. Intentá de nuevo."));
    }

    private static ResponseEntity<ProblemDetail> responder(ProblemDetail problema) {
        return ResponseEntity.status(problema.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores. En la salida aparece un `ERROR ... Error inesperado` con un stack trace: es el log esperado del test `errorInesperadoEs500ySinDetallesInternos`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): errores ProblemDetail con código del catálogo y Retry-After"
```

---
### Task 4: Modelo de usuarios

**Files:**
- Create en `backend/src/main/java/com/hellocr/usuarios/`: `Usuario.java`, `UsuarioRepository.java`, `NombresReservados.java`, `GeneradorCodigos.java`
- Test: `backend/src/test/java/com/hellocr/usuarios/UsuarioTest.java`, `backend/src/test/java/com/hellocr/usuarios/GeneradorCodigosTest.java`, `backend/src/test/java/com/hellocr/usuarios/UsuarioRepositoryTest.java`
- Modify: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`

**Interfaces:**
- Produces: entidad `Usuario(correo, nombreUsuario, nombreVisible, hashContrasena, codigoInvitacion, Instant creadoEn)` con `UUID getId()`, `static normalizarCorreo(String)`, `static normalizarNombreUsuario(String)`, las constantes `FORMATO_NOMBRE_USUARIO` y `MENSAJE_FORMATO_NOMBRE_USUARIO`, `correoVerificado()`, `verificarCorreo(Instant)` (conserva la primera fecha), `cambiarContrasena(String)`, `cambiarNombreVisible(String)`, `cambiarInfo(String)`, `cambiarNombreUsuario(String)`, `cambiarCodigoInvitacion(String)` y getters.
- Produces: `UsuarioRepository` (`JpaRepository<Usuario, UUID>`) con `findByCorreo`, `findByNombreUsuario`, `findByCodigoInvitacion`, `existsByCorreo`, `existsByNombreUsuario`, `existsByCodigoInvitacion`, `verificadoPorId(UUID)`, `verificadoPorNombreUsuario(String)`, `verificadoPorCodigo(String)`, `borrarSinVerificarPorCorreo(String): int` y `borrarSinVerificarCreadosAntesDe(Instant): int` (estos dos necesitan una transacción abierta).
- Produces: `NombresReservados.contiene(String nombreNormalizado)`; `GeneradorCodigos.codigoInvitacion()` (8 caracteres sin ambiguos, que no existan en la base).
- Produces (tests): en `PruebaIntegracion`, `CLAVE`, `crearUsuario(String nombreUsuario)` (verificado, correo `<nombreUsuario>@correo.cr`, nombre visible `Usuario <nombreUsuario>`) y `crearUsuarioSinVerificar(String nombreUsuario)`.

- [ ] **Step 1: Tests**

Archivo: `backend/src/test/java/com/hellocr/usuarios/UsuarioTest.java`

```java
package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UsuarioTest {

    private static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");

    @Test
    void normalizaElCorreo() {
        assertThat(Usuario.normalizarCorreo("  Ana@Correo.CR ")).isEqualTo("ana@correo.cr");
    }

    @Test
    void normalizaElNombreDeUsuarioQuitandoLaArroba() {
        assertThat(Usuario.normalizarNombreUsuario(" @Marco_Rojas ")).isEqualTo("marco_rojas");
        assertThat(Usuario.normalizarNombreUsuario("ana")).isEqualTo("ana");
    }

    @Test
    void elConstructorNormalizaCorreoYNombreDeUsuario() {
        Usuario usuario = new Usuario(" Ana@Correo.CR", "@Ana", "Ana", "hash", "Ab3dEf7h", T0);

        assertThat(usuario.getCorreo()).isEqualTo("ana@correo.cr");
        assertThat(usuario.getNombreUsuario()).isEqualTo("ana");
    }

    @Test
    void laVerificacionConservaLaPrimeraFecha() {
        Usuario usuario = new Usuario("ana@correo.cr", "ana", "Ana", "hash", "Ab3dEf7h", T0);
        assertThat(usuario.correoVerificado()).isFalse();

        usuario.verificarCorreo(T0.plusSeconds(60));
        usuario.verificarCorreo(T0.plusSeconds(120));

        assertThat(usuario.correoVerificado()).isTrue();
        assertThat(usuario.getCorreoVerificadoEn()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void reconoceLosNombresReservados() {
        assertThat(NombresReservados.contiene("admin")).isTrue();
        assertThat(NombresReservados.contiene("soporte")).isTrue();
        assertThat(NombresReservados.contiene("ana")).isFalse();
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/usuarios/GeneradorCodigosTest.java`

```java
package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class GeneradorCodigosTest {

    @Test
    void generaOchoCaracteresSinCaracteresAmbiguos() {
        GeneradorCodigos generador = new GeneradorCodigos(mock(UsuarioRepository.class));

        for (int i = 0; i < 1000; i++) {
            assertThat(generador.codigoInvitacion()).matches("[2-9A-HJKMNP-Za-kmnp-z]{8}");
        }
    }

    @Test
    void siElCodigoYaExisteGeneraOtro() {
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        when(usuarios.existsByCodigoInvitacion(anyString())).thenReturn(true, false);

        new GeneradorCodigos(usuarios).codigoInvitacion();

        verify(usuarios, times(2)).existsByCodigoInvitacion(anyString());
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/usuarios/UsuarioRepositoryTest.java`

```java
package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class UsuarioRepositoryTest extends PruebaIntegracion {

    @Autowired
    private UsuarioRepository usuarios;

    @Test
    void alGuardarGeneraUnUuidVersion7() {
        Usuario ana = crearUsuario("ana");

        assertThat(ana.getId().version()).isEqualTo(7);
    }

    @Test
    void buscaPorCorreoNombreYCodigo() {
        Usuario ana = crearUsuario("ana");

        assertThat(usuarios.findByCorreo("ana@correo.cr")).isPresent();
        assertThat(usuarios.findByNombreUsuario("ana")).isPresent();
        assertThat(usuarios.findByCodigoInvitacion(ana.getCodigoInvitacion())).isPresent();
    }

    @Test
    void lasBusquedasDePerfilesSoloVenCuentasVerificadas() {
        Usuario ana = crearUsuario("ana");
        Usuario luis = crearUsuarioSinVerificar("luis");

        assertThat(usuarios.verificadoPorId(ana.getId())).isPresent();
        assertThat(usuarios.verificadoPorId(luis.getId())).isEmpty();
        assertThat(usuarios.verificadoPorNombreUsuario("luis")).isEmpty();
        assertThat(usuarios.verificadoPorCodigo(luis.getCodigoInvitacion())).isEmpty();
    }

    @Test
    @Transactional
    void borrarSinVerificarPorCorreoNoTocaCuentasVerificadas() {
        crearUsuario("ana");
        crearUsuarioSinVerificar("luis");

        assertThat(usuarios.borrarSinVerificarPorCorreo("ana@correo.cr")).isZero();
        assertThat(usuarios.borrarSinVerificarPorCorreo("luis@correo.cr")).isEqualTo(1);
    }

    @Test
    @Transactional
    void borraLasCuentasSinVerificarCreadasAntesDeUnaFecha() {
        crearUsuarioSinVerificar("vieja");
        reloj.avanzar(Duration.ofDays(3));
        crearUsuarioSinVerificar("nueva");
        crearUsuario("ana");

        assertThat(usuarios.borrarSinVerificarCreadosAntesDe(reloj.instant().minus(Duration.ofDays(1)))).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Ayudantes de tests para crear usuarios**

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`

```java
package com.hellocr.soporte;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Base de los tests de integración: contexto completo, base hellocr_test limpia y reloj fijo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PruebasConfig.class)
public abstract class PruebaIntegracion {

    public static final String CLAVE = "clave12345";

    private static final String TABLAS = "tokens_correo, refresh_tokens, usuarios";
    private static final PasswordEncoder CODIFICADOR = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RelojAjustable reloj;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private GeneradorCodigos codigos;

    @BeforeEach
    void reiniciarEstado() {
        reloj.reiniciar();
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

- [ ] **Step 3: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='UsuarioTest,GeneradorCodigosTest,UsuarioRepositoryTest'`
Expected: FAIL de compilación — `cannot find symbol: class Usuario`.

- [ ] **Step 4: Entidad, repositorio y utilidades**

Archivo: `backend/src/main/java/com/hellocr/usuarios/Usuario.java`

```java
package com.hellocr.usuarios;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "usuarios")
public class Usuario {

    /** Mismo formato que la restricción usuarios_nombre_usuario_formato. */
    public static final String FORMATO_NOMBRE_USUARIO = "^[a-z][a-z0-9_.]{2,19}$";
    public static final String MENSAJE_FORMATO_NOMBRE_USUARIO = "El nombre de usuario debe empezar con una letra y "
            + "tener de 3 a 20 caracteres: letras, números, punto o guion bajo.";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String correo;

    @Column(name = "nombre_usuario", nullable = false, unique = true, length = 20)
    private String nombreUsuario;

    @Column(name = "nombre_visible", nullable = false, length = 50)
    private String nombreVisible;

    @Column(name = "hash_contrasena", nullable = false, length = 100)
    private String hashContrasena;

    @Column(length = 140)
    private String info;

    @Column(name = "codigo_invitacion", nullable = false, unique = true, length = 8)
    private String codigoInvitacion;

    @Column(name = "correo_verificado_en")
    private Instant correoVerificadoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected Usuario() {
    }

    public Usuario(String correo, String nombreUsuario, String nombreVisible, String hashContrasena,
            String codigoInvitacion, Instant creadoEn) {
        this.correo = normalizarCorreo(correo);
        this.nombreUsuario = normalizarNombreUsuario(nombreUsuario);
        this.nombreVisible = nombreVisible;
        this.hashContrasena = hashContrasena;
        this.codigoInvitacion = codigoInvitacion;
        this.creadoEn = creadoEn;
    }

    /** Sin espacios alrededor y en minúsculas, como lo exige la base. */
    public static String normalizarCorreo(String correo) {
        return correo.trim().toLowerCase(Locale.ROOT);
    }

    /** Sin espacios, sin la @ inicial que la gente suele escribir y en minúsculas. */
    public static String normalizarNombreUsuario(String nombreUsuario) {
        String limpio = nombreUsuario.trim();
        if (limpio.startsWith("@")) {
            limpio = limpio.substring(1);
        }
        return limpio.toLowerCase(Locale.ROOT);
    }

    public boolean correoVerificado() {
        return correoVerificadoEn != null;
    }

    /** Marca el correo como verificado; si ya lo estaba, conserva la fecha original. */
    public void verificarCorreo(Instant ahora) {
        if (correoVerificadoEn == null) {
            correoVerificadoEn = ahora;
        }
    }

    public void cambiarContrasena(String hashContrasena) {
        this.hashContrasena = hashContrasena;
    }

    public void cambiarNombreVisible(String nombreVisible) {
        this.nombreVisible = nombreVisible;
    }

    public void cambiarInfo(String info) {
        this.info = info;
    }

    public void cambiarNombreUsuario(String nombreUsuario) {
        this.nombreUsuario = normalizarNombreUsuario(nombreUsuario);
    }

    public void cambiarCodigoInvitacion(String codigoInvitacion) {
        this.codigoInvitacion = codigoInvitacion;
    }

    public UUID getId() {
        return id;
    }

    public String getCorreo() {
        return correo;
    }

    public String getNombreUsuario() {
        return nombreUsuario;
    }

    public String getNombreVisible() {
        return nombreVisible;
    }

    public String getHashContrasena() {
        return hashContrasena;
    }

    public String getInfo() {
        return info;
    }

    public String getCodigoInvitacion() {
        return codigoInvitacion;
    }

    public Instant getCorreoVerificadoEn() {
        return correoVerificadoEn;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/UsuarioRepository.java`

```java
package com.hellocr.usuarios;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByCorreo(String correo);

    Optional<Usuario> findByNombreUsuario(String nombreUsuario);

    Optional<Usuario> findByCodigoInvitacion(String codigoInvitacion);

    boolean existsByCorreo(String correo);

    boolean existsByNombreUsuario(String nombreUsuario);

    boolean existsByCodigoInvitacion(String codigoInvitacion);

    @Query("select u from Usuario u where u.id = :id and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorId(UUID id);

    @Query("select u from Usuario u where u.nombreUsuario = :nombreUsuario and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorNombreUsuario(String nombreUsuario);

    @Query("select u from Usuario u where u.codigoInvitacion = :codigo and u.correoVerificadoEn is not null")
    Optional<Usuario> verificadoPorCodigo(String codigo);

    /** Borra la cuenta sin verificar que tenga ese correo (la base borra sus tokens en cascada). */
    @Modifying
    @Query("delete from Usuario u where u.correo = :correo and u.correoVerificadoEn is null")
    int borrarSinVerificarPorCorreo(String correo);

    @Modifying
    @Query("delete from Usuario u where u.correoVerificadoEn is null and u.creadoEn < :limite")
    int borrarSinVerificarCreadosAntesDe(Instant limite);
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/NombresReservados.java`

```java
package com.hellocr.usuarios;

import java.util.Set;

/** Nombres de usuario que nadie puede tomar (spec, sección 4). */
public final class NombresReservados {

    private static final Set<String> RESERVADOS =
            Set.of("admin", "administrador", "hellocr", "soporte", "sistema", "root");

    private NombresReservados() {
    }

    /** Recibe el nombre ya normalizado con Usuario.normalizarNombreUsuario. */
    public static boolean contiene(String nombreUsuario) {
        return RESERVADOS.contains(nombreUsuario);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/GeneradorCodigos.java`

```java
package com.hellocr.usuarios;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class GeneradorCodigos {

    /** Sin 0, O, 1, I, l ni o, para que nadie los confunda al dictar o copiar un código. */
    static final String ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    static final int LARGO = 8;

    private final SecureRandom aleatorio = new SecureRandom();
    private final UsuarioRepository usuarios;

    public GeneradorCodigos(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Código de invitación nuevo que ningún usuario tiene todavía. */
    public String codigoInvitacion() {
        String codigo;
        do {
            codigo = generar();
        } while (usuarios.existsByCodigoInvitacion(codigo));
        return codigo;
    }

    private String generar() {
        StringBuilder codigo = new StringBuilder(LARGO);
        for (int i = 0; i < LARGO; i++) {
            codigo.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
        }
        return codigo.toString();
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores. Si Hibernate falla al arrancar con `Schema-validation: ...`, alguna columna de la entidad no coincide con `V1__identidad.sql`.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): modelo de usuarios con UUID v7 y códigos de invitación"
```

---

### Task 5: JWT y seguridad

**Files:**
- Create: `backend/src/main/java/com/hellocr/config/JwtProperties.java`, `backend/src/main/java/com/hellocr/config/SeguridadErrorHandler.java`, `backend/src/main/java/com/hellocr/config/SecurityConfig.java`
- Create: `backend/src/main/java/com/hellocr/auth/JwtService.java`, `backend/src/main/java/com/hellocr/comun/UsuarioAutenticado.java`
- Test: `backend/src/test/java/com/hellocr/config/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `Usuario` (Task 4), `CodigoError` (Task 3), `Clock` (Task 1).
- Produces: `JwtService.EMISOR` (`"hellocr"`) y `JwtService.emitir(Usuario): String`; `JwtProperties(secreto, duracionAccess, duracionRefresh, cookieSegura)`; beans `JwtEncoder`, `JwtDecoder` (vigencia validada con el `Clock`; lo reutiliza el plan 2 para el WebSocket) y `PasswordEncoder`; `UsuarioAutenticado.id(Jwt): UUID`.
- Produces: rutas públicas — todo `POST /api/auth/**`, que además ignora el header `Authorization`; el resto exige token. 401 → `NO_AUTENTICADO`; 403 → `SIN_PERMISO`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/config/SecurityConfigTest.java`

```java
package com.hellocr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.auth.JwtService;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest extends PruebaIntegracion {

    @Autowired
    private JwtService jwtService;
    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void elTokenLlevaElIdYElNombreDeUsuarioYVenceEn15Minutos() {
        Usuario ana = crearUsuario("ana");

        Jwt jwt = jwtDecoder.decode(jwtService.emitir(ana));

        assertThat(jwt.getSubject()).isEqualTo(ana.getId().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("hellocr");
        assertThat(jwt.getClaimAsString("nombreUsuario")).isEqualTo("ana");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void sinTokenUnaRutaProtegidaResponde401ConFormatoDeError() throws Exception {
        mvc.perform(get("/api/usuarios/yo"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.detail").value("Tenés que iniciar sesión."));
    }

    @Test
    void conTokenValidoLaSolicitudLlegaAlControlador() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void unTokenVencidoResponde401() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));
        reloj.avanzar(Duration.ofMinutes(15).plusSeconds(1));

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    void unTokenAlteradoResponde401() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));
        String alterado = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + alterado))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=SecurityConfigTest`
Expected: FAIL de compilación — `cannot find symbol: class JwtService`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/config/JwtProperties.java`

```java
package com.hellocr.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.jwt")
@Validated
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "app.jwt.secreto debe tener al menos 32 caracteres") String secreto,
        @NotNull Duration duracionAccess,
        @NotNull Duration duracionRefresh,
        boolean cookieSegura) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/JwtService.java`

```java
package com.hellocr.auth;

import com.hellocr.config.JwtProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String EMISOR = "hellocr";

    private final JwtEncoder encoder;
    private final JwtProperties propiedades;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties propiedades, Clock clock) {
        this.encoder = encoder;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    public String emitir(Usuario usuario) {
        Instant ahora = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(EMISOR)
                .subject(usuario.getId().toString())
                .issuedAt(ahora)
                .expiresAt(ahora.plus(propiedades.duracionAccess()))
                .claim("nombreUsuario", usuario.getNombreUsuario())
                .build();
        JwsHeader cabecera = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(cabecera, claims)).getTokenValue();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/comun/UsuarioAutenticado.java`

```java
package com.hellocr.comun;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** El sujeto del access token es el id del usuario. */
public final class UsuarioAutenticado {

    private UsuarioAutenticado() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/config/SeguridadErrorHandler.java`

```java
package com.hellocr.config;

import com.hellocr.comun.CodigoError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Respuestas 401/403 generadas por los filtros de seguridad, con el mismo formato que ApiErrorHandler. */
@Component
public class SeguridadErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public SeguridadErrorHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AuthenticationException error) throws IOException {
        escribir(respuesta, CodigoError.NO_AUTENTICADO, "Tenés que iniciar sesión.");
    }

    @Override
    public void handle(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AccessDeniedException error) throws IOException {
        escribir(respuesta, CodigoError.SIN_PERMISO, "No tenés permiso para esta acción.");
    }

    private void escribir(HttpServletResponse respuesta, CodigoError codigo, String detalle) throws IOException {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", "about:blank");
        cuerpo.put("title", codigo.estado().getReasonPhrase());
        cuerpo.put("status", codigo.estado().value());
        cuerpo.put("detail", detalle);
        cuerpo.put("codigo", codigo.name());
        respuesta.setStatus(codigo.estado().value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8);
        json.writeValue(respuesta.getOutputStream(), cuerpo);
    }
}
```

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

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): JWT con resource server y respuestas 401/403 uniformes"
```

---

### Task 6: Refresh tokens rotativos

**Files:**
- Create: `backend/src/main/java/com/hellocr/comun/TokensSeguros.java`
- Create: `backend/src/main/java/com/hellocr/auth/RefreshToken.java`, `backend/src/main/java/com/hellocr/auth/RefreshTokenRepository.java`, `backend/src/main/java/com/hellocr/auth/RefreshTokenService.java`, `backend/src/main/java/com/hellocr/auth/SesionesRevocadas.java`
- Test: `backend/src/test/java/com/hellocr/comun/TokensSegurosTest.java`, `backend/src/test/java/com/hellocr/auth/RefreshTokenServiceTest.java`

**Interfaces:**
- Consumes: `Usuario` (Task 4), `ErrorNegocio`/`CodigoError` (Task 3), `JwtProperties` (Task 5), `Clock`.
- Produces: `TokensSeguros.generar(): String` (32 bytes en base64url, 43 caracteres) y `TokensSeguros.hash(String): String` (SHA-256 en hexadecimal, 64 caracteres); los reutiliza la Task 8.
- Produces: `RefreshTokenService.emitir(Usuario): String` (valor en claro para la cookie), `rotar(String): Rotacion` con `record Rotacion(Usuario usuario, String nuevoToken)`, `revocar(String)`, `revocarTodos(UUID usuarioId)` y `limpiarVencidos(): int` (a diario a las 4:00). Cualquier token inválido, vencido o reutilizado lanza `ErrorNegocio(NO_AUTENTICADO, "La sesión expiró. Iniciá sesión de nuevo.")`.
- Produces: evento `record SesionesRevocadas(UUID usuarioId)`, publicado por `revocarTodos` (también cuando se detecta una reutilización).

- [ ] **Step 1: Tests**

Archivo: `backend/src/test/java/com/hellocr/comun/TokensSegurosTest.java`

```java
package com.hellocr.comun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokensSegurosTest {

    @Test
    void generaTokensDistintosSeguroParaUrls() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = TokensSeguros.generar();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            vistos.add(token);
        }
        assertThat(vistos).hasSize(100);
    }

    @Test
    void elHashEsSha256EnHexadecimal() {
        assertThat(TokensSeguros.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/auth/RefreshTokenServiceTest.java`

```java
package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class RefreshTokenServiceTest extends PruebaIntegracion {

    @Autowired
    private RefreshTokenService servicio;
    @Autowired
    private ApplicationEvents eventos;

    private Usuario ana;

    @BeforeEach
    void crearAna() {
        ana = crearUsuario("ana");
    }

    @Test
    void rotarDevuelveElUsuarioYUnTokenNuevo() {
        String original = servicio.emitir(ana);

        RefreshTokenService.Rotacion rotacion = servicio.rotar(original);

        assertThat(rotacion.usuario().getId()).isEqualTo(ana.getId());
        assertThat(rotacion.nuevoToken()).isNotBlank().isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens ORDER BY id LIMIT 1", String.class))
                .as("en la base solo se guarda el hash")
                .isNotEqualTo(original)
                .hasSize(64);
    }

    @Test
    void cadaRotacionExtiendeLaSesion30Dias() {
        String token = servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(29));
        String renovado = servicio.rotar(token).nuevoToken();
        reloj.avanzar(Duration.ofDays(29));

        assertThat(servicio.rotar(renovado).nuevoToken()).isNotBlank();
    }

    @Test
    void unTokenVencidoNoSirve() {
        String token = servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(30));

        assertThatThrownBy(() -> servicio.rotar(token)).satisfies(this::esSesionInvalida);
    }

    @Test
    void reusarUnTokenRotadoDespuesDeLaGraciaCierraTodasLasSesiones() {
        String sesionA = servicio.emitir(ana);
        String sesionB = servicio.emitir(ana);
        String sesionA2 = servicio.rotar(sesionA).nuevoToken();
        reloj.avanzar(Duration.ofSeconds(31));

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);

        assertThatThrownBy(() -> servicio.rotar(sesionA2)).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(sesionB)).satisfies(this::esSesionInvalida);
        assertThat(eventos.stream(SesionesRevocadas.class)).containsExactly(new SesionesRevocadas(ana.getId()));
    }

    @Test
    void reusarDentroDeLaGraciaSoloRechazaEseIntento() {
        String sesionA = servicio.emitir(ana);
        String sesionA2 = servicio.rotar(sesionA).nuevoToken();
        reloj.avanzar(Duration.ofSeconds(5));

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);

        assertThat(servicio.rotar(sesionA2).nuevoToken()).isNotBlank();
        assertThat(eventos.stream(SesionesRevocadas.class)).isEmpty();
    }

    @Test
    void unTokenDesconocidoOVacioNoSirve() {
        assertThatThrownBy(() -> servicio.rotar("inventado")).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(null)).satisfies(this::esSesionInvalida);
    }

    @Test
    void unTokenRevocadoPorLogoutNoSirve() {
        String token = servicio.emitir(ana);

        servicio.revocar(token);

        assertThatThrownBy(() -> servicio.rotar(token)).satisfies(this::esSesionInvalida);
    }

    @Test
    void revocarTodosCierraTodasLasSesionesYAvisa() {
        String sesionA = servicio.emitir(ana);
        String sesionB = servicio.emitir(ana);

        servicio.revocarTodos(ana.getId());

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(sesionB)).satisfies(this::esSesionInvalida);
        assertThat(eventos.stream(SesionesRevocadas.class)).contains(new SesionesRevocadas(ana.getId()));
    }

    @Test
    void laLimpiezaBorraSoloLosVencidos() {
        servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(29));
        servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(2));

        assertThat(servicio.limpiarVencidos()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    private void esSesionInvalida(Throwable error) {
        assertThat(error).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) error).codigo()).isEqualTo(CodigoError.NO_AUTENTICADO);
    }
}
```

- [ ] **Step 2: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='TokensSegurosTest,RefreshTokenServiceTest'`
Expected: FAIL de compilación — `cannot find symbol: class TokensSeguros`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/comun/TokensSeguros.java`

```java
package com.hellocr.comun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Tokens opacos (refresh y enlaces de correo): el valor viaja en claro, la base solo guarda su hash. */
public final class TokensSeguros {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private TokensSeguros() {
    }

    /** 32 bytes aleatorios en base64url sin relleno (43 caracteres, seguros en URLs y cookies). */
    public static String generar() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 en hexadecimal (64 caracteres). */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/SesionesRevocadas.java`

```java
package com.hellocr.auth;

import java.util.UUID;

/** Se revocaron todas las sesiones del usuario. El plan 2 lo escucha para cerrar sus WebSockets. */
public record SesionesRevocadas(UUID usuarioId) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RefreshToken.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(name = "revocado_en")
    private Instant revocadoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected RefreshToken() {
    }

    public RefreshToken(Usuario usuario, String tokenHash, Instant expiraEn, Instant creadoEn) {
        this.usuario = usuario;
        this.tokenHash = tokenHash;
        this.expiraEn = expiraEn;
        this.creadoEn = creadoEn;
    }

    public boolean revocado() {
        return revocadoEn != null;
    }

    public boolean vencido(Instant ahora) {
        return !expiraEn.isAfter(ahora);
    }

    public void revocar(Instant ahora) {
        if (revocadoEn == null) {
            revocadoEn = ahora;
        }
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Instant getRevocadoEn() {
        return revocadoEn;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RefreshTokenRepository.java`

```java
package com.hellocr.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revocadoEn = :ahora where t.usuario.id = :usuarioId and t.revocadoEn is null")
    int revocarTodosDe(UUID usuarioId, Instant ahora);

    @Modifying
    @Query("delete from RefreshToken t where t.expiraEn < :ahora")
    int borrarVencidos(Instant ahora);
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RefreshTokenService.java`

```java
package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.TokensSeguros;
import com.hellocr.config.JwtProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    /**
     * Si un token revocado se reusa dentro de este margen, se asume que dos pestañas refrescaron a la vez
     * y solo se rechaza ese request. Pasado el margen, se trata como robo y se cierran todas las sesiones.
     */
    static final Duration GRACIA_REUTILIZACION = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final JwtProperties propiedades;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties propiedades,
            ApplicationEventPublisher eventos, Clock clock) {
        this.tokens = tokens;
        this.propiedades = propiedades;
        this.eventos = eventos;
        this.clock = clock;
    }

    public record Rotacion(Usuario usuario, String nuevoToken) {
    }

    /** Crea un refresh token que vence en 30 días y devuelve su valor en claro (en la base queda el hash). */
    @Transactional
    public String emitir(Usuario usuario) {
        String token = TokensSeguros.generar();
        Instant ahora = clock.instant();
        tokens.save(new RefreshToken(usuario, TokensSeguros.hash(token), ahora.plus(propiedades.duracionRefresh()), ahora));
        return token;
    }

    /** Revoca el token recibido y emite uno nuevo. noRollbackFor: la revocación masiva debe persistir. */
    @Transactional(noRollbackFor = ErrorNegocio.class)
    public Rotacion rotar(String token) {
        Instant ahora = clock.instant();
        RefreshToken actual = buscar(token).orElseThrow(RefreshTokenService::sesionInvalida);
        if (actual.revocado()) {
            if (actual.getRevocadoEn().plus(GRACIA_REUTILIZACION).isBefore(ahora)) {
                revocarTodos(actual.getUsuario().getId());
                log.warn("Reutilización de refresh token: se cerraron las sesiones del usuario {}",
                        actual.getUsuario().getId());
            }
            throw sesionInvalida();
        }
        if (actual.vencido(ahora)) {
            throw sesionInvalida();
        }
        actual.revocar(ahora);
        return new Rotacion(actual.getUsuario(), emitir(actual.getUsuario()));
    }

    @Transactional
    public void revocar(String token) {
        buscar(token).ifPresent(encontrado -> encontrado.revocar(clock.instant()));
    }

    /** Cierra todas las sesiones del usuario y avisa con SesionesRevocadas. */
    @Transactional
    public void revocarTodos(UUID usuarioId) {
        tokens.revocarTodosDe(usuarioId, clock.instant());
        eventos.publishEvent(new SesionesRevocadas(usuarioId));
    }

    /** Todos los días a las 4:00. Los revocados se conservan hasta vencer para detectar reutilizaciones. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int limpiarVencidos() {
        return tokens.borrarVencidos(clock.instant());
    }

    private Optional<RefreshToken> buscar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(TokensSeguros.hash(token));
    }

    private static ErrorNegocio sesionInvalida() {
        return new ErrorNegocio(CodigoError.NO_AUTENTICADO, "La sesión expiró. Iniciá sesión de nuevo.");
    }
}
```

`rotar` usa `noRollbackFor = ErrorNegocio.class`: sin eso, la revocación de todas las sesiones se desharía al lanzar el error. El test `reusarUnTokenRotadoDespuesDeLaGraciaCierraTodasLasSesiones` lo comprueba.

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): refresh tokens hasheados, sesión deslizante de 30 días y detección de reutilización"
```

---
### Task 7: Envío de correos

**Files:**
- Create en `backend/src/main/java/com/hellocr/correo/`: `CorreoSaliente.java`, `EnviadorCorreo.java`, `EnviadorCorreoConsola.java`, `EnviadorCorreoSmtp.java`, `CorreoProperties.java`, `PlantillasCorreo.java`, `CorreoPendiente.java`, `CorreosDeCuenta.java`, `DespachadorCorreo.java`
- Create: `backend/src/test/java/com/hellocr/soporte/BuzonPrueba.java`
- Modify: `backend/src/test/java/com/hellocr/soporte/PruebasConfig.java`, `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`
- Test: `backend/src/test/java/com/hellocr/correo/PlantillasCorreoTest.java`, `backend/src/test/java/com/hellocr/correo/EnviadorCorreoSmtpTest.java`, `backend/src/test/java/com/hellocr/correo/DespachadorCorreoTest.java`

**Interfaces:**
- Consumes: `Usuario` (Task 4).
- Produces: `record CorreoSaliente(String destinatario, String asunto, String texto, String html)`; interfaz `EnviadorCorreo.enviar(CorreoSaliente)` con `EnviadorCorreoConsola` (`app.correo.modo=consola`, por defecto) y `EnviadorCorreoSmtp` (`app.correo.modo=smtp`); `CorreoProperties(modo, remitente, asincrono, duracionVerificacion, duracionRecuperacion, esperaReenvio)`.
- Produces: `CorreosDeCuenta.enviarVerificacion(Usuario, String token)` y `enviarRecuperacion(Usuario, String token)`: publican `CorreoPendiente`, que `DespachadorCorreo` envía **después del commit** (y nunca si la transacción se revierte). Los enlaces son `{app.url-publica}/verificar?token=…` y `{app.url-publica}/restablecer?token=…`.
- Produces (tests): `BuzonPrueba` (`@Primary`) con `todos()`, `para(String)`, `ultimoPara(String)`, `vaciar()` y `static tokenDe(CorreoSaliente)`; en `PruebaIntegracion`, `protected BuzonPrueba buzon`, vaciado antes de cada test.

- [ ] **Step 1: Buzón de pruebas**

Archivo: `backend/src/test/java/com/hellocr/soporte/BuzonPrueba.java`

```java
package com.hellocr.soporte;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.correo.EnviadorCorreo;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reemplaza al enviador real en los tests: guarda los correos en memoria. */
public class BuzonPrueba implements EnviadorCorreo {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<CorreoSaliente> correos = new CopyOnWriteArrayList<>();

    @Override
    public void enviar(CorreoSaliente correo) {
        correos.add(correo);
    }

    public List<CorreoSaliente> todos() {
        return List.copyOf(correos);
    }

    public List<CorreoSaliente> para(String destinatario) {
        return correos.stream().filter(correo -> correo.destinatario().equals(destinatario)).toList();
    }

    public CorreoSaliente ultimoPara(String destinatario) {
        List<CorreoSaliente> recibidos = para(destinatario);
        if (recibidos.isEmpty()) {
            throw new AssertionError("No llegó ningún correo para " + destinatario);
        }
        return recibidos.getLast();
    }

    public void vaciar() {
        correos.clear();
    }

    /** El token del enlace que trae el correo. */
    public static String tokenDe(CorreoSaliente correo) {
        Matcher encontrado = TOKEN.matcher(correo.texto());
        if (!encontrado.find()) {
            throw new AssertionError("El correo no trae un enlace con token: " + correo.texto());
        }
        return encontrado.group(1);
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebasConfig.java`

```java
package com.hellocr.soporte;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class PruebasConfig {

    /** Reemplaza al Clock del sistema en todos los beans que piden un Clock. */
    @Bean
    @Primary
    public RelojAjustable relojAjustable(@Value("${app.zona-horaria}") String zonaHoraria) {
        return new RelojAjustable(ZoneId.of(zonaHoraria));
    }

    /** Reemplaza al enviador de la consola: los tests leen los correos del buzón. */
    @Bean
    @Primary
    public BuzonPrueba buzonPrueba() {
        return new BuzonPrueba();
    }

    /** Para que las aserciones de MockMvc lean bien las tildes de las respuestas. */
    @Bean
    public MockMvcBuilderCustomizer respuestasEnUtf8() {
        return constructor -> constructor.defaultResponseCharacterEncoding(StandardCharsets.UTF_8);
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`

```java
package com.hellocr.soporte;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Base de los tests de integración: contexto completo, base hellocr_test limpia, reloj fijo y buzón vacío. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PruebasConfig.class)
public abstract class PruebaIntegracion {

    public static final String CLAVE = "clave12345";

    private static final String TABLAS = "tokens_correo, refresh_tokens, usuarios";
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

    @BeforeEach
    void reiniciarEstado() {
        reloj.reiniciar();
        buzon.vaciar();
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

- [ ] **Step 2: Tests**

Archivo: `backend/src/test/java/com/hellocr/correo/PlantillasCorreoTest.java`

```java
package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlantillasCorreoTest {

    private static final CorreoProperties PROPIEDADES = new CorreoProperties(CorreoProperties.Modo.CONSOLA,
            "HelloCR <no-responder@hellocr.local>", false, Duration.ofHours(24), Duration.ofHours(1),
            Duration.ofMinutes(1));

    private final PlantillasCorreo plantillas = new PlantillasCorreo("https://hellocr.duckdns.org/", PROPIEDADES);

    @Test
    void laVerificacionLlevaElEnlaceYCuantoDura() {
        CorreoSaliente correo = plantillas.verificacion(usuario("Sofía"), "tok_123");

        assertThat(correo.destinatario()).isEqualTo("sofia@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Verificá tu correo en HelloCR");
        assertThat(correo.texto())
                .contains("¡Hola, Sofía!")
                .contains("https://hellocr.duckdns.org/verificar?token=tok_123")
                .contains("El enlace vence en 24 horas.");
        assertThat(correo.html())
                .contains("href=\"https://hellocr.duckdns.org/verificar?token=tok_123\"")
                .contains("Sofía");
    }

    @Test
    void laRecuperacionNombraLaCuentaYVenceEnUnaHora() {
        CorreoSaliente correo = plantillas.recuperacion(usuario("Sofía"), "tok_456");

        assertThat(correo.asunto()).isEqualTo("Restablecé tu contraseña de HelloCR");
        assertThat(correo.texto())
                .contains("@sofia")
                .contains("https://hellocr.duckdns.org/restablecer?token=tok_456")
                .contains("El enlace vence en 1 hora.")
                .contains("Si no fuiste vos, ignorá este correo");
    }

    @Test
    void elNombreVisibleSeEscapaEnElHtml() {
        CorreoSaliente correo = plantillas.verificacion(usuario("<b>Ana</b> & \"Luis\""), "tok");

        assertThat(correo.html())
                .contains("&lt;b&gt;Ana&lt;/b&gt; &amp; &quot;Luis&quot;")
                .doesNotContain("<b>Ana</b>");
        assertThat(correo.texto()).contains("¡Hola, <b>Ana</b> & \"Luis\"!");
    }

    @Test
    void describeLasDuracionesEnPalabras() {
        assertThat(PlantillasCorreo.describir(Duration.ofHours(24))).isEqualTo("24 horas");
        assertThat(PlantillasCorreo.describir(Duration.ofHours(1))).isEqualTo("1 hora");
        assertThat(PlantillasCorreo.describir(Duration.ofMinutes(90))).isEqualTo("90 minutos");
        assertThat(PlantillasCorreo.describir(Duration.ofMinutes(1))).isEqualTo("1 minuto");
    }

    private static Usuario usuario(String nombreVisible) {
        return new Usuario("sofia@correo.cr", "sofia", nombreVisible, "hash", "Ab3dEf7h", Instant.EPOCH);
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/correo/EnviadorCorreoSmtpTest.java`

```java
package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class EnviadorCorreoSmtpTest {

    @Test
    void armaUnCorreoConRemitenteDestinatarioAsuntoYAmbasVersiones() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        CorreoProperties propiedades = new CorreoProperties(CorreoProperties.Modo.SMTP,
                "HelloCR <no-responder@hellocr.local>", true, Duration.ofHours(24), Duration.ofHours(1),
                Duration.ofMinutes(1));

        new EnviadorCorreoSmtp(mailSender, propiedades).enviar(
                new CorreoSaliente("ana@correo.cr", "Verificá tu correo en HelloCR", "texto plano", "<p>html</p>"));

        ArgumentCaptor<MimeMessage> enviado = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(enviado.capture());
        MimeMessage mensaje = enviado.getValue();
        InternetAddress remitente = (InternetAddress) mensaje.getFrom()[0];
        assertThat(remitente.getAddress()).isEqualTo("no-responder@hellocr.local");
        assertThat(remitente.getPersonal()).isEqualTo("HelloCR");
        assertThat(mensaje.getAllRecipients()[0].toString()).isEqualTo("ana@correo.cr");
        assertThat(mensaje.getSubject()).isEqualTo("Verificá tu correo en HelloCR");
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        mensaje.writeTo(crudo);
        assertThat(crudo.toString(StandardCharsets.UTF_8)).contains("texto plano").contains("<p>html</p>");
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/correo/DespachadorCorreoTest.java`

```java
package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class DespachadorCorreoTest extends PruebaIntegracion {

    @Autowired
    private CorreosDeCuenta correos;
    @Autowired
    private TransactionTemplate transacciones;
    @Autowired
    private CorreoProperties propiedades;

    @Test
    void elCorreoSaleRecienDespuesDelCommit() {
        Usuario ana = crearUsuario("ana");

        transacciones.executeWithoutResult(estado -> {
            correos.enviarVerificacion(ana, "tok");
            assertThat(buzon.todos()).as("todavía dentro de la transacción").isEmpty();
        });

        assertThat(buzon.todos()).singleElement()
                .extracting(CorreoSaliente::destinatario).isEqualTo("ana@correo.cr");
    }

    @Test
    void siLaTransaccionSeRevierteElCorreoNoSale() {
        Usuario ana = crearUsuario("ana");

        transacciones.executeWithoutResult(estado -> {
            correos.enviarRecuperacion(ana, "tok");
            estado.setRollbackOnly();
        });

        assertThat(buzon.todos()).isEmpty();
    }

    @Test
    void unFalloDelServidorDeCorreoNoSePropaga() {
        DespachadorCorreo despachador = new DespachadorCorreo(correo -> {
            throw new IllegalStateException("SMTP caído");
        }, propiedades);

        assertThatCode(() -> despachador.alConfirmar(
                new CorreoPendiente(new CorreoSaliente("ana@correo.cr", "Asunto", "texto", "<p>html</p>"))))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='PlantillasCorreoTest,EnviadorCorreoSmtpTest,DespachadorCorreoTest'`
Expected: FAIL de compilación — `cannot find symbol: class CorreoSaliente`.

- [ ] **Step 4: Implementación**

Archivo: `backend/src/main/java/com/hellocr/correo/CorreoSaliente.java`

```java
package com.hellocr.correo;

/** Un correo listo para enviar, en texto plano y en HTML. */
public record CorreoSaliente(String destinatario, String asunto, String texto, String html) {
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/EnviadorCorreo.java`

```java
package com.hellocr.correo;

/** Entrega un correo. En desarrollo lo escribe en el log; en producción lo manda por SMTP. */
public interface EnviadorCorreo {

    void enviar(CorreoSaliente correo);
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/CorreoProperties.java`

```java
package com.hellocr.correo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.correo")
@Validated
public record CorreoProperties(
        @NotNull Modo modo,
        @NotBlank String remitente,
        boolean asincrono,
        @NotNull Duration duracionVerificacion,
        @NotNull Duration duracionRecuperacion,
        @NotNull Duration esperaReenvio) {

    public enum Modo {
        CONSOLA, SMTP
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/EnviadorCorreoConsola.java`

```java
package com.hellocr.correo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Para desarrollo: el correo (con su enlace) aparece en el log en lugar de enviarse. */
@Component
@ConditionalOnProperty(name = "app.correo.modo", havingValue = "consola", matchIfMissing = true)
public class EnviadorCorreoConsola implements EnviadorCorreo {

    private static final Logger log = LoggerFactory.getLogger(EnviadorCorreoConsola.class);

    @Override
    public void enviar(CorreoSaliente correo) {
        log.info("Correo para {} — {}\n{}", correo.destinatario(), correo.asunto(), correo.texto());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/EnviadorCorreoSmtp.java`

```java
package com.hellocr.correo;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Para producción: envía por SMTP (Gmail) con la configuración spring.mail.*. */
@Component
@ConditionalOnProperty(name = "app.correo.modo", havingValue = "smtp")
public class EnviadorCorreoSmtp implements EnviadorCorreo {

    private final JavaMailSender mailSender;
    private final CorreoProperties propiedades;

    public EnviadorCorreoSmtp(JavaMailSender mailSender, CorreoProperties propiedades) {
        this.mailSender = mailSender;
        this.propiedades = propiedades;
    }

    @Override
    public void enviar(CorreoSaliente correo) {
        MimeMessage mensaje = mailSender.createMimeMessage();
        try {
            MimeMessageHelper ayudante = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
            ayudante.setFrom(propiedades.remitente());
            ayudante.setTo(correo.destinatario());
            ayudante.setSubject(correo.asunto());
            ayudante.setText(correo.texto(), correo.html());
        } catch (MessagingException error) {
            throw new IllegalStateException("No se pudo armar el correo para " + correo.destinatario(), error);
        }
        mailSender.send(mensaje);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/PlantillasCorreo.java`

```java
package com.hellocr.correo;

import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Textos de los correos de la cuenta, en voseo tico. */
@Component
public class PlantillasCorreo {

    private final String urlPublica;
    private final CorreoProperties propiedades;

    public PlantillasCorreo(@Value("${app.url-publica}") String urlPublica, CorreoProperties propiedades) {
        this.urlPublica = urlPublica.endsWith("/") ? urlPublica.substring(0, urlPublica.length() - 1) : urlPublica;
        this.propiedades = propiedades;
    }

    public CorreoSaliente verificacion(Usuario usuario, String token) {
        String enlace = urlPublica + "/verificar?token=" + token;
        String vence = describir(propiedades.duracionVerificacion());
        String texto = """
                ¡Hola, %s!

                Para terminar de crear tu cuenta en HelloCR, abrí este enlace:
                %s

                El enlace vence en %s. Si no creaste esta cuenta, ignorá este correo.
                """.formatted(usuario.getNombreVisible(), enlace, vence);
        String html = """
                <p>¡Hola, %s!</p>
                <p>Para terminar de crear tu cuenta en HelloCR, abrí este enlace:</p>
                <p><a href="%s">Verificar mi correo</a></p>
                <p>El enlace vence en %s. Si no creaste esta cuenta, ignorá este correo.</p>
                """.formatted(escapar(usuario.getNombreVisible()), enlace, vence);
        return new CorreoSaliente(usuario.getCorreo(), "Verificá tu correo en HelloCR", texto, html);
    }

    public CorreoSaliente recuperacion(Usuario usuario, String token) {
        String enlace = urlPublica + "/restablecer?token=" + token;
        String vence = describir(propiedades.duracionRecuperacion());
        String texto = """
                ¡Hola, %s!

                Recibimos un pedido para cambiar la contraseña de tu cuenta @%s en HelloCR. Para elegir una nueva, abrí este enlace:
                %s

                El enlace vence en %s. Si no fuiste vos, ignorá este correo: tu contraseña sigue igual.
                """.formatted(usuario.getNombreVisible(), usuario.getNombreUsuario(), enlace, vence);
        String html = """
                <p>¡Hola, %s!</p>
                <p>Recibimos un pedido para cambiar la contraseña de tu cuenta @%s en HelloCR. Para elegir una nueva, abrí este enlace:</p>
                <p><a href="%s">Elegir una contraseña nueva</a></p>
                <p>El enlace vence en %s. Si no fuiste vos, ignorá este correo: tu contraseña sigue igual.</p>
                """.formatted(escapar(usuario.getNombreVisible()), usuario.getNombreUsuario(), enlace, vence);
        return new CorreoSaliente(usuario.getCorreo(), "Restablecé tu contraseña de HelloCR", texto, html);
    }

    /** "24 horas", "1 hora", "90 minutos", "1 minuto". */
    static String describir(Duration duracion) {
        long horas = duracion.toHours();
        if (horas > 0 && duracion.toMinutesPart() == 0) {
            return horas == 1 ? "1 hora" : horas + " horas";
        }
        long minutos = duracion.toMinutes();
        return minutos == 1 ? "1 minuto" : minutos + " minutos";
    }

    /** El nombre visible lo escribe la persona: se escapa para que no inyecte HTML en el correo. */
    private static String escapar(String texto) {
        return HtmlUtils.htmlEscape(texto, "UTF-8");
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/CorreoPendiente.java`

```java
package com.hellocr.correo;

/** Evento: hay un correo para enviar cuando termine la transacción. */
public record CorreoPendiente(CorreoSaliente correo) {
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/CorreosDeCuenta.java`

```java
package com.hellocr.correo;

import com.hellocr.usuarios.Usuario;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Arma los correos de la cuenta y los deja pendientes hasta que la transacción se confirme. */
@Component
public class CorreosDeCuenta {

    private final PlantillasCorreo plantillas;
    private final ApplicationEventPublisher eventos;

    public CorreosDeCuenta(PlantillasCorreo plantillas, ApplicationEventPublisher eventos) {
        this.plantillas = plantillas;
        this.eventos = eventos;
    }

    public void enviarVerificacion(Usuario usuario, String token) {
        eventos.publishEvent(new CorreoPendiente(plantillas.verificacion(usuario, token)));
    }

    public void enviarRecuperacion(Usuario usuario, String token) {
        eventos.publishEvent(new CorreoPendiente(plantillas.recuperacion(usuario, token)));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/correo/DespachadorCorreo.java`

```java
package com.hellocr.correo;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DespachadorCorreo {

    private static final Logger log = LoggerFactory.getLogger(DespachadorCorreo.class);

    private final EnviadorCorreo enviador;
    private final Executor ejecutor;

    public DespachadorCorreo(EnviadorCorreo enviador, CorreoProperties propiedades) {
        this.enviador = enviador;
        this.ejecutor = propiedades.asincrono() ? Executors.newVirtualThreadPerTaskExecutor() : Runnable::run;
    }

    /**
     * Después del commit: si la transacción se revierte, el correo no sale. Sin transacción, sale enseguida.
     * Un fallo del servidor de correo se registra y no afecta la respuesta; la persona puede pedir un reenvío.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void alConfirmar(CorreoPendiente pendiente) {
        ejecutor.execute(() -> enviar(pendiente.correo()));
    }

    private void enviar(CorreoSaliente correo) {
        try {
            enviador.enviar(correo);
        } catch (RuntimeException error) {
            log.error("No se pudo enviar el correo \"{}\" a {}", correo.asunto(), correo.destinatario(), error);
        }
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores. En la salida aparece un `ERROR ... No se pudo enviar el correo` con `SMTP caído`: es el log esperado de `unFalloDelServidorDeCorreoNoSePropaga`.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): correos de cuenta enviados después del commit (consola, SMTP y buzón de pruebas)"
```

---

### Task 8: Tokens de los enlaces de correo

**Files:**
- Create en `backend/src/main/java/com/hellocr/auth/`: `PropositoToken.java`, `TokenCorreo.java`, `TokenCorreoRepository.java`, `TokenCorreoService.java`
- Test: `backend/src/test/java/com/hellocr/auth/TokenCorreoServiceTest.java`

**Interfaces:**
- Consumes: `Usuario` (Task 4), `TokensSeguros` (Task 6), `CorreoProperties` (Task 7), `ErrorNegocio` (Task 3), `Clock`.
- Produces: `enum PropositoToken { VERIFICACION, RECUPERACION }`; `TokenCorreoService.emitir(Usuario, PropositoToken): String` (invalida los tokens anteriores del mismo propósito y devuelve el valor en claro), `puedeEmitir(Usuario, PropositoToken): boolean` (pasó `app.correo.espera-reenvio` desde el último), `consumir(String token, PropositoToken): Usuario` (lo marca usado; usar dentro de la transacción del llamador) y `limpiarVencidos(): int` (a diario a las 4:00). Un token inexistente, vacío, usado, vencido o de otro propósito lanza `ErrorNegocio(TOKEN_INVALIDO, "El enlace no es válido o ya venció. Pedí uno nuevo.")`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/auth/TokenCorreoServiceTest.java`

```java
package com.hellocr.auth;

import static com.hellocr.auth.PropositoToken.RECUPERACION;
import static com.hellocr.auth.PropositoToken.VERIFICACION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TokenCorreoServiceTest extends PruebaIntegracion {

    @Autowired
    private TokenCorreoService servicio;

    private Usuario ana;

    @BeforeEach
    void crearAna() {
        ana = crearUsuarioSinVerificar("ana");
    }

    @Test
    void emitirGuardaSoloElHashYVenceSegunElProposito() {
        String verificacion = servicio.emitir(ana, VERIFICACION);
        servicio.emitir(ana, RECUPERACION);

        assertThat(vigenciaEnSegundos(VERIFICACION)).isEqualTo(24 * 3600);
        assertThat(vigenciaEnSegundos(RECUPERACION)).isEqualTo(3600);
        assertThat(jdbc.queryForList("SELECT token_hash FROM tokens_correo", String.class))
                .doesNotContain(verificacion)
                .allSatisfy(hash -> assertThat(hash).hasSize(64));
    }

    @Test
    void consumirDevuelveElUsuarioYElTokenNoSirveDosVeces() {
        String token = servicio.emitir(ana, VERIFICACION);

        assertThat(servicio.consumir(token, VERIFICACION).getId()).isEqualTo(ana.getId());
        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void unTokenVencidoNoSirve() {
        String token = servicio.emitir(ana, VERIFICACION);
        reloj.avanzar(Duration.ofHours(24));

        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void unTokenDeOtroPropositoNoSirve() {
        String token = servicio.emitir(ana, RECUPERACION);

        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void tokensInventadosOVaciosNoSirven() {
        assertThatThrownBy(() -> servicio.consumir("inventado", VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThatThrownBy(() -> servicio.consumir("", VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThatThrownBy(() -> servicio.consumir(null, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void emitirOtroInvalidaElAnteriorDelMismoProposito() {
        String viejo = servicio.emitir(ana, VERIFICACION);
        String recuperacion = servicio.emitir(ana, RECUPERACION);
        reloj.avanzar(Duration.ofMinutes(2));
        String nuevo = servicio.emitir(ana, VERIFICACION);

        assertThatThrownBy(() -> servicio.consumir(viejo, VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThat(servicio.consumir(nuevo, VERIFICACION).getId()).isEqualTo(ana.getId());
        assertThat(servicio.consumir(recuperacion, RECUPERACION).getId()).isEqualTo(ana.getId());
    }

    @Test
    void soloSePuedeReenviarDespuesDeUnMinuto() {
        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isTrue();

        servicio.emitir(ana, VERIFICACION);

        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isFalse();
        assertThat(servicio.puedeEmitir(ana, RECUPERACION)).isTrue();
        reloj.avanzar(Duration.ofMinutes(1));
        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isTrue();
    }

    @Test
    void dosConsumosSimultaneosDelMismoTokenSoloUnoGana() throws Exception {
        String token = servicio.emitir(ana, RECUPERACION);

        List<Boolean> resultados = enParalelo(5, () -> {
            try {
                servicio.consumir(token, RECUPERACION);
                return true;
            } catch (ErrorNegocio error) {
                return false;
            }
        });

        assertThat(Collections.frequency(resultados, true)).isEqualTo(1);
    }

    @Test
    void laLimpiezaBorraSoloLosVencidos() {
        servicio.emitir(ana, RECUPERACION);
        servicio.emitir(ana, VERIFICACION);
        reloj.avanzar(Duration.ofHours(2));

        assertThat(servicio.limpiarVencidos()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT proposito FROM tokens_correo", String.class))
                .containsExactly("VERIFICACION");
    }

    private long vigenciaEnSegundos(PropositoToken proposito) {
        return jdbc.queryForObject(
                "SELECT extract(epoch FROM expira_en - creado_en)::bigint FROM tokens_correo WHERE proposito = ?",
                Long.class, proposito.name());
    }

    private void esTokenInvalido(Throwable error) {
        assertThat(error).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) error).codigo()).isEqualTo(CodigoError.TOKEN_INVALIDO);
        assertThat(error.getMessage()).isEqualTo("El enlace no es válido o ya venció. Pedí uno nuevo.");
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=TokenCorreoServiceTest`
Expected: FAIL de compilación — `cannot find symbol: class TokenCorreoService`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/auth/PropositoToken.java`

```java
package com.hellocr.auth;

/** Para qué sirve un enlace enviado por correo. */
public enum PropositoToken {
    VERIFICACION, RECUPERACION
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/TokenCorreo.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tokens_correo")
public class TokenCorreo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PropositoToken proposito;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(name = "usado_en")
    private Instant usadoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected TokenCorreo() {
    }

    public TokenCorreo(Usuario usuario, PropositoToken proposito, String tokenHash, Instant expiraEn,
            Instant creadoEn) {
        this.usuario = usuario;
        this.proposito = proposito;
        this.tokenHash = tokenHash;
        this.expiraEn = expiraEn;
        this.creadoEn = creadoEn;
    }

    /** Sirve si es del propósito pedido, no se usó y no venció. */
    public boolean sirvePara(PropositoToken buscado, Instant ahora) {
        return proposito == buscado && usadoEn == null && expiraEn.isAfter(ahora);
    }

    public void usar(Instant ahora) {
        usadoEn = ahora;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/TokenCorreoRepository.java`

```java
package com.hellocr.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TokenCorreoRepository extends JpaRepository<TokenCorreo, Long> {

    /** SELECT … FOR UPDATE: dos clics simultáneos en el mismo enlace no pueden usarlo los dos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TokenCorreo t where t.tokenHash = :tokenHash")
    Optional<TokenCorreo> bloquearPorHash(String tokenHash);

    Optional<TokenCorreo> findFirstByUsuario_IdAndPropositoOrderByCreadoEnDesc(UUID usuarioId,
            PropositoToken proposito);

    @Modifying
    @Query("""
            update TokenCorreo t set t.usadoEn = :ahora
            where t.usuario.id = :usuarioId and t.proposito = :proposito and t.usadoEn is null
            """)
    int invalidarVigentes(UUID usuarioId, PropositoToken proposito, Instant ahora);

    @Modifying
    @Query("delete from TokenCorreo t where t.expiraEn < :ahora")
    int borrarVencidos(Instant ahora);
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/TokenCorreoService.java`

```java
package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.TokensSeguros;
import com.hellocr.correo.CorreoProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenCorreoService {

    private final TokenCorreoRepository tokens;
    private final CorreoProperties propiedades;
    private final Clock clock;

    public TokenCorreoService(TokenCorreoRepository tokens, CorreoProperties propiedades, Clock clock) {
        this.tokens = tokens;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Crea un token nuevo (el anterior del mismo propósito deja de servir) y devuelve su valor en claro. */
    @Transactional
    public String emitir(Usuario usuario, PropositoToken proposito) {
        Instant ahora = clock.instant();
        tokens.invalidarVigentes(usuario.getId(), proposito, ahora);
        String token = TokensSeguros.generar();
        tokens.save(new TokenCorreo(usuario, proposito, TokensSeguros.hash(token), ahora.plus(duracion(proposito)),
                ahora));
        return token;
    }

    /** Evita mandar correos seguidos: exige app.correo.espera-reenvio desde el último del mismo propósito. */
    @Transactional(readOnly = true)
    public boolean puedeEmitir(Usuario usuario, PropositoToken proposito) {
        Instant ahora = clock.instant();
        return tokens.findFirstByUsuario_IdAndPropositoOrderByCreadoEnDesc(usuario.getId(), proposito)
                .map(ultimo -> !ahora.isBefore(ultimo.getCreadoEn().plus(propiedades.esperaReenvio())))
                .orElse(true);
    }

    /** Marca el token como usado y devuelve su usuario. Llamar dentro de la transacción que lo va a modificar. */
    @Transactional
    public Usuario consumir(String token, PropositoToken proposito) {
        if (token == null || token.isBlank()) {
            throw tokenInvalido();
        }
        Instant ahora = clock.instant();
        TokenCorreo encontrado = tokens.bloquearPorHash(TokensSeguros.hash(token))
                .filter(candidato -> candidato.sirvePara(proposito, ahora))
                .orElseThrow(TokenCorreoService::tokenInvalido);
        encontrado.usar(ahora);
        return encontrado.getUsuario();
    }

    /** Todos los días a las 4:00. Ningún token dura más de 24 h, así que los usados también terminan acá. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int limpiarVencidos() {
        return tokens.borrarVencidos(clock.instant());
    }

    private Duration duracion(PropositoToken proposito) {
        return switch (proposito) {
            case VERIFICACION -> propiedades.duracionVerificacion();
            case RECUPERACION -> propiedades.duracionRecuperacion();
        };
    }

    private static ErrorNegocio tokenInvalido() {
        return new ErrorNegocio(CodigoError.TOKEN_INVALIDO, "El enlace no es válido o ya venció. Pedí uno nuevo.");
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): tokens de un solo uso para verificar el correo y recuperar la contraseña"
```

---

### Task 9: Bloqueo por intentos fallidos de login

**Files:**
- Create: `backend/src/main/java/com/hellocr/auth/LoginProperties.java`, `backend/src/main/java/com/hellocr/auth/LimiteIntentosLogin.java`
- Test: `backend/src/test/java/com/hellocr/auth/LimiteIntentosLoginTest.java`

**Interfaces:**
- Consumes: `ErrorNegocio`/`CodigoError` (Task 3), `Clock`.
- Produces: `LoginProperties(maxIntentos, bloqueo)`; `LimiteIntentosLogin.verificar(String clave)` (lanza `DEMASIADOS_INTENTOS` con el extra `reintentarEnSegundos` si la clave está bloqueada), `registrarFallo(String clave)`, `registrarExito(String clave)`, `olvidarVencidos()` (cada hora) y `olvidarTodo()` (para los tests: el estado vive en memoria y se comparte entre tests del mismo contexto). La clave la arma la Task 10: `identificadorNormalizado|ip`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/auth/LimiteIntentosLoginTest.java`

```java
package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.RelojAjustable;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LimiteIntentosLoginTest {

    private static final String CLAVE = "ana|127.0.0.1";

    private final RelojAjustable reloj = new RelojAjustable(ZoneId.of("America/Costa_Rica"));
    private final LimiteIntentosLogin limite =
            new LimiteIntentosLogin(new LoginProperties(5, Duration.ofMinutes(15)), reloj);

    @Test
    void cuatroFallosNoBloquean() {
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void elQuintoFalloBloqueaQuinceMinutos() {
        fallar(5);

        assertThatThrownBy(() -> limite.verificar(CLAVE)).isInstanceOfSatisfying(ErrorNegocio.class, error -> {
            assertThat(error.codigo()).isEqualTo(CodigoError.DEMASIADOS_INTENTOS);
            assertThat(error.extras()).containsEntry(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, 900L);
            assertThat(error.getMessage()).isEqualTo("Demasiados intentos fallidos. Probá de nuevo en 15 minutos.");
        });

        reloj.avanzar(Duration.ofMinutes(14).plusSeconds(30));
        assertThatThrownBy(() -> limite.verificar(CLAVE))
                .hasMessage("Demasiados intentos fallidos. Probá de nuevo en 1 minuto.");

        reloj.avanzar(Duration.ofSeconds(30));
        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void unExitoReiniciaElContador() {
        fallar(4);
        limite.registrarExito(CLAVE);
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void losFallosEspaciadosNoSeAcumulan() {
        fallar(4);
        reloj.avanzar(Duration.ofMinutes(15));
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void cadaClaveSeCuentaAparte() {
        fallar(5);

        assertThatCode(() -> limite.verificar("ana|10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void laLimpiezaOlvidaLoQueYaVencio() {
        fallar(5);
        limite.registrarFallo("luis|127.0.0.1");
        reloj.avanzar(Duration.ofMinutes(16));

        limite.olvidarVencidos();

        assertThat(limite.registrados()).isZero();
    }

    private void fallar(int veces) {
        for (int i = 0; i < veces; i++) {
            limite.registrarFallo(CLAVE);
        }
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=LimiteIntentosLoginTest`
Expected: FAIL de compilación — `cannot find symbol: class LimiteIntentosLogin`.

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/auth/LoginProperties.java`

```java
package com.hellocr.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.login")
@Validated
public record LoginProperties(@Min(1) int maxIntentos, @NotNull Duration bloqueo) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/LimiteIntentosLogin.java`

```java
package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cuenta los logins fallidos por identificador + IP, en memoria (hay un solo servidor).
 * Los fallos se cuentan en una ventana de app.login.bloqueo desde el primero; al llegar al máximo,
 * la clave queda bloqueada ese mismo tiempo.
 */
@Component
public class LimiteIntentosLogin {

    private record Registro(int fallos, Instant venceEn, boolean bloqueado) {
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final LoginProperties propiedades;
    private final Clock clock;

    public LimiteIntentosLogin(LoginProperties propiedades, Clock clock) {
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Lanza DEMASIADOS_INTENTOS si la clave está bloqueada. */
    public void verificar(String clave) {
        Instant ahora = clock.instant();
        Registro registro = registros.get(clave);
        if (registro != null && registro.bloqueado() && ahora.isBefore(registro.venceEn())) {
            throw bloqueada(Duration.between(ahora, registro.venceEn()));
        }
    }

    public void registrarFallo(String clave) {
        Instant ahora = clock.instant();
        registros.compute(clave, (k, actual) -> {
            Registro vigente = actual == null || !ahora.isBefore(actual.venceEn())
                    ? new Registro(0, ahora.plus(propiedades.bloqueo()), false)
                    : actual;
            int fallos = vigente.fallos() + 1;
            return fallos >= propiedades.maxIntentos()
                    ? new Registro(fallos, ahora.plus(propiedades.bloqueo()), true)
                    : new Registro(fallos, vigente.venceEn(), false);
        });
    }

    public void registrarExito(String clave) {
        registros.remove(clave);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void olvidarVencidos() {
        Instant ahora = clock.instant();
        registros.values().removeIf(registro -> !ahora.isBefore(registro.venceEn()));
    }

    /** Solo para tests: el estado vive en memoria y se comparte entre tests del mismo contexto. */
    public void olvidarTodo() {
        registros.clear();
    }

    int registrados() {
        return registros.size();
    }

    private static ErrorNegocio bloqueada(Duration falta) {
        long segundos = Math.max(1, (falta.toMillis() + 999) / 1000);
        long minutos = (segundos + 59) / 60;
        String cuando = minutos == 1 ? "1 minuto" : minutos + " minutos";
        return new ErrorNegocio(CodigoError.DEMASIADOS_INTENTOS,
                "Demasiados intentos fallidos. Probá de nuevo en " + cuando + ".",
                Map.of(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, segundos));
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): bloqueo de 15 minutos tras 5 logins fallidos"
```

---

### Task 10: Login, refresh y logout

**Files:**
- Create en `backend/src/main/java/com/hellocr/auth/`: `Identificador.java`, `Contrasenas.java`, `SolicitudLogin.java`, `RespuestaSesion.java`, `SesionService.java`, `CookieRefresh.java`, `SesionController.java`
- Create: `backend/src/main/java/com/hellocr/usuarios/UsuarioPropio.java`
- Create: `backend/src/test/java/com/hellocr/soporte/SesionPrueba.java`
- Modify: `backend/src/test/java/com/hellocr/soporte/PruebaIntegracion.java`
- Test: `backend/src/test/java/com/hellocr/auth/SesionControllerTest.java`

**Interfaces:**
- Consumes: `UsuarioRepository` (Task 4); `JwtService`, `JwtProperties`, `PasswordEncoder` (Task 5); `RefreshTokenService` (Task 6); `LimiteIntentosLogin` (Task 9).
- Produces (API): `POST /api/auth/login` `{identificador, contrasena}` → 200 `{accessToken, usuario: UsuarioPropio}` + cookie; `POST /api/auth/refresh` → 200 igual; `POST /api/auth/logout` → 204 y borra la cookie.
- Produces: `record UsuarioPropio(UUID id, String correo, String nombreUsuario, String nombreVisible, String info, String codigoInvitacion)` con `static de(Usuario)`; `record RespuestaSesion(String accessToken, UsuarioPropio usuario)`; `SesionService.abrirSesion(Usuario): Sesion` con `record Sesion(RespuestaSesion respuesta, String refreshToken)` (lo usa la verificación de correo); `CookieRefresh.responder(HttpStatus, Sesion)` y `CookieRefresh.NOMBRE`; `Contrasenas.excedeLimite(String)` y `Contrasenas.validarLongitud(String contrasena, String campo)` (400 si pasa de 72 bytes).
- Produces (tests): `SesionPrueba(accessToken, refreshToken, usuarioId)` con `static de(MvcResult)` y `cookie()`; en `PruebaIntegracion`: `iniciarSesion(String nombreUsuario)`, `sesionDe(String nombreUsuario)` (crea la cuenta verificada y abre sesión) y `con(SesionPrueba)` (agrega el Bearer). El contador de intentos se vacía antes de cada test.

- [ ] **Step 1: Ayudantes de sesión para los tests**

Archivo: `backend/src/test/java/com/hellocr/soporte/SesionPrueba.java`

```java
package com.hellocr.soporte;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.MvcResult;

/** Lo que un test necesita de una sesión abierta con /login, /refresh o /verificar. */
public record SesionPrueba(String accessToken, String refreshToken, String usuarioId) {

    public static SesionPrueba de(MvcResult resultado) throws Exception {
        String json = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Cookie cookie = resultado.getResponse().getCookie("refresh_token");
        return new SesionPrueba(JsonPath.read(json, "$.accessToken"),
                cookie == null ? null : cookie.getValue(), JsonPath.read(json, "$.usuario.id"));
    }

    public Cookie cookie() {
        return new Cookie("refresh_token", refreshToken);
    }
}
```

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

    private static final String TABLAS = "tokens_correo, refresh_tokens, usuarios";
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

- [ ] **Step 2: Test**

Archivo: `backend/src/test/java/com/hellocr/auth/SesionControllerTest.java`

```java
package com.hellocr.auth;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SesionControllerTest extends PruebaIntegracion {

    @Test
    void loginConElNombreDeUsuarioAbreSesion() throws Exception {
        crearUsuario("ana");

        login("ana", CLAVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.usuario.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.usuario.nombreVisible").value("Usuario ana"))
                .andExpect(jsonPath("$.usuario.codigoInvitacion").isNotEmpty())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token="), containsString("HttpOnly"),
                        containsString("SameSite=Strict"), containsString("Path=/api/auth"),
                        containsString("Max-Age=2592000"))));
    }

    @Test
    void loginAceptaCorreoOUsuarioConMayusculasEspaciosYArroba() throws Exception {
        crearUsuario("ana");

        for (String identificador : List.of("  ANA@Correo.cr ", "@Ana", " ana ")) {
            login(identificador, CLAVE).andExpect(status().isOk());
        }
    }

    @Test
    void loginFallidoNoRevelaSiLaCuentaExiste() throws Exception {
        crearUsuario("ana");

        login("ana", "otra-clave")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"))
                .andExpect(jsonPath("$.detail").value("El usuario o la contraseña no son correctos."));
        login("nadie", CLAVE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("El usuario o la contraseña no son correctos."));
    }

    @Test
    void unaCuentaSinVerificarNoEntraYSoloLoSabeQuienTieneLaClave() throws Exception {
        crearUsuarioSinVerificar("ana");

        login("ana", CLAVE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CORREO_NO_VERIFICADO"))
                .andExpect(jsonPath("$.detail").value("Todavía no verificaste tu correo. Revisá tu bandeja de entrada."));
        login("ana", "otra-clave")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void cincoFallosBloqueanQuinceMinutosAunConLaClaveCorrecta() throws Exception {
        crearUsuario("ana");
        for (int i = 0; i < 5; i++) {
            login("ana", "mala-clave").andExpect(status().isUnauthorized());
        }

        login("ana", CLAVE)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"));
        loginDesde("10.0.0.2", "ana", CLAVE).andExpect(status().isOk());

        reloj.avanzar(Duration.ofMinutes(15));
        login("ana", CLAVE).andExpect(status().isOk());
    }

    @Test
    void unaContrasenaDeMasDe72BytesNoProvocaErrorInterno() throws Exception {
        crearUsuario("ana");

        login("ana", "ñ".repeat(40)).andExpect(status().isUnauthorized());
    }

    @Test
    void camposVaciosDevuelvenLosErroresPorCampo() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.identificador").value("Escribí tu correo o tu nombre de usuario."))
                .andExpect(jsonPath("$.errores.contrasena").value("Escribí tu contraseña."));
    }

    @Test
    void unJsonMalFormadoEs400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"identificador\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void refreshRotaLaCookieYEntregaUnAccessTokenNuevo() throws Exception {
        SesionPrueba inicial = sesionDe("ana");
        reloj.avanzar(Duration.ofMinutes(20));

        SesionPrueba renovada = SesionPrueba.de(mvc.perform(post("/api/auth/refresh").cookie(inicial.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andReturn());

        // El token viejo venció (401); con el nuevo la solicitud llega al controlador (404: la ruta no existe).
        mvc.perform(get("/api/no-existe").with(con(inicial))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/no-existe").with(con(renovada))).andExpect(status().isNotFound());
    }

    @Test
    void refreshFuncionaAunqueElClienteMandeUnBearerVencido() throws Exception {
        SesionPrueba sesion = sesionDe("ana");
        reloj.avanzar(Duration.ofHours(1));

        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie()).with(con(sesion)))
                .andExpect(status().isOk());
    }

    @Test
    void reusarUnaCookieViejaCierraTodasLasSesiones() throws Exception {
        SesionPrueba vieja = sesionDe("ana");
        SesionPrueba nueva = SesionPrueba.de(mvc.perform(post("/api/auth/refresh").cookie(vieja.cookie())).andReturn());
        reloj.avanzar(Duration.ofMinutes(1));

        mvc.perform(post("/api/auth/refresh").cookie(vieja.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        mvc.perform(post("/api/auth/refresh").cookie(nueva.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevocaElRefreshYBorraLaCookie() throws Exception {
        SesionPrueba sesion = sesionDe("ana");

        mvc.perform(post("/api/auth/logout").cookie(sesion.cookie()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=;"), containsString("Max-Age=0"))));
        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshSinCookieEs401() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return loginDesde("127.0.0.1", identificador, contrasena);
    }

    private ResultActions loginDesde(String ip, String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(solicitud -> {
                    solicitud.setRemoteAddr(ip);
                    return solicitud;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identificador": "%s", "contrasena": "%s"}
                        """.formatted(identificador, contrasena)));
    }
}
```

- [ ] **Step 3: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=SesionControllerTest`
Expected: FAIL — los endpoints todavía no existen (por ejemplo `Status expected:<200> but was:<404>`).

- [ ] **Step 4: DTOs y utilidades**

Archivo: `backend/src/main/java/com/hellocr/usuarios/UsuarioPropio.java`

```java
package com.hellocr.usuarios;

import java.util.UUID;

/** Los datos de la cuenta que solo ve su dueño. El plan 3 agrega fotoId. */
public record UsuarioPropio(UUID id, String correo, String nombreUsuario, String nombreVisible, String info,
        String codigoInvitacion) {

    public static UsuarioPropio de(Usuario usuario) {
        return new UsuarioPropio(usuario.getId(), usuario.getCorreo(), usuario.getNombreUsuario(),
                usuario.getNombreVisible(), usuario.getInfo(), usuario.getCodigoInvitacion());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RespuestaSesion.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.UsuarioPropio;

public record RespuestaSesion(String accessToken, UsuarioPropio usuario) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/SolicitudLogin.java`

```java
package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;

public record SolicitudLogin(
        @NotBlank(message = "Escribí tu correo o tu nombre de usuario.") String identificador,
        @NotBlank(message = "Escribí tu contraseña.") String contrasena) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/Identificador.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;

/** Lo que la persona escribe para entrar: su correo o su @usuario, ya normalizado. */
record Identificador(String valor, boolean esCorreo) {

    static Identificador de(String texto) {
        String limpio = texto.trim();
        return limpio.indexOf('@') > 0
                ? new Identificador(Usuario.normalizarCorreo(limpio), true)
                : new Identificador(Usuario.normalizarNombreUsuario(limpio), false);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/Contrasenas.java`

```java
package com.hellocr.auth;

import com.hellocr.comun.ErrorNegocio;
import java.nio.charset.StandardCharsets;

final class Contrasenas {

    /** BCrypt solo admite 72 bytes; con tildes o eñes eso puede ser menos de 64 caracteres. */
    static final int MAX_BYTES = 72;

    private Contrasenas() {
    }

    static boolean excedeLimite(String contrasena) {
        return contrasena.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /** 400 con el error en el campo, igual que las demás validaciones del formulario. */
    static void validarLongitud(String contrasena, String campo) {
        if (excedeLimite(contrasena)) {
            throw ErrorNegocio.validacion(campo, "La contraseña es demasiado larga.");
        }
    }
}
```

- [ ] **Step 5: Servicio, cookie y controlador**

Archivo: `backend/src/main/java/com/hellocr/auth/SesionService.java`

```java
package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioPropio;
import com.hellocr.usuarios.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SesionService {

    private final UsuarioRepository usuarios;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final LimiteIntentosLogin limiteIntentos;
    /** Se compara contra este hash cuando la cuenta no existe, para que el login tarde lo mismo. */
    private final String hashFicticio;

    public SesionService(UsuarioRepository usuarios, JwtService jwtService, RefreshTokenService refreshTokens,
            PasswordEncoder passwordEncoder, LimiteIntentosLogin limiteIntentos) {
        this.usuarios = usuarios;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.limiteIntentos = limiteIntentos;
        this.hashFicticio = passwordEncoder.encode("contraseña-que-nadie-usa");
    }

    /** Sesión recién abierta: la respuesta para el cuerpo y el refresh token para la cookie. */
    public record Sesion(RespuestaSesion respuesta, String refreshToken) {
    }

    @Transactional
    public Sesion login(SolicitudLogin solicitud, String ip) {
        Identificador identificador = Identificador.de(solicitud.identificador());
        String clave = identificador.valor() + "|" + ip;
        limiteIntentos.verificar(clave);
        Usuario usuario = (identificador.esCorreo()
                ? usuarios.findByCorreo(identificador.valor())
                : usuarios.findByNombreUsuario(identificador.valor())).orElse(null);
        String hash = usuario == null ? hashFicticio : usuario.getHashContrasena();
        boolean valida = !Contrasenas.excedeLimite(solicitud.contrasena())
                && passwordEncoder.matches(solicitud.contrasena(), hash)
                && usuario != null;
        if (!valida) {
            limiteIntentos.registrarFallo(clave);
            throw new ErrorNegocio(CodigoError.CREDENCIALES_INVALIDAS, "El usuario o la contraseña no son correctos.");
        }
        limiteIntentos.registrarExito(clave);
        if (!usuario.correoVerificado()) {
            throw new ErrorNegocio(CodigoError.CORREO_NO_VERIFICADO,
                    "Todavía no verificaste tu correo. Revisá tu bandeja de entrada.");
        }
        return abrirSesion(usuario);
    }

    @Transactional(noRollbackFor = ErrorNegocio.class)
    public Sesion refrescar(String refreshToken) {
        RefreshTokenService.Rotacion rotacion = refreshTokens.rotar(refreshToken);
        return new Sesion(respuestaPara(rotacion.usuario()), rotacion.nuevoToken());
    }

    @Transactional
    public void cerrarSesion(String refreshToken) {
        refreshTokens.revocar(refreshToken);
    }

    /** También la usa la verificación de correo, que abre sesión al terminar. */
    @Transactional
    public Sesion abrirSesion(Usuario usuario) {
        return new Sesion(respuestaPara(usuario), refreshTokens.emitir(usuario));
    }

    private RespuestaSesion respuestaPara(Usuario usuario) {
        return new RespuestaSesion(jwtService.emitir(usuario), UsuarioPropio.de(usuario));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/CookieRefresh.java`

```java
package com.hellocr.auth;

import com.hellocr.config.JwtProperties;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** La cookie del refresh token: solo viaja a /api/auth y el JavaScript de la página no puede leerla. */
@Component
public class CookieRefresh {

    public static final String NOMBRE = "refresh_token";

    private final JwtProperties propiedades;

    public CookieRefresh(JwtProperties propiedades) {
        this.propiedades = propiedades;
    }

    public ResponseEntity<RespuestaSesion> responder(HttpStatus estado, SesionService.Sesion sesion) {
        return ResponseEntity.status(estado)
                .header(HttpHeaders.SET_COOKIE, crear(sesion.refreshToken(), propiedades.duracionRefresh()).toString())
                .body(sesion.respuesta());
    }

    public String borrar() {
        return crear("", Duration.ZERO).toString();
    }

    private ResponseCookie crear(String valor, Duration duracion) {
        return ResponseCookie.from(NOMBRE, valor)
                .httpOnly(true)
                .secure(propiedades.cookieSegura())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(duracion)
                .build();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/SesionController.java`

```java
package com.hellocr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SesionController {

    private final SesionService sesiones;
    private final CookieRefresh cookie;

    public SesionController(SesionService sesiones, CookieRefresh cookie) {
        this.sesiones = sesiones;
        this.cookie = cookie;
    }

    @PostMapping("/login")
    public ResponseEntity<RespuestaSesion> login(@Valid @RequestBody SolicitudLogin solicitud,
            HttpServletRequest http) {
        return cookie.responder(HttpStatus.OK, sesiones.login(solicitud, http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RespuestaSesion> refrescar(
            @CookieValue(name = CookieRefresh.NOMBRE, required = false) String refreshToken) {
        return cookie.responder(HttpStatus.OK, sesiones.refrescar(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = CookieRefresh.NOMBRE, required = false) String refreshToken) {
        if (refreshToken != null) {
            sesiones.cerrarSesion(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.borrar()).build();
    }
}
```

- [ ] **Step 6: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): login con correo o @usuario, refresh y logout"
```

---
### Task 11: Registro, verificación de correo y limpieza de cuentas sin verificar

**Files:**
- Create en `backend/src/main/java/com/hellocr/usuarios/`: `ErroresUsuario.java`, `CuentasProperties.java`, `LimpiezaCuentas.java`
- Create en `backend/src/main/java/com/hellocr/auth/`: `SolicitudRegistro.java`, `RespuestaRegistro.java`, `SolicitudToken.java`, `SolicitudCorreo.java`, `RegistroService.java`, `RegistroController.java`
- Test: `backend/src/test/java/com/hellocr/auth/RegistroControllerTest.java`, `backend/src/test/java/com/hellocr/usuarios/LimpiezaCuentasTest.java`

**Interfaces:**
- Consumes: `UsuarioRepository`, `GeneradorCodigos`, `NombresReservados` (Task 4); `PasswordEncoder` (Task 5); `CorreosDeCuenta` (Task 7); `TokenCorreoService`, `PropositoToken` (Task 8); `SesionService`, `CookieRefresh`, `Contrasenas` (Task 10).
- Produces (API): `POST /api/auth/registro` `{correo, nombreUsuario, nombreVisible, contrasena}` → 201 `{correo}` sin sesión; `POST /api/auth/verificar` `{token}` → 200 como el login; `POST /api/auth/reenviar-verificacion` `{correo}` → 204 siempre.
- Produces: `ErroresUsuario.correoEnUso()`, `nombreUsuarioEnUso()`, `nombreUsuarioReservado()`, `noEncontrado()` y `traducirDuplicado(DataIntegrityViolationException)` (los reutiliza la Task 13); `SolicitudCorreo(correo)` (la reutiliza la Task 12); `LimpiezaCuentas.borrarSinVerificar(): int` (a diario a las 4:10).

- [ ] **Step 1: Tests**

Archivo: `backend/src/test/java/com/hellocr/auth/RegistroControllerTest.java`

```java
package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.soporte.BuzonPrueba;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RegistroControllerTest extends PruebaIntegracion {

    @Test
    void registroCreaLaCuentaSinSesionYEnviaElCorreoDeVerificacion() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        CorreoSaliente correo = buzon.ultimoPara("ana@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Verificá tu correo en HelloCR");
        assertThat(correo.texto()).contains("¡Hola, Ana Mora!").contains("http://localhost:5173/verificar?token=");
        login("ana", CLAVE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CORREO_NO_VERIFICADO"));
    }

    @Test
    void verificarConElEnlaceDelCorreoAbreSesion() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());

        verificar(tokenDelCorreo("ana@correo.cr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")));
        iniciarSesion("ana");
    }

    @Test
    void verificarDosVecesFallaLaSegundaPeroLaCuentaQuedaVerificada() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());
        String token = tokenDelCorreo("ana@correo.cr");

        verificar(token).andExpect(status().isOk());
        verificar(token)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("El enlace no es válido o ya venció. Pedí uno nuevo."));
        iniciarSesion("ana");
    }

    @Test
    void unEnlaceVencidoNoSirve() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());
        String token = tokenDelCorreo("ana@correo.cr");
        reloj.avanzar(Duration.ofHours(24));

        verificar(token).andExpect(status().isUnprocessableContent());
    }

    @Test
    void normalizaElCorreoYElNombreDeUsuario() throws Exception {
        registrar("  Ana@Correo.CR ", "@Ana", "  Ana  ", CLAVE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"));

        assertThat(jdbc.queryForMap("SELECT correo, nombre_usuario, nombre_visible FROM usuarios"))
                .containsEntry("correo", "ana@correo.cr")
                .containsEntry("nombre_usuario", "ana")
                .containsEntry("nombre_visible", "Ana");
    }

    @Test
    void datosInvalidosDevuelvenLosErroresPorCampo() throws Exception {
        registrar("no-es-correo", "1a", "", "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.correo").value("El correo no es válido."))
                .andExpect(jsonPath("$.errores.nombreUsuario").value(Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO))
                .andExpect(jsonPath("$.errores.nombreVisible").value("El nombre es obligatorio."))
                .andExpect(jsonPath("$.errores.contrasena").value("La contraseña debe tener entre 8 y 64 caracteres."));
    }

    @Test
    void losNombresReservadosNoSeAceptan() throws Exception {
        registrar("ana@correo.cr", "Admin", "Ana", CLAVE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_RESERVADO"));
    }

    @Test
    void unCorreoVerificadoOUnNombreEnUsoDan409() throws Exception {
        crearUsuario("ana");

        registrar("ana@correo.cr", "otra", "Otra", CLAVE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CORREO_EN_USO"));
        registrar("otra@correo.cr", "ana", "Otra", CLAVE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_EN_USO"));
    }

    @Test
    void registrarseDeNuevoConUnCorreoSinVerificarReemplazaLaCuentaVieja() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        String tokenViejo = tokenDelCorreo("ana@correo.cr");

        registrar("ana@correo.cr", "ana_mora", "Ana Mora", "otra-clave-123").andExpect(status().isCreated());

        verificar(tokenViejo).andExpect(status().isUnprocessableContent());
        verificar(tokenDelCorreo("ana@correo.cr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana_mora"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isEqualTo(1);
    }

    @Test
    void unaContrasenaDeMasDe72BytesSeRechazaSinErrorInterno() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", "ñ".repeat(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.contrasena").value("La contraseña es demasiado larga."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isZero();
    }

    @Test
    void registrosSimultaneosDejanUnaSolaCuenta() throws Exception {
        List<Integer> estados = enParalelo(6,
                () -> registrar("ana@correo.cr", "ana", "Ana", CLAVE).andReturn().getResponse().getStatus());

        assertThat(estados).allMatch(estado -> estado == 201 || estado == 409).contains(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isEqualTo(1);
    }

    @Test
    void reenviarMandaUnEnlaceNuevoEInvalidaElAnterior() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        String viejo = tokenDelCorreo("ana@correo.cr");
        reloj.avanzar(Duration.ofMinutes(1));

        reenviar(" ANA@correo.cr").andExpect(status().isNoContent());

        assertThat(buzon.para("ana@correo.cr")).hasSize(2);
        verificar(viejo).andExpect(status().isUnprocessableContent());
        verificar(tokenDelCorreo("ana@correo.cr")).andExpect(status().isOk());
    }

    @Test
    void reenviarRespetaLaEsperaYNoRevelaNada() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        crearUsuario("luis");

        reenviar("ana@correo.cr").andExpect(status().isNoContent());
        reenviar("luis@correo.cr").andExpect(status().isNoContent());
        reenviar("nadie@correo.cr").andExpect(status().isNoContent());

        assertThat(buzon.todos()).hasSize(1);
    }

    private String tokenDelCorreo(String correo) {
        return BuzonPrueba.tokenDe(buzon.ultimoPara(correo));
    }

    private ResultActions registrar(String correo, String nombreUsuario, String nombreVisible, String contrasena)
            throws Exception {
        return mvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s", "nombreUsuario": "%s", "nombreVisible": "%s", "contrasena": "%s"}
                """.formatted(correo, nombreUsuario, nombreVisible, contrasena)));
    }

    private ResultActions verificar(String token) throws Exception {
        return mvc.perform(post("/api/auth/verificar").contentType(MediaType.APPLICATION_JSON).content("""
                {"token": "%s"}
                """.formatted(token)));
    }

    private ResultActions reenviar(String correo) throws Exception {
        return mvc.perform(post("/api/auth/reenviar-verificacion").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s"}
                """.formatted(correo)));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"identificador": "%s", "contrasena": "%s"}
                """.formatted(identificador, contrasena)));
    }
}
```

Archivo: `backend/src/test/java/com/hellocr/usuarios/LimpiezaCuentasTest.java`

```java
package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LimpiezaCuentasTest extends PruebaIntegracion {

    @Autowired
    private LimpiezaCuentas limpieza;

    @Test
    void borraLasCuentasSinVerificarDeMasDeSieteDias() {
        crearUsuarioSinVerificar("vieja");
        crearUsuario("verificada");
        reloj.avanzar(Duration.ofDays(3));
        crearUsuarioSinVerificar("nueva");
        reloj.avanzar(Duration.ofDays(4).plusMinutes(1));

        assertThat(limpieza.borrarSinVerificar()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT nombre_usuario FROM usuarios ORDER BY nombre_usuario", String.class))
                .containsExactly("nueva", "verificada");
    }
}
```

- [ ] **Step 2: Ver que fallan**

Run: `cd backend && ./mvnw -q test -Dtest='RegistroControllerTest,LimpiezaCuentasTest'`
Expected: FAIL de compilación — `cannot find symbol: class LimpiezaCuentas`.

- [ ] **Step 3: Errores de usuario y limpieza**

Archivo: `backend/src/main/java/com/hellocr/usuarios/ErroresUsuario.java`

```java
package com.hellocr.usuarios;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/** Errores que comparten el registro y la edición del perfil. */
public final class ErroresUsuario {

    private ErroresUsuario() {
    }

    public static ErrorNegocio correoEnUso() {
        return new ErrorNegocio(CodigoError.CORREO_EN_USO, "Ya existe una cuenta con ese correo.");
    }

    public static ErrorNegocio nombreUsuarioEnUso() {
        return new ErrorNegocio(CodigoError.NOMBRE_USUARIO_EN_USO, "Ese nombre de usuario ya está en uso.");
    }

    public static ErrorNegocio nombreUsuarioReservado() {
        return new ErrorNegocio(CodigoError.NOMBRE_USUARIO_RESERVADO, "Ese nombre de usuario no está disponible.");
    }

    public static ErrorNegocio noEncontrado() {
        return new ErrorNegocio(CodigoError.NO_ENCONTRADO, "No encontramos a esa persona.");
    }

    /** Dos requests simultáneos pasaron la verificación previa: la restricción única de la base decide. */
    public static RuntimeException traducirDuplicado(DataIntegrityViolationException error) {
        String mensaje = String.valueOf(NestedExceptionUtils.getMostSpecificCause(error).getMessage());
        if (mensaje.contains("usuarios_correo_unico")) {
            return correoEnUso();
        }
        if (mensaje.contains("usuarios_nombre_usuario_unico")) {
            return nombreUsuarioEnUso();
        }
        return error;
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/CuentasProperties.java`

```java
package com.hellocr.usuarios;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.cuentas")
@Validated
public record CuentasProperties(@Min(1) int diasSinVerificar) {
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/LimpiezaCuentas.java`

```java
package com.hellocr.usuarios;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Libera el correo y el @usuario de las cuentas que nadie verificó a tiempo. */
@Component
public class LimpiezaCuentas {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaCuentas.class);

    private final UsuarioRepository usuarios;
    private final CuentasProperties propiedades;
    private final Clock clock;

    public LimpiezaCuentas(UsuarioRepository usuarios, CuentasProperties propiedades, Clock clock) {
        this.usuarios = usuarios;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Todos los días a las 4:10. */
    @Scheduled(cron = "0 10 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int borrarSinVerificar() {
        Instant limite = clock.instant().minus(Duration.ofDays(propiedades.diasSinVerificar()));
        int borradas = usuarios.borrarSinVerificarCreadosAntesDe(limite);
        if (borradas > 0) {
            log.info("Se borraron {} cuentas sin verificar", borradas);
        }
        return borradas;
    }
}
```

- [ ] **Step 4: DTOs del registro**

Archivo: `backend/src/main/java/com/hellocr/auth/SolicitudRegistro.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SolicitudRegistro(
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 255, message = "El correo es demasiado largo.")
        String correo,

        @NotBlank(message = "El nombre de usuario es obligatorio.")
        @Pattern(regexp = Usuario.FORMATO_NOMBRE_USUARIO, message = Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO)
        String nombreUsuario,

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 50, message = "El nombre puede tener hasta 50 caracteres.")
        String nombreVisible,

        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 64, message = "La contraseña debe tener entre 8 y 64 caracteres.")
        String contrasena) {

    /** Se normaliza antes de validar: el autocompletado del celular agrega espacios y mayúsculas. */
    public SolicitudRegistro {
        correo = correo == null ? null : Usuario.normalizarCorreo(correo);
        nombreUsuario = nombreUsuario == null ? null : Usuario.normalizarNombreUsuario(nombreUsuario);
        nombreVisible = nombreVisible == null ? null : nombreVisible.trim();
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RespuestaRegistro.java`

```java
package com.hellocr.auth;

/** El frontend muestra "Revisá tu correo" con esta dirección. */
public record RespuestaRegistro(String correo) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/SolicitudToken.java`

```java
package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;

public record SolicitudToken(@NotBlank(message = "Falta el token del enlace.") String token) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/SolicitudCorreo.java`

```java
package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SolicitudCorreo(
        @NotBlank(message = "El correo es obligatorio.") @Email(message = "El correo no es válido.") String correo) {

    public SolicitudCorreo {
        correo = correo == null ? null : Usuario.normalizarCorreo(correo);
    }
}
```

- [ ] **Step 5: Servicio y controlador**

Archivo: `backend/src/main/java/com/hellocr/auth/RegistroService.java`

```java
package com.hellocr.auth;

import com.hellocr.correo.CorreosDeCuenta;
import com.hellocr.usuarios.ErroresUsuario;
import com.hellocr.usuarios.GeneradorCodigos;
import com.hellocr.usuarios.NombresReservados;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistroService {

    private final UsuarioRepository usuarios;
    private final GeneradorCodigos codigos;
    private final PasswordEncoder passwordEncoder;
    private final TokenCorreoService tokensCorreo;
    private final CorreosDeCuenta correos;
    private final SesionService sesiones;
    private final Clock clock;

    public RegistroService(UsuarioRepository usuarios, GeneradorCodigos codigos, PasswordEncoder passwordEncoder,
            TokenCorreoService tokensCorreo, CorreosDeCuenta correos, SesionService sesiones, Clock clock) {
        this.usuarios = usuarios;
        this.codigos = codigos;
        this.passwordEncoder = passwordEncoder;
        this.tokensCorreo = tokensCorreo;
        this.correos = correos;
        this.sesiones = sesiones;
        this.clock = clock;
    }

    /**
     * Crea la cuenta sin verificar y envía el enlace. Si el correo pertenecía a una cuenta sin verificar,
     * esa cuenta se reemplaza: así nadie puede bloquear un correo ajeno registrándose con él.
     */
    @Transactional
    public RespuestaRegistro registrar(SolicitudRegistro solicitud) {
        Contrasenas.validarLongitud(solicitud.contrasena(), "contrasena");
        if (NombresReservados.contiene(solicitud.nombreUsuario())) {
            throw ErroresUsuario.nombreUsuarioReservado();
        }
        usuarios.borrarSinVerificarPorCorreo(solicitud.correo());
        if (usuarios.existsByCorreo(solicitud.correo())) {
            throw ErroresUsuario.correoEnUso();
        }
        if (usuarios.existsByNombreUsuario(solicitud.nombreUsuario())) {
            throw ErroresUsuario.nombreUsuarioEnUso();
        }
        Usuario usuario = new Usuario(solicitud.correo(), solicitud.nombreUsuario(), solicitud.nombreVisible(),
                passwordEncoder.encode(solicitud.contrasena()), codigos.codigoInvitacion(), clock.instant());
        try {
            usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException carrera) {
            throw ErroresUsuario.traducirDuplicado(carrera);
        }
        correos.enviarVerificacion(usuario, tokensCorreo.emitir(usuario, PropositoToken.VERIFICACION));
        return new RespuestaRegistro(usuario.getCorreo());
    }

    /** Verifica el correo y abre sesión, para que la persona entre directo desde el enlace. */
    @Transactional
    public SesionService.Sesion verificar(String token) {
        Usuario usuario = tokensCorreo.consumir(token, PropositoToken.VERIFICACION);
        usuario.verificarCorreo(clock.instant());
        return sesiones.abrirSesion(usuario);
    }

    /** No revela si la cuenta existe: siempre termina bien. */
    @Transactional
    public void reenviarVerificacion(String correo) {
        usuarios.findByCorreo(correo)
                .filter(usuario -> !usuario.correoVerificado())
                .filter(usuario -> tokensCorreo.puedeEmitir(usuario, PropositoToken.VERIFICACION))
                .ifPresent(usuario -> correos.enviarVerificacion(usuario,
                        tokensCorreo.emitir(usuario, PropositoToken.VERIFICACION)));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RegistroController.java`

```java
package com.hellocr.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistroController {

    private final RegistroService registro;
    private final CookieRefresh cookie;

    public RegistroController(RegistroService registro, CookieRefresh cookie) {
        this.registro = registro;
        this.cookie = cookie;
    }

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public RespuestaRegistro registrar(@Valid @RequestBody SolicitudRegistro solicitud) {
        return registro.registrar(solicitud);
    }

    @PostMapping("/verificar")
    public ResponseEntity<RespuestaSesion> verificar(@Valid @RequestBody SolicitudToken solicitud) {
        return cookie.responder(HttpStatus.OK, registro.verificar(solicitud.token()));
    }

    @PostMapping("/reenviar-verificacion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reenviar(@Valid @RequestBody SolicitudCorreo solicitud) {
        registro.reenviarVerificacion(solicitud.correo());
    }
}
```

- [ ] **Step 6: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): registro con verificación de correo, reenvío y limpieza de cuentas sin verificar"
```

---

### Task 12: Recuperar la contraseña

**Files:**
- Create en `backend/src/main/java/com/hellocr/auth/`: `SolicitudRestablecer.java`, `RecuperacionService.java`, `RecuperacionController.java`
- Test: `backend/src/test/java/com/hellocr/auth/RecuperacionControllerTest.java`

**Interfaces:**
- Consumes: `UsuarioRepository` (Task 4); `PasswordEncoder` (Task 5); `RefreshTokenService.revocarTodos` (Task 6); `CorreosDeCuenta` (Task 7); `TokenCorreoService` (Task 8); `Contrasenas` (Task 10); `SolicitudCorreo` (Task 11).
- Produces (API): `POST /api/auth/recuperar` `{correo}` → 204 siempre; `POST /api/auth/restablecer` `{token, contrasenaNueva}` → 204. Restablecer cambia la contraseña, verifica el correo si hacía falta, revoca todas las sesiones y publica `SesionesRevocadas`.

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/auth/RecuperacionControllerTest.java`

```java
package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.soporte.BuzonPrueba;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

@RecordApplicationEvents
class RecuperacionControllerTest extends PruebaIntegracion {

    private static final String NUEVA = "nueva-clave-123";

    @Autowired
    private ApplicationEvents eventos;
    @Autowired
    private TokenCorreoService tokensCorreo;

    @Test
    void recuperarEnviaUnEnlaceParaRestablecer() throws Exception {
        crearUsuario("ana");

        recuperar(" ANA@correo.cr ").andExpect(status().isNoContent());

        CorreoSaliente correo = buzon.ultimoPara("ana@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Restablecé tu contraseña de HelloCR");
        assertThat(correo.texto()).contains("@ana").contains("http://localhost:5173/restablecer?token=");
    }

    @Test
    void recuperarNoRevelaSiLaCuentaExisteYRespetaLaEspera() throws Exception {
        recuperar("nadie@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).isEmpty();

        crearUsuario("ana");
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).hasSize(1);

        reloj.avanzar(Duration.ofMinutes(1));
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).hasSize(2);
    }

    @Test
    void restablecerCambiaLaContrasenaYCierraLasSesiones() throws Exception {
        SesionPrueba sesion = sesionDe("ana");
        recuperar("ana@correo.cr");

        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isNoContent());

        login("ana", CLAVE).andExpect(status().isUnauthorized());
        login("ana", NUEVA).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie())).andExpect(status().isUnauthorized());
        assertThat(eventos.stream(SesionesRevocadas.class))
                .containsExactly(new SesionesRevocadas(UUID.fromString(sesion.usuarioId())));
    }

    @Test
    void restablecerVerificaUnaCuentaSinVerificar() throws Exception {
        crearUsuarioSinVerificar("ana");
        recuperar("ana@correo.cr");

        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isNoContent());

        login("ana", NUEVA).andExpect(status().isOk());
    }

    @Test
    void elEnlaceSirveUnaSolaVezYVenceEnUnaHora() throws Exception {
        crearUsuario("ana");
        recuperar("ana@correo.cr");
        String token = tokenDelCorreo();

        restablecer(token, NUEVA).andExpect(status().isNoContent());
        restablecer(token, "otra-clave-456")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));

        reloj.avanzar(Duration.ofMinutes(1));
        recuperar("ana@correo.cr");
        String segundo = tokenDelCorreo();
        reloj.avanzar(Duration.ofHours(1));
        restablecer(segundo, "otra-clave-456").andExpect(status().isUnprocessableContent());
    }

    @Test
    void unEnlaceDeVerificacionNoSirveParaRestablecer() throws Exception {
        Usuario ana = crearUsuarioSinVerificar("ana");
        String deVerificacion = tokensCorreo.emitir(ana, PropositoToken.VERIFICACION);

        restablecer(deVerificacion, NUEVA).andExpect(status().isUnprocessableContent());
    }

    @Test
    void unaContrasenaNuevaInvalidaNoGastaElEnlace() throws Exception {
        crearUsuario("ana");
        recuperar("ana@correo.cr");
        String token = tokenDelCorreo();

        restablecer(token, "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contrasenaNueva").value("La contraseña debe tener entre 8 y 64 caracteres."));
        restablecer(token, "ñ".repeat(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contrasenaNueva").value("La contraseña es demasiado larga."));
        restablecer(token, NUEVA).andExpect(status().isNoContent());
    }

    private String tokenDelCorreo() {
        return BuzonPrueba.tokenDe(buzon.ultimoPara("ana@correo.cr"));
    }

    private ResultActions recuperar(String correo) throws Exception {
        return mvc.perform(post("/api/auth/recuperar").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s"}
                """.formatted(correo)));
    }

    private ResultActions restablecer(String token, String contrasenaNueva) throws Exception {
        return mvc.perform(post("/api/auth/restablecer").contentType(MediaType.APPLICATION_JSON).content("""
                {"token": "%s", "contrasenaNueva": "%s"}
                """.formatted(token, contrasenaNueva)));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"identificador": "%s", "contrasena": "%s"}
                """.formatted(identificador, contrasena)));
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=RecuperacionControllerTest`
Expected: FAIL — los endpoints todavía no existen (por ejemplo `Status expected:<204> but was:<404>`).

- [ ] **Step 3: Implementación**

Archivo: `backend/src/main/java/com/hellocr/auth/SolicitudRestablecer.java`

```java
package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudRestablecer(
        @NotBlank(message = "Falta el token del enlace.") String token,
        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 64, message = "La contraseña debe tener entre 8 y 64 caracteres.")
        String contrasenaNueva) {
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RecuperacionService.java`

```java
package com.hellocr.auth;

import com.hellocr.correo.CorreosDeCuenta;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecuperacionService {

    private final UsuarioRepository usuarios;
    private final TokenCorreoService tokensCorreo;
    private final CorreosDeCuenta correos;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    public RecuperacionService(UsuarioRepository usuarios, TokenCorreoService tokensCorreo, CorreosDeCuenta correos,
            PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens, Clock clock) {
        this.usuarios = usuarios;
        this.tokensCorreo = tokensCorreo;
        this.correos = correos;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** No revela si la cuenta existe: siempre termina bien. */
    @Transactional
    public void solicitar(String correo) {
        usuarios.findByCorreo(correo)
                .filter(usuario -> tokensCorreo.puedeEmitir(usuario, PropositoToken.RECUPERACION))
                .ifPresent(usuario -> correos.enviarRecuperacion(usuario,
                        tokensCorreo.emitir(usuario, PropositoToken.RECUPERACION)));
    }

    /**
     * La contraseña se valida antes de consumir el token, para no gastar el enlace con un error de tipeo.
     * Abrir el enlace prueba que la persona es dueña del correo, así que también lo verifica.
     */
    @Transactional
    public void restablecer(SolicitudRestablecer solicitud) {
        Contrasenas.validarLongitud(solicitud.contrasenaNueva(), "contrasenaNueva");
        Usuario usuario = tokensCorreo.consumir(solicitud.token(), PropositoToken.RECUPERACION);
        usuario.cambiarContrasena(passwordEncoder.encode(solicitud.contrasenaNueva()));
        usuario.verificarCorreo(clock.instant());
        refreshTokens.revocarTodos(usuario.getId());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/auth/RecuperacionController.java`

```java
package com.hellocr.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RecuperacionController {

    private final RecuperacionService recuperacion;

    public RecuperacionController(RecuperacionService recuperacion) {
        this.recuperacion = recuperacion;
    }

    @PostMapping("/recuperar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recuperar(@Valid @RequestBody SolicitudCorreo solicitud) {
        recuperacion.solicitar(solicitud.correo());
    }

    @PostMapping("/restablecer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restablecer(@Valid @RequestBody SolicitudRestablecer solicitud) {
        recuperacion.restablecer(solicitud);
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): recuperar la contraseña por correo y cerrar todas las sesiones"
```

---

### Task 13: Perfil, búsqueda por @usuario e invitaciones

**Files:**
- Create en `backend/src/main/java/com/hellocr/usuarios/`: `PerfilPublico.java`, `SolicitudPerfil.java`, `NuevoCodigo.java`, `UsuarioService.java`, `UsuarioController.java`, `InvitacionController.java`
- Test: `backend/src/test/java/com/hellocr/usuarios/UsuarioControllerTest.java`

**Interfaces:**
- Consumes: `Usuario`, `UsuarioRepository`, `GeneradorCodigos`, `NombresReservados` (Task 4); `UsuarioAutenticado` (Task 5); `UsuarioPropio` (Task 10); `ErroresUsuario` (Task 11).
- Produces (API): `GET /api/usuarios/yo` → `UsuarioPropio`; `PATCH /api/usuarios/yo` `{nombreVisible?, info?, nombreUsuario?}` → `UsuarioPropio` (un campo en `null` no cambia; `info` vacío la borra); `POST /api/usuarios/yo/codigo-invitacion` → `{codigoInvitacion}`; `GET /api/usuarios/buscar?nombreUsuario=` → `PerfilPublico` o 404; `GET /api/usuarios/{id}` → `PerfilPublico` o 404; `GET /api/invitaciones/{codigo}` → `PerfilPublico` o 404. Solo se ven cuentas verificadas.
- Produces: `record PerfilPublico(UUID id, String nombreUsuario, String nombreVisible, String info)` con `static de(Usuario)` (el plan 3 agrega `fotoId`; el plan 2 lo usa en las conversaciones).

- [ ] **Step 1: Test**

Archivo: `backend/src/test/java/com/hellocr/usuarios/UsuarioControllerTest.java`

```java
package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UsuarioControllerTest extends PruebaIntegracion {

    @Test
    void yoDevuelveElPerfilPropio() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        mvc.perform(get("/api/usuarios/yo").with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ana.usuarioId()))
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.nombreVisible").value("Usuario ana"))
                .andExpect(jsonPath("$.codigoInvitacion").value(codigoDe("ana")));
    }

    @Test
    void actualizaElNombreYLaInfo() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreVisible": "  Ana Mora ", "info": "Pura vida"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreVisible").value("Ana Mora"))
                .andExpect(jsonPath("$.info").value("Pura vida"));
        actualizar(ana, """
                {"info": ""}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreVisible").value("Ana Mora"))
                .andExpect(jsonPath("$.info").value(nullValue()));
    }

    @Test
    void unNombreVisibleVacioOUnaInfoLargaSonInvalidos() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreVisible": "   ", "info": "%s"}
                """.formatted("a".repeat(141)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombreVisible").value("El nombre debe tener entre 1 y 50 caracteres."))
                .andExpect(jsonPath("$.errores.info").value("La info puede tener hasta 140 caracteres."));
    }

    @Test
    void cambiaElNombreDeUsuarioNormalizado() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreUsuario": " @Ana_Mora "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("ana_mora"));
        iniciarSesion("ana_mora");
    }

    @Test
    void noSePuedeTomarUnNombreAjenoNiReservado() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        crearUsuario("luis");

        actualizar(ana, "{\"nombreUsuario\": \"luis\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_EN_USO"));
        actualizar(ana, "{\"nombreUsuario\": \"soporte\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_RESERVADO"));
        actualizar(ana, "{\"nombreUsuario\": \"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombreUsuario").value(Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO));
        actualizar(ana, "{\"nombreUsuario\": \"@ANA\"}").andExpect(status().isOk());
    }

    @Test
    void regenerarElCodigoInvalidaElLinkAnterior() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        SesionPrueba luis = sesionDe("luis");
        String viejo = codigoDe("ana");

        String respuesta = mvc.perform(post("/api/usuarios/yo/codigo-invitacion").with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoInvitacion").value(not(viejo)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String nuevo = JsonPath.read(respuesta, "$.codigoInvitacion");

        assertThat(nuevo).matches("[2-9A-HJKMNP-Za-kmnp-z]{8}");
        mvc.perform(get("/api/invitaciones/{codigo}", viejo).with(con(luis)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        mvc.perform(get("/api/invitaciones/{codigo}", nuevo).with(con(luis)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("ana"));
    }

    @Test
    void buscarSoloEncuentraCoincidenciasExactasDeCuentasVerificadas() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        crearUsuario("luis");
        crearUsuarioSinVerificar("sofia");

        buscar(ana, " @Luis ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$.nombreVisible").value("Usuario luis"))
                .andExpect(jsonPath("$.correo").doesNotExist())
                .andExpect(jsonPath("$.codigoInvitacion").doesNotExist());
        buscar(ana, "lu")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No encontramos a esa persona."));
        buscar(ana, "sofia").andExpect(status().isNotFound());
    }

    @Test
    void elPerfilPublicoPorIdSoloMuestraCuentasVerificadas() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        Usuario luis = crearUsuario("luis");
        Usuario sofia = crearUsuarioSinVerificar("sofia");

        mvc.perform(get("/api/usuarios/{id}", luis.getId()).with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("luis"));
        mvc.perform(get("/api/usuarios/{id}", sofia.getId()).with(con(ana)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/usuarios/no-es-un-uuid").with(con(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void lasInvitacionesRequierenSesion() throws Exception {
        crearUsuario("ana");

        mvc.perform(get("/api/invitaciones/{codigo}", codigoDe("ana")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elTokenDeUnaCuentaBorradaYaNoSirve() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        jdbc.update("DELETE FROM usuarios");

        mvc.perform(get("/api/usuarios/yo").with(con(ana)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private String codigoDe(String nombreUsuario) {
        return jdbc.queryForObject("SELECT codigo_invitacion FROM usuarios WHERE nombre_usuario = ?", String.class,
                nombreUsuario);
    }

    private ResultActions actualizar(SesionPrueba sesion, String json) throws Exception {
        return mvc.perform(patch("/api/usuarios/yo").with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions buscar(SesionPrueba sesion, String nombreUsuario) throws Exception {
        return mvc.perform(get("/api/usuarios/buscar").param("nombreUsuario", nombreUsuario).with(con(sesion)));
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd backend && ./mvnw -q test -Dtest=UsuarioControllerTest`
Expected: FAIL de compilación o 404 — los endpoints todavía no existen.

- [ ] **Step 3: DTOs**

Archivo: `backend/src/main/java/com/hellocr/usuarios/PerfilPublico.java`

```java
package com.hellocr.usuarios;

import java.util.UUID;

/** Lo que cualquier usuario con sesión puede ver de otro. Sin correo ni código de invitación. */
public record PerfilPublico(UUID id, String nombreUsuario, String nombreVisible, String info) {

    public static PerfilPublico de(Usuario usuario) {
        return new PerfilPublico(usuario.getId(), usuario.getNombreUsuario(), usuario.getNombreVisible(),
                usuario.getInfo());
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/SolicitudPerfil.java`

```java
package com.hellocr.usuarios;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Todos los campos son opcionales: null significa "no cambiar". Una info vacía la borra. */
public record SolicitudPerfil(
        @Size(min = 1, max = 50, message = "El nombre debe tener entre 1 y 50 caracteres.")
        String nombreVisible,

        @Size(max = 140, message = "La info puede tener hasta 140 caracteres.")
        String info,

        @Pattern(regexp = Usuario.FORMATO_NOMBRE_USUARIO, message = Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO)
        String nombreUsuario) {

    public SolicitudPerfil {
        nombreVisible = nombreVisible == null ? null : nombreVisible.trim();
        info = info == null ? null : info.trim();
        nombreUsuario = nombreUsuario == null ? null : Usuario.normalizarNombreUsuario(nombreUsuario);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/NuevoCodigo.java`

```java
package com.hellocr.usuarios;

public record NuevoCodigo(String codigoInvitacion) {
}
```

- [ ] **Step 4: Servicio y controladores**

Archivo: `backend/src/main/java/com/hellocr/usuarios/UsuarioService.java`

```java
package com.hellocr.usuarios;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarios;
    private final GeneradorCodigos codigos;

    public UsuarioService(UsuarioRepository usuarios, GeneradorCodigos codigos) {
        this.usuarios = usuarios;
        this.codigos = codigos;
    }

    @Transactional(readOnly = true)
    public UsuarioPropio propio(UUID id) {
        return UsuarioPropio.de(cargar(id));
    }

    @Transactional
    public UsuarioPropio actualizar(UUID id, SolicitudPerfil solicitud) {
        Usuario usuario = cargar(id);
        if (solicitud.nombreVisible() != null) {
            usuario.cambiarNombreVisible(solicitud.nombreVisible());
        }
        if (solicitud.info() != null) {
            usuario.cambiarInfo(solicitud.info().isEmpty() ? null : solicitud.info());
        }
        String nombreUsuario = solicitud.nombreUsuario();
        if (nombreUsuario != null && !nombreUsuario.equals(usuario.getNombreUsuario())) {
            if (NombresReservados.contiene(nombreUsuario)) {
                throw ErroresUsuario.nombreUsuarioReservado();
            }
            if (usuarios.existsByNombreUsuario(nombreUsuario)) {
                throw ErroresUsuario.nombreUsuarioEnUso();
            }
            usuario.cambiarNombreUsuario(nombreUsuario);
        }
        try {
            usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException carrera) {
            throw ErroresUsuario.traducirDuplicado(carrera);
        }
        return UsuarioPropio.de(usuario);
    }

    /** El link y el QR anteriores dejan de funcionar. */
    @Transactional
    public NuevoCodigo regenerarCodigo(UUID id) {
        Usuario usuario = cargar(id);
        usuario.cambiarCodigoInvitacion(codigos.codigoInvitacion());
        return new NuevoCodigo(usuario.getCodigoInvitacion());
    }

    /** Solo coincidencia exacta, para que nadie pueda recorrer la lista de usuarios. */
    @Transactional(readOnly = true)
    public PerfilPublico buscar(String nombreUsuario) {
        return usuarios.verificadoPorNombreUsuario(Usuario.normalizarNombreUsuario(nombreUsuario))
                .map(PerfilPublico::de)
                .orElseThrow(ErroresUsuario::noEncontrado);
    }

    @Transactional(readOnly = true)
    public PerfilPublico publico(UUID id) {
        return usuarios.verificadoPorId(id).map(PerfilPublico::de).orElseThrow(ErroresUsuario::noEncontrado);
    }

    @Transactional(readOnly = true)
    public PerfilPublico porInvitacion(String codigo) {
        return usuarios.verificadoPorCodigo(codigo.trim())
                .map(PerfilPublico::de)
                .orElseThrow(ErroresUsuario::noEncontrado);
    }

    /** Si la cuenta ya no existe, el token que la nombra no sirve. */
    private Usuario cargar(UUID id) {
        return usuarios.findById(id).orElseThrow(
                () -> new ErrorNegocio(CodigoError.NO_AUTENTICADO, "La sesión expiró. Iniciá sesión de nuevo."));
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/UsuarioController.java`

```java
package com.hellocr.usuarios;

import com.hellocr.comun.UsuarioAutenticado;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService usuarios;

    public UsuarioController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/yo")
    public UsuarioPropio yo(@AuthenticationPrincipal Jwt jwt) {
        return usuarios.propio(UsuarioAutenticado.id(jwt));
    }

    @PatchMapping("/yo")
    public UsuarioPropio actualizar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SolicitudPerfil solicitud) {
        return usuarios.actualizar(UsuarioAutenticado.id(jwt), solicitud);
    }

    @PostMapping("/yo/codigo-invitacion")
    public NuevoCodigo regenerarCodigo(@AuthenticationPrincipal Jwt jwt) {
        return usuarios.regenerarCodigo(UsuarioAutenticado.id(jwt));
    }

    @GetMapping("/buscar")
    public PerfilPublico buscar(@RequestParam String nombreUsuario) {
        return usuarios.buscar(nombreUsuario);
    }

    @GetMapping("/{id}")
    public PerfilPublico publico(@PathVariable UUID id) {
        return usuarios.publico(id);
    }
}
```

Archivo: `backend/src/main/java/com/hellocr/usuarios/InvitacionController.java`

```java
package com.hellocr.usuarios;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** El frontend abre /i/{codigo} y pregunta acá de quién es la invitación. */
@RestController
@RequestMapping("/api/invitaciones")
public class InvitacionController {

    private final UsuarioService usuarios;

    public InvitacionController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/{codigo}")
    public PerfilPublico deQuienEs(@PathVariable String codigo) {
        return usuarios.porInvitacion(codigo);
    }
}
```

- [ ] **Step 5: Ver que pasa**

Run: `cd backend && ./mvnw -q test`
Expected: sin errores.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): perfil, búsqueda exacta por @usuario e invitaciones"
```

---

### Task 14: Verificación final y README

**Files:**
- Create: `README.md`
- Create (local, ignorado por git): `backend/src/main/resources/application-local.yml`

- [ ] **Step 1: Suite completa**

Run: `cd backend && ./mvnw test`
Expected: `Tests run: 108, Failures: 0, Errors: 0, Skipped: 0` y `BUILD SUCCESS`.

- [ ] **Step 2: Configuración local**

```bash
cp backend/src/main/resources/application-local.example.yml backend/src/main/resources/application-local.yml
```

Pedirle a la persona usuaria que cambie en ese archivo el secreto del JWT (`openssl rand -base64 48`). Verificar que git lo ignora: `git status --short` no debe listar `application-local.yml`.

- [ ] **Step 3: Probar la app de punta a punta**

Arrancar en segundo plano: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. Esperar el log `Started HelloCrApplication`.

```bash
curl -s -i -X POST localhost:8080/api/auth/registro -H "Content-Type: application/json" -d '{"correo":"yo@correo.cr","nombreUsuario":"yo_mismo","nombreVisible":"Yo","contrasena":"clave-de-prueba"}'
```

Expected: `HTTP/1.1 201` con `{"correo":"yo@correo.cr"}`. En el log de la app aparece `Correo para yo@correo.cr — Verificá tu correo en HelloCR` con un enlace `http://localhost:5173/verificar?token=...`. Copiar ese token:

```bash
curl -s -i -X POST localhost:8080/api/auth/verificar -H "Content-Type: application/json" -d '{"token":"<el token del log>"}'
```

Expected: `HTTP/1.1 200`, un header `Set-Cookie: refresh_token=...; Path=/api/auth; Max-Age=2592000; ...; HttpOnly; SameSite=Strict` y un cuerpo con `accessToken` y `"nombreUsuario":"yo_mismo"`.

```bash
curl -s localhost:8080/api/usuarios/yo
```

Expected: `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Tenés que iniciar sesión.","codigo":"NO_AUTENTICADO"}`. Después, detener la app.

- [ ] **Step 4: README**

Archivo: `README.md`

````markdown
# HelloCR

App de mensajería al estilo de WhatsApp, instalable como PWA: chats 1 a 1 y grupos, imágenes y archivos,
estados de entrega, presencia y notificaciones push.

- Diseño: [`docs/superpowers/specs/2026-09-27-hellocr-design.md`](docs/superpowers/specs/2026-09-27-hellocr-design.md)
- Planes de implementación: [`docs/superpowers/plans/`](docs/superpowers/plans/)

| Carpeta | Tecnología |
|---|---|
| `backend/` | Java 25 · Spring Boot 4 · Maven · PostgreSQL 18 · Flyway |
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

## Tests

```bash
cd backend
./mvnw test
```

Corren contra la base `hellocr_test`, que se vacía antes de cada test.
````

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: README con configuración, ejecución y tests"
```
