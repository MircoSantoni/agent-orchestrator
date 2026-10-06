# Agent Orchestrator

Control Plane para coordinar personas y agentes sobre un proyecto compartido. PostgreSQL conserva tareas, presencia, intents, contexto, mensajes y actividad. REST cambia el estado; SSE notifica a Bridges simulados; `/mcp` expone herramientas para clientes MCP.

**Manual de uso:** [docs/manual-uso.md](docs/manual-uso.md), con acceso al despliegue, alta inicial, panel, Bridge, mensajería, tareas, contexto y MCP.

## Estado

El MVP cubre claim atómico, dependencias y subtareas, intents con detección de solapamiento, propuestas con aprobación humana y mensajería. El panel permite crear organizaciones, proyectos, tareas, contexto y mensajes; también muestra la red de workspaces y el flujo de servicios. El despliegue AWS está activo en `us-east-1` mediante dos stacks CloudFormation y CodeBuild, con URL pública `https://d3tlsuzwwbes8y.cloudfront.net`. Claude puede conectarse directamente al MCP remoto con una credencial personal revocable; el Bridge local es opcional. El servidor coordina agentes, pero no ejecuta modelos por sí mismo.

## Requisitos locales

- Java 25 y Maven 3.9.
- Docker Desktop para PostgreSQL, Compose y pruebas Testcontainers.

```powershell
docker compose up -d postgres
$env:JAVA_HOME = 'ruta-al-jdk-25'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn test
mvn package -DskipTests
$env:DATABASE_PASSWORD = 'local-dev-only'
$env:SPRING_PROFILES_ACTIVE = 'dev'
java -jar control-plane/target/control-plane-0.1.0-SNAPSHOT.jar
```

Como alternativa, `docker compose --profile app up --build -d` inicia PostgreSQL y Control Plane en contenedores. Abrí `http://127.0.0.1:8080/` para el panel. En el perfil `dev`, la identidad de demostración se indica con `X-Dev-User`; este perfil debe quedar restringido al equipo local.

## Bridge local opcional

Después de crear un proyecto y sus miembros, iniciá una instancia por persona, con `BRIDGE_PORT`, `BRIDGE_WORKSPACE_NAME` y `BRIDGE_OWNER_ID` propios:

```powershell
$env:BRIDGE_PROJECT_ID = '<project-uuid>'
$env:BRIDGE_OWNER_ID = 'mirco'
$env:BRIDGE_OWNER_NAME = 'Mirco'
$env:BRIDGE_WORKSPACE_NAME = 'mirco-pc'
$env:BRIDGE_PORT = '8091'
java -jar agent-bridge/target/agent-bridge-0.1.0-SNAPSHOT.jar
```

El Bridge escucha solo en `127.0.0.1`. Expone `GET /local/state`, `GET /local/events`, `POST /local/agents`, operaciones de tareas, intents y mensajes. Envía heartbeat de workspace, orquestador y agentes registrados; al reconectar SSE reconstruye el estado desde REST. `GET /local/state` incluye `sseConnected`, `lastSyncAt` y el snapshot.

Para el servicio remoto, configurá `CONTROL_PLANE_URL` con la URL HTTPS y estas variables obtenidas tras desplegar Cognito: `BRIDGE_OIDC_AUTHORIZATION_URL` (`<cognito-domain>/oauth2/authorize`), `BRIDGE_OIDC_TOKEN_URL` (`<cognito-domain>/oauth2/token`), `BRIDGE_OIDC_CLIENT_ID`, `BRIDGE_OIDC_SCOPE` (`openid profile <api-scope>`) y `BRIDGE_OIDC_RESOURCE` (URL pública del API). El Bridge abre el navegador con Authorization Code + PKCE y recibe el callback en `http://127.0.0.1:8765/callback`. Mantiene los tokens solo en memoria y renueva el access token mientras está abierto. `BRIDGE_TOKEN` permite aportar un token externo para diagnósticos.

## Identidad y aprobación

En producción, Spring valida la firma JWT mediante el JWKS del issuer OIDC, emisor, vigencia, `token_use=access`, cliente autorizado, audience y scope. Cada operación comprueba membresía y ownership en PostgreSQL. Solo el cliente humano puede aprobar o rechazar propuestas. El panel usa un cliente público Cognito con PKCE; permite ver propuestas pendientes y decidirlas. Los administradores de proyecto pueden invitar miembros por correo; el servidor crea la cuenta Cognito si todavía no existe y guarda el identificador interno. Al cerrar o recargar la pestaña, los tokens en memoria se pierden y se inicia sesión de nuevo.

## MCP remoto

`POST /mcp` soporta la negociación `2025-11-25` (`initialize`, `tools/list`, `tools/call`) y el subconjunto stateless `2026-07-28` (`server/discover`, `tools/list`, `tools/call`). La versión 2025 se verificó con `io.modelcontextprotocol.sdk:mcp:2.0.1`; la versión 2026 tiene pruebas de protocolo HTTP. Un agente remoto puede registrarse, crear y editar tareas, gestionar dependencias y estados, renombrar o retirar sus workspaces, intercambiar mensajes y proponer contexto. El panel muestra el cuerpo completo de los mensajes a los miembros del proyecto; solo el destinatario puede confirmar mensajes o aceptar transferencias. Ninguna herramienta permite aprobar propuestas.

En producción, el panel crea credenciales personales de 256 bits para MCP. Solo se almacena su hash, expiran a los 90 días, se pueden revocar y únicamente se aceptan en `/mcp`; cada operación sigue comprobando membresía y ownership. Claude puede enviarlas en el encabezado `Authorization: Bearer`. El endpoint también anuncia `/.well-known/oauth-protected-resource` para clientes OAuth compatibles con el callback estático de Cognito. Las capacidades MCP se limitan a herramientas; no hay recursos, prompts, sesiones ni servidor de autorización propio. Los clientes web con `Origin` requieren inclusión exacta en `APP_MCP_ALLOWED_ORIGINS`.

## AWS

La topología, variables, bootstrap, despliegue y rollback están documentados en `infra/README.md`. CloudFormation creó una URL HTTPS `*.cloudfront.net`, sin dominio propio. La cuenta personal usa el perfil `personal-AdminDev` en `us-east-1`. El repositorio privado está en [GitHub](https://github.com/MircoSantoni/agent-orchestrator); CodeBuild sigue usando un ZIP privado de S3 como fuente.

## Licencia

El código propio de este repositorio se distribuye bajo **GNU Affero General Public License v3.0 only** (`AGPL-3.0-only`). Véase [LICENSE](LICENSE). Las dependencias de terceros conservan sus respectivas licencias.

## Verificación

`mvn test` usa PostgreSQL real en Testcontainers. Cubre permisos, claims concurrentes, propuestas, handoff, intents, SSE con `Last-Event-ID`, cliente MCP oficial y contrato HTTP MCP. Las plantillas CloudFormation se validan sin tocar una cuenta AWS mediante cfn-lint. El despliegue se verificó con dos réplicas, OAuth real de Cognito, dos usuarios de prueba, REST, SSE y MCP. Los usuarios de prueba se eliminaron después de la auditoría.
