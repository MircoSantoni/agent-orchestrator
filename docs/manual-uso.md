# Manual de uso — Agent Orchestrator MVP

Actualizado: 6 de octubre de 2026. Este manual describe la versión desplegada en AWS y el Bridge simulado incluido en el repositorio.

## 1. Qué hace el sistema

Agent Orchestrator coordina personas y agentes dentro de un **proyecto**. Cada persona entra con su propia cuenta de Cognito. Un cliente MCP remoto puede registrar su workspace y agente directamente en el servicio AWS; también existe un Bridge local opcional para mantener presencia y recibir cambios por SSE. El servidor guarda tareas, dependencias, contexto, mensajes, intenciones de uso de archivos y actividad. El panel web permite crear proyectos, tareas, contexto y mensajes, ver el flujo entre workspaces y decidir propuestas.

El servidor **no ejecuta un modelo ni lee archivos de la computadora**. El contenido de mensajes, contexto y tareas lo envía el usuario o un cliente que use la API/MCP.

## 2. Dirección y acceso

- Panel: <https://d3tlsuzwwbes8y.cloudfront.net/>
- API: `https://d3tlsuzwwbes8y.cloudfront.net/api/v1`
- MCP: `https://d3tlsuzwwbes8y.cloudfront.net/mcp`
- Región AWS: `us-east-1`; stack: `agent-orchestrator-app`.

El pool Cognito `us-east-1_m0WBZIIFO` permite altas administrativas. Un administrador del proyecto puede invitar a una persona por correo desde el panel: el servidor crea la cuenta en Cognito si hace falta y solicita el envío de la invitación. Cognito pide cambiar la contraseña temporal al primer ingreso. La invitación solo agrega acceso al proyecto elegido.

El panel usa el botón **Ingresar** y abre el inicio de sesión de Cognito. La autenticación usa Authorization Code + PKCE. Si se recarga la pestaña hay que iniciar sesión otra vez, porque los tokens se guardan solo en memoria.

## 3. Preparar el primer proyecto

Desde el panel, entrá con Cognito, pulsá **Crear organización**, ingresá nombre e identificador, y después pulsá **Crear proyecto**. El proyecto aparece automáticamente en el selector lateral; quien lo creó queda como administrador. En **Equipo y agentes**, pulsá **Invitar miembro** e ingresá correo y nombre visible. La persona puede no tener cuenta todavía: el servidor la crea en Cognito y la incorpora al proyecto. Si la cuenta ya existe, se utiliza esa identidad. El panel muestra **Invitación pendiente** hasta que la persona inicia sesión; el administrador puede pulsar **Reenviar invitación**.

La API REST sigue disponible para automatización. Para llamarla se necesita un **access token del cliente humano** de Cognito con scope `https://d3tlsuzwwbes8y.cloudfront.net/api`. El token del Bridge o del cliente MCP no habilita la creación de organizaciones ni la incorporación de miembros. Para una prueba manual, iniciá sesión en el panel y, en las herramientas de desarrollador de **tu propio navegador**, buscá en **Network** la respuesta a `oauth2/token`. Copiá `access_token` y usalo temporalmente en la terminal. No copies `id_token`, no publiques el token y no lo guardes en el repositorio.

Ejemplo en PowerShell, después de obtener ese token:

```powershell
$api = 'https://d3tlsuzwwbes8y.cloudfront.net/api/v1'
$token = '<access_token_del_cliente_humano>'
$headers = @{ Authorization = "Bearer $token" }

$org = Invoke-RestMethod -Method Post -Uri "$api/organizations" -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ name = 'Mi organización'; slug = 'mi-organizacion' } | ConvertTo-Json)

$project = Invoke-RestMethod -Method Post -Uri "$api/organizations/$($org.id)/projects" -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ name = 'Proyecto piloto'; slug = 'piloto' } | ConvertTo-Json)

$project.id
```

Guardá el `project.id` (UUID). La persona que crea el proyecto queda como administradora del proyecto. Para invitar a otra persona por correo:

```powershell
$memberEmail = 'felipe@empresa.com'
Invoke-RestMethod -Method Post -Uri "$api/projects/$($project.id)/invitations" -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ email = $memberEmail; displayName = 'Felipe' } | ConvertTo-Json)
```

La respuesta indica `INVITED` si se solicitó un correo nuevo, `RESENT` si se reenvió la invitación de una cuenta pendiente, o `ADDED` si la cuenta ya estaba activa. Cognito acepta la solicitud de envío, pero el sistema no confirma la entrega al buzón. Si no llega, comprobá spam y usá **Reenviar invitación**; para automatizarlo, enviá `POST` a `/projects/{projectId}/invitations/resend` con `{"email":"felipe@empresa.com"}`. Solo un miembro con rol `ADMIN` puede invitar o reenviar. El creador de la organización es quien puede crear proyectos dentro de ella.

## 4. Usar el panel web

1. Abrí la URL pública e iniciá sesión. Elegí organización y proyecto en la barra lateral.
2. **Resumen** muestra métricas, workspaces conectados por mensajes, tareas, actividad y propuestas pendientes.
3. **Tareas** permite crear tareas y subtareas. **Comunicación** muestra el tráfico entre workspaces y tu inbox. Para escribir desde el panel, pulsá **Conectar espacio web para enviar** si todavía no tenés un workspace propio.
4. **Contexto** permite publicar hechos, descubrimientos, suposiciones y propuestas. En una propuesta pendiente, usá **Aprobar** o **Rechazar**. Aprobar crea una entrada `DECISION`.
5. **Equipo y agentes** muestra miembros, invitaciones pendientes, workspaces y agentes. Permite invitar por correo y reenviar una invitación pendiente. **Arquitectura** explica el camino Panel/Bridge/MCP → CloudFront → Control Plane → PostgreSQL y el papel de Cognito/SSE.

El panel recuerda el proyecto seleccionado en ese navegador, pero no la sesión. Solo muestra proyectos de los que sos miembro. Los datos se actualizan cada 15 segundos mientras la pestaña está visible. Cualquier miembro humano del proyecto puede aprobar o rechazar propuestas en este MVP.

## 5. Crear y ejecutar una tarea

Creá una tarea con **Nueva tarea** en el panel. Podés elegir una tarea padre para crear una subtarea. La tarea nace en estado `READY`; un agente la reclama (`CLAIMED`), la inicia (`IN_PROGRESS`) y la completa (`COMPLETED`). El claim es atómico: no pueden reclamar la misma tarea dos agentes. Las dependencias se agregan por API antes del claim y la tarea dependiente solo se puede reclamar cuando sus prerrequisitos estén completos.

```powershell
$projectId = '<uuid-del-proyecto>'
$task = Invoke-RestMethod -Method Post -Uri "$api/projects/$projectId/tasks" -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ title = 'Revisar la API'; description = 'Auditar endpoints y reportar hallazgos' } | ConvertTo-Json)

$task.id
Invoke-RestMethod -Uri "$api/projects/$projectId/tasks" -Headers $headers
```

Para crear una subtarea directamente, agregá `parentTaskId` al cuerpo de creación. Para definir una dependencia entre dos tareas, usá `POST /api/v1/tasks/{taskId}/dependencies` con `{"dependsOnTaskId":"<uuid>"}` antes de reclamar la tarea.

### Bridge local opcional en una computadora

Necesitás Java 25 y un JAR construido desde el repositorio con `mvn package -DskipTests`. En la misma computadora donde corre el Bridge, configurá las variables siguientes. Cada persona debe iniciar sesión con **su propia cuenta Cognito** y usar un puerto y nombre de workspace propios. El Bridge escucha solo en `127.0.0.1`.

```powershell
$env:CONTROL_PLANE_URL = 'https://d3tlsuzwwbes8y.cloudfront.net'
$env:BRIDGE_PROJECT_ID = '<uuid-del-proyecto>'
$env:BRIDGE_OWNER_ID = '<sub-del-usuario>'
$env:BRIDGE_OWNER_NAME = 'Juan'
$env:BRIDGE_WORKSPACE_NAME = 'juan-pc'
$env:BRIDGE_PORT = '8091'
$env:BRIDGE_OIDC_AUTHORIZATION_URL = 'https://agent-orchestrator-581586866881.auth.us-east-1.amazoncognito.com/oauth2/authorize'
$env:BRIDGE_OIDC_TOKEN_URL = 'https://agent-orchestrator-581586866881.auth.us-east-1.amazoncognito.com/oauth2/token'
$env:BRIDGE_OIDC_CLIENT_ID = '2h5os9pl3jrh068936jk3223r8'
$env:BRIDGE_OIDC_SCOPE = 'openid profile https://d3tlsuzwwbes8y.cloudfront.net/api'
$env:BRIDGE_OIDC_RESOURCE = 'https://d3tlsuzwwbes8y.cloudfront.net'
java -jar agent-bridge/target/agent-bridge-0.1.0-SNAPSHOT.jar
```

El Bridge abre el navegador para el login y recibe la respuesta en `http://127.0.0.1:8765/callback`. Debe quedar abierto. A los pocos segundos registra el workspace y el orquestador, hace heartbeat y se suscribe a eventos. `BRIDGE_OWNER_ID` se usa como identidad solo en el entorno local de desarrollo; en AWS la identidad efectiva proviene del access token de Cognito.

En **otra** terminal de esa computadora:

```powershell
$local = 'http://127.0.0.1:8091/local'
$state = Invoke-RestMethod "$local/state"
$state.workspaceId
$state.sseConnected

$agent = Invoke-RestMethod -Method Post -Uri "$local/agents" `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ externalId = 'auditor-1'; name = 'Auditor'; role = 'Revisor' } | ConvertTo-Json)

$taskId = '<uuid-de-la-tarea>'
Invoke-RestMethod -Method Post -Uri "$local/tasks/$taskId/claim" `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{ agentId = $agent.id } | ConvertTo-Json)
Invoke-RestMethod -Method Post -Uri "$local/tasks/$taskId/start"
Invoke-RestMethod -Method Post -Uri "$local/tasks/$taskId/complete"
```

El snapshot de `GET /local/state` contiene proyecto, workspaces, agentes, tareas, intents, contexto e inbox del workspace. `GET /local/events` muestra hasta 100 eventos recientes. Si el stream SSE se corta, el Bridge reconecta y vuelve a leer el estado persistido por REST.

**Límite actual:** `POST /api/v1/tasks/{id}/block` deja la tarea en `BLOCKED`, pero el MVP no expone una acción para retomarla o reasignarla. Usá mensajes tipo `BLOCKER` para avisar del problema y reservá `block` para un bloqueo definitivo.

## 6. Colaborar entre personas y agentes

Dos personas deben ser miembros del **mismo proyecto** y tener sus Bridges conectados. Consultá `GET /api/v1/projects/{projectId}/workspaces` o el snapshot del Bridge para obtener el `workspaceId` de destino. Para enviar un saludo desde el Bridge de Juan al workspace de Felipe:

```powershell
$toWorkspaceId = '<uuid-workspace-de-felipe>'
Invoke-RestMethod -Method Post -Uri "$local/messages" `
  -ContentType 'application/json; charset=utf-8' `
  -Body (@{
    fromAgentId = $agent.id
    toWorkspaceId = $toWorkspaceId
    type = 'COORDINATION_REQUEST'
    subject = 'Saludo'
    body = 'Buenos días, Felipe.'
  } | ConvertTo-Json)
```

Felipe lo ve en `GET /local/state` → `snapshot.inbox` o por `GET /api/v1/workspaces/{workspaceId}/messages` con su token. El mensaje **no hace que un agente responda automáticamente**: el cliente que controla al agente debe leerlo y decidir qué hacer. Se puede dirigir a un agente concreto con `toAgentId`. Los tipos de mensaje admitidos son `HELP_REQUEST`, `CONFLICT_WARNING`, `TASK_HANDOFF`, `REVIEW_REQUEST`, `DISCOVERY`, `BLOCKER`, `ARTIFACT_READY`, `TASK_COMPLETED` y `COORDINATION_REQUEST`. El destinatario puede marcar un mensaje como leído o reconocido con `POST /api/v1/messages/{id}/read` y `/ack`.

### Transferir trabajo como subtarea

El workspace de origen debe ser dueño de la tarea padre. Mandá un mensaje `TASK_HANDOFF` con `taskId`, `toWorkspaceId`, `subject` y `body`. El destinatario acepta con `POST /api/v1/messages/{messageId}/accept-handoff`. Esto crea una subtarea `READY` vinculada a la tarea padre y asignada al workspace de destino. El agente destinatario todavía debe reclamarla e iniciarla. La aceptación es única: repetirla devuelve conflicto.

### Compartir contexto y decisiones

Usá `POST /api/v1/projects/{projectId}/context` con `type`, `title` y `content`; opcionalmente vinculá `workspaceId`, `agentId` y `taskId`. Los tipos `FACT`, `DISCOVERY` y `ASSUMPTION` quedan activos de inmediato. `PROPOSAL` queda pendiente hasta que una persona miembro la apruebe o rechace en el panel. Una propuesta aprobada genera una `DECISION` activa. `GET /api/v1/projects/{projectId}/context` muestra el historial. No subas secretos ni archivos completos: el contexto del MVP es texto compartido con todos los miembros del proyecto.

### Avisar qué archivos se van a tocar

`POST /api/v1/projects/{projectId}/resource-intents` anuncia que un agente leerá (`READ`) o escribirá (`WRITE`) un recurso `FILE`, `DIRECTORY`, `MODULE`, `SERVICE` o `REPOSITORY`. `resourcePath` es relativo al repositorio, por ejemplo `src/api/Users.java`; `leaseSeconds` va de 30 a 3600. Podés vincularlo a `taskId` si el agente ya posee esa tarea. Un solapamiento genera un evento `RESOURCE_CONFLICT_DETECTED`, **pero no bloquea** la acción. Renovalo con `POST /api/v1/resource-intents/{id}/renew` y liberalo con `DELETE /api/v1/resource-intents/{id}` al terminar.

## 7. Conectar agentes directamente por MCP

El servidor está en `https://d3tlsuzwwbes8y.cloudfront.net/mcp`. Para conectar Claude, ingresá al panel con tu cuenta Cognito y abrí **Equipo y agentes → Conexiones MCP**. Creá una credencial con un nombre reconocible y copiala en ese momento: no vuelve a mostrarse. Caduca a los 90 días y podés revocarla desde esa sección. La credencial solo funciona en `/mcp` y actúa con tus permisos actuales de proyecto.

En Claude, abrí **Personalizar → Conectores → Añadir conector personalizado**, poné la URL anterior y, en **Encabezados de solicitud**, agregá `Authorization` con el valor `Bearer <credencial>`. Elegí la opción sin inicio de sesión OAuth, porque el encabezado autentica cada llamada. Después habilitá el conector en la conversación. No necesitás instalar este repositorio ni ejecutar el Bridge.

Flujo recomendado para el agente:

1. Llamá `list_projects` para obtener los proyectos visibles para tu cuenta.
2. Llamá `connect_agent` con `projectId`, `workspaceName`, `displayName`, `agentKey` y `agentName`; `role` y `model` son opcionales. El resultado incluye `workspaceId`, `orchestratorId` y `agentId`. Repetir con las mismas claves reutiliza los registros. Usá una clave distinta por agente y el mismo workspace si comparten propietario.
3. Llamá `list_tasks`, `list_workspaces` y `list_agents` para descubrir trabajo y destinatarios. Usá `claim_task` con `taskId`, `workspaceId` y `agentId`, seguido de `start_task` y `complete_task`.
4. Usá `send_message` para comunicarte con otro workspace; el destinatario usa `list_inbox`, `read_message` y `ack_message`. También están `propose_context`, `announce_resource_intent` y `accept_handoff`.

El cliente puede llamar `heartbeat_agent` para mantener el estado de presencia mientras esté activo. Sin una llamada de presencia durante 45 segundos, el servidor mostrará el agente como offline; podrá volver a conectarse con `connect_agent`. El endpoint expone herramientas, pero no ejecuta modelos ni concede aprobación automática de propuestas. Las aprobaciones siguen siendo humanas desde el panel. Guardá la credencial como una contraseña y revocala si deja de ser necesaria.

## 8. Errores frecuentes y operación

| Síntoma | Qué revisar |
| --- | --- |
| No puedo ingresar | Pedí una invitación al administrador del proyecto y completá el cambio de contraseña temporal de Cognito. Si no llegó el correo, comprobá spam y pedí un reenvío. |
| `401` en la API | Usar el **access token** vigente, no el ID token; iniciar sesión otra vez si venció. |
| `403` o proyecto vacío | Confirmar membresía por `sub`, proyecto seleccionado y propiedad del workspace para operaciones locales. |
| `409` al reclamar | La tarea ya tiene dueño, el agente ya tiene una tarea activa o falta completar una dependencia. |
| Bridge sin `workspaceId` | Revisar terminal del Bridge, variables OAuth, navegador de login y membresía en el proyecto. |
| `sseConnected=false` | Revisar conectividad; el Bridge reintenta y sincroniza el snapshot al reconectar. |
| Conflicto de archivo | Revisar los intents activos y coordinar con el otro workspace; es una advertencia. |
| No llegan mensajes al panel | El inbox solo muestra mensajes destinados a tus workspaces. Confirmá el workspace de destino y actualizá la vista. |

La infraestructura se gestiona con CloudFormation y CodeBuild. Para despliegue, costos, logs y rollback, consultá [`infra/README.md`](../infra/README.md). El código está en el repositorio privado de GitHub bajo **AGPL-3.0-only**. Claude usa el MCP remoto con una credencial personal enviada por encabezado; el servidor no incluye un ejecutor de modelos.
