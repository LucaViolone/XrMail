package com.xremail.backend.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.xremail.backend.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

/**
 * Configures JWT bearer authentication for all protected routes.
 *
 * The Android XR client obtains a JWT from /auth/callback after the Gmail OAuth
 * flow completes. It then includes this token in the Authorization header for all
 * subsequent API calls:
 *   Authorization: Bearer <jwt>
 */
fun Application.configureSecurity(config: AppConfig) {
    val jwtConfig = config.jwt
    val algorithm = Algorithm.HMAC256(jwtConfig.secret)

    install(Authentication) {
        jwt("jwt-auth") {
            realm = "XrMail API"
            verifier(
                JWT.require(algorithm)
                    .withIssuer(jwtConfig.issuer)
                    .withAudience(jwtConfig.audience)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.subject
                if (userId != null && credential.payload.audience.contains(jwtConfig.audience)) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}
