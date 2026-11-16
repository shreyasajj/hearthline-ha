# Vendored Sendspin player

Source: https://github.com/chrisuthe/SendspinDroid
Commit: bd1d90a8f79b8b78c1ee924a0e6c14016ed1f1e2
License: MIT, see LICENSE (also shipped in APK assets/licenses).

The shared and player library source/test trees are copied from this commit. Pixel Home supplies its own Android lifecycle, encrypted persistence, pairing UI and audio-focus owner. No upstream app UI, services or account settings are used. Build scripts adapt the libraries to this project's AGP 8.7.3, API 35 and Java 17 using the older supported Android KMP target.

Source adaptation: WebSocketTransport.buildWebSocketUrl accepts validated explicit ws/wss authorities from Pixel Home, preserving TLS scheme; original host:port callers retain their behavior.
