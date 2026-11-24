package app.tantal.depot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature

class CatalogTest {
    private val first = Source("First", "https://first.example/index", "first")
    private val second = Source("Second", "https://second.example/index", "second")
    private val id = ApkId("example.app", "a".repeat(64))

    private fun apk(code: Long, sha: String = "b".repeat(64), signer: ApkId = id) =
        ApkBuild(signer, "Example", "$code", code, 36, 99, emptyList(), sha, 42, URI("https://example.com/app.apk"))

    private fun index(vararg builds: ApkBuild) = Index(1, builds.toList(), emptyList(), emptyList(), emptyList())

    @Test
    fun groupsMirrorsByDigestAndSeparatesSignersAndSameVersionBuilds() {
        val shared = apk(4)
        val alternate = apk(4, "c".repeat(64))
        val other = apk(4, signer = id.copy(signer = "d".repeat(64)))
        val cards = appCards(listOf(first to index(shared), second to index(alternate, shared, other)))
        assertEquals(2, cards.size)
        assertEquals(listOf(2, 1), cards.first().builds.map(List<Offer<ApkBuild>>::size))
        assertNotEquals(cards[0].id.key, cards[1].id.key)
    }

    @Test
    fun choosesHighestCompatibleUpgradeAndHonorsSourceOrderAndPin() {
        val firstOffer = Offer(first, apk(3))
        val secondOffer = Offer(second, apk(4))
        val incompatible = Offer(first, apk(5).copy(minSdk = 37))
        val mirror = Offer(second, firstOffer.build)
        val offers = listOf(firstOffer, incompatible, secondOffer, mirror)
        assertEquals(secondOffer, appOffer(offers, null, emptyMap(), 36, emptySet()))
        assertEquals(firstOffer, appOffer(offers, first.id, emptyMap(), 36, emptySet()))
        assertEquals(firstOffer, appOffer(listOf(firstOffer, mirror), null, emptyMap(), 36, emptySet()))
        assertNull(appOffer(offers, null, emptyMap(), 36, emptySet(), 4))
    }

    @Test
    fun sourceWithdrawalOnlyBlocksThatMirrorAndPublisherRevocationBlocksBoth() {
        val apk = apk(1)
        val mirrors = listOf(Offer(first, apk), Offer(second, apk))
        val scoped = mapOf("${first.id}/apk/${apk.sha}" to "Withdrawn")
        val global = scoped + ("${apk.id.signer}/apk/${apk.sha}" to "Revoked")
        assertEquals(second, appOffer(mirrors, null, scoped, 36, emptySet())?.source)
        assertNull(appOffer(mirrors, null, global, 36, emptySet()))
    }

    @Test
    fun modelSourcePinDoesNotSwitchToMirrorWhenLanguageIsMissing() {
        val en = ModelId("d3", "en")
        val ru = ModelId("d3", "ru")
        val model = ModelBuild(en, "D3", "English", 1, "abc", "tantal/d3",
            "e".repeat(40), "en", "f".repeat(64), 100, 1)
        val snapshot = listOf(first to index().copy(models = listOf(model)),
            second to index().copy(models = listOf(model.copy(id = ru))))
        assertNull(modelOffer(ru, snapshot, first.id, emptyMap()))
        assertEquals(second, modelOffer(ru, snapshot, null, emptyMap())?.source)
        assertNull(modelOffer(ru, snapshot, null, emptyMap(), "different"))
    }

    @Test
    fun verifiesExactSignedBytesAndRejectsOtherKeys() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val bytes = "{}".encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(bytes)
            sign()
        }
        verifySignature(pair.public.encoded.encoded(), bytes, signature)
        assertThrows(IllegalArgumentException::class.java) {
            verifySignature(pair.public.encoded.encoded(), "{ }".encodeToByteArray(), signature)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifySignature(generator.generateKeyPair().public.encoded.encoded(), bytes, signature)
        }
    }

    @Test
    fun rejectsNonHttpsCredentialsAndAlternatePorts() {
        assertEquals("example.com", https("https://example.com:443/app.apk").host)
        for (url in listOf("http://example.com", "https://user@example.com", "https://example.com:444")) {
            assertThrows(IllegalArgumentException::class.java) { https(url) }
        }
    }
}
