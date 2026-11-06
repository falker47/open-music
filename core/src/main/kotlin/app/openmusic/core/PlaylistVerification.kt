package app.openmusic.core

enum class VerificationDisplay { ANALYZING, AVAILABLE, REVIEW, AMBIGUOUS, MISSING }

/** Show proven SHA matches immediately; absence is final only after a complete scan. */
object PlaylistVerification {
    fun display(status: MatchStatus?, analyzing: Boolean, complete: Boolean): VerificationDisplay {
        if (status == MatchStatus.EXACT) return VerificationDisplay.AVAILABLE
        if (analyzing) return VerificationDisplay.ANALYZING
        if (!complete) return VerificationDisplay.REVIEW
        return when (status) {
            MatchStatus.AMBIGUOUS -> VerificationDisplay.AMBIGUOUS
            MatchStatus.MISSING -> VerificationDisplay.MISSING
            else -> VerificationDisplay.REVIEW
        }
    }
}
