terraform {
  required_version = ">= 1.5.0"
  required_providers {
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 1.72.0"
    }
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.100.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6.0"
    }
  }
}

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = true
      recover_soft_deleted_key_vaults = true
    }
  }
}

provider "confluent" {
  # API Key for Confluent Cloud itself (not the cluster)
  # Export via CONFLUENT_CLOUD_API_KEY and CONFLUENT_CLOUD_API_SECRET
}
