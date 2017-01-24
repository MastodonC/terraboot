```
apt-get install nginx
```

edit
/etc/nginx/sites-enabled/default
using the right URL for the elasticsearch kibana (to be found on the relevant elasticsearch cluster page on AWS console

```
map $http_x_forwarded_proto $real_scheme {
    default $http_x_forwarded_proto;
    ''      $scheme;
}

server {
    listen 80 default_server;
    server_name _;
    location = / {
      return 302 $real_scheme://$host/_plugin/kibana/;
    }
    location = /status {
      return 200;
    }
    location / {
      auth_basic "Restricted";
      auth_basic_user_file /etc/nginx/htpasswd;
      if ($http_x_forwarded_proto != "https") {
        rewrite ^(.*)$ https://$host$1 permanent;
      }
      proxy_pass https://search-sandpit-elasticsearch-xijn4teq3gmimkpzpy2ivbbnbm.eu-central-1.es.amazonaws.com;
      proxy_set_header Authorization "";
    }
}
```
Create htpasswd file:
```
apt-get install apache2-utils # to have htpasswd available
htpasswd -bc /etc/nginx/htpasswd <user> <passwd>
```

Reload nginx `service nginx reload`

This is not accessed directly, but goes through the (internal) kibana AWS ELB.
Outward side of listener to kibana ELB needs to be https! (see rewrite and proxy-pass in nginx config above)
Also edit policy to allow addresses to access kibana (all from VPC for instance, to allow people on vpn to surf logs).

*TODO*: trying to do more specific policy can lead to terraform cycles, as logstash config depends on elasticsearch address and elasticsearch policy depends on logstash address. Needs to be automated somehow
