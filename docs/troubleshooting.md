# Troubleshooting

## Starting the cluster

### DC/OS console is not starting

ssh into a master box and run

    journalctl

To see if there are any errors in the DC/OS setup


## deployment

### Is the slave disk full (without mesos knowning) because of docker?

Log on into the slave (ssh core@<ip> when on the VPN)
Check for disk space `df -h` - if the main disk is at 100%, this may be your problem.
Solution

    docker rmi $(docker images -a -q)
    docker rm $(docker ps -a -q)`

If it doesn't release space properly, stop docker, rm -rf /var/lib/docker and restart (but this means pulling all images again).


### Is it not even starting to deploy (staying in staging mode, 0 of 1)

There may be a resource contention: whether memory, CPU or ports. Check whether the ports your application requires are free, and whether the resources are present on one of the slaves.  This can be done by checking the mesos state-summary.

    curl http://staging-masters.sandpit-vpc.kixi/mesos/state-summary

(View the output with formatted json)

### It's deploying and starting but doesn't turn green

Does the health check work?  The health check should work from the masters.  It can be TCP, UDP or HTTP, documentation here <https://mesosphere.github.io/marathon/docs/health-checks.html>.

ssh into either the slave or the master box, and attempt to check manually.

HTTP: with curl
TCP, UDP:

    netstat -a | grep LISTEN

to see all the listening ports.

## Marathon

### After starting a marathon framework and stopping it, it sometimes keeps a new one from starting (C*, kafka)

Sometimes just removing a process from Marathon doesn't completely remove all the traces of a process.  Sometimes the framework needs torn down.

    curl -d@delete.txt -X POST http://staging-masters.sandpit-vpc.kixi/mesos/master/teardown

with delete.txt containing a string which is frameworkId=xyz

Then all traces must be removed in Zookeeper and similar (described [here](https://docs.mesosphere.com/1.7/usage/managing-services/uninstall/)).
For isntance for cassandra:

```
docker run mesosphere/janitor /janitor.py -r cassandra-role -p cassandra-principal -z dcos-service-cassandra
```
