"""Exercise the locked shared gate without a workstation plugin installation."""

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".hypertext/formatting"))
from core import formatting, git_policy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["verify"])
    parser.add_argument("--repo", type=Path, required=True)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--git-precommit", action="store_true")
    modes.add_argument("--git-message", type=Path)
    modes.add_argument("--git-prepush", action="store_true")
    parser.add_argument("--git-remote")
    parser.add_argument("--git-remote-url")
    args = parser.parse_args()
    if args.git_precommit:
        git_policy.require_studio_identity(args.repo)
        git_policy.hook_policy(args.repo)
        formatting.format_staged(args.repo)
    elif args.git_message:
        git_policy.validate_message(
            args.git_message.read_text(), git_policy.hook_policy(args.repo)
        )
    else:
        git_policy.verify_push(
            args.repo,
            args.git_remote,
            sys.stdin.read(),
            git_policy.read_policy(args.repo / git_policy.POLICY_PATH),
            args.git_remote_url,
        )


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print("Git policy blocked: " + str(error), file=sys.stderr)
        raise SystemExit(1)
