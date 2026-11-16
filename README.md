# hearthline-ha

Standalone Home Assistant integration source for the separate Android tablet app in `pixel-home`. This branch preserves the legacy YAML display bridge as the integration baseline. The tablet app, Gradle project, APKs, Android tests and Sendspin source are maintained outside this repository.

Install `custom_components/pixel_home` into your HA configuration. The integration domain remains `pixel_home`. See `examples/legacy-configuration.yaml` for legacy provider settings and `display-contract` for display schemas.

Run existing display/provider tests:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
```

The feature PR adds integration-managed tablet setup, configuration delivery, pairing and telemetry to this baseline.
