-- Bluesky identity confirmation cannot be spent through ordinary passkey sign-in or step-up.
ALTER TABLE webauthn_challenges
DROP CONSTRAINT IF EXISTS challenge_type_valid;

ALTER TABLE webauthn_challenges
ADD CONSTRAINT challenge_type_valid CHECK (
  challenge_type IN (
    'registration',
    'authentication',
    'oauth_authorize',
    'step_up',
    'step_up_grant',
    'bluesky_signin'
  )
);
