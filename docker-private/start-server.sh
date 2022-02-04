#!/bin/bash

cd /usr/local/lib/blacklab-tools

# This directories need to remain in sycn with the index settings in blacklab-server.yaml
mkdir -p /data/index
mdkir -p /data/user-index
#rm -rf /data/test
#java -cp '*' nl.inl.blacklab.tools.IndexTool create /data/test /input voice-tei
cd /usr/local/tomcat && catalina.sh jpda run
