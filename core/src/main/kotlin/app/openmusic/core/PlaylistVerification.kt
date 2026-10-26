package app.openmusic.core

enum class VerificationDisplay { ANALYZING, AVAILABLE, REVIEW, AMBIGUOUS, MISSING }

/** A missing result is only authoritative after every local audio item was checked. */
object PlaylistVerification {
    fun display(status: MatchStatus?, analyzing: Boolean, complete: Boolean): VerificationDisplay {
        if (analyzing) return VerificationDisplay.ANALYZING
        if (status == MatchStatus.EXACT) return VerificationDisplay.AVAILABLE
        if (!complete) return VerificationDisplay.REVIEW
        return when (status) {
            MatchStatus.AMBIGUOUS -> VerificationDisplay.AMBIGUOUS
            MatchStatus.MISSING -> VerificationDisplay.MISSING
            else -> VerificationDisplay.REVIEW
        }
    }
}
