import datetime as dt
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    "route", pathlib.Path(__file__).resolve().parents[1] / "studio-route.py"
)
route = importlib.util.module_from_spec(spec)
spec.loader.exec_module(route)

NOW = dt.datetime(2026, 11, 23, 20, 0, tzinfo=dt.timezone.utc)
MAX_AGE = dt.timedelta(minutes=30)
FRESH = {"studio-linux": NOW - dt.timedelta(minutes=4), "studio-macos": NOW - dt.timedelta(minutes=4)}


def choose(**overrides):
    args = {"fork": False, "private": False, "attempt": 1, "beats": FRESH, "now": NOW, "max_age": MAX_AGE}
    args.update(overrides)
    return {pool: choice for pool, (choice, _reason) in route.decide(**args).items()}


class DecideTests(unittest.TestCase):
    def test_fresh_heartbeats_choose_studio(self):
        self.assertEqual(choose(), {"studio-linux": "studio", "studio-macos": "studio"})

    def test_a_fork_never_reaches_studio(self):
        self.assertEqual(choose(fork=True), {"studio-linux": "hosted", "studio-macos": "hosted"})
        self.assertEqual(choose(fork=True, private=True), {"studio-linux": "hosted", "studio-macos": "hosted"})

    def test_a_private_repository_always_chooses_studio(self):
        self.assertEqual(choose(private=True, beats=None), {"studio-linux": "studio", "studio-macos": "studio"})

    def test_a_rerun_chooses_hosted(self):
        self.assertEqual(choose(attempt=2), {"studio-linux": "hosted", "studio-macos": "hosted"})

    def test_each_pool_falls_back_on_its_own(self):
        beats = {"studio-linux": NOW - dt.timedelta(minutes=4), "studio-macos": NOW - dt.timedelta(minutes=31)}
        self.assertEqual(choose(beats=beats), {"studio-linux": "studio", "studio-macos": "hosted"})

    def test_a_pool_that_never_beat_chooses_hosted(self):
        self.assertEqual(choose(beats={}), {"studio-linux": "hosted", "studio-macos": "hosted"})

    def test_an_unreadable_heartbeat_chooses_hosted(self):
        self.assertEqual(choose(beats=None), {"studio-linux": "hosted", "studio-macos": "hosted"})


class ForkTests(unittest.TestCase):
    def pull(self, head, base="TheHypertextStudio/logdate-mobile"):
        return {"pull_request": {"head": {"repo": {"full_name": head} if head else None}, "base": {"repo": {"full_name": base}}}}

    def test_a_same_repository_pull_request_is_not_a_fork(self):
        self.assertFalse(route.is_fork_pull_request("pull_request", self.pull("TheHypertextStudio/logdate-mobile")))

    def test_a_fork_pull_request_is_a_fork(self):
        self.assertTrue(route.is_fork_pull_request("pull_request", self.pull("someone/logdate-mobile")))

    def test_a_deleted_fork_counts_as_a_fork(self):
        self.assertTrue(route.is_fork_pull_request("pull_request", self.pull(None)))

    def test_a_push_is_not_a_fork(self):
        self.assertFalse(route.is_fork_pull_request("push", {}))


class BeatTests(unittest.TestCase):
    def test_the_newest_successful_beat_per_pool_wins(self):
        def job(name, conclusion, minutes_ago):
            done = (NOW - dt.timedelta(minutes=minutes_ago)).isoformat().replace("+00:00", "Z")
            return {"name": name, "conclusion": conclusion, "completed_at": done}

        runs = [
            ({}, [job("studio-linux", "success", 3), job("studio-macos", "cancelled", 3)]),
            ({}, [job("studio-linux", "success", 13), job("studio-macos", "success", 13)]),
        ]
        beats = route.latest_beats(runs)
        self.assertEqual(beats["studio-linux"], NOW - dt.timedelta(minutes=3))
        self.assertEqual(beats["studio-macos"], NOW - dt.timedelta(minutes=13))


if __name__ == "__main__":
    unittest.main()
