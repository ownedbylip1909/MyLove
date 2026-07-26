package pl.ownedbylip.mylove;

final class Letter {
    final long id;
    final String remoteId;
    final String title;
    final String preview;
    final String body;
    final String dateLabel;
    final String publishedAt;
    final boolean unread;

    Letter(long id, String remoteId, String title, String preview, String body,
           String dateLabel, String publishedAt, boolean unread) {
        this.id = id;
        this.remoteId = remoteId;
        this.title = title;
        this.preview = preview;
        this.body = body;
        this.dateLabel = dateLabel;
        this.publishedAt = publishedAt;
        this.unread = unread;
    }
}
