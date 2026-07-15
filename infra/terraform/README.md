# Infraestrutura as Code - Credit Hub

Este diretório contém a infraestrutura do Credit Hub construída com **Terraform**.

## Arquitetura Cloud (Topologia)
- **Azure Container Apps**: Hospeda 4 serviços:
  - `credit-query-service` (EXTERNAL Ingress)
  - `audit-service` (sem ingress)
  - `decision-consumer` (sem ingress)
  - `wiremock` (INTERNAL Ingress, montando arquivos do file share com mapeamentos)
- **Azure PostgreSQL Flexible Server**: Tier Basic (`B1ms`), contendo os 3 databases.
- **Azure Key Vault**: Gerenciamento e injeção de senhas para os containers via Container App Secrets.
- **Confluent Cloud**: Kafka serverless tier "Basic" rodando na **mesma região** do Azure para evitar latência/custos de egress. Usado com Stream Governance ESSENTIALS.

## Pré-requisitos
- [Terraform](https://developer.hashicorp.com/terraform/downloads) CLI (>= 1.5.0) instalado.
- [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli) instalado (`az login` executado).
- Conta no **Confluent Cloud**.
- API Key de uma Service Account na Confluent Cloud com permissão de `OrganizationAdmin` (para provisionar cluster, users e ACLs).

## Configuração do Entra ID (Federated Credentials para CI/CD)
O pipeline (`.github/workflows/deploy.yml` e `terraform.yml`) usa OIDC. Você precisa criar isso no Entra ID manualmente, uma vez:
```bash
# 1. Crie o App Registration (Service Principal)
APP_ID=$(az ad app create --display-name "github-actions-credithub" --query appId -o tsv)
SP_ID=$(az ad sp create --id $APP_ID --query id -o tsv)

# 2. Dê permissão de Contributor na Subscription
SUB_ID=$(az account show --query id -o tsv)
az role assignment create --role contributor --subscription $SUB_ID --assignee-object-id $SP_ID --assignee-principal-type ServicePrincipal --scope /subscriptions/$SUB_ID

# 3. Crie a Federated Credential vinculando ao repositório GitHub
# Substitua <GITHUB_USER> e <REPO_NAME> pelo seu repo
az ad app federated-credential create --id $APP_ID --parameters '{
  "name": "credithub-github-actions",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:<GITHUB_USER>/<REPO_NAME>:ref:refs/heads/main",
  "description": "Permite deploy via GitHub Actions",
  "audiences": ["api://AzureADTokenExchange"]
}'

# O client-id é $APP_ID e tenant-id pode ser visto com az account show --query tenantId -o tsv
```

## Como aplicar a infraestrutura localmente (Dry-Run / Plan)

Antes de rodar, defina as credenciais no seu terminal (evite versionar no código):

```bash
export TF_VAR_postgres_admin_password="UmaSenhaForte123!"
export CONFLUENT_CLOUD_API_KEY="SuaApiKeyDoCloud"
export CONFLUENT_CLOUD_API_SECRET="SuaApiSecretDoCloud"
```

1. **Inicialize o Terraform:**
   ```bash
   terraform init
   ```
2. **Valide a sintaxe:**
   ```bash
   terraform validate
   ```
3. **Visualize o Plano:**
   ```bash
   terraform plan
   ```

*(Não aplique diretamente; o deploy deve passar pelo pipeline GitHub Actions após aprovação!)*

---
> ⚠️ **ATENÇÃO: DESTRUIÇÃO E CUSTOS**
>
> Para evitar cobranças inesperadas quando não estiver usando:
> ```bash
> terraform destroy
> ```
> O Confluent Cloud não foi criado com `prevent_destroy = true` para garantir que o comando funcione. **Recomenda-se configurar um Budget Alert de $5 no Azure Cost Management.**
