# terraboot
DSL to generate terraform configuration and run it

# Usage
## Setup

You need to create `./vpc/creds.tf` from `./vpc/creds.tf.example`

## Running it
At the moment

```
# lein run
```

Will generate a JSON formatted terraform file in `./vpc` that you can
use with the terraform CLI

```
# terraform plan vpc/

# terraform apply vpc/
```
