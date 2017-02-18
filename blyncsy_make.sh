#!/bin/bash
set -x

export MAVEN_OPTS="-Xmx2g -XX:ReservedCodeCacheSize=512m"

./build/mvn -Pyarn -Phadoop-2.7 -Dhadoop.version=2.7.3 -DskipTests clean package


