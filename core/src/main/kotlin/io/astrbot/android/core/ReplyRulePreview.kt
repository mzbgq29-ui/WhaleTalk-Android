package io.astrbot.android.core

/** The allowed-list and wake checks for ordinary QQ messages in the 4.29.3 runtime. */
object ReplyRulePreview {
    enum class Result { ALLOWED, NOT_ALLOWED, NOT_AWAKENED }

    fun wakePrefixes(options: BigFishOptions): List<String> =
        if (options.groupMentionOnly) listOf("/") else listOf(options.wakeWord, "/")

    fun evaluate(
        owner: String,
        friends: String,
        groups: String,
        options: BigFishOptions,
        recipient: String,
        group: Boolean,
        mentionsBot: Boolean,
        message: String,
    ): Result {
        require(BigFishInput.parseIds(recipient).singleOrNull() == recipient)
        val allowed = if (group) recipient in BigFishInput.parseIds(groups)
            else recipient == owner.trim() || recipient in BigFishInput.parseIds(friends)
        if (!allowed) return Result.NOT_ALLOWED
        val awakened = (!group && !options.friendNeedsWake) || mentionsBot ||
            wakePrefixes(options).any { message.trimStart().startsWith(it) }
        return if (awakened) Result.ALLOWED else Result.NOT_AWAKENED
    }
}
