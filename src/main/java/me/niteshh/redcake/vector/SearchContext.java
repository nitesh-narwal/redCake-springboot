package me.niteshh.redcake.vector;

import java.util.Arrays;

/**
 * Everything one search or one insertion needs, kept in reusable arrays so that the hot path allocates nothing:
 * the query in storage format, the visited marks, the two heaps and the scratch arrays for ranking and for
 * choosing neighbours. A {@link VectorSet} keeps a few of these in a pool; a context is used by one thread at a
 * time.
 */
final class SearchContext {

    // ---- the query, in the storage format of the set --------------------------------------------------
    byte[] q8;
    float[] f32;
    long[] bin;
    float qScale;
    /** Own buffers, used when the query is a stored element (the arrays above then point at these). */
    final byte[] q8Buffer;
    final float[] f32Buffer;
    final long[] binBuffer;

    // ---- visited marks: one bit per node, and a list of the words that were touched so that clearing costs --------
    // ---- what the search visited, not what the set holds. A bit per node keeps the marks of a ten-million-node set
    // ---- in 1.25 MB (a cache-resident array) where an int per node would be 40 MB of cache misses.
    private long[] visited = new long[0];
    private int[] touched = new int[256];
    private int touchedCount;

    // ---- the heaps of the layer search -----------------------------------------------------------------
    final LongHeap candidates = new LongHeap();
    final LongHeap results = new LongHeap();

    // ---- results sorted best first (index 0 = most similar) ---------------------------------------------
    int[] sortedIds = new int[0];
    float[] sortedScores = new float[0];
    int sortedCount;

    // ---- neighbour selection and eviction scratch -----------------------------------------------------------
    int[] selIds = new int[0];
    float[] selScores = new float[0];
    int[] tmpIds = new int[0];
    float[] tmpScores = new float[0];

    // ---- entry points for the next layer down, and the answer of a greedy descent ------------------------------
    int[] epIds = new int[0];
    float[] epScores = new float[0];
    int epCount;
    int greedyId;
    float greedyScore;

    SearchContext(int dim, int words) {
        this.q8Buffer = new byte[dim];
        this.f32Buffer = new float[dim];
        this.binBuffer = new long[words];
    }

    /** Makes the visited set large enough for {@code slots} nodes. */
    void ensureSlots(int slots) {
        int words = (slots + 63) >>> 6;
        if (visited.length < words) {
            visited = new long[Math.max(words, visited.length + (visited.length >> 1))];
            touchedCount = 0;
        }
    }

    /** Sizes the arrays that hold up to {@code ef} results and {@code links} neighbours. */
    void ensureWidth(int ef, int links) {
        int results = Math.max(ef, links + 1);
        if (sortedIds.length < results) {
            sortedIds = new int[results];
            sortedScores = new float[results];
            epIds = new int[results];
            epScores = new float[results];
        }
        int neighbours = links + 1;
        if (selIds.length < neighbours) {
            selIds = new int[neighbours];
            selScores = new float[neighbours];
            tmpIds = new int[neighbours];
            tmpScores = new float[neighbours];
        }
    }

    /** Starts a new search: clears the heaps and the visited marks left by the previous one. */
    void beginSearch() {
        candidates.clear();
        results.clear();
        for (int i = 0; i < touchedCount; i++) {
            visited[touched[i]] = 0L;
        }
        touchedCount = 0;
    }

    /** Marks {@code node} as visited. @return whether it had been visited before in this search */
    boolean seen(int node) {
        int word = node >>> 6;
        long bit = 1L << (node & 63);
        long bits = visited[word];
        if ((bits & bit) != 0) {
            return true;
        }
        if (bits == 0) { // first mark in this word: remember it for the next clear
            if (touchedCount == touched.length) {
                touched = Arrays.copyOf(touched, touchedCount * 2);
            }
            touched[touchedCount++] = word;
        }
        visited[word] = bits | bit;
        return false;
    }
}
