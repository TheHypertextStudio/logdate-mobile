# Continuous integration

This repository follows the Studio CI standard in logdate-web `docs/ci.md`. Every workflow starts
with the shared route job (`.github/workflows/studio-route.yml` and `scripts/ci/studio-route.py`),
and each job takes its runner from it: the Studio pool for its platform while the pool answers
`.github/workflows/studio-heartbeat.yml`, and its GitHub-hosted runner otherwise. Those three files
are identical in every LogDate repository; change them in the standard, not here.

This repository is public, so it also runs the Studio runner watchdog
(`.github/workflows/studio-runner-watchdog.yml`) for every LogDate repository. It cancels a run that
waits more than 10 minutes for a pool, reruns it here on hosted runners, and keeps the "Studio
runner pool needs attention" issue open while a pool is offline.

Jobs the pools can't run yet keep a hosted runner and say why in a `Studio platform gap` comment,
from the standard's list: Android builds and lint (aapt2 has no Linux ARM64 build), x86_64
emulator acceptance runs (KVM and sudo), and the Cloud Run deploy (a linux/amd64 image).
