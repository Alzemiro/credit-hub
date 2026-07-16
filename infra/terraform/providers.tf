terraform {
  required_version = ">= 1.5.0"
  required_providers {
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 2.0"
    }
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.100.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6.0"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12.0"
    }
  }
}

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = true
      recover_soft_deleted_key_vaults = true
    }
    # O Azure cria sozinho o action group "Application Insights Smart Detection" no RG,
    # fora do Terraform. Sem esta flag o destroy do RG falha por conter recurso nao
    # gerenciado; com ela, o TF deleta o RG via API limpando os aninhados.
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
  }
}

provider "confluent" {
  # API Key for Confluent Cloud itself (not the cluster)
  # Export via CONFLUENT_CLOUD_API_KEY and CONFLUENT_CLOUD_API_SECRET
}
