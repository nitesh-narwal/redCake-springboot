package me.niteshh.redcake.vector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static me.niteshh.redcake.vector.VectorTestData.build;
import static me.niteshh.redcake.vector.VectorTestData.clustered;
import static me.niteshh.redcake.vector.VectorTestData.name;
import static org.junit.jupiter.api.Assertions.*;

class VectorSetTest {

    private static void assertSound(VectorSet set) {
        List<String> problems = set.validate();
        assertTrue(problems.isEmpty(), () -> "graph is broken: " + problems.stream().limit(5).toList());
    }

    // ------------------------------------------------------------------------------ basics

    @Test
    void anEmptySetHasNoHitsAndNoStructure() {
        VectorSet set = new VectorSet(8, Quantization.Q8, 16, 200);
        assertEquals(0, set.size());
        assertTrue(set.search(set.encode(new float[]{1, 0, 0, 0, 0, 0, 0, 0}), 5, 100, -1, false).isEmpty());
        assertEquals(List.of(), set.randomElements(3, true));
        assertSound(set);
    }

    @Test
    void addingTheSameNameReplacesTheVectorAndKeepsTheAttribute() {
        VectorSet set = new VectorSet(4, Quantization.NOQUANT, 16, 200);
        assertTrue(set.add("a", set.encode(new float[]{1, 0, 0, 0}), "{\"x\":1}"));
        assertTrue(set.add("b", set.encode(new float[]{0, 1, 0, 0}), null));
        assertFalse(set.add("a", set.encode(new float[]{0, 0, 1, 0}), null), "second add is an update");

        assertEquals(2, set.size());
        assertEquals("{\"x\":1}", set.attribute("a"));
        List<VectorSet.Hit> hits = set.search(set.encode(new float[]{0, 0, 5, 0}), 1, 100, -1, true);
        assertEquals("a", hits.getFirst().element());
        assertEquals(1.0, hits.getFirst().similarity(), 1e-6);
        assertSound(set);
    }

    @Test
    void attributesCanBeSetChangedAndCleared() {
        VectorSet set = new VectorSet(4, Quantization.Q8, 16, 200);
        set.add("a", set.encode(new float[]{1, 2, 3, 4}), null);
        assertNull(set.attribute("a"));
        assertTrue(set.setAttribute("a", "{\"k\":\"v\"}"));
        assertEquals("{\"k\":\"v\"}", set.attribute("a"));
        assertTrue(set.setAttribute("a", ""));
        assertNull(set.attribute("a"));
        assertFalse(set.setAttribute("missing", "x"));
        assertEquals(1, set.info().size());
        assertEquals(0, set.info().attributes());
    }

    @Test
    void invalidVectorsAreRejected() {
        VectorSet set = new VectorSet(3, Quantization.Q8, 16, 200);
        assertThrows(IllegalArgumentException.class, () -> set.encode(new float[]{1, 2}), "wrong length");
        assertThrows(IllegalArgumentException.class, () -> set.encode(new float[]{0, 0, 0}), "zero vector");
        assertThrows(IllegalArgumentException.class, () -> set.encode(new float[]{1, Float.NaN, 0}), "NaN");
        assertThrows(IllegalArgumentException.class, () -> set.encode(new float[]{1, Float.POSITIVE_INFINITY, 0}), "inf");
    }

    @Test
    void parametersAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new VectorSet(0, Quantization.Q8, 16, 200));
        assertThrows(IllegalArgumentException.class, () -> new VectorSet(VectorSet.MAX_DIM + 1, Quantization.Q8, 16, 200));
        assertThrows(IllegalArgumentException.class, () -> new VectorSet(8, Quantization.Q8, 2, 200));
        assertThrows(IllegalArgumentException.class, () -> new VectorSet(8, Quantization.Q8, 16, 0));
    }

    @Test
    void normsAndStoredVectorsComeBack() {
        VectorSet set = new VectorSet(3, Quantization.NOQUANT, 16, 200);
        set.add("v", set.encode(new float[]{3, 0, 4}), null);
        assertEquals(5f, set.norm("v"), 1e-6);
        float[] stored = set.embedding("v");
        assertArrayEquals(new float[]{0.6f, 0f, 0.8f}, stored, 1e-6f);
        assertNull(set.embedding("nope"));
        assertTrue(Float.isNaN(set.norm("nope")));
    }

    @Test
    void int8StorageReconstructsTheDirectionClosely() {
        VectorSet set = new VectorSet(64, Quantization.Q8, 16, 200);
        float[] original = clustered(1, 64, 1, 1.0, 5)[0];
        set.add("v", set.encode(original), null);
        float[] stored = set.embedding("v");
        double length = 0;
        for (float x : stored) {
            length += x * x;
        }
        assertEquals(1.0, Math.sqrt(length), 1e-4, "the reconstruction has unit length");
        assertTrue(VectorTestData.cosine(original, stored) > 0.9995, "quantization error stays tiny");
    }

    // ------------------------------------------------------------------------------ search quality

    @Test
    void smallSetsAreSearchedExactly() {
        float[][] vectors = clustered(150, 32, 5, 0.3, 1);
        VectorSet set = build(vectors, Quantization.NOQUANT, 16, 200);
        Random random = new Random(2);
        for (int q = 0; q < 20; q++) {
            float[] query = vectors[random.nextInt(vectors.length)];
            List<VectorSet.Hit> hits = set.search(set.encode(query), 10, 100, -1, false);
            assertEquals(VectorTestData.trueTop(vectors, query, 10),
                    hits.stream().map(VectorSet.Hit::element).toList());
        }
    }

    private static void assertRecall(Quantization quantization, double minimum, int ef) {
        float[][] all = clustered(3100, 48, 25, 0.35, 3);
        float[][] vectors = java.util.Arrays.copyOfRange(all, 0, 3000);
        float[][] queries = java.util.Arrays.copyOfRange(all, 3000, 3100);
        VectorSet set = build(vectors, quantization, 16, 200);
        assertSound(set);
        double total = 0;
        for (float[] query : queries) {
            List<VectorSet.Hit> hits = set.search(set.encode(query), 10, ef, -1, false);
            total += VectorTestData.recall(hits, VectorTestData.trueTop(vectors, query, 10));
        }
        double recall = total / queries.length;
        assertTrue(recall >= minimum, quantization + " recall@10 was " + recall);
    }

    @Test
    void graphSearchFindsTheTrueNeighboursWithFloats() {
        assertRecall(Quantization.NOQUANT, 0.97, 100);
    }

    @Test
    void graphSearchFindsTheTrueNeighboursWithInt8() {
        assertRecall(Quantization.Q8, 0.95, 100);
    }

    @Test
    void aWiderSearchIsMoreAccurate() {
        float[][] vectors = clustered(3000, 48, 25, 0.5, 6);
        VectorSet set = build(vectors, Quantization.Q8, 8, 64);
        float[][] queries = clustered(100, 48, 25, 0.5, 7);
        double narrow = 0;
        double wide = 0;
        for (float[] query : queries) {
            List<String> truth = VectorTestData.trueTop(vectors, query, 10);
            narrow += VectorTestData.recall(set.search(set.encode(query), 10, 10, -1, false), truth);
            wide += VectorTestData.recall(set.search(set.encode(query), 10, 400, -1, false), truth);
        }
        assertTrue(wide > narrow, "ef 400 (" + wide + ") should beat ef 10 (" + narrow + ")");
        assertTrue(wide / queries.length > 0.95);
    }

    @Test
    void binaryQuantizationKeepsClustersTogetherAndTheGraphMatchesAnExactScan() {
        // One bit per dimension cannot rank neighbours inside a tight cluster (the differences are below the
        // resolution), so the right questions are: do the hits come from the query's cluster, and does the graph
        // find hits as good as an exhaustive scan of the same bits would (ties on Hamming distance are common).
        float[][] all = clustered(1550, 128, 12, 0.3, 8); // same seed: the extra points share the clusters
        float[][] vectors = java.util.Arrays.copyOfRange(all, 0, 1500);
        float[][] queries = java.util.Arrays.copyOfRange(all, 1500, 1550);
        VectorSet set = build(vectors, Quantization.BIN, 16, 200);
        assertSound(set);
        double sameCluster = 0;
        double asGoodAsExact = 0;
        for (float[] query : queries) {
            List<VectorSet.Hit> graph = set.search(set.encode(query), 10, 200, -1, false);
            List<VectorSet.Hit> exact = set.search(set.encode(query), 10, 200, -1, true);
            float exactTenth = exact.getLast().similarity();
            asGoodAsExact += graph.stream().filter(h -> h.similarity() >= exactTenth - 1e-6f).count() / 10.0;
            for (VectorSet.Hit hit : graph) {
                int index = Integer.parseInt(hit.element().substring("item:".length()));
                sameCluster += VectorTestData.cosine(query, vectors[index]) / graph.size();
            }
        }
        assertTrue(sameCluster / queries.length > 0.85, "mean true cosine of the hits: " + sameCluster / queries.length);
        assertTrue(asGoodAsExact / queries.length > 0.9, "graph vs exact scan: " + asGoodAsExact / queries.length);
    }

    @Test
    void searchByElementFindsItselfFirstAndMatchesAQueryByItsVector() {
        float[][] vectors = clustered(800, 32, 8, 0.4, 10);
        VectorSet set = build(vectors, Quantization.Q8, 16, 200);
        List<VectorSet.Hit> hits = set.searchLike(name(17), 5, 100, -1, false);
        assertEquals(name(17), hits.getFirst().element());
        assertEquals(1.0, hits.getFirst().similarity(), 1e-3);
        assertNull(set.searchLike("nope", 5, 100, -1, false));
    }

    @Test
    void theMinimumSimilarityDropsWeakHits() {
        VectorSet set = new VectorSet(2, Quantization.NOQUANT, 16, 200);
        set.add("same", set.encode(new float[]{1, 0}), null);
        set.add("near", set.encode(new float[]{1, 0.2f}), null);
        set.add("orthogonal", set.encode(new float[]{0, 1}), null);
        set.add("opposite", set.encode(new float[]{-1, 0}), null);
        List<VectorSet.Hit> all = set.search(set.encode(new float[]{1, 0}), 10, 100, -1, true);
        assertEquals(4, all.size());
        assertEquals("opposite", all.getLast().element());
        List<VectorSet.Hit> close = set.search(set.encode(new float[]{1, 0}), 10, 100, 0.9f, true);
        assertEquals(List.of("same", "near"), close.stream().map(VectorSet.Hit::element).toList());
    }

    // ------------------------------------------------------------------------------ structure under change

    @Test
    void theGraphStaysSoundThroughInsertsUpdatesAndRemoves() {
        Random random = new Random(11);
        float[][] pool = clustered(600, 24, 10, 0.4, 12);
        VectorSet set = new VectorSet(24, Quantization.Q8, 8, 64);
        Set<String> live = new HashSet<>();
        for (int step = 0; step < 4000; step++) {
            int i = random.nextInt(pool.length);
            String element = name(i);
            int action = random.nextInt(10);
            if (action < 6) {
                set.add(element, set.encode(pool[random.nextInt(pool.length)]), null); // insert or update
                live.add(element);
            } else {
                assertEquals(live.remove(element), set.remove(element));
            }
            if (step % 250 == 0) {
                assertSound(set);
                assertEquals(live.size(), set.size());
            }
        }
        assertSound(set);
        assertEquals(live.size(), set.size());
        for (String element : live) {
            assertTrue(set.contains(element));
        }
    }

    @Test
    void removingEverythingLeavesAnEmptyUsableSet() {
        float[][] vectors = clustered(300, 16, 6, 0.4, 13);
        VectorSet set = build(vectors, Quantization.Q8, 8, 64);
        for (int i = 0; i < vectors.length; i++) {
            assertTrue(set.remove(name(i)));
            if (i % 40 == 0) {
                assertSound(set);
            }
        }
        assertEquals(0, set.size());
        assertSound(set);
        assertFalse(set.remove(name(0)));
        set.add("again", set.encode(vectors[0]), null);
        assertEquals("again", set.search(set.encode(vectors[0]), 1, 10, -1, false).getFirst().element());
        assertSound(set);
    }

    @Test
    void searchStillWorksAfterHeavyDeletion() {
        float[][] vectors = clustered(2500, 32, 20, 0.4, 14);
        VectorSet set = build(vectors, Quantization.Q8, 16, 200);
        List<float[]> kept = new ArrayList<>();
        List<String> keptNames = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            if (i % 5 < 3) { // delete 60 %
                set.remove(name(i));
            } else {
                kept.add(vectors[i]);
                keptNames.add(name(i));
            }
        }
        assertSound(set);
        double total = 0;
        float[][] queries = clustered(60, 32, 20, 0.4, 15);
        for (float[] query : queries) {
            List<VectorSet.Hit> hits = set.search(set.encode(query), 10, 100, -1, false);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                order.add(i);
            }
            order.sort(java.util.Comparator.comparingDouble((Integer i) -> -VectorTestData.cosine(kept.get(i), query)));
            List<String> truth = order.stream().limit(10).map(keptNames::get).toList();
            total += VectorTestData.recall(hits, truth);
        }
        assertTrue(total / queries.length > 0.93, "recall after deleting 60 %: " + total / queries.length);
    }

    @Test
    void freedSlotsAreReusedSoMemoryDoesNotGrowWithChurn() {
        float[][] vectors = clustered(400, 16, 5, 0.4, 16);
        VectorSet set = build(vectors, Quantization.Q8, 8, 64);
        long before = set.estimatedBytes();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 200; i++) {
                set.remove(name(i));
            }
            for (int i = 0; i < 200; i++) {
                set.add(name(i), set.encode(vectors[i]), null);
            }
        }
        assertEquals(400, set.size());
        assertTrue(set.estimatedBytes() <= before * 1.05, "estimate " + set.estimatedBytes() + " vs " + before);
        assertSound(set);
    }

    @Test
    void theSameCommandsBuildTheSameGraph() {
        float[][] vectors = clustered(500, 24, 8, 0.4, 17);
        VectorSet first = build(vectors, Quantization.Q8, 8, 64);
        VectorSet second = build(vectors, Quantization.Q8, 8, 64);
        for (int i = 0; i < vectors.length; i += 7) {
            assertEquals(first.links(name(i)).toString(), second.links(name(i)).toString());
        }
    }

    // ------------------------------------------------------------------------------ planning ahead

    @Test
    void insertingWithAPreparedPlanBuildsTheSameGraphAsInsertingDirectly() {
        float[][] vectors = clustered(600, 24, 8, 0.4, 30);
        VectorSet direct = new VectorSet(24, Quantization.Q8, 8, 64);
        VectorSet planned = new VectorSet(24, Quantization.Q8, 8, 64);
        for (int i = 0; i < vectors.length; i++) {
            direct.add(name(i), direct.encode(vectors[i]), null);
            VectorSet.Encoded encoded = planned.encode(vectors[i]);
            VectorSet.Plan plan = planned.plan(name(i), encoded, 0);
            assertEquals(i == 0, plan == null, "the first element has nothing to plan");
            planned.add(name(i), encoded, null, 0, plan);
        }
        assertSound(planned);
        for (int i = 0; i < vectors.length; i += 11) {
            assertEquals(direct.links(name(i)).toString(), planned.links(name(i)).toString());
        }
    }

    @Test
    void aPlanThatNoLongerFitsIsIgnoredAndTheInsertionStillSucceeds() {
        float[][] vectors = clustered(300, 16, 5, 0.4, 31);
        VectorSet set = build(java.util.Arrays.copyOf(vectors, 200), Quantization.Q8, 8, 64);
        VectorSet.Encoded encoded = set.encode(vectors[250]);
        VectorSet.Plan stale = set.plan("late", encoded, 0);
        assertNotNull(stale);
        set.add("interloper", set.encode(vectors[251]), null); // the set changes after the plan was made
        set.remove(name(3));
        assertTrue(set.add("late", encoded, null, 0, stale));
        assertEquals(201, set.size(), "200 + interloper - one removed + late");
        assertSound(set);
        assertEquals("late", set.search(encoded, 1, 100, -1, false).getFirst().element());

        // a plan made for an element that has since been inserted by someone else
        VectorSet.Plan duplicate = set.plan("twice", set.encode(vectors[260]), 0);
        set.add("twice", set.encode(vectors[261]), null);
        assertFalse(set.add("twice", set.encode(vectors[260]), null, 0, duplicate), "now an update");
        assertSound(set);
    }

    @Test
    void anInsertionByAnotherWriterDoesNotSpoilAPlanButARemovalDoes() {
        float[][] vectors = clustered(120, 8, 4, 0.5, 33);
        VectorSet set = build(java.util.Arrays.copyOf(vectors, 100), Quantization.NOQUANT, 8, 64);

        float[] x = vectors[110];
        float[] nearX = x.clone();
        nearX[0] += 0.001f;
        VectorSet.Plan plan = set.plan("x", set.encode(x), 0);
        set.add("nearX", set.encode(nearX), null); // somebody inserts a near duplicate in the meantime
        set.add("x", set.encode(x), null, 0, plan);
        List<String> linkedToX = set.links("x").getFirst().stream().map(VectorSet.Link::element).toList();
        assertFalse(linkedToX.contains("nearX"), "the plan was used as it was made, before nearX existed: " + linkedToX);
        assertSound(set);

        float[] z = vectors[111];
        float[] nearZ = z.clone();
        nearZ[0] += 0.001f;
        VectorSet.Plan stale = set.plan("z", set.encode(z), 0);
        set.add("nearZ", set.encode(nearZ), null);
        set.remove(name(5)); // a removal makes plans unsafe (a neighbour's slot could be reused)
        set.add("z", set.encode(z), null, 0, stale);
        List<String> linkedToZ = set.links("z").getFirst().stream().map(VectorSet.Link::element).toList();
        assertTrue(linkedToZ.contains("nearZ"), "the neighbours were searched again and found nearZ: " + linkedToZ);
        assertSound(set);
    }

    @Test
    void thereIsNothingToPlanForAnEmptySetOrAnExistingElement() {
        VectorSet set = new VectorSet(4, Quantization.NOQUANT, 8, 64);
        assertNull(set.plan("a", set.encode(new float[]{1, 0, 0, 0}), 0));
        set.add("a", set.encode(new float[]{1, 0, 0, 0}), null);
        assertNull(set.plan("a", set.encode(new float[]{0, 1, 0, 0}), 0), "an update has to remove the old node first");
        assertNotNull(set.plan("b", set.encode(new float[]{0, 1, 0, 0}), 0));
        assertThrows(IllegalArgumentException.class, () -> set.plan("b", set.encode(new float[]{0, 1, 0, 0}), VectorSet.MAX_EF + 1));
    }

    @Test
    void severalThreadsCanPlanAndInsertAtTheSameTime() throws Exception {
        float[][] vectors = clustered(1200, 24, 8, 0.4, 32);
        VectorSet set = build(java.util.Arrays.copyOf(vectors, 100), Quantization.Q8, 8, 64);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int from = 100 + t * 275;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = from; i < from + 275; i++) {
                        VectorSet.Encoded encoded = set.encode(vectors[i]);
                        VectorSet.Plan plan = set.plan(name(i), encoded, 0); // concurrent writers make most plans stale
                        set.add(name(i), encoded, null, 0, plan);
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals(1200, set.size());
        assertSound(set);
    }

    // ------------------------------------------------------------------------------ introspection

    @Test
    void infoLinksAndRandomElementsDescribeTheSet() {
        float[][] vectors = clustered(300, 16, 5, 0.4, 18);
        VectorSet set = build(vectors, Quantization.Q8, 8, 64);
        VectorSet.Info info = set.info();
        assertEquals(300, info.size());
        assertEquals(16, info.dim());
        assertEquals(Quantization.Q8, info.quantization());
        assertTrue(info.bytes() > 300 * 16);

        List<List<VectorSet.Link>> links = set.links(name(3));
        assertFalse(links.getFirst().isEmpty());
        assertNull(set.links("nope"));

        List<String> some = set.randomElements(10, true);
        assertEquals(10, some.size());
        assertEquals(10, new HashSet<>(some).size(), "distinct");
        assertEquals(50, set.randomElements(50, false).size());
        assertEquals(300, new HashSet<>(set.randomElements(1000, true)).size(), "asking for more returns the whole set");
    }

    @Test
    void rawFormPerQuantization() {
        for (Quantization quantization : Quantization.values()) {
            VectorSet set = new VectorSet(10, quantization, 16, 200);
            set.add("v", set.encode(new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}), null);
            VectorSet.Raw raw = set.raw("v");
            assertEquals(quantization, raw.quantization());
            assertEquals(Math.sqrt(385), raw.norm(), 1e-3);
            int expected = switch (quantization) {
                case Q8 -> 10;
                case NOQUANT -> 40;
                case BIN -> 8;
            };
            assertEquals(expected, raw.codes().length);
        }
    }

    // ------------------------------------------------------------------------------ dump and restore

    private static VectorSet restore(VectorSet.Dump dump) {
        VectorSet copy = VectorSet.beginRestore(dump.header());
        for (VectorSet.Chunk chunk : dump.chunks()) {
            copy.restoreChunk(chunk.firstId(), chunk.data());
        }
        copy.finishRestore();
        return copy;
    }

    @Test
    void aDumpRestoresToAnIdenticalSetForEveryQuantization() {
        for (Quantization quantization : Quantization.values()) {
            float[][] vectors = clustered(700, 20, 7, 0.4, 19);
            VectorSet original = build(vectors, quantization, 8, 64);
            original.setAttribute(name(5), "{\"genre\":\"x\"}");
            for (int i = 0; i < 700; i += 4) {
                original.remove(name(i)); // leaves holes: the dump must renumber densely
            }
            VectorSet copy = restore(original.dump());

            assertEquals(original.size(), copy.size());
            assertSound(copy);
            assertEquals("{\"genre\":\"x\"}", copy.attribute(name(5)));
            for (int i = 1; i < 700; i += 9) {
                if (i % 4 == 0) {
                    continue;
                }
                assertEquals(original.links(name(i)).toString(), copy.links(name(i)).toString(), quantization + " " + i);
                assertArrayEquals(original.embedding(name(i)), copy.embedding(name(i)));
                assertEquals(original.norm(name(i)), copy.norm(name(i)));
            }
            float[] query = vectors[1];
            assertEquals(original.search(original.encode(query), 10, 100, -1, false),
                    copy.search(copy.encode(query), 10, 100, -1, false));
            // the restored set keeps working as a normal one
            copy.add("fresh", copy.encode(vectors[2]), null);
            copy.remove(name(1));
            assertSound(copy);
        }
    }

    @Test
    void aDumpIsSplitIntoSeveralChunksForBigSets() {
        float[][] vectors = clustered(2500, 128, 10, 0.4, 20);
        VectorSet set = build(vectors, Quantization.NOQUANT, 16, 100);
        VectorSet.Dump dump = set.dump();
        assertTrue(dump.chunks().size() > 1, "chunks: " + dump.chunks().size());
        assertTrue(dump.chunks().stream().allMatch(c -> c.data().length < 1_000_000), "each chunk fits a command argument");
        assertSound(restore(dump));
    }

    @Test
    void anEmptySetDumpsAndRestores() {
        VectorSet empty = new VectorSet(8, Quantization.Q8, 16, 200);
        VectorSet copy = restore(empty.dump());
        assertEquals(0, copy.size());
        assertSound(copy);
    }

    @Test
    void aSetCannotBeUsedWhileItIsBeingRestored() {
        VectorSet source = build(clustered(50, 8, 2, 0.4, 21), Quantization.Q8, 8, 32);
        VectorSet.Dump dump = source.dump();
        VectorSet target = VectorSet.beginRestore(dump.header());
        assertTrue(target.isRestoring());
        assertThrows(IllegalStateException.class,
                () -> target.search(target.encode(new float[]{1, 0, 0, 0, 0, 0, 0, 0}), 1, 1, -1, false));
    }

    @Test
    void corruptDumpsAreRejectedWithoutHarmingTheJvm() {
        VectorSet source = build(clustered(200, 16, 4, 0.4, 22), Quantization.Q8, 8, 32);
        VectorSet.Dump good = source.dump();
        byte[] header = good.header();

        assertThrows(IllegalArgumentException.class, () -> VectorSet.beginRestore(new byte[3]));
        byte[] badMagic = header.clone();
        badMagic[0] ^= 0x55;
        assertThrows(IllegalArgumentException.class, () -> VectorSet.beginRestore(badMagic));

        // truncated chunk
        VectorSet.Chunk first = good.chunks().getFirst();
        VectorSet truncated = VectorSet.beginRestore(header);
        assertThrows(IllegalArgumentException.class,
                () -> truncated.restoreChunk(0, java.util.Arrays.copyOf(first.data(), first.data().length - 10)));

        // chunks out of order
        VectorSet outOfOrder = VectorSet.beginRestore(header);
        assertThrows(IllegalArgumentException.class, () -> outOfOrder.restoreChunk(5, first.data()));

        // incomplete
        VectorSet incomplete = VectorSet.beginRestore(header);
        assertThrows(IllegalArgumentException.class, incomplete::finishRestore);

        // random bit flips never crash: they are either rejected or yield a set whose links were checked
        Random random = new Random(1);
        for (int round = 0; round < 60; round++) {
            byte[] damaged = first.data().clone();
            for (int flips = 0; flips < 3; flips++) {
                damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            }
            VectorSet target = VectorSet.beginRestore(header);
            try {
                target.restoreChunk(0, damaged);
                for (int c = 1; c < good.chunks().size(); c++) {
                    target.restoreChunk(good.chunks().get(c).firstId(), good.chunks().get(c).data());
                }
                target.finishRestore();
                // accepted: it must at least be safe to search
                float[] probe = new float[16];
                probe[0] = 1;
                target.search(target.encode(probe), 1, 10, -1, true);
            } catch (IllegalArgumentException expected) {
                // rejected cleanly
            } catch (IllegalStateException expected) {
                // zero query vector rejected as IllegalArgument above; anything else here is a failure
                fail("unexpected state error " + expected);
            }
        }
    }

    // ------------------------------------------------------------------------------ concurrency

    @Test
    void searchesRunWhileTheSetIsBeingChanged() throws Exception {
        float[][] vectors = clustered(1200, 32, 10, 0.4, 23);
        VectorSet set = build(java.util.Arrays.copyOf(vectors, 600), Quantization.Q8, 8, 64);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 600; i < 1200; i++) {
                    set.add(name(i), set.encode(vectors[i]), null);
                    if (i % 3 == 0) {
                        set.remove(name(i - 400));
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            Thread reader = new Thread(() -> {
                try {
                    Random random = new Random();
                    while (writer.isAlive()) {
                        float[] query = vectors[random.nextInt(vectors.length)];
                        List<VectorSet.Hit> hits = set.search(set.encode(query), 10, 50, -1, false);
                        assertFalse(hits.isEmpty());
                        for (int i = 1; i < hits.size(); i++) {
                            assertTrue(hits.get(i - 1).similarity() >= hits.get(i).similarity(), "sorted");
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            readers.add(reader);
        }
        writer.start();
        readers.forEach(Thread::start);
        writer.join();
        for (Thread reader : readers) {
            reader.join();
        }
        assertNull(failure.get(), () -> "worker failed: " + failure.get());
        assertSound(set);
    }
}
