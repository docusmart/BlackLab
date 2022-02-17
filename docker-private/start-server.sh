#!/bin/bash


# This directories need to remain in sycn with the index settings in blacklab-server.yaml
mkdir /data/index
mkdir /data/user-index

cd /usr/local/lib/blacklab-tools
rm -rf /data/index/test
java -cp '*' nl.inl.blacklab.tools.IndexTool create /data/index/test /input/\*.xml voice-tei
cd /usr/local/tomcat && catalina.sh jpda run
