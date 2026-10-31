package me.niteshh.redcake.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A set of named embeddings that can be searched by similarity - the value behind the {@code VADD}/{@code VSIM}
 * family of commands ({@code TYPE} reports {@code vectorset}).
 *
 * <h3>What it stores</h3>
 * Every vector is normalized to unit length, so "similar" means cosine similarity, and stored either as 8-bit
 * integers with one scale per vector (the default, a quarter of the memory), as floats, or as one bit per
 * dimension (see {@link Quantization}). The element name, an optional JSON attribute string and the graph links sit
 * next to it.
 *
 * <h3>How it searches</h3>
 * A Hierarchical Navigable Small World graph (Malkov and Yashunin): every element is a node with a random
 * level; each level is a proximity graph over the nodes that reach it, the top levels are sparse and give long
 * jumps, level 0 holds everything. A query descends greedily through the sparse levels and then explores level 0
 * best-first, keeping the {@code ef} best nodes seen. Cost grows with {@code ef}, not with the number of
 * elements. Sets small enough that the graph would visit most of them anyway are scanned exactly.
 *
 * <p>Two choices differ from the textbook version, both so that deleting is exact and cheap:
 * <ul>
 *   <li><b>Links are reciprocal</b> (a links to b exactly when b links to a). Removing an element therefore knows
 *       everybody that points at it, unlinks it, reconnects its neighbours with each other, and frees the slot for
 *       reuse - no tombstones, no periodic rebuild.</li>
 *   <li>The <b>level of an element is derived from a hash of its name</b>, not from a random generator, so the same
 *       sequence of commands builds the same graph on the primary, on its replicas and when the append-only file is
 *       replayed.</li>
 * </ul>
 *
 * <h3>Memory layout</h3>
 * No object per node or per link. Elements live in fixed-size <em>slots</em>, and slot data sits in a few primitive
 * arrays cut into pages of a few hundred kilobytes: pages are added as the set grows (nothing is ever copied) and
 * the garbage collector sees a handful of large arrays instead of millions of small objects.
 *
 * <h3>Concurrency</h3>
 * The set synchronizes itself with a read/write lock, so searches run in parallel with each other and only wait
 * while an insert or delete is rewiring the graph. This is why the store hands out the live object
 * ({@code KeyValueStore.peek}) instead of calling the search from inside its per-key lock, which would serialize
 * all searches on a hot index. Writers are additionally serialized by the server's primary write lock.
 */
public final class VectorSet {

    public static final int MAX_DIM = 16_384;
    public static final int DEFAULT_M = 16;
    public static final int MIN_M = 4;
    public static final int MAX_M = 128;
    public static final int DEFAULT_EF_CONSTRUCTION = 200;
    /** Upper bound for every exploration factor: it caps the work one command can ask for. */
    public static final int MAX_EF = 10_000;
    static final int MAX_LEVEL = 15;

    private static final int TARGET_PAGE_BYTES = 256 * 1024;
    private static final int MIN_PAGE_SLOTS = 16;
    private static final int MAX_PAGE_SLOTS = 4096;
    /** How many of the most distant links of a full node are checked for redundancy before the farthest is dropped. */
    private static final int DIVERSITY_PROBES = 4;
    private static final int MAX_POOLED_CONTEXTS = 8;
    /** Rough heap cost of an element name beyond its characters (String and array headers, reference). */
    private static final long NAME_OVERHEAD = 56;
    private static final long ATTRIBUTE_OVERHEAD = 48;

    /** A vector converted to the storage format of one set; build it with {@link #encode} (no lock needed). */
    public static final class Encoded {
        final byte[] q8;
        final float[] f32;
        final long[] bin;
        final float scale;
        final float norm;

        private Encoded(byte[] q8, float[] f32, long[] bin, float scale, float norm) {
            this.q8 = q8;
            this.f32 = f32;
            this.bin = bin;
            this.scale = scale;
            this.norm = norm;
        }
    }

    /** One search result: the element and its cosine similarity to the query, in [-1, 1]. */
    public record Hit(String element, float similarity) {
    }

    /** Facts about a set, for {@code VINFO}. */
    public record Info(int dim, Quantization quantization, int size, int maxLevel, int attributes, int m,
                       int efConstruction, long bytes, int freeSlots) {
    }

    private final DistanceKernel kernel = Kernels.active();
    private final int dim;
    private final Quantization quant;
    private final int m;
    private final int m0;
    private final int slab0;
    private final int slabUp;
    private final int efConstruction;
    private final double levelMult;
    private final int words;
    private final int shift;
    private final int mask;
    private final int pageSlots;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ArrayBlockingQueue<SearchContext> pool = new ArrayBlockingQueue<>(MAX_POOLED_CONTEXTS);
    private final SearchContext writeContext;

    // ---- slot storage, all indexed [slot >>> shift][slot & mask] (codes and links: scaled by their width) ----
    private byte[][] q8;
    private float[][] f32;
    private long[][] bin;
    private float[][] scales;
    private float[][] norms;
    private byte[][] levels;
    private String[][] names;
    private String[][] attrs;
    private int[][] links0;
    private int[][][] upper;
    private int pages;
    private int slots;
    private int[] free = new int[16];
    private int freeCount;

    // ---- name index (open addressing, linear probing; entries are slot + 1, 0 is empty) ----------------------
    private int[] table = new int[16];
    private int tableMask = 15;
    private int tableCount;

    private int entry = -1;
    private int maxLevel = -1;
    private int attrCount;
    /** Counts removals (and updates, which remove first): the one change that makes a prepared {@link Plan} unsafe. */
    private long removals;

    private volatile int count;
    private volatile long bytes;
    /** True while a dump is being loaded (see {@link #beginRestore}); the set refuses searches until it is complete. */
    private volatile boolean restoring;

    public VectorSet(int dim, Quantization quant, int m, int efConstruction) {
        if (dim < 1 || dim > MAX_DIM) {
            throw new IllegalArgumentException("vector dimension must be between 1 and " + MAX_DIM);
        }
        if (m < MIN_M || m > MAX_M) {
            throw new IllegalArgumentException("M must be between " + MIN_M + " and " + MAX_M);
        }
        if (efConstruction < 1 || efConstruction > MAX_EF) {
            throw new IllegalArgumentException("EF must be between 1 and " + MAX_EF);
        }
        this.dim = dim;
        this.quant = quant;
        this.m = m;
        this.m0 = 2 * m;
        this.slab0 = 1 + m0;
        this.slabUp = 1 + m;
        this.efConstruction = efConstruction;
        this.levelMult = 1.0 / StrictMath.log(m);
        this.words = (dim + 63) >>> 6;

        long codeBytes = switch (quant) {
            case Q8 -> dim + 4L;
            case NOQUANT -> 4L * dim;
            case BIN -> 8L * words;
        };
        long perSlot = codeBytes + 4 + 1 + 8 + 4L * slab0 + 8;
        int slotsPerPage = Integer.highestOneBit((int) Math.max(1, TARGET_PAGE_BYTES / perSlot));
        slotsPerPage = Math.max(MIN_PAGE_SLOTS, Math.min(MAX_PAGE_SLOTS, slotsPerPage));
        this.pageSlots = slotsPerPage;
        this.shift = Integer.numberOfTrailingZeros(slotsPerPage);
        this.mask = slotsPerPage - 1;

        int outer = 8;
        switch (quant) {
            case Q8 -> {
                q8 = new byte[outer][];
                scales = new float[outer][];
            }
            case NOQUANT -> f32 = new float[outer][];
            case BIN -> bin = new long[outer][];
        }
        norms = new float[outer][];
        levels = new byte[outer][];
        names = new String[outer][];
        attrs = new String[outer][];
        links0 = new int[outer][];
        upper = new int[outer][][];
        writeContext = new SearchContext(dim, words);
        bytes = 512;
    }

    // ================================================================== simple accessors

    public int dim() {
        return dim;
    }

    public Quantization quantization() {
        return quant;
    }

    public int m() {
        return m;
    }

    public int efConstruction() {
        return efConstruction;
    }

    /** @return the number of elements (what {@code VCARD} reports) */
    public int size() {
        return count;
    }

    /** @return whether a dump is still being loaded into this set */
    public boolean isRestoring() {
        return restoring;
    }

    /** @return an estimate of the heap held by this set, maintained incrementally (O(1)) for {@code maxmemory} */
    public long estimatedBytes() {
        return bytes + 4L * table.length + 4L * free.length;
    }

    public Info info() {
        lock.readLock().lock();
        try {
            return new Info(dim, quant, count, maxLevel, attrCount, m, efConstruction, estimatedBytes(), freeCount);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean contains(String element) {
        lock.readLock().lock();
        try {
            return indexGet(element) >= 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ================================================================== encoding

    /**
     * Normalizes {@code vector} and converts it to this set's storage format.
     *
     * @throws IllegalArgumentException if the length is wrong, a value is NaN or infinite, or the vector is all zeros
     */
    public Encoded encode(float[] vector) {
        if (vector.length != dim) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch - got " + vector.length + " but set has " + dim);
        }
        double sumSquares = 0;
        for (float x : vector) {
            if (!Float.isFinite(x)) {
                throw new IllegalArgumentException("invalid vector: contains NaN or infinity");
            }
            sumSquares += (double) x * x;
        }
        if (sumSquares == 0) {
            throw new IllegalArgumentException("invalid vector: all components are zero");
        }
        double norm = Math.sqrt(sumSquares);
        float inverse = (float) (1.0 / norm);

        switch (quant) {
            case NOQUANT -> {
                float[] out = new float[dim];
                for (int i = 0; i < dim; i++) {
                    out[i] = vector[i] * inverse;
                }
                return new Encoded(null, out, null, 1f, (float) norm);
            }
            case Q8 -> {
                float maxAbs = 0;
                for (float x : vector) {
                    maxAbs = Math.max(maxAbs, Math.abs(x));
                }
                float step = maxAbs / 127f;
                byte[] out = new byte[dim];
                long squares = 0;
                for (int i = 0; i < dim; i++) {
                    int q = Math.round(vector[i] / step);
                    q = Math.max(-127, Math.min(127, q));
                    out[i] = (byte) q;
                    squares += (long) q * q;
                }
                // Scale so that the reconstruction q * scale has length exactly 1: then the integer dot product
                // times the two scales is the cosine of the reconstructed vectors, with no per-pair correction.
                float scale = (float) (1.0 / Math.sqrt((double) squares));
                return new Encoded(out, null, null, scale, (float) norm);
            }
            default -> {
                long[] out = new long[words];
                for (int i = 0; i < dim; i++) {
                    if (vector[i] > 0) {
                        out[i >>> 6] |= 1L << (i & 63);
                    }
                }
                return new Encoded(null, null, out, 1f, (float) norm);
            }
        }
    }

    // ================================================================== slot storage

    private void allocatePage() {
        int p = pages;
        if (p == names.length) {
            int grown = p * 2;
            if (q8 != null) {
                q8 = Arrays.copyOf(q8, grown);
                scales = Arrays.copyOf(scales, grown);
            }
            if (f32 != null) {
                f32 = Arrays.copyOf(f32, grown);
            }
            if (bin != null) {
                bin = Arrays.copyOf(bin, grown);
            }
            norms = Arrays.copyOf(norms, grown);
            levels = Arrays.copyOf(levels, grown);
            names = Arrays.copyOf(names, grown);
            attrs = Arrays.copyOf(attrs, grown);
            links0 = Arrays.copyOf(links0, grown);
            upper = Arrays.copyOf(upper, grown);
        }
        long added = 0;
        switch (quant) {
            case Q8 -> {
                q8[p] = new byte[pageSlots * dim];
                scales[p] = new float[pageSlots];
                added += (long) pageSlots * (dim + 4);
            }
            case NOQUANT -> {
                f32[p] = new float[pageSlots * dim];
                added += 4L * pageSlots * dim;
            }
            case BIN -> {
                bin[p] = new long[pageSlots * words];
                added += 8L * pageSlots * words;
            }
        }
        norms[p] = new float[pageSlots];
        levels[p] = new byte[pageSlots];
        names[p] = new String[pageSlots];
        links0[p] = new int[pageSlots * slab0];
        upper[p] = new int[pageSlots][];
        added += (long) pageSlots * (4 + 1 + 8 + 8) + 4L * pageSlots * slab0;
        pages++;
        bytes += added;
    }

    private int allocSlot() {
        if (freeCount > 0) {
            return free[--freeCount];
        }
        if (slots == Integer.MAX_VALUE) {
            throw new IllegalStateException("too many elements in one vector set");
        }
        int slot = slots++;
        if ((slot >>> shift) >= pages) {
            allocatePage();
        }
        return slot;
    }

    private void pushFree(int slot) {
        if (freeCount == free.length) {
            free = Arrays.copyOf(free, freeCount * 2);
        }
        free[freeCount++] = slot;
    }

    /** Copies the encoded vector into the slot's code storage. */
    private void writeCodes(int slot, Encoded encoded) {
        int p = slot >>> shift;
        int o = slot & mask;
        switch (quant) {
            case Q8 -> {
                System.arraycopy(encoded.q8, 0, q8[p], o * dim, dim);
                scales[p][o] = encoded.scale;
            }
            case NOQUANT -> System.arraycopy(encoded.f32, 0, f32[p], o * dim, dim);
            case BIN -> System.arraycopy(encoded.bin, 0, bin[p], o * words, words);
        }
        norms[p][o] = encoded.norm;
    }

    // ================================================================== name index

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    private String nameOf(int slot) {
        return names[slot >>> shift][slot & mask];
    }

    private int indexGet(String name) {
        int i = mix(name.hashCode()) & tableMask;
        while (true) {
            int v = table[i];
            if (v == 0) {
                return -1;
            }
            if (nameOf(v - 1).equals(name)) {
                return v - 1;
            }
            i = (i + 1) & tableMask;
        }
    }

    private void indexPut(String name, int slot) {
        if ((tableCount + 1) * 2 > table.length) {
            rehash(table.length * 2);
        }
        int i = mix(name.hashCode()) & tableMask;
        while (table[i] != 0) {
            i = (i + 1) & tableMask;
        }
        table[i] = slot + 1;
        tableCount++;
    }

    /** Removes {@code name}; its slot must still hold the name (other entries are re-probed by their names). */
    private void indexRemove(String name) {
        int i = mix(name.hashCode()) & tableMask;
        while (true) {
            int v = table[i];
            if (v == 0) {
                return;
            }
            if (nameOf(v - 1).equals(name)) {
                break;
            }
            i = (i + 1) & tableMask;
        }
        // backward-shift deletion keeps probe sequences intact without tombstones
        table[i] = 0;
        tableCount--;
        int j = i;
        while (true) {
            j = (j + 1) & tableMask;
            int v = table[j];
            if (v == 0) {
                return;
            }
            int home = mix(nameOf(v - 1).hashCode()) & tableMask;
            boolean movable = (j > i) ? (home <= i || home > j) : (home <= i && home > j);
            if (movable) {
                table[i] = v;
                table[j] = 0;
                i = j;
            }
        }
    }

    private void rehash(int size) {
        int[] old = table;
        table = new int[size];
        tableMask = size - 1;
        for (int v : old) {
            if (v != 0) {
                int i = mix(nameOf(v - 1).hashCode()) & tableMask;
                while (table[i] != 0) {
                    i = (i + 1) & tableMask;
                }
                table[i] = v;
            }
        }
    }

    // ================================================================== scoring

    private float scoreQuery(SearchContext c, int node) {
        final int p = node >>> shift;
        final int o = node & mask;
        switch (quant) {
            case Q8:
                return kernel.dotInt8(c.q8, 0, q8[p], o * dim, dim) * c.qScale * scales[p][o];
            case NOQUANT:
                return kernel.dotFloat(c.f32, 0, f32[p], o * dim, dim);
            default: {
                long[] page = bin[p];
                int base = o * words;
                int hamming = 0;
                for (int w = 0; w < words; w++) {
                    hamming += Long.bitCount(c.bin[w] ^ page[base + w]);
                }
                return 1f - 2f * hamming / dim;
            }
        }
    }

    private float scoreNodes(int a, int b) {
        final int pa = a >>> shift;
        final int oa = a & mask;
        final int pb = b >>> shift;
        final int ob = b & mask;
        switch (quant) {
            case Q8:
                return kernel.dotInt8(q8[pa], oa * dim, q8[pb], ob * dim, dim) * scales[pa][oa] * scales[pb][ob];
            case NOQUANT:
                return kernel.dotFloat(f32[pa], oa * dim, f32[pb], ob * dim, dim);
            default: {
                long[] pageA = bin[pa];
                long[] pageB = bin[pb];
                int baseA = oa * words;
                int baseB = ob * words;
                int hamming = 0;
                for (int w = 0; w < words; w++) {
                    hamming += Long.bitCount(pageA[baseA + w] ^ pageB[baseB + w]);
                }
                return 1f - 2f * hamming / dim;
            }
        }
    }

    private void loadQuery(SearchContext c, Encoded e) {
        c.q8 = e.q8;
        c.f32 = e.f32;
        c.bin = e.bin;
        c.qScale = e.scale;
    }

    private void loadQueryFromSlot(SearchContext c, int slot) {
        int p = slot >>> shift;
        int o = slot & mask;
        switch (quant) {
            case Q8 -> {
                System.arraycopy(q8[p], o * dim, c.q8Buffer, 0, dim);
                c.q8 = c.q8Buffer;
                c.qScale = scales[p][o];
            }
            case NOQUANT -> {
                System.arraycopy(f32[p], o * dim, c.f32Buffer, 0, dim);
                c.f32 = c.f32Buffer;
            }
            case BIN -> {
                System.arraycopy(bin[p], o * words, c.binBuffer, 0, words);
                c.bin = c.binBuffer;
            }
        }
    }

    // ---- packing (score, node) into one long so the heaps need no objects ----------------------------------

    private static int sortable(float f) {
        int bits = Float.floatToRawIntBits(f);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    private static float unsortable(int s) {
        return Float.intBitsToFloat(s ^ ((s >> 31) & 0x7fffffff));
    }

    /** Min-heap key whose smallest element is the <em>lowest</em> score (the result set drops its worst). */
    private static long resultKey(float score, int node) {
        return ((long) sortable(score) << 32) | (node & 0xffffffffL);
    }

    /** Min-heap key whose smallest element is the <em>highest</em> score (the candidate queue pops its best). */
    private static long candidateKey(float score, int node) {
        return ((long) ~sortable(score) << 32) | (node & 0xffffffffL);
    }

    private static int keyNode(long key) {
        return (int) key;
    }

    private static float resultScore(long key) {
        return unsortable((int) (key >> 32));
    }

    private static float candidateScore(long key) {
        return unsortable(~(int) (key >> 32));
    }

    // ================================================================== graph primitives

    private int capOf(int level) {
        return level == 0 ? m0 : m;
    }

    private int[] slabArray(int node, int level) {
        return level == 0 ? links0[node >>> shift] : upper[node >>> shift][node & mask];
    }

    private int slabBase(int node, int level) {
        return level == 0 ? (node & mask) * slab0 : (level - 1) * slabUp;
    }

    private int levelOf(int node) {
        return levels[node >>> shift][node & mask];
    }

    private int degree(int node, int level) {
        return slabArray(node, level)[slabBase(node, level)];
    }

    private boolean hasLink(int node, int level, int other) {
        int[] arr = slabArray(node, level);
        int base = slabBase(node, level);
        int degree = arr[base];
        for (int i = 1; i <= degree; i++) {
            if (arr[base + i] == other) {
                return true;
            }
        }
        return false;
    }

    private void addLink(int node, int level, int other) {
        int[] arr = slabArray(node, level);
        int base = slabBase(node, level);
        int degree = arr[base];
        arr[base + 1 + degree] = other;
        arr[base] = degree + 1;
    }

    private void removeLink(int node, int level, int other) {
        int[] arr = slabArray(node, level);
        int base = slabBase(node, level);
        int degree = arr[base];
        for (int i = 1; i <= degree; i++) {
            if (arr[base + i] == other) {
                arr[base + i] = arr[base + degree];
                arr[base] = degree - 1;
                return;
            }
        }
    }

    /** A level for {@code element} drawn from the usual exponential distribution, seeded by the name alone. */
    private int levelFor(String element) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < element.length(); i++) {
            h ^= element.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        double uniform = ((h >>> 11) + 1) * 0x1.0p-53; // (0, 1]
        return Math.min(MAX_LEVEL, (int) (-StrictMath.log(uniform) * levelMult));
    }

    // ================================================================== searching the graph

    /**
     * Follows the best neighbour on one level until none is better than the current node; the answer is left in
     * {@code c.greedyId/greedyScore}.
     */
    private void greedy(SearchContext c, int start, float startScore, int level) {
        int current = start;
        float best = startScore;
        boolean improved = true;
        while (improved) {
            improved = false;
            int[] arr = slabArray(current, level);
            int base = slabBase(current, level);
            int degree = arr[base];
            for (int i = 1; i <= degree; i++) {
                int neighbour = arr[base + i];
                float score = scoreQuery(c, neighbour);
                if (score > best) {
                    best = score;
                    current = neighbour;
                    improved = true;
                }
            }
        }
        c.greedyId = current;
        c.greedyScore = best;
    }

    /**
     * Best-first search of one level. The entry points must already be in both heaps and marked visited; on return
     * {@code c.results} holds the {@code ef} most similar nodes found.
     */
    private void searchLayer(SearchContext c, int ef, int level) {
        final LongHeap candidates = c.candidates;
        final LongHeap results = c.results;
        float worst = results.size() >= ef ? resultScore(results.peek()) : Float.NEGATIVE_INFINITY;
        while (candidates.size() > 0) {
            long top = candidates.pop();
            if (results.size() >= ef && candidateScore(top) < worst) {
                break;
            }
            int node = keyNode(top);
            int[] arr;
            int base;
            if (level == 0) {
                arr = links0[node >>> shift];
                base = (node & mask) * slab0;
            } else {
                arr = upper[node >>> shift][node & mask];
                base = (level - 1) * slabUp;
            }
            int degree = arr[base];
            for (int i = 1; i <= degree; i++) {
                int neighbour = arr[base + i];
                if (c.seen(neighbour)) {
                    continue;
                }
                float score = scoreQuery(c, neighbour);
                if (results.size() < ef) {
                    candidates.push(candidateKey(score, neighbour));
                    results.push(resultKey(score, neighbour));
                    if (results.size() >= ef) {
                        worst = resultScore(results.peek());
                    }
                } else if (score > worst) {
                    candidates.push(candidateKey(score, neighbour));
                    results.push(resultKey(score, neighbour));
                    results.pop();
                    worst = resultScore(results.peek());
                }
            }
        }
    }

    /** Empties the result heap into {@code c.sortedIds/sortedScores}, most similar first. */
    private void drainResults(SearchContext c) {
        int n = c.results.size();
        for (int i = n - 1; i >= 0; i--) {
            long key = c.results.pop();
            c.sortedIds[i] = keyNode(key);
            c.sortedScores[i] = resultScore(key);
        }
        c.sortedCount = n;
    }

    /** Scans every element and keeps the {@code k} most similar in {@code c.results}. */
    private void scanAll(SearchContext c, int k) {
        c.results.clear();
        for (int p = 0; p < pages; p++) {
            String[] pageNames = names[p];
            for (int o = 0; o < pageSlots; o++) {
                if (pageNames[o] == null) {
                    continue;
                }
                int node = (p << shift) | o;
                float score = scoreQuery(c, node);
                if (c.results.size() < k) {
                    c.results.push(resultKey(score, node));
                } else if (score > resultScore(c.results.peek())) {
                    c.results.pop();
                    c.results.push(resultKey(score, node));
                }
            }
        }
    }

    /**
     * The search proper, for a query that is already loaded into {@code c}. Leaves the best hits in
     * {@code c.sortedIds/sortedScores} (at least {@code count} of them when the set has that many).
     */
    private void runSearch(SearchContext c, int count, int ef, boolean exact) {
        int want = Math.max(count, 1);
        int effort = Math.max(ef, want);
        c.ensureSlots(slots);
        c.ensureWidth(Math.max(effort, want), m0);
        c.beginSearch();
        if (exact || this.count <= 2 * effort || entry < 0) {
            scanAll(c, want);
            drainResults(c);
            return;
        }
        int node = entry;
        float score = scoreQuery(c, node);
        for (int level = maxLevel; level >= 1; level--) {
            greedy(c, node, score, level);
            node = c.greedyId;
            score = c.greedyScore;
        }
        c.seen(node);
        c.candidates.push(candidateKey(score, node));
        c.results.push(resultKey(score, node));
        searchLayer(c, effort, 0);
        drainResults(c);
    }

    private SearchContext borrow() {
        SearchContext c = pool.poll();
        return c != null ? c : new SearchContext(dim, words);
    }

    private void release(SearchContext c) {
        pool.offer(c);
    }

    private List<Hit> hits(SearchContext c, int count, float minSimilarity) {
        List<Hit> out = new ArrayList<>(Math.min(count, c.sortedCount));
        for (int i = 0; i < c.sortedCount && out.size() < count; i++) {
            if (c.sortedScores[i] < minSimilarity) {
                break;
            }
            out.add(new Hit(nameOf(c.sortedIds[i]), c.sortedScores[i]));
        }
        return out;
    }

    private void checkReadable() {
        if (restoring) {
            throw new IllegalStateException("the vector set is still being restored");
        }
    }

    // ================================================================== public queries

    /**
     * The {@code count} elements most similar to {@code query}.
     *
     * @param ef            how many nodes the graph search keeps while exploring; larger is slower and more accurate
     * @param minSimilarity drop hits below this cosine similarity (use {@code -1} for none)
     * @param exact         scan everything instead of walking the graph
     */
    public List<Hit> search(Encoded query, int count, int ef, float minSimilarity, boolean exact) {
        SearchContext c = borrow();
        try {
            lock.readLock().lock();
            try {
                checkReadable();
                loadQuery(c, query);
                runSearch(c, count, ef, exact);
                return hits(c, count, minSimilarity);
            } finally {
                lock.readLock().unlock();
            }
        } finally {
            release(c);
        }
    }

    /**
     * Like {@link #search} with the stored vector of {@code element} as the query (the element itself is
     * included).
     *
     * @return the hits, or {@code null} if the element is not in the set
     */
    public List<Hit> searchLike(String element, int count, int ef, float minSimilarity, boolean exact) {
        SearchContext c = borrow();
        try {
            lock.readLock().lock();
            try {
                checkReadable();
                int slot = indexGet(element);
                if (slot < 0) {
                    return null;
                }
                loadQueryFromSlot(c, slot);
                runSearch(c, count, ef, exact);
                return hits(c, count, minSimilarity);
            } finally {
                lock.readLock().unlock();
            }
        } finally {
            release(c);
        }
    }

    /**
     * The normalized vector of {@code element} as stored (so, after quantization), or {@code null}.
     * Multiply by {@link #norm} to get back the original length.
     */
    public float[] embedding(String element) {
        lock.readLock().lock();
        try {
            checkReadable();
            int slot = indexGet(element);
            if (slot < 0) {
                return null;
            }
            int p = slot >>> shift;
            int o = slot & mask;
            float[] out = new float[dim];
            switch (quant) {
                case Q8 -> {
                    float scale = scales[p][o];
                    byte[] page = q8[p];
                    for (int i = 0; i < dim; i++) {
                        out[i] = page[o * dim + i] * scale;
                    }
                }
                case NOQUANT -> System.arraycopy(f32[p], o * dim, out, 0, dim);
                case BIN -> {
                    float magnitude = (float) (1.0 / Math.sqrt(dim));
                    long[] page = bin[p];
                    for (int i = 0; i < dim; i++) {
                        boolean set = (page[o * words + (i >>> 6)] >>> (i & 63) & 1L) != 0;
                        out[i] = set ? magnitude : -magnitude;
                    }
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The stored codes of an element in their raw form, for {@code VEMB ... RAW}. */
    public record Raw(Quantization quantization, byte[] codes, float norm, float range) {
    }

    /** @return the raw stored form of {@code element}, or {@code null} */
    public Raw raw(String element) {
        lock.readLock().lock();
        try {
            checkReadable();
            int slot = indexGet(element);
            if (slot < 0) {
                return null;
            }
            int p = slot >>> shift;
            int o = slot & mask;
            byte[] codes;
            float range = 1f;
            switch (quant) {
                case Q8 -> {
                    codes = Arrays.copyOfRange(q8[p], o * dim, (o + 1) * dim);
                    range = scales[p][o] * 127f;
                }
                case NOQUANT -> {
                    codes = new byte[4 * dim];
                    java.nio.ByteBuffer.wrap(codes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer().put(f32[p], o * dim, dim);
                }
                default -> {
                    codes = new byte[8 * words];
                    java.nio.ByteBuffer.wrap(codes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asLongBuffer().put(bin[p], o * words, words);
                }
            }
            return new Raw(quant, codes, norms[p][o], range);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** @return the length the vector had before normalization, or {@code NaN} if the element does not exist */
    public float norm(String element) {
        lock.readLock().lock();
        try {
            int slot = indexGet(element);
            return slot < 0 ? Float.NaN : norms[slot >>> shift][slot & mask];
        } finally {
            lock.readLock().unlock();
        }
    }

    /** @return the attribute string of {@code element}, or {@code null} if it has none or does not exist */
    public String attribute(String element) {
        lock.readLock().lock();
        try {
            int slot = indexGet(element);
            return slot < 0 ? null : attributeOf(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The attribute strings of the elements of a hit list, in order (nulls where there is none). */
    public List<String> attributes(List<Hit> hits) {
        lock.readLock().lock();
        try {
            List<String> out = new ArrayList<>(hits.size());
            for (Hit hit : hits) {
                int slot = indexGet(hit.element());
                out.add(slot < 0 ? null : attributeOf(slot));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private String attributeOf(int slot) {
        String[] page = attrs[slot >>> shift];
        return page == null ? null : page[slot & mask];
    }

    /** One neighbour of an element on one level. */
    public record Link(String element, float similarity) {
    }

    /**
     * The graph neighbours of {@code element}, one list per level (level 0 first).
     *
     * @return the lists, or {@code null} if the element does not exist
     */
    public List<List<Link>> links(String element) {
        lock.readLock().lock();
        try {
            checkReadable();
            int slot = indexGet(element);
            if (slot < 0) {
                return null;
            }
            int level = levelOf(slot);
            List<List<Link>> out = new ArrayList<>(level + 1);
            for (int l = 0; l <= level; l++) {
                int[] arr = slabArray(slot, l);
                int base = slabBase(slot, l);
                int degree = arr[base];
                List<Link> list = new ArrayList<>(degree);
                for (int i = 1; i <= degree; i++) {
                    int neighbour = arr[base + i];
                    list.add(new Link(nameOf(neighbour), scoreNodes(slot, neighbour)));
                }
                out.add(list);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Random elements. With {@code unique} the result has at most {@code count} distinct elements; otherwise it has
     * exactly {@code count} and may repeat.
     */
    public List<String> randomElements(int count, boolean unique) {
        lock.readLock().lock();
        try {
            checkReadable();
            List<String> out = new ArrayList<>();
            if (this.count == 0 || count <= 0) {
                return out;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (!unique) {
                for (int i = 0; i < count; i++) {
                    out.add(nameOf(randomLiveSlot(random)));
                }
                return out;
            }
            int target = Math.min(count, this.count);
            if (target * 2 >= this.count) { // most of the set: shuffle all names, take a prefix
                List<String> all = new ArrayList<>(this.count);
                for (int p = 0; p < pages; p++) {
                    for (String name : names[p]) {
                        if (name != null) {
                            all.add(name);
                        }
                    }
                }
                java.util.Collections.shuffle(all, random);
                return new ArrayList<>(all.subList(0, target));
            }
            java.util.Set<Integer> chosen = new java.util.HashSet<>();
            while (chosen.size() < target) {
                chosen.add(randomLiveSlot(random));
            }
            for (int slot : chosen) {
                out.add(nameOf(slot));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private int randomLiveSlot(ThreadLocalRandom random) {
        // A free slot is rare enough in practice that rejection sampling is fast; after many misses scan instead.
        for (int attempt = 0; attempt < 64; attempt++) {
            int slot = random.nextInt(slots);
            if (nameOf(slot) != null) {
                return slot;
            }
        }
        int skip = random.nextInt(count);
        for (int p = 0; p < pages; p++) {
            for (int o = 0; o < pageSlots; o++) {
                if (names[p][o] != null && skip-- == 0) {
                    return (p << shift) | o;
                }
            }
        }
        throw new IllegalStateException("no live slot");
    }

    // ================================================================== inserting

    /**
     * Adds {@code element}, or replaces its vector if it already exists.
     *
     * @param attribute the attribute string to store; {@code null} keeps the element's current one
     * @return {@code true} if the element is new, {@code false} if an existing element was updated
     */
    public boolean add(String element, Encoded vector, String attribute) {
        return add(element, vector, attribute, 0);
    }

    /**
     * Like {@link #add(String, Encoded, String)} with an explicit exploration factor for this insertion
     * ({@code 0} uses the one the set was created with): larger builds a better neighbourhood and costs more.
     *
     * <p>An insertion is two steps of very different weight. Finding the neighbours of the new vector (a graph
     * search, about two thirds of the work) only reads the graph, so it runs under the <em>shared</em> lock while
     * searches carry on; only wiring the new node in, which changes links, takes the exclusive lock. If somebody
     * else changed the set between the two steps, the neighbours are simply searched again under the exclusive lock.
     */
    public boolean add(String element, Encoded vector, String attribute, int ef) {
        return add(element, vector, attribute, ef, null);
    }

    /**
     * Finds the neighbours of a new element without changing the set (read lock only), so the caller can do it
     * before taking the server's write lock and hand the result to {@link #add(String, Encoded, String, int, Plan)}.
     *
     * @return the plan, or {@code null} if there is nothing to plan: the set is empty, or the element exists
     */
    public Plan plan(String element, Encoded vector, int ef) {
        if (ef < 0 || ef > MAX_EF) {
            throw new IllegalArgumentException("EF must be between 1 and " + MAX_EF);
        }
        final int effort = ef == 0 ? efConstruction : ef;
        final int level = levelFor(element);
        SearchContext reader = borrow();
        try {
            lock.readLock().lock();
            try {
                if (restoring || entry < 0 || indexGet(element) >= 0) { // an update must remove the old node first
                    return null;
                }
                return planInsert(reader, vector, level, effort);
            } finally {
                lock.readLock().unlock();
            }
        } finally {
            release(reader);
        }
    }

    /**
     * Like {@link #add(String, Encoded, String, int)} but with the neighbours already found by {@link #plan}.
     * A plan that no longer fits (the set changed since, or the element exists now) is ignored and the search
     * redone, so passing one can only make the insertion faster, never wrong.
     */
    public boolean add(String element, Encoded vector, String attribute, int ef, Plan prepared) {
        if (ef < 0 || ef > MAX_EF) {
            throw new IllegalArgumentException("EF must be between 1 and " + MAX_EF);
        }
        final int effort = ef == 0 ? efConstruction : ef;
        final int level = levelFor(element);

        Plan plan = prepared != null ? prepared : plan(element, vector, ef);

        lock.writeLock().lock();
        try {
            checkWritable();
            if (plan != null && (plan.removals != removals || plan.entry != entry || plan.maxLevel != maxLevel)) {
                plan = null; // a neighbour may be gone, or the top of the graph moved: search again
            }
            int existing = indexGet(element);
            boolean created = existing < 0;
            String attr = attribute;
            if (!created) {
                if (attr == null) {
                    attr = attributeOf(existing);
                }
                removeSlot(existing);
                plan = null;
            }
            int slot = allocSlot();
            int p = slot >>> shift;
            int o = slot & mask;

            writeCodes(slot, vector);
            names[p][o] = element;
            levels[p][o] = (byte) level;
            links0[p][o * slab0] = 0;
            upper[p][o] = level > 0 ? new int[level * slabUp] : null;
            bytes += NAME_OVERHEAD + element.length() + 8 + (level > 0 ? 16L + 4L * level * slabUp : 0);
            indexPut(element, slot);
            count++;
            if (attr != null && !attr.isEmpty()) {
                setAttributeAt(slot, attr);
            }
            if (entry < 0) {
                entry = slot;
                maxLevel = level;
            } else {
                if (plan == null) {
                    plan = planInsert(writeContext, vector, level, effort);
                }
                wireIn(writeContext, slot, level, plan);
            }
            return created;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkWritable() {
        if (restoring) {
            throw new IllegalStateException("the vector set is still being restored");
        }
    }

    private void setAttributeAt(int slot, String value) {
        int p = slot >>> shift;
        if (attrs[p] == null) {
            attrs[p] = new String[pageSlots];
            bytes += 8L * pageSlots;
        }
        String old = attrs[p][slot & mask];
        if (old != null) {
            bytes -= ATTRIBUTE_OVERHEAD + old.length();
            attrCount--;
        }
        if (value == null || value.isEmpty()) {
            attrs[p][slot & mask] = null;
            return;
        }
        attrs[p][slot & mask] = value;
        bytes += ATTRIBUTE_OVERHEAD + value.length();
        attrCount++;
    }

    /**
     * Sets the attribute string of an element; an empty string removes it.
     *
     * @return {@code false} if the element does not exist
     */
    public boolean setAttribute(String element, String value) {
        lock.writeLock().lock();
        try {
            int slot = indexGet(element);
            if (slot < 0) {
                return false;
            }
            setAttributeAt(slot, value);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * The neighbours chosen for a new element on every level, found without changing the graph (see {@link #plan}).
     * Opaque to callers. Other elements being inserted meanwhile do not spoil it - they are just not among the
     * neighbours, as in any parallel HNSW build - but a removal might (the slot of a neighbour could be reused), and
     * so would a new entry point; in those cases {@code add} quietly searches again.
     */
    public static final class Plan {
        /** The state of the set the plan was made in. */
        final long removals;
        final int entry;
        final int maxLevel;
        /** Highest level the node is wired in on: the lower of its own level and the graph's top level. */
        int top;
        int[][] ids;
        float[][] scores;
        /** The best level-0 candidates, to fall back on if none of the chosen neighbours can take a new link. */
        int[] fallbackIds;
        float[] fallbackScores;

        private Plan(long removals, int entry, int maxLevel) {
            this.removals = removals;
            this.entry = entry;
            this.maxLevel = maxLevel;
        }
    }

    /**
     * Searches the graph for the neighbours of a vector that is about to be inserted: greedy descent through the
     * levels above the node's own, then on each of its levels a best-first search whose result is thinned by the
     * diversity heuristic. Reads the graph only; call with the shared or the exclusive lock held and the set not empty.
     */
    private Plan planInsert(SearchContext c, Encoded vector, int level, int ef) {
        c.ensureSlots(slots);
        c.ensureWidth(ef, m0);
        loadQuery(c, vector);

        Plan plan = new Plan(removals, entry, maxLevel);
        int node = entry;
        float score = scoreQuery(c, node);
        for (int l = maxLevel; l > level; l--) {
            greedy(c, node, score, l);
            node = c.greedyId;
            score = c.greedyScore;
        }
        c.epIds[0] = node;
        c.epScores[0] = score;
        c.epCount = 1;

        plan.top = Math.min(level, maxLevel);
        plan.ids = new int[plan.top + 1][];
        plan.scores = new float[plan.top + 1][];
        for (int l = plan.top; l >= 0; l--) {
            c.beginSearch();
            for (int i = 0; i < c.epCount; i++) {
                int e = c.epIds[i];
                if (!c.seen(e)) {
                    c.candidates.push(candidateKey(c.epScores[i], e));
                    c.results.push(resultKey(c.epScores[i], e));
                }
            }
            searchLayer(c, ef, l);
            drainResults(c);

            int selected = selectNeighbours(c, -1, capOf(l));
            plan.ids[l] = Arrays.copyOf(c.selIds, selected);
            plan.scores[l] = Arrays.copyOf(c.selScores, selected);
            if (l == 0) {
                int keep = Math.min(c.sortedCount, 32);
                plan.fallbackIds = Arrays.copyOf(c.sortedIds, keep);
                plan.fallbackScores = Arrays.copyOf(c.sortedScores, keep);
            }
            // everything found on this level is a good place to start on the one below
            int carry = Math.min(c.sortedCount, c.epIds.length);
            System.arraycopy(c.sortedIds, 0, c.epIds, 0, carry);
            System.arraycopy(c.sortedScores, 0, c.epScores, 0, carry);
            c.epCount = carry;
        }
        return plan;
    }

    /** Links the stored node to the neighbours in the plan (exclusive lock held, plan still valid). */
    private void wireIn(SearchContext c, int slot, int level, Plan plan) {
        c.ensureWidth(efConstruction, m0);
        for (int l = plan.top; l >= 0; l--) {
            int[] ids = plan.ids[l];
            float[] scores = plan.scores[l];
            for (int i = 0; i < ids.length; i++) {
                connect(c, slot, ids[i], l, scores[i], false);
            }
            if (l == 0 && degree(slot, 0) == 0) { // never leave a node cut off from the rest of the graph
                for (int i = 0; i < plan.fallbackIds.length; i++) {
                    if (connect(c, slot, plan.fallbackIds[i], 0, plan.fallbackScores[i], true)) {
                        break;
                    }
                }
            }
        }
        if (level > maxLevel) {
            entry = slot;
            maxLevel = level;
        }
    }

    /**
     * The "diverse neighbours" heuristic: walking the candidates from the most similar, keep one only if it is more
     * similar to the new node than to every neighbour already kept. Keeps links pointing in different directions,
     * which is what lets the search cross between clusters.
     */
    private int selectNeighbours(SearchContext c, int node, int cap) {
        int selected = 0;
        for (int i = 0; i < c.sortedCount && selected < cap; i++) {
            int candidate = c.sortedIds[i];
            if (candidate == node) {
                continue;
            }
            float score = c.sortedScores[i];
            boolean diverse = true;
            for (int j = 0; j < selected; j++) {
                if (scoreNodes(candidate, c.selIds[j]) > score) {
                    diverse = false;
                    break;
                }
            }
            if (diverse) {
                c.selIds[selected] = candidate;
                c.selScores[selected] = score;
                selected++;
            }
        }
        if (selected == 0) {
            for (int i = 0; i < c.sortedCount; i++) {
                if (c.sortedIds[i] != node) {
                    c.selIds[0] = c.sortedIds[i];
                    c.selScores[0] = c.sortedScores[i];
                    return 1;
                }
            }
        }
        return selected;
    }

    /**
     * Links {@code q} and {@code r} on {@code level} in both directions. If {@code r} already has the maximum
     * number of links, one of them is dropped to make room (see {@link #chooseVictim}); if that victim would be the
     * new link itself, nothing is linked.
     *
     * @param force drop a link of {@code r} even if the new one is the least useful (used so a node is never left
     *              without any link)
     * @return whether the two are linked afterwards
     */
    private boolean connect(SearchContext c, int q, int r, int level, float similarity, boolean force) {
        if (q == r) {
            return false;
        }
        if (hasLink(q, level, r)) {
            return true;
        }
        int cap = capOf(level);
        if (degree(q, level) >= cap) {
            return false;
        }
        if (degree(r, level) < cap) {
            addLink(r, level, q);
            addLink(q, level, r);
            return true;
        }
        int victim = chooseVictim(c, r, level, q, similarity, force);
        if (victim < 0 || victim == q) {
            return false;
        }
        removeLink(r, level, victim);
        removeLink(victim, level, r);
        addLink(r, level, q);
        addLink(q, level, r);
        return true;
    }

    /**
     * Picks the link of the full node {@code r} to drop in favour of {@code q}. Links are ranked by similarity to
     * {@code r}; walking from the least similar, the first one that is closer to a better-ranked neighbour than to
     * {@code r} itself is redundant (that neighbour already leads there) and goes. If the few farthest links are all
     * useful, the farthest goes. A link whose other end has no other link is never chosen, so nobody is isolated.
     *
     * @return the node to unlink from {@code r} (possibly {@code q}, meaning "do not add"), or -1 if none can go
     */
    private int chooseVictim(SearchContext c, int r, int level, int q, float similarityQ, boolean force) {
        int[] arr = slabArray(r, level);
        int base = slabBase(r, level);
        int degree = arr[base];
        int[] ids = c.tmpIds;
        float[] sims = c.tmpScores;
        int total = 0;
        for (int i = 1; i <= degree; i++) {
            int id = arr[base + i];
            ids[total] = id;
            sims[total] = scoreNodes(r, id);
            total++;
        }
        if (!force) {
            ids[total] = q;
            sims[total] = similarityQ;
            total++;
        }
        for (int i = 1; i < total; i++) { // insertion sort, most similar first (at most 2M + 1 items)
            int id = ids[i];
            float sim = sims[i];
            int j = i - 1;
            while (j >= 0 && sims[j] < sim) {
                ids[j + 1] = ids[j];
                sims[j + 1] = sims[j];
                j--;
            }
            ids[j + 1] = id;
            sims[j + 1] = sim;
        }
        int probes = 0;
        for (int i = total - 1; i >= 1 && probes < DIVERSITY_PROBES; i--) {
            int candidate = ids[i];
            if (candidate != q && degree(candidate, level) <= 1) {
                continue;
            }
            probes++;
            for (int j = 0; j < i; j++) {
                if (scoreNodes(candidate, ids[j]) > sims[i]) {
                    return candidate;
                }
            }
        }
        for (int i = total - 1; i >= 0; i--) {
            int candidate = ids[i];
            if (candidate == q || degree(candidate, level) > 1) {
                return candidate;
            }
        }
        return -1;
    }

    // ================================================================== removing

    /**
     * Removes an element.
     *
     * @return {@code false} if it did not exist
     */
    public boolean remove(String element) {
        lock.writeLock().lock();
        try {
            if (restoring) {
                throw new IllegalStateException("the vector set is still being restored");
            }
            int slot = indexGet(element);
            if (slot < 0) {
                return false;
            }
            removeSlot(slot);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Unlinks the node (every node pointing at it is, by reciprocity, in its own lists), reconnects the nodes that
     * lost a link with each other, and frees the slot.
     */
    private void removeSlot(int slot) {
        removals++;
        final int p = slot >>> shift;
        final int o = slot & mask;
        final int level = levels[p][o];
        final String name = names[p][o];
        int[][] orphans = new int[level + 1][];
        for (int l = 0; l <= level; l++) {
            int[] arr = slabArray(slot, l);
            int base = slabBase(slot, l);
            int degree = arr[base];
            orphans[l] = Arrays.copyOfRange(arr, base + 1, base + 1 + degree);
            arr[base] = 0;
            for (int neighbour : orphans[l]) {
                removeLink(neighbour, l, slot);
            }
        }
        indexRemove(name);
        String attribute = attributeOf(slot);
        if (attribute != null) {
            attrs[p][o] = null;
            attrCount--;
            bytes -= ATTRIBUTE_OVERHEAD + attribute.length();
        }
        names[p][o] = null;
        upper[p][o] = null;
        levels[p][o] = 0;
        bytes -= NAME_OVERHEAD + name.length() + 8 + (level > 0 ? 16L + 4L * level * slabUp : 0);
        pushFree(slot);
        count--;

        if (count == 0) {
            entry = -1;
            maxLevel = -1;
            return;
        }
        if (entry == slot) {
            electEntry();
        }
        writeContext.ensureSlots(slots);
        writeContext.ensureWidth(efConstruction, m0);
        for (int l = 0; l <= level; l++) {
            repair(writeContext, orphans[l], l);
        }
    }

    /** Chooses the live node with the highest level as the new entry point (rare: only when the entry is removed). */
    private void electEntry() {
        int best = -1;
        int bestLevel = -1;
        for (int p = 0; p < pages; p++) {
            String[] pageNames = names[p];
            byte[] pageLevels = levels[p];
            for (int o = 0; o < pageSlots; o++) {
                if (pageNames[o] != null && pageLevels[o] > bestLevel) {
                    bestLevel = pageLevels[o];
                    best = (p << shift) | o;
                }
            }
        }
        entry = best;
        maxLevel = bestLevel;
    }

    /**
     * After a node was unlinked, its former neighbours each lack a link. They are the nodes most likely to want
     * each other: connect them pairwise, most similar pairs first, as far as their link limits allow.
     */
    private void repair(SearchContext c, int[] orphans, int level) {
        int k = Math.min(orphans.length, 64);
        int cap = capOf(level);
        if (k >= 2) {
            long[] pairs = new long[k * (k - 1) / 2];
            int n = 0;
            for (int a = 0; a < k; a++) {
                for (int b = a + 1; b < k; b++) {
                    float score = scoreNodes(orphans[a], orphans[b]);
                    pairs[n++] = resultKey(score, a * 64 + b);
                }
            }
            Arrays.sort(pairs, 0, n);
            for (int i = n - 1; i >= 0; i--) {
                int pair = keyNode(pairs[i]);
                int a = orphans[pair / 64];
                int b = orphans[pair % 64];
                if (degree(a, level) < cap && degree(b, level) < cap && !hasLink(a, level, b)) {
                    addLink(a, level, b);
                    addLink(b, level, a);
                }
            }
        }
        if (level == 0) {
            for (int orphan : orphans) {
                if (degree(orphan, 0) == 0 && count > 1) {
                    attach(c, orphan);
                }
            }
        }
    }

    /** Connects a node that has no level-0 link at all to the most similar node the graph can find for it. */
    private void attach(SearchContext c, int node) {
        c.ensureWidth(Math.max(32, m0), m0);
        loadQueryFromSlot(c, node);
        c.beginSearch();
        int start = entry;
        float score;
        if (start == node || start < 0) {
            start = anyLiveSlotExcept(node);
            score = scoreQuery(c, start);
        } else {
            score = scoreQuery(c, start);
            for (int l = maxLevel; l >= 1; l--) {
                greedy(c, start, score, l);
                start = c.greedyId;
                score = c.greedyScore;
            }
        }
        c.seen(start);
        c.seen(node);
        c.candidates.push(candidateKey(score, start));
        c.results.push(resultKey(score, start));
        searchLayer(c, Math.max(32, m0), 0);
        drainResults(c);
        for (int i = 0; i < c.sortedCount; i++) {
            if (c.sortedIds[i] != node && connect(c, node, c.sortedIds[i], 0, c.sortedScores[i], true)) {
                return;
            }
        }
    }

    private int anyLiveSlotExcept(int except) {
        for (int p = 0; p < pages; p++) {
            String[] pageNames = names[p];
            for (int o = 0; o < pageSlots; o++) {
                int slot = (p << shift) | o;
                if (pageNames[o] != null && slot != except) {
                    return slot;
                }
            }
        }
        throw new IllegalStateException("no other live node");
    }


    // ================================================================== dump and restore

    /** Longest element name accepted; together with {@link #MAX_ATTRIBUTE_BYTES} it keeps one dump record under the 1 MB command argument limit. */
    public static final int MAX_ELEMENT_BYTES = 64 * 1024;
    public static final int MAX_ATTRIBUTE_BYTES = 256 * 1024;

    private static final int DUMP_MAGIC = 0x53564352; // "RCVS" read as a little-endian int
    private static final int DUMP_VERSION = 1;
    private static final int DUMP_HEADER_BYTES = 4 + 1 + 1 + 4 + 2 + 2 + 4 + 1 + 4;
    /** A dump chunk is flushed once it holds this much; one record adds at most about 400 KB on top. */
    private static final int CHUNK_TARGET_BYTES = 512 * 1024;

    // state of a restore in progress (valid while `restoring`)
    private int expectedNodes;
    private int declaredMaxLevel;
    private int declaredEntry;

    /** One part of a dump: the nodes numbered {@code firstId} and up, in the binary node format. */
    public record Chunk(int firstId, byte[] data) {
    }

    /**
     * A compact, self-contained copy of a set: a header plus chunks of nodes, numbered densely (free slots are
     * squeezed out). Loading it needs no searching - the links are in it - which is what makes restarting from an
     * append-only file rewrite, or syncing a replica, take seconds instead of the hours that inserting millions of
     * vectors again would.
     */
    public record Dump(byte[] header, List<Chunk> chunks) {
    }

    /** @return a consistent copy of the whole set; takes the read lock for the duration of the copy */
    public Dump dump() {
        lock.readLock().lock();
        try {
            checkReadable();
            int[] dense = new int[slots];
            int n = 0;
            for (int slot = 0; slot < slots; slot++) {
                dense[slot] = nameOf(slot) == null ? -1 : n++;
            }
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(DUMP_HEADER_BYTES)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(DUMP_MAGIC).put((byte) DUMP_VERSION).put((byte) quant.code()).putInt(dim)
                    .putShort((short) m).putShort((short) efConstruction).putInt(n)
                    .put((byte) maxLevel).putInt(entry < 0 ? -1 : dense[entry]);

            List<Chunk> chunks = new ArrayList<>();
            java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(CHUNK_TARGET_BYTES + 512 * 1024)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int firstInChunk = 0;
            int written = 0;
            for (int slot = 0; slot < slots; slot++) {
                if (dense[slot] < 0) {
                    continue;
                }
                if (out.position() >= CHUNK_TARGET_BYTES) {
                    chunks.add(new Chunk(firstInChunk, Arrays.copyOf(out.array(), out.position())));
                    out.clear();
                    firstInChunk = written;
                }
                writeRecord(out, slot, dense);
                written++;
            }
            if (out.position() > 0) {
                chunks.add(new Chunk(firstInChunk, Arrays.copyOf(out.array(), out.position())));
            }
            return new Dump(header.array(), chunks);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void writeRecord(java.nio.ByteBuffer out, int slot, int[] dense) {
        int p = slot >>> shift;
        int o = slot & mask;
        int level = levels[p][o];
        out.put((byte) level).putFloat(norms[p][o]).putFloat(quant == Quantization.Q8 ? scales[p][o] : 1f);
        switch (quant) {
            case Q8 -> out.put(q8[p], o * dim, dim);
            case NOQUANT -> {
                float[] page = f32[p];
                for (int i = 0; i < dim; i++) {
                    out.putFloat(page[o * dim + i]);
                }
            }
            case BIN -> {
                long[] page = bin[p];
                for (int w = 0; w < words; w++) {
                    out.putLong(page[o * words + w]);
                }
            }
        }
        byte[] name = names[p][o].getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.putInt(name.length).put(name);
        String attribute = attributeOf(slot);
        if (attribute == null) {
            out.putInt(-1);
        } else {
            byte[] raw = attribute.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            out.putInt(raw.length).put(raw);
        }
        for (int l = 0; l <= level; l++) {
            int[] arr = slabArray(slot, l);
            int base = slabBase(slot, l);
            int degree = arr[base];
            out.putShort((short) degree);
            for (int i = 1; i <= degree; i++) {
                out.putInt(dense[arr[base + i]]);
            }
        }
    }

    /**
     * Starts loading a dump: returns an empty set with the parameters from the header that refuses searches until
     * {@link #finishRestore} succeeds.
     *
     * @throws IllegalArgumentException if the header is not valid
     */
    public static VectorSet beginRestore(byte[] header) {
        if (header.length != DUMP_HEADER_BYTES) {
            throw new IllegalArgumentException("bad vector dump header");
        }
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != DUMP_MAGIC || in.get() != DUMP_VERSION) {
            throw new IllegalArgumentException("not a vector dump of a supported version");
        }
        Quantization quantization = Quantization.fromCode(in.get());
        int dim = in.getInt();
        int m = in.getShort();
        int ef = in.getShort() & 0xffff;
        int nodes = in.getInt();
        int maxLevel = in.get();
        int entry = in.getInt();
        if (quantization == null || nodes < 0 || maxLevel < -1 || maxLevel > MAX_LEVEL
                || entry < -1 || entry >= Math.max(nodes, 1) || (nodes == 0) != (entry == -1)) {
            throw new IllegalArgumentException("bad vector dump header");
        }
        VectorSet set = new VectorSet(dim, quantization, m, ef); // validates dim, m and ef
        set.restoring = true;
        set.expectedNodes = nodes;
        set.declaredMaxLevel = maxLevel;
        set.declaredEntry = entry;
        return set;
    }

    /**
     * Appends the nodes of one dump chunk. Chunks must arrive in order.
     *
     * @throws IllegalArgumentException if the data is malformed or out of order
     */
    public void restoreChunk(int firstId, byte[] data) {
        lock.writeLock().lock();
        try {
            if (!restoring) {
                throw new IllegalStateException("no restore in progress");
            }
            if (firstId != slots) {
                throw new IllegalArgumentException("vector dump chunks are out of order");
            }
            java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            try {
                while (in.hasRemaining()) {
                    if (slots >= expectedNodes) {
                        throw new IllegalArgumentException("more nodes than the dump header announced");
                    }
                    readRecord(in);
                }
            } catch (java.nio.BufferUnderflowException e) {
                throw new IllegalArgumentException("truncated vector dump");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void readRecord(java.nio.ByteBuffer in) {
        int level = in.get() & 0xff;
        if (level > MAX_LEVEL) {
            throw new IllegalArgumentException("bad node level in vector dump");
        }
        float norm = in.getFloat();
        float scale = in.getFloat();
        int slot = allocSlot();
        int p = slot >>> shift;
        int o = slot & mask;
        switch (quant) {
            case Q8 -> {
                in.get(q8[p], o * dim, dim);
                scales[p][o] = scale;
            }
            case NOQUANT -> {
                float[] page = f32[p];
                for (int i = 0; i < dim; i++) {
                    page[o * dim + i] = in.getFloat();
                }
            }
            case BIN -> {
                long[] page = bin[p];
                for (int w = 0; w < words; w++) {
                    page[o * words + w] = in.getLong();
                }
            }
        }
        norms[p][o] = norm;
        int nameLength = in.getInt();
        if (nameLength < 0 || nameLength > MAX_ELEMENT_BYTES || nameLength > in.remaining()) {
            throw new IllegalArgumentException("bad element name in vector dump");
        }
        byte[] nameBytes = new byte[nameLength];
        in.get(nameBytes);
        String name = new String(nameBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int attributeLength = in.getInt();
        String attribute = null;
        if (attributeLength >= 0) {
            if (attributeLength > MAX_ATTRIBUTE_BYTES || attributeLength > in.remaining()) {
                throw new IllegalArgumentException("bad attribute in vector dump");
            }
            byte[] raw = new byte[attributeLength];
            in.get(raw);
            attribute = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        names[p][o] = name;
        levels[p][o] = (byte) level;
        upper[p][o] = level > 0 ? new int[level * slabUp] : null;
        for (int l = 0; l <= level; l++) {
            int degree = in.getShort() & 0xffff;
            if (degree > capOf(l)) {
                throw new IllegalArgumentException("bad link count in vector dump");
            }
            int[] arr = slabArray(slot, l);
            int base = slabBase(slot, l);
            arr[base] = degree;
            for (int i = 1; i <= degree; i++) {
                arr[base + i] = in.getInt();
            }
        }
        if (indexGet(name) >= 0) {
            names[p][o] = null; // keep the half-written slot from being seen as live
            throw new IllegalArgumentException("duplicate element in vector dump");
        }
        bytes += NAME_OVERHEAD + name.length() + 8 + (level > 0 ? 16L + 4L * level * slabUp : 0);
        indexPut(name, slot);
        count++;
        if (attribute != null && !attribute.isEmpty()) {
            setAttributeAt(slot, attribute);
        }
    }

    /**
     * Completes a restore after checking that every link points at an existing node that reaches the level of the
     * link, and that the entry point and levels match the header. The set can be searched afterwards.
     *
     * @throws IllegalArgumentException if the loaded graph is inconsistent
     */
    public void finishRestore() {
        lock.writeLock().lock();
        try {
            if (!restoring) {
                throw new IllegalStateException("no restore in progress");
            }
            if (slots != expectedNodes || count != expectedNodes) {
                throw new IllegalArgumentException("the vector dump is incomplete");
            }
            int highest = -1;
            for (int node = 0; node < slots; node++) {
                int level = levelOf(node);
                highest = Math.max(highest, level);
                for (int l = 0; l <= level; l++) {
                    int[] arr = slabArray(node, l);
                    int base = slabBase(node, l);
                    int degree = arr[base];
                    for (int i = 1; i <= degree; i++) {
                        int other = arr[base + i];
                        if (other < 0 || other >= slots || other == node || levelOf(other) < l) {
                            throw new IllegalArgumentException("inconsistent link in vector dump");
                        }
                    }
                }
            }
            if (slots > 0 && (declaredEntry < 0 || declaredEntry >= slots || highest != declaredMaxLevel
                    || levelOf(declaredEntry) != declaredMaxLevel)) {
                throw new IllegalArgumentException("inconsistent entry point in vector dump");
            }
            entry = slots == 0 ? -1 : declaredEntry;
            maxLevel = slots == 0 ? -1 : declaredMaxLevel;
            restoring = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ================================================================== self-check (tests and diagnostics)

    /**
     * Checks every structural rule and returns the problems found (empty if the set is sound): the name index, the
     * link limits, that links are reciprocal and only join nodes that reach the level, that no node is cut off from
     * level 0, and that the entry point is the highest node.
     */
    public List<String> validate() {
        lock.readLock().lock();
        try {
            List<String> problems = new ArrayList<>();
            int live = 0;
            int highest = -1;
            for (int p = 0; p < pages; p++) {
                for (int o = 0; o < pageSlots; o++) {
                    String name = names[p][o];
                    if (name == null) {
                        continue;
                    }
                    live++;
                    int node = (p << shift) | o;
                    int level = levels[p][o];
                    highest = Math.max(highest, level);
                    if (indexGet(name) != node) {
                        problems.add("name index does not lead to " + name);
                    }
                    if (level > 0 && upper[p][o] == null) {
                        problems.add(name + " has level " + level + " but no upper links");
                    }
                    for (int l = 0; l <= level; l++) {
                        int[] arr = slabArray(node, l);
                        int base = slabBase(node, l);
                        int degree = arr[base];
                        if (degree < 0 || degree > capOf(l)) {
                            problems.add(name + " has " + degree + " links on level " + l);
                            continue;
                        }
                        if (l == 0 && degree == 0 && this.count > 1) {
                            problems.add(name + " has no level-0 link");
                        }
                        for (int i = 1; i <= degree; i++) {
                            int other = arr[base + i];
                            if (other < 0 || other >= slots || nameOf(other) == null) {
                                problems.add(name + " links to a missing node " + other + " on level " + l);
                                continue;
                            }
                            if (other == node) {
                                problems.add(name + " links to itself");
                            }
                            if (levelOf(other) < l) {
                                problems.add(name + " links to " + nameOf(other) + " above its level");
                                continue;
                            }
                            if (!hasLink(other, l, node)) {
                                problems.add(name + " -> " + nameOf(other) + " on level " + l + " is not reciprocal");
                            }
                            for (int j = 1; j < i; j++) {
                                if (arr[base + j] == other) {
                                    problems.add(name + " links twice to " + nameOf(other));
                                }
                            }
                        }
                    }
                }
            }
            if (live != this.count) {
                problems.add("count is " + this.count + " but " + live + " elements are live");
            }
            if (tableCount != live) {
                problems.add("name index holds " + tableCount + " entries for " + live + " elements");
            }
            if (live == 0 && (entry != -1 || maxLevel != -1)) {
                problems.add("empty set has an entry point");
            }
            if (live > 0) {
                if (entry < 0 || nameOf(entry) == null) {
                    problems.add("entry point is not a live node");
                } else if (levelOf(entry) != maxLevel || maxLevel != highest) {
                    problems.add("entry level " + levelOf(entry) + ", maxLevel " + maxLevel + ", highest " + highest);
                }
            }
            for (int i = 0; i < freeCount; i++) {
                if (nameOf(free[i]) != null) {
                    problems.add("free slot " + free[i] + " is in use");
                }
            }
            return problems;
        } finally {
            lock.readLock().unlock();
        }
    }
}
