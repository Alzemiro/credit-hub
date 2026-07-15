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
  for_each         = setsubtract(fileset("${path.module}/../wiremock/mappings", "*"), [".gitkeep"])
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

# --- OTel Collector (recebe OTLP das apps, exporta traces p/ App Insights) ---
resource "azurerm_storage_share" "otel" {
  name                 = "otel-config"
  storage_account_name = azurerm_storage_account.sa.name
  quota                = 1
}

resource "azurerm_storage_share_file" "otel_config" {
  name             = "config.yaml"
  storage_share_id = azurerm_storage_share.otel.id
  source           = "${path.module}/../otel/otel-collector-config.cloud.yaml"
}

resource "azurerm_container_app_environment_storage" "otel_storage" {
  name                         = "otelstorage"
  container_app_environment_id = azurerm_container_app_environment.env.id
  account_name                 = azurerm_storage_account.sa.name
  share_name                   = azurerm_storage_share.otel.name
  access_key                   = azurerm_storage_account.sa.primary_access_key
  access_mode                  = "ReadOnly"
}

resource "azurerm_container_app" "otel_collector" {
  name                         = "${var.prefix}-otel"
  container_app_environment_id = azurerm_container_app_environment.env.id
  resource_group_name          = azurerm_resource_group.rg.name
  revision_mode                = "Single"

  # A connection string do App Insights e o unico segredo do collector; nao passa pelo KV
  # (e um output de recurso TF, nao uma credencial que o usuario digita).
  secret {
    name  = "appinsights-connection"
    value = azurerm_application_insights.app_insights.connection_string
  }

  ingress {
    external_enabled = false   # interno: so as apps do mesmo environment falam com ele
    target_port      = 4318    # OTLP/HTTP (o exporter do micrometer usa http/protobuf em /v1/traces)
    transport        = "http"
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    container {
      name   = "otel-collector"
      image  = "otel/opentelemetry-collector-contrib:0.104.0"
      cpu    = 0.5
      memory = "1Gi"
      # args (nao command): a imagem tem ENTRYPOINT no binario do collector; passamos so a flag.
      args   = ["--config=/etc/otel/config.yaml"]

      env {
        name        = "APPLICATIONINSIGHTS_CONNECTION_STRING"
        secret_name = "appinsights-connection"
      }

      volume_mounts {
        name = "otel-config-vol"
        path = "/etc/otel"
      }
    }

    volume {
      name         = "otel-config-vol"
      storage_name = azurerm_container_app_environment_storage.otel_storage.name
      storage_type = "AzureFile"
    }

    min_replicas = 1
    max_replicas = 1
  }
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
      # args (não command): a imagem do WireMock tem ENTRYPOINT java -jar; estas são as flags
      # passadas a ele. Usar `command` sobrescreveria o ENTRYPOINT e tentaria executar
      # "--global-response-templating" como binário (crash loop).
      args   = ["--global-response-templating", "--verbose"]

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
      # B1ms: max_connections=50, Azure reserva ~15 => ~35 uteis.
      # Todas as apps rodam min=max=1 replica (query-service pelo OutboxRelay; audit/decision
      # porque nao ha KEDA scale rule, entao >1 seria inerte). Pior caso: 3 replicas * 8 = 24 <= 35.
      name  = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"
      value = "8"
    },
    {
      # Traces vao para o OTel Collector (container app), que faz fan-out p/ o exporter azuremonitor
      # (App Insights). App mantem a instrumentacao OTLP atual; so muda o endpoint de push.
      name  = "MANAGEMENT_OTLP_TRACING_ENDPOINT"
      value = "http://${azurerm_container_app.otel_collector.ingress[0].fqdn}/v1/traces"
    },
    {
      name  = "MANAGEMENT_TRACING_SAMPLING_PROBABILITY"
      value = "1.0"
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

  # A UAI precisa ter acesso de leitura ao KV antes da app tentar resolver os secrets.
  depends_on = [azurerm_key_vault_access_policy.kv_aca]

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  # Segredos resolvidos do Key Vault via managed identity (a UAI tem policy Get/List no KV).
  secret {
    name                = "kafka-jaas"
    key_vault_secret_id = azurerm_key_vault_secret.kafka_jaas.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "sr-auth"
    key_vault_secret_id = azurerm_key_vault_secret.sr_auth.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "pg-password"
    key_vault_secret_id = azurerm_key_vault_secret.pg_password.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
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
      # Placeholder publico: no 1o apply o ACR esta vazio e a imagem real ainda nao existe.
      # O CD (deploy.yml) troca pela imagem real; o ignore_changes abaixo impede o TF de reverter.
      image  = "mcr.microsoft.com/k8se/quickstart:latest"
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
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/credithub?sslmode=require"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_PRODUCER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = confluent_schema_registry_cluster.sr.rest_endpoint
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

  depends_on = [azurerm_key_vault_access_policy.kv_aca]

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  # Segredos resolvidos do Key Vault via managed identity (a UAI tem policy Get/List no KV).
  secret {
    name                = "kafka-jaas"
    key_vault_secret_id = azurerm_key_vault_secret.kafka_jaas.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "sr-auth"
    key_vault_secret_id = azurerm_key_vault_secret.sr_auth.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "pg-password"
    key_vault_secret_id = azurerm_key_vault_secret.pg_password.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  template {
    container {
      name   = "audit-service"
      # Placeholder publico (ver credit-query-service): CD assume a imagem real depois.
      image  = "mcr.microsoft.com/k8se/quickstart:latest"
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
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/audit?sslmode=require"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_CONSUMER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = confluent_schema_registry_cluster.sr.rest_endpoint
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

    # Sem KEDA scale rule (consumer nao tem ingress HTTP), entao >1 replica ficaria ociosa.
    # Fixado em 1 tambem por orcamento de conexoes do B1ms (ver pool size no locals.spring_env).
    min_replicas = 1
    max_replicas = 1
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

  depends_on = [azurerm_key_vault_access_policy.kv_aca]

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aca_identity.id]
  }

  registry {
    server   = azurerm_container_registry.acr.login_server
    identity = azurerm_user_assigned_identity.aca_identity.id
  }

  # Segredos resolvidos do Key Vault via managed identity (a UAI tem policy Get/List no KV).
  secret {
    name                = "kafka-jaas"
    key_vault_secret_id = azurerm_key_vault_secret.kafka_jaas.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "sr-auth"
    key_vault_secret_id = azurerm_key_vault_secret.sr_auth.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  secret {
    name                = "pg-password"
    key_vault_secret_id = azurerm_key_vault_secret.pg_password.id
    identity            = azurerm_user_assigned_identity.aca_identity.id
  }

  template {
    container {
      name   = "decision-consumer"
      # Placeholder publico (ver credit-query-service): CD assume a imagem real depois.
      image  = "mcr.microsoft.com/k8se/quickstart:latest"
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
        value = "jdbc:postgresql://${azurerm_postgresql_flexible_server.pg.fqdn}:5432/decision?sslmode=require"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = "credithub"
      }
      env {
        name  = "SPRING_KAFKA_CONSUMER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = confluent_schema_registry_cluster.sr.rest_endpoint
      }
      env {
        name  = "SPRING_KAFKA_PRODUCER_PROPERTIES_SCHEMA_REGISTRY_URL"
        value = confluent_schema_registry_cluster.sr.rest_endpoint
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

    # Sem KEDA scale rule (consumer nao tem ingress HTTP), entao >1 replica ficaria ociosa.
    # Fixado em 1 tambem por orcamento de conexoes do B1ms (ver pool size no locals.spring_env).
    min_replicas = 1
    max_replicas = 1
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}
