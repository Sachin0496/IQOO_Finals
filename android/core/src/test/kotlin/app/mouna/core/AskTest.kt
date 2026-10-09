package app.mouna.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTest {
    @Test
    fun bundledTreeReachesEveryAnswerAndNone() {
        val t = AskTree.bundled()
        fun leaves(ns: List<AskNode>): List<AskNode> = ns.flatMap { if (it.children.isEmpty()) listOf(it) else leaves(it.children) }
        assertEquals(30, leaves(t.tree).size) // the Lab's ward tree: 30 answers in 8 groups
        // "no" to everything ends in none
        val s = AskSession(t.tree)
        while (s.result == null) s.no()
        assertEquals(AskResult.None, s.result)
        // yes into the first group, yes to its first answer
        val a = AskSession(t.tree)
        a.yes()
        a.yes()
        assertEquals(t.tree[0].children[0], (a.result as AskResult.Answer).node)
        assertEquals(2, a.asked)
    }

    @Test
    fun blocksDecodeAgainstTheSentenceList() {
        val pack = BlockPack.bundled()
        assertEquals(16, pack.blocks.size)
        val s = pack.sentences.first { it.blocks.size == 2 }
        // two blocks, each recognised with its own word clearly first
        val row = s.blocks.map { b ->
            val others = pack.blocks.keys.filter { it != b }
            Ranked(listOf(b) + others, listOf(1.0) + others.map { 3.0 }, listOf(0.07) + others.map { 0.21 })
        }
        val r = readBlocks(row, pack)
        assertEquals(s, r.ranked.first().first)
        assertTrue(r.sure)
        assertEquals(s.blocks, r.words)
        assertTrue(plainWords(r.words, pack).getValue("en").contains(", "))
    }
}
