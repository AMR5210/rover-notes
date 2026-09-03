// Rover Notes on Azure Container Apps.
//
// One resource group holds everything, so `az group delete` is a complete teardown and
// nothing is left billing after a demo.
//
// The three services run as container apps against a managed PostgreSQL Flexible Server
// with pgvector, and a storage account for uploaded originals. Only the frontend is
// reachable from the internet; the API and the ML service take internal ingress and are
// addressable by name inside the environment.

@description('Region for every resource. Postgres Flexible Server and Container Apps must agree.')
param location string = resourceGroup().location

@description('Prefix for resource names. Storage and registry names have their own rules, so this is normalised below.')
@minLength(3)
@maxLength(11)
param namePrefix string = 'rovernotes'

@description('Administrator login for PostgreSQL.')
param postgresAdmin string = 'rover'

@secure()
@description('Administrator password for PostgreSQL. Supply at deploy time; never defaulted.')
param postgresPassword string

@secure()
@description('Anthropic API key used for answer generation.')
param anthropicApiKey string

@secure()
@description('32 bytes, base64, protecting the signing keys at rest. openssl rand -base64 32')
param keyEncryptionKey string

@description('SMTP host for account email. The API refuses to start without one: SmtpMailer is active outside the local profile and Boot creates a JavaMailSender only when spring.mail.host is set.')
param mailHost string

@description('SMTP port.')
param mailPort int = 587

@description('SMTP username.')
param mailUsername string = ''

@secure()
@description('SMTP password.')
param mailPassword string = ''

@description('Address account email is sent from.')
param mailFrom string = 'no-reply@rovernotes.dev'

@description('Container image tags, supplied by the deploy script after it pushes them.')
param apiImage string
param mlImage string
param webImage string

var suffix = uniqueString(resourceGroup().id)
var storageName = toLower('${namePrefix}${take(suffix, 8)}')
var postgresName = toLower('${namePrefix}-pg-${take(suffix, 6)}')

// The address a browser uses, which is the frontend's — it is the only app with external
// ingress, and it proxies /oauth2, /login and /.well-known through to the API. Derived
// from the environment's domain rather than read off the web app, because the API needs
// it and the API is declared first; a reference the other way would be a cycle.
//
// This is the value the identity module cannot be given a default for: tokens carry it as
// their issuer and clients validate against it, so a deployment that fell back to
// localhost would sign tokens nothing could accept.
var webOrigin = 'https://${namePrefix}-web.${environment.properties.defaultDomain}'

// ---------------------------------------------------------------- observability

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${namePrefix}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    // Shortest supported retention. Logs are for reading during a demo, not for keeping.
    retentionInDays: 30
  }
}

// ---------------------------------------------------------------- data

// Burstable tier: this workload is one user during a demo, and the general-purpose tiers
// cost several times as much for headroom nothing uses.
resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: postgresName
  location: location
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '17'
    administratorLogin: postgresAdmin
    administratorLoginPassword: postgresPassword
    storage: {
      storageSizeGB: 32
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    // Public networking with a firewall rule for Azure services. A private endpoint is the
    // right answer for anything holding real data; it also needs a VNet, a private DNS
    // zone and a NAT gateway, which is most of the cost of this deployment.
    network: {
      publicNetworkAccess: 'Enabled'
    }
  }
}

resource postgresDatabase 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: postgres
  name: 'rover'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// pgvector has to be allow-listed on the server before an extension can be created in the
// database. Without this, V1__baseline.sql fails on `create extension vector`.
resource allowExtensions 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: postgres
  name: 'azure.extensions'
  properties: {
    value: 'VECTOR,PG_TRGM'
    source: 'user-override'
  }
}

resource allowAzureServices 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: postgres
  name: 'allow-azure-services'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: {
    allowBlobPublicAccess: false
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
}

resource documents 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: 'rover-documents'
  properties: {
    publicAccess: 'None'
  }
}

// ---------------------------------------------------------------- registry

// Created by infra/registry.bicep before the images are pushed, and referenced here.
// A container app cannot be created against an image that does not exist yet, so the
// registry has to be deployed, and pushed to, in an earlier step.
resource registry 'Microsoft.ContainerRegistry/registries@2023-11-01-preview' existing = {
  name: toLower('${namePrefix}acr${take(suffix, 8)}')
}

// ---------------------------------------------------------------- compute

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${namePrefix}-env'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
  }
}

// The API holds a system-assigned identity, which is what reads and writes blobs. No
// storage key is issued, configured or rotated.
resource api 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${namePrefix}-api'
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      ingress: {
        // Internal: the frontend proxies to it, and nothing else should reach it.
        external: false
        targetPort: 8080
        transport: 'auto'
      }
      // Pulling with the app's own identity. The registry has no admin user, so without
      // this the image pull is unauthenticated and the revision never starts.
      registries: [
        { server: registry.properties.loginServer, identity: 'system' }
      ]
      secrets: [
        { name: 'postgres-password', value: postgresPassword }
        { name: 'anthropic-api-key', value: anthropicApiKey }
        { name: 'key-encryption-key', value: keyEncryptionKey }
        { name: 'mail-password', value: mailPassword }
      ]
    }
    template: {
      containers: [
        {
          name: 'api'
          image: apiImage
          resources: {
            cpu: json('1.0')
            memory: '2Gi'
          }
          env: [
            { name: 'POSTGRES_URL', value: 'jdbc:postgresql://${postgres.properties.fullyQualifiedDomainName}:5432/rover?sslmode=require' }
            { name: 'POSTGRES_USER', value: postgresAdmin }
            { name: 'POSTGRES_PASSWORD', secretRef: 'postgres-password' }
            { name: 'ANTHROPIC_API_KEY', secretRef: 'anthropic-api-key' }
            { name: 'ROVER_KEY_ENCRYPTION_KEY', secretRef: 'key-encryption-key' }
            { name: 'EMBEDDINGS_URL', value: 'http://${namePrefix}-ml' }
            { name: 'RERANKER_URL', value: 'http://${namePrefix}-ml' }
            { name: 'ROVER_PARSING_URL', value: 'http://${namePrefix}-ml' }
            // Azure Blob, addressed by account URL and reached with the identity above.
            { name: 'ROVER_STORAGE_PROVIDER', value: 'azure-blob' }
            { name: 'ROVER_STORAGE_ACCOUNT_URL', value: storage.properties.primaryEndpoints.blob }
            { name: 'ROVER_STORAGE_BUCKET', value: 'rover-documents' }
            // Identity. The issuer is the frontend's origin rather than the API's own,
            // because that is the address a browser reaches: sign-in, the discovery
            // documents and the JWKS are all proxied through it, and a token whose issuer
            // names the internal FQDN would be rejected by the client that received it.
            { name: 'ROVER_ISSUER_URI', value: webOrigin }
            { name: 'ROVER_INTERFACE_URL', value: webOrigin }
            { name: 'ROVER_WEB_REDIRECT_URIS', value: '${webOrigin}/auth/callback' }
            // Account email. SmtpMailer is active outside the local profile and depends on
            // a JavaMailSender, which Boot builds only when spring.mail.host is set, so
            // these are what the container starts or fails on.
            { name: 'SPRING_MAIL_HOST', value: mailHost }
            { name: 'SPRING_MAIL_PORT', value: string(mailPort) }
            { name: 'SPRING_MAIL_USERNAME', value: mailUsername }
            { name: 'SPRING_MAIL_PASSWORD', secretRef: 'mail-password' }
            { name: 'ROVER_MAIL_FROM', value: mailFrom }
          ]
          probes: [
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              initialDelaySeconds: 30
              periodSeconds: 30
            }
            {
              type: 'Readiness'
              httpGet: { path: '/actuator/health/readiness', port: 8080 }
              initialDelaySeconds: 20
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        // One replica. The rate limiter keys per caller in Postgres and would behave
        // correctly across replicas, but a single instance keeps the demo's cost and its
        // latency figures comparable to the local measurements.
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
}

resource ml 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${namePrefix}-ml'
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      ingress: {
        external: false
        targetPort: 8000
        transport: 'auto'
      }
      registries: [
        { server: registry.properties.loginServer, identity: 'system' }
      ]
    }
    template: {
      containers: [
        {
          name: 'ml'
          image: mlImage
          // Embedding is CPU-bound and is the largest contributor to retrieval latency
          // under load, so this is the container to grow first.
          resources: {
            cpu: json('1.0')
            memory: '2Gi'
          }
          probes: [
            {
              type: 'Readiness'
              httpGet: { path: '/health', port: 8000 }
              initialDelaySeconds: 10
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
}

resource web 'Microsoft.App/containerApps@2024-03-01' = {
  name: '${namePrefix}-web'
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      ingress: {
        // The only public surface. Container Apps terminates TLS and supplies the
        // certificate for its own domain.
        external: true
        targetPort: 3000
        transport: 'auto'
        allowInsecure: false
      }
      registries: [
        { server: registry.properties.loginServer, identity: 'system' }
      ]
    }
    template: {
      containers: [
        {
          name: 'web'
          image: webImage
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            // Every browser-facing path is proxied through here, so the session cookie
            // stays first-party.
            { name: 'API_BASE_URL', value: 'https://${api.properties.configuration.ingress.fqdn}' }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 2
      }
    }
  }
}

// ---------------------------------------------------------------- authorisation

// Storage Blob Data Contributor, granted to the API's identity over this account alone.
// This is what replaces a connection string: the role is the credential.
var blobContributor = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
)

resource apiBlobAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: storage
  name: guid(storage.id, api.id, blobContributor)
  properties: {
    roleDefinitionId: blobContributor
    principalId: api.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

var acrPull = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '7f951dda-4ed3-4680-a7ca-43fe172d538d'
)

resource apiPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, api.id, acrPull)
  properties: {
    roleDefinitionId: acrPull
    principalId: api.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

resource mlPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, ml.id, acrPull)
  properties: {
    roleDefinitionId: acrPull
    principalId: ml.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

resource webPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, web.id, acrPull)
  properties: {
    roleDefinitionId: acrPull
    principalId: web.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

output registryLoginServer string = registry.properties.loginServer
output webUrl string = 'https://${web.properties.configuration.ingress.fqdn}'
output apiInternalUrl string = 'https://${api.properties.configuration.ingress.fqdn}'
output postgresHost string = postgres.properties.fullyQualifiedDomainName
output storageAccount string = storage.name
