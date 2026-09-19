package ai.withmurph.companion.core

interface SentMealHistory {
    suspend fun load(memberKey: String): List<SentMealPhoto>
    suspend fun append(memberKey: String, photo: SentMealPhoto, isCurrent: () -> Boolean)
    suspend fun clear()
}

object NoopSentMealHistory : SentMealHistory {
    override suspend fun load(memberKey: String): List<SentMealPhoto> = emptyList()
    override suspend fun append(memberKey: String, photo: SentMealPhoto, isCurrent: () -> Boolean) = Unit
    override suspend fun clear() = Unit
}
