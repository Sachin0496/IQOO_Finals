package app.mouna.app

import app.mouna.app.engine.NameMatch
import app.mouna.app.engine.PhoneBook
import app.mouna.app.engine.PhoneCommand
import app.mouna.app.engine.PhoneContact
import app.mouna.app.engine.PhoneParser
import app.mouna.app.engine.PhoneRoute
import app.mouna.app.engine.PhoneRouter
import app.mouna.app.engine.PhoneVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentTest {
    private fun call(name: String?, body: String? = null) = PhoneCommand(PhoneVerb.CALL, name, body)
    private fun msg(name: String?, body: String? = null) = PhoneCommand(PhoneVerb.MESSAGE, name, body)
    private fun contact(name: String, number: String? = null, room: String? = null, vararg aliases: String) =
        PhoneContact(name, number, room, aliases.toList())

    // Parser: calls

    @Test
    fun englishCallsNameTheContact() {
        val cases = listOf(
            "call nakul" to call("nakul"),
            "call to nakul" to call("nakul"),
            "phone nakul" to call("nakul"),
            "dial nakul" to call("nakul"),
            "ring nakul" to call("nakul"),
            "please call nakul" to call("nakul"),
            "can you call nakul" to call("nakul"),
            "call nakul now" to call("nakul"),
            "make a call to nakul" to call("nakul"),
            "video call nakul" to call("nakul"),
            "call nakul sharma" to call("nakul sharma"),
            "Call Nakul!" to call("nakul"),
            "  could you please call   nakul  " to call("nakul"),
        )
        for ((text, want) in cases) assertEquals(text, want, PhoneParser.parse(text))
    }

    @Test
    fun callWithoutANameAsksWho() {
        for (text in listOf("call", "make a call", "phone", "video call")) {
            assertEquals(text, call(null), PhoneParser.parse(text))
        }
    }

    @Test
    fun hinglishCalls() {
        val cases = listOf(
            "nakul ko call karo" to call("nakul"),
            "nakul ko phone karo" to call("nakul"),
            "nakul ko call kar do" to call("nakul"),
            "nakul ko phone lagao" to call("nakul"),
            "call karo nakul ko" to call("nakul"),
            "nakul sharma ko call karo" to call("nakul sharma"),
        )
        for ((text, want) in cases) assertEquals(text, want, PhoneParser.parse(text))
    }

    // Parser: messages

    @Test
    fun englishMessagesNameTheContact() {
        val cases = listOf(
            "msg nakul" to msg("nakul"),
            "message nakul" to msg("nakul"),
            "text nakul" to msg("nakul"),
            "sms nakul" to msg("nakul"),
            "send a message to nakul" to msg("nakul"),
            "message nakul i am late" to msg("nakul", "i am late"),
            "tell nakul to come home" to msg("nakul", "come home"),
            "tell nakul that i'm hungry" to msg("nakul", "i'm hungry"),
            "msg nakul: come now" to msg("nakul", "come now"),
            "tell nakul sharma that i am tired" to msg("nakul sharma", "i am tired"),
        )
        for ((text, want) in cases) assertEquals(text, want, PhoneParser.parse(text))
    }

    @Test
    fun messageAloneAsksWho() {
        for (text in listOf("message", "msg", "send a message")) {
            assertEquals(text, msg(null), PhoneParser.parse(text))
        }
    }

    @Test
    fun hinglishMessages() {
        val cases = listOf(
            "nakul ko message karo" to msg("nakul"),
            "nakul ko msg bhejo" to msg("nakul"),
            "nakul ko bolo ki khana chahiye" to msg("nakul", "khana chahiye"),
            "nakul ko message bhejo ki main aa raha hoon" to msg("nakul", "main aa raha hoon"),
        )
        for ((text, want) in cases) assertEquals(text, want, PhoneParser.parse(text))
    }

    // Parser: not phone commands

    @Test
    fun ordinarySentencesAreNotPhoneCommands() {
        val texts = listOf(
            "i will call you later",
            "call me",
            "call me back",
            "who called",
            "the phone is ringing",
            "i need water",
            "message received",
            "nakul is here",
            "what is your name",
            "don't call nakul",
            "please don't call nakul",
            "do not call nakul",
            "i am calling",
            "how are you",
            "ring the bell",
            "call him",
            "tell me a joke",
            "text me",
        )
        for (text in texts) assertNull(text, PhoneParser.parse(text))
    }

    @Test
    fun negatedHinglishIsNotACommand() {
        for (text in listOf("nakul ko call mat karo", "nakul ko message nahi karna", "nakul ko bolo mat aana")) {
            assertNull(text, PhoneParser.parse(text))
        }
    }

    @Test
    fun emptyTextIsNotACommand() {
        assertNull(PhoneParser.parse(""))
        assertNull(PhoneParser.parse("   "))
        assertNull(PhoneParser.parse("!!!"))
    }

    // Parser: two-word names

    @Test
    fun resolveJoinsATwoWordNameWhenAContactHasIt() {
        val contacts = listOf(contact("Nakul Sharma", "+919876500001"), contact("Ravi"))
        val cmd = PhoneParser.parse("message nakul sharma i am late")!!
        assertEquals(msg("nakul sharma", "i am late"), PhoneParser.resolve(cmd, contacts))
    }

    @Test
    fun resolveJoinsAStandaloneSecondWordWithoutABody() {
        val contacts = listOf(contact("Nakul Sharma"))
        val cmd = PhoneParser.parse("message nakul sharma")!!
        assertEquals(msg("nakul sharma"), PhoneParser.resolve(cmd, contacts))
    }

    @Test
    fun resolvePrefersTheExactTwoWordContact() {
        val contacts = listOf(contact("Nakul"), contact("Nakul Sharma"))
        val cmd = PhoneParser.parse("message nakul sharma hi")!!
        assertEquals(msg("nakul sharma", "hi"), PhoneParser.resolve(cmd, contacts))
    }

    @Test
    fun resolveKeepsTheNameWhenTheBodyIsNotPartOfIt() {
        val contacts = listOf(contact("Nakul"), contact("Ravi"))
        val cmd = PhoneParser.parse("message nakul come home")!!
        assertEquals(msg("nakul", "come home"), PhoneParser.resolve(cmd, contacts))
    }

    @Test
    fun resolveKeepsTheColonFormWhenItIsOneName() {
        val contacts = listOf(contact("Nakul Sharma"))
        val cmd = PhoneParser.parse("msg nakul: come now")!!
        assertEquals(msg("nakul", "come now"), PhoneParser.resolve(cmd, contacts))
    }

    @Test
    fun resolveLeavesCallsAlone() {
        val contacts = listOf(contact("Nakul Sharma"))
        assertEquals(call("nakul"), PhoneParser.resolve(call("nakul"), contacts))
        assertEquals(msg(null), PhoneParser.resolve(msg(null), contacts))
    }

    // Parser: signs

    @Test
    fun signWordsGiveTheVerb() {
        assertEquals(PhoneVerb.CALL, PhoneParser.fromSign("telephone"))
        assertEquals(PhoneVerb.CALL, PhoneParser.fromSign("Cellphone"))
        assertEquals(PhoneVerb.MESSAGE, PhoneParser.fromSign(" LETTER "))
        assertNull(PhoneParser.fromSign("water"))
        assertNull(PhoneParser.fromSign(""))
    }

    // Name matching

    @Test
    fun exactNamesScoreOneAndSpellingsFoldTogether() {
        val nakul = listOf(contact("Nakul"))
        assertEquals(1f, NameMatch.rank("Nakul", nakul).single().second, 1e-6f)
        assertTrue(NameMatch.rank("nakool", nakul).single().second >= 0.9f)
        assertEquals(1f, NameMatch.rank("madhav", listOf(contact("Maadhav"))).single().second, 1e-6f)
    }

    @Test
    fun aCloseNameRanksFirstAndIsSure() {
        val contacts = listOf(contact("Nakul"), contact("Kunal"))
        val ranked = NameMatch.rank("nakul", contacts)
        assertEquals("Nakul", ranked.first().first.name)
        assertTrue(NameMatch.sure(ranked))
    }

    @Test
    fun aDifferentNameWinsForItsOwnSpelling() {
        val contacts = listOf(contact("Nakul"), contact("Kunal"))
        val ranked = NameMatch.rank("kunal", contacts)
        assertEquals("Kunal", ranked.first().first.name)
        assertTrue(NameMatch.sure(ranked))
    }

    @Test
    fun aliasesMatch() {
        val contacts = listOf(contact("Amma", null, null, "mom", "mummy"), contact("Nakul"))
        val ranked = NameMatch.rank("mom", contacts)
        assertEquals("Amma", ranked.first().first.name)
        assertEquals(1f, ranked.first().second, 1e-6f)
    }

    @Test
    fun aFirstNameMatchesAFullName() {
        val ranked = NameMatch.rank("sachin", listOf(contact("Sachin Kumar")))
        assertTrue(ranked.single().second >= 0.9f)
    }

    @Test
    fun unknownNamesGiveNoChoices() {
        assertTrue(NameMatch.rank("xyz", listOf(contact("Nakul"), contact("Kunal"))).isEmpty())
        assertTrue(NameMatch.rank("", listOf(contact("Nakul"))).isEmpty())
    }

    @Test
    fun equallyGoodMatchesAreNotSure() {
        val ranked = NameMatch.rank("nakul", listOf(contact("Nakul"), contact("Nakool")))
        assertEquals(2, ranked.size)
        assertFalse(NameMatch.sure(ranked))
    }

    @Test
    fun aNearMissIsAChoiceNotASilentPick() {
        val ranked = NameMatch.rank("nakul sharma", listOf(contact("Nakul")))
        assertEquals(1, ranked.size)
        assertFalse(NameMatch.sure(ranked))
    }

    @Test
    fun sureNeedsAClearLead() {
        val a = contact("A")
        val b = contact("B")
        assertFalse(NameMatch.sure(emptyList()))
        assertTrue(NameMatch.sure(listOf(a to 0.95f)))
        assertFalse(NameMatch.sure(listOf(a to 0.91f)))
        assertFalse(NameMatch.sure(listOf(a to 0.95f, b to 0.90f)))
        assertTrue(NameMatch.sure(listOf(a to 0.95f, b to 0.80f)))
    }

    // Phone book

    @Test
    fun phoneAndWebFavouritesMergeByName() {
        val merged = PhoneBook.merge(
            phone = listOf("Amma" to "98765 43210", "Nakul" to "+91 98765 11111"),
            web = listOf("nakul" to "room-nakul", "Maadhav" to "room-m"),
        )
        assertEquals(
            listOf(
                PhoneContact("Amma", "9876543210", null),
                PhoneContact("Nakul", "+919876511111", "room-nakul"),
                PhoneContact("Maadhav", null, "room-m"),
            ),
            merged,
        )
    }

    @Test
    fun mergeDropsBlankEntriesAndKeepsTheFirstDuplicate() {
        val merged = PhoneBook.merge(
            phone = listOf(" " to "9876543210", "Ravi" to "9876543210", "ravi" to "9000000000", "Bad" to "x"),
            web = listOf("Ravi" to "room-1", "Ravi" to "room-2", "Empty" to " "),
        )
        assertEquals(
            listOf(PhoneContact("Ravi", "9876543210", "room-1"), PhoneContact("Bad", null, null)),
            merged,
        )
    }

    // Routing

    @Test
    fun emergencyNumbersOnlyEverDial() {
        val c = contact("Ambulance", "108", "room-x")
        assertEquals(PhoneRoute.EmergencyDial("108"), PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = false, hasWhatsApp = true))
        assertEquals(PhoneRoute.EmergencyDial("108"), PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = true, hasWhatsApp = false))
    }

    @Test
    fun emergencyNumbersAreRecognised() {
        for (n in listOf("112", " 112 ", "+112", "100", "101", "102", "108", "911", "999")) {
            assertTrue(n, PhoneRouter.isEmergency(n))
        }
        for (n in listOf("1123", "+91 98765 43210", "", "12")) {
            assertFalse(n, PhoneRouter.isEmergency(n))
        }
    }

    @Test
    fun aWebRoomIsPreferredToACarrierCall() {
        val c = contact("Nakul", "9876543210", "room-nakul")
        assertEquals(
            PhoneRoute.WebRoom("room-nakul", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = true, hasWhatsApp = false),
        )
    }

    @Test
    fun aWebRoomWorksWithoutASim() {
        val c = contact("Nakul", null, "room-nakul")
        assertEquals(
            PhoneRoute.WebRoom("room-nakul", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = false, hasWhatsApp = false),
        )
    }

    @Test
    fun aNumberIsCalledWithASim() {
        val c = contact("Nakul", "9876543210")
        assertEquals(
            PhoneRoute.Carrier("9876543210", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = true, hasWhatsApp = false),
        )
    }

    @Test
    fun aNumberWithoutASimOrRoomIsUnreachable() {
        val c = contact("Nakul", "9876543210")
        val route = PhoneRouter.route(PhoneVerb.CALL, c, null, hasSim = false, hasWhatsApp = false)
        assertEquals(PhoneRoute.Unreachable("Nakul has no web room and this phone has no SIM"), route)
    }

    @Test
    fun aContactWithNothingIsUnreachable() {
        val route = PhoneRouter.route(PhoneVerb.CALL, contact("Nakul"), null, hasSim = true, hasWhatsApp = true)
        assertEquals(PhoneRoute.Unreachable("Nakul has no phone number or web room saved"), route)
    }

    @Test
    fun aMessageNeedsANumber() {
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, contact("Nakul", null, "room"), "hi", hasSim = true, hasWhatsApp = true)
        assertEquals(PhoneRoute.Unreachable("Nakul has no phone number saved"), route)
    }

    @Test
    fun whatsAppGetsTheBodyEncoded() {
        val c = contact("Nakul", "9876543210")
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, c, "I'm late, come home", hasSim = false, hasWhatsApp = true)
        assertEquals(PhoneRoute.WhatsApp("https://wa.me/919876543210?text=I%27m%20late%2C%20come%20home"), route)
    }

    @Test
    fun whatsAppWithoutABodyHasNoTextParameter() {
        val c = contact("Nakul", "9876543210")
        val url = "https://wa.me/919876543210"
        assertEquals(PhoneRoute.WhatsApp(url), PhoneRouter.route(PhoneVerb.MESSAGE, c, null, hasSim = true, hasWhatsApp = true))
        assertEquals(PhoneRoute.WhatsApp(url), PhoneRouter.route(PhoneVerb.MESSAGE, c, "   ", hasSim = true, hasWhatsApp = true))
    }

    @Test
    fun whatsAppEncodesSpacesAsPercent20AndNeverPlus() {
        val c = contact("Nakul", "9876543210")
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, c, "a+b c", hasSim = true, hasWhatsApp = true) as PhoneRoute.WhatsApp
        assertEquals("https://wa.me/919876543210?text=a%2Bb%20c", route.url)
    }

    @Test
    fun withoutWhatsAppAMessageIsAnSmsComposer() {
        val c = contact("Nakul", "9876543210")
        assertEquals(
            PhoneRoute.Sms("9876543210", "I'm late"),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, "I'm late", hasSim = false, hasWhatsApp = false),
        )
        assertEquals(
            PhoneRoute.Sms("9876543210", ""),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, null, hasSim = false, hasWhatsApp = false),
        )
    }

    @Test
    fun anUnusableNumberFallsBackToSms() {
        val c = contact("Nakul", "12")
        assertEquals(
            PhoneRoute.Sms("12", "hi"),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, "hi", hasSim = true, hasWhatsApp = true),
        )
    }

    @Test
    fun waDigitsAddsIndiaAndDropsTheTrunkPrefix() {
        assertEquals("919876543210", PhoneRouter.waDigits("9876543210"))
        assertEquals("919876543210", PhoneRouter.waDigits("+91 98765 43210"))
        assertEquals("919876543210", PhoneRouter.waDigits("098765 43210"))
        assertEquals("919876543210", PhoneRouter.waDigits("919876543210"))
        assertEquals("15551234567", PhoneRouter.waDigits("+1 555 123 4567"))
        assertEquals("919876543210", PhoneRouter.waDigits("00919876543210"))
    }

    @Test
    fun waDigitsRejectsUnusableNumbers() {
        assertNull(PhoneRouter.waDigits("12"))
        assertNull(PhoneRouter.waDigits(""))
        assertNull(PhoneRouter.waDigits("1234567890"))
        assertNull(PhoneRouter.waDigits("abc"))
    }
}
