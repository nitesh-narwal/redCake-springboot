package me.niteshh.redcake.vector;

import java.util.Arrays;

/**
 * Binary min-heap of {@code long} keys, the only priority queue the graph search uses. A key packs a similarity
 * score and a node id (see {@link VectorSet}), so the heap needs no objects: no boxing, no entry records, nothing
 * for the garbage collector to do per visited node. Not thread-safe; each search owns its heaps.
 */
final class LongHeap {

    private long[] keys = new long[64];
    private int size;

    int size() {
        return size;
    }

    void clear() {
        size = 0;
    }

    /** @return the smallest key; undefined if the heap is empty */
    long peek() {
        return keys[0];
    }

    void push(long key) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
        }
        int child = size++;
        while (child > 0) {
            int parent = (child - 1) >>> 1;
            long parentKey = keys[parent];
            if (parentKey <= key) {
                break;
            }
            keys[child] = parentKey;
            child = parent;
        }
        keys[child] = key;
    }

    /** Removes and returns the smallest key; undefined if the heap is empty. */
    long pop() {
        long top = keys[0];
        long last = keys[--size];
        if (size > 0) {
            int parent = 0;
            int half = size >>> 1;
            while (parent < half) {
                int child = 2 * parent + 1;
                long childKey = keys[child];
                int right = child + 1;
                if (right < size && keys[right] < childKey) {
                    child = right;
                    childKey = keys[right];
                }
                if (last <= childKey) {
                    break;
                }
                keys[parent] = childKey;
                parent = child;
            }
            keys[parent] = last;
        }
        return top;
    }
}
