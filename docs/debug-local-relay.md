# Debug loopback transport

The Debug APK permits HTTP only to `localhost` and `127.0.0.1`, without subdomains. Its base policy denies other cleartext traffic. Release does not include this network-security resource or manifest attribute. Model clients and TLS validation are unchanged.

An explicitly owned ADB reverse can route the device loopback endpoint to a host HTTPS relay while AndroidWorld retains its official device date. Do not expose an ADB server publicly or use a shared server for evaluation. The optional executor and private reverse lifecycle are delivered separately in the AndroidWorld harness PR; this APK change has no code dependency on that executor.

Never commit credentials, request bodies, device screenshots or relay logs. Use an explicit endpoint through the existing Debug configuration interface. Allowing loopback HTTP does not authenticate a relay; the executor must enforce authorization and standard upstream TLS.

Validation: inspect Debug and Release merged manifests; the network-security attribute and resource must be Debug-only. No real model request is required for this check.
