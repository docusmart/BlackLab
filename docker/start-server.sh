#!/bin/bash

cd /usr/local/lib/blacklab-tools
rm -rf /data/test
java -cp '*' nl.inl.blacklab.tools.IndexTool create /data/test /input voice-tei
cd /usr/local/tomcat && catalina.sh jpda run
