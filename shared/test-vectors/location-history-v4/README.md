# Shared Location History v4 Test Vectors

This directory stores deterministic JSON fixtures for `ReconstructLocationDay` (`VERSION = 4`), cross-checked by Kotlin (`logdate-mobile`), Swift (`logdate-apple`), and Rust (`logdate-linux`).

Each fixture file (`01_desk_wifi_drift.json` through `10_mock_gps_contamination.json`) encodes synthetic coordinates, `activities`, `places`, `observations`, `corrections`, and `expected` outcomes using `GoldenLocationVector`. No real user coordinates or device identities appear in any fixture.
