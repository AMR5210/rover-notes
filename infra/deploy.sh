#!/usr/bin/env bash
# Build the three images, push them to the registry, and deploy.
#
# Idempotent: run it again after a code change and it rebuilds, pushes new tags and
# updates the container apps in place.
#
# Teardown is `infra/destroy.sh`, which deletes the resource group. Nothing is created
# outside it, so that is a complete removal.
set -euo pipefail

RESOURCE_GROUP="${RESOURCE_GROUP:-rover-notes}"
LOCATION="${LOCATION:-eastus}"
NAME_PREFIX="${NAME_PREFIX:-rovernotes}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"

for required in POSTGRES_PASSWORD ANTHROPIC_API_KEY ROVER_KEY_ENCRYPTION_KEY MAIL_HOST; do
  if [ -z "${!required:-}" ]; then
    echo "error: $required is not set." >&2
    echo "  POSTGRES_PASSWORD          any strong password; Azure enforces its own rules" >&2
    echo "  ANTHROPIC_API_KEY          from console.anthropic.com" >&2
    echo "  ROVER_KEY_ENCRYPTION_KEY   openssl rand -base64 32" >&2
    echo "  MAIL_HOST                  SMTP host for account email" >&2
    echo >&2
    echo "  MAIL_HOST is required because the API refuses to start without it. Account" >&2
    echo "  email — verification, password reset — is sent by a component active outside" >&2
    echo "  the local profile, and it depends on a mail sender that Spring builds only" >&2
    echo "  when a host is configured. Failing here is the same decision, moved earlier." >&2
    echo >&2
    echo "  Optional alongside it: MAIL_PORT (587), MAIL_USERNAME, MAIL_PASSWORD," >&2
    echo "  MAIL_FROM." >&2
    exit 1
  fi
done

echo "==> resource group $RESOURCE_GROUP in $LOCATION"
az group create --name "$RESOURCE_GROUP" --location "$LOCATION" --output none

# The registry is deployed first, on its own, because the images have to exist before the
# container apps that reference them are created.
echo "==> container registry"
REGISTRY=$(az deployment group create \
  --resource-group "$RESOURCE_GROUP" \
  --template-file infra/registry.bicep \
  --parameters namePrefix="$NAME_PREFIX" location="$LOCATION" \
  --query properties.outputs.loginServer.value --output tsv)
echo "    $REGISTRY"

echo "==> building and pushing images at tag $TAG"
az acr login --name "${REGISTRY%%.*}"
for service in api ml-service web; do
  image="$REGISTRY/rover-$service:$TAG"
  echo "    $image"
  docker build --quiet --tag "$image" "$service"
  docker push --quiet "$image"
done

echo "==> deploying"
az deployment group create \
  --resource-group "$RESOURCE_GROUP" \
  --template-file infra/main.bicep \
  --parameters \
      namePrefix="$NAME_PREFIX" \
      location="$LOCATION" \
      postgresPassword="$POSTGRES_PASSWORD" \
      anthropicApiKey="$ANTHROPIC_API_KEY" \
      keyEncryptionKey="$ROVER_KEY_ENCRYPTION_KEY" \
      mailHost="$MAIL_HOST" \
      mailPort="${MAIL_PORT:-587}" \
      mailUsername="${MAIL_USERNAME:-}" \
      mailPassword="${MAIL_PASSWORD:-}" \
      mailFrom="${MAIL_FROM:-no-reply@rovernotes.dev}" \
      apiImage="$REGISTRY/rover-api:$TAG" \
      mlImage="$REGISTRY/rover-ml-service:$TAG" \
      webImage="$REGISTRY/rover-web:$TAG" \
  --output none

WEB_URL=$(az deployment group show --resource-group "$RESOURCE_GROUP" --name main \
  --query properties.outputs.webUrl.value --output tsv 2>/dev/null || true)

echo
echo "deployed. ${WEB_URL:-check the portal for the frontend URL}"
echo "tear down with: infra/destroy.sh"
