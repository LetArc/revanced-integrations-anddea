package app.revanced.integrations.youtube.patches.components;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.utils.ByteTrieSearch;
import app.revanced.integrations.youtube.utils.LogHelper;
import app.revanced.integrations.youtube.utils.StringTrieSearch;
import app.revanced.integrations.youtube.utils.TrieSearch;

abstract class FilterGroup<T> {
    protected final SettingsEnum setting;
    protected final T[] filters;

    /**
     * Initialize a new filter group.
     *
     * @param setting The associated setting.
     * @param filters The filters.
     */
    @SafeVarargs
    public FilterGroup(final SettingsEnum setting, final T... filters) {
        this.setting = setting;
        this.filters = filters;
        if (filters.length == 0) {
            throw new IllegalArgumentException("Must use one or more filter patterns (zero specified)");
        }
    }

    public boolean isEnabled() {
        return setting == null || setting.getBoolean();
    }

    /**
     * @return If {@link FilterGroupList} should exclude this group when searching.
     * By default, all filters are included except non enabled settings that require reboot.
     */
    public boolean excludeInSearch() {
        return !isEnabled() && setting.rebootApp;
    }

    @NonNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + (setting == null ? "(null setting)" : setting);
    }

    public abstract FilterGroupResult check(final T stack);

    final static class FilterGroupResult {
        private SettingsEnum setting;
        private int matchedIndex;
        private int matchedLength;
        // In the future it might be useful to include which pattern matched,
        // but for now that is not needed.

        FilterGroupResult() {
            this(null, -1, 0);
        }

        FilterGroupResult(SettingsEnum setting, int matchedIndex, int matchedLength) {
            setValues(setting, matchedIndex, matchedLength);
        }

        public void setValues(SettingsEnum setting, int matchedIndex, int matchedLength) {
            this.setting = setting;
            this.matchedIndex = matchedIndex;
            this.matchedLength = matchedLength;
        }

        /**
         * A null value if the group has no setting,
         * or if no match is returned from {@link FilterGroupList#check(Object)}.
         */
        public SettingsEnum getSetting() {
            return setting;
        }

        public boolean isFiltered() {
            return matchedIndex >= 0;
        }

        /**
         * Matched index of first pattern that matched, or -1 if nothing matched.
         */
        public int getMatchedIndex() {
            return matchedIndex;
        }

        /**
         * Length of the matched filter pattern.
         */
        @SuppressWarnings("unused")
        public int getMatchedLength() {
            return matchedLength;
        }
    }
}

class StringFilterGroup extends FilterGroup<String> {

    public StringFilterGroup(final SettingsEnum setting, final String... filters) {
        super(setting, filters);
    }

    @Override
    public FilterGroupResult check(final String string) {
        int matchedIndex = -1;
        int matchedLength = 0;
        if (isEnabled()) {
            for (String pattern : filters) {
                if (!string.isEmpty()) {
                    final int indexOf = pattern.indexOf(string);
                    if (indexOf >= 0) {
                        matchedIndex = indexOf;
                        matchedLength = pattern.length();
                        break;
                    }
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex, matchedLength);
    }
}

/**
 * If you have more than 1 filter patterns, then all instances of
 * this class should be filtered using {@link ByteArrayFilterGroupList#check(byte[])},
 * which uses a prefix tree to give better performance.
 */
class ByteArrayFilterGroup extends FilterGroup<byte[]> {

    private volatile int[][] failurePatterns;

    public ByteArrayFilterGroup(SettingsEnum setting, byte[]... filters) {
        super(setting, filters);
    }

    // Modified implementation from https://stackoverflow.com/a/1507813
    private static int indexOf(final byte[] data, final byte[] pattern, final int[] failure) {
        // Finds the first occurrence of the pattern in the byte array using
        // KMP matching algorithm.
        int patternLength = pattern.length;
        for (int i = 0, j = 0, dataLength = data.length; i < dataLength; i++) {
            while (j > 0 && pattern[j] != data[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == data[i]) {
                j++;
            }
            if (j == patternLength) {
                return i - patternLength + 1;
            }
        }
        return -1;
    }

    private static int[] createFailurePattern(byte[] pattern) {
        // Computes the failure function using a bootstrapping process,
        // where the pattern is matched against itself.
        final int patternLength = pattern.length;
        final int[] failure = new int[patternLength];

        for (int i = 1, j = 0; i < patternLength; i++) {
            while (j > 0 && pattern[j] != pattern[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == pattern[i]) {
                j++;
            }
            failure[i] = j;
        }
        return failure;
    }

    private synchronized void buildFailurePatterns() {
        if (failurePatterns != null)
            return; // Thread race and another thread already initialized the search.
        int[][] failurePatterns = new int[filters.length][];
        int i = 0;
        for (byte[] pattern : filters) {
            failurePatterns[i++] = createFailurePattern(pattern);
        }
        this.failurePatterns = failurePatterns; // Must set after initialization finishes.
    }

    @Override
    public FilterGroupResult check(final byte[] bytes) {
        int matchedLength = 0;
        int matchedIndex = -1;
        if (isEnabled()) {
            if (failurePatterns == null) {
                buildFailurePatterns(); // Lazy load.
            }
            for (int i = 0, length = filters.length; i < length; i++) {
                byte[] filter = filters[i];
                matchedIndex = indexOf(bytes, filter, failurePatterns[i]);
                if (matchedIndex >= 0) {
                    matchedLength = filter.length;
                    break;
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex, matchedLength);
    }
}


final class ByteArrayAsStringFilterGroup extends ByteArrayFilterGroup {

    public ByteArrayAsStringFilterGroup(SettingsEnum setting, String... filters) {
        super(setting, ByteTrieSearch.convertStringsToBytes(filters));
    }
}

abstract class FilterGroupList<V, T extends FilterGroup<V>> implements Iterable<T> {

    private final List<T> filterGroups = new ArrayList<>();
    /**
     * Search graph. Created only if needed.
     */
    private volatile TrieSearch<V> search;

    @SafeVarargs
    protected final void addAll(final T... groups) {
        filterGroups.addAll(Arrays.asList(groups));
        search = null; // Rebuild, if already created.
    }

    protected final synchronized void buildSearch() {
        // Since litho filtering is multithreaded, this method can be concurrently called by multiple threads.
        if (search != null)
            return; // Thread race and another thread already initialized the search.
        LogHelper.printDebug(() -> "Creating prefix search tree for: " + this);
        TrieSearch<V> search = createSearchGraph();
        for (T group : filterGroups) {
            if (group.excludeInSearch()) {
                continue;
            }
            for (V pattern : group.filters) {
                search.addPattern(pattern, (textSearched, matchedStartIndex, matchedLength, callbackParameter) -> {
                    if (group.isEnabled()) {
                        FilterGroup.FilterGroupResult result = (FilterGroup.FilterGroupResult) callbackParameter;
                        result.setValues(group.setting, matchedStartIndex, matchedLength);
                        return true;
                    }
                    return false;
                });
            }
        }
        this.search = search; // Must set after it's completely initialized.
    }

    @NonNull
    @Override
    public Iterator<T> iterator() {
        return filterGroups.iterator();
    }

    @Override
    public void forEach(@NonNull Consumer<? super T> action) {
        filterGroups.forEach(action);
    }

    @NonNull
    @Override
    public Spliterator<T> spliterator() {
        return filterGroups.spliterator();
    }

    protected FilterGroup.FilterGroupResult check(V stack) {
        if (search == null) {
            buildSearch(); // Lazy load.
        }
        FilterGroup.FilterGroupResult result = new FilterGroup.FilterGroupResult();
        search.matches(stack, result);
        return result;

    }

    protected abstract TrieSearch<V> createSearchGraph();
}

final class StringFilterGroupList extends FilterGroupList<String, StringFilterGroup> {
    protected StringTrieSearch createSearchGraph() {
        return new StringTrieSearch();
    }
}

/**
 * If searching for a single byte pattern, then it is slightly better to use
 * {@link ByteArrayFilterGroup#check(byte[])} as it uses KMP which is faster
 * than a prefix tree to search for only 1 pattern.
 */
final class ByteArrayFilterGroupList extends FilterGroupList<byte[], ByteArrayFilterGroup> {
    protected ByteTrieSearch createSearchGraph() {
        return new ByteTrieSearch();
    }
}

abstract class Filter {
    /**
     * All group filters must be set before the constructor call completes.
     * Otherwise {@link #isFiltered(String, String, String, byte[], FilterGroupList, FilterGroup, int)}
     * will never be called for any matches.
     */

    protected final StringFilterGroupList pathFilterGroupList = new StringFilterGroupList();
    protected final StringFilterGroupList identifierFilterGroupList = new StringFilterGroupList();
    protected final StringFilterGroupList allValueFilterGroupList = new StringFilterGroupList();

    /**
     * Called after an enabled filter has been matched.
     * Default implementation is to always filter the matched item.
     * Subclasses can perform additional or different checks if needed.
     * <p>
     * Method is called off the main thread.
     *
     * @param matchedList  The list the group filter belongs to.
     * @param matchedGroup The actual filter that matched.
     * @param matchedIndex Matched index of string/array.
     * @return True if the litho item should be filtered out.
     */
    @SuppressWarnings("rawtypes")
    boolean isFiltered(String path, @Nullable String identifier, String allValue, byte[] protobufBufferArray,
                       FilterGroupList matchedList, FilterGroup matchedGroup, int matchedIndex) {
        if (SettingsEnum.ENABLE_DEBUG_LOGGING.getBoolean()) {
            if (matchedList == identifierFilterGroupList) {
                LogHelper.printDebug(() -> getClass().getSimpleName() + " Filtered identifier: " + identifier);
            } else {
                LogHelper.printDebug(() -> getClass().getSimpleName() + " Filtered path: " + path);
            }
        }
        return true;
    }
}

@SuppressWarnings("unused")
public final class LithoFilterPatch {
    private static final Filter[] filters = new Filter[]{
            new DummyFilter() // Replaced by patch.
    };
    private static final StringTrieSearch pathSearchTree = new StringTrieSearch();
    private static final StringTrieSearch identifierSearchTree = new StringTrieSearch();
    private static final StringTrieSearch allValueSearchTree = new StringTrieSearch();

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /**
     * Because litho filtering is multithreaded and the buffer is passed in from a different injection point,
     * the buffer is saved to a ThreadLocal so each calling thread does not interfere with other threads.
     */
    private static final ThreadLocal<ByteBuffer> bufferThreadLocal = new ThreadLocal<>();

    static {
        for (Filter filter : filters) {
            filterGroupLists(identifierSearchTree, filter, filter.identifierFilterGroupList);
            filterGroupLists(pathSearchTree, filter, filter.pathFilterGroupList);
            filterGroupLists(allValueSearchTree, filter, filter.allValueFilterGroupList);
        }

        LogHelper.printDebug(() -> "Using: "
                + identifierSearchTree.numberOfPatterns() + " identifier filters"
                + " (" + identifierSearchTree.getEstimatedMemorySize() + " KB), "
                + pathSearchTree.numberOfPatterns() + " path filters"
                + " (" + pathSearchTree.getEstimatedMemorySize() + " KB)");
    }

    private static <T> void filterGroupLists(TrieSearch<T> pathSearchTree,
                                             Filter filter, FilterGroupList<T, ? extends FilterGroup<T>> list) {
        for (FilterGroup<T> group : list) {
            if (group.excludeInSearch()) {
                continue;
            }
            for (T pattern : group.filters) {
                pathSearchTree.addPattern(pattern, (textSearched, matchedStartIndex, matchedLength, callbackParameter) -> {
                            if (!group.isEnabled()) return false;
                            LithoFilterParameters parameters = (LithoFilterParameters) callbackParameter;
                            return filter.isFiltered(parameters.path, parameters.identifier, parameters.allValue, parameters.protoBuffer,
                                    list, group, matchedStartIndex);
                        }
                );
            }
        }
    }

    /**
     * Injection point.  Called off the main thread.
     */
    @SuppressWarnings("unused")
    public static void setProtoBuffer(@Nullable ByteBuffer protobufBuffer) {
        // Set the buffer to a thread local.  The buffer will remain in memory, even after the call to #filter completes.
        // This is intentional, as it appears the buffer can be set once and then filtered multiple times.
        // The buffer will be cleared from memory after a new buffer is set by the same thread,
        // or when the calling thread eventually dies.
        if (protobufBuffer == null) {
            // It appears the buffer can be cleared out just before the call to #filter()
            // Ignore this null value and retain the last buffer that was set.
            LogHelper.printDebug(() -> "Ignoring null protobuffer");
        } else {
            bufferThreadLocal.set(protobufBuffer);
        }
    }

    /**
     * Injection point.  Called off the main thread, and commonly called by multiple threads at the same time.
     */
    public static boolean filter(@NonNull StringBuilder pathBuilder, @Nullable String identifier, @NonNull Object object) {
        try {
            // It is assumed that protobufBuffer is empty as well in this case.
            if (pathBuilder.length() == 0)
                return false;

            ByteBuffer protobufBuffer = bufferThreadLocal.get();
            final byte[] bufferArray;
            // Potentially the buffer may have been null or never set up until now.
            // Use an empty buffer so the litho id/path filters still work correctly.
            if (protobufBuffer == null) {
                LogHelper.printDebug(() -> "Proto buffer is null, using an empty buffer array");
                bufferArray = EMPTY_BYTE_ARRAY;
            } else if (!protobufBuffer.hasArray()) {
                LogHelper.printDebug(() -> "Proto buffer does not have an array, using an empty buffer array");
                bufferArray = EMPTY_BYTE_ARRAY;
            } else {
                bufferArray = protobufBuffer.array();
            }

            LithoFilterParameters parameter = new LithoFilterParameters(pathBuilder.toString(), identifier, object.toString(), bufferArray);
            LogHelper.printDebug(() -> "Searching " + parameter);

            if (parameter.identifier != null) {
                if (identifierSearchTree.matches(parameter.identifier, parameter)) return true;
            }
            if (pathSearchTree.matches(parameter.path, parameter)) return true;
            if (allValueSearchTree.matches(parameter.allValue, parameter)) return true;
        } catch (Exception ex) {
            LogHelper.printException(() -> "Litho filter failure", ex);
        }

        return false;
    }

    /**
     * Simple wrapper to pass the litho parameters through the prefix search.
     */
    private static final class LithoFilterParameters {
        @Nullable
        final String identifier;
        final String path;
        final String allValue;
        final byte[] protoBuffer;

        LithoFilterParameters(String lithoPath, @Nullable String lithoIdentifier, String allValues, byte[] bufferArray) {
            this.path = lithoPath;
            this.identifier = lithoIdentifier;
            this.allValue = allValues;
            this.protoBuffer = bufferArray;
        }

        /**
         * Search through a byte array for all ASCII strings.
         */
        private static void findAsciiStrings(StringBuilder builder, byte[] buffer) {
            // Valid ASCII values (ignore control characters).
            final int minimumAscii = 32;  // 32 = space character
            final int maximumAscii = 126; // 127 = delete character
            final int minimumAsciiStringLength = 4; // Minimum length of an ASCII string to include.
            String delimitingCharacter = "❙"; // Non ascii character, to allow easier log filtering.

            final int length = buffer.length;
            int start = 0;
            int end = 0;
            while (end < length) {
                int value = buffer[end];
                if (value < minimumAscii || value > maximumAscii || end == length - 1) {
                    if (end - start >= minimumAsciiStringLength) {
                        for (int i = start; i < end; i++) {
                            builder.append((char) buffer[i]);
                        }
                        builder.append(delimitingCharacter);
                    }
                    start = end + 1;
                }
                end++;
            }
        }

        @NonNull
        @Override
        public String toString() {
            // Estimate the percentage of the buffer that are Strings.
            StringBuilder builder = new StringBuilder(Math.max(100, protoBuffer.length / 2));
            builder.append("\nID: ");
            builder.append(identifier);
            builder.append("\nPath: ");
            builder.append(path);

            if (SettingsEnum.ENABLE_DEBUG_BUFFER_LOGGING.getBoolean()) {
                builder.append("\nBufferStrings: ");
                findAsciiStrings(builder, protoBuffer);
            }

            return builder.toString();
        }
    }
}
