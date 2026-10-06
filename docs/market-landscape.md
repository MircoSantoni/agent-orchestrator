# Alternativas de orquestación de agentes

Consulta realizada el 6 de octubre de 2026. Esta comparación usa documentación de cada proyecto; las diferencias de posicionamiento son una inferencia de producto, no una prueba exhaustiva de funcionalidades.

| Alternativa | Qué ofrece | Relación con Agent Orchestrator |
| --- | --- | --- |
| [Concord MCP](https://github.com/Get-Concord-AI/concord-mcp) | Mensajería directa entre agentes de distintos clientes, reclamos de archivos, contexto de tareas, handoffs y revisiones mediante MCP. | Competidor más cercano para coordinación de agentes de programación; conviene comparar la experiencia real de conexión, entrega de mensajes y resolución de conflictos. |
| [Agentic Workspace](https://github.com/agentic-workspaces/agentic-workspace) | Protocolo y referencia para varios humanos y agentes, con identidad, colas, aprobaciones y estado compartido. | Competidor conceptual del plano de control; su especificación se declara borrador. |
| [CLAI](https://github.com/clairun/clai) | Aplicación local para equipos de agentes con herramientas MCP, tareas, memoria, ejecución y supervisión. | Alternativa orientada a la operación local y a la interfaz de trabajo del usuario. |
| [LangGraph y LangSmith Deployment](https://www.langchain.com/blog/langgraph-platform-ga) | Ejecución durable de grafos de agentes, persistencia, despliegue y supervisión. | Plataforma de ejecución con la que un plano de coordinación entre personas y agentes externos podría integrarse. |
| [CrewAI AMP](https://docs.crewai.com/enterprise/introduction) | Despliegue, escalado, API y observabilidad de crews y agentes. | Plataforma administrada para ejecutar flujos de agentes. |
| [Amazon Bedrock AgentCore](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/) | Runtime, memoria, gateway MCP, identidad y observabilidad para agentes en AWS. | Alternativa de infraestructura y posible integración, relevante por nuestro despliegue en AWS. |
| [Temporal](https://docs.temporal.io/ai) | Ejecución durable y recuperación de flujos de agentes y aprobaciones largas. | Componente complementario si el producto incorpora ejecución y reintentos propios. |

## Hipótesis de posicionamiento

La oportunidad más específica es coordinar agentes **de distintas personas, máquinas y clientes** sobre un proyecto compartido: propiedad de tareas, dependencias, intenciones sobre recursos, contexto aprobado por humanos y auditoría, expuestos por REST y MCP. Concord MCP ya cubre buena parte de la coordinación entre clientes; por eso la diferenciación debe comprobarse en pruebas con usuarios, especialmente en colaboración remota, permisos por organización y persistencia operativa.

## Próxima validación de producto

Entrevistar equipos que usan al menos dos clientes de agentes sobre un mismo repositorio. Pedirles que ejecuten una tarea compartida con y sin el plano de control y medir colisiones de archivos, handoffs perdidos y tiempo hasta recuperar el contexto. Evitar agregar otro runtime de agentes antes de validar que el plano compartido resuelve un problema frecuente.
