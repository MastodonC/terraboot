mkdir -p dcos && cd dcos &&
curl -O https://downloads.mesosphere.com/dcos-cli/install.sh &&
bash ./install.sh . http://${internal-lb} &&
source ./bin/env-setup
