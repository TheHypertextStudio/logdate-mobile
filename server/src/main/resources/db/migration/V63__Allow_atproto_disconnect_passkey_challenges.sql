-- Disconnect requires fresh proof that cannot be consumed by sign-in or other account actions.
ALTER TABLE webauthn_challenges
DROP CONSTRAINT challenge_type_valid;

ALTER TABLE webauthn_challenges
ADD CONSTRAINT challenge_type_valid CHECK (
  challenge_type IN (
    'registration',
    'authentication',
    'oauth_authorize',
    'step_up',
    'step_up_grant',
    'bluesky_signin',
    'atproto_disconnect'
  )
);
