package com.example.bench

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import kotlin.random.Random

/** One measured run: its time inside the JVM and its checksum. */
data class Sample(val nanos: Long, val checksum: Long)

class Benchmarks {
    private val words = List(5_000) { "w${it.toString(36)}" }
    private var directory = ByteBuffersDirectory()

    fun vmName(): String = "${System.getProperty("java.vm.name")} ${Runtime.version()}"

    private inline fun measure(block: () -> Long): Sample {
        val start = System.nanoTime()
        val checksum = block()
        return Sample(System.nanoTime() - start, checksum)
    }

    fun jackson(orders: Int): Sample = measure { Workloads.jackson(orders) }

    fun fft(log2: Int): Sample = measure { Workloads.fft(log2) }

    fun matrix(n: Int): Sample = measure { Workloads.matrix(n) }

    /** Lucene: indexes [docs] documents (in memory) with the standard analyzer. */
    fun luceneIndex(docs: Int): Sample = measure {
        directory = ByteBuffersDirectory()
        val random = Random(3)
        IndexWriter(directory, IndexWriterConfig(StandardAnalyzer())).use { writer ->
            repeat(docs) { i ->
                writer.addDocument(Document().apply {
                    add(StringField("id", i.toString(), Field.Store.YES))
                    add(TextField("title", List(6) { words[random.nextInt(words.size)] }.joinToString(" "), Field.Store.YES))
                    add(TextField("body", List(60) { words[random.nextInt(200 + random.nextInt(words.size - 200))] }.joinToString(" "), Field.Store.NO))
                })
            }
            writer.commit()
        }
        docs.toLong()
    }

    /** Lucene: [queries] term, boolean and phrase queries on the last index. */
    fun luceneSearch(queries: Int): Sample = measure {
        val random = Random(5)
        DirectoryReader.open(directory).use { reader ->
            val searcher = IndexSearcher(reader)
            var hits = 0L
            repeat(queries) { q ->
                val query = when (q % 3) {
                    0 -> TermQuery(Term("body", words[random.nextInt(200)]))
                    1 -> BooleanQuery.Builder()
                        .add(TermQuery(Term("body", words[random.nextInt(200)])), BooleanClause.Occur.MUST)
                        .add(TermQuery(Term("title", words[random.nextInt(words.size)])), BooleanClause.Occur.SHOULD)
                        .build()
                    else -> PhraseQuery("body", words[random.nextInt(200)], words[random.nextInt(200)])
                }
                hits += searcher.search(query, 10).totalHits.value()
            }
            hits
        }
    }

    // The bridge's own costs

    fun noop() {}

    fun echoInt(value: Int): Int = value

    fun echoBytes(bytes: ByteArray): ByteArray = bytes

    fun echoStrings(items: List<String>): List<String> = items

    fun callbacks(count: Int, callback: (Int) -> Int): Int {
        var sum = 0
        for (i in 0 until count) sum += callback(i)
        return sum
    }

    suspend fun suspendEcho(value: Int): Int = value

    fun numbers(count: Int): Flow<Int> = flow { repeat(count) { emit(it) } }
}
