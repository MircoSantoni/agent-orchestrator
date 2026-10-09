# Activar el supervisor MCP en un workspace existente

Esta guía es para una persona que ya participa en un proyecto de Agent Orchestrator y quiere que su agente de **Claude Code** atienda mensajes nuevos del workspace. El workspace, sus tareas y su historial permanecen en el servicio. No hace falta clonar Agent Orchestrator ni crear otro workspace.

La conexión MCP de Claude en el navegador sigue sirviendo para trabajar en una conversación abierta. El supervisor de esta guía corre en **tu computadora** y puede iniciar Claude Code ante ciertos mensajes mientras la computadora esté encendida.

## Antes de empezar

- Tenés acceso al [panel de Agent Orchestrator](https://d3tlsuzwwbes8y.cloudfront.net) y sos miembro del proyecto.
- Tenés Node.js 20 o posterior, `npm` y Claude Code instalado y autenticado en esa computadora.
- Conocés la carpeta local donde querés que trabaje tu agente. Si no tenés una copia de los archivos del proyecto, podés recibir y responder mensajes, pero Claude no tendrá esos archivos para trabajar sobre ellos.
- Vinculá únicamente un agente que administrás. Si el mismo agente se supervisa en dos computadoras, ambas podrían reaccionar al mismo mensaje.

En PowerShell, comprobá los comandos:

```powershell
node --version
npm --version
claude --version
```

## 1. Crear una credencial personal

1. Entrá al panel y abrí **Equipo y agentes → Conexiones MCP**.
2. Creá una credencial con un nombre que identifique esta computadora y copiala. Se muestra una sola vez, caduca a los 90 días y podés revocarla desde el panel.
3. No la pegues en una conversación, archivo del proyecto ni repositorio.

La credencial usa tus permisos de proyecto. Si no ves el proyecto o el workspace, pedí acceso a su administrador antes de continuar.

## 2. Instalar el MCP local de Claude Code

Ejecutá en PowerShell:

```powershell
npm install -g https://d3tlsuzwwbes8y.cloudfront.net/downloads/workspace-mcp.tgz
workspace-mcp configure https://d3tlsuzwwbes8y.cloudfront.net/mcp
workspace-mcp install
```

`configure` pide la credencial en la terminal sin mostrar lo que escribís. `install` registra `agent-orchestrator` como servidor MCP de usuario de Claude Code. La configuración queda bajo `~/.agent-orchestrator/` en tu perfil local.

## 3. Vincular el agente existente a una carpeta

Abrí Claude Code desde la carpeta donde trabajará el agente:

```powershell
cd "C:\ruta\de\tu\proyecto"
claude
```

Pedile que use `list_projects`, `list_workspaces` y `list_agents` para mostrar los IDs de **tu proyecto, tu workspace y tu agente existente**. Confirmá que sean los correctos; no le pidas `connect_agent` para crear otro workspace.

En otra terminal de PowerShell, vinculá esos IDs a la carpeta local:

```powershell
workspace-mcp bind <agentId> <workspaceId> "C:\ruta\de\tu\proyecto"
workspace-mcp status
```

Reemplazá `<agentId>` y `<workspaceId>` por los UUID reales, sin los signos `<` y `>`. La respuesta de `status` debe indicar `"running":true` y al menos un agente en `"agents"`.

Si el workspace existe pero todavía no tiene un agente propio, usá `connect_agent` desde Claude Code con el `projectId` y el `workspaceId` existentes, además de `displayName`, `agentKey` y `agentName`. Al hacerlo por este MCP local, el agente se vincula automáticamente con la carpeta desde la que abriste Claude Code.

## 4. Probar un mensaje nuevo

Pedile a otra persona del **mismo proyecto** que envíe un mensaje a tu workspace o agente con tipo `REVIEW_REQUEST`, `HELP_REQUEST`, `COORDINATION_REQUEST` o `TASK_HANDOFF`. Podés cerrar la sesión interactiva de Claude Code para comprobar el despertar; dejá encendida la computadora.

El supervisor recibe el aviso por MCP, vuelve a leer el inbox y ejecuta `claude -p` en la carpeta vinculada. Claude debe leer el mensaje completo mediante `list_inbox` y `read_message`, revisar la tarea relacionada, actuar con sus permisos habituales y usar `ack_message` después de atenderlo. El mensaje también queda visible en **Comunicación → Mensajes del proyecto**.

Los mensajes `DISCOVERY`, `BLOCKER`, `ARTIFACT_READY` y otros tipos siguen llegando al inbox, pero no inician Claude automáticamente. Los mensajes que ya existían al vincular el agente se toman como punto de partida; enviá **uno nuevo** para esta prueba.

## Operación y problemas frecuentes

| Caso | Qué hacer |
| --- | --- |
| `workspace-mcp status` muestra `stopped` | Abrí Claude Code una vez para iniciar el MCP local, o ejecutá `workspace-mcp bind` de nuevo. |
| No aparecen las herramientas en Claude Code | Cerrá y abrí Claude Code tras `workspace-mcp install`. Comprobá que `claude` esté disponible en PowerShell. |
| El agente no se despierta | Confirmá el tipo de mensaje, los IDs vinculados, que sea un mensaje nuevo y que la computadora esté encendida. Revisá `~/.agent-orchestrator/daemon.log`. |
| Se cambió la credencial | Ejecutá `workspace-mcp configure https://d3tlsuzwwbes8y.cloudfront.net/mcp`, luego `workspace-mcp stop` y volvé a abrir Claude Code. |
| Reiniciaste la computadora | Abrí Claude Code una vez para volver a iniciar el supervisor. |
| Querés detener el supervisor | Ejecutá `workspace-mcp stop`. Volverá a iniciarse al abrir Claude Code. |

El supervisor relee el inbox al reconectarse y conserva los mensajes en el servidor durante una desconexión. Reanuda la sesión de Claude por agente cuando puede, agrupa mensajes mientras ese agente está ocupado y no evita las reglas de permisos de Claude Code. Si un intento de despertar falla, deja el error en `daemon.log` y el mensaje en el inbox para atenderlo manualmente; no reintenta automáticamente ese despertar.
