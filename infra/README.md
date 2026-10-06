# Despliegue AWS del Control Plane

La plantilla `terraform/` prepara un servicio nativo cloud: ALB público con HTTPS, dos tareas ECS Fargate sin estado en zonas de disponibilidad distintas, RDS PostgreSQL 17 en subredes privadas, Cognito para clientes públicos, ECR, Secrets Manager, CloudWatch y alerta de presupuesto. Los Bridges siguen ejecutándose en las PCs. El Security Group de ECS solo acepta tráfico del ALB; RDS solo acepta tráfico de ECS. Las tareas tienen IP pública para salir a servicios AWS sin NAT Gateway, pero el puerto de la aplicación no queda abierto a Internet.

Esto todavía no se ha aplicado en la cuenta personal. El presupuesto es una alerta, no un límite de gasto. Dos tareas, ALB y RDS generan costes continuos; la estimación concreta depende de región y opciones elegidas y debe revisarse en AWS Pricing Calculator antes de aplicar. RDS tiene backup de 7 días, cifrado y protección contra borrado, pero esta configuración inicial usa una sola instancia y no es Multi-AZ.

## Datos necesarios para el cierre con la cuenta

1. Región AWS y dominio administrado en Route 53, más su Hosted Zone ID.
2. Perfil AWS CLI con permisos para Terraform, ECR, ECS, EC2/VPC, ELB, ACM, RDS, Cognito, IAM, CloudWatch, Budgets, S3 y Secrets Manager.
3. Nombre de bucket S3 privado para el estado de Terraform; crearlo con versionado, cifrado y bloqueo de acceso público antes de inicializar.
4. Correo para alertas, presupuesto mensual aceptable y callbacks de los clientes MCP que se usarán.
5. Usuarios a invitar y estrategia de alta en Cognito. El user pool permite solo usuarios creados por un administrador.

## Bootstrap y despliegue

Los comandos siguientes son el procedimiento para la sesión final en la cuenta AWS. Requieren AWS CLI, Terraform y Docker además de Java 25/Maven. Revisar el plan antes del `apply`.

```powershell
cd infra/terraform
Copy-Item terraform.tfvars.example terraform.tfvars
Copy-Item backend.hcl.example backend.hcl
# Editar ambos archivos con región, DNS, bucket y callbacks reales.
terraform init -backend-config=backend.hcl
terraform fmt -check -recursive
terraform validate
terraform plan -out=preparacion.tfplan
```

El repositorio ECR debe existir antes de publicar la imagen. Se puede crear de forma controlada con `terraform apply -target=aws_ecr_repository.app` dentro de `infra/terraform` después de revisar el recurso, seguido de un plan completo. Volvé a la raíz del proyecto para construir la imagen:

```powershell
mvn test
mvn package -DskipTests
$imageTag = (git rev-parse --short=12 HEAD)
docker build -f Dockerfile -t agent-orchestrator:$imageTag .
$repository = (terraform -chdir=infra/terraform output -raw ecr_repository_url)
$registry = $repository.Split('/')[0]
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin $registry
docker tag agent-orchestrator:$imageTag "${repository}:${imageTag}"
docker push "${repository}:${imageTag}"
```

Fijar `image_tag` al mismo commit, ejecutar `terraform plan`, revisar coste/recursos y luego `terraform apply`. La primera puesta en marcha puede tardar por el certificado ACM, RDS y health checks. Verificar:

- `https://<dominio>/actuator/health` devuelve 200.
- `https://<dominio>/.well-known/oauth-protected-resource` publica resource, issuer y scope.
- El panel humano inicia sesión por Cognito y puede aprobar una propuesta de prueba.
- Un Bridge fuera de AWS obtiene token PKCE, se registra y recibe eventos SSE.
- Dos Bridges conectados a réplicas distintas recuperan estado por REST tras reconexión.
- Un cliente MCP con callback registrado descubre herramientas y llama `list_tasks` y `claim_task`.
- CloudWatch muestra logs estructurados y no hay errores de Flyway ni fallos de health check.

## Actualización y rollback

Publicar cada versión con un tag de imagen inmutable, cambiar `image_tag` y aplicar un plan nuevo. Para rollback, volver al tag anterior y reaplicar. Las migraciones Flyway deben ser compatibles hacia atrás con la versión previa antes de hacer rollback. RDS crea backups automáticos de 7 días y una snapshot final al destruir; la restauración de base de datos es una operación separada. No ejecutar `terraform destroy` como procedimiento de rollback.

## Límites de la plantilla

La plantilla no habilita alarmas de latencia/error, WAF, Multi-AZ de RDS, rotación automática de credenciales de la base ni pipeline de CI/CD. La política de IAM para despliegues y el coste regional se revisarán con la cuenta real. El estado S3 debe mantenerse fuera del repositorio y protegido por políticas propias del bucket.
