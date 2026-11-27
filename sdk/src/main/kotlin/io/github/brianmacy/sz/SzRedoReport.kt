package io.github.brianmacy.sz

/**
 * The outcome of draining the redo queue ([SzAsyncEngine.processRedoRecords]).
 *
 * @property processed How many redo records were processed.
 * @property failed The redo records that failed, in the order they were fetched.
 */
public data class SzRedoReport(
    public val processed: Int = 0,
    public val failed: List<Failure> = emptyList(),
) {
    /**
     * One redo record that failed to process, with its error.
     *
     * @property redoRecord The redo record, already removed from the engine's redo queue.
     * @property error Why processing it failed.
     */
    public data class Failure(
        public val redoRecord: String,
        public val error: SzException,
    )

    internal companion object {
        /**
         * Fetches redo records with [next] and processes each with [process] until [next]
         * returns null, collecting the failures.
         */
        fun drain(
            next: () -> String?,
            process: (String) -> Unit,
        ): SzRedoReport {
            var processed = 0
            val failed = mutableListOf<Failure>()
            while (true) {
                val redo = next() ?: break
                try {
                    process(redo)
                    processed += 1
                } catch (e: SzException) {
                    failed += Failure(redo, e)
                }
            }
            return SzRedoReport(processed, failed)
        }
    }
}
