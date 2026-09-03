#!/usr/bin/env bash
# Delete everything this deployment created.
#
# Every resource lives in one resource group and nothing is created outside it, so this
# is complete. Deleting the group is also the only reliable way to stop the charges: a
# stopped container app still bills for its environment, and the database bills whether
# or not anything connects to it.
set -euo pipefail

RESOURCE_GROUP="${RESOURCE_GROUP:-rover-notes}"

echo "This deletes the resource group '$RESOURCE_GROUP' and everything in it."
read -r -p "Type the group name to confirm: " confirm
if [ "$confirm" != "$RESOURCE_GROUP" ]; then
  echo "aborted."
  exit 1
fi

az group delete --name "$RESOURCE_GROUP" --yes --no-wait
echo "deletion started. az group show --name $RESOURCE_GROUP reports progress."
