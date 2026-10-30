-- OAuth authorization codes and refresh tokens are stored under the SHA-256 of their value,
-- base64url-encoded without padding, so the database never holds a value an app could redeem. The
-- server looks each one up by hashing what the app presents. This rewrites the rows that already
-- exist the same way, so the grants they belong to keep working.
UPDATE oauth_authorization_codes
SET
  code = rtrim(
    translate(
      encode(sha256(convert_to(code, 'UTF8')), 'base64'),
      '+/',
      '-_'
    ),
    '='
  );

UPDATE oauth_refresh_tokens
SET
  token = rtrim(
    translate(
      encode(sha256(convert_to(token, 'UTF8')), 'base64'),
      '+/',
      '-_'
    ),
    '='
  );
