#!/usr/bin/env bash

/usr/bin/curl --fail --retry 20 --location --silent --show-error --verbose --output /tmp/confd-0.11.0 https://s3.eu-central-1.amazonaws.com/witan-cf-templates/confd-0.11.0-linux-amd64
/bin/mv /tmp/confd-0.11.0 /usr/local/bin/
/bin/ln -s /usr/local/bin/confd-0.11.0 /usr/local/bin/confd
/bin/mkdir -p /etc/confd/conf.d
/bin/mkdir -p /etc/confd/templates
/bin/chmod +x /usr/local/bin/confd-0.11.0
/bin/rm -f /tmp/confd-0.11.0
