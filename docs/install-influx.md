```
curl -sL https://repos.influxdata.com/influxdb.key | sudo apt-key add -
source /etc/lsb-release
echo "deb https://repos.influxdata.com/${DISTRIB_ID,,} ${DISTRIB_CODENAME} stable" | sudo tee /etc/apt/sources.list.d/influxdb.list |
sudo apt-get update && sudo apt-get install influxdb &&
sudo service influxdb start
sudo apt-get install nginx
```

Create htpasswd file:
```
apt-get install apache2-utils # to have htpasswd available
htpasswd -bc /etc/nginx/htpasswd <user> <passwd>
```
edit `/etc/nginx/sites-enabled/default`

```
server {
        listen 80 default_server;
        server_name _;
        location = /status {
          return 200;
        }
        location / {
# TODO fix ssl (see earlier commits for https enforcement)
#         auth_basic "Restricted";
#         auth_basic_user_file /etc/nginx/htpasswd;
          proxy_pass http://localhost:3000;
        }
}
```
In nginx.conf (http section)
```
        map $http_x_forwarded_proto $real_scheme {
          default $http_x_forwarded_proto;
          ''      $scheme;
        }
```

Note: databases shoudl be created manually in influxdb. using `influx` cli:
```
create database cadvisor_staging;
create database metrics;
```
!! Add a retention policy otherwise they will fill up quickly
```
create retention policy two_weeks on cadvisor_staging duration 2w replication 1 default;
create retention policy two_weeks on metrics duration 2w replication 1 default;
```

Install grafana

```
curl https://packagecloud.io/gpg.key | sudo apt-key add -
echo "deb https://packagecloud.io/grafana/stable/debian/ jessie main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt-get update
sudo apt-get install grafana
sudo service grafana-server start
```
