package me.niteshh.redcake.command.commands.types;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import me.niteshh.redcake.command.CommandException;
import me.niteshh.redcake.command.CommandProvider;
import me.niteshh.redcake.command.CommandSupport;
import me.niteshh.redcake.command.PreparedWork;
import me.niteshh.redcake.command.RedCakeCommand;
import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.resp.ErrorValue;
import me.niteshh.redcake.resp.IntegerValue;
import me.niteshh.redcake.resp.NullValue;
import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import me.niteshh.redcake.store.KeyValueStore;
import me.niteshh.redcake.store.ValueType;
import me.niteshh.redcake.store.ZSet;
import me.niteshh.redcake.vector.Kernels;
import me.niteshh.redcake.vector.Quantization;
import me.niteshh.redcake.vector.VectorSet;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static me.niteshh.redcake.command.LambdaCommand.read;
import static me.niteshh.redcake.command.LambdaCommand.shrink;
import static me.niteshh.redcake.command.LambdaCommand.write;

/**
 * Vector sets, modelled on the Redis 8 command family: {@code VADD VSIM VREM VCARD VDIM VEMB VINFO VGETATTR
 * VSETATTR VISMEMBER VRANDMEMBER VLINKS}, plus the internal {@code VRESTORE} that carries a binary dump through the
 * append-only file and the replication stream. The data structure is {@link VectorSet}.
 *
 * <p>Differences from Redis 8: {@code REDUCE} (random projection) and {@code FILTER} (attribute expressions) are
 * not implemented and answer with an error; {@code CAS} and {@code NOTHREAD} are accepted and ignored (RedCake
 * already runs searches on the caller's thread and inserts under its single write lock).
 *
 * <p>Searches ({@code VSIM}) never run inside the store's per-key lock: they take the live set with
 * {@link KeyValueStore#peek} and rely on the set's own read/write lock, so many searches proceed in parallel.
 */
@Component
@RequiredArgsConstructor
public class VectorCommands implements CommandProvider {

    static final int DEFAULT_COUNT = 10;
    static final int DEFAULT_SEARCH_EF = 100;
    /** Most results one {@code VSIM} may ask for. */
    static final int MAX_COUNT = 10_000;

    private final KeyValueStore store;

    /** Chooses (and logs) the distance kernel at startup, so the operator sees it before the first vector arrives. */
    @PostConstruct
    void announceKernel() {
        Kernels.active();
    }

    @Override
    public List<RedCakeCommand> commands() {
        return List.of(
                new Vadd(),
                read("VSIM", 3, -1, this::vsim),
                shrink("VREM", 2, 2, this::vrem),
                read("VCARD", 1, 1, a -> {
                    VectorSet set = set(a.get(0));
                    return new IntegerValue(set == null ? 0 : set.size());
                }),
                read("VDIM", 1, 1, a -> {
                    VectorSet set = set(a.get(0));
                    if (set == null) {
                        return new ErrorValue("key does not exist");
                    }
                    return new IntegerValue(set.dim());
                }),
                read("VEMB", 2, 3, this::vemb),
                read("VINFO", 1, 1, this::vinfo),
                read("VGETATTR", 2, 2, a -> {
                    VectorSet set = set(a.get(0));
                    String attribute = set == null ? null : set.attribute(a.get(1));
                    return attribute == null ? new NullValue() : new BulkString(attribute);
                }),
                write("VSETATTR", 3, 3, this::vsetattr),
                read("VISMEMBER", 2, 2, a -> {
                    VectorSet set = set(a.get(0));
                    return new IntegerValue(set != null && set.contains(a.get(1)) ? 1 : 0);
                }),
                read("VRANDMEMBER", 1, 2, this::vrandmember),
                read("VLINKS", 2, 3, this::vlinks),
                new Vrestore()
        );
    }

    // ------------------------------------------------------------------ helpers

    /** The live vector set stored at {@code key}, or {@code null}. Throws WRONGTYPE for another type. */
    private VectorSet set(String key) {
        return (VectorSet) store.peek(key, ValueType.VECTORSET);
    }

    private static CommandException error(String message) {
        return new CommandException(message);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static int intOption(List<String> a, int i, int min, int max, String name) {
        if (i + 1 >= a.size()) {
            throw new CommandException(CommandSupport.SYNTAX_ERROR);
        }
        long value = CommandSupport.parseLong(a.get(i + 1));
        if (value < min || value > max) {
            throw error(name + " must be between " + min + " and " + max);
        }
        return (int) value;
    }

    /** A parsed vector argument and the index of the first argument after it. */
    private record Parsed(float[] vector, int next) {
    }

    /** Reads {@code FP32 <blob>} or {@code VALUES <n> <v1> ... <vn>} starting at {@code a[i]}. */
    private static Parsed parseVector(List<String> a, int i) {
        if (i + 1 >= a.size()) {
            throw new CommandException(CommandSupport.SYNTAX_ERROR);
        }
        String kind = a.get(i).toUpperCase(Locale.ROOT);
        if ("FP32".equals(kind)) {
            return new Parsed(floatsOfBlob(a.get(i + 1)), i + 2);
        }
        if ("VALUES".equals(kind)) {
            long n = CommandSupport.parseLong(a.get(i + 1));
            if (n < 1 || n > VectorSet.MAX_DIM) {
                throw error("invalid vector dimension: must be between 1 and " + VectorSet.MAX_DIM);
            }
            if (i + 2 + n > a.size()) {
                throw new CommandException(CommandSupport.SYNTAX_ERROR);
            }
            float[] vector = new float[(int) n];
            for (int k = 0; k < n; k++) {
                vector[k] = parseComponent(a.get(i + 2 + k));
            }
            return new Parsed(vector, i + 2 + (int) n);
        }
        throw new CommandException(CommandSupport.SYNTAX_ERROR);
    }

    /** {@code FP32} blob: little-endian IEEE floats, four bytes per dimension. */
    private static float[] floatsOfBlob(String blob) {
        byte[] raw = bytes(blob);
        if (raw.length == 0 || raw.length % 4 != 0 || raw.length / 4 > VectorSet.MAX_DIM) {
            throw error("invalid FP32 vector: the blob must hold 1 to " + VectorSet.MAX_DIM + " floats of 4 bytes");
        }
        float[] out = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    /** A decimal number only: no hex, no type suffix ({@code 1f}), no words - stricter than {@link Float#parseFloat}. */
    private static float parseComponent(String text) {
        boolean ok = !text.isEmpty() && text.length() <= 40;
        for (int i = 0; ok && i < text.length(); i++) {
            char c = text.charAt(i);
            ok = (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
        }
        if (ok) {
            try {
                return Float.parseFloat(text);
            } catch (NumberFormatException e) {
                ok = false;
            }
        }
        throw error("invalid vector component '" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "'");
    }

    private static String blobOfFloats(float[] vector) {
        byte[] raw = new byte[4 * vector.length];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(vector);
        return text(raw);
    }

    private static void checkElement(String element) {
        if (element.length() > VectorSet.MAX_ELEMENT_BYTES) {
            throw error("element name is longer than " + VectorSet.MAX_ELEMENT_BYTES + " bytes");
        }
    }

    private static String formatFloat(float value) {
        return value == Math.rint(value) && Math.abs(value) < 1e7f ? Long.toString((long) value) : Float.toString(value);
    }

    /** Redis reports similarity in [0, 1]: 1 identical, 0.5 unrelated, 0 opposite. */
    private static String score(float cosine) {
        double score = Math.max(0.0, Math.min(1.0, (cosine + 1.0) / 2.0));
        return ZSet.formatScore(score);
    }

    // ------------------------------------------------------------------ VADD

    /** A parsed {@code VADD}; {@code quantization}, {@code ef} and {@code m} are 0/null when not given. */
    private record VaddRequest(String key, float[] vector, String element, Quantization quantization, int ef, int m,
                               String attribute) {
    }

    /**
     * What {@link Vadd#prepare} found out before the write lock was taken: the set it looked at, the vector
     * converted to that set's format, and the neighbours already searched for it. {@code execute} uses it only if it
     * still describes the same command and the same set.
     */
    private record PreparedAdd(VectorSet set, String key, String element, float[] vector,
                               VectorSet.Encoded encoded, VectorSet.Plan plan) {
    }

    /** {@code VADD key [REDUCE dim] (FP32 blob | VALUES n v...) element [CAS] [NOQUANT|Q8|BIN] [EF n] [SETATTR json] [M n]}. */
    private final class Vadd implements RedCakeCommand {

        @Override
        public String name() {
            return "VADD";
        }

        @Override
        public boolean isWrite() {
            return true;
        }

        /** @throws CommandException for anything that is not a valid VADD */
        private VaddRequest parse(List<String> a) {
            if (a.size() < 4) {
                throw new CommandException(CommandSupport.wrongArity("vadd"));
            }
            String key = a.get(0);
            if ("REDUCE".equalsIgnoreCase(a.get(1))) {
                throw error("REDUCE is not supported");
            }
            Parsed parsed = parseVector(a, 1);
            int i = parsed.next();
            if (i >= a.size()) {
                throw new CommandException(CommandSupport.SYNTAX_ERROR);
            }
            String element = a.get(i++);
            checkElement(element);

            Quantization quantization = null;
            int ef = 0;
            int m = 0;
            String attribute = null;
            while (i < a.size()) {
                String option = a.get(i).toUpperCase(Locale.ROOT);
                switch (option) {
                    case "CAS" -> i++;
                    case "NOQUANT", "Q8", "BIN" -> {
                        Quantization chosen = Quantization.fromOption(option);
                        if (quantization != null && quantization != chosen) {
                            throw new CommandException(CommandSupport.SYNTAX_ERROR);
                        }
                        quantization = chosen;
                        i++;
                    }
                    case "EF" -> {
                        ef = intOption(a, i, 1, VectorSet.MAX_EF, "EF");
                        i += 2;
                    }
                    case "M" -> {
                        m = intOption(a, i, VectorSet.MIN_M, VectorSet.MAX_M, "M");
                        i += 2;
                    }
                    case "SETATTR" -> {
                        if (i + 1 >= a.size()) {
                            throw new CommandException(CommandSupport.SYNTAX_ERROR);
                        }
                        attribute = a.get(i + 1);
                        if (attribute.length() > VectorSet.MAX_ATTRIBUTE_BYTES) {
                            throw error("attributes are longer than " + VectorSet.MAX_ATTRIBUTE_BYTES + " bytes");
                        }
                        i += 2;
                    }
                    default -> throw new CommandException(CommandSupport.SYNTAX_ERROR);
                }
            }
            return new VaddRequest(key, parsed.vector(), element, quantization, ef, m, attribute);
        }

        /**
         * Before the write lock: for an element that is new to an existing set, converts the vector and searches the
         * graph for its neighbours - the read-only two thirds of an insertion - so concurrent connections do that in
         * parallel and the lock only has to link the node in.
         */
        @Override
        public Object prepare(List<String> command) {
            try {
                VaddRequest request = parse(command.subList(1, command.size()));
                VectorSet set = set(request.key());
                if (set == null || set.dim() != request.vector().length) {
                    return null;
                }
                VectorSet.Encoded encoded = set.encode(request.vector());
                VectorSet.Plan plan = set.plan(request.element(), encoded, request.ef());
                return new PreparedAdd(set, request.key(), request.element(), request.vector(), encoded, plan);
            } catch (RuntimeException e) {
                return null; // execute() reports whatever is wrong with the command
            }
        }

        @Override
        public RespValue execute(List<String> a) {
            PreparedAdd taken = PreparedWork.take(PreparedAdd.class);
            VaddRequest request = parse(a);
            final PreparedAdd prepared = taken != null && taken.key().equals(request.key())
                    && taken.element().equals(request.element()) && Arrays.equals(taken.vector(), request.vector())
                    ? taken : null;

            final float[] vector = request.vector();
            try {
                Boolean added = store.<Boolean>mutate(request.key(), ValueType.VECTORSET,
                        () -> new VectorSet(vector.length,
                                request.quantization() == null ? Quantization.Q8 : request.quantization(),
                                request.m() == 0 ? VectorSet.DEFAULT_M : request.m(),
                                VectorSet.DEFAULT_EF_CONSTRUCTION),
                        o -> {
                            VectorSet set = (VectorSet) o;
                            if (request.quantization() != null && request.quantization() != set.quantization()) {
                                throw error("asked quantization mismatch with existing vector set");
                            }
                            if (request.m() != 0 && request.m() != set.m()) {
                                throw error("asked M value mismatch with existing vector set");
                            }
                            if (prepared != null && prepared.set() == set) { // the set the neighbours were searched in
                                return set.add(request.element(), prepared.encoded(), request.attribute(),
                                        request.ef(), prepared.plan());
                            }
                            return set.add(request.element(), set.encode(vector), request.attribute(), request.ef());
                        });
                return new IntegerValue(Boolean.TRUE.equals(added) ? 1 : 0);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new ErrorValue(e.getMessage());
            }
        }

        /**
         * {@code VALUES} become an {@code FP32} blob so that the append-only file and the replication stream carry the
         * exact binary vector (four bytes per dimension instead of a dozen characters) and every node reads
         * identical numbers.
         */
        @Override
        public List<String> normalize(List<String> command, long nowMillis) {
            try {
                if (command.size() < 6 || !"VALUES".equalsIgnoreCase(command.get(2))) {
                    return command;
                }
                long n = Long.parseLong(command.get(3));
                if (n < 1 || n > VectorSet.MAX_DIM || 4 + n >= command.size()) {
                    return command;
                }
                float[] vector = new float[(int) n];
                for (int k = 0; k < n; k++) {
                    vector[k] = parseComponent(command.get(4 + k));
                }
                List<String> out = new ArrayList<>(command.size() - (int) n + 1);
                out.add(command.get(0));
                out.add(command.get(1));
                out.add("FP32");
                out.add(blobOfFloats(vector));
                out.addAll(command.subList(4 + (int) n, command.size()));
                return out;
            } catch (RuntimeException e) {
                return command; // let execute() report the problem
            }
        }
    }

    // ------------------------------------------------------------------ VSIM

    /**
     * {@code VSIM key (ELE element | FP32 blob | VALUES n v...) [WITHSCORES] [WITHATTRIBS] [COUNT n] [EPSILON d]
     * [EF n] [TRUTH] [NOTHREAD]}. Scores are in [0, 1]: (cosine + 1) / 2.
     */
    private RespValue vsim(List<String> a) {
        String key = a.get(0);
        String mode = a.get(1).toUpperCase(Locale.ROOT);
        float[] vector = null;
        String element = null;
        int i;
        switch (mode) {
            case "ELE" -> {
                element = a.get(2);
                i = 3;
            }
            case "FP32", "VALUES" -> {
                Parsed parsed = parseVector(a, 1);
                vector = parsed.vector();
                i = parsed.next();
            }
            default -> {
                return CommandSupport.SYNTAX_ERROR;
            }
        }

        boolean withScores = false;
        boolean withAttribs = false;
        boolean truth = false;
        int count = DEFAULT_COUNT;
        int ef = DEFAULT_SEARCH_EF;
        float minSimilarity = -1f;
        while (i < a.size()) {
            String option = a.get(i).toUpperCase(Locale.ROOT);
            switch (option) {
                case "WITHSCORES" -> {
                    withScores = true;
                    i++;
                }
                case "WITHATTRIBS" -> {
                    withAttribs = true;
                    i++;
                }
                case "TRUTH" -> {
                    truth = true;
                    i++;
                }
                case "NOTHREAD" -> i++;
                case "COUNT" -> {
                    count = intOption(a, i, 1, MAX_COUNT, "COUNT");
                    i += 2;
                }
                case "EF" -> {
                    ef = intOption(a, i, 1, VectorSet.MAX_EF, "EF");
                    i += 2;
                }
                case "EPSILON" -> {
                    if (i + 1 >= a.size()) {
                        return CommandSupport.SYNTAX_ERROR;
                    }
                    double epsilon = CommandSupport.parseDouble(a.get(i + 1));
                    if (epsilon < 0) {
                        return error("EPSILON must not be negative").error();
                    }
                    // the reply score is (cosine + 1) / 2, so "score >= 1 - epsilon" is "cosine >= 1 - 2 * epsilon"
                    minSimilarity = (float) Math.max(-1.0, 1.0 - 2.0 * epsilon);
                    i += 2;
                }
                case "FILTER", "FILTER-EF" -> {
                    return new ErrorValue("FILTER is not supported");
                }
                default -> {
                    return CommandSupport.SYNTAX_ERROR;
                }
            }
        }

        VectorSet set = set(key);
        if (set == null) {
            return ArrayValue.empty();
        }
        List<VectorSet.Hit> hits;
        try {
            if (element != null) {
                hits = set.searchLike(element, count, ef, minSimilarity, truth);
                if (hits == null) {
                    return new ErrorValue("element not found in set");
                }
            } else {
                hits = set.search(set.encode(vector), count, ef, minSimilarity, truth);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new ErrorValue(e.getMessage());
        }

        List<String> attributes = withAttribs ? set.attributes(hits) : null;
        List<RespValue> reply = new ArrayList<>(hits.size() * (1 + (withScores ? 1 : 0) + (withAttribs ? 1 : 0)));
        for (int n = 0; n < hits.size(); n++) {
            VectorSet.Hit hit = hits.get(n);
            reply.add(new BulkString(hit.element()));
            if (withScores) {
                reply.add(new BulkString(score(hit.similarity())));
            }
            if (withAttribs) {
                String attribute = attributes.get(n);
                reply.add(attribute == null ? new NullValue() : new BulkString(attribute));
            }
        }
        return new ArrayValue(reply);
    }

    // ------------------------------------------------------------------ the rest

    private RespValue vrem(List<String> a) {
        Boolean removed = store.<Boolean>mutate(a.get(0), ValueType.VECTORSET, null,
                o -> ((VectorSet) o).remove(a.get(1)));
        return new IntegerValue(Boolean.TRUE.equals(removed) ? 1 : 0);
    }

    private RespValue vsetattr(List<String> a) {
        if (a.get(2).length() > VectorSet.MAX_ATTRIBUTE_BYTES) {
            return new ErrorValue("attributes are longer than " + VectorSet.MAX_ATTRIBUTE_BYTES + " bytes");
        }
        Boolean changed = store.<Boolean>mutate(a.get(0), ValueType.VECTORSET, null,
                o -> ((VectorSet) o).setAttribute(a.get(1), a.get(2)));
        return new IntegerValue(Boolean.TRUE.equals(changed) ? 1 : 0);
    }

    /** {@code VEMB key element [RAW]}: the stored (normalized, quantized) vector; {@code RAW} gives the stored bytes. */
    private RespValue vemb(List<String> a) {
        boolean raw = false;
        if (a.size() == 3) {
            if (!"RAW".equalsIgnoreCase(a.get(2))) {
                return CommandSupport.SYNTAX_ERROR;
            }
            raw = true;
        }
        VectorSet set = set(a.get(0));
        if (set == null) {
            return new NullValue();
        }
        try {
            if (raw) {
                VectorSet.Raw stored = set.raw(a.get(1));
                if (stored == null) {
                    return new NullValue();
                }
                return new ArrayValue(List.of(
                        new BulkString(stored.quantization().wireName()),
                        new BulkString(text(stored.codes())),
                        new BulkString(formatFloat(stored.norm())),
                        new BulkString(formatFloat(stored.range()))));
            }
            float[] embedding = set.embedding(a.get(1));
            if (embedding == null) {
                return new NullValue();
            }
            List<RespValue> out = new ArrayList<>(embedding.length);
            for (float value : embedding) {
                out.add(new BulkString(formatFloat(value)));
            }
            return new ArrayValue(out);
        } catch (IllegalStateException e) {
            return new ErrorValue(e.getMessage());
        }
    }

    private RespValue vinfo(List<String> a) {
        VectorSet set = set(a.get(0));
        if (set == null) {
            return new NullValue();
        }
        VectorSet.Info info = set.info();
        List<RespValue> out = new ArrayList<>();
        addField(out, "quant-type", info.quantization().wireName());
        addField(out, "vector-dim", info.dim());
        addField(out, "size", info.size());
        addField(out, "max-level", info.maxLevel());
        addField(out, "attributes-count", info.attributes());
        addField(out, "hnsw-m", info.m());
        addField(out, "hnsw-ef-construction", info.efConstruction());
        addField(out, "memory-bytes", info.bytes());
        addField(out, "free-slots", info.freeSlots());
        return new ArrayValue(out);
    }

    private static void addField(List<RespValue> out, String name, String value) {
        out.add(new BulkString(name));
        out.add(new BulkString(value));
    }

    private static void addField(List<RespValue> out, String name, long value) {
        out.add(new BulkString(name));
        out.add(new IntegerValue(value));
    }

    private RespValue vrandmember(List<String> a) {
        VectorSet set = set(a.get(0));
        if (a.size() == 1) {
            if (set == null) {
                return new NullValue();
            }
            List<String> one = set.randomElements(1, false);
            return one.isEmpty() ? new NullValue() : new BulkString(one.getFirst());
        }
        long wanted = CommandSupport.parseLong(a.get(1));
        if (set == null || wanted == 0) {
            return ArrayValue.empty();
        }
        if (Math.abs(wanted) > MAX_COUNT) {
            return new ErrorValue("count must be between -" + MAX_COUNT + " and " + MAX_COUNT);
        }
        // like SRANDMEMBER: a positive count returns distinct elements, a negative one may repeat them
        return ArrayValue.ofBulkStrings(set.randomElements((int) Math.abs(wanted), wanted > 0));
    }

    /** {@code VLINKS key element [WITHSCORES]}: the graph neighbours on every level, level 0 first. */
    private RespValue vlinks(List<String> a) {
        boolean withScores = false;
        if (a.size() == 3) {
            if (!"WITHSCORES".equalsIgnoreCase(a.get(2))) {
                return CommandSupport.SYNTAX_ERROR;
            }
            withScores = true;
        }
        VectorSet set = set(a.get(0));
        if (set == null) {
            return new NullValue();
        }
        List<List<VectorSet.Link>> levels;
        try {
            levels = set.links(a.get(1));
        } catch (IllegalStateException e) {
            return new ErrorValue(e.getMessage());
        }
        if (levels == null) {
            return new NullValue();
        }
        List<RespValue> out = new ArrayList<>(levels.size());
        for (List<VectorSet.Link> level : levels) {
            List<RespValue> row = new ArrayList<>(level.size() * (withScores ? 2 : 1));
            for (VectorSet.Link link : level) {
                row.add(new BulkString(link.element()));
                if (withScores) {
                    row.add(new BulkString(score(link.similarity())));
                }
            }
            out.add(new ArrayValue(row));
        }
        return new ArrayValue(out);
    }

    // ------------------------------------------------------------------ VRESTORE

    /**
     * {@code VRESTORE key HEADER <bytes> | NODES <first id> <bytes> | END}: loads a binary dump made by
     * {@link VectorSet#dump()}. Not meant for people: it is how a vector set travels through an AOF rewrite and a
     * replica's full sync (see {@code SnapshotEntry}), so it is administrative. An invalid dump deletes the key.
     */
    private final class Vrestore implements RedCakeCommand {

        @Override
        public String name() {
            return "VRESTORE";
        }

        @Override
        public boolean isWrite() {
            return true;
        }

        @Override
        public boolean isAdmin() {
            return true;
        }

        @Override
        public RespValue execute(List<String> a) {
            if (a.size() < 2) {
                return CommandSupport.wrongArity("vrestore");
            }
            String key = a.get(0);
            String step = a.get(1).toUpperCase(Locale.ROOT);
            try {
                switch (step) {
                    case "HEADER" -> {
                        if (a.size() != 3) {
                            return CommandSupport.wrongArity("vrestore");
                        }
                        VectorSet fresh;
                        try {
                            fresh = VectorSet.beginRestore(bytes(a.get(2)));
                        } catch (IllegalArgumentException e) {
                            return new ErrorValue(e.getMessage()); // nothing was touched
                        }
                        store.delete(key); // a restore replaces whatever the key held
                        store.mutate(key, ValueType.VECTORSET, () -> fresh, o -> null);
                    }
                    case "NODES" -> {
                        if (a.size() != 4) {
                            return CommandSupport.wrongArity("vrestore");
                        }
                        long first = CommandSupport.parseLong(a.get(2));
                        byte[] data = bytes(a.get(3));
                        requireRestore(key, o -> {
                            ((VectorSet) o).restoreChunk((int) first, data);
                            return null;
                        });
                    }
                    case "END" -> {
                        if (a.size() != 2) {
                            return CommandSupport.wrongArity("vrestore");
                        }
                        requireRestore(key, o -> {
                            ((VectorSet) o).finishRestore();
                            return null;
                        });
                    }
                    default -> {
                        return CommandSupport.SYNTAX_ERROR;
                    }
                }
                return new SimpleString("OK");
            } catch (IllegalArgumentException | IllegalStateException e) {
                store.delete(key); // never keep a half-loaded or inconsistent set
                return new ErrorValue(e.getMessage());
            }
        }

        private void requireRestore(String key, java.util.function.Function<Object, Object> step) {
            boolean[] ran = {false};
            store.mutate(key, ValueType.VECTORSET, null, o -> {
                ran[0] = true;
                return step.apply(o);
            });
            if (!ran[0]) {
                throw new IllegalStateException("no vector set restore in progress for this key");
            }
        }
    }
}
