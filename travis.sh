#!/bin/bash

set -euo pipefail

function installTravisTools {
  mkdir ~/.local
  curl -sSL https://github.com/SonarSource/travis-utils/tarball/6b72fc8fdd1842b1da2a32f4188892a2fb8fcf9a | tar zx --strip-components 1 -C ~/.local
  source ~/.local/bin/install
}

installTravisTools

function strongEcho {
  echo ""
  echo "================ $1 ================="
}

# Do not deploy a SNAPSHOT version but the release version related to this build
export TYCHO_BUILD=true
set_maven_build_version $TRAVIS_BUILD_NUMBER

case "$TARGET" in

CI)
  #Temporary for checking deployment repoX
  mvn deploy \
      -Pdeploy-sonarsource \
      -B -e -V -Dtycho.disableP2Mirrors=true
  ;;

IT)
  mvn verify -B -e -V -Dtycho.disableP2Mirrors=true -Dtarget.platform=$TARGET_PLATFORM
  ;;
*)
  echo "Unexpected TARGET value: $TARGET"
  exit 1
  ;;

esac
