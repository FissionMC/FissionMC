package net.fissionmc.master.client

import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ProfileProperty(val name: String, val value: String, val signature: String?)
data class PlayerProfile(val uuid: UUID, val name: String, val properties: List<ProfileProperty> = emptyList())

/** Verifies that the client really owns [username] (Mojang session server) -- completes with null if not. */
fun interface Authenticator {
    fun authenticate(username: String, serverHash: String): CompletableFuture<PlayerProfile?>
}

object LoginCrypto {
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    fun newToken(): ByteArray = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
    fun rsaDecrypt(key: PrivateKey, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key) }.doFinal(data)
    fun aes(secret: ByteArray, mode: Int): Cipher =
        Cipher.getInstance("AES/CFB8/NoPadding").apply { init(mode, SecretKeySpec(secret, "AES"), IvParameterSpec(secret)) }

    /** Minecraft's "server hash": SHA-1 over serverId, shared secret, public key, printed as a signed hex number. */
    fun serverHash(serverId: String, secret: ByteArray, publicKey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(serverId.toByteArray(StandardCharsets.ISO_8859_1)); md.update(secret); md.update(publicKey)
        return BigInteger(md.digest()).toString(16)
    }
    fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
    fun parseUuid(s: String): UUID = UUID.fromString(s.replaceFirst(Regex("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)"), "$1-$2-$3-$4-$5"))
}

object ValidName {
    private val re = Regex("[A-Za-z0-9_.\\-]{1,16}")
    fun ok(n: String) = re.matches(n)
}

/** Mojang's session server `hasJoined`. Blocking HTTP runs on the HttpClient's own threads, never on a Netty loop. */
class MojangAuthenticator(private val base: String = "https://sessionserver.mojang.com") : Authenticator {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    override fun authenticate(username: String, serverHash: String): CompletableFuture<PlayerProfile?> {
        val q = "username=${URLEncoder.encode(username, StandardCharsets.UTF_8)}&serverId=${URLEncoder.encode(serverHash, StandardCharsets.UTF_8)}"
        val req = HttpRequest.newBuilder(URI.create("$base/session/minecraft/hasJoined?$q")).timeout(Duration.ofSeconds(10)).GET().build()
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply { r -> if (r.statusCode() == 200) parse(r.body()) else null }
    }
    companion object {
        fun parse(json: String): PlayerProfile? {
            val o = JsonParser.parseString(json).asJsonObject
            val id = o["id"]?.asString ?: return null
            val props = o["properties"]?.asJsonArray?.map { p ->
                val po = p.asJsonObject
                ProfileProperty(po["name"].asString, po["value"].asString, po["signature"]?.takeIf { !it.isJsonNull }?.asString)
            } ?: emptyList()
            return PlayerProfile(LoginCrypto.parseUuid(id), o["name"].asString, props)
        }
    }
}
