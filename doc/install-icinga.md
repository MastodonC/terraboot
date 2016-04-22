add-apt-repository ppa:formorer/icinga
apt-get update
apt-get install icinga2
apt-get install icinga2-ido-pgsql
# dont take db config option

icinga2 feature enable statusdata

apt-get install apache2

psql -c "CREATE ROLE icinga WITH LOGIN PASSWORD 'icinga'" -h
sandpit-alerts.cp1dovhlduxq.eu-central-1.rds.amazonaws.com -U kixi -W -d template1

psql -c "GRANT icinga to kixi" -h
sandpit-alerts.cp1dovhlduxq.eu-central-1.rds.amazonaws.com -U kixi -W -d template1

createdb -h sandpit-alerts.cp1dovhlduxq.eu-central-1.rds.amazonaws.com -U kixi -W -O icinga -E UTF8 icinga

export PGPASSWORD=icinga
psql -U icinga -d icinga < /usr/share/icinga2-ido-pgsql/schema/pgsql.sql
psql -U icinga -d icinga < /usr/share/icinga2-ido-pgsql/schema/pgsql.sql -h sandpit-alerts.cp1dovhlduxq.eu-central-1.rds.amazonaws.com

apt-get install apache2-utils
htpasswd -c passwd /etc/icingaweb2/passwd kixi
chown www-data:icingaweb2 passwd

/etc/icinga2/features-available/ido-pgsql.conf
```
library "db_ido_pgsql"

object IdoPgsqlConnection "ido-pgsql" {
  user = "icinga",
  password = "icinga",
  host = "sandpit-alerts.cp1dovhlduxq.eu-central-1.rds.amazonaws.com",
  database = "icinga"
}
```

icinga2 feature enable ido-pgsql

icinga2 feature enable command

usermod -a -G nagios www-data

apt-get install php5 php5-cli php-pear php5-xmlrpc php5-xsl php-soap php5-gd php5-ldap php5-pgsql
apt-get install icingaweb2

usermod -a -G icingaweb2 www-data;

icingacli setup config directory --group icingaweb2
icingacli setup token create

visit http://<ELB>/icingaweb2/

/etc/php5/apache2/php.ini
set
date.timezone = Europe/London

apt-get install nagios-plugins nagios-plugins-basic nagios-plugins-common nagios-plugins-standard nagios-plugins-contrib nagios-plugins-extra

apt-get install python-pip
pip install influx-nagios-plugin
