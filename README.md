# VNETGPS

An Android telemetry collector. Three foreground services capture location,
health (heart rate and skin temperature), and microphone audio, and
stream them off the device over kafka [device-bridge](https://github.com/victorhoppenot/device-bridge) and nats for audio streams.

## Configuration

```sh
cp .env.example .env
```

```sh
NATS_HOST=100.x.y.z     # nats node (preferably over a secure connection)
NATS_PORT=4222
BRIDGE_HOST=100.x.y.z   # device-bridge node
BRIDGE_PORT=3000
```

## Build

Requires an Android SDK with API 37.

`minSdk` 34, `targetSdk` 37, AGP 9.3.1, Gradle 9.5.

