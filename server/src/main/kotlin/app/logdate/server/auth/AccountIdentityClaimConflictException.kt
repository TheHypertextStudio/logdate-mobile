package app.logdate.server.auth

/** An insertion or authority write cannot claim an unavailable identity. */
class AccountIdentityClaimConflictException : IllegalStateException("Account identity is unavailable")
