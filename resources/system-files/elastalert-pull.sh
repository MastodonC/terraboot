#!/bin/bash
set -x
# Parse Args
REPO=${1:?Error: provide the Github repo as the first argument}
KEY=${2:?Error: provide the Github deploy key path as the second argument}
DIR=${3:?Error: provide the dir to use for the rules location as the third argument}

pushd () {
    command pushd "$@" > /dev/null
}

popd () {
    command popd "$@" > /dev/null
}

# Init

export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i $KEY"

#- Create Dir
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

