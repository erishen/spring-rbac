package com.example.rbac.gateway.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 零依赖 HS256 JWT 工具（与 auth 服务实现一致，仅用 verify 即可）。
 * 网关侧用来校验签名与过期时间，确保下游收到的请求一定已经过认证。
 */
public class JwtUtil {

    private final String secret;
    private final long ttlMillis;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtUtil(String secret, long ttlMillis) {
        this.secret = secret;
        this.ttlMillis = ttlMillis;
    }

    public String verify(String token) {
        if (token == null || token.isEmpty()) {
            throw new JwtException("empty token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("malformed token");
        }
        String sigInput = parts[0] + "." + parts[1];
        String expected = b64(hmac(sigInput.getBytes(StandardCharsets.UTF_8)));
        if (!constantTimeEquals(expected, parts[2])) {
            throw new JwtException("bad signature");
        }
        try {
            Map<String, Object> payload = mapper.readValue(fromB64(parts[1]), Map.class);
            Object exp = payload.get("exp");
            if (exp instanceof Number && ((Number) exp).longValue() * 1000 < System.currentTimeMillis()) {
                throw new JwtException("token expired");
            }
            Object sub = payload.get("sub");
            if (sub == null) {
                throw new JwtException("missing subject");
            }
            return sub.toString();
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("invalid payload");
        }
    }

    private String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] fromB64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }
}
