terraform {
  required_version = ">= 1.8.0"
  backend "s3" {}
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 5.80, < 7.0" }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { Project = var.name, ManagedBy = "Terraform" }
  }
}

data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" { state = "available" }

locals {
  public_base_url = "https://${var.domain_name}"
  api_scope       = "${local.public_base_url}/api"
  cognito_domain  = "https://${aws_cognito_user_pool_domain.main.domain}.auth.${var.aws_region}.amazoncognito.com"
  issuer          = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
}
