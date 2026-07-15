resource "azurerm_container_app_environment" "env" {
  name                = "${var.prefix}-cae"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id
}

resource "azurerm_storage_account" "sa" {
  name                     = "${var.prefix}sa${random_string.suffix.result}"
  resource_group_name      = azurerm_resource_group.rg.name
  location                 = azurerm_resource_group.rg.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
}

resource "azurerm_storage_share" "wiremock" {
  name                 = "wiremock-mappings"
  storage_account_name = azurerm_storage_account.sa.name
  quota                = 1
}

resource "azurerm_storage_share_file" "mappings" {
  for_each         = fileset("${path.module}/../wiremock/mappings", "*")
  name             = each.key
  storage_share_id = azurerm_storage_share.wiremock.id
  source           = "${path.module}/../wiremock/mappings/${each.key}"
}

resource "azurerm_container_app_environment_storage" "wiremock_storage" {
  name                         = "wiremockstorage"
  container_app_environment_id = azurerm_container_app_environment.env.id
  account_name                 = azurerm_storage_account.sa.name
  share_name                   = azurerm_storage_share.wiremock.name
  access_key                   = azurerm_storage_account.sa.primary_access_key
  access_mode                  = "ReadOnly"
}

# --- Wiremock ---
resource "azurerm_container_app" "wiremock" {
  name                         = "${var.prefix}-wiremock"
  container_app_environment_id = azurerm_container_app_environment.env.id
  resource_group_name          = azurerm_resource_group.rg.name
  revision_mode                = "Single"

  ingress {
    external_enabled = false
    target_port      = 8080
    transport        = "auto"
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    container {
      name   = "wiremock"
      image  = "wiremock/wiremock:3.9.1"
      cpu    = 0.5
      memory = "1Gi"
      command = ["--global-response-templating", "--verbose"]

      volume_mounts {
        name = "mappings-vol"
        path = "/home/wiremock/mappings"
      }
    }
    
    volume {
      name         = "mappings-vol"
      storage_name = azurerm_container_app_environment_storage.wiremock_storage.name
      storage_type = "AzureFile"
    }

    min_replicas = 1
    max_replicas = 1
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Shared Variables for Spring Boot Apps ---
locals {
  spring_env = [
    {
      name  = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"
      value = "8" # B1ms max_conn=50. Azure reserva 15 = 35 uteis. 3 apps * 8 = 24.
    },
    {
      name  = "SPRING_KAFKA_BOOTSTRAP_SERVERS"
      value = replace(confluent_kafka_cluster.basic.bootstrap_endpoint, "SASL_SSL://", "")
    },
    {
      name  = "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL"
      value = "SASL_SSL"
    },
    {
      name  = "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM"
      value = "PLAIN"
    },
    {
      name  = "SPRING_KAFKA_PROPERTIES_BASIC_AUTH_CREDENTIALS_SOURCE"
      value = "USER_INFO"
    },
    {
      name        = "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG"
      secret_name = "kafka-jaas"
    },
    {
      name        = "SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO"
      secret_name = "sr-auth"
    },
    {
      name  = "BUREAU_SERASA_BASE_URL"
      value = "http://${azurerm_container_app.wiremock.ingress[0].fqdn}"
    },
    {
      name  = "BUREAU_QUOD_BASE_URL"
      value = "http://${azurerm_container_app.wiremock.ingress[0].fqdn}"
    },
    {
      name  = "BUREAU_BOAVISTA_BASE_URL"
      value = "http://${azurerm_container_app.wiremock.ingress[0].fqdn}"
    },
    {
      name        = "SPRING_DATASOURCE_PASSWORD"
      secret_name = "pg-password"
    }
  ]
}

# --- Credit Query Service ---
resource "azurerm_container_app" "query_service" {
  name                         = "${var.prefix}-query-service"
  container_app_environment_id = azurerm_container_app_environment.env.id
  resource_group_name          = azurerm_resource_group.rg.name
  revision_mode                = "Single"
  
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name  = "kafka-jaas"
    value = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${confluent_api_key.app_kafka_api_key.id}\" password=\"${confluent_api_key.app_kafka_api_key.secret}\";"
  }

  secret {
    name  = "sr-auth"
    value = "${confluent_api_key.app_sr_api_key.id}:${confluent_api_key.app_sr_api_key.secret}"
  }

  secret {
    name  = "pg-password"
    value = var.postgres_admin_password
  }

  ingress {
    external_enabled = true
    target_port      = 8083
    transport        = "auto"
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    container {
      name   = "credit-query-service"
      image  = "${azurerm_container_registry.acr.login_server}/credit-query-service:latest"
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.spring_env
        content {
          name        = env.value.name
          value       = lookup(env.value, "value", null)
          secret_name = lookup(env.value, "secret_name", null)
        }
      }
      
      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/credithub"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_PRODUCER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = data.confluent_schema_registry_cluster.sr.rest_endpoint
      }

      # Probes
      # Readiness: verifica dependencias externas (Banco, Kafka). Deve falhar se dependencias off, cortando trafego web.
      # Liveness: verifica apenas se o processo local/JVM trava. Nao deve reiniciar a toa se Kafka falha (problema externo).
      liveness_probe {
        transport = "HTTP"
        port      = 8083
        path      = "/actuator/health/liveness"
      }
      readiness_probe {
        transport = "HTTP"
        port      = 8083
        path      = "/actuator/health/readiness"
      }
    }

    # CRITICO: min 1, max 1. O OutboxRelay faz findAll() a cada 2s e publica mensagens. 
    # Com 2+ replicadas, TODAS vao varrer a tabela ao mesmo tempo e publicar os mesmos eventos no Kafka (duplicacao).
    min_replicas = 1
    max_replicas = 1
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Audit Service ---
resource "azurerm_container_app" "audit_service" {
  name                         = "${var.prefix}-audit"
  container_app_environment_id = azurerm_container_app_environment.env.id
  resource_group_name          = azurerm_resource_group.rg.name
  revision_mode                = "Single"
  
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name  = "kafka-jaas"
    value = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${confluent_api_key.app_kafka_api_key.id}\" password=\"${confluent_api_key.app_kafka_api_key.secret}\";"
  }

  secret {
    name  = "sr-auth"
    value = "${confluent_api_key.app_sr_api_key.id}:${confluent_api_key.app_sr_api_key.secret}"
  }

  secret {
    name  = "pg-password"
    value = var.postgres_admin_password
  }

  template {
    container {
      name   = "audit-service"
      image  = "${azurerm_container_registry.acr.login_server}/audit-service:latest"
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.spring_env
        content {
          name        = env.value.name
          value       = lookup(env.value, "value", null)
          secret_name = lookup(env.value, "secret_name", null)
        }
      }

      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/audit"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_CONSUMER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = data.confluent_schema_registry_cluster.sr.rest_endpoint
      }
      
      liveness_probe {
        transport = "HTTP"
        port      = 8084
        path      = "/actuator/health/liveness"
      }
      readiness_probe {
        transport = "HTTP"
        port      = 8084
        path      = "/actuator/health/readiness"
      }
    }

    min_replicas = 1
    max_replicas = 2
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Decision Consumer ---
resource "azurerm_container_app" "decision_consumer" {
  name                         = "${var.prefix}-decision"
  container_app_environment_id = azurerm_container_app_environment.env.id
  resource_group_name          = azurerm_resource_group.rg.name
  revision_mode                = "Single"
  
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name  = "kafka-jaas"
    value = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${confluent_api_key.app_kafka_api_key.id}\" password=\"${confluent_api_key.app_kafka_api_key.secret}\";"
  }

  secret {
    name  = "sr-auth"
    value = "${confluent_api_key.app_sr_api_key.id}:${confluent_api_key.app_sr_api_key.secret}"
  }

  secret {
    name  = "pg-password"
    value = var.postgres_admin_password
  }

  template {
    container {
      name   = "decision-consumer"
      image  = "${azurerm_container_registry.acr.login_server}/decision-consumer:latest"
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.spring_env
        content {
          name        = env.value.name
          value       = lookup(env.value, "value", null)
          secret_name = lookup(env.value, "secret_name", null)
        }
      }

      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/decision"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_CONSUMER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = data.confluent_schema_registry_cluster.sr.rest_endpoint
      }
      env {
        name  = "SPRING_KAFKA_PRODUCER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = data.confluent_schema_registry_cluster.sr.rest_endpoint
      }

      liveness_probe {
        transport = "HTTP"
        port      = 8085
        path      = "/actuator/health/liveness"
      }
      readiness_probe {
        transport = "HTTP"
        port      = 8085
        path      = "/actuator/health/readiness"
      }
    }

    min_replicas = 1
    max_replicas = 2
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}
