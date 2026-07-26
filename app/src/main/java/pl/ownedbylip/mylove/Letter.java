package pl.ownedbylip.mylove;

import java.util.List;

final class Letter {
    static final class Attachment {
        final String storagePath;
        final String mimeType;

        Attachment(String storagePath, String mimeType) {
            this.storagePath = storagePath;
            this.mimeType = mimeType;
        }
    }

    final long id;
    final String remoteId;
    final String title;
    final String preview;
    final String body;
    final String dateLabel;
    final String publishedAt;
    final boolean unread;
    final List<Attachment> attachments;

    Letter(long id, String remoteId, String title, String preview, String body,
           String dateLabel, String publishedAt, boolean unread,
           List<Attachment> attachments) {
        this.id = id;
        this.remoteId = remoteId;
        this.title = title;
        this.preview = preview;
        this.body = body;
        this.dateLabel = dateLabel;
        this.publishedAt = publishedAt;
        this.unread = unread;
        this.attachments = attachments;
    }
}
