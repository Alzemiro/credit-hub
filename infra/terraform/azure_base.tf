resource "random_string" "suffix" {
  length  = 6
  special = false
  upper   = false
}

resource "azurerm_resource_group" "rg" {
  name     = "${var.prefix}-rg"
  location = var.location
}

resource "azurerm_log_analytics_workspace" "law" {
  name                = "${var.prefix}-law-${random_string.suffix.result}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku                 = "PerGB2018"
}

resource "azurerm_application_insights" "app_insights" {
  name                = "${var.prefix}-appinsights"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  workspace_id        = azurerm_log_analytics_workspace.law.id
  application_type    = "web"
}

resource "azurerm_container_registry" "acr" {
  name                = "${var.prefix}acr${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku                 = "Basic"
  admin_enabled       = false
}

resource "azurerm_user_assigned_identity" "aca_identity" {
  name                = "${var.prefix}-aca-identity"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.aca_identity.principal_id
}

# A UAI recem-criada leva alguns segundos para propagar no Entra ID; sem esperar, o ACA
# valida a identity na criacao do Container App e recebe NotFound (IdentityDoesNotExist).
# Espera tambem cobre a propagacao do role AcrPull e da access policy do KV.
resource "time_sleep" "wait_for_identity" {
  depends_on = [
    azurerm_user_assigned_identity.aca_identity,
    azurerm_role_assignment.acr_pull,
    azurerm_key_vault_access_policy.kv_aca
  ]
  create_duration = "60s"
}

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "kv" {
  name                       = "${var.prefix}-kv-${random_string.suffix.result}"
  location                   = azurerm_resource_group.rg.location
  resource_group_name        = azurerm_resource_group.rg.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  purge_protection_enabled   = false
}

resource "azurerm_key_vault_access_policy" "kv_admin" {
  key_vault_id = azurerm_key_vault.kv.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = data.azurerm_client_config.current.object_id

  secret_permissions = [
    "Get", "List", "Set", "Delete", "Recover", "Backup", "Restore", "Purge"
  ]
}

resource "azurerm_key_vault_access_policy" "kv_aca" {
  key_vault_id = azurerm_key_vault.kv.id
  tenant_id    = azurerm_user_assigned_identity.aca_identity.tenant_id
  object_id    = azurerm_user_assigned_identity.aca_identity.principal_id

  secret_permissions = [
    "Get", "List"
  ]
}

# Guardamos no KV os valores JÁ COMPOSTOS que as apps consomem (não os componentes crus):
# o Container App referencia estes secrets via key_vault_secret_id (ver azure_aca.tf), então o
# KV é a fonte real dos segredos — não mais recurso órfão.
resource "azurerm_key_vault_secret" "kafka_jaas" {
  name         = "kafka-jaas"
  value        = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${confluent_api_key.app_kafka_api_key.id}\" password=\"${confluent_api_key.app_kafka_api_key.secret}\";"
  key_vault_id = azurerm_key_vault.kv.id
  depends_on   = [azurerm_key_vault_access_policy.kv_admin]
}

resource "azurerm_key_vault_secret" "sr_auth" {
  name         = "sr-auth"
  value        = "${confluent_api_key.app_sr_api_key.id}:${confluent_api_key.app_sr_api_key.secret}"
  key_vault_id = azurerm_key_vault.kv.id
  depends_on   = [azurerm_key_vault_access_policy.kv_admin]
}

resource "azurerm_key_vault_secret" "pg_password" {
  name         = "pg-password"
  value        = var.postgres_admin_password
  key_vault_id = azurerm_key_vault.kv.id
  depends_on   = [azurerm_key_vault_access_policy.kv_admin]
}
