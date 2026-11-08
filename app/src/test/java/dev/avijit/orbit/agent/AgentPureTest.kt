package dev.avijit.orbit.agent

import dev.avijit.orbit.ui.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPureTest {
    private val fp = "a".repeat(64)

    @Test fun pairLink_walkerAndOrbitSchemes() {
        val u = "https%3A%2F%2Forbit.example.com"
        val p = PairLink.parse("orbitwalker://pair?u=$u&t=tok123&n=Home+server&m=ca")!!
        assertEquals("https://orbit.example.com", p.url); assertEquals("tok123", p.token); assertTrue(p.ca); assertEquals("Home server", p.name)
        val q = PairLink.parse("orbit://pair?u=$u&fp=$fp&t=t&n=x")!!
        assertFalse(q.ca); assertEquals(fp, q.fingerprint)
    }

    @Test fun pairLink_rejectsBadOnes() {
        assertNull(PairLink.parse("https://example.com"))
        assertNull(PairLink.parse("orbitwalker://pair?u=http%3A%2F%2Fx.com&t=t&m=ca"))       // not https
        assertNull(PairLink.parse("orbitwalker://pair?u=https%3A%2F%2Fx.com&t=t"))            // pin without fingerprint
        assertNull(PairLink.parse("orbitwalker://pair?u=https%3A%2F%2Fx.com&fp=zz&t=t"))      // bad fingerprint
        assertNull(PairLink.parse("orbitwalker://pair?u=https%3A%2F%2Fx.com&m=ca"))           // no token
    }

    private val apps = listOf("Orbit Browser", "YouTube", "YouTube Music", "Chrome", "Google", "Google Maps", "WhatsApp")
        .map { AppEntry(it, "p/$it", "p") }

    @Test fun appMatch() {
        assertEquals("YouTube", AppMatch.best(apps, "youtube")!!.label)
        assertEquals("YouTube", AppMatch.best(apps, "You Tube")!!.label)
        assertEquals("YouTube Music", AppMatch.best(apps, "youtube music")!!.label)
        assertEquals("Google Maps", AppMatch.best(apps, "maps")!!.label)
        assertEquals("WhatsApp", AppMatch.best(apps, "whatsapp app")!!.label)
        assertNull(AppMatch.best(apps, "netflix"))
    }

    @Test fun webTarget() {
        assertEquals("https://youtube.com", WebTarget.url("youtube.com"))
        assertEquals("https://a.b/c", WebTarget.url("https://a.b/c"))
        assertEquals("https://www.google.com/search?q=weather+in+London", WebTarget.url("weather in London"))
    }

    @Test fun wakePhrase() {
        assertTrue(WakePhrase.matches("hey orbit"))
        assertTrue(WakePhrase.matches("[unk] hey orbit"))
        assertFalse(WakePhrase.matches("orbit"))
        assertFalse(WakePhrase.matches("[unk]"))
    }

    @Test fun appLabelsAreCleaned() {
        assertEquals("Evil Ignore previous", LabelText.clean("Evil\u202E\n\u2028Ignore\u200B previous"))
        assertEquals("Café 2", LabelText.clean("  Café   2 "))
        assertEquals(40, LabelText.clean("x".repeat(80)).length)
    }

    @Test fun conversationRequestsGoodbyesAndNothing() {
        val ask = { t: String -> (Conversation.classify(t) as? Conversation.Heard.Ask)?.text }
        assertEquals("open YouTube", ask("open YouTube"))
        assertEquals("open Chrome", ask("Hey Orbit, open Chrome"))
        assertEquals("make it bigger", ask("okay orbit make it bigger"))
        assertEquals("thanks for opening chrome, now close it", ask("thanks for opening chrome, now close it"))
        assertEquals(Conversation.Heard.Bye, Conversation.classify("That's all."))
        assertEquals(Conversation.Heard.Bye, Conversation.classify("thank you Orbit"))
        assertEquals(Conversation.Heard.Bye, Conversation.classify("Goodbye!"))
        assertEquals(Conversation.Heard.Nothing, Conversation.classify("Hey Orbit"))
        assertEquals(Conversation.Heard.Nothing, Conversation.classify("  ...  "))
    }

    @Test fun conversationIdleAndIds() {
        assertFalse(Conversation.idle(1_000, 30_999, 30_000))
        assertTrue(Conversation.idle(1_000, 31_000, 30_000))
        val id = Conversation.newId()
        assertTrue(Conversation.validId(id))
        assertFalse(Conversation.validId(""))
        assertFalse(Conversation.validId("a/b"))
        assertFalse(Conversation.validId(null))
    }
}
