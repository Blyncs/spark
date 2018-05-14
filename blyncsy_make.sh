#!/bin/bash
set -x

export MAVEN_OPTS="-Xmx2g -XX:ReservedCodeCacheSize=512m"

./build/mvn -Pkubernetes -DskipTests clean package

