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
