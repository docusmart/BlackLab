#!/bin/bash

set -e

export DOCKER_BUILDKIT=1

# Build and run BlackLab Server
# (--force-recreate to avoid error 'network not found')
if [ -z ${TOMCAT_APP_NAME}  ]; then
  docker-compose build testserver
else
  docker-compose build --build-arg TOMCAT_APP_NAME=${TOMCAT_APP_NAME} testserver
fi

docker-compose up --force-recreate -d testserver

# Build and run the test suite
docker-compose build test
docker-compose run --rm test

# Clean up
# (stop then down to avoid warning about network in use)
docker-compose stop testserver
docker-compose down -v
