resource "confluent_environment" "env" {
  display_name = "${var.prefix}-env"

  stream_governance {
    package = "ESSENTIALS"
  }
}

resource "time_sleep" "wait_for_sr" {
  depends_on = [confluent_environment.env]
  create_duration = "3m"
}

resource "confluent_kafka_cluster" "basic" {
  display_name = "${var.prefix}-cluster"
  availability = "SINGLE_ZONE"
  cloud        = "AZURE"
  region       = var.location

  basic {}

  environment {
    id = confluent_environment.env.id
  }
}

resource "confluent_service_account" "env_manager" {
  display_name = "${var.prefix}-env-manager"
  description  = "SA with CloudClusterAdmin to manage Topics and ACLs"
}

resource "confluent_role_binding" "env_manager_cluster_admin" {
  principal   = "User:${confluent_service_account.env_manager.id}"
  role_name   = "CloudClusterAdmin"
  crn_pattern = confluent_kafka_cluster.basic.rbac_crn
}

resource "confluent_api_key" "env_manager_kafka_api_key" {
  display_name = "${var.prefix}-env-manager-kafka-api-key"
  description  = "Kafka API Key owned by env_manager to create topics and ACLs"
  owner {
    id          = confluent_service_account.env_manager.id
    api_version = confluent_service_account.env_manager.api_version
    kind        = confluent_service_account.env_manager.kind
  }
  managed_resource {
    id          = confluent_kafka_cluster.basic.id
    api_version = confluent_kafka_cluster.basic.api_version
    kind        = confluent_kafka_cluster.basic.kind
    environment {
      id = confluent_environment.env.id
    }
  }
  depends_on = [confluent_role_binding.env_manager_cluster_admin]
}

resource "confluent_service_account" "app" {
  display_name = "${var.prefix}-app-sa"
  description  = "Service Account para as aplicacoes Credit Hub"
}

resource "confluent_api_key" "app_kafka_api_key" {
  display_name = "${var.prefix}-kafka-api-key"
  description  = "Kafka API Key that is owned by app service account"
  owner {
    id          = confluent_service_account.app.id
    api_version = confluent_service_account.app.api_version
    kind        = confluent_service_account.app.kind
  }
  managed_resource {
    id          = confluent_kafka_cluster.basic.id
    api_version = confluent_kafka_cluster.basic.api_version
    kind        = confluent_kafka_cluster.basic.kind
    environment {
      id = confluent_environment.env.id
    }
  }
}

resource "confluent_kafka_acl" "app_read_group" {
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  resource_type = "GROUP"
  resource_name = "*"
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.app.id}"
  host          = "*"
  operation     = "READ"
  permission    = "ALLOW"
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
}

resource "confluent_kafka_acl" "app_write_group" {
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  resource_type = "GROUP"
  resource_name = "*"
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.app.id}"
  host          = "*"
  operation     = "WRITE"
  permission    = "ALLOW"
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
}

resource "confluent_kafka_acl" "app_read_topic" {
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  resource_type = "TOPIC"
  resource_name = "*"
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.app.id}"
  host          = "*"
  operation     = "READ"
  permission    = "ALLOW"
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
}

resource "confluent_kafka_acl" "app_write_topic" {
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  resource_type = "TOPIC"
  resource_name = "*"
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.app.id}"
  host          = "*"
  operation     = "WRITE"
  permission    = "ALLOW"
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
}

resource "confluent_kafka_acl" "app_create_topic" {
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  resource_type = "TOPIC"
  resource_name = "*"
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.app.id}"
  host          = "*"
  operation     = "CREATE"
  permission    = "ALLOW"
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
}

data "confluent_schema_registry_cluster" "sr" {
  environment {
    id = confluent_environment.env.id
  }
  depends_on = [time_sleep.wait_for_sr]
}

resource "confluent_api_key" "app_sr_api_key" {
  display_name = "${var.prefix}-sr-api-key"
  description  = "Schema Registry API Key that is owned by app service account"
  owner {
    id          = confluent_service_account.app.id
    api_version = confluent_service_account.app.api_version
    kind        = confluent_service_account.app.kind
  }
  managed_resource {
    id          = data.confluent_schema_registry_cluster.sr.id
    api_version = data.confluent_schema_registry_cluster.sr.api_version
    kind        = data.confluent_schema_registry_cluster.sr.kind
    environment {
      id = confluent_environment.env.id
    }
  }
}

locals {
  topics = [
    "consulta-credito-event",
    "consulta-credito-event-retry-0",
    "consulta-credito-event-retry-1",
    "consulta-credito-event-dlt"
  ]
}

resource "confluent_kafka_topic" "topics" {
  for_each = toset(local.topics)
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  topic_name    = each.key
  # Topico principal com 6 particoes: (a) habilita paralelismo real de consumers no futuro
  # (via KEDA Kafka scaler); (b) da sentido a chave de particao por CPF (ordenacao por documento
  # so importa com >1 particao). Retry/DLT ficam com 1 (baixo volume, ordenacao irrelevante).
  partitions_count = each.key == "consulta-credito-event" ? 6 : 1
  rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  credentials {
    key    = confluent_api_key.env_manager_kafka_api_key.id
    secret = confluent_api_key.env_manager_kafka_api_key.secret
  }
  depends_on = [
    confluent_kafka_acl.app_create_topic,
    confluent_kafka_acl.app_write_topic
  ]
}
