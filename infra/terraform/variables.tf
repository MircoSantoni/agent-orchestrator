variable "name" {
  type    = string
  default = "agent-orchestrator"
}
variable "aws_region" {
  type        = string
  description = "AWS region selected for the personal account deployment."
}
variable "domain_name" {
  type        = string
  description = "Public API and approval UI DNS name, for example agents.example.com."
}
variable "hosted_zone_id" {
  type        = string
  description = "Route 53 public hosted zone ID for domain_name."
}
variable "image_tag" {
  type        = string
  description = "Immutable Git commit tag already pushed to the ECR repository."
}
variable "bridge_callback_url" {
  type    = string
  default = "http://127.0.0.1:8765/callback"
}
variable "mcp_callback_urls" {
  type        = list(string)
  description = "Pre-registered callback URLs used by the selected MCP clients."
  default     = ["http://127.0.0.1:8766/callback"]
}
variable "additional_mcp_origins" {
  type        = list(string)
  description = "Extra exact browser origins allowed to call the MCP endpoint. The public API origin is always allowed."
  default     = []
}
variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "desired_count" {
  type    = number
  default = 2
}
variable "budget_limit_usd" {
  type    = string
  default = "60"
}
variable "budget_email" {
  type        = string
  description = "Optional email for an 80 percent actual-cost budget alert."
  default     = ""
}
