#!/bin/bash

set -o xtrace
set -o errexit

echo "************************************** Publish docker ******************************************"

file='./ci/version'
VERSION_NUMBER=$(<"$file")

docker build --rm -f scriptsCI/docker/core/Dockerfile -t  cytomine/core:$VERSION_NUMBER .

docker image tag ubuntu 185.35.173.82:5000/core-$CUSTOMER:$VERSION_NUMBER

#docker push cytomine/core:$VERSION_NUMBER

#docker rmi cytomine/core:$VERSION_NUMBER