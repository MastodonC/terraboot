#!/usr/bin/env bash

MARATHON_HOST=${internal-lb}
ENVIRONMENT=${cluster-name}
# using deployment service sebastopol
NAME=$1
JSON_TEMPLATE=$2
TAG=$3
sed -e "s/@@TAG@@/$TAG/" -e "s/@@ENVIRONMENT@@/$ENVIRONMENT/" $JSON_TEMPLATE > deploy.json

echo "deleting $NAME app ..."
curl -X delete http://$MARATHON_HOST:8080/v2/apps/$NAME

# we want curl to output something we can use to indicate success/failure
echo "redeploying $NAME app ..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://$MARATHON_HOST:8080/v2/apps -H "Content-Type: application/json" --data-binary "@deploy.json")

echo "HTTP code " $STATUS
if [ $STATUS == "201" ]
then exit 0
else exit 1
fi
