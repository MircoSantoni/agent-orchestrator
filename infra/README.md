# Despliegue AWS con CloudFormation y CodeBuild

La arquitectura se define en dos stacks:

1. `cloudformation/bootstrap-build.yaml`: bucket S3 privado para un ZIP de código, repositorio ECR y proyecto CodeBuild. Permite desplegar antes de publicar el repositorio en GitHub. `cloudformation/build.yaml` conserva la variante con conexión CodeConnections para la etapa posterior.
2. `cloudformation/application.yaml`: VPC, dos subredes públicas para Fargate, dos subredes privadas para ALB y RDS PostgreSQL 17, CloudFront con dominio HTTPS de AWS, Cognito, ECS Fargate, Secrets Manager, CloudWatch y alerta de presupuesto.

`buildspec.yml` construye el Dockerfile con Java 25 y publica en ECR con los primeros 12 caracteres del commit como etiqueta inmutable. En el modo S3, la etiqueta se pasa a CodeBuild mediante `IMAGE_TAG_OVERRIDE`. CloudFormation recibe esa etiqueta en `ImageTag`. El despliegue de aplicación se hace con un cambio explícito del stack después de revisar la compilación. Este flujo evita que un push aplique infraestructura o cambie producción sin revisión.

Los Bridges siguen en las PCs. CloudFront asigna una URL `https://...cloudfront.net` sin registrar dominio propio; conecta por un origen VPC al ALB privado. CloudFront no almacena respuestas de esta API y reenvía métodos, cabeceras, cookies y query strings, necesarios para OAuth, MCP y SSE. El Security Group de ECS solo acepta tráfico del ALB; RDS solo acepta tráfico de ECS. Las tareas tienen IP pública para salir a servicios AWS sin NAT Gateway, pero el puerto 8080 no queda abierto a Internet. RDS usa una sola instancia, cifrado, backup de siete días y protección contra borrado; no es Multi-AZ. El presupuesto envía una alerta al 80 % y no detiene gastos.

## Datos necesarios en la sesión final

- Cuenta AWS, región y un perfil AWS CLI con permisos de CloudFormation, IAM, CodeBuild, CodeConnections, ECR, ECS, EC2/VPC, ELB, CloudFront, RDS, Cognito, Logs y Budgets. CloudFront VPC origins debe estar disponible en la región y en las zonas de disponibilidad elegidas.
- ID regional de la prefix list administrada `com.amazonaws.global.cloudfront.origin-facing` para permitir que CloudFront llegue al ALB privado.
- Para pasar al modo GitHub: repositorio publicado y conexión CodeConnections autorizada para leerlo. Una conexión creada por CloudFormation queda pendiente hasta autorizarla; por eso `build.yaml` recibe un ARN ya activo.
- Presupuesto mensual y correo de alerta. La plantilla usa USD 10 como valor inicial; antes de aplicarla hay que comprobar si ya existe un presupuesto para evitar alertas duplicadas. La URL pública aparece en el output `PublicUrl` al crear el stack.
- Callback URLs de los clientes MCP reales y usuarios para invitar a Cognito.
- Revisión del coste regional en AWS Pricing Calculator antes de crear los stacks.

## Orden de creación

Para el arranque sin GitHub, desde la raíz del proyecto, crear el stack de build, comprimir el commit y ejecutar CodeBuild:

```powershell
aws cloudformation deploy --stack-name agent-orchestrator-build `
  --template-file infra/cloudformation/bootstrap-build.yaml --capabilities CAPABILITY_IAM
$sha = git rev-parse HEAD
git archive --format=zip --output=work/deploy-source.zip HEAD
$bucket = aws cloudformation describe-stacks --stack-name agent-orchestrator-build `
  --query "Stacks[0].Outputs[?OutputKey=='SourceBucketName'].OutputValue | [0]" --output text
aws s3 cp work/deploy-source.zip "s3://$bucket/source.zip"
$tag = $sha.Substring(0, 12)
aws codebuild start-build --project-name agent-orchestrator `
  --environment-variables-override "name=IMAGE_TAG_OVERRIDE,value=$tag,type=PLAINTEXT"
```

Comprobar que el build terminó en `SUCCEEDED` y que ECR contiene la imagen. Para la variante futura con GitHub, publicar el repositorio y autorizar CodeConnections antes de crear el stack `build.yaml`:

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
    RepositoryUri=$repository `
    ImageTag=$tag `
    CloudFrontPrefixListId=<prefix-list-id> `
    BudgetEmail=<correo> `
    BudgetLimitUsd=<monto> `
    McpCallbackUrls=<callback-1>,<callback-2>
```

Para la revisión previa, usar `aws cloudformation create-change-set` y `describe-change-set` con los mismos parámetros antes de ejecutar `deploy`. Crear usuarios mediante la consola/CLI de Cognito; el user pool solo permite alta administrativa.

## Verificación y actualización

Verificar `/actuator/health`, metadatos OAuth en `/.well-known/oauth-protected-resource`, login PKCE del panel y Bridge, permisos por proyecto, un cliente MCP real, logs CloudWatch y SSE/reconexión con dos tareas ECS. La primera creación puede esperar el origen VPC/CloudFront y el arranque de RDS. El hostname genérico pertenece a la distribución: si se reemplaza la distribución, habrá que actualizar Cognito y los clientes que usen esa URL.

Para una versión nueva, ejecutar CodeBuild con un commit nuevo, comprobar que la imagen existe en ECR y actualizar `ImageTag` en el stack de aplicación. Para rollback, desplegar el tag anterior. Las migraciones Flyway deben ser compatibles con la versión anterior si se necesita ese rollback. La base de datos tiene backup automático y política Snapshot al eliminar el recurso, pero la restauración es una operación separada.

La validación local con `cfn-lint` confirma sintaxis y tipos de recursos; la creación y el comportamiento de AWS se verificarán únicamente con la cuenta final. La plantilla no incluye WAF, Multi-AZ de RDS, rotación automática de credenciales, alarmas de latencia/error ni un pipeline de despliegue automático.
