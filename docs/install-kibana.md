apt-get install nginx

edit
/etc/nginx/sites-enabled/default

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
