# Useful links

## prerequisites

Having DNS setup to use the amazon DNS.
The openvpn starting script will do this if you're on linux, on mac you might need a manual intervention to add the second address of the VPC address range.
Say if your VPC has CIDR 172.20.0.0/20
Then your nameserver should be 172.20.0.2

## Getting on the VPN

the VPN on the kixi cluster is

    vpn.mastodonc.net

(but the public IP address for the <vpc>-vpn box would also work)

## DC/OS Links

These are all set up by the DC/OS installation process, and live behind an nginx proxy.

DC/OS console

    http://<cluster-name>-masters.<vpc>-vpc.kixi

Mesos console

    http://<cluster-name>-masters.<vpc>-vpc.kixi/mesos

Marathon console

    http://<cluster-name>-masters.<vpc>-vpc.kixi/marathon

Exhibitor

    http://<cluster-name>-masters.<vpc>-vpc.kixi

For Cassandra API calls (and other services when set up vi dcos cli possibly)

    http://<cluster-name>-masters.<vpc>-vpc.kixi/service/cassandra/


## Monitoring, alerting

Logstash DNS: for posting to logstash, internal DNS

    logstash.<vpc>-vpc.kixi

Kibana

    ELB link, need to create DNS


Influxdb: for posting to influx, internal DNS


    influxdb.<vpc>-vpc.kixi

Icinga2

    https://alerts.mastodonc.net/icingaweb2/dashboard


Grafana

    https://grafana.mastodonc.net/
