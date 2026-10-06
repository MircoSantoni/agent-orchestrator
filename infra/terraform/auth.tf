resource "aws_cognito_user_pool" "main" {
  name                     = var.name
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  deletion_protection      = "ACTIVE"
  admin_create_user_config { allow_admin_create_user_only = true }
  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }
}

resource "aws_cognito_user_pool_domain" "main" {
  domain       = "${var.name}-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.main.id
}

resource "aws_cognito_resource_server" "api" {
  identifier   = local.public_base_url
  name         = "Agent Orchestrator API"
  user_pool_id = aws_cognito_user_pool.main.id
  scope {
    scope_name        = "api"
    scope_description = "Read and coordinate projects as a signed-in member"
  }
}

resource "aws_cognito_user_pool_client" "human" {
  name                                 = "${var.name}-human"
  user_pool_id                         = aws_cognito_user_pool.main.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email", local.api_scope]
  callback_urls                        = ["${local.public_base_url}/"]
  logout_urls                          = ["${local.public_base_url}/"]
  supported_identity_providers         = ["COGNITO"]
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 60
  refresh_token_validity               = 30
  token_validity_units {
    access_token  = "minutes"
    refresh_token = "days"
  }
  depends_on = [aws_cognito_resource_server.api]
}

resource "aws_cognito_user_pool_client" "bridge" {
  name                                 = "${var.name}-bridge"
  user_pool_id                         = aws_cognito_user_pool.main.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email", local.api_scope]
  callback_urls                        = [var.bridge_callback_url]
  supported_identity_providers         = ["COGNITO"]
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 60
  refresh_token_validity               = 30
  token_validity_units {
    access_token  = "minutes"
    refresh_token = "days"
  }
  depends_on = [aws_cognito_resource_server.api]
}

resource "aws_cognito_user_pool_client" "mcp" {
  name                                 = "${var.name}-mcp"
  user_pool_id                         = aws_cognito_user_pool.main.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email", local.api_scope]
  callback_urls                        = var.mcp_callback_urls
  supported_identity_providers         = ["COGNITO"]
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 60
  refresh_token_validity               = 30
  token_validity_units {
    access_token  = "minutes"
    refresh_token = "days"
  }
  depends_on = [aws_cognito_resource_server.api]
}
