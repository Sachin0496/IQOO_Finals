package app.mouna.app

import app.mouna.app.engine.AppInfo
import app.mouna.app.engine.AppMatch
import app.mouna.app.engine.AppParser
import app.mouna.app.engine.DeviceNumber
import app.mouna.app.engine.NameMatch
import app.mouna.app.engine.PhoneBook
import app.mouna.app.engine.PhoneCommand
import app.mouna.app.engine.PhoneContact
import app.mouna.app.engine.PhoneFlow
import app.mouna.app.engine.PhoneParser
import app.mouna.app.engine.PhoneRoute
import app.mouna.app.engine.PhoneRouter
import app.mouna.app.engine.PhoneStep
import app.mouna.app.engine.PhoneVerb
import app.mouna.app.engine.shortlist
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

    @Test
    fun deviceContactsAreAddedWhenTheNameIsNew() {
        val device = listOf(PhoneContact("Ravi", "+91 98765 00002", aliases = listOf("ravi bhai"), starred = true))
        val merged = PhoneBook.merge(phone = listOf("Amma" to "9876543210"), web = emptyList(), device = device)
        assertEquals(
            listOf(
                PhoneContact("Amma", "9876543210"),
                PhoneContact("Ravi", "+919876500002", null, listOf("ravi bhai"), starred = true),
            ),
            merged,
        )
    }

    @Test
    fun aDeviceContactWithAKnownNameFillsInAMissingNumberOnly() {
        val device = listOf(PhoneContact("maadhav", "9000000001"), PhoneContact("Nakul", "9000000002"))
        val merged = PhoneBook.merge(
            phone = listOf("Nakul" to "9876500001"),
            web = listOf("Maadhav" to "room-m"),
            device = device,
        )
        assertEquals(
            listOf(PhoneContact("Nakul", "9876500001"), PhoneContact("Maadhav", "9000000001", "room-m")),
            merged,
        )
    }

    @Test
    fun deviceNamesAreNotRepeatedAndBadNumbersAreDropped() {
        val device = listOf(
            PhoneContact("RAVI", "9000000009"),
            PhoneContact("Priya", "9000000003"),
            PhoneContact("priya", "9000000004"),
            PhoneContact("Bad", "x"),
            PhoneContact("  "),
        )
        val merged = PhoneBook.merge(phone = listOf("Ravi" to "9876543210"), web = emptyList(), device = device)
        assertEquals(
            listOf(PhoneContact("Ravi", "9876543210"), PhoneContact("Priya", "9000000003"), PhoneContact("Bad", null)),
            merged,
        )
    }

    // Phone book: the picker

    @Test
    fun shortlistIsFavouritesThenStarredInTheirOwnOrder() {
        val all = listOf(
            PhoneContact("Ravi", "1", starred = true),
            PhoneContact("Amma", "2"),
            PhoneContact("Zed", "3"),
            PhoneContact("Nakul", "4", starred = true),
            PhoneContact("Priya", "5", starred = true),
        )
        val shown = PhoneBook.shortlist(all, setOf("nakul", " AMMA "))
        assertEquals(listOf("Amma", "Nakul", "Ravi", "Priya"), shown.map { it.name })
    }

    @Test
    fun shortlistFallsBackToTheFirstEightWhenNobodyIsFavouriteOrStarred() {
        val all = (1..10).map { PhoneContact("P$it") }
        assertEquals(all.take(8), PhoneBook.shortlist(all, emptySet()))
        assertEquals(all.take(8), PhoneBook.shortlist(all, setOf("nobody")))
    }

    @Test
    fun shortlistKeepsEveryFavouriteAndStarredContact() {
        val all = (1..10).map { PhoneContact("P$it", starred = true) }
        assertEquals(all, PhoneBook.shortlist(all, emptySet()))
    }

    @Test
    fun shortlistIsEmptyWithNoContacts() {
        assertEquals(emptyList<PhoneContact>(), PhoneBook.shortlist(emptyList(), setOf("nakul")))
    }

    // Routing

    @Test
    fun emergencyNumbersOnlyEverDial() {
        val c = contact("Ambulance", "108", "room-x")
        assertEquals(PhoneRoute.EmergencyDial("108"), PhoneRouter.route(PhoneVerb.CALL, c, null, hasWhatsApp = true))
        assertEquals(PhoneRoute.EmergencyDial("108"), PhoneRouter.route(PhoneVerb.CALL, c, null, hasWhatsApp = false))
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
    fun aCallGoesToTheNumberEvenWhenThereIsARoom() {
        val c = contact("Nakul", "9876543210", "room-nakul")
        assertEquals(
            PhoneRoute.Carrier("9876543210", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasWhatsApp = false),
        )
    }

    @Test
    fun aWebRoomIsTheFallbackWhenThereIsNoNumber() {
        val c = contact("Nakul", null, "room-nakul")
        assertEquals(
            PhoneRoute.WebRoom("room-nakul", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasWhatsApp = false),
        )
    }

    @Test
    fun aNumberIsCalledByThePhoneApp() {
        val c = contact("Nakul", "9876543210")
        assertEquals(
            PhoneRoute.Carrier("9876543210", "Nakul"),
            PhoneRouter.route(PhoneVerb.CALL, c, null, hasWhatsApp = false),
        )
    }

    @Test
    fun aContactWithNothingIsUnreachable() {
        val route = PhoneRouter.route(PhoneVerb.CALL, contact("Nakul"), null, hasWhatsApp = true)
        assertEquals(PhoneRoute.Unreachable("Nakul has no phone number saved"), route)
    }

    @Test
    fun aMessageNeedsANumber() {
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, contact("Nakul", null, "room"), "hi", hasWhatsApp = true)
        assertEquals(PhoneRoute.Unreachable("Nakul has no phone number saved"), route)
    }

    @Test
    fun whatsAppGetsTheBodyEncoded() {
        val c = contact("Nakul", "9876543210")
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, c, "I'm late, come home", hasWhatsApp = true)
        assertEquals(PhoneRoute.WhatsApp("https://wa.me/919876543210?text=I%27m%20late%2C%20come%20home"), route)
    }

    @Test
    fun whatsAppWithoutABodyHasNoTextParameter() {
        val c = contact("Nakul", "9876543210")
        val url = "https://wa.me/919876543210"
        assertEquals(PhoneRoute.WhatsApp(url), PhoneRouter.route(PhoneVerb.MESSAGE, c, null, hasWhatsApp = true))
        assertEquals(PhoneRoute.WhatsApp(url), PhoneRouter.route(PhoneVerb.MESSAGE, c, "   ", hasWhatsApp = true))
    }

    @Test
    fun whatsAppEncodesSpacesAsPercent20AndNeverPlus() {
        val c = contact("Nakul", "9876543210")
        val route = PhoneRouter.route(PhoneVerb.MESSAGE, c, "a+b c", hasWhatsApp = true) as PhoneRoute.WhatsApp
        assertEquals("https://wa.me/919876543210?text=a%2Bb%20c", route.url)
    }

    @Test
    fun withoutWhatsAppAMessageIsAnSmsComposer() {
        val c = contact("Nakul", "9876543210")
        assertEquals(
            PhoneRoute.Sms("9876543210", "I'm late"),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, "I'm late", hasWhatsApp = false),
        )
        assertEquals(
            PhoneRoute.Sms("9876543210", ""),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, null, hasWhatsApp = false),
        )
    }

    @Test
    fun anUnusableNumberFallsBackToSms() {
        val c = contact("Nakul", "12")
        assertEquals(
            PhoneRoute.Sms("12", "hi"),
            PhoneRouter.route(PhoneVerb.MESSAGE, c, "hi", hasWhatsApp = true),
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

    // Flow: who, what to say, confirm

    @Test
    fun aSureNameGoesStraightToConfirm() {
        val nakul = contact("Nakul", "+919876500001")
        val contacts = listOf(nakul, contact("Ravi"))
        assertEquals(PhoneStep.Confirm(PhoneVerb.CALL, nakul, null), PhoneFlow.start(call("nakul"), contacts))
    }

    @Test
    fun aNameThatFitsTwoPeopleAsksWho() {
        val nakul = contact("Nakul", "9876500001")
        val sharma = contact("Nakul Sharma", "9876500002")
        val step = PhoneFlow.start(call("nakul"), listOf(nakul, sharma))
        assertEquals(PhoneStep.Who(PhoneVerb.CALL, null, listOf(nakul, sharma)), step)
    }

    @Test
    fun aNameThatSoundsLikeAnotherContactAsksWho() {
        val step = PhoneFlow.start(call("nakul"), listOf(contact("Nakul"), contact("Nakool")))
        assertTrue(step is PhoneStep.Who)
    }

    @Test
    fun anUnknownNameIsMissing() {
        val contacts = listOf(contact("Nakul"), contact("Kunal"))
        assertEquals(PhoneStep.Missing("priya"), PhoneFlow.start(call("priya"), contacts))
        assertEquals(PhoneStep.Missing("nakul"), PhoneFlow.start(call("nakul"), emptyList()))
    }

    @Test
    fun callAloneListsEveryoneSaved() {
        val contacts = listOf(contact("Nakul"), contact("Ravi"))
        assertEquals(PhoneStep.Who(PhoneVerb.CALL, null, contacts), PhoneFlow.start(call(null), contacts))
        assertEquals(PhoneStep.Who(PhoneVerb.CALL, null, emptyList()), PhoneFlow.start(call(null), emptyList()))
    }

    @Test
    fun aSureMessageKeepsItsTextOrHasNone() {
        val nakul = contact("Nakul", "9876500001")
        val contacts = listOf(nakul)
        assertEquals(PhoneStep.Confirm(PhoneVerb.MESSAGE, nakul, "i am late"), PhoneFlow.start(msg("nakul", "i am late"), contacts))
        assertEquals(PhoneStep.Confirm(PhoneVerb.MESSAGE, nakul, null), PhoneFlow.start(msg("nakul"), contacts))
    }

    @Test
    fun aTwoWordNameIsJoinedBeforeItIsMatched() {
        val sharma = contact("Nakul Sharma", "9876500002")
        val cmd = PhoneParser.parse("message nakul sharma hi")!!
        assertEquals(PhoneStep.Confirm(PhoneVerb.MESSAGE, sharma, "hi"), PhoneFlow.start(cmd, listOf(sharma, contact("Ravi"))))
    }

    @Test
    fun pairShowsTwoAtATimeAndWrapsRoundOddCounts() {
        val three = listOf("a", "b", "c")
        assertEquals(listOf("a", "b"), PhoneFlow.pair(three, 0))
        assertEquals(listOf("c", "a"), PhoneFlow.pair(three, 1))
        assertEquals(listOf("b", "c"), PhoneFlow.pair(three, 2))
        assertEquals(listOf("a", "b"), PhoneFlow.pair(three, 3))
        assertEquals(listOf("a"), PhoneFlow.pair(listOf("a"), 5))
        assertEquals(emptyList<String>(), PhoneFlow.pair(emptyList<String>(), 0))
    }

    @Test
    fun pairWithAnEvenCountGoesBackToTheStart() {
        val four = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "d"), PhoneFlow.pair(four, 1))
        assertEquals(listOf("a", "b"), PhoneFlow.pair(four, 2))
    }

    @Test
    fun pagesOfFiveShowEveryItem() {
        val five = listOf(1, 2, 3, 4, 5)
        val seen = (0 until 5).flatMap { PhoneFlow.pair(five, it) }.toSet()
        assertEquals(five.toSet(), seen)
    }

    @Test
    fun quickMessagesAreFourDistinctTexts() {
        assertEquals(4, PhoneFlow.QUICK.size)
        assertEquals(PhoneFlow.QUICK.size, PhoneFlow.QUICK.distinct().size)
    }

    // Apps: parsing "open X"

    @Test
    fun openRequestsNameTheApp() {
        val cases = listOf(
            "open youtube" to "youtube",
            "launch whatsapp" to "whatsapp",
            "start the camera app" to "camera",
            "please open youtube now" to "youtube",
            "Open YouTube!" to "youtube",
            "can you open maps" to "maps",
            "mouna open youtube" to "youtube",
            "could you please launch google maps" to "google maps",
            "run the calculator app" to "calculator",
            "show me the gallery" to "gallery",
            "open you tube" to "you tube",
            "open youtube app" to "youtube",
            "open the door" to "door",
        )
        for ((text, want) in cases) assertEquals(text, want, AppParser.parse(text))
    }

    @Test
    fun hinglishOpenRequestsNameTheApp() {
        val cases = listOf(
            "youtube kholo" to "youtube",
            "youtube khol do" to "youtube",
            "youtube open karo" to "youtube",
            "youtube open kar do" to "youtube",
            "youtube chalao" to "youtube",
            "youtube chala do" to "youtube",
            "youtube start karo" to "youtube",
            "youtube kholo please" to "youtube",
            "mouna youtube kholo" to "youtube",
            "you tube kholo" to "you tube",
        )
        for ((text, want) in cases) assertEquals(text, want, AppParser.parse(text))
    }

    @Test
    fun textThatIsNotAnOpenRequestGivesNull() {
        val texts = listOf(
            "open", "please open", "open the", "open the app", "start", "start karo", "open karo", "kholo",
            "youtube", "close the app", "i will open the window later", "where is my phone", "",
        )
        for (text in texts) assertNull(text, AppParser.parse(text))
    }

    // Apps: matching installed apps

    private val apps = listOf(
        AppInfo("YouTube", "com.google.android.youtube"),
        AppInfo("YouTube Music", "com.google.android.apps.youtube.music"),
        AppInfo("Maps", "com.google.android.apps.maps"),
        AppInfo("WhatsApp", "com.whatsapp"),
        AppInfo("Camera", "com.android.camera"),
    )

    @Test
    fun anExactAppNameIsFirstAtFullScore() {
        val ranked = AppMatch.rank("youtube", apps)
        assertEquals(listOf("YouTube", "YouTube Music"), ranked.map { it.first.label })
        assertEquals(1f, ranked.first().second, 1e-6f)
    }

    @Test
    fun aFullNameFindsTheLongerApp() {
        val ranked = AppMatch.rank("youtube music", apps)
        assertEquals("YouTube Music", ranked.first().first.label)
        assertEquals(1f, ranked.first().second, 1e-6f)
    }

    @Test
    fun spacesAndSpellingDoNotStopAMatch() {
        assertEquals("YouTube", AppMatch.rank("you tube", apps).first().first.label)
        assertEquals("YouTube", AppMatch.rank("utube", apps).first().first.label)
        assertEquals("WhatsApp", AppMatch.rank("whatsap", apps).first().first.label)
        assertEquals("Camera", AppMatch.rank("camra", apps).first().first.label)
        assertEquals("Maps", AppMatch.rank("maps", apps).first().first.label)
    }

    @Test
    fun aWordThatIsNoAppMatchesNothing() {
        assertTrue(AppMatch.rank("door", apps).isEmpty())
        assertTrue(AppMatch.rank("", apps).isEmpty())
    }

    @Test
    fun aClearAppNameIsSureAndANearSpellingIsNot() {
        assertTrue(AppMatch.sure(AppMatch.rank("whatsapp", apps)))
        assertTrue(AppMatch.sure(AppMatch.rank("maps", apps)))
        assertTrue(AppMatch.sure(AppMatch.rank("youtube", apps)))
        assertFalse(AppMatch.sure(AppMatch.rank("utube", apps)))
        assertFalse(AppMatch.sure(emptyList()))
    }

    // Apps: a leading "Google " is also offered without it

    @Test
    fun aGooglePrefixedAppIsAlsoFoundByItsShortName() {
        val maps = listOf(AppInfo("Google Maps", "com.google.android.apps.maps"))
        assertEquals(listOf("Google Maps", "Maps"), AppMatch.aliases(maps).map { it.label })
        val found = AppMatch.search("maps", maps)
        assertEquals("Google Maps", found.first().first.label)
        assertEquals(1f, found.first().second, 1e-6f)
    }

    @Test
    fun aFullGoogleNameAndAnAppWithoutThePrefixAreUnchanged() {
        val maps = listOf(AppInfo("Google Maps", "com.google.android.apps.maps"))
        assertEquals("Google Maps", AppMatch.search("google maps", maps).first().first.label)
        val plain = listOf(AppInfo("Google", "com.google.android.googlequicksearchbox"), AppInfo("YouTube", "com.google.android.youtube"))
        assertEquals(plain, AppMatch.aliases(plain))
    }

    @Test
    fun anAppIsListedOnceWhateverItsNamesMatch() {
        val maps = listOf(AppInfo("Google Maps", "com.google.android.apps.maps"))
        assertEquals(1, AppMatch.search("google maps", maps).size)
        assertEquals(1, AppMatch.search("maps", maps).size)
    }

    // The phone's own contacts, one per name

    private fun row(name: String, number: String, mobile: Boolean = true, starred: Boolean = false) =
        DeviceNumber(name, number, mobile, starred)

    @Test
    fun aDeviceNameIsOneContactWithItsMobileNumberFirst() {
        val contacts = PhoneBook.fromDevice(
            listOf(row("Nakul", "011 2345 6789", mobile = false), row("Nakul", "98765 43210"), row("Nakul", "9000000000")),
        )
        assertEquals(listOf(PhoneContact("Nakul", "98765 43210")), contacts)
    }

    @Test
    fun withNoMobileTheFirstUsableNumberIsKept() {
        val contacts = PhoneBook.fromDevice(listOf(row("Amma", "", mobile = true), row("Amma", "0112345678", mobile = false)))
        assertEquals("0112345678", contacts.single().number)
    }

    @Test
    fun starredIsTakenFromAnyRowOfTheName() {
        assertTrue(PhoneBook.fromDevice(listOf(row("Ravi", "9876543210"), row("Ravi", "9123456780", starred = true))).single().starred)
        assertFalse(PhoneBook.fromDevice(listOf(row("Ravi", "9876543210"))).single().starred)
    }

    @Test
    fun blankNamesAreDroppedAndANameWithNoUsableNumberStaysWithoutOne() {
        val contacts = PhoneBook.fromDevice(listOf(row("  ", "9876543210"), row("Dadi", " ")))
        assertEquals(listOf(PhoneContact("Dadi", null)), contacts)
    }

    @Test
    fun noSimCallsTheWebRoomWhenThereIsOne() {
        val both = PhoneContact("Nakul", "+919876543210", "room1")
        assertEquals(PhoneRoute.Carrier("+919876543210", "Nakul"), PhoneRouter.route(PhoneVerb.CALL, both, null, hasWhatsApp = false, hasSim = true))
        assertEquals(PhoneRoute.WebRoom("room1", "Nakul"), PhoneRouter.route(PhoneVerb.CALL, both, null, hasWhatsApp = false, hasSim = false))
        val numberOnly = PhoneContact("Nakul", "+919876543210")
        assertEquals(PhoneRoute.Carrier("+919876543210", "Nakul"), PhoneRouter.route(PhoneVerb.CALL, numberOnly, null, hasWhatsApp = false, hasSim = false))
    }
}
