package com.golda.patchertiktok;

final class SeekbarPolicy {
    private SeekbarPolicy() {
    }

    /** Type 3 (hidden, no dragging) becomes 4: invisible, but the bottom edge still seeks. */
    static int normalizeShowType(int requestedType) {
        return requestedType == 3 ? 4 : requestedType;
    }

    /**
     * TikTok logs why it hides the bar. Only "can not drag" (2), "can not show progressbar" (4, short clips)
     * and the inverse experiment (6) are lifted; the feed not being on screen, stories, masks and the like stay.
     */
    static boolean liftable(String reason) {
        return reason == null || reason.contains("state: 2,") || reason.contains("state: 4,")
                || reason.contains("state: 6,");
    }

    static boolean shouldForceShow(
            boolean originalResult,
            boolean hasVideo,
            boolean isAd,
            boolean isLive,
            boolean isPhoto
    ) {
        return originalResult || (hasVideo && !isAd && !isLive && !isPhoto);
    }
}
