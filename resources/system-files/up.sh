#!/usr/bin/env sh
/sbin/sysctl -p
/sbin/sysctl -w net.ipv4.ip_forward=1
/sbin/iptables -t nat -A POSTROUTING -s 10.20.0.0/24 -o eth0 -j MASQUERADE
