curl -sL https://repos.influxdata.com/influxdb.key | sudo apt-key add -
source /etc/lsb-release 
echo "deb https://repos.influxdata.com/${DISTRIB_ID,,} ${DISTRIB_CODENAME} stable" | sudo tee /etc/apt/sources.list.d/influxdb.list |
sudo apt-get update && sudo apt-get install influxdb &&
sudo service influxdb start

Create htpasswd file:
```
apt-get install apache2-utils # to have htpasswd available
htpasswd -bc /etc/nginx/htpasswd <user> <passwd>
```


apt-get install nginx

edit `/etc/nginx/sites-enabled/default`

```
server {

        map $http_x_forwarded_proto $real_scheme {
          default $http_x_forwarded_proto;
          ''      $scheme;
        }
        listen 80 default_server;
        server_name _;
        location = /status {
          return 200;
        }
        location / {
          auth_basic "Restricted";
          auth_basic_user_file /etc/nginx/htpasswd;
          if ($http_x_forwarded_proto != "https") {
            rewrite ^(.*)$ https://$host$1 permanent;
          }
          proxy_pass https://localhost:10000;
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
create database cadvisor_staging
```
!! Add a retention policy otherwise they will fill up quickly
```
create retention policy one_week on cadvisor_staging duration 1w replication 1 default;
```
