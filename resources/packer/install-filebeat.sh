!#/usr/bin/env bash

/usr/bin/curl -o /home/core/filebeat-1.2.1-x86_64.tar.gz https://download.elastic.co/beats/filebeat/filebeat-1.2.1-x86_64.tar.gz
/usr/bin/tar xvzf /home/core/filebeat-1.2.1-x86_64.tar.gz
cd /home/core/filebeat-1.2.1-x86_64
cat > /home/core/filebeat-1.2.1-x86_64/filebeat.yml <<EOF
filebeat:
  prospectors:
    -
      paths:
        - /var/log/mesos/*.INFO
        - /var/log/mesos/*.WARNING
        - /var/log/mesos/*.ERROR

output:
  logstash:
    hosts: ["logstash.mastodonc.net:9200"]
    tls:
      certificate_authorities: ["/home/core/ca.crt"]
      certificate: "/home/core/client.pem"
      certificate_key: "/home/core/client.key"
EOF
