package ru.rollers.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITER = 120_000
    private val rnd = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(rnd::nextBytes)
        return "pbkdf2$$ITER$${b64(salt)}$${b64(derive(password, salt, ITER))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val expected = Base64.getDecoder().decode(p[3])
        return java.security.MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(p[2]), p[1].toInt()))
    }

    private fun derive(pw: String, salt: ByteArray, iter: Int) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

class JwtConfig(secret: String, private val issuer: String = "rollers") {
    private val algorithm = Algorithm.HMAC256(secret)
    val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun token(userId: Long): String = JWT.create().withIssuer(issuer).withClaim("uid", userId)
        .withExpiresAt(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)).sign(algorithm)

    fun install(app: Application) = app.install(Authentication) {
        jwt("auth") {
            verifier(verifier)
            // для WebSocket допускаем токен в query-параметре
            authHeader { call ->
                call.request.headers["Authorization"]?.let { io.ktor.http.auth.parseAuthorizationHeader(it) }
                    ?: call.request.queryParameters["token"]?.let { io.ktor.http.auth.HttpAuthHeader.Single("Bearer", it) }
            }
            validate { c -> c.payload.getClaim("uid").asLong()?.let { JWTPrincipal(c.payload) } }
        }
    }
}

val ApplicationCall.userId: Long get() = principal<JWTPrincipal>()!!.payload.getClaim("uid").asLong()
