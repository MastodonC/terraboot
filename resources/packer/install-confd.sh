#!/usr/bin/env bash

/usr/bin/curl --fail --retry 20 --location --silent --show-error --verbose --output /tmp/confd-0.11.0 https://s3.eu-central-1.amazonaws.com/witan-cf-templates/confd-0.11.0-linux-amd64
/usr/bin/mkdir -p /opt/mesosphere/bin
/usr/bin/mv /tmp/confd-0.11.0 /opt/mesosphere/bin/
/usr/bin/ln -s /opt/mesosphere/bin/confd-0.11.0 /opt/mesosphere/bin/confd
/usr/bin/mkdir -p /etc/confd/conf.d
/usr/bin/mkdir -p /etc/confd/templates
/usr/bin/chmod +x /opt/mesosphere/bin/confd-0.11.0
/usr/bin/rm -f /tmp/confd-0.11.0
