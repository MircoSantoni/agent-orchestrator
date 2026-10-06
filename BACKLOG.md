# Agent Orchestrator — backlog v0.1

## Avance (5 de octubre de 2026)

- M0: base Java 25 / Spring Boot 4.1, PostgreSQL, Flyway, Docker y Testcontainers operativos.
- M1: primer demo local verificado con dos Bridges simulados, conflicto WRITE/WRITE y mensaje recibido por SSE.
- M2: escenario manual con tres Bridges, propuesta aprobada por otra persona y handoff a subtarea verificado. También quedaron implementados heartbeats de agentes, resincronización REST tras SSE, intents con solapamiento de paths, UI mínima de aprobación, Cognito PKCE para humano/Bridge y autorización JWT por audience/scope/cliente. Se agregaron pruebas de SSE y compatibilidad con el SDK Java MCP 2.0.1.
- M3: stacks CloudFormation y compilación CodeBuild preparados localmente. Aplicación en AWS pendiente de región, dominio, presupuesto, callbacks MCP, repositorio GitHub y acceso a la cuenta personal. Las pruebas con Cognito real, clientes MCP externos y dos réplicas pertenecen a ese cierre.

## Objetivo

Coordinar por Internet a varias personas, cada una con su propio orquestador y agentes locales. El Control Plane se desplegará en AWS y conservará el estado compartido en PostgreSQL. Los Agent Bridges del primer MVP simularán la integración con Claude; el servidor nunca ejecutará ni accederá directamente a Claude.

## Decisiones confirmadas

- Proyecto: carpeta `agent-orchestrator` del Escritorio.
- Conectividad: por Internet; despliegue objetivo en AWS. Control Plane sin estado de sesión, infraestructura como código, servicios administrados y datos persistentes fuera del contenedor.
- Primera integración: Agent Bridges simulados mediante CLI/API local.
- Propuestas: aprobación por cualquier miembro autenticado del proyecto mediante una sesión humana; los agentes y el Bridge no pueden aprobar.
- Handoff: el receptor acepta una subtarea; la tarea original no cambia de dueño por ese acto.
- Identidad propuesta: Amazon Cognito emite JWT de acceso firmados. Humanos y Bridges usan clientes públicos separados con Authorization Code + PKCE; ningún secreto de cliente se instala en las PCs. El backend valida firma, issuer, vigencia, token_use y cliente; consulta membresía y ownership en PostgreSQL.
- Stack y límites: Java 25, Spring Boot 4.1, PostgreSQL, Flyway, REST y SSE; sin brokers, WebSockets ni ejecución remota.

## Hitos

- **M0 — Base ejecutable:** aplicación, base de datos, migraciones y pruebas de integración.
- **M1 — Primer demo:** dos Bridges simulados conectados al mismo proyecto; conflicto WRITE/WRITE y mensaje de coordinación recibidos por SSE.
- **M2 — MVP funcional:** tareas con dependencias, contexto aprobado por humanos, handoff a subtarea, tres Bridges simulados y activity stream reconstruible.
- **M3 — Despliegue por Internet:** M2 funcionando en AWS con TLS, datos persistentes, acceso controlado y procedimientos de operación.

## Backlog ordenado

### A. Fundamentos (M0)

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| CP-001 | Crear repositorio y módulos `control-plane` y `agent-bridge`; fijar Java 25 y convenciones de paquetes. | Ambos módulos compilan de forma reproducible. |
| CP-002 | Configurar PostgreSQL, perfiles de desarrollo y Flyway. | La aplicación inicia sobre una base vacía y aplica las migraciones. |
| CP-003 | Crear migraciones de Organization, Project, Workspace, Orchestrator, Agent, Task, TaskDependency, ResourceIntent, ContextEntry, AgentMessage, ActivityEvent y ProjectMember. | Claves foráneas, unicidad e índices del documento original verificados sobre PostgreSQL. |
| CP-004 | Configurar JUnit 5, Spring Boot Test y Testcontainers PostgreSQL. | Una prueba de integración arranca la aplicación con migraciones reales. |
| CP-005 | Definir DTO, validación, respuestas de error, paginación, reloj UTC y formato uniforme de eventos. | Errores de validación, inexistencia y conflicto tienen respuestas coherentes. |

### B. Identidad y autorización (prerrequisito para uso por Internet)

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| SEC-001 | Configurar Cognito con clientes públicos separados para humano y Bridge; usar Authorization Code + PKCE. | Login interactivo emite access JWT sin secreto embebido en la PC. |
| SEC-002 | Validar JWT en Spring Security: firma por JWKS, issuer, expiración, token_use, cliente y scopes. | Tokens falsificados, vencidos o del cliente incorrecto reciben 401/403. |
| SEC-003 | Persistir miembros de proyecto y vincular identidad humana (`sub`) con workspaces. | Todo request comprueba membresía y ownership en PostgreSQL. |
| SEC-004 | Implementar `PolicyService` para claim, intents, mensajes, handoff y aprobación. | Solo sesiones humanas de miembros del proyecto aprueban; agentes y Bridges no. |
| SEC-005 | Crear flujo mínimo de login y aprobación humana, sin construir el panel completo. | La decisión registra `approvedBy` como identidad humana autenticada. |

### C. Presencia y descubrimiento

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| CP-010 | Crear Organization y Project con API de consulta/alta administrativa. | Dos workspaces pueden pertenecer al mismo proyecto. |
| CP-011 | Registrar Workspace idempotentemente por proyecto, dueño y nombre. | Reintentar el registro devuelve el workspace existente. |
| CP-012 | Implementar heartbeat y scheduler de presencia para Workspace. | ONLINE, DEGRADED y OFFLINE se calculan sin borrar registros; reconexión vuelve a ONLINE. |
| CP-013 | Registrar Orchestrator y mantener heartbeat/estado. | Cada Bridge puede declarar su orquestador. |
| CP-014 | Registrar Agent idempotentemente por orchestratorId + externalId. | Reintentar registro no duplica agentes. |
| CP-015 | Implementar heartbeat, transición de estados y búsqueda filtrada de Agent. | Se pueden consultar agentes de todos los workspaces de un proyecto. |

### D. Tareas y coordinación

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| CP-020 | Crear/consultar tareas, subtareas y dependencias; validar pertenencia al proyecto y ciclos. | El grafo no admite ciclos ni referencias entre proyectos. |
| CP-021 | Definir transiciones de Task y regla para pasar a READY según dependencias. | Una tarea con dependencias incompletas no se puede reclamar. |
| CP-022 | Reclamar tareas con UPDATE condicional transaccional. | Dos agentes concurrentes: exactamente uno obtiene el claim; el otro recibe 409. |
| CP-023 | Iniciar, bloquear, completar y consultar ownership de tarea. | Los cambios dejan actividad auditable. |
| CP-024 | Registrar/renovar/liberar ResourceIntent con leases. | Una renovación vencida o de otro workspace no prospera. |
| CP-025 | Normalizar paths y detectar solapamientos relevantes entre archivo/directorio/módulo. | READ/READ no alerta; READ/WRITE alerta; WRITE/WRITE alerta alta. |
| CP-026 | Expirar intents por scheduler. | El estado pasa a EXPIRED sin borrar el registro y genera actividad. |

### E. Actividad y SSE

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| CP-030 | Registrar ActivityEvent en las operaciones del dominio. | Se identifica actor, momento, proyecto, tarea y recurso cuando aplican. |
| CP-031 | Exponer activity stream paginado y filtros útiles. | Puede reconstruirse la secuencia de trabajo desde REST. |
| CP-032 | Crear SSE por proyecto basado en eventos persistidos y visible entre instancias; emitir solo tras commit. | Workspaces conectados a distintas réplicas reciben tareas, conflictos y mensajes del mismo proyecto. |
| CP-033 | Gestionar desconexión, reconexión y resincronización mediante REST. | Perder SSE no pierde el estado persistido ni deja al Bridge desactualizado. |

### F. Contexto, mensajería y handoff (M2)

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| CP-040 | Crear/consultar FACT, DISCOVERY y ASSUMPTION con referencias a tareas. | Son visibles por proyecto con filtros. |
| CP-041 | Crear PROPOSAL pendiente y aprobar/rechazar por humano autenticado. | Un agente no puede aprobar; aprobación atómica crea DECISION y actividad. |
| CP-042 | Enviar mensajes estructurados, inbox, leído y acknowledged. | Mensajes persisten y el destinatario recibe notificación SSE. |
| CP-043 | Solicitar handoff referenciando una tarea original y describiendo la subtarea. | El mensaje queda pendiente hasta aceptación explícita del destinatario. |
| CP-044 | Aceptar handoff de forma atómica y crear/asignar la subtarea al receptor. | La subtarea tiene el padre correcto; una segunda aceptación no la duplica. |

### G. Agent Bridge simulado

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| AB-001 | Crear configuración sin secretos en repositorio y API local ligada a `127.0.0.1`. | La API no escucha en la interfaz de red. |
| AB-002 | Registrar workspace/orchestrator/agents y enviar heartbeats periódicos. | El Control Plane refleja presencia y desconexión. |
| AB-003 | Mantener SSE, reconectar y resincronizar con REST. | Un corte temporal no requiere reiniciar Bridge. |
| AB-004 | Implementar comandos/API local para estado, tareas, intents y mensajes. | Dos instancias simuladas completan el flujo del primer demo. |
| AB-005 | Permitir ejecutar tres instancias con configuraciones separadas. | Mirco, Juan y Sofía se observan en el mismo proyecto. |

### H. MCP remoto (M2)

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| MCP-001 | Exponer `server/discover`, `tools/list` y `tools/call` en `/mcp` con Streamable HTTP 2026-07-28. | Un cliente obtiene el catálogo y llama herramientas sin sesiones del transporte. Implementado en MVP. |
| MCP-002 | Mapear lectura, claim, intents, propuestas, mensajes y handoff a los servicios existentes. | Los mismos permisos, conflictos y estados aplican por REST y MCP; la aprobación humana no figura como herramienta. Implementado en MVP. |
| MCP-003 | Validar interoperabilidad con clientes MCP externos y soportar la revisión 2025 si resulta necesaria. | Codex y al menos otro cliente completan descubrimiento, lectura y claim. |
| MCP-004 | Completar el flujo OAuth/OIDC para clientes MCP remotos. | Un cliente obtiene y renueva credenciales sin copiar tokens manualmente. |
| MCP-005 | Ampliar cobertura de protocolo, observabilidad y límites de peticiones. | Pruebas de conformidad y métricas de herramientas en CI. |

### I. Despliegue AWS y validación (M3)

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| OPS-001 | Definir topología AWS, región, dominio, coste esperado y código de infraestructura antes de aprovisionar. | Diagrama y cambios de infraestructura revisables. |
| OPS-002 | Desplegar Control Plane en cómputo administrado sin estado, detrás de HTTPS; usar PostgreSQL administrado y privado. | Bridges externos se conectan por Internet; la base de datos no es pública. |
| OPS-003 | Configurar Cognito, gestión de secretos, logs, backups, health checks y despliegue/rollback. | Reiniciar o redeplegar no pierde datos; credenciales no están en Git. |
| OPS-004 | Verificar SSE con al menos dos réplicas y reconexión durante un despliegue. | El cliente recupera estado por REST aunque cambie de réplica o pierda eventos. |
| QA-001 | Probar concurrencia, permisos, heartbeats, intents, SSE y reconexión con PostgreSQL real. | Suite automatizada verde. |
| QA-002 | Ejecutar escenario E2E Mirco → Juan → Sofía con tres Bridges simulados. | Se observa conflicto, coordinación, aprobación humana, subtarea aceptada y actividad completa. |
| DOC-001 | Documentar instalación, endpoints, configuración de Bridge, despliegue y demo. | Una persona nueva puede repetir el escenario siguiendo la documentación. |

## Dependencias principales

`A → B → C → D → E → F → G → H`. El Bridge básico puede avanzar junto con C–E. El primer demo requiere A–E, seguridad mínima de B y AB-001 a AB-004. El despliegue AWS requiere decidir región, dominio y autenticación humana antes de aprovisionar.

## Pendientes para el cierre en AWS

1. Elegir región, dominio/Hosted Zone, presupuesto y correo de alerta; calcular coste regional antes de crear los stacks CloudFormation.
2. Conectar GitHub con AWS CodeConnections, crear el stack de build, compilar y publicar la imagen con CodeBuild; revisar y crear el stack de aplicación con la cuenta personal.
3. Crear usuarios de Cognito por invitación administrativa, registrar callbacks MCP concretos y completar pruebas OAuth y MCP desde clientes externos.
4. Verificar health checks, logs, SSE y resincronización con dos tareas ECS, más el flujo E2E de tres Bridges contra el servicio público.
