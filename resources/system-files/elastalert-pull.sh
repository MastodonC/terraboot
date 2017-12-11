#!/bin/bash
# Parse Args
REPO=${1}
KEY=${2}
DIR=${3}

pushd () {
    command pushd "$@" > /dev/null
}

popd () {
    command popd "$@" > /dev/null
}

# Init

export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i $KEY"
if [[ ! -d /opt/elastalert/src/rules ]] ; then
  mkdir -p /opt/elastalert/src
  pushd /opt/elastalert/src
  git clone $REPO rules
  popd
  pushd /opt/elastalert
  ln -s src/rules/$DIR rules
  popd
fi

# Main
pushd /opt/elastalert/src/rules
git pull
popd

