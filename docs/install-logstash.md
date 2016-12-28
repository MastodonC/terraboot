Set hostname

```
add-apt-repository ppa:webupd8team/java -y
apt-get update
echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections
apt-get install oracle-java8-installer
apt-get install oracle-java8-set-default
```

```
wget -qO - https://packages.elastic.co/GPG-KEY-elasticsearch | apt-key add -
echo "deb http://packages.elastic.co/logstash/2.2/debian stable main" > /etc/apt/sources.list.d/logstash.list
apt-get update
apt-get install logstash
update-rc.d logstash defaults
```

/opt/logstash/bin/plugin install logstash-output-amazon_es
(not working yet with AWS driver though)

```
input{
    beats {
        port => 9200
#        ssl => true
#        ssl_certificate => "/etc/pki/tls/certs/logstash-forwarder.crt"
#        ssl_key => "/etc/pki/tls/private/logstash-forwarder.key"
    }
}

input{
  file {
    path => ["/tmp/logstashtest"]
  }
}

input {
  gelf {
    type => docker
    port => 12201
  }
}

output{
    elasticsearch {
        hosts => ["search-sandpit-elasticsearch-xijn4teq3gmimkpzpy2ivbbnbm.eu-central-1.es.amazonaws.com:80"]
        document_type => "%{[@metadata][type]}"
    }
}

output {
 file {
   path => "/var/log/logstash/all.log"
   flush_interval => 0
 }
}
```
