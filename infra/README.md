# Despliegue AWS con CloudFormation y CodeBuild

La arquitectura se define en dos stacks:

1. `cloudformation/build.yaml`: repositorio ECR y proyecto CodeBuild con acceso al repositorio GitHub mediante una conexión AWS CodeConnections.
2. `cloudformation/application.yaml`: VPC, dos subredes públicas para ALB/Fargate, dos subredes privadas para RDS PostgreSQL 17, Cognito, certificado ACM, DNS Route 53, ECS Fargate, Secrets Manager, CloudWatch y alerta de presupuesto.

`buildspec.yml` construye el Dockerfile con Java 25, publica en ECR y usa los primeros 12 caracteres del commit como etiqueta inmutable. CloudFormation recibe esa etiqueta en `ImageTag`. El despliegue de aplicación se hace con un cambio explícito del stack después de revisar la compilación. Este flujo evita que un push aplique infraestructura o cambie producción sin revisión.

Los Bridges siguen en las PCs. El Security Group de ECS solo acepta tráfico del ALB; RDS solo acepta tráfico de ECS. Las tareas tienen IP pública para salir a servicios AWS sin NAT Gateway, pero el puerto 8080 no queda abierto a Internet. RDS usa una sola instancia, cifrado, backup de siete días y protección contra borrado; no es Multi-AZ. El presupuesto envía una alerta al 80 % y no detiene gastos.

## Datos necesarios en la sesión final

- Cuenta AWS, región y un perfil AWS CLI con permisos de CloudFormation, IAM, CodeBuild, CodeConnections, ECR, ECS, EC2/VPC, ELB, ACM, Route 53, RDS, Cognito, Logs y Budgets.
- Repositorio GitHub publicado y conexión CodeConnections autorizada para leerlo. Una conexión creada por CloudFormation queda pendiente hasta autorizarla; por eso el stack de build recibe un ARN ya activo.
- Dominio con Hosted Zone pública de Route 53 en la cuenta, presupuesto mensual y correo de alerta. La plantilla usa USD 10 como valor inicial del presupuesto; antes de aplicarla hay que comprobar si ya existe un presupuesto para evitar alertas duplicadas.
- Callback URLs de los clientes MCP reales y usuarios para invitar a Cognito.
- Revisión del coste regional en AWS Pricing Calculator antes de crear los stacks.

## Orden de creación

Los comandos siguientes son una guía para la sesión con la cuenta. No se ejecutaron todavía. Sustituir los valores entre `<...>` y revisar cada change set de CloudFormation. Primero, publicar el repositorio GitHub y autorizar la conexión AWS CodeConnections para ese repositorio. Después, desde la raíz del proyecto:

```powershell
aws cloudformation deploy --stack-name agent-orchestrator-build `
  --template-file infra/cloudformation/build.yaml `
  --capabilities CAPABILITY_IAM `
  --parameter-overrides `
    Name=agent-orchestrator `
    GitHubRepositoryUrl=https://github.com/<usuario>/agent-orchestrator.git `
    GitHubBranch=main `
    ConnectionArn=<arn-de-conexion-activa>
```

Iniciar CodeBuild con el SHA completo del commit que se quiere desplegar y esperar `SUCCEEDED`:

```powershell
$sha = (git rev-parse HEAD)
aws codebuild start-build --project-name agent-orchestrator --source-version $sha
aws codebuild batch-get-builds --ids <build-id>
$tag = $sha.Substring(0, 12)
$repository = aws cloudformation describe-stacks --stack-name agent-orchestrator-build `
  --query "Stacks[0].Outputs[?OutputKey=='RepositoryUri'].OutputValue | [0]" --output text
```

Revisar los parámetros, crear un change set y desplegar el stack de aplicación con la imagen publicada:

```powershell
aws cloudformation deploy --stack-name agent-orchestrator-app `
  --template-file infra/cloudformation/application.yaml `
  --capabilities CAPABILITY_IAM `
  --parameter-overrides `
    Name=agent-orchestrator `
    DomainName=<dominio> `
    HostedZoneId=<zone-id> `
    RepositoryUri=$repository `
    ImageTag=$tag `
    BudgetEmail=<correo> `
    BudgetLimitUsd=<monto> `
    McpCallbackUrls=<callback-1>,<callback-2>
```

Para la revisión previa, usar `aws cloudformation create-change-set` y `describe-change-set` con los mismos parámetros antes de ejecutar `deploy`. Crear usuarios mediante la consola/CLI de Cognito; el user pool solo permite alta administrativa.

## Verificación y actualización

Verificar `/actuator/health`, metadatos OAuth en `/.well-known/oauth-protected-resource`, login PKCE del panel y Bridge, permisos por proyecto, un cliente MCP real, logs CloudWatch y SSE/reconexión con dos tareas ECS. La primera creación puede esperar la validación DNS del certificado y el arranque de RDS.

Para una versión nueva, ejecutar CodeBuild con un commit nuevo, comprobar que la imagen existe en ECR y actualizar `ImageTag` en el stack de aplicación. Para rollback, desplegar el tag anterior. Las migraciones Flyway deben ser compatibles con la versión anterior si se necesita ese rollback. La base de datos tiene backup automático y política Snapshot al eliminar el recurso, pero la restauración es una operación separada.

La validación local con `cfn-lint` confirma sintaxis y tipos de recursos; la creación y el comportamiento de AWS se verificarán únicamente con la cuenta final. La plantilla no incluye WAF, Multi-AZ de RDS, rotación automática de credenciales, alarmas de latencia/error ni un pipeline de despliegue automático.
