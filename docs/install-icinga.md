# Installing Icinga2

TODO: automating most of this
On box:
```
add-apt-repository ppa:formorer/icinga
apt-get update
apt-get install icinga2
```

In the next step, when presented with the option, say no to dbconfig-common
```
apt-get install icinga2-ido-pgsql
```

Normally, terraform has set up an AWS RDS database, using postgresql and prod parameters, db.t2.small, and master user kixi.
Replace <rds-db-hostname> by the relevant database hostname from AWS.

```
icinga2 feature enable statusdata

apt-get install apache2

psql -c "CREATE ROLE icinga WITH LOGIN PASSWORD 'icinga'" -h <rds-db-hostname> -U kixi -W -d template1

psql -c "GRANT icinga to kixi" -h <rds-db-hostname> -U kixi -W -d template1

createdb -h <rds-db-hostname> -U kixi -W -O icinga -E UTF8 icinga

export PGPASSWORD=icinga
psql -U icinga -d icinga < /usr/share/icinga2-ido-pgsql/schema/pgsql.sql
psql -U icinga -d icinga < /usr/share/icinga2-ido-pgsql/schema/pgsql.sql -h <rds-db-hostname>

apt-get install apache2-utils

```

In /etc/icinga2/features-available/ido-pgsql.conf

```
library "db_ido_pgsql"

object IdoPgsqlConnection "ido-pgsql" {
  user = "icinga",
  password = "icinga",
  host = "<rds-db-hostname>",
  database = "icinga"
}
```

Continuing

```
icinga2 feature enable ido-pgsql

icinga2 feature enable command

usermod -a -G nagios www-data

apt-get install php5 php5-cli php-pear php5-xmlrpc php5-xsl php-soap php5-gd php5-ldap php5-pgsql
apt-get install icingaweb2

usermod -a -G icingaweb2 www-data

htpasswd -bc /etc/icingaweb2/passwd kixi <your-passwd>
# with basic-auth passwd
chown www-data:icingaweb2 /etc/icingaweb2/passwd

icingacli setup config directory --group icingaweb2
icingacli setup token create
```
Copy that last token, to use in the manual setup bit (after next snippet).

In /etc/php5/apache2/php.ini set
```
date.timezone = Europe/London
```

visit http://<ELB>/icingaweb2/
go through wizard, using the setup token saved above
(I checked 'monitoring' and 'doc' modules)
(I chose 'External' for authentication, which requires some form of authentication on the web server, we're doing basic auth)


To activate basic auth, go to /etc/apache2/conf-enabled/icingaweb2.conf and add to the <Directory ...> tag
First change `AllowOverride None` to `AllowOverride AuthConfig`

Then add

```
  AuthType Basic
  AuthName "Authentication Required"
  AuthUserFile "/etc/icingaweb2/passwd"
  Require valid-user

  Order allow,deny
  Allow from all
```

Some more installations to use nagions plugins if we wish
```
apt-get install nagios-plugins nagios-plugins-basic nagios-plugins-common nagios-plugins-standard nagios-plugins-contrib nagios-plugins-extra

apt-get install python-pip
pip install influx-nagios-plugin
```

# general configs

scp all the content of the resources/icinga2/conf.d directory to /etc/icinga2/conf.d

# slack integration

Copy and adapt the script slack-service-notification.sh in resources/icinga2/scripts to /etc/icinga2/scripts
Make sure a webhook integration exists in slack (create webhook) and copy the URL to use in the script.
Also
```
chmod +x /etc/icinga2/scripts/slack-service-notification.sh
```
