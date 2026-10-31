package me.niteshh.redcake.json;

import java.util.ArrayList;
import java.util.List;

/**
 * A path into a JSON document, in the two dialects RedisJSON accepts.
 *
 * <ul>
 *   <li><b>JSONPath</b> - starts with {@code $}. May select any number of values: {@code $.a.b}, {@code $['a b']},
 *       {@code $.items[0]}, {@code $.items[-1]}, {@code $.items[*]}, {@code $.a.*}, {@code $..name} (any depth),
 *       {@code $.items[1:3]}, {@code $.items[::2]}, {@code $['a','b']}, {@code $[0,2]} and filters such as
 *       {@code $.items[?(@.price < 10 && @.status == "ok")]}. Replies to a JSONPath are arrays, one entry per match.</li>
 *   <li><b>Legacy</b> - starts with {@code .} or a name: {@code .}, {@code .a.b}, {@code a.b[0]}. Only
 *       definite paths (names and indexes), and it selects one value; replies are that value and a missing value is
 *       an error.</li>
 * </ul>
 * Member names with other characters go in brackets: {@code $["a.b"]} (single or double quotes, JSON escapes).
 *
 * <p>{@link #select} returns the matches in document order together with where each sits, so that commands can
 * change them. Paths are untrusted input, so they are bounded: a limit on length and on the nesting of filter
 * expressions, and one budget of work per evaluation that nested filters share (a path like {@code $..*..*..*..*}
 * is otherwise polynomial in the size of the document, and nested filters exponential).
 */
public final class JsonPath {

    /** Paths longer than this are refused. */
    static final int MAX_LENGTH = 8192;
    private static final int MAX_EXPRESSION_DEPTH = 32;
    /** Nodes one evaluation may visit before it is abandoned. */
    static final long MAX_WORK = 10_000_000;

    /**
     * One value a path selected.
     *
     * @param parent the object or array that holds it, {@code null} for the root
     * @param key    the member name ({@link String}) or element index ({@link Integer}) in {@code parent}
     * @param depth  how many levels below the root it sits
     */
    public record Match(Object parent, Object key, Object value, int depth) {
        public boolean isRoot() {
            return parent == null;
        }
    }

    // ---- the syntax tree -----------------------------------------------------------------------------------------

    sealed interface Selector permits Name, Index, Wildcard, Slice, Filter {
    }

    record Name(String name) implements Selector {
    }

    record Index(long index) implements Selector {
    }

    enum Wildcard implements Selector {
        INSTANCE
    }

    /** {@code [start:end:step]}; a missing bound is {@code null}. */
    record Slice(Long start, Long end, long step) implements Selector {
    }

    record Filter(Expression expression) implements Selector {
    }

    /** One segment of a path: selectors applied to the children of a node, or of every descendant ({@code ..}). */
    record Step(boolean recursive, List<Selector> selectors) {
        boolean isDefinite() {
            return !recursive && selectors.size() == 1 && (selectors.get(0) instanceof Name || selectors.get(0) instanceof Index);
        }
    }

    private final String text;
    private final boolean legacy;
    private final List<Step> steps;

    private JsonPath(String text, boolean legacy, List<Step> steps) {
        this.text = text;
        this.legacy = legacy;
        this.steps = steps;
    }

    /**
     * @throws JsonException if {@code text} is not a valid path
     */
    public static JsonPath parse(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new JsonException("invalid path: longer than " + MAX_LENGTH + " bytes");
        }
        return new PathParser(text).parsePath();
    }

    public String text() {
        return text;
    }

    /** @return {@code true} for the legacy dialect: one value, replies without the surrounding array */
    public boolean isLegacy() {
        return legacy;
    }

    /** @return {@code true} for {@code $} and {@code .} */
    public boolean isRoot() {
        return steps.isEmpty();
    }

    /** @return {@code true} if the path can select at most one value: only names and indexes */
    public boolean isDefinite() {
        for (Step step : steps) {
            if (!step.isDefinite()) {
                return false;
            }
        }
        return true;
    }

    /** @return this path without its last segment (the root path if there is only one) */
    public JsonPath parent() {
        return new JsonPath(text, legacy, steps.isEmpty() ? steps : steps.subList(0, steps.size() - 1));
    }

    /** @return the member name a value could be added under, if the last segment is exactly one plain name */
    public String lastName() {
        if (steps.isEmpty()) {
            return null;
        }
        Step last = steps.get(steps.size() - 1);
        return !last.recursive() && last.selectors().size() == 1 && last.selectors().get(0) instanceof Name n ? n.name() : null;
    }

    @Override
    public String toString() {
        return text;
    }

    // ---- evaluation ---------------------------------------------------------------------------------------------

    /**
     * @return every value the path selects in {@code root}, in document order (empty if none)
     * @throws JsonException if the evaluation would take more than {@link #MAX_WORK} steps
     */
    public List<Match> select(Object root) {
        return select(root, root, new Budget());
    }

    /** The work one top-level evaluation may do, shared with the paths nested filters evaluate. */
    private static final class Budget {
        private long work;

        void spend() {
            if (++work > MAX_WORK) {
                throw new JsonException("the path is too expensive to evaluate on this document");
            }
        }
    }

    private List<Match> select(Object start, Object root, Budget budget) {
        List<Match> out = new ArrayList<>();
        new Evaluation(steps, root, out, budget).walk(0, null, null, start, 0);
        return out;
    }

    /** One run of a path over one document. */
    private static final class Evaluation {
        private final List<Step> steps;
        private final Object root;
        private final List<Match> out;
        private final Budget budget;

        Evaluation(List<Step> steps, Object root, List<Match> out, Budget budget) {
            this.steps = steps;
            this.root = root;
            this.out = out;
            this.budget = budget;
        }

        void walk(int step, Object parent, Object key, Object node, int depth) {
            if (step == steps.size()) {
                out.add(new Match(parent, key, node, depth));
                return;
            }
            Step current = steps.get(step);
            if (current.recursive()) {
                descend(step, current.selectors(), node, depth);
            } else {
                apply(step, current.selectors(), node, depth);
            }
        }

        /** The selectors on the node and on every node below it, parents before children. */
        private void descend(int step, List<Selector> selectors, Object node, int depth) {
            apply(step, selectors, node, depth);
            if (node instanceof JsonObject object) {
                object.forEach((k, v) -> {
                    if (v instanceof JsonObject || v instanceof JsonArray) {
                        descend(step, selectors, v, depth + 1);
                    }
                });
            } else if (node instanceof JsonArray array && !array.isPacked()) {
                for (int i = 0, n = array.size(); i < n; i++) {
                    Object v = array.get(i);
                    if (v instanceof JsonObject || v instanceof JsonArray) {
                        descend(step, selectors, v, depth + 1);
                    }
                }
            }
        }

        /** Selects children of {@code node}, then continues with the rest of the path from each. */
        private void apply(int step, List<Selector> selectors, Object node, int depth) {
            budget.spend();
            for (Selector selector : selectors) {
                switch (selector) {
                    case Name name -> {
                        if (node instanceof JsonObject object) {
                            Object child = object.get(name.name());
                            if (child != null) {
                                walk(step + 1, node, name.name(), child, depth + 1);
                            }
                        }
                    }
                    case Index index -> {
                        if (node instanceof JsonArray array) {
                            long at = index.index() < 0 ? array.size() + index.index() : index.index();
                            if (at >= 0 && at < array.size()) {
                                walk(step + 1, node, (int) at, array.get((int) at), depth + 1);
                            }
                        }
                    }
                    case Wildcard ignored -> children(step, node, depth, null);
                    case Slice slice -> {
                        if (node instanceof JsonArray array) {
                            slice(step, array, slice, depth);
                        }
                    }
                    case Filter filter -> children(step, node, depth, filter.expression());
                }
            }
        }

        /** Every child of {@code node}, or those for which {@code filter} holds. */
        private void children(int step, Object node, int depth, Expression filter) {
            if (node instanceof JsonObject object) {
                object.forEach((k, v) -> {
                    if (filter == null || filter.test(v, root, budget)) {
                        walk(step + 1, node, k, v, depth + 1);
                    }
                });
            } else if (node instanceof JsonArray array) {
                for (int i = 0, n = array.size(); i < n; i++) {
                    Object v = array.get(i);
                    if (filter == null || filter.test(v, root, budget)) {
                        walk(step + 1, node, i, v, depth + 1);
                    }
                }
            }
        }

        /** RFC 9535 slice semantics: negative bounds count from the end, a negative stride walks backwards. */
        private void slice(int step, JsonArray array, Slice slice, int depth) {
            long stride = slice.step();
            if (stride == 0) {
                return;
            }
            long len = array.size();
            if (stride > 0) {
                long lower = clamp(normalize(slice.start() == null ? 0 : slice.start(), len), 0, len);
                long upper = clamp(normalize(slice.end() == null ? len : slice.end(), len), 0, len);
                for (long i = lower; i < upper; i += stride) {
                    walk(step + 1, array, (int) i, array.get((int) i), depth + 1);
                }
            } else {
                long upper = clamp(normalize(slice.start() == null ? len - 1 : slice.start(), len), -1, len - 1);
                long lower = clamp(normalize(slice.end() == null ? -len - 1 : slice.end(), len), -1, len - 1);
                for (long i = upper; i > lower; i += stride) {
                    walk(step + 1, array, (int) i, array.get((int) i), depth + 1);
                }
            }
        }

        private static long normalize(long index, long len) {
            return index >= 0 ? index : len + index;
        }

        private static long clamp(long value, long min, long max) {
            return Math.min(Math.max(value, min), max);
        }
    }

    // ---- filter expressions --------------------------------------------------------------------------------------

    /** A filter's condition on one candidate child ({@code @}), with the document root ({@code $}) at hand. */
    @FunctionalInterface
    interface Expression {
        boolean test(Object current, Object root, Budget budget);
    }

    /** Stands for "no value" when a path inside a filter selects nothing. */
    private static final Object MISSING = new Object();

    private enum Comparison {EQ, NE, LT, LE, GT, GE}

    /** A value in a filter: a literal, or a path relative to the candidate ({@code @...}) or to the root ({@code $...}). */
    private interface Operand {
        Object resolve(Object current, Object root, Budget budget);
    }

    private record Literal(Object value) implements Operand {
        @Override
        public Object resolve(Object current, Object root, Budget budget) {
            return value;
        }
    }

    private record PathOperand(boolean absolute, JsonPath path) implements Operand {
        List<Match> select(Object current, Object root, Budget budget) {
            return path.select(absolute ? root : current, root, budget);
        }

        /** The single value the path selects, or {@link #MISSING}. */
        @Override
        public Object resolve(Object current, Object root, Budget budget) {
            List<Match> matches = select(current, root, budget);
            return matches.isEmpty() ? MISSING : matches.get(0).value();
        }
    }

    private static boolean equalValues(Object a, Object b) {
        if (a == MISSING || b == MISSING) {
            return a == b; // two empty results are equal, as in RFC 9535
        }
        return Json.equal(a, b);
    }

    private static boolean less(Object a, Object b) {
        if (Json.isNumber(a) && Json.isNumber(b)) {
            return ((Number) a).doubleValue() < ((Number) b).doubleValue();
        }
        return a instanceof String x && b instanceof String y && x.compareTo(y) < 0;
    }

    private static boolean compare(Object a, Comparison op, Object b) {
        return switch (op) {
            case EQ -> equalValues(a, b);
            case NE -> !equalValues(a, b);
            case LT -> less(a, b);
            case LE -> less(a, b) || equalValues(a, b);
            case GT -> less(b, a);
            case GE -> less(b, a) || equalValues(a, b);
        };
    }

    // ---- the parser -----------------------------------------------------------------------------------------------

    private static final class PathParser {
        private final String s;
        private final int n;
        private int pos;
        private int expressionDepth;

        PathParser(String text) {
            this.s = text;
            this.n = text.length();
        }

        JsonException fail(String reason) {
            return new JsonException("invalid path '" + (n > 80 ? s.substring(0, 80) + "..." : s) + "': " + reason);
        }

        JsonPath parsePath() {
            if (n == 0) {
                throw fail("it is empty");
            }
            boolean legacy = s.charAt(0) != '$';
            List<Step> steps = new ArrayList<>();
            if (!legacy) {
                pos = 1;
                steps = parseSegments();
            } else if (s.equals(".")) {
                pos = 1; // the root
            } else {
                if (s.charAt(0) != '.' && s.charAt(0) != '[') {
                    steps.add(new Step(false, List.of(parseDotSelector()))); // a bare first name: "a.b"
                }
                steps.addAll(parseSegments());
            }
            if (pos != n) {
                throw fail("unexpected '" + s.charAt(pos) + "' at position " + pos);
            }
            if (legacy) {
                for (Step step : steps) {
                    if (!step.isDefinite()) {
                        throw fail("a path that does not start with $ may only use names and indexes; use a JSONPath ($...)");
                    }
                }
            }
            return new JsonPath(s, legacy, steps);
        }

        /** Reads segments ({@code .name}, {@code .*}, {@code ..x}, {@code [...]}) from here while there are any. */
        private List<Step> parseSegments() {
            List<Step> steps = new ArrayList<>();
            while (pos < n) {
                char c = s.charAt(pos);
                if (c == '.') {
                    if (pos + 1 < n && s.charAt(pos + 1) == '.') {
                        pos += 2;
                        steps.add(new Step(true, parseAfterDescent()));
                    } else {
                        pos++;
                        steps.add(new Step(false, List.of(parseDotSelector())));
                    }
                } else if (c == '[') {
                    steps.add(new Step(false, parseBracket()));
                } else {
                    break;
                }
            }
            return steps;
        }

        private Selector parseDotSelector() {
            if (pos < n && s.charAt(pos) == '*') {
                pos++;
                return Wildcard.INSTANCE;
            }
            int start = pos;
            while (pos < n && isNameChar(s.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw fail("expected a member name at position " + pos);
            }
            return new Name(KeyPool.intern(s.substring(start, pos)));
        }

        private List<Selector> parseAfterDescent() {
            if (pos < n && s.charAt(pos) == '[') {
                return parseBracket();
            }
            return List.of(parseDotSelector());
        }

        private static boolean isNameChar(char c) {
            return c == '_' || c == '-' || c == '$' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c >= 0x80;
        }

        private void skipSpaces() {
            while (pos < n && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) {
                pos++;
            }
        }

        private List<Selector> parseBracket() {
            pos++; // [
            List<Selector> selectors = new ArrayList<>();
            while (true) {
                skipSpaces();
                if (pos >= n) {
                    throw fail("a [ is never closed");
                }
                char c = s.charAt(pos);
                if (c == '*') {
                    pos++;
                    selectors.add(Wildcard.INSTANCE);
                } else if (c == '?') {
                    pos++;
                    selectors.add(new Filter(parseOr()));
                } else if (c == '\'' || c == '"') {
                    selectors.add(new Name(KeyPool.intern(parseQuoted(c))));
                } else if (c == '-' || c == ':' || (c >= '0' && c <= '9')) {
                    selectors.add(parseIndexOrSlice());
                } else {
                    throw fail("unexpected '" + c + "' in brackets at position " + pos);
                }
                skipSpaces();
                if (pos < n && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                break;
            }
            if (pos >= n || s.charAt(pos) != ']') {
                throw fail("expected ']' at position " + pos);
            }
            pos++;
            return selectors;
        }

        private Selector parseIndexOrSlice() {
            Long first = parseOptionalInteger();
            skipSpaces();
            if (pos < n && s.charAt(pos) == ':') {
                pos++;
                skipSpaces();
                Long second = parseOptionalInteger();
                skipSpaces();
                Long stride = null;
                if (pos < n && s.charAt(pos) == ':') {
                    pos++;
                    skipSpaces();
                    stride = parseOptionalInteger();
                }
                return new Slice(first, second, stride == null ? 1 : stride);
            }
            if (first == null) {
                throw fail("expected an index at position " + pos);
            }
            return new Index(first);
        }

        private Long parseOptionalInteger() {
            int start = pos;
            if (pos < n && s.charAt(pos) == '-') {
                pos++;
            }
            int digits = pos;
            while (pos < n && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == digits) {
                pos = start;
                return null;
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException tooBig) {
                return s.charAt(start) == '-' ? Long.MIN_VALUE : Long.MAX_VALUE; // beyond any array: selects nothing
            }
        }

        /** A quoted name with JSON escapes; the result is a byte string like every other text. */
        private String parseQuoted(char quote) {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < n) {
                char c = s.charAt(pos++);
                if (c == quote) {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= n) {
                    break;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\', '/', '\'', '"' -> out.append(e);
                    case 'u' -> {
                        int cp = hex4();
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            if (pos + 1 < n && s.charAt(pos) == '\\' && s.charAt(pos + 1) == 'u') {
                                pos += 2;
                                int low = hex4();
                                if (low < 0xDC00 || low > 0xDFFF) {
                                    throw fail("an unpaired surrogate in a name");
                                }
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                            } else {
                                throw fail("an unpaired surrogate in a name");
                            }
                        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                            throw fail("an unpaired surrogate in a name");
                        }
                        appendUtf8(out, cp);
                    }
                    default -> throw fail("invalid escape \\" + e);
                }
            }
            throw fail("a quoted name is never closed");
        }

        private int hex4() {
            if (pos + 4 > n) {
                throw fail("an incomplete \\u escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s.charAt(pos + i), 16);
                if (digit < 0) {
                    throw fail("an invalid \\u escape");
                }
                value = (value << 4) | digit;
            }
            pos += 4;
            return value;
        }

        private static void appendUtf8(StringBuilder out, int cp) {
            if (cp < 0x80) {
                out.append((char) cp);
            } else if (cp < 0x800) {
                out.append((char) (0xC0 | (cp >> 6))).append((char) (0x80 | (cp & 0x3F)));
            } else if (cp < 0x10000) {
                out.append((char) (0xE0 | (cp >> 12))).append((char) (0x80 | ((cp >> 6) & 0x3F)))
                        .append((char) (0x80 | (cp & 0x3F)));
            } else {
                out.append((char) (0xF0 | (cp >> 18))).append((char) (0x80 | ((cp >> 12) & 0x3F)))
                        .append((char) (0x80 | ((cp >> 6) & 0x3F))).append((char) (0x80 | (cp & 0x3F)));
            }
        }

        // ---- filter expressions: or > and > not > comparison / test ------------------------------------------------

        private Expression parseOr() {
            if (++expressionDepth > MAX_EXPRESSION_DEPTH) {
                throw fail("filter expressions are nested too deeply");
            }
            Expression left = parseAnd();
            skipSpaces();
            while (s.startsWith("||", pos)) {
                pos += 2;
                Expression l = left;
                Expression right = parseAnd();
                left = (current, root, budget) -> l.test(current, root, budget) || right.test(current, root, budget);
                skipSpaces();
            }
            expressionDepth--;
            return left;
        }

        private Expression parseAnd() {
            Expression left = parseUnary();
            skipSpaces();
            while (s.startsWith("&&", pos)) {
                pos += 2;
                Expression l = left;
                Expression right = parseUnary();
                left = (current, root, budget) -> l.test(current, root, budget) && right.test(current, root, budget);
                skipSpaces();
            }
            return left;
        }

        private Expression parseUnary() {
            skipSpaces();
            if (pos < n && s.charAt(pos) == '!' && !s.startsWith("!=", pos)) {
                pos++;
                Expression inner = parseUnary();
                return (current, root, budget) -> !inner.test(current, root, budget);
            }
            if (pos < n && s.charAt(pos) == '(') {
                pos++;
                Expression inner = parseOr();
                skipSpaces();
                if (pos >= n || s.charAt(pos) != ')') {
                    throw fail("expected ')' at position " + pos);
                }
                pos++;
                return inner;
            }
            return parseComparison();
        }

        private Expression parseComparison() {
            Operand left = parseOperand();
            skipSpaces();
            Comparison op = parseComparisonOperator();
            if (op == null) {
                if (!(left instanceof PathOperand path)) {
                    throw fail("a filter test must be a path or a comparison, at position " + pos);
                }
                return (current, root, budget) -> !path.select(current, root, budget).isEmpty(); // selects anything?
            }
            skipSpaces();
            Operand a = requireSingular(left);
            Operand b = requireSingular(parseOperand());
            return (current, root, budget) ->
                    compare(a.resolve(current, root, budget), op, b.resolve(current, root, budget));
        }

        private Comparison parseComparisonOperator() {
            Comparison op = null;
            int length = 2;
            if (s.startsWith("==", pos)) {
                op = Comparison.EQ;
            } else if (s.startsWith("!=", pos)) {
                op = Comparison.NE;
            } else if (s.startsWith("<=", pos)) {
                op = Comparison.LE;
            } else if (s.startsWith(">=", pos)) {
                op = Comparison.GE;
            } else if (s.startsWith("<", pos)) {
                op = Comparison.LT;
                length = 1;
            } else if (s.startsWith(">", pos)) {
                op = Comparison.GT;
                length = 1;
            }
            if (op != null) {
                pos += length;
            }
            return op;
        }

        /** A path that is compared must select at most one value, otherwise the comparison has no meaning. */
        private Operand requireSingular(Operand operand) {
            if (operand instanceof PathOperand p && !p.path().isDefinite()) {
                throw fail("a path compared inside a filter must select a single value (names and indexes only)");
            }
            return operand;
        }

        private Operand parseOperand() {
            skipSpaces();
            if (pos >= n) {
                throw fail("a filter ends unexpectedly");
            }
            char c = s.charAt(pos);
            if (c == '@' || c == '$') {
                pos++;
                return new PathOperand(c == '$', new JsonPath(c + "...", false, parseSegments()));
            }
            if (c == '\'' || c == '"') {
                return new Literal(parseQuoted(c));
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                int start = pos;
                pos++;
                while (pos < n && (Character.isDigit(s.charAt(pos)) || ".eE+-".indexOf(s.charAt(pos)) >= 0)) {
                    pos++;
                }
                String number = s.substring(start, pos);
                try {
                    return new Literal(JsonParser.parse(number).value());
                } catch (JsonException e) {
                    throw fail("'" + number + "' is not a number");
                }
            }
            for (String word : new String[]{"true", "false", "null"}) {
                if (s.startsWith(word, pos)) {
                    pos += word.length();
                    return new Literal(word.equals("null") ? Json.NULL : Boolean.valueOf(word));
                }
            }
            throw fail("unexpected '" + c + "' in a filter at position " + pos);
        }
    }
}
