package com.myagent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** JWT 签发与解析（HS256，密钥来自 {@code JWT_SECRET}）。 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(
            @Value("${myagent.jwt.secret}") String secret,
            @Value("${myagent.jwt.expire-minutes:720}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    public String issue(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /** 解析并验签；失败时抛 jjwt 异常，由调用方转为未认证。 */
    public JwtPrincipal parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims claims = jws.getPayload();
        Number uid = claims.get("uid", Number.class);
        return new JwtPrincipal(
                uid == null ? null : uid.longValue(),
                claims.getSubject(),
                claims.get("role", String.class));
    }
}
