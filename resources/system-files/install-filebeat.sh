#!/usr/bin/bash
curl -o /home/core/filebeat-1.2.3-x86_64.tar.gz https://download.elastic.co/beats/filebeat/filebeat-1.2.3-x86_64.tar.gz
tar xvzf /home/core/filebeat-1.2.3-x86_64.tar.gz
mkdir -p /opt/bin
cp /home/core/filebeat-1.2.3-x86_64/filebeat /opt/bin/
