#!/bin/sh
# Notify participating search engines after publishing changes to the project pages.
set -eu
curl --fail --silent --show-error \
  --header 'Content-Type: application/json; charset=utf-8' \
  --data-binary @- \
  --write-out '\nHTTP %{http_code}\n' \
  https://api.indexnow.org/indexnow <<'JSON'
{
  "host": "weakmafaka.github.io",
  "key": "f33a3c598f1c4044b7e285a611c374d7",
  "keyLocation": "https://weakmafaka.github.io/WeakBridge/f33a3c598f1c4044b7e285a611c374d7.txt",
  "urlList": [
    "https://weakmafaka.github.io/WeakBridge/",
    "https://weakmafaka.github.io/WeakBridge/ru/"
  ]
}
JSON
