package app.mouna.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelStartTest {
    @Test
    fun savedTypeOpensOnLipsWhileTheDeveloperOptionIsOff() {
        assertEquals(Channel.LIPS, startChannel("type", devType = false))
        assertEquals(Channel.LIPS, startChannel("TYPE", devType = false))
    }

    @Test
    fun savedTypeOpensOnTypeWhileTheDeveloperOptionIsOn() {
        assertEquals(Channel.TYPE, startChannel("type", devType = true))
    }

    @Test
    fun savedLipsAndSignAreKeptWhateverTheDeveloperOption() {
        assertEquals(Channel.LIPS, startChannel("lips", devType = false))
        assertEquals(Channel.SIGN, startChannel("sign", devType = false))
        assertEquals(Channel.SIGN, startChannel("sign", devType = true))
    }

    @Test
    fun voiceAndUnreadableValuesOpenOnLips() {
        assertEquals(Channel.LIPS, startChannel("voice", devType = true))
        assertEquals(Channel.LIPS, startChannel("", devType = true))
        assertEquals(Channel.LIPS, startChannel("nonsense", devType = true))
    }
}
