import datetime as dt
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    "watchdog", pathlib.Path(__file__).resolve().parents[1] / "studio-runner-watchdog.py"
)
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)

NOW = dt.datetime(2026, 11, 23, 20, 0, tzinfo=dt.timezone.utc)
LIMIT = dt.timedelta(minutes=10)


def job(status="queued", labels=("self-hosted", "macOS", "ARM64", "studio-macos"), minutes_ago=11):
    created = (NOW - dt.timedelta(minutes=minutes_ago)).isoformat().replace("+00:00", "Z")
    return {"status": status, "labels": list(labels), "created_at": created}


class StalledJobTests(unittest.TestCase):
    def test_a_studio_job_queued_past_the_limit_is_stalled(self):
        self.assertTrue(watchdog.stalled(job(), NOW, LIMIT))

    def test_a_studio_job_inside_the_limit_is_not_stalled(self):
        self.assertFalse(watchdog.stalled(job(minutes_ago=9), NOW, LIMIT))

    def test_a_running_job_is_not_stalled(self):
        self.assertFalse(watchdog.stalled(job(status="in_progress", minutes_ago=90), NOW, LIMIT))

    def test_a_hosted_job_is_left_alone(self):
        self.assertFalse(watchdog.stalled(job(labels=("ubuntu-latest",), minutes_ago=90), NOW, LIMIT))


class OnlinePoolTests(unittest.TestCase):
    def test_only_online_runners_count(self):
        runners = [
            {"status": "offline", "labels": [{"name": "studio-macos"}]},
            {"status": "online", "labels": [{"name": "self-hosted"}, {"name": "studio-linux"}]},
        ]
        self.assertEqual(watchdog.online_pools(runners), {"studio-linux"})


if __name__ == "__main__":
    unittest.main()
