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
