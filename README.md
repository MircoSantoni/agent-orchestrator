# Agent Orchestrator

Control Plane y Agent Bridge simulado para coordinar personas y agentes que trabajan en un mismo proyecto. El servidor persiste el estado en PostgreSQL; REST realiza cambios y SSE distribuye notificaciones.

## Estado del MVP

El primer flujo funciona localmente con tres Bridges simulados: registro, presencia, tareas, claim atómico, resource intents, conflicto WRITE/WRITE, mensajería, propuesta y aprobación humana, handoff a subtarea y activity stream. El Control Plane también expone una entrada MCP remota. La integración real con Claude y el despliegue AWS quedan pendientes.

## Requisitos

- Java 25 y Maven 3.9 para compilar y ejecutar los Bridges.
- Docker para PostgreSQL y las pruebas Testcontainers.

## Ejecutar localmente

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

También se puede ejecutar el servidor en Docker después de generar la imagen:

```powershell
docker compose --profile app up --build -d
```

El perfil `dev` usa `X-Dev-User` únicamente para demostración local. Nunca se debe activar en un servicio accesible por Internet. Fuera de ese perfil, la API exige un JWT firmado de un emisor OIDC configurado mediante `OIDC_ISSUER_URI`; `app.security.human-client-id` y `app.security.bridge-client-id` identifican los dos clientes permitidos. La aprobación exige el cliente humano. La membresía y el ownership se verifican en PostgreSQL.

## Bridge simulado

Cada instancia necesita un `BRIDGE_PROJECT_ID`, un `BRIDGE_OWNER_ID` ya incorporado al proyecto, un nombre de workspace y un puerto local diferente:

```powershell
$env:BRIDGE_PROJECT_ID = '<project-uuid>'
$env:BRIDGE_OWNER_ID = 'mirco'
$env:BRIDGE_OWNER_NAME = 'Mirco'
$env:BRIDGE_WORKSPACE_NAME = 'mirco-pc'
$env:BRIDGE_PORT = '8091'
java -jar agent-bridge/target/agent-bridge-0.1.0-SNAPSHOT.jar
```

El Bridge escucha solo en `127.0.0.1`. Para producción, `CONTROL_PLANE_URL` debe apuntar al servicio HTTPS y `BRIDGE_TOKEN` debe contener un access token válido obtenido por el usuario. La adquisición y renovación automática con PKCE aún no está implementada.

Rutas locales principales:

- `GET /local/state`, `GET /local/events`
- `POST /local/agents`, `PATCH /local/agents/{id}/status`
- `POST /local/tasks/{id}/claim`, `/start`, `/complete`
- `POST /local/resource-intents`, `POST /local/messages`

El Control Plane expone `/api/v1` y `/actuator/health`.

## MCP remoto

El endpoint `POST /mcp` expone un subconjunto de MCP Streamable HTTP `2026-07-28`: `server/discover`, `tools/list` y `tools/call`. Es stateless y reutiliza la autenticación JWT y los permisos de proyecto y workspace de la API REST. En modo `dev`, se puede usar `X-Dev-User` solo para pruebas locales. El cliente debe enviar `MCP-Protocol-Version`, `Mcp-Method` y, en `tools/call`, `Mcp-Name`; además debe incluir la versión y sus capacidades en `params._meta`. No hay compatibilidad con clientes que solo implementen el handshake de 2025.

Herramientas disponibles: `get_project`, `list_tasks`, `list_agents`, `list_context`, `list_resource_intents`, `list_inbox`, `claim_task`, `announce_resource_intent`, `propose_context`, `send_message` y `accept_handoff`. Cada llamada recibe IDs explícitos; los servicios comprueban la membresía y el ownership. Las propuestas quedan pendientes: no se expone aprobación humana a los agentes.

Ejemplo local para descubrir el servidor:

```powershell
$body = @{
  jsonrpc = '2.0'; id = 1; method = 'server/discover'
  params = @{ _meta = @{
    'io.modelcontextprotocol/protocolVersion' = '2026-07-28'
    'io.modelcontextprotocol/clientCapabilities' = @{}
  }}
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/mcp' -Method Post -ContentType 'application/json' `
  -Headers @{ 'MCP-Protocol-Version' = '2026-07-28'; 'Mcp-Method' = 'server/discover'; 'X-Dev-User' = 'local-user' } `
  -Body $body
```

Si un cliente web envía `Origin`, configúrelo en `APP_MCP_ALLOWED_ORIGINS` como lista de orígenes exactos separados por comas. Sin esa lista, las peticiones con `Origin` se rechazan. El despliegue público requiere HTTPS y un emisor OIDC configurado. La conexión OAuth interactiva desde clientes MCP y la compatibilidad con protocolos anteriores siguen pendientes.

## Pruebas

`mvn test` levanta PostgreSQL con Testcontainers y comprueba claim concurrente, conflicto auditable, propuesta pendiente, aprobación y handoff a subtarea. El flujo manual con tres Bridges se verificó sobre PostgreSQL en Docker.

## Próximos pasos

Completar autenticación interactiva Cognito + PKCE para Bridges, personas y clientes MCP, verificar interoperabilidad con clientes MCP reales, ampliar pruebas de SSE y reconexión, definir infraestructura AWS como código y desplegar. El detalle está en `BACKLOG.md`.
