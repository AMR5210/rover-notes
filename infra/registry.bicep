// The container registry, deployed before the images exist.
//
// Separate from main.bicep because of an ordering constraint: a container app cannot be
// created referencing an image that has not been pushed, and the images cannot be pushed
// until there is a registry to push them to.

param location string = resourceGroup().location
@minLength(3)
@maxLength(11)
param namePrefix string = 'rovernotes'

var suffix = uniqueString(resourceGroup().id)

resource registry 'Microsoft.ContainerRegistry/registries@2023-11-01-preview' = {
  name: toLower('${namePrefix}acr${take(suffix, 8)}')
  location: location
  sku: { name: 'Basic' }
  properties: {
    adminUserEnabled: false
  }
}

output loginServer string = registry.properties.loginServer
