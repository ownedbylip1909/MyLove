package pl.ownedbylip.mylove;

final class Letter {
    final long id;
    final String remoteId;
    final String title;
    final String preview;
    final String body;
    final String dateLabel;
    final boolean unread;

    Letter(long id, String remoteId, String title, String preview, String body, String dateLabel, boolean unread) {
        this.id = id;
        this.remoteId = remoteId;
        this.title = title;
        this.preview = preview;
        this.body = body;
        this.dateLabel = dateLabel;
        this.unread = unread;
    }
}
